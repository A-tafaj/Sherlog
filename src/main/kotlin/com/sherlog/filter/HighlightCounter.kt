package com.sherlog.filter

import com.sherlog.core.LogIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream

/**
 * Counts how many of the given lines contain a substring
 * (case-insensitive). Sequential single pass over the file, like
 * [FilterEngine]'s text pass; cancellable via the calling coroutine.
 */
object HighlightCounter {

    suspend fun count(index: LogIndex, lines: IntArray, needle: String): Int {
        if (needle.isEmpty() || lines.isEmpty()) return 0
        val ctx = currentCoroutineContext()
        var count = 0
        BufferedInputStream(index.file.inputStream(), 1 shl 20).use { input ->
            var pos = 0L
            var buffer = ByteArray(8192)
            for ((k, i) in lines.withIndex()) {
                if (k and 0xFFFF == 0) ctx.ensureActive()
                val start = index.offsets[i]
                while (pos < start) {
                    val skipped = input.skip(start - pos)
                    if (skipped <= 0) break
                    pos += skipped
                }
                val len = index.lineByteLength(i)
                if (buffer.size < len) buffer = ByteArray(len)
                var read = 0
                while (read < len) {
                    val r = input.read(buffer, read, len - read)
                    if (r < 0) break
                    read += r
                }
                pos += read
                var textLen = read
                while (textLen > 0 && (buffer[textLen - 1] == '\n'.code.toByte() || buffer[textLen - 1] == '\r'.code.toByte())) textLen--
                val line = String(buffer, 0, textLen, Charsets.UTF_8)
                if (line.contains(needle, ignoreCase = true)) count++
            }
        }
        return count
    }
}
