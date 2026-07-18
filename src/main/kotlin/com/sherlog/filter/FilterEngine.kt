package com.sherlog.filter

import com.sherlog.core.IntList
import com.sherlog.core.LogIndex
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream

/**
 * Applies a [FilterState] to a [LogIndex], producing the matching line
 * indices in order.
 *
 * Metadata-only filters are answered from the index arrays (instant, no IO).
 * Filters that need line text stream the file once, sequentially, testing
 * only lines that already passed the metadata filters.
 */
object FilterEngine {

    /** A compiled search matcher, reused for viewer highlighting. */
    class SearchMatcher(query: String, isRegex: Boolean) {
        val regex: Regex? = when {
            query.isBlank() -> null
            isRegex -> runCatching { Regex(query, RegexOption.IGNORE_CASE) }.getOrNull()
            else -> Regex(Regex.escape(query), RegexOption.IGNORE_CASE)
        }
        /** True when the query was regex but failed to compile; treated as matching nothing. */
        val isInvalid: Boolean = query.isNotBlank() && regex == null

        fun matches(line: String): Boolean = regex?.containsMatchIn(line) ?: true
    }

    suspend fun apply(
        index: LogIndex,
        state: FilterState,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): IntArray {
        val ctx = currentCoroutineContext()
        val n = index.lineCount
        val result = IntList()

        // --- Metadata predicate, from index arrays only ---
        // tagIdFilter marks the tags that are ALLOWED, whichever the mode.
        val tagIdFilter: BooleanArray? = if (state.selectedTags.isEmpty()) null else {
            BooleanArray(index.tags.size) {
                val checked = index.tags[it] in state.selectedTags
                if (state.tagMode == FilterMode.HIDE) !checked else checked
            }
        }
        val levelFilter: BooleanArray? =
            if (state.levels.size == com.sherlog.model.LogLevel.entries.size) null
            else BooleanArray(com.sherlog.model.LogLevel.entries.size) {
                com.sherlog.model.LogLevel.entries[it] in state.levels
            }
        val from = state.timeFromMs
        val to = state.timeToMs

        fun metadataMatch(i: Int): Boolean {
            if (levelFilter != null && !levelFilter[index.levels[i].toInt()]) return false
            if (tagIdFilter != null) {
                val tagId = index.tagIds[i]
                if (tagId < 0) {
                    // Unparsed lines have no tag: hidden by a show-only
                    // selection, untouched by a hide selection.
                    if (state.tagMode == FilterMode.SHOW_ONLY) return false
                } else if (!tagIdFilter[tagId]) {
                    return false
                }
            }
            if (state.pids.isNotEmpty()) {
                // Unparsed lines hold PID -1, so they fall out of a show-only
                // selection and survive a hide selection, matching the tags.
                val hit = index.pids[i] in state.pids
                if (if (state.pidMode == FilterMode.HIDE) hit else !hit) return false
            }
            if (from != null || to != null) {
                // Unparsed lines carry the previous parsed line's timestamp,
                // so stack traces stay with their crash inside a time range.
                val ts = index.timestamps[i]
                if (from != null && ts < from) return false
                if (to != null && ts > to) return false
            }
            return true
        }

        if (!state.needsText) {
            for (i in 0 until n) {
                if (i and 0xFFFFF == 0) ctx.ensureActive()
                if (metadataMatch(i)) result.add(i)
            }
            onProgress(1, 1)
            return result.toArray()
        }

        // --- Text pass: stream the file sequentially ---
        val excludes = state.excludeTexts.filter { it.isNotBlank() }
        val includes = state.includeTexts.filter { it.isNotBlank() }
        val matcher = SearchMatcher(state.searchQuery, state.searchIsRegex)
        val totalBytes = index.offsets.last()

        BufferedInputStream(index.file.inputStream(), 1 shl 20).use { input ->
            var pos = 0L
            for (i in 0 until n) {
                if (i and 0xFFFF == 0) {
                    ctx.ensureActive()
                    onProgress(pos, totalBytes)
                }
                val start = index.offsets[i]
                val len = index.lineByteLength(i)
                if (!metadataMatch(i)) {
                    pos = skipTo(input, pos, start + len)
                    continue
                }
                pos = skipTo(input, pos, start)
                val bytes = ByteArray(len)
                var read = 0
                while (read < len) {
                    val r = input.read(bytes, read, len - read)
                    if (r < 0) break
                    read += r
                }
                pos += read
                var textLen = read
                while (textLen > 0 && (bytes[textLen - 1] == '\n'.code.toByte() || bytes[textLen - 1] == '\r'.code.toByte())) textLen--
                val line = String(bytes, 0, textLen, Charsets.UTF_8)

                if (excludes.any { line.contains(it, ignoreCase = true) }) continue
                if (includes.isNotEmpty() && includes.none { line.contains(it, ignoreCase = true) }) continue
                if (matcher.isInvalid || !matcher.matches(line)) continue
                result.add(i)
            }
        }
        onProgress(totalBytes, totalBytes)
        return result.toArray()
    }

    private fun skipTo(input: BufferedInputStream, pos: Long, target: Long): Long {
        var p = pos
        while (p < target) {
            val skipped = input.skip(target - p)
            if (skipped <= 0) break
            p += skipped
        }
        return p
    }
}
