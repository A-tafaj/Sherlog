package com.sherlog.filter

import com.sherlog.model.LogLevel

/**
 * The complete filter configuration, as set in the UI.
 *
 * Tag/PID/level/time filters are resolved purely against the in-memory index.
 * [excludeTexts], [includeTexts] and [searchQuery] need line text, which
 * triggers a streaming pass over the file.
 */
/**
 * How a selection — checked tags, entered PIDs — is applied. Lines the
 * selection cannot describe (unparsed ones, which carry no tag and PID -1)
 * are dropped by [SHOW_ONLY] and left untouched by [HIDE].
 */
enum class FilterMode {
    /** Keep only lines the selection matches (empty selection = keep all). */
    SHOW_ONLY,

    /** Drop lines the selection matches. */
    HIDE,
}

data class FilterState(
    /** Tags checked in the tag list; interpreted per [tagMode]. Empty = no tag filter. */
    val selectedTags: Set<String> = emptySet(),
    val tagMode: FilterMode = FilterMode.SHOW_ONLY,
    /** Case-insensitive substrings; any match drops the line ("remove all lines containing these strings"). */
    val excludeTexts: List<String> = emptyList(),
    /** Case-insensitive substrings; when non-empty a line must contain at least one (crash preset). */
    val includeTexts: List<String> = emptyList(),
    /** PIDs entered in the PID field; interpreted per [pidMode]. Empty = no PID filter. */
    val pids: Set<Int> = emptySet(),
    val pidMode: FilterMode = FilterMode.SHOW_ONLY,
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
