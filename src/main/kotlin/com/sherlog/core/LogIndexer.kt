package com.sherlog.core

import com.sherlog.parser.LogcatLineParser
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/**
 * Streams a log file once and builds a [LogIndex]. Never holds more than one
 * read buffer of file content in memory, so 1GB+ files are fine. UTF-8 and
 * UTF-16 files are both read as-is ([LogEncoding]); nothing is converted.
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
        val (encoding, bomLength) = LogEncoding.detect(file)
        val unit = encoding.unitSize

        val offsets = LongList()
        val timestamps = LongList()
        val pids = IntList()
        val levels = ByteList()
        val tagIds = IntList()
        val tagLookup = HashMap<String, Int>()
        val tagNames = ArrayList<String>()
        val tagCounts = IntList()

        val parsed = LogcatLineParser.Result()
        // Unparsed lines (stack traces, buffer markers) inherit the timestamp
        // of the preceding parsed line so time-range filters keep them with
        // their context.
        var lastTimestampMs = 0L
        // Carry buffer for a line spanning read-buffer boundaries.
        var carry = ByteArray(4096)
        var carryLen = 0
        // The BOM is not part of any line: the first one starts after it.
        var lineStartOffset = bomLength.toLong()
        // File offset of buffer[0].
        var bytesRead = bomLength.toLong()
        var nextProgressAt = 0L

        fun addLine(bytes: ByteArray, from: Int, len: Int) {
            offsets.add(lineStartOffset)
            // Decoding drops the trailing \r; offsets keep the raw extent.
            val line = encoding.decodeLine(bytes, from, len)
            if (LogcatLineParser.parse(line, parsed)) {
                timestamps.add(parsed.timestampMs)
                lastTimestampMs = parsed.timestampMs
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
                timestamps.add(lastTimestampMs)
                pids.add(-1)
                levels.add(0) // LogLevel.UNKNOWN
                tagIds.add(-1)
            }
        }

        file.inputStream().use { input ->
            input.skipNBytes(bomLength.toLong())
            val buffer = ByteArray(READ_BUFFER_SIZE)
            // UTF-16 is scanned a whole code unit at a time. A read ending
            // mid-unit leaves its stray byte ("held") at the front of the
            // buffer for the next read, so a unit never splits across reads.
            var held = 0
            while (true) {
                val r = input.read(buffer, held, buffer.size - held)
                if (r < 0) break
                val avail = held + r
                val n = avail - avail % unit
                var segmentStart = 0
                var i = 0
                while (i < n) {
                    val lineFeed = if (unit == 1) buffer[i] == '\n'.code.toByte() else encoding.isLineFeedAt(buffer, i)
                    if (lineFeed) {
                        val segLen = i - segmentStart
                        if (carryLen > 0) {
                            carry = ensureCapacity(carry, carryLen + segLen)
                            System.arraycopy(buffer, segmentStart, carry, carryLen, segLen)
                            addLine(carry, 0, carryLen + segLen)
                            carryLen = 0
                        } else {
                            addLine(buffer, segmentStart, segLen)
                        }
                        lineStartOffset = bytesRead + i + unit
                        segmentStart = i + unit
                    }
                    i += unit
                }
                // Stash the unterminated tail for the next read.
                val tail = n - segmentStart
                if (tail > 0) {
                    carry = ensureCapacity(carry, carryLen + tail)
                    System.arraycopy(buffer, segmentStart, carry, carryLen, tail)
                    carryLen += tail
                }
                bytesRead += n
                held = avail - n
                if (held > 0) System.arraycopy(buffer, n, buffer, 0, held)
                if (bytesRead >= nextProgressAt) {
                    ctx.ensureActive()
                    onProgress(bytesRead, totalBytes)
                    nextProgressAt = bytesRead + PROGRESS_EVERY_BYTES
                }
            }
            // A stray byte at EOF (a UTF-16 capture cut off mid-character)
            // still belongs to the last line.
            if (held > 0) {
                carry = ensureCapacity(carry, carryLen + held)
                System.arraycopy(buffer, 0, carry, carryLen, held)
                carryLen += held
            }
        }
        // Final line without a trailing newline.
        if (carryLen > 0) addLine(carry, 0, carryLen)
        offsets.add(totalBytes) // sentinel
        // Unparsed lines BEFORE the first parsed line (e.g. a leading
        // "--------- beginning of main" marker) inherited 0; backfill them
        // with the first parsed timestamp so time filters don't drop them.
        run {
            var i = 0
            while (i < tagIds.size && tagIds[i] < 0) i++
            if (i < tagIds.size) {
                val firstParsedTs = timestamps[i]
                for (j in 0 until i) timestamps[j] = firstParsedTs
            }
        }
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
            encoding = encoding,
        )
    }

    private fun ensureCapacity(array: ByteArray, needed: Int): ByteArray =
        if (array.size >= needed) array else array.copyOf(Integer.highestOneBit(needed) shl 1)
}
