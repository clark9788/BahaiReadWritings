package com.bahairesearch.bahaireadwritings

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.bahairesearch.bahaireadwritings.data.CorpusIndex
import com.bahairesearch.bahaireadwritings.data.ManifestReader
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var authorSpinner: Spinner
    private lateinit var titleSpinner: Spinner
    private lateinit var openButton: Button
    private lateinit var statusText: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private var corpusIndex: CorpusIndex? = null

    companion object {
        const val EXTRA_FILENAME = "filename"
        const val EXTRA_TITLE = "title"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))

        authorSpinner = findViewById(R.id.authorSpinner)
        titleSpinner = findViewById(R.id.titleSpinner)
        openButton = findViewById(R.id.openButton)
        statusText = findViewById(R.id.statusText)

        titleSpinner.isEnabled = false
        openButton.isEnabled = false
        statusText.text = getString(R.string.status_loading)

        openButton.setOnClickListener { openSelectedText() }

        executor.execute {
            val index = ManifestReader.read(this)
            handler.post {
                corpusIndex = index
                initAuthorSpinner(index)
                statusText.text = getString(R.string.hint_author)
            }
        }
    }

    private fun initAuthorSpinner(index: CorpusIndex) {
        val items = listOf(getString(R.string.all_authors)) + index.authors
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        authorSpinner.adapter = adapter
        authorSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (pos == 0) {
                    setTitleSpinner(emptyList(), "")
                    statusText.text = getString(R.string.hint_author)
                } else {
                    val author = items[pos]
                    setTitleSpinner(index.titlesFor[author] ?: emptyList(), author)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun setTitleSpinner(titles: List<String>, author: String) {
        val items = listOf(getString(R.string.all_titles)) + titles
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, items)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        titleSpinner.adapter = adapter
        titleSpinner.isEnabled = titles.isNotEmpty()
        openButton.isEnabled = false
        statusText.text = if (titles.isNotEmpty()) getString(R.string.hint_title) else getString(R.string.hint_author)

        titleSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                openButton.isEnabled = pos > 0
                statusText.text = if (pos > 0) getString(R.string.hint_open) else getString(R.string.hint_title)
            }
            override fun onNothingSelected(parent: AdapterView<*>) {}
        }
    }

    private fun openSelectedText() {
        val index = corpusIndex ?: return
        val author = authorSpinner.selectedItem as? String ?: return
        val title = titleSpinner.selectedItem as? String ?: return
        val filename = index.filenameFor[author]?.get(title) ?: return

        startActivity(Intent(this, ReaderActivity::class.java).apply {
            putExtra(EXTRA_FILENAME, filename)
            putExtra(EXTRA_TITLE, title)
        })
    }
}
