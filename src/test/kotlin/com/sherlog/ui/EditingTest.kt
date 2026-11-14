package com.sherlog.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import java.util.Collections
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A tab's unsaved edits: what they change in the view, and what Save, Export and Copy write. */
class EditingTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val dir: File = Files.createTempDirectory("editing").toFile()

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dir.walk().forEach { it.setWritable(true) }
        dir.deleteRecursively()
    }

    private fun line(n: Int, msg: String = "line $n") = "07-12 10:00:%02d.000  1000  1000 I Tag: %s".format(n, msg)
    private val lines = (0 until 6).map { line(it) }

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun open(name: String = "edit.txt"): Pair<AppState, File> {
        val file = File(dir, name).apply { writeText(lines.joinToString("\n", postfix = "\n")) }
        val tab = AppState(scope)
        tab.openFile(file)
        awaitUntil("the load") { tab.index != null && !tab.isLoading && tab.filteredLines.size == lines.size }
        return tab to file
    }

    @Test
    fun `an edit makes the tab dirty, and typing it back to the original does not`() {
        val (tab, _) = open()
        assertFalse(tab.isDirty)
        tab.editLine(2, line(2, "changed"), original = lines[2])
        assertTrue(tab.isDirty)
        assertTrue(tab.canSave) // edits alone are something to save
        assertEquals(0, tab.hiddenLineCount)
        tab.editLine(2, lines[2], original = lines[2])
        assertFalse(tab.isDirty)
    }

    @Test
    fun `deleted lines leave the view, and stay out when the filters run again`() {
        val (tab, _) = open()
        tab.deleteLines(1..2)
        assertContentEquals(intArrayOf(0, 3, 4, 5), tab.filteredLines)
        assertTrue(tab.isDirty)
        assertEquals(0, tab.hiddenLineCount) // deleted on purpose, not hidden
        tab.pidText = "1000"
        tab.scheduleApply(0)
        awaitUntil("the filter") { tab.activeFilterCount == 1 && !tab.isBusy }
        assertContentEquals(intArrayOf(0, 3, 4, 5), tab.filteredLines)
    }

    @Test
    fun `save writes the edits and deletions, then reloads the file clean`() {
        val (tab, file) = open()
        tab.editLine(0, line(0, "edited"), original = lines[0])
        tab.deleteLines(4..4)
        var saved: Boolean? = null
        tab.save { saved = it }
        awaitUntil("the save") { saved == true && !tab.isLoading && tab.index?.lineCount == 5 }
        assertEquals(listOf(line(0, "edited"), lines[1], lines[2], lines[3], lines[5]), file.readLines())
        assertFalse(tab.isDirty)
        assertEquals(5, tab.filteredLines.size)
    }

    @Test
    fun `export writes the edits but leaves the tab's own file and edits alone`() {
        val (tab, file) = open()
        val before = file.readBytes()
        tab.editLine(1, line(1, "edited"), original = lines[1])
        val out = File(dir, "out.txt")
        tab.export(out)
        awaitUntil("the export") { out.exists() && !tab.isBusy }
        assertEquals(line(1, "edited"), out.readLines()[1])
        assertContentEquals(before, file.readBytes())
        assertTrue(tab.isDirty)
    }

    @Test
    fun `copying selected lines copies the edited text`() {
        val (tab, _) = open()
        tab.editLine(3, line(3, "edited"), original = lines[3])
        tab.selectLines(2, 3)
        val copies = Collections.synchronizedList(mutableListOf<String>())
        tab.copySelectedLines({ copies += it })
        awaitUntil("the copy") { copies.isNotEmpty() }
        assertEquals(listOf(lines[2], line(3, "edited")).joinToString(System.lineSeparator()), copies.single())
    }

    @Test
    fun `undo and redo step through edits, a run of typing being one step`() {
        val (tab, _) = open()
        tab.editLine(1, lines[1] + "a", original = lines[1])
        tab.editLine(1, lines[1] + "ab", original = lines[1]) // the same run of typing
        tab.editLine(2, lines[2] + "!", original = lines[2])
        assertEquals(2, tab.undo())
        assertEquals(null, tab.editedLines[2])
        assertEquals(1, tab.undo())
        assertFalse(tab.isDirty) // both keystrokes went at once
        assertEquals(null, tab.undo()) // nothing left
        assertEquals(1, tab.redo())
        assertEquals(lines[1] + "ab", tab.editedLines[1])
        tab.editLine(3, lines[3] + "?", original = lines[3])
        assertEquals(null, tab.redo()) // a new edit drops what could be redone
    }

    @Test
    fun `undoing a deletion puts the lines back in the view, edits and all`() {
        val (tab, _) = open()
        tab.editLine(2, line(2, "edited"), original = lines[2])
        tab.deleteLines(1..3)
        assertContentEquals(intArrayOf(0, 4, 5), tab.filteredLines)
        tab.undo()
        assertContentEquals(lines.indices.toList().toIntArray(), tab.filteredLines)
        assertEquals(line(2, "edited"), tab.editedLines[2])
        assertEquals(0, tab.deletedCount)
        tab.redo()
        assertContentEquals(intArrayOf(0, 4, 5), tab.filteredLines)
    }

    @Test
    fun `backspace and delete at a row's edge join rows, and undo splits them again`() {
        val (tab, _) = open()
        assertEquals(lines[1].length, tab.joinWithPrevious(2)) // the caret goes where they met
        assertEquals(lines[1] + lines[2], tab.editedLines[1])
        assertFalse(2 in tab.filteredLines)
        assertTrue(tab.joinWithNext(2)) // the row at position 2 (line 3) takes in line 4
        assertEquals(lines[3] + lines[4], tab.editedLines[3])
        assertEquals(null, tab.joinWithPrevious(0)) // nothing above the first row
        assertFalse(tab.joinWithNext(tab.filteredLines.lastIndex))

        tab.undo()
        tab.undo()
        assertFalse(tab.isDirty)
        assertContentEquals(lines.indices.toList().toIntArray(), tab.filteredLines)
    }

    @Test
    fun `a line split with enter is saved as two lines`() {
        val (tab, file) = open()
        tab.editLine(0, "${lines[0]}\nnew line", original = lines[0])
        var saved: Boolean? = null
        tab.save { saved = it }
        awaitUntil("the save") { saved == true && !tab.isLoading && tab.index?.lineCount == lines.size + 1 }
        assertEquals(listOf(lines[0], "new line") + lines.drop(1), file.readLines())
        assertEquals(null, tab.undo()) // the history went with the reload
    }

    @Test
    fun `a save that fails keeps the edits`() {
        val (tab, file) = open()
        tab.editLine(0, line(0, "edited"), original = lines[0])
        file.setReadOnly() // the replace at the end of the save is refused
        var saved: Boolean? = null
        tab.save { saved = it }
        awaitUntil("the save to fail") { saved == false && !tab.isLoading && tab.index != null }
        assertTrue(tab.isDirty)
        assertEquals(line(0, "edited"), tab.editedLines[0])
        // In the notice: the reload's filter pass has rewritten the status by now.
        assertTrue("Save failed" in tab.notice.orEmpty(), tab.notice)
        assertEquals(lines, file.readLines())
    }
}
