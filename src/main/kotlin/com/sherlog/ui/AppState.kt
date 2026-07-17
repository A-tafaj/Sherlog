package com.sherlog.ui

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
import com.sherlog.filter.Preset
import com.sherlog.filter.TagMode
import com.sherlog.model.LogLevel
import com.sherlog.parser.LogcatLineParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * All UI state and the background jobs that mutate it. Snapshot state is
 * safe to write from worker threads; Compose picks the changes up.
 */
class AppState(private val scope: CoroutineScope) {

    // Loaded file
    var index by mutableStateOf<LogIndex?>(null)
        private set
    var provider by mutableStateOf<LineTextProvider?>(null)
        private set

    // Filter inputs (raw UI text; compiled into FilterState on apply)
    var selectedTags by mutableStateOf(emptySet<String>())
    var tagMode by mutableStateOf(TagMode.SHOW_ONLY)
    var pidText by mutableStateOf("")
    var timeFromText by mutableStateOf("")
    var timeToText by mutableStateOf("")
    var excludeText by mutableStateOf("")
    var includeText by mutableStateOf("")
    var searchText by mutableStateOf("")
    var searchIsRegex by mutableStateOf(false)
    var enabledLevels by mutableStateOf(LogLevel.entries.toSet())
    var tagSearchText by mutableStateOf("")
    var sortTagsByCount by mutableStateOf(true)

    /** Text currently selected in the viewer; all its occurrences are highlighted. */
    var selectionHighlight by mutableStateOf("")
        private set

    /** Lines (within the current filter) containing [selectionHighlight]; null when inactive. */
    var highlightCount by mutableStateOf<Int?>(null)
        private set
    var highlightCounting by mutableStateOf(false)
        private set

    /** Positions within [filteredLines] that contain [selectionHighlight]; the next/prev targets. */
    var highlightMatches by mutableStateOf(IntArray(0))
        private set

    /** Index into [highlightMatches] of the match the user last navigated to; -1 before any jump. */
    var currentMatchIndex by mutableStateOf(-1)
        private set

    /** Position in [filteredLines] of the current match (the amber-highlighted line); -1 when none. */
    val currentMatchPosition: Int
        get() = if (currentMatchIndex in highlightMatches.indices) highlightMatches[currentMatchIndex] else -1
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
    var statusMessage by mutableStateOf("Open a logcat file to begin.")
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
        statusMessage = "Indexing ${file.name}…"
        workJob = scope.launch(Dispatchers.IO) {
            try {
                val idx = LogIndexer.index(file) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else 0f
                    progressLabel = "Indexing: %,d / %,d MB".format(done shr 20, total shr 20)
                }
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
            }
        }
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

    fun applyPreset(preset: Preset) {
        excludeText = preset.excludeTexts.joinToString(", ")
        includeText = preset.includeTexts.joinToString(", ")
        scheduleApply(0)
    }

    fun clearFilters() {
        selectedTags = emptySet()
        tagMode = TagMode.SHOW_ONLY
        pidText = ""
        timeFromText = index?.firstTimestampMs?.let(LogcatLineParser::formatTimestamp) ?: ""
        timeToText = index?.lastTimestampMs?.let(LogcatLineParser::formatTimestamp) ?: ""
        excludeText = ""; includeText = ""; searchText = ""
        searchIsRegex = false
        enabledLevels = LogLevel.entries.toSet()
        scheduleApply(0)
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

    /** Called by the viewer when the user makes a new (latched) selection. */
    fun onViewerSelection(text: String) {
        if (text == selectionHighlight) return
        selectionHighlight = text
        scheduleHighlightCount()
    }

    /** Explicitly drops the latched selection highlight (from the status bar). */
    fun clearHighlight() {
        if (selectionHighlight.isEmpty()) return
        selectionHighlight = ""
        scheduleHighlightCount(0)
    }

    /**
     * Recounts how many filtered lines contain the selected text. Debounced:
     * dragging a selection fires this on every change, and the count needs a
     * streaming pass over the file.
     */
    private fun scheduleHighlightCount(debounceMs: Long = 400) {
        highlightJob?.cancel()
        val idx = index
        val needle = selectionHighlight
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
            val result = runCatching { HighlightCounter.matches(idx, filteredLines, needle) }
            if (!isActive) return@launch
            val hits = result.getOrNull()
            highlightMatches = hits ?: IntArray(0)
            highlightCount = hits?.size
            currentMatchIndex = -1
            highlightCounting = false
        }
    }

    /** Advances to the next occurrence (wrapping); returns its position in [filteredLines], or null. */
    fun nextMatch(): Int? {
        val n = highlightMatches.size
        if (n == 0) return null
        currentMatchIndex = if (currentMatchIndex + 1 >= n) 0 else currentMatchIndex + 1
        return highlightMatches[currentMatchIndex]
    }

    /** Steps back to the previous occurrence (wrapping); returns its position in [filteredLines], or null. */
    fun prevMatch(): Int? {
        val n = highlightMatches.size
        if (n == 0) return null
        currentMatchIndex = if (currentMatchIndex <= 0) n - 1 else currentMatchIndex - 1
        return highlightMatches[currentMatchIndex]
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
            timeFromMs = from,
            timeToMs = to,
            levels = enabledLevels,
            searchQuery = searchText,
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
        filteredLines = result
        progress = null
        statusMessage = buildString {
            append("%,d / %,d lines".format(result.size, idx.lineCount))
            if (state.searchQuery.isNotBlank()) append(" · %,d search matches".format(result.size))
        }
        // The highlight count is relative to the filtered set; recount.
        if (selectionHighlight.isNotEmpty()) scheduleHighlightCount(0)
    }
}
