package com.bahairesearch.bahaireadwritings.data

import android.content.Context

data class CorpusIndex(
    val authors: List<String>,
    val titlesFor: Map<String, List<String>>,
    val filenameFor: Map<String, Map<String, String>>
)

object ManifestReader {

    private val AUTHOR_ORDER = listOf(
        "Baha'u'llah",
        "Bab",
        "'Abdu'l-Baha",
        "Shoghi Effendi",
        "Universal House of Justice",
        "Compilation"
    )

    fun read(context: Context): CorpusIndex {
        val byAuthor = mutableMapOf<String, MutableMap<String, String>>()

        context.assets.open("manifest.csv").bufferedReader().useLines { lines ->
            lines.drop(1).forEach { line ->
                val parts = parseCsvLine(line)
                if (parts.size < 4) return@forEach
                val filename = parts[0].trim()
                val title = parts[1].trim()
                val author = parts[2].trim()
                val format = parts[3].trim()
                if (format != "xhtml") return@forEach
                byAuthor.getOrPut(author) { mutableMapOf() }[title] = filename
            }
        }

        val orderedAuthors = AUTHOR_ORDER.filter { byAuthor.containsKey(it) }
        val titlesFor = orderedAuthors.associateWith { author ->
            byAuthor[author]?.keys?.sorted() ?: emptyList()
        }
        val filenameFor = orderedAuthors.associateWith { author ->
            byAuthor[author] ?: emptyMap<String, String>()
        }

        return CorpusIndex(orderedAuthors, titlesFor, filenameFor)
    }

    // Handles quoted fields (e.g. "Title, with comma") per basic CSV rules.
    private fun parseCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '"' && !inQuotes -> inQuotes = true
                c == '"' && inQuotes -> {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        current.append('"'); i++
                    } else {
                        inQuotes = false
                    }
                }
                c == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        result.add(current.toString())
        return result
    }
}
