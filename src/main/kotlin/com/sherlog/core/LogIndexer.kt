package com.sherlog.core

import com.sherlog.parser.LogcatLineParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Streams a log file once and builds a [LogIndex]. Never holds more than one
 * read buffer of file content in memory, so 1GB+ files are fine.
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

        val offsets = LongList()
        val timestamps = LongList()
        val pids = IntList()
        val levels = ByteList()
        val tagIds = IntList()
        val tagLookup = HashMap<String, Int>()
        val tagNames = ArrayList<String>()
        val tagCounts = IntList()

        val parsed = LogcatLineParser.Result()
        // Carry buffer for a line spanning read-buffer boundaries.
        var carry = ByteArray(4096)
        var carryLen = 0
        var lineStartOffset = 0L
        var bytesRead = 0L
        var nextProgressAt = 0L

        fun addLine(bytes: ByteArray, from: Int, len: Int) {
            offsets.add(lineStartOffset)
            // Strip trailing \r for parsing; offsets keep the raw extent.
            var textLen = len
            if (textLen > 0 && bytes[from + textLen - 1] == '\r'.code.toByte()) textLen--
            val line = String(bytes, from, textLen, Charsets.UTF_8)
            if (LogcatLineParser.parse(line, parsed)) {
                timestamps.add(parsed.timestampMs)
                pids.add(parsed.pid)
                levels.add(parsed.level.ordinal.toByte())
                val tagId = tagLookup.getOrPut(parsed.tag) {
                    tagNames.add(parsed.tag)
                    tagCounts.add(0)
                    tagNames.size - 1
                }
                tagCounts[tagId] = tagCounts[tagId] + 1
                tagIds.add(tagId)
            } else {
                timestamps.add(0L)
                pids.add(-1)
                levels.add(0) // LogLevel.UNKNOWN
                tagIds.add(-1)
            }
        }

        file.inputStream().use { input ->
            val buffer = ByteArray(READ_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                var segmentStart = 0
                var i = 0
                while (i < n) {
                    if (buffer[i] == '\n'.code.toByte()) {
                        val segLen = i - segmentStart
                        if (carryLen > 0) {
                            carry = ensureCapacity(carry, carryLen + segLen)
                            System.arraycopy(buffer, segmentStart, carry, carryLen, segLen)
                            addLine(carry, 0, carryLen + segLen)
                            carryLen = 0
                        } else {
                            addLine(buffer, segmentStart, segLen)
                        }
                        lineStartOffset = bytesRead + i + 1
                        segmentStart = i + 1
                    }
                    i++
                }
                // Stash the unterminated tail for the next read.
                val tail = n - segmentStart
                if (tail > 0) {
                    carry = ensureCapacity(carry, carryLen + tail)
                    System.arraycopy(buffer, segmentStart, carry, carryLen, tail)
                    carryLen += tail
                }
                bytesRead += n
                if (bytesRead >= nextProgressAt) {
                    ctx.ensureActive()
                    onProgress(bytesRead, totalBytes)
                    nextProgressAt = bytesRead + PROGRESS_EVERY_BYTES
                }
            }
        }
        // Final line without a trailing newline.
        if (carryLen > 0) addLine(carry, 0, carryLen)
        offsets.add(totalBytes) // sentinel
        onProgress(totalBytes, totalBytes)

        return LogIndex(
            file = file,
            offsets = offsets.toArray(),
            timestamps = timestamps.toArray(),
            pids = pids.toArray(),
            levels = levels.toArray(),
            tagIds = tagIds.toArray(),
            tags = tagNames.toTypedArray(),
            tagCounts = tagCounts.toArray(),
        )
    }

    private fun ensureCapacity(array: ByteArray, needed: Int): ByteArray =
        if (array.size >= needed) array else array.copyOf(Integer.highestOneBit(needed) shl 1)
}
