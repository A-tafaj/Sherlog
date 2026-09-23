package com.sherlog.ui

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.sherlog.filter.FilterEngine
import com.sherlog.filter.HighlightCounter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Preview lines kept per file. The match count itself is always exact. */
private const val MAX_PREVIEWS_PER_FILE = 500

/**
 * Searching every open tab at once (Ctrl+Shift+F). It carries its own query so
 * that searching across files never disturbs a tab's filters or its search box,
 * and it lives on [Workspace] rather than [AppState] so results outlive tab
 * switches.
 *
 * Each tab is searched over the lines it currently *shows*, so every result can
 * be jumped to. The per-file pass is [HighlightCounter.matches] — the same one
 * behind the status bar's occurrence count — and the preview text comes from
 * that tab's [com.sherlog.core.LineTextProvider], which reads through its block
 * cache instead of a second scan of the file.
 */
class TabSearch(private val scope: CoroutineScope) {

    /** One matching line: [position] within that tab's filtered view, [line] in the file. */
    data class Hit(val position: Int, val line: Int, val text: String)

    /** One tab's hits: [total] is exact, [previews] is capped. */
    data class FileResult(val tab: AppState, val name: String, val total: Int, val previews: List<Hit>) {
        val truncated: Boolean get() = total > previews.size
    }

    var isOpen by mutableStateOf(false)
        private set
    var query by mutableStateOf("")
    var isRegex by mutableStateOf(false)
    /** What [results] were produced with — a jump highlights this, not a half-typed [query]. */
    var resultsQuery by mutableStateOf("")
        private set
    var resultsAreRegex by mutableStateOf(false)
        private set
    var isSearching by mutableStateOf(false)
        private set
    /** Which file is being searched right now, for the header. */
    var progressLabel by mutableStateOf("")
        private set
    /** Grows a file at a time, so each group appears as its file finishes. */
    var results by mutableStateOf<List<FileResult>>(emptyList())
        private set
    var invalidPattern by mutableStateOf(false)
        private set
    /** Groups the user folded away. */
    var collapsed by mutableStateOf(emptySet<AppState>())
        private set

    // Panel state lives here, not in the panel: App re-creates everything under
    // its key(state) on a tab switch, and clicking a result *is* a tab switch —
    // a remembered scroll position would jump to the top under the pointer.
    val listState = LazyListState()
    var height by mutableStateOf(240.dp)

    private var job: Job? = null

    // A superseded run must not write its results over a newer one's, the same
    // way AppState guards its highlight count.
    @Volatile
    private var generation = 0

    val totalMatches: Int get() = results.sumOf { it.total }

    /** Ctrl+Shift+F: opens the panel, seeded with [seed] when there is one. */
    fun open(seed: String, regex: Boolean) {
        isOpen = true
        if (seed.isNotEmpty()) {
            query = seed
            isRegex = regex
        }
    }

    fun close() {
        cancel()
        isOpen = false
    }

    fun toggleGroup(tab: AppState) {
        collapsed = if (tab in collapsed) collapsed - tab else collapsed + tab
    }

    /** Drops a closed tab's group, so results stop pinning its index in memory. */
    fun forget(tab: AppState) {
        if (results.any { it.tab === tab }) results = results.filterNot { it.tab === tab }
        if (tab in collapsed) collapsed = collapsed - tab
    }

    fun cancel() {
        job?.cancel()
        job = null
        isSearching = false
        progressLabel = ""
    }

    /** Searches every loaded tab for [query], over the lines each one shows. */
    fun run(tabs: List<AppState>) {
        job?.cancel()
        val generation = ++this.generation
        val needle = query
        results = emptyList()
        resultsQuery = needle
        resultsAreRegex = isRegex
        invalidPattern = false
        progressLabel = ""
        if (needle.isEmpty()) {
            isSearching = false
            return
        }
        // HighlightCounter answers a pattern that won't compile with "no
        // matches", which would read as a real result; catch it up front.
        if (FilterEngine.SearchMatcher(needle, isRegex).isInvalid) {
            invalidPattern = true
            isSearching = false
            return
        }
        val targets = tabs.filter { it.index != null && it.provider != null }
        isSearching = true
        job = scope.launch(Dispatchers.IO) {
            val found = mutableListOf<FileResult>()
            for (tab in targets) {
                val idx = tab.index ?: continue
                val provider = tab.provider ?: continue
                val lines = tab.filteredLines
                progressLabel = tab.file?.name.orEmpty()
                val hits = try {
                    HighlightCounter.matches(idx, lines, needle, isRegex)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    continue // an unreadable file shouldn't sink the whole search
                }
                if (generation != this@TabSearch.generation) return@launch
                val previews = hits.take(MAX_PREVIEWS_PER_FILE).map { position ->
                    val line = lines[position]
                    Hit(position, line, runCatching { provider.line(line) }.getOrDefault(""))
                }
                found += FileResult(tab, tab.file?.name.orEmpty(), hits.size, previews)
                results = found.toList()
            }
            if (generation == this@TabSearch.generation) {
                isSearching = false
                progressLabel = ""
            }
        }
    }
}
