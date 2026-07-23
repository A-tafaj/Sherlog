package com.sherlog.core

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Streams a log file once and builds a [LogIndex]. Never holds more than one
 * read buffer of file content in memory, so 1GB+ files are fine. The line scan
 * itself lives in [LiveLogIndexer]; this just drives it to completion over a
 * whole file, adding progress reporting and cancellation.
 */
object LogIndexer {

    private const val READ_BUFFER_SIZE = 1 shl 20 // 1 MiB
    private const val PROGRESS_EVERY_BYTES = 8L shl 20

    /**
     * [onProgress] receives (bytesRead, totalBytes). Cancellation is
     * cooperative via the calling coroutine's job.
     */
    suspend fun index(file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): LogIndex {
        val ctx = currentCoroutineContext()
        val totalBytes = file.length()
        val indexer = LiveLogIndexer(file)
        var bytesRead = 0L
        var nextProgressAt = 0L

        file.inputStream().use { input ->
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                indexer.append(buffer, n)
                bytesRead += n
                if (bytesRead >= nextProgressAt) {
                    ctx.ensureActive()
                    onProgress(bytesRead, totalBytes)
                    nextProgressAt = bytesRead + PROGRESS_EVERY_BYTES
                }
            }
        }
        onProgress(totalBytes, totalBytes)
        return indexer.finish()
    }
}
