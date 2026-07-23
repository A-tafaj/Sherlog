package com.sherlog.adb

import com.sherlog.core.LiveLogIndexer
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * One running logcat capture. Reads [input] (adb's stdout) to end-of-stream or
 * until [stop], teeing every byte to the capture file and the incremental
 * indexer. Takes a plain [InputStream] so tests drive it with a canned stream
 * and no real process.
 */
class LogcatSession(
    private val input: InputStream,
    private val process: Process? = null,
) {
    @Volatile
    private var stopped = false

    /**
     * Blocking read loop — call from an IO coroutine. Appends bytes to [file]
     * and feeds [indexer]; [onBytes] fires after each chunk so the caller can
     * schedule a snapshot. Returns when the stream ends or [stop] is called.
     */
    fun pump(file: File, indexer: LiveLogIndexer, onBytes: () -> Unit) {
        FileOutputStream(file, /* append = */ true).use { out ->
            val buffer = ByteArray(64 * 1024)
            while (!stopped) {
                val n = try {
                    input.read(buffer)
                } catch (_: IOException) {
                    break // stream closed under us by stop()
                }
                if (n < 0) break
                // File first, then index: a snapshot's offsets must point at
                // bytes already on disk for the provider to read them.
                out.write(buffer, 0, n)
                out.flush()
                indexer.append(buffer, n)
                onBytes()
            }
        }
    }

    /** Ends the capture: destroys the adb process and unblocks [pump]. */
    fun stop() {
        stopped = true
        runCatching { process?.destroy() }
        runCatching { input.close() }
    }
}
