package com.sherlog.core

import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogFilesTest {

    private val dir: File = Files.createTempDirectory("logfiles").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private val logText = "09-21 08:29:41.227589   483   483 W         : [dhd] scan busy\r\n" +
        "09-21 08:29:41.230102  1550  1611 I WifiService:\tstartScan\r\n"

    private fun looksLikeText(bytes: ByteArray) = LogFiles.looksLikeText(bytes, bytes.size)

    @Test
    fun `logcat text in every encoding the indexer reads looks like text`() {
        assertTrue(looksLikeText(logText.toByteArray(Charsets.UTF_8)))
        assertTrue(looksLikeText(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + logText.toByteArray(Charsets.UTF_16LE)))
        assertTrue(looksLikeText(logText.toByteArray(Charsets.UTF_16LE))) // BOM-less, as some tools write it
        assertTrue(looksLikeText("Grüße 上海 — ok\n".repeat(50).toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `ansi colour codes and a character split by the 4 KB cut are still text`() {
        assertTrue(looksLikeText("\u001B[31mE/AndroidRuntime: boom\u001B[0m\n".repeat(20).toByteArray()))
        val utf8 = ("a".repeat(4095) + "é").toByteArray(Charsets.UTF_8)
        assertTrue(LogFiles.looksLikeText(utf8, 4096)) // ends on half of "é"
    }

    @Test
    fun `binaries do not look like text`() {
        assertFalse(looksLikeText(Random(42).nextBytes(4096))) // compressed data looks like noise
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10) +
            ByteArray(64) { if (it % 3 == 0) 0 else it.toByte() }
        assertFalse(looksLikeText(png))
        assertFalse(looksLikeText(ByteArray(512) { (it % 4).toByte() })) // mostly NULs and control bytes
    }

    @Test
    fun `empty files are text and unreadable ones are not`() {
        assertTrue(looksLikeText(ByteArray(0)))
        assertFalse(LogFiles.looksLikeText(File(dir, "missing")))
    }

    @Test
    fun `isOpenable trusts txt and log names and sniffs everything else`() {
        val rotated = File(dir, "logcat.09").apply { writeText(logText) }
        val noExtension = File(dir, "logcat").apply { writeText(logText) }
        val zip = File(dir, "bugreport.zip").apply { writeBytes(Random(7).nextBytes(8192)) }
        val oddTxt = File(dir, "weird.TXT").apply { writeBytes(Random(7).nextBytes(64)) }
        assertTrue(LogFiles.isOpenable(rotated))
        assertTrue(LogFiles.isOpenable(noExtension))
        assertFalse(LogFiles.isOpenable(zip))
        assertTrue(LogFiles.isOpenable(oddTxt)) // the name says text; the user gets what they asked for
    }

    @Test
    fun `export names keep an unusual extension and always end in txt`() {
        assertEquals("logcat_filtered.txt", LogFiles.exportName(File("logcat.txt")))
        assertEquals("radio_filtered.txt", LogFiles.exportName(File("radio.LOG")))
        assertEquals("logcat.09_filtered.txt", LogFiles.exportName(File("logcat.09")))
        assertEquals("logcat_filtered.txt", LogFiles.exportName(File("logcat")))
        assertEquals("cleaned_logcat.txt", LogFiles.exportName(null))
    }

    @Test
    fun `a typed name without txt or log gets txt`() {
        assertEquals("wifi_issue.txt", LogFiles.withTextExtension(File(dir, "wifi_issue")).name)
        assertEquals("logcat.09.txt", LogFiles.withTextExtension(File(dir, "logcat.09")).name)
        assertEquals("keep.txt", LogFiles.withTextExtension(File(dir, "keep.txt")).name)
        assertEquals("keep.Log", LogFiles.withTextExtension(File(dir, "keep.Log")).name)
        assertEquals(dir, LogFiles.withTextExtension(File(dir, "wifi_issue")).parentFile)
    }

    @Test
    fun `an existing file picked in the dialog is kept as it is`() {
        val existing = File(dir, "logcat.09").apply { writeText(logText) }
        assertEquals(existing, LogFiles.withTextExtension(existing))
    }
}
