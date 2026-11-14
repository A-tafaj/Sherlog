package com.sherlog.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.sherlog.core.IntList
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.BitSet

/** What the search bar does with what you type. */
enum class SearchMode {
    /** Drops lines that don't match — the view shrinks to the hits. */
    FILTER,

    /** Leaves every line in place and highlights the hits, as an editor does. */
    FIND,
}

/** Most lines one copy puts on the clipboard (~10 MB of typical logcat). */
private const val MAX_COPY_LINES = 100_000

/** Keystrokes in one line closer together than this undo as one step. */
private const val TYPING_RUN_MS = 1_000L
private const val MAX_UNDO = 500

/** Two ascending arrays as one ascending array. */
private fun mergeSorted(a: IntArray, b: IntArray): IntArray {
    val out = IntArray(a.size + b.size)
    var i = 0
    var j = 0
    var k = 0
    while (i < a.size && j < b.size) out[k++] = if (a[i] <= b[j]) a[i++] else b[j++]
    while (i < a.size) out[k++] = a[i++]
    while (j < b.size) out[k++] = b[j++]
    return out
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
    @Volatile private var highlightGeneration = 0

    /**
     * Lines selected across rows, as positions in [filteredLines]: whole
     * lines, for copying. A selection inside one line belongs to that row's
     * text field and drives the highlight instead. Null when none.
     */
    var lineSelection by mutableStateOf<IntRange?>(null)
        private set
    /** Where a Shift+click selection starts: the line pressed last. */
    private var selectionAnchor = -1

    // Results
    var appliedFilter by mutableStateOf(FilterState.EMPTY)
        private set
    var filteredLines by mutableStateOf(IntArray(0))
        private set

    // Unsaved edits. They sit beside the file, never in it, until Save writes
    // them out: the file and its index stay as they are, so byte offsets hold.
    // Only changed lines are kept, which is what keeps a 1 GB file workable.

    /** The new text of the lines changed since the last save, by file line. */
    val editedLines = mutableStateMapOf<Int, String>()

    /** File lines deleted since the last save. Replaced, never mutated, so Compose sees changes. */
    var deletedLines by mutableStateOf(BitSet())
        private set
    var deletedCount by mutableIntStateOf(0)
        private set

    /** Whether there are changes Save would write (the ● on the tab). */
    val isDirty: Boolean get() = editedLines.isNotEmpty() || deletedCount > 0

    /** A copy of [editedLines] for work off the UI thread. */
    fun editsSnapshot(): Map<Int, String> = if (editedLines.isEmpty()) emptyMap() else editedLines.toMap()

    // Progress / status
    var progress by mutableStateOf<Float?>(null)
        private set
    var progressLabel by mutableStateOf("")
        private set
    /** The latest message ("Copied …", "Filters applied …"); empty when there's none. */
    var statusMessage by mutableStateOf("Open a log file or a folder to begin.")
        private set

    /**
     * "1,234 / 5,678 lines": what the view shows of the file, which the status
     * bar keeps in front of [statusMessage] at all times. Deleted lines no
     * longer count as the file's. Null until a file is loaded.
     */
    val lineCountText: String?
        get() = index?.let { "%,d / %,d lines".format(filteredLines.size, it.lineCount - deletedCount) }

    /**
     * A heads-up the status bar keeps showing beside [statusMessage], which
     * every re-filter rewrites: "this isn't logcat", "these files were
     * skipped". Gone when dismissed or when another file loads in this tab.
     */
    var notice by mutableStateOf<String?>(null)
        private set

    fun addNotice(text: String) {
        notice = notice?.let { "$it · $text" } ?: text
    }

    fun dismissNotice() {
        notice = null
    }

    private var workJob: Job? = null
    private var applyJob: Job? = null

    val isBusy: Boolean get() = progress != null

    /**
     * Set for an Untitled tab ("Untitled 2"): a new file with no place yet. It
     * lives in a temp file holding one empty line — so it is an ordinary tab
     * to everything else, edits and undo included — until Save As gives it a
     * real one. The temp file goes when the tab moves on or closes.
     */
    var untitledName by mutableStateOf<String?>(null)
        private set
    val isUntitled: Boolean get() = untitledName != null

    /** What the tab is called: the file's name, or its Untitled name. */
    val displayName: String get() = untitledName ?: file?.name.orEmpty()

    /** Set when the caret should go straight into the first line (a new Untitled tab). */
    var wantsCaret by mutableStateOf(false)

    /** Makes this an empty Untitled tab called [name]. */
    fun openUntitled(name: String) {
        val temp = File.createTempFile("sherlog-untitled-", ".txt").apply {
            writeText("\n") // one empty line to type into
            deleteOnExit()
        }
        openFile(temp, untitled = name)
        wantsCaret = true
    }

    /** [untitled]: the name for an Untitled tab's temp file; null for a real file. */
    fun openFile(file: File, untitled: String? = null) {
        // An Untitled tab's temp file is done with once the tab shows another.
        val leftTemp = this.file.takeIf { untitledName != null && it != file }
        untitledName = untitled
        cancelWork()
        applyJob?.cancel()
        provider?.close()
        leftTemp?.delete()
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
        lineSelection = null
        selectionAnchor = -1
        notice = null
        // Edits key on this file's lines; a (re)loaded file has its own. So
        // does the undo history: its line numbers mean nothing after a save.
        editedLines.clear()
        deletedLines = BitSet()
        deletedCount = 0
        undoStack.clear()
        redoStack.clear()
        this.file = file
        isLoading = true
        statusMessage = "Indexing $displayName…"
        // A superseded load's cleanup runs late, after the next load has
        // begun; the generation keeps it from clearing that one's isLoading.
        val generation = ++loadGeneration
        // ATOMIC: even cancelled before it starts, the body runs (and stops at
        // its first check), so the finally below always settles isLoading.
        workJob = scope.launch(indexDispatcher, start = CoroutineStart.ATOMIC) {
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
                // Every parsed line has a tag (maybe an empty one), so no tags
                // means no line was logcat. Without saying so, a plain text
                // file looks like a log that failed to colour. (A new file
                // is plain text on purpose.)
                if (idx.lineCount > 0 && idx.tags.isEmpty() && untitled == null) {
                    addNotice("No logcat lines found — showing it as plain text")
                }
                runFilter(idx)
            } catch (e: Exception) {
                if (isActive) statusMessage = "Failed to open $displayName: ${e.message}"
            } finally {
                progress = null
                if (generation == loadGeneration) isLoading = false
            }
        }
    }

    /**
     * Brings file line [line] into view and, for a plain-substring [needle],
     * makes it the current match the way a double-clicked occurrence does.
     * Returns false when the line is no longer in this tab's filtered view.
     *
     * The scroll goes through `requestScrollToItem`, not `scrollToItem`: this
     * is called for a tab that may not be composed yet, and the suspending one
     * waits for that list's first layout, which never comes.
     */
    fun revealLine(line: Int, needle: String = ""): Boolean {
        val position = filteredLines.binarySearch(line)
        if (position < 0) return false
        if (needle.isNotEmpty()) {
            if (needle == highlightNeedle) {
                // The matches already stand for this needle, so just point the
                // current one at this line (or let an in-flight recount land
                // on it — it resolves currentMatchLine at its tail).
                currentMatchLine = line
                currentMatchNeedle = needle
                makeLineCurrent(line)
            } else {
                onViewerSelection(line, needle)
            }
        }
        listState.requestScrollToItem(position)
        return true
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

    /** A plain press on the line at [pos]: it anchors a later Shift+click, and drops any line selection. */
    fun onLinePressed(pos: Int) {
        selectionAnchor = pos
        lineSelection = null
    }

    /** Selects the lines at positions [from]..[to] (either order), inclusive. */
    fun selectLines(from: Int, to: Int) {
        if (selectionAnchor < 0) selectionAnchor = from
        lineSelection = minOf(from, to)..maxOf(from, to)
        // Across lines there is no one piece of text to highlight, so drop the
        // one a drag picked up in its first line. A Find query keeps its own.
        if (selectionHighlight.isNotEmpty() && !(searchMode == SearchMode.FIND && searchText.isNotBlank())) {
            clearHighlight()
        }
    }

    /** Ctrl+A: every line in the view (the filtered lines, not the whole file). */
    fun selectAllLines() {
        if (filteredLines.isEmpty()) return
        selectionAnchor = 0
        selectLines(0, filteredLines.size - 1)
    }

    /** Shift+click on the line at [pos]: selects from the line pressed last. */
    fun extendLineSelection(pos: Int) = selectLines(if (selectionAnchor >= 0) selectionAnchor else pos, pos)

    fun clearLineSelection() {
        lineSelection = null
    }

    /**
     * Hands the selected lines — exactly as in the file, one per line — to
     * [write] (the clipboard), read off the UI thread. Capped at [maxLines]:
     * past that, Export Filtered is the tool, not the clipboard.
     */
    fun copySelectedLines(write: (String) -> Unit, maxLines: Int = MAX_COPY_LINES) {
        val range = lineSelection ?: return
        val prov = provider ?: return
        val lines = filteredLines
        val edits = editsSnapshot()
        scope.launch(Dispatchers.IO) {
            val total = range.last - range.first + 1
            val n = minOf(total, maxLines)
            val text = runCatching {
                buildString {
                    for (k in 0 until n) {
                        if (k > 0) append(System.lineSeparator())
                        val line = lines[range.first + k]
                        // A row can hold new lines typed into it.
                        append(edits[line]?.replace("\n", System.lineSeparator()) ?: prov.line(line))
                    }
                }
            }.getOrElse {
                statusMessage = "Copy failed: ${it.message}"
                return@launch
            }
            write(text)
            statusMessage = if (n < total) {
                "Copied the first %,d of %,d selected lines — use Export Filtered for more.".format(n, total)
            } else {
                "Copied %,d line%s.".format(n, if (n == 1) "" else "s")
            }
        }
    }

    /** The tab is closing: stop its work and release its file handle. */
    fun close() {
        loadGeneration++
        isLoading = false
        cancelWork()
        applyJob?.cancel()
        highlightJob?.cancel()
        provider?.close()
        provider = null
        if (isUntitled) file?.delete()
    }

    /** Re-applies filters after a short debounce; called on every filter-input change. */
    fun scheduleApply(debounceMs: Long = 300) {
        val idx = index ?: return
        applyJob?.cancel()
        applyJob = scope.launch(Dispatchers.IO) {
            delay(debounceMs)
            // The file was (re)opened meanwhile: that load filters on its own,
            // and its job is the "work" this would cancel — a load cancelled
            // before it starts never clears isLoading. (Seen after Save.)
            if (index !== idx) return@launch
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

    /**
     * Ctrl+F: puts the text selected inside a line into the search box, so
     * searching for it takes no copy-and-paste. The search mode is left
     * alone — Filter narrows the view to it, Find highlights it in place.
     * Returns whether there was a selection to search for.
     */
    fun useSelectionAsSearch(): Boolean {
        val text = selectionHighlight
        if (text.isEmpty()) return false
        if (text == searchText) return true
        searchText = text
        // A deliberate gesture rather than typing, so no debounce.
        if (searchMode == SearchMode.FILTER) scheduleApply(0) else scheduleHighlightCount(0)
        return true
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
        resetFilters()
        scheduleApply(0)
    }

    /**
     * [clearFilters] without the re-filter, for a caller that reloads the file
     * anyway (Save): the load filters with these, and a re-filter queued
     * beside it would race it.
     */
    private fun resetFilters() {
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
                // Anything other than this file's own pre-filled span counts —
                // including a bound copied from another tab that happens to sit
                // outside this file's range. Comparing "is it narrower?"
                // instead made two tabs with the very same filters report
                // different numbers after Apply filters to all tabs.
                if (f.timeFromMs != idx.firstTimestampMs || f.timeToMs != idx.lastTimestampMs) n++
            }
            return n
        }

    /**
     * Lines the filters hide, which a [save] would remove from the file. Not
     * the deleted ones: those the user removed on purpose.
     */
    val hiddenLineCount: Int get() = (index?.lineCount ?: 0) - deletedCount - filteredLines.size

    /**
     * Whether there is anything to write: nothing running, and lines in view
     * — or edits, since deleting every line and saving is an editor's right.
     * An empty view is otherwise never written: a filter that matched
     * nothing would only produce an empty file.
     */
    val canWriteView: Boolean get() = index != null && !isBusy && (filteredLines.isNotEmpty() || isDirty)

    /** Save changes the file when there are edits, or lines the filters hide. */
    val canSave: Boolean get() = canWriteView && (isDirty || hiddenLineCount > 0)

    /**
     * Types [text] into file line [line], whose text in the file is
     * [original]. Typing it back to the original is no edit at all. The text
     * may hold line breaks (Enter, a paste): new lines live inside their row
     * until Save writes them out and the reload gives each a row of its own.
     */
    fun editLine(line: Int, text: String, original: String) {
        val before = editedLines[line]
        val after = text.takeUnless { it == original }
        if (after == before) return
        val change = LineChange(line, before, after, System.currentTimeMillis())
        apply(change, forward = true)
        record(change)
    }

    /** Deletes the lines at [range] (positions in the view). Save takes them out of the file. */
    fun deleteLines(range: IntRange) {
        val lines = filteredLines
        val first = range.first.coerceAtLeast(0)
        val last = range.last.coerceAtMost(lines.lastIndex)
        if (first > last) return
        val change = deletionOf(lines.copyOfRange(first, last + 1))
        apply(change, forward = true)
        record(change)
        val n = last - first + 1
        statusMessage = "Deleted %,d line%s — Save writes it to the file.".format(n, if (n == 1) "" else "s")
    }

    /**
     * Backspace at the very start of the row at [position]: its text moves
     * onto the end of the row above, and the row goes. Returns where the
     * caret belongs in that row (where the two met), or null at the top.
     */
    fun joinWithPrevious(position: Int): Int? {
        val lines = filteredLines
        if (position !in 1..lines.lastIndex) return null
        val above = lines[position - 1]
        val aboveText = textOf(above) ?: return null
        joinLines(above, lines[position]) ?: return null
        return aboveText.length
    }

    /** Delete at the very end of the row at [position]: the row below joins it. */
    fun joinWithNext(position: Int): Boolean {
        val lines = filteredLines
        if (position !in 0 until lines.lastIndex) return false
        return joinLines(lines[position], lines[position + 1]) != null
    }

    /** [keep] gets [drop]'s text on its end, and [drop] is deleted: one undo step. */
    private fun joinLines(keep: Int, drop: Int): Unit? {
        val keepText = textOf(keep) ?: return null
        val dropText = textOf(drop) ?: return null
        val joined = keepText + dropText
        val edit = LineChange(keep, editedLines[keep], joined.takeUnless { it == originalOf(keep) }, 0)
        val steps = Steps(listOf(edit, deletionOf(intArrayOf(drop))))
        apply(steps, forward = true)
        record(steps)
        return Unit
    }

    // ---- Undo / redo ----
    // Every change to the edits is one of these, recorded once it's applied.
    // Undo applies it backwards; each entry is one user action, so undoing a
    // join brings back both the row that went and the text that moved.

    private sealed interface Change
    /** A line's edited text went from [before] to [after] (null: as in the file). */
    private class LineChange(val line: Int, val before: String?, var after: String?, var at: Long) : Change
    /** [lines] were deleted, taking [edits] with them, while the view showed [filter]. */
    private class LinesDeleted(val lines: IntArray, val edits: Map<Int, String>, val filter: FilterState) : Change
    private class Steps(val steps: List<Change>) : Change

    private val undoStack = ArrayDeque<Change>()
    private val redoStack = ArrayDeque<Change>()

    private fun deletionOf(lines: IntArray): LinesDeleted {
        val set = lines.toHashSet()
        return LinesDeleted(lines, editedLines.filterKeys { it in set }, appliedFilter)
    }

    private fun record(change: Change) {
        val top = undoStack.lastOrNull()
        // A run of typing in one line is one step, as in any editor.
        if (change is LineChange && top is LineChange && top.line == change.line && change.at - top.at < TYPING_RUN_MS) {
            top.after = change.after
            top.at = change.at
        } else {
            undoStack.addLast(change)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        redoStack.clear()
    }

    /** Ctrl+Z. Returns a file line it changed, so the view can show it; null when nothing to undo. */
    fun undo(): Int? {
        val change = undoStack.removeLastOrNull() ?: return null
        apply(change, forward = false)
        redoStack.addLast(change)
        return lineOf(change)
    }

    /** Ctrl+Y / Ctrl+Shift+Z. */
    fun redo(): Int? {
        val change = redoStack.removeLastOrNull() ?: return null
        apply(change, forward = true)
        undoStack.addLast(change)
        return lineOf(change)
    }

    private fun lineOf(change: Change): Int = when (change) {
        is LineChange -> change.line
        is LinesDeleted -> change.lines.first()
        is Steps -> lineOf(change.steps.first())
    }

    private fun apply(change: Change, forward: Boolean) {
        when (change) {
            is LineChange -> {
                val text = if (forward) change.after else change.before
                if (text == null) editedLines.remove(change.line) else editedLines[change.line] = text
                // Positions don't move, so the matches stay usable while recounting.
                if (highlightNeedle.isNotEmpty()) scheduleHighlightCount(keepMatches = true)
            }
            is LinesDeleted -> applyDeletion(change, forward)
            is Steps -> (if (forward) change.steps else change.steps.asReversed()).forEach { apply(it, forward) }
        }
    }

    private fun applyDeletion(change: LinesDeleted, forward: Boolean) {
        val deleted = deletedLines.clone() as BitSet
        if (forward) {
            for (line in change.lines) deleted.set(line)
            // Only the edited keys, not every deleted line: Ctrl+A, Delete on a
            // million lines must not mean a million map updates.
            change.edits.keys.forEach { editedLines.remove(it) }
            filteredLines = IntList(filteredLines.size).apply {
                for (line in filteredLines) if (!deleted.get(line)) add(line)
            }.toArray()
        } else {
            for (line in change.lines) deleted.clear(line)
            editedLines.putAll(change.edits)
        }
        deletedLines = deleted
        deletedCount = deleted.cardinality()
        lineSelection = null
        selectionAnchor = -1
        if (!forward) {
            // Back into the view where they were — if the view still shows what
            // it did then. After a filter change, only the filters know.
            if (appliedFilter == change.filter) {
                filteredLines = mergeSorted(filteredLines, change.lines)
            } else {
                scheduleApply(0)
                return
            }
        }
        // Positions after the change moved.
        scheduleHighlightCount(0)
    }

    /** The line's text as the tab shows it: edited, or from the file. */
    private fun textOf(line: Int): String? = editedLines[line] ?: originalOf(line)

    private fun originalOf(line: Int): String? =
        provider?.let { p -> runCatching { p.cachedLine(line) ?: p.line(line) }.getOrNull() }

    /** Export Filtered: writes the view to [target] as a copy; this tab stays on its file. */
    fun export(target: File) = writeView(target, showTarget = false)

    /**
     * Save: writes the view, with its edits, over this tab's own file (see
     * [saveAs]). [then] learns whether it worked, once the file is written.
     */
    fun save(then: (saved: Boolean) -> Unit = {}) {
        val f = file ?: return then(false)
        saveAs(f, then)
    }

    /**
     * Save As: writes the view to [target], and this tab then shows [target],
     * re-indexed. Lines and tags the filters hid are gone from it, so the
     * filters are reset: they are now part of the file. So are the edits.
     */
    fun saveAs(target: File, then: (saved: Boolean) -> Unit = {}) = writeView(target, showTarget = true, then)

    private fun writeView(target: File, showTarget: Boolean, then: (Boolean) -> Unit = {}) {
        val idx = index ?: return then(false)
        val lines = filteredLines
        val edits = editsSnapshot()
        cancelWork()
        // Writing onto the currently open file is allowed (the exporter
        // writes via a temp file), but our own read handle must be released
        // first or the final replace fails on Windows — and afterwards the
        // on-disk content no longer matches the index, so re-open it.
        val ontoOpenFile = runCatching { idx.file.canonicalFile == target.canonicalFile }.getOrDefault(false)
        if (ontoOpenFile) {
            provider?.close()
            provider = null
        }
        val deleted = deletedLines
        val untitled = untitledName
        // The file is unchanged when writing fails, so reopening it to get the
        // released provider back must not cost the user their edits.
        fun reopenKeepingEdits() {
            openFile(idx.file, untitled)
            editedLines.putAll(edits)
            deletedLines = deleted
            deletedCount = deleted.cardinality()
        }
        val verb = if (showTarget) "Saving" else "Exporting"
        workJob = scope.launch(Dispatchers.IO) {
            try {
                LogExporter.export(idx, lines, target, edits) { done, total ->
                    progress = if (total > 0) done.toFloat() / total else 1f
                    progressLabel = "$verb: %,d / %,d lines".format(done, total)
                }
                if (showTarget) {
                    resetFilters()
                    openFile(target)
                } else {
                    statusMessage = "Exported %,d lines to ${target.name}".format(lines.size)
                    if (ontoOpenFile) openFile(target)
                }
                then(true)
            } catch (e: CancellationException) {
                if (ontoOpenFile) reopenKeepingEdits() // restore the released provider
                then(false)
                throw e
            } catch (e: Exception) {
                if (isActive) {
                    if (ontoOpenFile) reopenKeepingEdits() // restore the released provider
                    // A notice, not just the status: the reload's filter pass
                    // rewrites the status, and a failed save must not go unseen.
                    val message = "${if (showTarget) "Save" else "Export"} failed: ${e.message}"
                    statusMessage = message
                    addNotice(message)
                }
                then(false)
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
        if (lineSelection != null) {
            lineSelection = null
            return true
        }
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
    /**
     * [keepMatches]: the view's positions haven't moved (an edit within a
     * line), so the current matches stay usable — markers, F3 — until the
     * recount replaces them, instead of vanishing on every keystroke.
     */
    private fun scheduleHighlightCount(debounceMs: Long = 400, keepMatches: Boolean = false) {
        highlightJob?.cancel()
        // Cancelling can't stop a count that is already past its last check,
        // and it would then write its matches over the ones cleared here. The
        // generation lets a superseded count recognise itself and drop them.
        val generation = ++highlightGeneration
        val idx = index
        val needle = highlightNeedle
        val isRegex = highlightIsRegex
        if (idx == null || needle.isEmpty()) {
            // The matches go first: whoever sees the count gone and counting
            // done (navigation, a test on another thread) must not still find
            // the old matches behind it.
            highlightMatches = IntArray(0)
            currentMatchIndex = -1
            highlightCount = null
            highlightCounting = false
            return
        }
        if (!keepMatches) highlightCounting = true
        highlightJob = scope.launch(Dispatchers.IO) {
            delay(debounceMs)
            val lines = filteredLines
            val result = runCatching { HighlightCounter.matches(idx, lines, needle, isRegex, editsSnapshot()) }
            if (!isActive || generation != highlightGeneration) return@launch
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

    /**
     * Makes match [matchIndex] the current one (a clicked scrollbar marker),
     * so F3/Enter carry on from it. Returns its position, like [nextMatch].
     */
    fun goToMatch(matchIndex: Int): Int? =
        if (matchIndex in highlightMatches.indices) moveToMatch(matchIndex) else null

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
        val deleted = deletedLines
        val found = FilterEngine.apply(idx, state, editsSnapshot()) { done, total ->
            if (state.needsText) {
                progress = if (total > 0) done.toFloat() / total else 0f
                progressLabel = "Filtering…"
            }
        }
        // Deleted lines stay out of every view until Save takes them out of the file.
        val result = if (deleted.isEmpty) found else IntList(found.size).apply {
            for (line in found) if (!deleted.get(line)) add(line)
        }.toArray()
        // The line count is the status bar's own ([lineCountText]); a message
        // from before this view ("Copied …", "Filters applied …") is stale now.
        // Written before the view swaps, so whoever sees the new view sees
        // the status that goes with it.
        val active = activeFilterCount
        statusMessage = if (result.isEmpty() && active > 0) {
            // An empty view is otherwise indistinguishable from a broken one.
            "%d filter%s active".format(active, if (active == 1) "" else "s")
        } else ""
        // The matches index the old view until the recount below lands; mark
        // them stale *before* swapping the view so navigation can't use them.
        if (highlightNeedle.isNotEmpty()) highlightCounting = true
        // Selected positions would point at different lines in the new view.
        lineSelection = null
        selectionAnchor = -1
        filteredLines = result
        progress = null
        // The highlight count is relative to the filtered set; recount. Also
        // with an empty needle, which clears the matches: Clear Filters can
        // empty a Find query without passing through a recount, and matches
        // left from it would index this new view.
        scheduleHighlightCount(0)
    }
}
