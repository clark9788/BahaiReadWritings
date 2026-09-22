package com.bahairesearch.bahaireadwritings

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.view.ScaleGestureDetector
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.bahairesearch.bahaireadwritings.data.Bookmark
import com.bahairesearch.bahaireadwritings.data.BookmarkDatabase
import com.bahairesearch.bahaireadwritings.data.BookmarkDao
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class ReaderActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bookmarkDao: BookmarkDao
    private lateinit var prefs: SharedPreferences
    private lateinit var scaleDetector: ScaleGestureDetector

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var filename = ""
    private var savedAnchorId: String? = null
    private var pageLoaded = false

    // Text size, as a WebView text-zoom percentage. The corpus CSS sizes all text in
    // `rem` units off `html{font-size:62.5%}`, so this scales the whole page cleanly.
    private var textZoom = DEFAULT_TEXT_ZOOM

    // Accumulates the pinch gesture as a float so sub-step pinches aren't lost to
    // rounding before the applied zoom is allowed to change.
    private var pendingZoom = DEFAULT_TEXT_ZOOM.toFloat()

    // Anchor nearest the top of the viewport, used to keep the reader's place while
    // the page reflows under a text-size change.
    private var topAnchorId: String? = null

    // JS to find the nearest paragraph anchor at or above the viewport midpoint.
    private val findAnchorJs = """
        (function(){
            var mid = window.scrollY + window.innerHeight / 2;
            var best = null, dist = Infinity;
            document.querySelectorAll('[id]').forEach(function(el) {
                var t = el.getBoundingClientRect().top + window.scrollY;
                if (t <= mid && (mid - t) < dist) { dist = mid - t; best = el.id; }
            });
            return best;
        })()
    """.trimIndent()

    // JS to find the nearest anchor at or above the top of the viewport.
    private val findTopAnchorJs = """
        (function(){
            var top = window.scrollY;
            var best = null, bestTop = -Infinity;
            document.querySelectorAll('[id]').forEach(function(el) {
                var t = el.getBoundingClientRect().top + window.scrollY;
                if (t <= top + 4 && t > bestTop) { bestTop = t; best = el.id; }
            });
            return best;
        })()
    """.trimIndent()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        filename = intent.getStringExtra(MainActivity.EXTRA_FILENAME) ?: ""
        val title = intent.getStringExtra(MainActivity.EXTRA_TITLE) ?: ""

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.apply {
            this.title = title
            setDisplayHomeAsUpEnabled(true)
        }

        webView = findViewById(R.id.webView)
        bookmarkDao = BookmarkDatabase.getInstance(this).bookmarkDao()

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        textZoom = prefs.getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM)
            .coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        pendingZoom = textZoom.toFloat()

        webView.settings.javaScriptEnabled = true
        // Pinch is handled here as a text-size gesture, not as WebView page zoom.
        webView.settings.setSupportZoom(false)
        webView.settings.textZoom = textZoom

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                if (!pageLoaded) {
                    pageLoaded = true
                    savedAnchorId?.let { anchorId ->
                        webView.evaluateJavascript(
                            "document.getElementById('$anchorId')?.scrollIntoView({block:'center'})",
                            null
                        )
                    }
                }
            }
        }

        webView.setOnLongClickListener {
            markPosition()
            true
        }

        setUpTextSizeGesture()

        executor.execute {
            val bookmark = bookmarkDao.get(filename)
            handler.post {
                savedAnchorId = bookmark?.anchorId
                loadPage()
            }
        }
    }

    /**
     * Two-finger spread grows the text and pinch shrinks it. The listener returns false
     * for ordinary events so one-finger scrolling and long-press still reach the WebView,
     * and consumes only the moves belonging to an active pinch.
     */
    private fun setUpTextSizeGesture() {
        scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                    pendingZoom = textZoom.toFloat()
                    captureTopAnchor()
                    return true
                }

                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    pendingZoom = (pendingZoom * detector.scaleFactor)
                        .coerceIn(MIN_TEXT_ZOOM.toFloat(), MAX_TEXT_ZOOM.toFloat())
                    val snapped = snapToStep(pendingZoom)
                    if (snapped != textZoom) applyTextZoom(snapped, preservePosition = true)
                    return true
                }

                override fun onScaleEnd(detector: ScaleGestureDetector) {
                    invalidateOptionsMenu()
                    showTextSizeSnackbar()
                }
            }
        ).apply {
            // Only a genuine two-finger pinch should change the size.
            isQuickScaleEnabled = false
        }

        webView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            scaleDetector.isInProgress
        }
    }

    private fun captureTopAnchor(onCaptured: (() -> Unit)? = null) {
        webView.evaluateJavascript(findTopAnchorJs) { result ->
            topAnchorId = result?.removeSurrounding("\"")?.takeIf { it != "null" }
            onCaptured?.invoke()
        }
    }

    private fun applyTextZoom(percent: Int, preservePosition: Boolean) {
        textZoom = percent
        webView.settings.textZoom = percent
        prefs.edit().putInt(KEY_TEXT_ZOOM, percent).apply()
        if (preservePosition) {
            topAnchorId?.let { anchorId ->
                // Wait for the reflow this zoom triggers before restoring the position.
                webView.evaluateJavascript(
                    "requestAnimationFrame(function(){" +
                        "var el=document.getElementById('$anchorId');" +
                        "if(el){el.scrollIntoView({block:'start'});}})",
                    null
                )
            }
        }
    }

    private fun adjustTextZoom(delta: Int) {
        val target = (textZoom + delta).coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
        if (target == textZoom) {
            showTextSizeSnackbar()
            return
        }
        captureTopAnchor { applyTextZoom(target, preservePosition = true) }
        showTextSizeSnackbar(target)
    }

    private fun resetTextZoom() {
        if (textZoom == DEFAULT_TEXT_ZOOM) {
            showTextSizeSnackbar()
            return
        }
        captureTopAnchor { applyTextZoom(DEFAULT_TEXT_ZOOM, preservePosition = true) }
        showTextSizeSnackbar(DEFAULT_TEXT_ZOOM)
    }

    private fun showTextSizeSnackbar(percent: Int = textZoom) {
        Snackbar.make(
            webView,
            getString(R.string.text_size_percent, percent),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    private fun loadPage() {
        pageLoaded = false
        val anchor = savedAnchorId?.let { "#$it" } ?: ""
        webView.loadUrl("file:///android_asset/curated/en/html/$filename$anchor")
    }

    private fun markPosition() {
        webView.evaluateJavascript(findAnchorJs) { result ->
            val anchorId = result?.removeSurrounding("\"")?.takeIf { it != "null" } ?: return@evaluateJavascript
            executor.execute {
                bookmarkDao.save(Bookmark(filename, anchorId, System.currentTimeMillis()))
                handler.post {
                    Snackbar.make(webView, getString(R.string.position_marked), Snackbar.LENGTH_SHORT).show()
                }
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_increase_text)?.isEnabled = textZoom < MAX_TEXT_ZOOM
        menu.findItem(R.id.action_decrease_text)?.isEnabled = textZoom > MIN_TEXT_ZOOM
        menu.findItem(R.id.action_reset_text)?.isEnabled = textZoom != DEFAULT_TEXT_ZOOM
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_mark_position -> { markPosition(); true }
        R.id.action_reset -> { showResetDialog(); true }
        R.id.action_increase_text -> { adjustTextZoom(TEXT_ZOOM_MENU_STEP); true }
        R.id.action_decrease_text -> { adjustTextZoom(-TEXT_ZOOM_MENU_STEP); true }
        R.id.action_reset_text -> { resetTextZoom(); true }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showResetDialog() {
        AlertDialog.Builder(this)
            .setTitle(R.string.reset_dialog_title)
            .setMessage(R.string.reset_dialog_message)
            .setPositiveButton(R.string.reset_confirm) { _, _ ->
                executor.execute {
                    bookmarkDao.delete(filename)
                    handler.post {
                        savedAnchorId = null
                        loadPage()
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    companion object {
        private const val PREFS_NAME = "reader_prefs"
        private const val KEY_TEXT_ZOOM = "text_zoom"

        private const val DEFAULT_TEXT_ZOOM = 100
        private const val MIN_TEXT_ZOOM = 80
        private const val MAX_TEXT_ZOOM = 200

        // Pinch changes are quantized to this percentage so the page isn't relaid out
        // for every sub-step jitter of the gesture.
        private const val TEXT_ZOOM_STEP = 4
        private const val TEXT_ZOOM_MENU_STEP = 10

        private fun snapToStep(value: Float): Int =
            ((value / TEXT_ZOOM_STEP).roundToInt() * TEXT_ZOOM_STEP)
                .coerceIn(MIN_TEXT_ZOOM, MAX_TEXT_ZOOM)
    }
}
