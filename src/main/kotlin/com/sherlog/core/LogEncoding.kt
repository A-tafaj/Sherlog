package com.sherlog.core

import java.io.File
import java.nio.charset.Charset

/**
 * The text encoding of a log file. UTF-8 is the norm, but Windows
 * PowerShell 5.1's `>` — i.e. `adb logcat > file.txt` — writes UTF-16LE with a
 * BOM, so UTF-16 is an everyday input rather than an edge case.
 *
 * Offsets everywhere stay byte offsets into the file; only the steps that look
 * at bytes (finding line feeds, turning a line's bytes into text) go through
 * here. The BOM is never part of a line: the first line starts after it.
 */
enum class LogEncoding(val charset: Charset, val unitSize: Int) {
    UTF_8(Charsets.UTF_8, 1),
    UTF_16LE(Charsets.UTF_16LE, 2),
    UTF_16BE(Charsets.UTF_16BE, 2);

    /**
     * Whether the code unit starting at [i] is a line feed. [i] must sit on a
     * code-unit boundary with the whole unit present. For UTF-16 both bytes
     * matter: a lone 0x0A is also half of other characters (上 is U+4E0A,
     * stored as 0A 4E in UTF-16LE).
     */
    fun isLineFeedAt(bytes: ByteArray, i: Int): Boolean = isUnitAt(bytes, i, LF)

    /**
     * Decodes a line's bytes, dropping trailing CR/LF code units — and a
     * trailing half code unit, as left by a UTF-16 capture cut off mid-character.
     */
    fun decodeLine(bytes: ByteArray, from: Int, len: Int): String {
        var end = from + len - len % unitSize
        while (end - from >= unitSize &&
            (isUnitAt(bytes, end - unitSize, LF) || isUnitAt(bytes, end - unitSize, CR))
        ) end -= unitSize
        return String(bytes, from, end - from, charset)
    }

    private fun isUnitAt(bytes: ByteArray, i: Int, ascii: Byte): Boolean = when (this) {
        UTF_8 -> bytes[i] == ascii
        UTF_16LE -> bytes[i] == ascii && bytes[i + 1] == ZERO
        UTF_16BE -> bytes[i] == ZERO && bytes[i + 1] == ascii
    }

    /** An encoding plus the length of the byte-order mark to skip (0 when none). */
    data class Detected(val encoding: LogEncoding, val bomLength: Int)

    companion object {
        private const val LF = '\n'.code.toByte()
        private const val CR = '\r'.code.toByte()
        private const val ZERO: Byte = 0
        private const val SNIFF_BYTES = 4096

        /** Reads the start of [file] and detects its encoding. */
        fun detect(file: File): Detected {
            val head = ByteArray(SNIFF_BYTES)
            val n = file.inputStream().use { it.readNBytes(head, 0, head.size) }
            return detect(head, n)
        }

        /**
         * A BOM decides outright. Without one, UTF-16 still shows itself: log
         * text is overwhelmingly ASCII, which UTF-16 stores with a NUL in every
         * other byte — and UTF-8 text never contains NULs at all.
         */
        fun detect(head: ByteArray, n: Int): Detected {
            if (n >= 2) {
                val b0 = head[0].toInt() and 0xFF
                val b1 = head[1].toInt() and 0xFF
                if (b0 == 0xFF && b1 == 0xFE) return Detected(UTF_16LE, 2)
                if (b0 == 0xFE && b1 == 0xFF) return Detected(UTF_16BE, 2)
            }
            val pairs = n / 2
            if (pairs >= 2) {
                var evenZeros = 0
                var oddZeros = 0
                for (i in 0 until pairs * 2) {
                    if (head[i] != ZERO) continue
                    if (i % 2 == 0) evenZeros++ else oddZeros++
                }
                if (oddZeros * 2 > pairs && evenZeros * 10 < pairs) return Detected(UTF_16LE, 0)
                if (evenZeros * 2 > pairs && oddZeros * 10 < pairs) return Detected(UTF_16BE, 0)
            }
            return Detected(UTF_8, 0)
        }
    }
}
