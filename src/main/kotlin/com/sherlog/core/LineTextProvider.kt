package com.sherlog.core

import java.io.Closeable
import java.io.RandomAccessFile

/**
 * Fetches individual line text from disk using the offsets in a [LogIndex],
 * so the viewer never needs the whole file in memory. Reads go through an
 * LRU cache of fixed-size blocks; scrolling mostly hits the cache.
 *
 * Thread-safe: the viewer fetches from IO coroutines.
 */
class LineTextProvider(private val index: LogIndex) : Closeable {

    private companion object {
        const val BLOCK_SIZE = 1 shl 16 // 64 KiB
        const val MAX_BLOCKS = 128      // 8 MiB cache
    }

    private val raf = RandomAccessFile(index.file, "r")
    private val blocks = object : LinkedHashMap<Long, ByteArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<Long, ByteArray>) = size > MAX_BLOCKS
    }
    private val lock = Any()

    /** Returns the text of [line] (0-based), without its trailing newline. */
    fun line(line: Int): String {
        val start = index.offsets[line]
        var len = index.lineByteLength(line)
        val bytes = ByteArray(len)
        synchronized(lock) {
            var copied = 0
            while (copied < len) {
                val pos = start + copied
                val blockId = pos / BLOCK_SIZE
                val block = blockAt(blockId)
                val inBlock = (pos % BLOCK_SIZE).toInt()
                val n = minOf(len - copied, block.size - inBlock)
                if (n <= 0) { len = copied; break } // truncated file since indexing
                System.arraycopy(block, inBlock, bytes, copied, n)
                copied += n
            }
        }
        // Strip line terminators kept in the byte extent.
        while (len > 0 && (bytes[len - 1] == '\n'.code.toByte() || bytes[len - 1] == '\r'.code.toByte())) len--
        return String(bytes, 0, len, Charsets.UTF_8)
    }

    private fun blockAt(blockId: Long): ByteArray {
        blocks[blockId]?.let { return it }
        val pos = blockId * BLOCK_SIZE
        val size = minOf(BLOCK_SIZE.toLong(), raf.length() - pos).toInt().coerceAtLeast(0)
        val data = ByteArray(size)
        if (size > 0) {
            raf.seek(pos)
            raf.readFully(data)
        }
        blocks[blockId] = data
        return data
    }

    override fun close() {
        synchronized(lock) { raf.close() }
    }
}
