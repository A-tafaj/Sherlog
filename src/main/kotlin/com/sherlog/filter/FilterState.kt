package com.sherlog.filter

import com.sherlog.model.LogLevel

/**
 * The complete filter configuration, as set in the UI.
 *
 * Tag/PID/level/time filters are resolved purely against the in-memory index.
 * [excludeTexts], [includeTexts] and [searchQuery] need line text, which
 * triggers a streaming pass over the file.
 */
data class FilterState(
    /** Tags to keep. Empty = all tags. */
    val includedTags: Set<String> = emptySet(),
    /** Case-insensitive substrings; any match drops the line ("remove all lines containing these strings"). */
    val excludeTexts: List<String> = emptyList(),
    /** Case-insensitive substrings; when non-empty a line must contain at least one (crash preset). */
    val includeTexts: List<String> = emptyList(),
    /** PIDs to keep. Empty = all. */
    val pids: Set<Int> = emptySet(),
    /** Inclusive time range, in parser timestamp millis. */
    val timeFromMs: Long? = null,
    val timeToMs: Long? = null,
    /** Levels to keep. */
    val levels: Set<LogLevel> = LogLevel.entries.toSet(),
    /** Search query; acts as an additional constraint and drives highlighting. */
    val searchQuery: String = "",
    val searchIsRegex: Boolean = false,
) {
    val needsText: Boolean
        get() = excludeTexts.isNotEmpty() || includeTexts.isNotEmpty() || searchQuery.isNotBlank()

    companion object {
        val EMPTY = FilterState()
    }
}
