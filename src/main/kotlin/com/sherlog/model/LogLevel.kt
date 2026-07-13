package com.sherlog.model

/**
 * Android logcat severity levels. [UNKNOWN] is used for lines that do not
 * match the threadtime format (e.g. "--------- beginning of main" markers).
 */
enum class LogLevel(val letter: Char) {
    UNKNOWN('?'),
    VERBOSE('V'),
    DEBUG('D'),
    INFO('I'),
    WARN('W'),
    ERROR('E'),
    FATAL('F');

    companion object {
        private val byLetter = entries.associateBy { it.letter }

        fun fromLetter(c: Char): LogLevel = byLetter[c] ?: UNKNOWN

        fun fromByte(b: Byte): LogLevel = entries[b.toInt()]
    }
}
