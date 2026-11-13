package com.sherlog.ui

import java.util.prefs.Preferences
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class UiPrefsTest {

    // A throwaway node, so the test never touches the real settings.
    private val node: Preferences = Preferences.userRoot().node("com/sherlog/test-${System.nanoTime()}")

    @AfterTest
    fun cleanup() {
        node.removeNode()
    }

    @Test
    fun `zoom walks the browser steps and stops at either end`() {
        val prefs = UiPrefs()
        assertEquals(100, prefs.zoomPercent)
        prefs.zoom(1)
        assertEquals(110, prefs.zoomPercent)
        prefs.zoom(-3)
        assertEquals(80, prefs.zoomPercent)
        prefs.zoom(100)
        assertEquals(300, prefs.zoomPercent)
        prefs.zoom(-100)
        assertEquals(50, prefs.zoomPercent)
        prefs.resetZoom()
        assertEquals(1f, prefs.textScale)
    }

    @Test
    fun `zoom survives a restart`() {
        UiPrefs(node).zoom(2)
        assertEquals(125, UiPrefs(node).zoomPercent)
    }

    @Test
    fun `the filter panel's width and fold survive a restart`() {
        val prefs = UiPrefs(node)
        prefs.filterPanelWidth = 420f
        prefs.toggleFilterPanel()
        val again = UiPrefs(node)
        assertEquals(420f, again.filterPanelWidth)
        assertEquals(true, again.filterPanelCollapsed)
        again.resetFilterPanel()
        assertEquals(UiPrefs.DEFAULT_PANEL_WIDTH, UiPrefs(node).filterPanelWidth)
        assertEquals(false, UiPrefs(node).filterPanelCollapsed)
    }

    @Test
    fun `the panel never gets narrower than its contents need`() {
        val prefs = UiPrefs()
        prefs.filterPanelWidth = 10f
        assertEquals(UiPrefs.MIN_PANEL_WIDTH, prefs.filterPanelWidth)
        UiPrefs(node).apply { node.putFloat("filterPanelWidth", Float.NaN) }
        assertEquals(UiPrefs.DEFAULT_PANEL_WIDTH, UiPrefs(node).filterPanelWidth)
    }

    @Test
    fun `a saved zoom that is not a step falls back to 100`() {
        node.putInt("logZoomPercent", 113)
        assertEquals(100, UiPrefs(node).zoomPercent)
    }
}
