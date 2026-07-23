package com.sherlog.core

import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class LiveLogIndexerTest {

    private val tempFiles = mutableListOf<File>()

    private fun tempFileOf(content: String): File {
        val f = File.createTempFile("liveindexer", ".txt")
        f.writeText(content)
        tempFiles.add(f)
        return f
    }

    @AfterTest
    fun tearDown() {
        tempFiles.forEach { it.delete() }
    }

    private val sample = """
        07-12 14:10:14.880   715   822 E AudioService: gain not parsable
        --------- beginning of main
        07-12 14:10:16.620  6432 27697 D CCodec: int32_t height = 528
        07-12 14:10:18.100  1913 18142 E OkHttp: request timeout
        07-13 09:00:00.000   715   822 E AndroidRuntime: FATAL EXCEPTION: main
    """.trimIndent() + "\n"

    /** Feeds [bytes] to [indexer] in fixed-size chunks, splitting lines arbitrarily. */
    private fun feed(indexer: LiveLogIndexer, bytes: ByteArray, chunk: Int) {
        var i = 0
        while (i < bytes.size) {
            val len = minOf(chunk, bytes.size - i)
            val slice = bytes.copyOfRange(i, i + len)
            indexer.append(slice, len)
            i += len
        }
    }

    private fun assertSameIndex(expected: LogIndex, actual: LogIndex) {
        assertEquals(expected.lineCount, actual.lineCount, "lineCount")
        assertContentEquals(expected.offsets, actual.offsets, "offsets")
        assertContentEquals(expected.timestamps, actual.timestamps, "timestamps")
        assertContentEquals(expected.pids, actual.pids, "pids")
        assertContentEquals(expected.levels, actual.levels, "levels")
        assertContentEquals(expected.tagIds, actual.tagIds, "tagIds")
        assertContentEquals(expected.tags, actual.tags, "tags")
        assertContentEquals(expected.tagCounts, actual.tagCounts, "tagCounts")
    }

    @Test
    fun `snapshot of fully-fed content matches a one-shot index`() = runBlocking {
        val file = tempFileOf(sample)
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val expected = LogIndexer.index(file)

        // A range of chunk sizes so lines split at every possible boundary.
        for (chunk in intArrayOf(1, 3, 7, 64, bytes.size)) {
            val live = LiveLogIndexer(tempFileOf(sample))
            feed(live, bytes, chunk)
            assertSameIndex(expected, live.snapshot())
        }
    }

    @Test
    fun `a partial trailing line is excluded until its newline arrives`() = runBlocking {
        val first = "07-12 14:10:18.100  1913 18142 E OkHttp: request timeout\n"
        val second = "07-12 14:10:19.000  1913 18143 W NetworkMonitor: DNS fail"
        val file = tempFileOf(first + second + "\n")
        val live = LiveLogIndexer(file)

        // Only the completed line and the still-open one's bytes are fed.
        feed(live, (first + second).toByteArray(Charsets.UTF_8), 8)
        val mid = live.snapshot()
        assertEquals(1, mid.lineCount, "the unterminated second line must not appear yet")

        // The newline completes it.
        live.append("\n".toByteArray(Charsets.UTF_8), 1)
        val done = live.snapshot()
        assertEquals(2, done.lineCount)
    }

    @Test
    fun `snapshot line text is readable from the growing file`() = runBlocking {
        val file = tempFileOf(sample)
        val bytes = sample.toByteArray(Charsets.UTF_8)
        val live = LiveLogIndexer(file)
        feed(live, bytes, 5)
        val idx = live.snapshot()
        LineTextProvider(idx).use { provider ->
            assertEquals("--------- beginning of main", provider.line(1))
            assertEquals("07-12 14:10:18.100  1913 18142 E OkHttp: request timeout", provider.line(3))
        }
    }

    @Test
    fun `provider advanced to a newer snapshot reads freshly appended lines`() = runBlocking {
        // The live loop: append to disk and feed the indexer in lockstep, then
        // advance one long-lived provider across snapshots.
        val file = tempFileOf("")
        val live = LiveLogIndexer(file)
        val out = java.io.FileOutputStream(file, true)

        fun push(line: String) {
            val b = (line + "\n").toByteArray(Charsets.UTF_8)
            out.write(b); out.flush()
            live.append(b, b.size)
        }

        push("07-12 14:10:18.100  1913 18142 E OkHttp: one")
        val provider = LineTextProvider(live.snapshot())
        provider.use {
            assertEquals("07-12 14:10:18.100  1913 18142 E OkHttp: one", it.line(0))

            // More arrives; advance the provider to the larger snapshot.
            push("07-12 14:10:19.000  1913 18143 W NetworkMonitor: two")
            it.advance(live.snapshot())
            assertEquals(2, live.snapshot().lineCount)
            assertEquals("07-12 14:10:19.000  1913 18143 W NetworkMonitor: two", it.line(1))
        }
        out.close()
    }
}
