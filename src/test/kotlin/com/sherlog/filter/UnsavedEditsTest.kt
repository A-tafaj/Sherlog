package com.sherlog.filter

import com.sherlog.core.LogIndex
import com.sherlog.core.LogIndexer
import com.sherlog.export.LogExporter
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/** The text passes — filter, match counting, export — read a tab's unsaved edits instead of the file. */
class UnsavedEditsTest {

    private lateinit var file: File
    private lateinit var index: LogIndex

    // CRLF, as a Windows capture has it: an edited line must keep its ending.
    private val lines = listOf(
        "07-12 10:00:00.000  1000  1000 I Tag: alpha",
        "07-12 10:00:01.000  1000  1000 I Tag: bravo",
        "07-12 10:00:02.000  1000  1000 I Tag: charlie",
    )

    @BeforeTest
    fun setUp() = runBlocking {
        file = File.createTempFile("unsavededits", ".txt")
        file.writeText(lines.joinToString("\r\n", postfix = "\r\n"))
        index = LogIndexer.index(file)
    }

    @AfterTest
    fun tearDown() {
        file.delete()
    }

    private val edits = mapOf(1 to "07-12 10:00:01.000  1000  1000 I Tag: renamed")

    @Test
    fun `the search filter matches the edited text, not the file's`() = runBlocking {
        assertContentEquals(intArrayOf(1), FilterEngine.apply(index, FilterState(searchQuery = "renamed"), edits))
        assertContentEquals(IntArray(0), FilterEngine.apply(index, FilterState(searchQuery = "bravo"), edits))
        assertContentEquals(intArrayOf(1), FilterEngine.apply(index, FilterState(searchQuery = "bravo")))
    }

    @Test
    fun `match counting sees the edited text`() = runBlocking {
        val all = intArrayOf(0, 1, 2)
        assertContentEquals(intArrayOf(1), HighlightCounter.matches(index, all, "renamed", edits = edits))
        assertContentEquals(IntArray(0), HighlightCounter.matches(index, all, "bravo", edits = edits))
    }

    @Test
    fun `export writes edited lines with their own line ending, the rest byte for byte`() = runBlocking {
        val out = File.createTempFile("unsavededits-out", ".txt")
        try {
            LogExporter.export(index, intArrayOf(0, 1, 2), out, edits)
            val expected = listOf(lines[0], edits.getValue(1), lines[2]).joinToString("\r\n", postfix = "\r\n")
            assertEquals(expected, out.readText())
        } finally {
            out.delete()
        }
    }
}
