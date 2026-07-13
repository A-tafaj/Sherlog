package com.sherlog.export

import com.sherlog.core.LogIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File

/**
 * Streams the given line indices from the source file into [target].
 * Sequential single pass; never holds more than one line in memory.
 */
object LogExporter {

    suspend fun export(
        index: LogIndex,
        lines: IntArray,
        target: File,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ) {
        val ctx = currentCoroutineContext()
        val total = lines.size.toLong()
        BufferedInputStream(index.file.inputStream(), 1 shl 20).use { input ->
            target.outputStream().buffered(1 shl 20).use { out ->
                var pos = 0L
                var written = 0L
                var buffer = ByteArray(8192)
                for (i in lines) {
                    if (written and 0xFFFL == 0L) {
                        ctx.ensureActive()
                        onProgress(written, total)
                    }
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
                    out.write(buffer, 0, read)
                    // Guarantee a terminator when the source line lacked one (last line).
                    if (read == 0 || buffer[read - 1] != '\n'.code.toByte()) out.write('\n'.code)
                    written++
                }
            }
        }
        onProgress(total, total)
    }
}
