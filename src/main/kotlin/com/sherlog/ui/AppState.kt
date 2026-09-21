package com.sherlog.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sherlog.core.LineTextProvider
import com.sherlog.core.LogIndex
import com.sherlog.core.LogIndexer
import com.sherlog.export.LogExporter
import com.sherlog.filter.FilterEngine
import com.sherlog.filter.FilterState
import com.sherlog.filter.HighlightCounter
import com.sherlog.filter.MatchNavigator
import com.sherlog.filter.Preset
import com.sherlog.filter.FilterMode
import com.sherlog.model.LogLevel
import com.sherlog.parser.LogcatLineParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** What the search bar does with what you type. */
enum class SearchMode {
    /** Drops lines that don't match — the view shrinks to the hits. */
    FILTER,

    /** Leaves every line in place and highlights the hits, as an editor does. */
    FIND,
}

/**
 * All state of one open file (one tab — see [Workspace]) and the background
 * jobs that mutate it. Snapshot state is safe to write from worker threads;
 * Compose picks the changes up.
 *
 * [indexDispatcher] runs indexing, so a [Workspace] opening a whole folder
 * can cap how many files are read at once.
 */
class AppState(
    private val scope: CoroutineScope,
    private val indexDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    // Loaded file
    /** The file this tab shows — set as soon as it starts opening; null for a blank tab. */
    var file by mutableStateOf<File?>(null)
        private set
    var index by mutableStateOf<LogIndex?>(null)
        private set
    var provider by mutableStateOf<LineTextProvider?>(null)
        private set

    /** True from [openFile] until indexing finishes or fails (also while queued behind other files). */
    var isLoading by mutableStateOf(false)
        private set
    @Volatile private var loadGeneration = 0

    /**
     * The viewer's scroll position. Kept here rather than remembered in the
     * UI so each tab returns to where it was when switched back to.
     */
    val listState = LazyListState()

    // Filter inputs (raw UI text; compiled into FilterState on apply)
    var selectedTags by mutableStateOf(emptySet<String>())
    var tagMode by mutableStateOf(FilterMode.SHOW_ONLY)
    var pidText by mutableStateOf("")
    var pidMode by mutableStateOf(FilterMode.SHOW_ONLY)
    var timeFromText by mutableStateOf("")
    var timeToText by mutableStateOf("")
    var excludeText by mutableStateOf("")
    var includeText by mutableStateOf("")
    var searchText by mutableStateOf("")
    var searchIsRegex by mutableStateOf(false)
    var searchMode by mutableStateOf(SearchMode.FILTER)
    var enabledLevels by mutableStateOf(LogLevel.entries.toSet())
    var tagSearchText by mutableStateOf("")
    var sortTagsByCount by mutableStateOf(true)

    /** Names of the presets currently toggled on; several may be active at once. */
    var selectedPresets by mutableStateOf(emptySet<String>())
        private set

    /** Text currently selected in the viewer; all its occurrences are highlighted. */
    var selectionHighlight by mutableStateOf("")
        private set

    /**
     * The needle that drives occurrence highlighting and next/prev navigation.
     * The search box wins whenever it is in Find mode with text; an in-line
     * double-click selection fills in only when the search box isn't claiming
     * the highlight.
     */
    val highlightNeedle: String
        get() = if (searchMode == SearchMode.FIND && searchText.isNotBlank()) searchText else selectionHighlight

    /** Whether [highlightNeedle] should be matched as a regex (only Find-mode search can be). */
    val highlightIsRegex: Boolean
        get() = searchMode == SearchMode.FIND && searchText.isNotBlank() && searchIsRegex

    /** Lines (within the current filter) containing [selectionHighlight]; null when inactive. */
    var highlightCount by mutableStateOf<Int?>(null)
        private set
    var highlightCounting by mutableStateOf(false)
        private set

    /** Positions within [filteredLines] that contain [selectionHighlight]; the next/prev targets. */
    var highlightMatches by mutableStateOf(IntArray(0))
        private set

    /** Index into [highlightMatches] of the match the user last navigated to or selected; -1 when none. */
    var currentMatchIndex by mutableStateOf(-1)
        private set

    /**
     * Position in [filteredLines] of the current match (the amber-highlighted
     * line); -1 when none. Hidden while counting: until a recount lands,
     * [highlightMatches] may still index the previous view.
     */
    val currentMatchPosition: Int
        get() = if (!highlightCounting && currentMatchIndex in highlightMatches.indices) highlightMatches[currentMatchIndex] else -1

    /**
     * File line of the current match, and the needle it matched. Match
     * positions shift on every recount (a filter change re-numbers the view),
     * so the line is what carries the current match across one — for the same
     * needle only; a new needle starts with no current match.
     */
    private var currentMatchLine = -1
    private var currentMatchNeedle = ""
    private var highlightJob: Job? = null

    // Results
    var appliedFilter by mutableStateOf(FilterState.EMPTY)
        private set
    var filteredLines by mutableStateOf(IntArray(0))
        private set

    // Progress / status
    var progress by mutableStateOf<Float?>(null)
        private set
    var progressLabel by mutableStateOf("")
        private set
    var statusMessage by mutableStateOf("Open a log file or a folder to begin.")
        private set

    private var workJob: Job? = null
    private var applyJob: Job? = null

    val isBusy: Boolean get() = progress != null

    fun openFile(file: File) {
        cancelWork()
        applyJob?.cancel()
        provider?.close()
        index = null
        provider = null
        filteredLines = IntArray(0)
        selectedTags = emptySet()
        selectionHighlight = ""
        highlightCount = null
        highlightCounting = false
        highlightMatches = IntArray(0)
        currentMatchIndex = -1
        currentMatchLine = -1
        this.file = file
        isLoading = true
        statusMessage = "Indexing ${file.name}…"
        // A superseded load's cleanup runs late, after the next load has
        // begun; the generation keeps it from clearing that one's isLoading.
        val generation = ++loadGeneration
        workJob = scope.launch(indexDispatcher) {
            try {
                val idx = LogIndexer.index(file) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else 0f
                    progressLabel = "Indexing: %,d / %,d MB".format(done shr 20, total shr 20)
                }
                // Cancelled while finishing the scan (tab closed, another file
                // opened): don't take a file handle nobody will release.
                ensureActive()
                index = idx
                provider = LineTextProvider(idx)
                // Pre-fill the time range with the log's actual span so the
                // user only edits the boundary they care about.
                timeFromText = idx.firstTimestampMs?.let(LogcatLineParser::formatTimestamp) ?: ""
                timeToText = idx.lastTimestampMs?.let(LogcatLineParser::formatTimestamp) ?: ""
                statusMessage = "%,d lines · %s".format(idx.lineCount, file.name)
                runFilter(idx)
            } catch (e: Exception) {
                if (isActive) statusMessage = "Failed to open ${file.name}: ${e.message}"
            } finally {
                progress = null
                if (generation == loadGeneration) isLoading = false
            }
        }
    }

    /** Puts a one-off notice in the status bar (or the empty viewer, for a blank tab). */
    fun showStatus(message: String) {
        statusMessage = message
    }

    /**
     * Takes over [source]'s filters — everything the filter panel and the
     * search box hold — and re-applies. Tags carry over by name; one this
     * file doesn't have simply matches nothing. The time range carries over
     * only where [source] narrowed it: its pre-filled full span says nothing
     * about this file's span, which may not even overlap.
     */
    fun copyFiltersFrom(source: AppState) {
        selectedTags = source.selectedTags
        tagMode = source.tagMode
        pidText = source.pidText
        pidMode = source.pidMode
        enabledLevels = source.enabledLevels
        excludeText = source.excludeText
        includeText = source.includeText
        selectedPresets = source.selectedPresets
        searchText = source.searchText
        searchIsRegex = source.searchIsRegex
        searchMode = source.searchMode
        timeFromText = if (source.timeFromText == source.fullSpanFromText) fullSpanFromText else source.timeFromText
        timeToText = if (source.timeToText == source.fullSpanToText) fullSpanToText else source.timeToText
        scheduleApply(0)
    }

    private val fullSpanFromText: String
        get() = index?.firstTimestampMs?.let(LogcatLineParser::formatTimestamp) ?: ""
    private val fullSpanToText: String
        get() = index?.lastTimestampMs?.let(LogcatLineParser::formatTimestamp) ?: ""

    /** The tab is closing: stop its work and release its file handle. */
    fun close() {
        loadGeneration++
        isLoading = false
        cancelWork()
        applyJob?.cancel()
        highlightJob?.cancel()
        provider?.close()
        provider = null
    }

    /** Re-applies filters after a short debounce; called on every filter-input change. */
    fun scheduleApply(debounceMs: Long = 300) {
        val idx = index ?: return
        applyJob?.cancel()
        applyJob = scope.launch(Dispatchers.IO) {
            delay(debounceMs)
            workJob?.cancel()
            workJob = scope.launch(Dispatchers.IO) {
                try {
                    runFilter(idx)
                } finally {
                    progress = null
                }
            }
        }
    }

    /**
     * The search box changed. In Filter mode the text narrows the view, so we
     * re-filter; in Find mode it only moves the highlight, so we skip the
     * filter pass entirely and just recount occurrences.
     */
    fun onSearchChanged() {
        if (searchMode == SearchMode.FILTER) scheduleApply(500) else scheduleHighlightCount(300)
    }

    /** Flips the search box between narrowing the view and highlighting in place. */
    fun toggleSearchMode() {
        searchMode = if (searchMode == SearchMode.FILTER) SearchMode.FIND else SearchMode.FILTER
        // Leaving Filter mode drops the search constraint; entering it adds one.
        // Re-filtering recomputes the highlight at its tail, covering both.
        scheduleApply(0)
    }

    /** Turns a preset on or off; the text fields are rebuilt from whatever stays selected. */
    fun togglePreset(preset: Preset) {
        selectedPresets =
            if (preset.name in selectedPresets) selectedPresets - preset.name
            else selectedPresets + preset.name
        val merged = Preset.merge(selectedPresets.mapNotNull(Preset::byName))
        excludeText = merged.excludeTexts.joinToString(", ")
        includeText = merged.includeTexts.joinToString(", ")
        scheduleApply(0)
    }

    /**
     * Hand-edits to the text fields. Once the user rewrites what a preset
     * wrote, the selection no longer describes the filter, so the presets are
     * deselected rather than left claiming credit for text they don't match.
     */
    fun editExcludeText(value: String) {
        excludeText = value
        selectedPresets = emptySet()
    }

    fun editIncludeText(value: String) {
        includeText = value
        selectedPresets = emptySet()
    }

    fun clearFilters() {
        selectedTags = emptySet()
        tagMode = FilterMode.SHOW_ONLY
        pidText = ""
        pidMode = FilterMode.SHOW_ONLY
        timeFromText = index?.firstTimestampMs?.let(LogcatLineParser::formatTimestamp) ?: ""
        timeToText = index?.lastTimestampMs?.let(LogcatLineParser::formatTimestamp) ?: ""
        excludeText = ""; includeText = ""; searchText = ""
        searchIsRegex = false
        searchMode = SearchMode.FILTER
        enabledLevels = LogLevel.entries.toSet()
        // Everything the panel shows resets too, or the UI keeps looking
        // filtered after a "clear": the tag list stays narrowed by its search
        // box and the status bar keeps counting the old selection.
        selectedPresets = emptySet()
        tagSearchText = ""
        clearHighlight()
        scheduleApply(0)
    }

    /**
     * How many filter categories are actually narrowing the view. Drives the
     * "0 lines — N filters active" explanation, so an empty result says why.
     * The time range only counts when it is tighter than the file's own span,
     * since the fields are pre-filled with that span on load.
     */
    val activeFilterCount: Int
        get() {
            val f = appliedFilter
            var n = 0
            if (f.selectedTags.isNotEmpty()) n++
            if (f.pids.isNotEmpty()) n++
            if (f.levels.size < LogLevel.entries.size) n++
            if (f.excludeTexts.isNotEmpty()) n++
            if (f.includeTexts.isNotEmpty()) n++
            if (f.searchQuery.isNotBlank()) n++
            val idx = index
            if (idx != null) {
                val narrowedStart = f.timeFromMs?.let { from -> idx.firstTimestampMs?.let { from > it } } ?: false
                val narrowedEnd = f.timeToMs?.let { to -> idx.lastTimestampMs?.let { to < it } } ?: false
                if (narrowedStart || narrowedEnd) n++
            }
            return n
        }

    fun export(target: File) {
        val idx = index ?: return
        val lines = filteredLines
        cancelWork()
        // Exporting onto the currently open file is allowed (the exporter
        // writes via a temp file), but our own read handle must be released
        // first or the final replace fails on Windows — and afterwards the
        // on-disk content no longer matches the index, so re-open it.
        val ontoOpenFile = runCatching { idx.file.canonicalFile == target.canonicalFile }.getOrDefault(false)
        if (ontoOpenFile) {
            provider?.close()
            provider = null
        }
        workJob = scope.launch(Dispatchers.IO) {
            try {
                LogExporter.export(idx, lines, target) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else 1f
                    progressLabel = "Exporting: %,d / %,d lines".format(done, total)
                }
                statusMessage = "Exported %,d lines to ${target.name}".format(lines.size)
                if (ontoOpenFile) openFile(target)
            } catch (e: CancellationException) {
                if (ontoOpenFile) openFile(idx.file) // restore the released provider
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    statusMessage = "Export failed: ${e.message}"
                    if (ontoOpenFile) openFile(idx.file) // restore the released provider
                }
            } finally {
                progress = null
            }
        }
    }

    /**
     * Called by the viewer when the user makes a new (latched) selection on
     * file line [lineIndex]. The selected occurrence becomes the current
     * match, so next/prev continue from it instead of from the first match.
     */
    fun onViewerSelection(lineIndex: Int, text: String) {
        if (searchMode == SearchMode.FIND && searchText.isNotBlank()) {
            // The search box owns the highlight, so a stray in-line selection
            // is remembered but must not kick off a recount. Clicking a found
            // line does make it the current match, as the caret does in an editor.
            selectionHighlight = text
            makeLineCurrent(lineIndex)
            return
        }
        if (text == selectionHighlight) {
            // Same text picked on another line: the matches are unchanged, so
            // just move the current one there.
            makeLineCurrent(lineIndex)
            return
        }
        selectionHighlight = text
        currentMatchLine = if (text.isEmpty()) -1 else lineIndex
        currentMatchNeedle = text
        scheduleHighlightCount()
    }

    /** Makes the match on file line [line] current, if that line is one. */
    private fun makeLineCurrent(line: Int) {
        if (highlightCounting) return
        val i = MatchNavigator.indexOfLine(filteredLines, highlightMatches, line)
        if (i < 0) return
        currentMatchIndex = i
        currentMatchLine = line
        currentMatchNeedle = highlightNeedle
    }

    /**
     * The Escape key: peel back one layer of search state. Clears the query
     * first, then any lingering highlight; returns false when there was
     * nothing to clear so the key can fall through.
     */
    fun onEscape(): Boolean {
        if (searchText.isNotBlank()) {
            searchText = ""
            if (searchMode == SearchMode.FILTER) scheduleApply(0) else scheduleHighlightCount(0)
            return true
        }
        if (selectionHighlight.isNotEmpty()) {
            clearHighlight()
            return true
        }
        return false
    }

    /** Explicitly drops the current highlight (the ✕ in the status bar). */
    fun clearHighlight() {
        // Clear whichever source is driving the highlight.
        if (searchMode == SearchMode.FIND && searchText.isNotBlank()) {
            searchText = ""
            scheduleHighlightCount(0)
            return
        }
        if (selectionHighlight.isEmpty()) return
        selectionHighlight = ""
        scheduleHighlightCount(0)
    }

    /**
     * Recounts how many filtered lines contain [highlightNeedle]. Debounced:
     * dragging a selection or typing a Find query fires this on every change,
     * and the count needs a streaming pass over the file.
     */
    private fun scheduleHighlightCount(debounceMs: Long = 400) {
        highlightJob?.cancel()
        val idx = index
        val needle = highlightNeedle
        val isRegex = highlightIsRegex
        if (idx == null || needle.isEmpty()) {
            highlightCount = null
            highlightCounting = false
            highlightMatches = IntArray(0)
            currentMatchIndex = -1
            return
        }
        highlightCounting = true
        highlightJob = scope.launch(Dispatchers.IO) {
            delay(debounceMs)
            val lines = filteredLines
            val result = runCatching { HighlightCounter.matches(idx, lines, needle, isRegex) }
            if (!isActive) return@launch
            val hits = result.getOrNull()
            highlightMatches = hits ?: IntArray(0)
            highlightCount = hits?.size
            // Keep the current match on its line if that line still matches:
            // a fresh selection, or a filter change that left it in view.
            currentMatchIndex =
                if (hits == null || needle != currentMatchNeedle) -1
                else MatchNavigator.indexOfLine(lines, hits, currentMatchLine)
            highlightCounting = false
        }
    }

    /**
     * Moves to the next occurrence — from the current match while it is on
     * screen, otherwise from the screen itself ([MatchNavigator]). [visible] is
     * the range of [filteredLines] positions on screen. Returns the new match's
     * position, or null when there are no matches — or while they are being
     * recounted, since until then they may index the previous view.
     */
    fun nextMatch(visible: IntRange): Int? =
        moveToMatch(MatchNavigator.next(highlightMatches, currentMatchIndex, visible))

    /** The mirror of [nextMatch]. */
    fun prevMatch(visible: IntRange): Int? =
        moveToMatch(MatchNavigator.prev(highlightMatches, currentMatchIndex, visible))

    private fun moveToMatch(matchIndex: Int): Int? {
        if (matchIndex < 0 || highlightCounting) return null
        currentMatchIndex = matchIndex
        val pos = highlightMatches[matchIndex]
        currentMatchLine = filteredLines.getOrElse(pos) { -1 }
        currentMatchNeedle = highlightNeedle
        return pos
    }

    fun cancelWork() {
        workJob?.cancel()
        workJob = null
        progress = null
    }

    /** Builds a FilterState from the raw UI inputs; null on invalid input (with status set). */
    fun buildFilterState(): FilterState? {
        val pids = pidText.split(',', ' ', ';')
            .filter { it.isNotBlank() }
            .map { it.trim().toIntOrNull() ?: run { statusMessage = "Invalid PID: \"${it.trim()}\""; return null } }
            .toSet()
        val from = timeFromText.trim().takeIf { it.isNotEmpty() }?.let {
            LogcatLineParser.parseTimestamp(it)
                ?: run { statusMessage = "Invalid time (use MM-DD HH:MM:SS): \"$it\""; return null }
        }
        val to = timeToText.trim().takeIf { it.isNotEmpty() }?.let {
            LogcatLineParser.parseTimestamp(it)
                ?: run { statusMessage = "Invalid time (use MM-DD HH:MM:SS): \"$it\""; return null }
        }
        return FilterState(
            selectedTags = selectedTags,
            tagMode = tagMode,
            excludeTexts = excludeText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            includeTexts = includeText.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            pids = pids,
            pidMode = pidMode,
            timeFromMs = from,
            timeToMs = to,
            levels = enabledLevels,
            // Find mode highlights in place instead of narrowing, so it puts
            // nothing into the filter — the query drives the highlight path.
            searchQuery = if (searchMode == SearchMode.FILTER) searchText else "",
            searchIsRegex = searchIsRegex,
        )
    }

    private suspend fun runFilter(idx: LogIndex) {
        val state = buildFilterState() ?: return
        appliedFilter = state
        val result = FilterEngine.apply(idx, state) { done, total ->
            if (state.needsText) {
                progress = if (total > 0) done.toFloat() / total else 0f
                progressLabel = "Filtering…"
            }
        }
        // The matches index the old view until the recount below lands; mark
        // them stale *before* swapping the view so navigation can't use them.
        if (highlightNeedle.isNotEmpty()) highlightCounting = true
        filteredLines = result
        progress = null
        val active = activeFilterCount
        statusMessage = if (result.isEmpty() && active > 0) {
            // An empty view is otherwise indistinguishable from a broken one.
            "0 lines — %d filter%s active".format(active, if (active == 1) "" else "s")
        } else buildString {
            append("%,d / %,d lines".format(result.size, idx.lineCount))
            if (state.searchQuery.isNotBlank()) append(" · %,d search matches".format(result.size))
        }
        // The highlight count is relative to the filtered set; recount. Also
        // with an empty needle, which clears the matches: Clear Filters can
        // empty a Find query without passing through a recount, and matches
        // left from it would index this new view.
        scheduleHighlightCount(0)
    }
}
