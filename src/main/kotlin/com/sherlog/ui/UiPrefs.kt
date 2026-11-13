package com.sherlog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.prefs.Preferences

/**
 * How the window is laid out, as opposed to what a file shows: the log text
 * zoom, and the filter panel's width and whether it is collapsed. These
 * belong to the app, so every tab shares them.
 *
 * Kept across restarts in [store] (the user's Java preferences; the registry
 * on Windows). Without one, nothing is saved — which is what tests use, so
 * they never touch the real settings.
 */
class UiPrefs(private val store: Preferences? = null) {

    /** Log text size, as a percentage of the default; always one of [ZOOM_STEPS]. */
    var zoomPercent by mutableStateOf(store?.getInt(ZOOM_KEY, 100)?.takeIf { it in ZOOM_STEPS } ?: 100)
        private set

    /** Scale for the log text: 1.0 at 100%. */
    val textScale: Float get() = zoomPercent / 100f

    /** Moves [steps] zoom steps, + for bigger; stops at either end. */
    fun zoom(steps: Int) {
        val at = ZOOM_STEPS.indexOf(zoomPercent).takeIf { it >= 0 } ?: ZOOM_STEPS.indexOf(100)
        setZoom(ZOOM_STEPS[(at + steps).coerceIn(ZOOM_STEPS.indices)])
    }

    fun resetZoom() = setZoom(100)

    private var panelWidth by mutableStateOf(
        store?.getFloat(PANEL_WIDTH_KEY, DEFAULT_PANEL_WIDTH)?.takeIf { it.isFinite() }
            ?.coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH) ?: DEFAULT_PANEL_WIDTH,
    )
    private var panelCollapsed by mutableStateOf(store?.getBoolean(PANEL_COLLAPSED_KEY, false) ?: false)

    /** The filter panel's width while open, in dp. The window caps it further. */
    var filterPanelWidth: Float
        get() = panelWidth
        set(dp) {
            val width = dp.coerceIn(MIN_PANEL_WIDTH, MAX_PANEL_WIDTH)
            if (width == panelWidth) return
            panelWidth = width
            store?.putFloat(PANEL_WIDTH_KEY, width)
        }

    /** Whether the filter panel is folded down to a thin strip, for more log room. */
    var filterPanelCollapsed: Boolean
        get() = panelCollapsed
        set(collapsed) {
            if (collapsed == panelCollapsed) return
            panelCollapsed = collapsed
            store?.putBoolean(PANEL_COLLAPSED_KEY, collapsed)
        }

    fun toggleFilterPanel() {
        filterPanelCollapsed = !filterPanelCollapsed
    }

    /** Back to how the panel first looked: open, at the default width. */
    fun resetFilterPanel() {
        filterPanelWidth = DEFAULT_PANEL_WIDTH
        filterPanelCollapsed = false
    }

    private fun setZoom(percent: Int) {
        if (percent == zoomPercent) return
        zoomPercent = percent
        store?.putInt(ZOOM_KEY, percent)
    }

    companion object {
        /** The browser's zoom steps, as people already know them. */
        val ZOOM_STEPS = listOf(50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300)

        private const val ZOOM_KEY = "logZoomPercent"

        const val DEFAULT_PANEL_WIDTH = 300f
        /** The five level checkboxes side by side need this much. */
        const val MIN_PANEL_WIDTH = 270f
        private const val MAX_PANEL_WIDTH = 1200f
        private const val PANEL_WIDTH_KEY = "filterPanelWidth"
        private const val PANEL_COLLAPSED_KEY = "filterPanelCollapsed"

        /** The real, saved preferences. */
        fun persistent() = UiPrefs(Preferences.userRoot().node("com/sherlog/ui"))
    }
}
