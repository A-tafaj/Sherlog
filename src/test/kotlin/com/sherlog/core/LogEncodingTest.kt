package com.sherlog.core

import com.sherlog.export.LogExporter
import com.sherlog.filter.FilterEngine
import com.sherlog.filter.FilterState
import com.sherlog.filter.HighlightCounter
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.charset.Charset
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogEncodingTest {

    private val tempFiles = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempFiles.forEach { it.delete() }
    }

    // What `adb logcat > file.txt` in Windows PowerShell 5.1 produces.
    private val utf16LeBom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val utf16BeBom = byteArrayOf(0xFE.toByte(), 0xFF.toByte())

    private val sampleLines = listOf(
        "--------- beginning of main",
        "09-10 09:17:42.902  1913  1913 I GetCardStatus: card present",
        "09-10 09:17:43.100  2045  2050 E HidOtaInteractor: OTA failed: timeout",
        "09-10 09:17:43.200  2045  2050 W System.err: \tat com.example.Foo.bar(Foo.java:42)",
        // 上 is U+4E0A: in UTF-16LE its first byte is 0x0A, a line feed's byte.
        "09-10 09:17:44.000  1913  1913 D InputDispatcher: 上 reset 上",
        "    at com.example.Unparsed.line(Stack.java:1)",
        "09-10 09:17:45.500  1913  1913 I GetCardStatus: ümlaut ok",
    )

    private fun file(bytes: ByteArray): File =
        File.createTempFile("logencoding", ".txt").also { it.writeBytes(bytes); tempFiles.add(it) }

    private fun encode(lines: List<String>, charset: Charset, bom: ByteArray = byteArrayOf(), eol: String = "\r\n") =
        bom + lines.joinToString(eol, postfix = eol).toByteArray(charset)

    private fun detect(bytes: ByteArray) = LogEncoding.detect(bytes, bytes.size)

    private fun linesOf(index: LogIndex): List<String> =
        LineTextProvider(index).use { p -> (0 until index.lineCount).map { p.line(it) } }

    /** Asserts [actual] carries exactly the metadata a UTF-8 index of the same text does. */
    private fun assertSameIndex(expected: LogIndex, actual: LogIndex) {
        assertEquals(expected.lineCount, actual.lineCount)
        assertContentEquals(expected.tags, actual.tags)
        assertContentEquals(expected.tagCounts, actual.tagCounts)
        assertContentEquals(expected.tagIds, actual.tagIds)
        assertContentEquals(expected.levels, actual.levels)
        assertContentEquals(expected.pids, actual.pids)
        assertContentEquals(expected.timestamps, actual.timestamps)
    }

    @Test
    fun `a byte-order mark decides the encoding`() {
        assertEquals(LogEncoding.Detected(LogEncoding.UTF_16LE, 2), detect(encode(sampleLines, Charsets.UTF_16LE, utf16LeBom)))
        assertEquals(LogEncoding.Detected(LogEncoding.UTF_16BE, 2), detect(encode(sampleLines, Charsets.UTF_16BE, utf16BeBom)))
    }

    @Test
    fun `UTF-16 without a BOM is recognised by its NUL bytes`() {
        assertEquals(LogEncoding.Detected(LogEncoding.UTF_16LE, 0), detect(encode(sampleLines, Charsets.UTF_16LE)))
        assertEquals(LogEncoding.Detected(LogEncoding.UTF_16BE, 0), detect(encode(sampleLines, Charsets.UTF_16BE)))
    }

    @Test
    fun `UTF-8 stays UTF-8, non-ASCII text and empty files included`() {
        assertEquals(LogEncoding.Detected(LogEncoding.UTF_8, 0), detect(encode(sampleLines, Charsets.UTF_8)))
        assertEquals(LogEncoding.Detected(LogEncoding.UTF_8, 0), detect(ByteArray(0)))
        assertEquals(LogEncoding.Detected(LogEncoding.UTF_8, 0), detect("a".toByteArray()))
    }

    @Test
    fun `a PowerShell capture (UTF-16LE, BOM, CRLF) indexes exactly like UTF-8`() = runBlocking {
        val utf8 = LogIndexer.index(file(encode(sampleLines, Charsets.UTF_8)))
        val utf16 = LogIndexer.index(file(encode(sampleLines, Charsets.UTF_16LE, utf16LeBom)))

        assertEquals(LogEncoding.UTF_16LE, utf16.encoding)
        assertSameIndex(utf8, utf16)
        assertEquals(2L, utf16.offsets[0]) // the first line starts after the BOM
        assertEquals(listOf("GetCardStatus", "HidOtaInteractor", "System.err", "InputDispatcher"), utf16.tags.toList())
        assertEquals(sampleLines, linesOf(utf16)) // no BOM, no CR, no stray NULs
    }

    @Test
    fun `UTF-16BE and BOM-less UTF-16LE index like UTF-8 too`() = runBlocking {
        val utf8 = LogIndexer.index(file(encode(sampleLines, Charsets.UTF_8)))
        for (bytes in listOf(encode(sampleLines, Charsets.UTF_16BE, utf16BeBom), encode(sampleLines, Charsets.UTF_16LE, eol = "\n"))) {
            val index = LogIndexer.index(file(bytes))
            assertSameIndex(utf8, index)
            assertEquals(sampleLines, linesOf(index))
        }
    }

    @Test
    fun `a 0x0A byte inside a UTF-16 character is not a line break`() = runBlocking {
        val lines = listOf("09-10 09:17:44.000  1913  1913 D Tag: 上上上 still one line")
        val index = LogIndexer.index(file(encode(lines, Charsets.UTF_16LE, utf16LeBom)))
        assertEquals(1, index.lineCount)
        assertEquals(lines, linesOf(index))
    }

    @Test
    fun `UTF-16 lines spanning read buffers keep exact offsets`() = runBlocking {
        // ~2.6 MB of UTF-16: crosses the indexer's 1 MiB read buffer twice.
        val lines = (0 until 20_000).map { i ->
            "09-10 09:%02d:%02d.%03d %5d %5d I Tag%d: line %d 上".format(i / 60 % 60, i % 60, i % 1000, 1000 + i % 7, 2000, i % 5, i)
        }
        val index = LogIndexer.index(file(encode(lines, Charsets.UTF_16LE, utf16LeBom)))
        assertEquals(lines.size, index.lineCount)
        assertEquals(lines, linesOf(index))
        assertEquals(5, index.tags.size)
    }

    @Test
    fun `a capture cut off mid-character keeps its lines`() = runBlocking {
        val bytes = encode(sampleLines, Charsets.UTF_16LE, utf16LeBom)
        val index = LogIndexer.index(file(bytes.copyOf(bytes.size - 1))) // odd length: half a code unit at the end
        assertEquals(sampleLines.size, index.lineCount)
        assertEquals(sampleLines, linesOf(index)) // the half unit is dropped, the text is whole
        assertTrue(index.tagIds.last() >= 0, "the last line still parses")
    }

    @Test
    fun `search, highlight counting and export read UTF-16 text`() = runBlocking {
        val index = LogIndexer.index(file(encode(sampleLines, Charsets.UTF_16LE, utf16LeBom)))
        val all = IntArray(index.lineCount) { it }

        assertContentEquals(intArrayOf(2), FilterEngine.apply(index, FilterState(searchQuery = "timeout")))
        assertContentEquals(intArrayOf(4), HighlightCounter.matches(index, all, "上"))
        assertContentEquals(intArrayOf(6), HighlightCounter.matches(index, all, "ÜMLAUT")) // case-insensitive

        // Export writes UTF-8, keeping each line's CRLF, and reads back as UTF-8.
        val out = File.createTempFile("logencoding_export", ".txt").also { tempFiles.add(it) }
        LogExporter.export(index, intArrayOf(2, 4, 6), out)
        val expected = listOf(sampleLines[2], sampleLines[4], sampleLines[6])
        assertContentEquals(encode(expected, Charsets.UTF_8), out.readBytes())
        val reread = LogIndexer.index(out)
        assertEquals(LogEncoding.UTF_8, reread.encoding)
        assertEquals(expected, linesOf(reread))
    }
}
