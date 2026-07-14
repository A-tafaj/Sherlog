package com.sherlog.core

import com.sherlog.model.LogLevel
import java.io.File

/**
 * In-memory index of a log file. Holds per-line metadata in primitive arrays;
 * line text stays on disk and is fetched on demand via [LineTextProvider].
 */
class LogIndex(
    val file: File,
    /** Byte offset of each line start, plus one trailing sentinel = file length. Size = lineCount + 1. */
    val offsets: LongArray,
    /** Parser timestamp per line (ms since Jan 1 of the reference year); 0 when unparsed. */
    val timestamps: LongArray,
    /** PID per line; -1 when unparsed. */
    val pids: IntArray,
    /** LogLevel ordinal per line. */
    val levels: ByteArray,
    /** Index into [tags] per line; -1 when unparsed. */
    val tagIds: IntArray,
    /** Unique tags in first-seen order. */
    val tags: Array<String>,
    /** Line count per tag, parallel to [tags]. */
    val tagCounts: IntArray,
) {
    val lineCount: Int get() = offsets.size - 1

    /** Timestamp of the first/last parsed line; null when no line parsed. */
    val firstTimestampMs: Long? by lazy {
        for (i in 0 until lineCount) if (tagIds[i] >= 0) return@lazy timestamps[i]
        null
    }
    val lastTimestampMs: Long? by lazy {
        for (i in lineCount - 1 downTo 0) if (tagIds[i] >= 0) return@lazy timestamps[i]
        null
    }

    val errorCount: Int by lazy { countLevel(LogLevel.ERROR) + countLevel(LogLevel.FATAL) }
    val warningCount: Int by lazy { countLevel(LogLevel.WARN) }

    fun level(line: Int): LogLevel = LogLevel.fromByte(levels[line])

    /** Byte length of the line including its terminator(s). */
    fun lineByteLength(line: Int): Int = (offsets[line + 1] - offsets[line]).toInt()

    private fun countLevel(level: LogLevel): Int {
        val b = level.ordinal.toByte()
        var n = 0
        for (v in levels) if (v == b) n++
        return n
    }
}
