package com.sherlog.filter

import com.sherlog.core.LogIndex
import com.sherlog.core.LogIndexer
import com.sherlog.export.LogExporter
import com.sherlog.model.LogLevel
import com.sherlog.parser.LogcatLineParser
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class FilterEngineTest {

    private lateinit var file: File
    private lateinit var index: LogIndex

    private val content = """
        07-12 14:10:14.880   715   822 E AudioService: gain value not parsable
        07-12 14:10:16.620  6432 27697 D CCodec: int32_t height = 528
        07-12 14:10:18.100  1913 18142 E OkHttp: request timeout
        07-12 14:10:19.000  1913 18143 W NetworkMonitor: DNS TIMEOUT fail
        --------- beginning of main
        07-12 14:40:00.000  1913 18142 I OkHttp: connection restored
        07-13 09:00:00.000   715   822 E AndroidRuntime: FATAL EXCEPTION: main
    """.trimIndent() + "\n"

    @BeforeTest
    fun setUp() = runBlocking {
        file = File.createTempFile("filtertest", ".txt")
        file.writeText(content)
        index = LogIndexer.index(file)
    }

    @AfterTest
    fun tearDown() {
        file.delete()
    }

    private fun apply(state: FilterState): IntArray = runBlocking { FilterEngine.apply(index, state) }

    @Test
    fun `empty filter keeps everything`() {
        assertContentEquals(IntArray(7) { it }, apply(FilterState.EMPTY))
    }

    @Test
    fun `show-only mode keeps checked tags and drops unparsed lines`() {
        val result = apply(FilterState(selectedTags = setOf("OkHttp", "NetworkMonitor")))
        assertContentEquals(intArrayOf(2, 3, 5), result)
    }

    @Test
    fun `hide mode drops checked tags but keeps unparsed lines`() {
        val result = apply(
            FilterState(selectedTags = setOf("CCodec", "AudioService"), tagMode = TagMode.HIDE),
        )
        // Lines 0 and 1 are hidden; the unparsed marker (line 4) survives.
        assertContentEquals(intArrayOf(2, 3, 4, 5, 6), result)
    }

    @Test
    fun `pid filter`() {
        assertContentEquals(intArrayOf(2, 3, 5), apply(FilterState(pids = setOf(1913))))
        assertContentEquals(intArrayOf(0, 6), apply(FilterState(pids = setOf(715))))
    }

    @Test
    fun `level filter drops unparsed lines when unknown unchecked`() {
        val result = apply(FilterState(levels = setOf(LogLevel.ERROR, LogLevel.FATAL)))
        assertContentEquals(intArrayOf(0, 2, 6), result)
    }

    @Test
    fun `time range filter keeps unparsed lines with their preceding entry`() {
        val result = apply(
            FilterState(
                timeFromMs = LogcatLineParser.parseTimestamp("07-12 14:10:18"),
                timeToMs = LogcatLineParser.parseTimestamp("07-12 14:30:00"),
            ),
        )
        // Line 4 (the "beginning of main" marker) inherits line 3's timestamp.
        assertContentEquals(intArrayOf(2, 3, 4), result)
    }

    @Test
    fun `exclude texts drop matching lines case-insensitively`() {
        val result = apply(FilterState(excludeTexts = listOf("audio", "CCodec")))
        assertContentEquals(intArrayOf(2, 3, 4, 5, 6), result)
    }

    @Test
    fun `include texts keep only matching lines`() {
        val result = apply(FilterState(includeTexts = listOf("FATAL EXCEPTION", "AndroidRuntime")))
        assertContentEquals(intArrayOf(6), result)
    }

    @Test
    fun `search is case insensitive and combines with metadata filters`() {
        assertContentEquals(intArrayOf(2, 3), apply(FilterState(searchQuery = "timeout")))
        assertContentEquals(
            intArrayOf(2),
            apply(FilterState(searchQuery = "timeout", selectedTags = setOf("OkHttp"))),
        )
    }

    @Test
    fun `regex search`() {
        val result = apply(FilterState(searchQuery = "DNS|restored", searchIsRegex = true))
        assertContentEquals(intArrayOf(3, 5), result)
    }

    @Test
    fun `invalid regex matches nothing instead of crashing`() {
        assertContentEquals(intArrayOf(), apply(FilterState(searchQuery = "[unclosed", searchIsRegex = true)))
    }

    @Test
    fun `network preset shape`() {
        val state = Preset.NETWORK.applyTo(FilterState.EMPTY)
        val result = apply(state)
        assertContentEquals(intArrayOf(2, 3, 5), result)
    }

    @Test
    fun `highlight counter counts lines containing needle case-insensitively`() = runBlocking {
        val all = apply(FilterState.EMPTY)
        assertEquals(2, HighlightCounter.matches(index, all, "timeout").size) // "timeout" + "TIMEOUT"
        assertEquals(2, HighlightCounter.matches(index, all, "OkHttp").size)
        assertEquals(0, HighlightCounter.matches(index, all, "nonexistent").size)
        // Relative to the filtered set, not the whole file.
        val onlyPid715 = apply(FilterState(pids = setOf(715)))
        assertEquals(0, HighlightCounter.matches(index, onlyPid715, "timeout").size)
    }

    @Test
    fun `highlight counter returns positions within the filtered list`() = runBlocking {
        val all = apply(FilterState.EMPTY)
        val hits = HighlightCounter.matches(index, all, "timeout")
        // Each returned position indexes into the filtered array and its line contains the needle.
        for (pos in hits) assertEquals(true, pos in all.indices)
        assertEquals(2, hits.size)
    }

    @Test
    fun `prefilled full time range keeps every line even in non-monotonic dumps`() = runBlocking {
        val source = File.createTempFile("multibuffer", ".txt")
        try {
            source.writeText(
                """
                07-12 14:00:00.000   100   100 I SystemServer: boot
                --------- beginning of main
                07-12 13:00:00.000   200   200 D Volley: connect
                """.trimIndent() + "\n",
            )
            val idx = com.sherlog.core.LogIndexer.index(source)
            val result = FilterEngine.apply(
                idx,
                FilterState(timeFromMs = idx.firstTimestampMs, timeToMs = idx.lastTimestampMs),
            )
            assertContentEquals(intArrayOf(0, 1, 2), result)
        } finally {
            source.delete()
        }
    }

    @Test
    fun `exporting onto the source file itself must not destroy the data`() = runBlocking {
        // Regression: writing directly to the target used to truncate the
        // source before reading it, producing an empty file.
        val source = File.createTempFile("selfexport", ".txt")
        try {
            source.writeText(content)
            val idx = com.sherlog.core.LogIndexer.index(source)
            val lines = runBlocking { FilterEngine.apply(idx, FilterState(searchQuery = "timeout")) }
            LogExporter.export(idx, lines, source)
            assertEquals(
                listOf(
                    "07-12 14:10:18.100  1913 18142 E OkHttp: request timeout",
                    "07-12 14:10:19.000  1913 18143 W NetworkMonitor: DNS TIMEOUT fail",
                ),
                source.readLines(),
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun `export writes exactly the filtered lines`() = runBlocking {
        val target = File.createTempFile("export", ".txt")
        try {
            val lines = apply(FilterState(searchQuery = "timeout"))
            LogExporter.export(index, lines, target)
            assertEquals(
                listOf(
                    "07-12 14:10:18.100  1913 18142 E OkHttp: request timeout",
                    "07-12 14:10:19.000  1913 18143 W NetworkMonitor: DNS TIMEOUT fail",
                ),
                target.readLines(),
            )
        } finally {
            target.delete()
        }
    }
}
