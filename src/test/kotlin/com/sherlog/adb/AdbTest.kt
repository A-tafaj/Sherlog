package com.sherlog.adb

import com.sherlog.core.LineTextProvider
import com.sherlog.core.LiveLogIndexer
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdbTest {

    private val tempFiles = mutableListOf<File>()

    private fun tempFile(): File =
        File.createTempFile("adbtest", ".txt").also { tempFiles.add(it) }

    @AfterTest
    fun tearDown() = tempFiles.forEach { it.delete() }

    @Test
    fun `parseDevices reads serials, models, and skips non-device states`() {
        val output = """
            List of devices attached
            9A271FFAZ004TT          device product:panther model:Pixel_7 device:panther transport_id:1
            emulator-5554           device product:sdk_gphone model:sdk_gphone64 transport_id:2
            0123456789ABCDEF        offline
            badtoken                unauthorized
        """.trimIndent()

        val devices = RealAdb.parseDevices(output)
        assertEquals(2, devices.size)
        assertEquals("9A271FFAZ004TT", devices[0].serial)
        assertEquals("Pixel 7", devices[0].model) // underscores become spaces
        assertEquals("Pixel 7 (9A271FFAZ004TT)", devices[0].label)
        assertEquals("emulator-5554", devices[1].serial)
    }

    @Test
    fun `parseDevices on an empty list yields nothing`() {
        assertEquals(0, RealAdb.parseDevices("List of devices attached\n").size)
    }

    @Test
    fun `label falls back to the serial when the model is unknown`() {
        assertEquals("abc123", AdbDevice("abc123", null).label)
    }

    @Test
    fun `pump tees the stream to the capture file and the indexer`() {
        val streamed =
            "07-12 14:10:18.100  1913 18142 E OkHttp: one\n" +
                "07-12 14:10:19.000  1913 18143 W NetworkMonitor: two\n"
        val file = tempFile()
        val indexer = LiveLogIndexer(file)
        var ticks = 0
        // Small chunks so a line splits across reads, exercising the carry path.
        val session = LogcatSession(ChunkedStream(streamed.toByteArray(), chunk = 10))

        session.pump(file, indexer) { ticks++ }

        assertEquals(streamed, file.readText())
        assertTrue(ticks > 1, "onBytes should fire once per chunk")
        val idx = indexer.snapshot()
        assertEquals(2, idx.lineCount)
        LineTextProvider(idx).use { provider ->
            assertEquals("07-12 14:10:18.100  1913 18142 E OkHttp: one", provider.line(0))
            assertEquals("07-12 14:10:19.000  1913 18143 W NetworkMonitor: two", provider.line(1))
        }
    }

    @Test
    fun `stop ends the pump loop`() {
        // A stream that never ends; stop() must break the loop.
        val endless = object : java.io.InputStream() {
            override fun read(): Int = 'x'.code
            override fun read(b: ByteArray): Int { b[0] = 'x'.code.toByte(); return 1 }
        }
        val file = tempFile()
        val session = LogcatSession(endless)
        val thread = Thread { session.pump(file, LiveLogIndexer(file)) {} }
        thread.start()
        Thread.sleep(50)
        session.stop()
        thread.join(2000)
        assertTrue(!thread.isAlive, "pump must return after stop()")
    }

    /** Serves [data] in fixed-size chunks so reads split lines. */
    private class ChunkedStream(private val data: ByteArray, private val chunk: Int) : java.io.InputStream() {
        private var pos = 0
        override fun read(): Int = if (pos < data.size) data[pos++].toInt() and 0xFF else -1
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= data.size) return -1
            val n = minOf(chunk, len, data.size - pos)
            System.arraycopy(data, pos, b, off, n)
            pos += n
            return n
        }
    }
}
