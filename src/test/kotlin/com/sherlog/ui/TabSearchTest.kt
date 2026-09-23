package com.sherlog.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TabSearchTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val dir: File = Files.createTempDirectory("tabsearch").toFile()

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun line(second: Int, tag: String, message: String) =
        "07-12 10:00:%02d.000  1000  1000 I %s: %s".format(second, tag, message)

    private fun log(name: String, vararg lines: String): File =
        File(dir, name).apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun workspaceWith(vararg files: File): Workspace = Workspace(scope).apply {
        open(files.toList())
        awaitUntil("files to load") { tabs.all { it.index != null && !it.isLoading } }
    }

    private fun Workspace.searchFor(text: String, regex: Boolean = false) {
        search.query = text
        search.isRegex = regex
        searchAllTabs()
        awaitUntil("the search") { !search.isSearching }
    }

    private val alpha get() = log(
        "alpha.txt",
        line(1, "Net", "TOKEN one"),
        line(2, "Net", "nothing here"),
        line(3, "Other", "TOKEN two"),
    )
    private val bravo get() = log(
        "bravo.txt",
        line(4, "Net", "TOKEN three"),
        line(5, "Net", "quiet"),
    )

    @Test
    fun `results come back per file, in tab order, with the matching text`() {
        val ws = workspaceWith(alpha, bravo)
        ws.searchFor("TOKEN")

        assertEquals(listOf("alpha.txt", "bravo.txt"), ws.search.results.map { it.name })
        assertEquals(listOf(2, 1), ws.search.results.map { it.total })
        assertEquals(3, ws.search.totalMatches)
        assertEquals(listOf(0, 2), ws.search.results[0].previews.map { it.line })
        assertTrue(ws.search.results[0].previews[0].text.endsWith("TOKEN one"))
        assertFalse(ws.search.results.any { it.truncated })
    }

    @Test
    fun `only the lines a tab currently shows are searched`() {
        val ws = workspaceWith(alpha, bravo)
        val tabA = ws.tabs[0]
        tabA.selectedTags = setOf("Net")
        tabA.scheduleApply(0)
        awaitUntil("a to re-filter") { tabA.filteredLines.size == 2 }

        ws.searchFor("TOKEN")
        // "TOKEN two" carries the Other tag, so it is out of alpha's view.
        assertEquals(1, ws.search.results.first { it.name == "alpha.txt" }.total)
        assertEquals(1, ws.search.results.first { it.name == "bravo.txt" }.total)
    }

    @Test
    fun `a regex searches as a pattern, and a broken one is reported`() {
        val ws = workspaceWith(alpha)
        ws.searchFor("TOKEN (one|two)", regex = true)
        assertEquals(2, ws.search.totalMatches)

        ws.searchFor("TOKEN (", regex = true)
        assertTrue(ws.search.invalidPattern)
        assertTrue(ws.search.results.isEmpty())
        assertFalse(ws.search.isSearching)
    }

    @Test
    fun `a new search supersedes the one before it`() {
        val ws = workspaceWith(alpha, bravo)
        ws.search.query = "TOKEN"
        ws.searchAllTabs()
        ws.searchFor("quiet") // starts while the first may still be running
        assertEquals(1, ws.search.totalMatches)
        assertEquals("quiet", ws.search.resultsQuery)
    }

    @Test
    fun `closing a tab drops its results, so its index is not pinned`() {
        val ws = workspaceWith(alpha, bravo)
        ws.searchFor("TOKEN")
        assertEquals(2, ws.search.results.size)

        ws.close(ws.tabs[1])
        assertEquals(listOf("alpha.txt"), ws.search.results.map { it.name })
    }

    @Test
    fun `opening a hit shows its tab and its line`() {
        val ws = workspaceWith(alpha, bravo)
        ws.searchFor("TOKEN")
        val bravoResult = ws.search.results.first { it.name == "bravo.txt" }
        val hit = bravoResult.previews.single()

        assertTrue(ws.openHit(bravoResult.tab, hit))
        assertSame(bravoResult.tab, ws.active)
        assertEquals(hit.line, ws.active.listState.firstVisibleItemIndex) // no filters: line == position
        assertEquals("TOKEN", ws.active.selectionHighlight)
    }

    @Test
    fun `a hit whose line has since been filtered out is refused`() {
        val ws = workspaceWith(alpha)
        ws.searchFor("TOKEN")
        val result = ws.search.results.single()
        val hit = result.previews.first { it.line == 2 } // the "Other"-tagged line

        result.tab.selectedTags = setOf("Net")
        result.tab.scheduleApply(0)
        // Wait for the filter's own status line too, or it lands after ours.
        awaitUntil("the re-filter") {
            result.tab.filteredLines.size == 2 && "lines" in result.tab.statusMessage && !result.tab.highlightCounting
        }

        assertFalse(ws.openHit(result.tab, hit))
        assertTrue("no longer in this tab" in result.tab.statusMessage, result.tab.statusMessage)
    }

    @Test
    fun `a closed tab's hit is ignored`() {
        val ws = workspaceWith(alpha, bravo)
        ws.searchFor("TOKEN")
        val group = ws.search.results.first { it.name == "bravo.txt" }
        val hit = group.previews.single()
        ws.close(group.tab)
        assertFalse(ws.openHit(group.tab, hit))
    }
}
