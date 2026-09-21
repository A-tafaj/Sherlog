package com.sherlog.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LineSelectionTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val lines = (0 until 50).map { "07-12 10:00:%02d.000  1000  1000 I SelTest: line %03d".format(it, it) }
    private val logFile = File.createTempFile("lineselection", ".txt").apply { writeText(lines.joinToString("\n", postfix = "\n")) }

    @AfterTest
    fun cleanup() {
        scope.cancel()
        logFile.delete()
    }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun loaded(): AppState = AppState(scope).apply {
        openFile(logFile)
        awaitUntil("the file to load") { filteredLines.size == lines.size && !isLoading }
    }

    private fun AppState.copied(maxLines: Int = Int.MAX_VALUE): String {
        val out = Collections.synchronizedList(mutableListOf<String>())
        copySelectedLines({ out += it }, maxLines)
        awaitUntil("the copy") { out.isNotEmpty() }
        return out.single()
    }

    @Test
    fun `a range selects whole lines in either direction`() {
        val state = loaded()
        state.selectLines(7, 3)
        assertEquals(3..7, state.lineSelection)
        assertEquals(lines.subList(3, 8).joinToString(System.lineSeparator()), state.copied())
    }

    @Test
    fun `shift-click extends from the line pressed last`() {
        val state = loaded()
        state.onLinePressed(10)
        state.extendLineSelection(12)
        assertEquals(10..12, state.lineSelection)
        state.extendLineSelection(8) // still anchored at 10
        assertEquals(8..10, state.lineSelection)
    }

    @Test
    fun `a plain press, escape and a filter change each drop the selection`() {
        val state = loaded()
        state.selectLines(1, 4)
        state.onLinePressed(20)
        assertNull(state.lineSelection)

        state.selectLines(1, 4)
        assertTrue(state.onEscape())
        assertNull(state.lineSelection)

        state.selectLines(1, 4)
        state.excludeText = "line 000"
        state.scheduleApply(0)
        awaitUntil("the re-filter") { state.filteredLines.size == lines.size - 1 }
        assertNull(state.lineSelection) // positions now point at other lines
    }

    @Test
    fun `selecting across lines drops a double-click highlight but not a Find query`() {
        val state = loaded()
        state.onViewerSelection(3, "SelTest")
        state.selectLines(3, 5)
        assertEquals("", state.selectionHighlight)

        state.searchMode = SearchMode.FIND
        state.searchText = "line 00"
        state.selectLines(1, 2)
        assertEquals("line 00", state.highlightNeedle)
    }

    @Test
    fun `copying is capped, and says so`() {
        val state = loaded()
        state.selectLines(0, 9)
        assertEquals(lines.subList(0, 4).joinToString(System.lineSeparator()), state.copied(maxLines = 4))
        awaitUntil("the status") { "first 4 of 10" in state.statusMessage }
    }
}
