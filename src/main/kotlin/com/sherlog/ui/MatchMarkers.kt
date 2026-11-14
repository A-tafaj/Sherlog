package com.sherlog.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import kotlin.math.abs

private val MarkerColor = Color(0xCC00BCD4) // the occurrence highlight's cyan
private val CurrentMarkerColor = Color(0xFFFF9800) // the current match's amber

/** A click this close to a marker (in px) counts as a click on it. */
private const val HIT_SLOP_PX = 4f

/**
 * Where the matches sit in the whole view, as ticks in a thin lane beside
 * the scrollbar (IntelliJ's marker stripe, not dots on the scrollbar: those
 * would fight with dragging the thumb). Clicking a tick jumps to that match.
 *
 * [matches] are positions in a view of [total] lines, ascending, as
 * [AppState.highlightMatches] holds them. Ticks that land on the same pixel
 * row are drawn once, so 100,000 matches cost no more than the lane's height.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MatchMarkers(
    matches: IntArray,
    total: Int,
    currentMatchIndex: Int,
    onMarkerClick: (matchIndex: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (matches.isEmpty() || total <= 0) return
    var heightPx by remember { mutableIntStateOf(0) }
    var hovered by remember { mutableStateOf<Int?>(null) }
    val click = rememberUpdatedState(onMarkerClick)

    fun rowOf(position: Int): Float = (position + 0.5f) / total * heightPx

    /** The match drawn nearest to [y], if a click there would be on it. */
    fun matchAt(y: Float): Int? {
        if (heightPx <= 0) return null
        val target = (y / heightPx * total).toInt().coerceIn(0, total - 1)
        val at = matches.binarySearch(target).let { if (it >= 0) it else -(it + 1) }
        return listOf(at - 1, at).filter { it in matches.indices }
            .minByOrNull { abs(rowOf(matches[it]) - y) }
            ?.takeIf { abs(rowOf(matches[it]) - y) <= HIT_SLOP_PX }
    }

    // Distinct pixel rows holding at least one match.
    val rows = remember(matches, total, heightPx) {
        if (heightPx <= 0) IntArray(0) else {
            val seen = BooleanArray(heightPx + 1)
            for (p in matches) seen[rowOf(p).toInt().coerceIn(0, heightPx)] = true
            seen.indices.filter { seen[it] }.toIntArray()
        }
    }

    TooltipArea(
        tooltip = { hovered?.let { TooltipText("Match %,d / %,d".format(it + 1, matches.size)) } },
        modifier = modifier,
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged { heightPx = it.height }
                .pointerHoverIcon(if (hovered != null) PointerIcon.Hand else PointerIcon.Default)
                .pointerInput(matches, total, heightPx) {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent()
                            hovered = when (event.type) {
                                PointerEventType.Exit -> null
                                else -> event.changes.firstOrNull()?.let { matchAt(it.position.y) }
                            }
                        }
                    }
                }
                .pointerInput(matches, total, heightPx) {
                    detectTapGestures { offset -> matchAt(offset.y)?.let { click.value(it) } }
                }
                .testTag("matchMarkers"),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val tick = 2f
                for (row in rows) {
                    drawRect(MarkerColor, Offset(0f, row - tick / 2), Size(size.width, tick))
                }
                if (currentMatchIndex in matches.indices) {
                    val y = rowOf(matches[currentMatchIndex])
                    drawRect(CurrentMarkerColor, Offset(0f, y - 1.5f), Size(size.width, 3f))
                }
            }
        }
    }
}
