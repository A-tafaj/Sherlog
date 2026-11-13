package com.sherlog.core

import java.io.File

/**
 * Which files Sherlog opens and what it names the files it writes.
 *
 * Logs don't always end in `.txt`/`.log`: logcat's own rotation writes
 * `logcat`, `logcat.01` … `logcat.09`. So anything that *looks like text* is
 * opened, and only binaries (archives, images, protobuf dumps) are refused.
 */
object LogFiles {

    /** Opened without looking inside, and kept as-is when saving. */
    private val TEXT_EXTENSIONS = setOf("txt", "log")

    private const val SNIFF_BYTES = 4096

    /** More than this share of junk characters in the first 4 KB means binary. */
    private const val MAX_JUNK_PERCENT = 5

    fun hasTextExtension(file: File): Boolean = file.extension.lowercase() in TEXT_EXTENSIONS

    /** Whether [file] can be opened: a `.txt`/`.log`, or anything whose content is text. */
    fun isOpenable(file: File): Boolean = hasTextExtension(file) || looksLikeText(file)

    /** Reads the start of [file]; false for binaries and for files that can't be read. */
    fun looksLikeText(file: File): Boolean {
        val head = ByteArray(SNIFF_BYTES)
        val n = runCatching { file.inputStream().use { it.readNBytes(head, 0, head.size) } }
            .getOrElse { return false }
        return looksLikeText(head, n)
    }

    /**
     * Decodes [n] bytes the way the indexer would ([LogEncoding.detect], so
     * UTF-16's NULs are fine) and counts what text never contains: control
     * characters and undecodable bytes. Tabs, line breaks, form feeds and
     * ESC (ANSI colour codes) are text. One bad character is expected, since
     * the 4 KB cut can split a character in two.
     */
    internal fun looksLikeText(head: ByteArray, n: Int): Boolean {
        val (encoding, bomLength) = LogEncoding.detect(head, n)
        val bytes = n - bomLength
        val text = String(head, bomLength, bytes - bytes % encoding.unitSize, encoding.charset)
        var junk = 0
        for (c in text) {
            val control = c < ' ' && c != '\t' && c != '\n' && c != '\r' && c != '\u000C' && c != '\u001B'
            if (control || c in '\u007F'..'\u009F' || c == '�') junk++
        }
        return junk * 100 <= text.length * MAX_JUNK_PERCENT || junk <= 1
    }

    /**
     * The name an export of [source] is offered under. A `.txt`/`.log`
     * extension makes way for `_filtered.txt`; any other is part of the name,
     * so `logcat.09` becomes `logcat.09_filtered.txt` rather than colliding
     * with the export of `logcat.08`.
     */
    fun exportName(source: File?): String = when {
        source == null -> "cleaned_logcat.txt"
        hasTextExtension(source) -> "${source.nameWithoutExtension}_filtered.txt"
        else -> "${source.name}_filtered.txt"
    }

    /**
     * Where a save to [chosen] should land. A typed name without `.txt` or
     * `.log` gets `.txt`, so the file still opens as text everywhere. An
     * existing file the user picked is kept as-is: the save dialog already
     * asked about replacing it.
     */
    fun withTextExtension(chosen: File): File =
        if (chosen.exists() || hasTextExtension(chosen)) chosen
        else File(chosen.parentFile, chosen.name + ".txt")
}
