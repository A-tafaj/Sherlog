package com.sherlog.ui

import com.sherlog.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkspaceTest {

    private val scope = CoroutineScope(SupervisorJob())
    private val dir: File = Files.createTempDirectory("workspace").toFile()

    @AfterTest
    fun cleanup() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun log(name: String, vararg lines: String): File =
        File(dir, name).apply {
            parentFile.mkdirs()
            writeText(lines.joinToString("\n", postfix = "\n"))
        }

    private fun line(time: String, level: Char, tag: String, msg: String) = "07-12 $time.000  1000  1000 $level $tag: $msg"

    private fun awaitUntil(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(10)
        }
    }

    private fun Workspace.awaitLoaded() = awaitUntil("files to load") { tabs.all { it.index != null && !it.isLoading } }

    private val a get() = log("a.txt", line("10:00:00", 'I', "OkHttp", "alpha"), line("10:01:00", 'D', "CCodec", "alpha"))
    private val b get() = log("b.log", line("11:00:00", 'I', "OkHttp", "bravo"))
    private val c get() = log("c.txt", line("12:00:00", 'I', "OkHttp", "charlie"))

    @Test
    fun `opened files fill the blank tab first, then get tabs of their own`() {
        val ws = Workspace(scope)
        val blank = ws.active
        ws.open(listOf(a, b))
        assertEquals(2, ws.tabs.size)
        assertSame(blank, ws.tabs[0])
        assertEquals(listOf("a.txt", "b.log"), ws.tabs.map { it.file!!.name })
        assertSame(ws.tabs[0], ws.active) // the first one opened is shown
        ws.awaitLoaded()
        assertEquals(listOf(2, 1), ws.tabs.map { it.index!!.lineCount })
    }

    @Test
    fun `opening a file that is already open shows its tab instead of a duplicate`() {
        val ws = Workspace(scope)
        val fileA = a
        ws.open(listOf(fileA, b))
        ws.select(ws.tabs[1])
        ws.open(listOf(File(dir, "./a.txt"))) // same file, different path spelling
        assertEquals(2, ws.tabs.size)
        assertEquals(fileA, ws.active.file)
    }

    private fun binary(name: String): File =
        File(dir, name).apply { writeBytes(kotlin.random.Random(1).nextBytes(4096)) }

    @Test
    fun `open folder takes the text files directly inside it whatever their extension, by name`() {
        log("b.log", "x")
        log("A.TXT", "x")
        log("logcat.09", line("10:00:00", 'I', "OkHttp", "rotated"))
        log("logcat", "x")
        log("sub/d.txt", "x")
        log(".hidden_notes", "x")
        val ws = Workspace(scope)
        ws.openFolder(dir)
        assertEquals(listOf("A.TXT", "b.log", "logcat", "logcat.09"), ws.tabs.map { it.file!!.name })
        assertFalse(ws.tabs.any { "Skipped" in it.notice.orEmpty() }) // nothing here was binary
    }

    @Test
    fun `open folder skips binaries and names them on the first tab`() {
        log("logcat.01", line("10:00:00", 'I', "OkHttp", "x"))
        binary("bugreport.zip")
        binary("screen.png")
        val ws = Workspace(scope)
        ws.openFolder(dir)
        assertEquals(listOf("logcat.01"), ws.tabs.map { it.file!!.name })
        assertEquals("Skipped 2 files that aren't text: bugreport.zip, screen.png", ws.active.notice)
        ws.awaitLoaded()
        // Loading the file doesn't wipe the notice; dismissing it does.
        assertEquals("Skipped 2 files that aren't text: bugreport.zip, screen.png", ws.active.notice)
        ws.active.dismissNotice()
        assertNull(ws.active.notice)
    }

    @Test
    fun `a folder with only binaries opens nothing and says so`() {
        binary("bugreport.zip")
        val ws = Workspace(scope)
        ws.openFolder(dir)
        assertEquals(1, ws.tabs.size)
        assertNull(ws.active.file)
        assertEquals("Nothing opened — not a text file: bugreport.zip", ws.active.statusMessage)
    }

    @Test
    fun `an empty folder says so`() {
        val ws = Workspace(scope)
        ws.openFolder(dir)
        assertNull(ws.active.file)
        assertTrue("has no files" in ws.active.statusMessage, ws.active.statusMessage)
    }

    @Test
    fun `a text file with no logcat lines opens with a plain-text notice`() {
        val ws = Workspace(scope)
        ws.open(listOf(log("notes", "just some notes", "nothing logcat about them")))
        ws.awaitLoaded()
        assertEquals(2, ws.active.index!!.lineCount)
        assertEquals("No logcat lines found — showing it as plain text", ws.active.notice)
        // A real log in the same tab clears it.
        ws.active.openFile(a)
        ws.awaitLoaded()
        assertNull(ws.active.notice)
    }

    @Test
    fun `closing a tab shows its neighbour, and closing the last leaves a blank tab`() {
        val ws = Workspace(scope)
        ws.open(listOf(a, b, c))
        val (ta, tb, tc) = ws.tabs

        ws.select(tc)
        ws.close(ta) // before the active tab: the active tab stays shown
        assertSame(tc, ws.active)

        ws.select(tb)
        ws.close(tb) // the active tab: its right-hand neighbour takes over
        assertSame(tc, ws.active)

        ws.close(tc)
        assertEquals(1, ws.tabs.size)
        assertNull(ws.active.file)
    }

    @Test
    fun `a closed tab releases its file`() {
        val ws = Workspace(scope)
        val fileA = a
        ws.open(listOf(fileA))
        ws.awaitLoaded()
        ws.close(ws.active)
        assertTrue(fileA.delete(), "the file is still held open") // Windows refuses to delete an open file
    }

    @Test
    fun `apply filters to all copies the panel onto every other file and re-filters it`() {
        val fileA = log(
            "a.txt",
            line("10:00:00", 'I', "OkHttp", "alpha early"),
            line("10:06:00", 'I', "OkHttp", "alpha ok"),
            line("10:07:00", 'I', "OkHttp", "alpha noise"),
            line("10:08:00", 'D', "OkHttp", "alpha debug"),
            line("10:10:00", 'I', "CCodec", "alpha other tag"),
        )
        val fileB = log(
            "b.txt",
            line("10:30:00", 'I', "OkHttp", "bravo ok"),
            line("10:31:00", 'I', "OkHttp", "bravo noise"),
            line("10:32:00", 'D', "OkHttp", "bravo debug"),
            line("10:33:00", 'I', "Audio", "bravo other tag"),
            line("10:40:00", 'I', "OkHttp", "bravo last"),
        )
        val ws = Workspace(scope)
        ws.open(listOf(fileA, fileB))
        assertFalse(ws.canApplyFiltersToAll) // still loading
        ws.awaitLoaded()
        assertTrue(ws.canApplyFiltersToAll)

        val (ta, tb) = ws.tabs
        ta.selectedTags = setOf("OkHttp")
        ta.excludeText = "noise"
        ta.enabledLevels = LogLevel.entries.toSet() - LogLevel.DEBUG
        ta.timeFromText = "07-12 10:05:00.000" // narrowed; To is left at a's full span
        val bFullTo = tb.timeToText

        assertEquals(1, ws.applyFiltersToAll())

        assertEquals(setOf("OkHttp"), tb.selectedTags)
        assertEquals("noise", tb.excludeText)
        assertEquals(ta.enabledLevels, tb.enabledLevels)
        assertEquals("07-12 10:05:00.000", tb.timeFromText) // narrowed: carried over
        assertEquals(bFullTo, tb.timeToText) // untouched full span: b keeps its own
        // b re-filters: OkHttp, no "noise", no debug, from 10:05 to its own end.
        awaitUntil("b to re-filter") { tb.filteredLines.contentEquals(intArrayOf(0, 4)) }
        assertContentEquals(intArrayOf(0, 4), tb.filteredLines)
    }

    @Test
    fun `every tab counts the same filters the same way`() {
        // b's lines all fall after the time range a narrows to, so a bound
        // copied from a can't be "narrower" than b's own span.
        val fileA = log(
            "a.txt",
            line("10:00:00", 'I', "OkHttp", "alpha early"),
            line("10:06:00", 'I', "OkHttp", "alpha ok"),
            line("10:08:00", 'D', "OkHttp", "alpha debug"),
        )
        val fileB = log(
            "b.txt",
            line("10:30:00", 'I', "OkHttp", "bravo ok"),
            line("10:31:00", 'I', "OkHttp", "bravo noise"),
            line("10:40:00", 'D', "Audio", "bravo debug"),
        )
        val ws = Workspace(scope)
        ws.open(listOf(fileA, fileB))
        ws.awaitLoaded()
        val (ta, tb) = ws.tabs

        assertEquals(0, ta.activeFilterCount) // the pre-filled span is not a filter
        assertEquals(0, tb.activeFilterCount)

        ta.selectedTags = setOf("OkHttp")
        ta.excludeText = "noise"
        ta.enabledLevels = LogLevel.entries.toSet() - LogLevel.DEBUG
        ta.timeFromText = "07-12 10:05:00.000"
        ta.scheduleApply(0)
        awaitUntil("a to re-filter") { ta.activeFilterCount == 4 }

        ws.applyFiltersToAll()
        awaitUntil("b to re-filter") { tb.filteredLines.size == 1 }
        assertEquals(4, tb.activeFilterCount, "the same filters must count the same on every tab")
    }

    @Test
    fun `moving a tab reorders the strip and keeps the shown tab shown`() {
        val ws = Workspace(scope)
        ws.open(listOf(a, b, c))
        val (ta, tb, tc) = ws.tabs

        ws.select(tc)
        ws.moveTab(0, 2) // drag a to the end; c is still the one on screen
        assertEquals(listOf(tb, tc, ta), ws.tabs)
        assertSame(tc, ws.active)

        ws.select(tb)
        ws.moveTab(0, 1) // drag the shown tab itself
        assertEquals(listOf(tc, tb, ta), ws.tabs)
        assertSame(tb, ws.active)
    }

    @Test
    fun `a move that goes nowhere is a no-op`() {
        val ws = Workspace(scope)
        ws.open(listOf(a, b))
        val before = ws.tabs
        ws.moveTab(0, 0)
        ws.moveTab(0, 5)
        ws.moveTab(-1, 1)
        assertEquals(before, ws.tabs)
        assertSame(before[0], ws.active)
    }

    @Test
    fun `export refuses a target another tab has open`() {
        val ws = Workspace(scope)
        val fileB = b
        val before = fileB.readBytes()
        ws.open(listOf(a, fileB))
        ws.awaitLoaded()
        ws.select(ws.tabs[0])
        ws.exportActive(fileB)
        assertTrue("open in another tab" in ws.active.statusMessage, ws.active.statusMessage)
        assertContentEquals(before, fileB.readBytes())
    }

    private val mixed get() = log(
        "mixed.txt",
        line("10:00:00", 'I', "Keep", "first"),
        line("10:00:01", 'D', "Noise", "drop me"),
        line("10:00:02", 'E', "Keep", "second"),
        line("10:00:03", 'D', "Noise", "drop me too"),
    )

    /** Hides the Noise tag in the active tab and waits for the view to settle. */
    private fun Workspace.hideNoise() {
        val tab = active
        tab.selectedTags = setOf("Noise")
        tab.tagMode = com.sherlog.filter.FilterMode.HIDE
        tab.scheduleApply(0)
        awaitUntil("the filter") { tab.filteredLines.size == 2 && !tab.isBusy }
    }

    @Test
    fun `save keeps only the lines in view, re-indexes the file and resets the filters`() {
        val ws = Workspace(scope)
        val file = mixed
        ws.open(listOf(file))
        ws.awaitLoaded()
        assertFalse(ws.active.canSave) // nothing hidden, nothing to save
        ws.hideNoise()
        assertTrue(ws.active.canSave)
        assertEquals(2, ws.active.hiddenLineCount)

        ws.active.save()
        awaitUntil("the save") { !ws.active.isLoading && ws.active.index?.lineCount == 2 }
        assertEquals(
            listOf(line("10:00:00", 'I', "Keep", "first"), line("10:00:02", 'E', "Keep", "second")),
            file.readLines(),
        )
        assertEquals(listOf("Keep"), ws.active.index!!.tags.toList()) // the hidden tag left with its lines
        assertEquals(0, ws.active.activeFilterCount)
        assertEquals(2, ws.active.filteredLines.size)
        assertFalse(ws.active.canSave)
    }

    @Test
    fun `save as writes the view to a new file, leaves the original alone and shows the new one`() {
        val ws = Workspace(scope)
        val original = mixed
        val before = original.readBytes()
        ws.open(listOf(original))
        ws.awaitLoaded()
        ws.hideNoise()

        val target = File(dir, "keep_only.txt")
        ws.saveActiveAs(target)
        awaitUntil("the save") { !ws.active.isLoading && ws.active.file == target && ws.active.index != null }
        assertEquals(1, ws.tabs.size) // the same tab, now on the new file
        assertEquals(2, target.readLines().size)
        assertContentEquals(before, original.readBytes())
        assertEquals(0, ws.active.activeFilterCount)
    }

    @Test
    fun `save as refuses a file another tab has open`() {
        val ws = Workspace(scope)
        val fileB = b
        val before = fileB.readBytes()
        ws.open(listOf(mixed, fileB))
        ws.awaitLoaded()
        ws.select(ws.tabs[0])
        ws.hideNoise()
        ws.saveActiveAs(fileB)
        assertTrue("open in another tab" in ws.active.statusMessage, ws.active.statusMessage)
        assertContentEquals(before, fileB.readBytes())
        assertEquals("mixed.txt", ws.active.file!!.name)
    }

    @Test
    fun `a view with no lines can't be saved or exported`() {
        val ws = Workspace(scope)
        ws.open(listOf(mixed))
        ws.awaitLoaded()
        val tab = ws.active
        tab.pidText = "4242"
        tab.scheduleApply(0)
        awaitUntil("the filter") { tab.filteredLines.isEmpty() && !tab.isBusy }
        assertFalse(tab.canWriteView)
        assertFalse(tab.canSave)
    }

    @Test
    fun `new files are untitled, numbered, and have one empty line to type into`() {
        val ws = Workspace(scope)
        ws.newUntitled()
        ws.newUntitled()
        ws.awaitLoaded()
        assertEquals(listOf("Untitled", "Untitled 2"), ws.tabs.map { it.displayName })
        assertSame(ws.tabs[1], ws.active)
        assertEquals(1, ws.active.index!!.lineCount)
        assertNull(ws.active.notice) // plain text on purpose: no "not logcat" note
        assertFalse(ws.active.isDirty)
        ws.close(ws.tabs[0])
        ws.newUntitled()
        assertEquals("Untitled", ws.active.displayName) // the free name is reused
    }

    @Test
    fun `save as gives an untitled file its place, and its temp file goes`() {
        val ws = Workspace(scope)
        ws.newUntitled()
        ws.awaitLoaded()
        val tab = ws.active
        val temp = tab.file!!
        tab.editLine(0, "first line\nsecond line", original = "")
        val target = File(dir, "notes.txt")
        var saved: Boolean? = null
        ws.saveActiveAs(target) { saved = it }
        awaitUntil("the save") { saved == true && !tab.isLoading && tab.file == target && tab.index != null }
        assertEquals(listOf("first line", "second line"), target.readLines())
        assertFalse(tab.isUntitled)
        assertEquals("notes.txt", tab.displayName)
        assertFalse(temp.exists())
    }

    @Test
    fun `closing an untitled tab deletes its temp file`() {
        val ws = Workspace(scope)
        ws.newUntitled()
        ws.awaitLoaded()
        val temp = ws.active.file!!
        assertTrue(temp.exists())
        ws.close(ws.active)
        assertFalse(temp.exists())
    }
}
