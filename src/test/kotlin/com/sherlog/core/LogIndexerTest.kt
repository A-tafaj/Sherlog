package com.sherlog.core

import com.sherlog.model.LogLevel
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class LogIndexerTest {

    private val tempFiles = mutableListOf<File>()

    private fun logFile(content: String): File {
        val f = File.createTempFile("logindexer", ".txt")
        f.writeText(content)
        tempFiles.add(f)
        return f
    }

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    private val sample = """
        07-12 14:10:14.880   715   822 E AudioService: gain value not parsable : ""
        07-12 14:10:16.620  6432 27697 D CCodec: int32_t height = 528
        07-12 14:10:18.100  1913 18142 E OkHttp: timeout
        --------- beginning of main
        07-12 14:10:19.000  1913 18143 W NetworkMonitor: DNS fail
        07-12 14:10:20.000  6432 27697 D CCodec: color format
    """.trimIndent() + "\n"

    @Test
    fun `indexes lines with counts and tags`() = runBlocking {
        val index = LogIndexer.index(logFile(sample))
        assertEquals(6, index.lineCount)
        assertEquals(2, index.errorCount)
        assertEquals(1, index.warningCount)
        assertEquals(4, index.tags.size)
        assertEquals(2, index.tagCounts[index.tags.indexOf("CCodec")])
        assertEquals(LogLevel.UNKNOWN, index.level(3))
        assertEquals(-1, index.pids[3])
        assertEquals(1913, index.pids[2])
        // Unparsed lines inherit the previous parsed line's timestamp.
        assertEquals(index.timestamps[2], index.timestamps[3])
        assertEquals(index.timestamps[0], index.firstTimestampMs)
        assertEquals(index.timestamps[5], index.lastTimestampMs)
    }

    @Test
    fun `offsets recover exact line text`() = runBlocking {
        val file = logFile(sample)
        val index = LogIndexer.index(file)
        LineTextProvider(index).use { provider ->
            assertEquals("07-12 14:10:18.100  1913 18142 E OkHttp: timeout", provider.line(2))
            assertEquals("--------- beginning of main", provider.line(3))
            assertEquals("07-12 14:10:20.000  6432 27697 D CCodec: color format", provider.line(5))
        }
    }

    @Test
    fun `handles crlf line endings and missing final newline`() = runBlocking {
        val file = logFile(
            "07-12 14:10:18.100  1913 18142 E OkHttp: timeout\r\n" +
                "07-12 14:10:19.000  1913 18143 W NetworkMonitor: DNS fail",
        )
        val index = LogIndexer.index(file)
        assertEquals(2, index.lineCount)
        assertEquals("OkHttp", index.tags[index.tagIds[0]])
        assertEquals("NetworkMonitor", index.tags[index.tagIds[1]])
        LineTextProvider(index).use { provider ->
            assertEquals("07-12 14:10:18.100  1913 18142 E OkHttp: timeout", provider.line(0))
            assertEquals("07-12 14:10:19.000  1913 18143 W NetworkMonitor: DNS fail", provider.line(1))
        }
    }

    @Test
    fun `non-monotonic multi-buffer dumps get true min-max timestamps`() = runBlocking {
        // Regression: logcat dumps concatenate ring buffers, each restarting
        // at an earlier time. first/last used to be taken by file position,
        // so the pre-filled time range silently dropped whole buffers.
        val file = logFile(
            """
            --------- beginning of system
            07-12 14:00:00.000   100   100 I SystemServer: boot
            07-12 15:00:00.000   100   100 I SystemServer: late event
            --------- beginning of main
            07-12 13:00:00.000   200   200 D Volley: connect
            07-12 13:30:00.000   200   200 D Volley: emit
            """.trimIndent() + "\n",
        )
        val index = LogIndexer.index(file)
        assertEquals(
            com.sherlog.parser.LogcatLineParser.parseTimestamp("07-12 13:00:00"),
            index.firstTimestampMs,
        )
        assertEquals(
            com.sherlog.parser.LogcatLineParser.parseTimestamp("07-12 15:00:00"),
            index.lastTimestampMs,
        )
        // Leading unparsed marker inherits the first parsed timestamp.
        assertEquals(index.timestamps[1], index.timestamps[0])
    }

    @Test
    fun `handles lines spanning read buffers`() = runBlocking {
        // A message longer than the carry buffer's initial 4KB.
        val long = "07-12 14:10:18.100  1913 18142 E OkHttp: " + "x".repeat(10_000)
        val file = logFile("$long\n07-12 14:10:19.000  1913 18143 W NetworkMonitor: DNS fail\n")
        val index = LogIndexer.index(file)
        assertEquals(2, index.lineCount)
        LineTextProvider(index).use { provider ->
            assertEquals(long, provider.line(0))
        }
    }
}
