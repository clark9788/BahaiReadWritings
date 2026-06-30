package com.bahairesearch.bahaireadwritings

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Menu
import android.view.MenuItem
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.bahairesearch.bahaireadwritings.data.Bookmark
import com.bahairesearch.bahaireadwritings.data.BookmarkDatabase
import com.bahairesearch.bahaireadwritings.data.BookmarkDao
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.Executors

class ReaderActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bookmarkDao: BookmarkDao

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var filename = ""
    private var savedAnchorId: String? = null
    private var pageLoaded = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)

        filename = intent.getStringExtra(MainActivity.EXTRA_FILENAME) ?: ""
        val title = intent.getStringExtra(MainActivity.EXTRA_TITLE) ?: ""

        supportActionBar?.apply {
            this.title = title
            setDisplayHomeAsUpEnabled(true)
        }

        webView = findViewById(R.id.webView)
        bookmarkDao = BookmarkDatabase.getInstance(this).bookmarkDao()

        webView.settings.javaScriptEnabled = true
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

        executor.execute {
            val bookmark = bookmarkDao.get(filename)
            handler.post {
                savedAnchorId = bookmark?.anchorId
                loadPage()
            }
        }
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> { finish(); true }
        R.id.action_mark_position -> { markPosition(); true }
        R.id.action_reset -> { showResetDialog(); true }
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
}
