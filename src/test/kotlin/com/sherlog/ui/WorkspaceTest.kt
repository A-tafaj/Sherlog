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

    @Test
    fun `open folder takes only the txt and log files directly inside it, by name`() {
        log("b.log", "x")
        log("A.TXT", "x")
        log("notes.json", "x")
        log("logcat.log.1", "x")
        log("sub/d.txt", "x")
        val ws = Workspace(scope)
        ws.openFolder(dir)
        assertEquals(listOf("A.TXT", "b.log"), ws.tabs.map { it.file!!.name })
    }

    @Test
    fun `a folder without log files opens nothing and says so`() {
        log("notes.json", "x")
        val ws = Workspace(scope)
        ws.openFolder(dir)
        assertEquals(1, ws.tabs.size)
        assertNull(ws.active.file)
        assertTrue("No .txt or .log files" in ws.active.statusMessage, ws.active.statusMessage)
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
}
