package com.sherlog.core

import com.sherlog.parser.LogcatLineParser

/**
 * An indexer that is fed bytes incrementally and can produce a [LogIndex]
 * snapshot at any point. It owns the same growable primitive lists and
 * carry-buffer line scan that a one-shot index needs, so both the live-capture
 * path and [LogIndexer.index] (which drives this to completion over a whole
 * file) share one implementation.
 *
 * Thread-safe: [append] runs on the stream-reading coroutine while [snapshot]
 * runs on a separate ticker, so both hold an internal lock.
 */
class LiveLogIndexer(private val file: java.io.File) {

    private val lock = Any()

    private val offsets = LongList()      // one line-start per line; the sentinel is added at build time
    private val timestamps = LongList()
    private val pids = IntList()
    private val levels = ByteList()
    private val tagIds = IntList()
    private val tagLookup = HashMap<String, Int>()
    private val tagNames = ArrayList<String>()
    private val tagCounts = IntList()

    private val parsed = LogcatLineParser.Result()
    private var lastTimestampMs = 0L
    private var carry = ByteArray(4096)   // a line spanning append boundaries
    private var carryLen = 0
    private var lineStartOffset = 0L      // byte offset where the pending (carry) line begins
    private var totalBytes = 0L           // bytes fed so far

    /** Bytes fed so far — the ticker uses this to skip snapshots when nothing new arrived. */
    val byteCount: Long get() = synchronized(lock) { totalBytes }

    private fun addLine(bytes: ByteArray, from: Int, len: Int) {
        offsets.add(lineStartOffset)
        // Strip trailing \r for parsing; offsets keep the raw byte extent.
        var textLen = len
        if (textLen > 0 && bytes[from + textLen - 1] == '\r'.code.toByte()) textLen--
        val line = String(bytes, from, textLen, Charsets.UTF_8)
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
            // Unparsed lines (stack traces, buffer markers) inherit the previous
            // parsed line's timestamp so time-range filters keep them together.
            timestamps.add(lastTimestampMs)
            pids.add(-1)
            levels.add(0) // LogLevel.UNKNOWN
            tagIds.add(-1)
        }
    }

    /** Feeds [n] bytes from [buffer] (the rest of the array is ignored). */
    fun append(buffer: ByteArray, n: Int) = synchronized(lock) {
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
                lineStartOffset = totalBytes + i + 1
                segmentStart = i + 1
            }
            i++
        }
        val tail = n - segmentStart
        if (tail > 0) {
            carry = ensureCapacity(carry, carryLen + tail)
            System.arraycopy(buffer, segmentStart, carry, carryLen, tail)
            carryLen += tail
        }
        totalBytes += n
    }

    /**
     * A [LogIndex] over the complete lines seen so far. A partially-received
     * trailing line (still in the carry buffer) is excluded — the sentinel is
     * the start of that pending line, so [LineTextProvider] never reads it.
     */
    fun snapshot(): LogIndex = synchronized(lock) { build(sentinel = lineStartOffset) }

    /**
     * The final index for a fully-read file: the last line may lack a trailing
     * newline, so flush the carry as a real line and end the sentinel at the
     * true byte total.
     */
    fun finish(): LogIndex = synchronized(lock) {
        if (carryLen > 0) {
            addLine(carry, 0, carryLen)
            carryLen = 0
            lineStartOffset = totalBytes
        }
        build(sentinel = totalBytes)
    }

    private fun build(sentinel: Long): LogIndex {
        val lineCount = offsets.size
        val off = LongArray(lineCount + 1)
        for (k in 0 until lineCount) off[k] = offsets[k]
        off[lineCount] = sentinel

        val ts = timestamps.toArray()
        val tag = tagIds.toArray()
        // Leading unparsed lines (e.g. a "beginning of main" marker before the
        // first parsed line) inherited 0; backfill them with the first real
        // timestamp so time filters don't drop them.
        var i = 0
        while (i < tag.size && tag[i] < 0) i++
        if (i < tag.size) {
            val firstParsedTs = ts[i]
            for (j in 0 until i) ts[j] = firstParsedTs
        }

        return LogIndex(
            file = file,
            offsets = off,
            timestamps = ts,
            pids = pids.toArray(),
            levels = levels.toArray(),
            tagIds = tag,
            tags = tagNames.toTypedArray(),
            tagCounts = tagCounts.toArray(),
        )
    }

    private fun ensureCapacity(array: ByteArray, needed: Int): ByteArray =
        if (array.size >= needed) array else array.copyOf(Integer.highestOneBit(needed) shl 1)
}
