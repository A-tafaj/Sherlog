package com.sherlog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * The open files, one [AppState] per tab, Sublime-style. There is always at
 * least one tab: with nothing open it is a blank one, which the next opened
 * file fills rather than sitting beside it.
 *
 * Each tab keeps its own filters; [applyFiltersToAll] copies the active tab's
 * onto the others.
 */
class Workspace(private val scope: CoroutineScope) {

    // Indexing is disk-bound: opening a folder of big logs shouldn't read
    // them all at once. Two at a time keeps the disk busy without thrashing.
    private val indexing = Dispatchers.IO.limitedParallelism(2)

    /** Searching every open tab at once; outlives tab switches, so it lives here. */
    val search = TabSearch(scope)

    var tabs by mutableStateOf(listOf(newTab()))
        private set
    var activeIndex by mutableStateOf(0)
        private set
    val active: AppState get() = tabs[activeIndex.coerceIn(tabs.indices)]

    private fun newTab() = AppState(scope, indexing)

    /**
     * Opens [files] in tabs, in order, and shows the first. A file that is
     * already open is not opened twice; its tab is shown instead.
     */
    fun open(files: List<File>) {
        var first: AppState? = null
        for (file in files) {
            val tab = tabs.firstOrNull { it.shows(file) } ?: run {
                val tab = tabs.firstOrNull { it.file == null } ?: newTab().also { tabs = tabs + it }
                tab.openFile(file)
                tab
            }
            if (first == null) first = tab
        }
        first?.let(::select)
    }

    /** Opens the .txt/.log files directly inside [dir] (see [logFilesIn]). */
    fun openFolder(dir: File) {
        val files = logFilesIn(dir)
        if (files.isEmpty()) active.showStatus("No .txt or .log files in ${dir.name}.") else open(files)
    }

    fun select(tab: AppState) {
        val i = tabs.indexOf(tab)
        if (i >= 0) activeIndex = i
    }

    /**
     * Moves the tab at [from] to [to]. The shown tab stays shown, found again
     * by identity. Unlike [close] the list keeps its length, so [tabs] can be
     * written before [activeIndex] without [active] ever indexing past the end.
     */
    fun moveTab(from: Int, to: Int) {
        if (from == to || from !in tabs.indices || to !in tabs.indices) return
        val shown = active
        tabs = tabs.toMutableList().apply { add(to, removeAt(from)) }
        activeIndex = tabs.indexOfFirst { it === shown }.coerceAtLeast(0)
    }

    /** Cycles through the tabs; [step] is +1 for the next one, -1 for the previous. */
    fun selectNext(step: Int) {
        if (tabs.size > 1) activeIndex = (activeIndex + step).mod(tabs.size)
    }

    /** Closes [tab], showing its neighbour; closing the last tab leaves a blank one. */
    fun close(tab: AppState) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        tab.close()
        // Results hold their tab, and a closed tab's index is ~25 B per line.
        search.forget(tab)
        val rest = tabs.filterIndexed { k, _ -> k != i }
        // Written before [tabs] and valid for both lists, so [active] never
        // indexes past the end in between.
        activeIndex = when {
            i < activeIndex -> activeIndex - 1
            i == activeIndex -> minOf(i, rest.size - 1).coerceAtLeast(0)
            else -> activeIndex
        }
        tabs = rest.ifEmpty { listOf(newTab()) }
    }

    /** Runs the cross-tab search over every loaded tab. */
    fun searchAllTabs() = search.run(tabs)

    /**
     * Shows what a search result points at, in the tab it came from. Returns
     * false when that tab was closed or its filters moved the line out of view.
     *
     * A regex search jumps without highlighting: the occurrence highlight is
     * always matched as a substring, so a pattern would light up nothing.
     */
    fun openHit(tab: AppState, hit: TabSearch.Hit): Boolean {
        if (tabs.none { it === tab }) return false
        select(tab)
        val needle = if (search.resultsAreRegex) "" else search.resultsQuery
        val shown = tab.revealLine(hit.line, needle)
        if (!shown) tab.showStatus("That line is no longer in this tab's filtered view.")
        return shown
    }

    /**
     * Whether [applyFiltersToAll] can run: there are other tabs, and none is
     * still loading — a load finishing afterwards would reset the time range
     * it was given.
     */
    val canApplyFiltersToAll: Boolean
        get() = tabs.size > 1 && active.index != null && tabs.none { it.isLoading }

    /** Copies the active tab's filters onto every other open file; returns how many. */
    fun applyFiltersToAll(): Int {
        val source = active
        val targets = tabs.filter { it !== source && it.index != null }
        targets.forEach { it.copyFiltersFrom(source) }
        return targets.size
    }

    /**
     * Exports the active tab's filtered lines. A target another tab has open
     * is refused: Windows can't replace a file that is open, and that tab
     * would be left showing an index of the old content.
     */
    fun exportActive(target: File) {
        if (tabs.any { it !== active && it.shows(target) }) {
            active.showStatus("${target.name} is open in another tab — close it there first, or export under another name.")
            return
        }
        active.export(target)
    }

    private fun AppState.shows(other: File): Boolean {
        val mine = file ?: return false
        return runCatching { mine.canonicalFile == other.canonicalFile }.getOrDefault(mine == other)
    }

    companion object {
        private val LOG_EXTENSIONS = setOf("txt", "log")

        /** The .txt/.log files directly inside [dir], by name; subfolders are not walked. */
        fun logFilesIn(dir: File): List<File> =
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.extension.lowercase() in LOG_EXTENSIONS }
                .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
