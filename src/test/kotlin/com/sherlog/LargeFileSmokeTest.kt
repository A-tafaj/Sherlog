package com.sherlog

import com.sherlog.core.LineTextProvider
import com.sherlog.core.LogIndexer
import com.sherlog.export.LogExporter
import com.sherlog.filter.FilterEngine
import com.sherlog.filter.FilterState
import com.sherlog.filter.Preset
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Opt-in end-to-end smoke test against a real large log file. Skipped unless
 * the SMOKE_FILE environment variable points to an existing file.
 *
 *   SMOKE_FILE=C:\path\to\big_logcat.txt gradlew test --tests "*LargeFileSmokeTest*"
 */
class LargeFileSmokeTest {

    @Test
    fun `index, filter and export a large file`() = runBlocking {
        val path = System.getenv("SMOKE_FILE") ?: return@runBlocking
        val file = File(path)
        if (!file.isFile) return@runBlocking

        println("File: %,d bytes".format(file.length()))

        lateinit var index: com.sherlog.core.LogIndex
        val indexMs = measureTimeMillis { index = LogIndexer.index(file) }
        println("Indexed %,d lines, %d tags in %,d ms".format(index.lineCount, index.tags.size, indexMs))
        println("Errors: %,d  Warnings: %,d".format(index.errorCount, index.warningCount))
        assertTrue(index.lineCount > 0)

        // Metadata-only filter (should be near-instant)
        var metadataResult: IntArray
        val metaMs = measureTimeMillis {
            metadataResult = FilterEngine.apply(index, FilterState(selectedTags = setOf("OkHttp")))
        }
        println("Metadata filter: %,d matches in %,d ms".format(metadataResult.size, metaMs))

        // Text filter (streams the whole file)
        var textResult: IntArray
        val textMs = measureTimeMillis {
            textResult = FilterEngine.apply(index, Preset.NETWORK.applyTo(FilterState(searchQuery = "timeout")))
        }
        println("Text filter (network preset + search): %,d matches in %,d ms".format(textResult.size, textMs))

        // Random-access line reads (what the viewer does while scrolling)
        LineTextProvider(index).use { provider ->
            val readMs = measureTimeMillis {
                var i = 0
                while (i < index.lineCount) {
                    provider.line(i)
                    i += index.lineCount / 500
                }
            }
            println("500 random line reads in %,d ms".format(readMs))
        }

        // Export
        val out = File.createTempFile("smoke_export", ".txt")
        try {
            val exportMs = measureTimeMillis { LogExporter.export(index, textResult, out) }
            println("Exported %,d lines (%,d bytes) in %,d ms".format(textResult.size, out.length(), exportMs))
            assertTrue(out.length() > 0 || textResult.isEmpty())
        } finally {
            out.delete()
        }
    }
}
