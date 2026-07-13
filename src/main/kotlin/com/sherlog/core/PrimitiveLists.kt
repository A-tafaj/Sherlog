package com.sherlog.core

/**
 * Growable primitive-array lists. Used instead of ArrayList<T> so a
 * 10M-line index costs ~25 bytes/line instead of boxed objects.
 */
class LongList(initialCapacity: Int = 1 shl 16) {
    var size: Int = 0
        private set
    private var data = LongArray(initialCapacity)

    fun add(value: Long) {
        if (size == data.size) data = data.copyOf(data.size + (data.size shr 1))
        data[size++] = value
    }

    operator fun get(index: Int): Long = data[index]

    fun toArray(): LongArray = data.copyOf(size)
}

class IntList(initialCapacity: Int = 1 shl 16) {
    var size: Int = 0
        private set
    private var data = IntArray(initialCapacity)

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size + (data.size shr 1))
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    operator fun set(index: Int, value: Int) {
        data[index] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}

class ByteList(initialCapacity: Int = 1 shl 16) {
    var size: Int = 0
        private set
    private var data = ByteArray(initialCapacity)

    fun add(value: Byte) {
        if (size == data.size) data = data.copyOf(data.size + (data.size shr 1))
        data[size++] = value
    }

    operator fun get(index: Int): Byte = data[index]

    fun toArray(): ByteArray = data.copyOf(size)
}
