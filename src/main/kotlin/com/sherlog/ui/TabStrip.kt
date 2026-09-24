package com.sherlog.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex

/**
 * How narrow a tab may get before the strip starts scrolling instead, and how
 * wide it may grow when only a couple of files are open.
 */
private val MIN_TAB_WIDTH = 110.dp
private val MAX_TAB_WIDTH = 220.dp

/** Below this, the filtered-line badge would eat the file name. */
private val BADGE_MIN_WIDTH = 150.dp

/**
 * One tab per open file, Sublime-style. Tabs share the width evenly and shrink
 * as more open; past [MIN_TAB_WIDTH] they stop shrinking and the strip scrolls,
 * keeping the active tab in view. The + stays outside the scrolling part, so it
 * is reachable however many files are open.
 *
 * The blank tab that stands in while nothing is open gets no chip.
 */
@Composable
fun TabStrip(workspace: Workspace, onAddFiles: () -> Unit) {
    val chips = workspace.tabs.filter { it.file != null }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
    ) {
        BoxWithConstraints(Modifier.weight(1f)) {
            // maxWidth is already net of the + button, which Row measures first.
            val tabWidth = (maxWidth / chips.size.coerceAtLeast(1)).coerceIn(MIN_TAB_WIDTH, MAX_TAB_WIDTH)
            val strip = rememberScrollState()
            val viewport = maxWidth
            val widthPx = with(LocalDensity.current) { tabWidth.toPx() }
            // Which chip is being dragged, and how far from its slot.
            var dragging by remember { mutableStateOf<AppState?>(null) }
            var dragOffset by remember { mutableStateOf(0f) }
            Row(Modifier.horizontalScroll(strip)) {
                for (tab in chips) {
                    // Keyed by identity so a reorder moves the node with its
                    // tab instead of handing it to whoever took the slot.
                    key(tab) {
                        FileTab(
                            tab,
                            width = tabWidth,
                            isActive = tab === workspace.active,
                            isDragging = tab === dragging,
                            dragOffset = if (tab === dragging) dragOffset else 0f,
                            onSelect = { workspace.select(tab) },
                            onClose = { workspace.close(tab) },
                            onDragStart = { dragging = tab; dragOffset = 0f },
                            onDrag = { delta ->
                                dragOffset += delta
                                // Crossed a neighbour: reorder now and keep the
                                // chip under the pointer by dropping a slot's
                                // worth of offset.
                                val slots = (dragOffset / widthPx).toInt()
                                if (slots != 0) {
                                    val at = workspace.tabs.indexOfFirst { it === tab }
                                    val target = (at + slots).coerceIn(0, workspace.tabs.lastIndex)
                                    if (target != at) {
                                        workspace.moveTab(at, target)
                                        dragOffset -= (target - at) * widthPx
                                    }
                                }
                            },
                            onDragEnd = { dragging = null; dragOffset = 0f },
                        )
                    }
                }
            }
            // Every chip is the same width, so where the active one sits is
            // arithmetic — no per-chip position bookkeeping.
            val density = LocalDensity.current
            LaunchedEffect(workspace.active, chips.size, tabWidth, strip.maxValue) {
                val i = chips.indexOfFirst { it === workspace.active }
                if (i < 0) return@LaunchedEffect
                val width = with(density) { tabWidth.roundToPx() }
                val view = with(density) { viewport.roundToPx() }
                val start = i * width
                when {
                    start < strip.value -> strip.animateScrollTo(start)
                    start + width > strip.value + view -> strip.animateScrollTo(start + width - view)
                }
            }
        }
        PlusButton(onAddFiles)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlusButton(onAddFiles: () -> Unit) {
    TooltipArea(tooltip = { TooltipText("Open files in new tabs (Ctrl+O)") }) {
        Text(
            "+",
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .clickable(onClick = onAddFiles)
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileTab(
    tab: AppState,
    width: Dp,
    isActive: Boolean,
    isDragging: Boolean,
    dragOffset: Float,
    onSelect: () -> Unit,
    onClose: () -> Unit,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val file = tab.file ?: return
    val accent = MaterialTheme.colorScheme.primary
    TooltipArea(tooltip = { TooltipText(file.absolutePath) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .width(width)
                // Drawn over its neighbours while it is being dragged.
                .zIndex(if (isDragging) 1f else 0f)
                .graphicsLayer { translationX = dragOffset }
                // Before clickable: a drag past the touch slop consumes the
                // gesture, so the chip's click is cancelled, while a plain
                // press with no movement still selects the tab.
                .pointerInput(tab) {
                    detectDragGestures(
                        onDragStart = { onDragStart() },
                        onDrag = { change, delta -> change.consume(); onDrag(delta.x) },
                        onDragEnd = { onDragEnd() },
                        onDragCancel = { onDragEnd() },
                    )
                }
                .background(if (isActive) MaterialTheme.colorScheme.background else Color.Transparent)
                // A bar along the top marks the active tab by more than a shade.
                .drawBehind { if (isActive) drawRect(accent, size = Size(size.width, 2.dp.toPx())) }
                .clickable(onClick = onSelect)
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                file.name,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            // With filters active each tab shows its line count, so after
            // "Apply filters to all tabs" the files with hits stand out. On a
            // crowded strip the name matters more, so the badge steps aside.
            val badge = when {
                tab.isLoading || tab.isBusy -> "…"
                tab.index != null && tab.activeFilterCount > 0 && width >= BADGE_MIN_WIDTH ->
                    "%,d".format(tab.filteredLines.size)
                else -> null
            }
            if (badge != null) Text(badge, fontSize = 11.sp, color = accent, maxLines = 1)
            Text(
                "×",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 6.dp),
            )
        }
    }
}
