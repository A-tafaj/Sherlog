package com.sherlog.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.awt.Cursor

/** How wide the collapsed panel's strip is, in dp. */
private const val STRIP_WIDTH = 24f

/** Dragged this far below the minimum width, the panel snaps shut. */
private const val SNAP_SHUT_MARGIN = 80f

/**
 * The filter panel with its edge, or, collapsed, a thin strip in its place.
 * The panel folds away three ways: its « button, Ctrl+B, or dragging its
 * edge well past the minimum width. The strip keeps the count of active
 * filters in sight, since hidden filters still drop lines.
 *
 * Width and state live in [UiPrefs], not here or in the tab: this sits under
 * `key(state)` in [App], so anything remembered here would reset on every
 * tab switch.
 */
@Composable
fun FilterSidePanel(state: AppState, prefs: UiPrefs, maxWidth: Dp) {
    val maxDp = maxOf(UiPrefs.MIN_PANEL_WIDTH, maxWidth.value)
    if (prefs.filterPanelCollapsed) {
        CollapsedFilterStrip(state, onExpand = { prefs.filterPanelCollapsed = false })
    } else {
        FilterPanel(
            state,
            onCollapse = { prefs.filterPanelCollapsed = true },
            modifier = Modifier.width(prefs.filterPanelWidth.coerceIn(UiPrefs.MIN_PANEL_WIDTH, maxDp).dp).fillMaxHeight(),
        )
    }
    PanelEdge(prefs, maxDp)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollapsedFilterStrip(state: AppState, onExpand: () -> Unit) {
    val active = state.activeFilterCount
    val hint = if (active > 0) "Show filters (Ctrl+B) — $active active" else "Show filters (Ctrl+B)"
    TooltipArea(tooltip = { TooltipText(hint) }, modifier = Modifier.fillMaxHeight()) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .width(STRIP_WIDTH.dp)
                .fillMaxHeight()
                .clickable(onClick = onExpand)
                .padding(top = 8.dp)
                .testTag("filterStrip"),
        ) {
            Text("»", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
            if (active > 0) {
                Badge(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.testTag("filterStripCount"),
                ) { Text("$active") }
            }
        }
    }
}

/**
 * The line between the panel and the log. Dragging it resizes the panel;
 * dragging well past the minimum folds the panel away, and dragging back out
 * reopens it. A double-click puts the default width back.
 */
@Composable
private fun PanelEdge(prefs: UiPrefs, maxDp: Float) {
    val density = LocalDensity.current
    // Where the drag has got to, unclamped: the panel's own width stops at the
    // minimum, so it can't tell "dragged a little too far" from "meant to close".
    var reached by remember { mutableFloatStateOf(0f) }
    val dragState = rememberDraggableState { deltaPx ->
        reached += with(density) { deltaPx.toDp().value }
        if (reached < UiPrefs.MIN_PANEL_WIDTH - SNAP_SHUT_MARGIN) {
            prefs.filterPanelCollapsed = true
        } else {
            prefs.filterPanelCollapsed = false
            prefs.filterPanelWidth = reached.coerceAtMost(maxDp)
        }
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxHeight()
            .width(7.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .draggable(
                dragState,
                Orientation.Horizontal,
                onDragStarted = {
                    reached = if (prefs.filterPanelCollapsed) STRIP_WIDTH
                    else prefs.filterPanelWidth.coerceAtMost(maxDp)
                },
            )
            .pointerInput(prefs) { detectTapGestures(onDoubleTap = { prefs.resetFilterPanel() }) }
            .testTag("filterPanelEdge"),
    ) {
        VerticalDivider()
    }
}
