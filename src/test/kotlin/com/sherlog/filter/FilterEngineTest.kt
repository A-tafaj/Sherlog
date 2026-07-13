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
    fun `include tags`() {
        val result = apply(FilterState(includedTags = setOf("OkHttp", "NetworkMonitor")))
        assertContentEquals(intArrayOf(2, 3, 5), result)
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
    fun `time range filter`() {
        val result = apply(
            FilterState(
                timeFromMs = LogcatLineParser.parseTimestamp("07-12 14:10:18"),
                timeToMs = LogcatLineParser.parseTimestamp("07-12 14:30:00"),
            ),
        )
        assertContentEquals(intArrayOf(2, 3), result)
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
            apply(FilterState(searchQuery = "timeout", includedTags = setOf("OkHttp"))),
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
