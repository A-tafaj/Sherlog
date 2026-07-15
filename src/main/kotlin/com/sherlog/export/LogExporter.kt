package com.sherlog.export

import com.sherlog.core.LogIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Streams the given line indices from the source file into [target].
 * Sequential single pass; never holds more than one line in memory.
 *
 * The output is written to a temporary sibling file and moved over [target]
 * only after the whole pass succeeds. This makes exporting onto the source
 * file itself safe (writing to [target] directly would truncate the source
 * before it is read) and guarantees a cancelled or failed export never
 * leaves a partial [target] behind.
 *
 * NOTE for callers: if [target] is open elsewhere in the app (e.g. it is the
 * currently viewed file), that handle must be closed before calling, or the
 * final replace fails on Windows.
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
        val absoluteTarget = target.absoluteFile
        val tmp = File(absoluteTarget.parentFile, absoluteTarget.name + ".exporting.tmp")
        try {
            BufferedInputStream(index.file.inputStream(), 1 shl 20).use { input ->
                tmp.outputStream().buffered(1 shl 20).use { out ->
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
            Files.move(tmp.toPath(), absoluteTarget.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete() // no-op when the move succeeded
        }
        onProgress(total, total)
    }
}
