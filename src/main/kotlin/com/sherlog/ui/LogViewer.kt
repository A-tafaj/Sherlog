package com.sherlog.ui

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.sherlog.core.LineTextProvider
import com.sherlog.core.LogIndex
import com.sherlog.filter.FilterEngine
import com.sherlog.model.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val levelColors = mapOf(
    LogLevel.FATAL to Color(0xFFFF4081),
    LogLevel.ERROR to Color(0xFFFF6B6B),
    LogLevel.WARN to Color(0xFFFFB74D),
    LogLevel.INFO to Color(0xFF81C784),
    LogLevel.DEBUG to Color(0xFF64B5F6),
    LogLevel.VERBOSE to Color(0xFF9E9E9E),
    LogLevel.UNKNOWN to Color(0xFFB0BEC5),
)

private val searchHighlightStyle = SpanStyle(background = Color(0x66FFEB3B), color = Color.White)
private val selectionHighlightStyle = SpanStyle(background = Color(0x5900BCD4), color = Color.White)
// The single occurrence-line the next/prev arrows are currently sitting on.
private val activeSelectionHighlightStyle = SpanStyle(background = Color(0xE6FF9800), color = Color.Black)

/** Minimum selected characters before occurrence highlighting kicks in. */
private const val MIN_HIGHLIGHT_LENGTH = 2
private const val MAX_HIGHLIGHT_LENGTH = 200

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun LogViewer(
    index: LogIndex,
    provider: LineTextProvider,
    filteredLines: IntArray,
    searchQuery: String,
    searchIsRegex: Boolean,
    highlightNeedle: String,
    highlightIsRegex: Boolean,
    currentMatchPosition: Int,
    onSelectionChange: (lineIndex: Int, text: String) -> Unit,
    lineSelection: IntRange?,
    onLinePressed: (pos: Int) -> Unit,
    onSelectLines: (from: Int, to: Int) -> Unit,
    onExtendLineSelection: (pos: Int) -> Unit,
    onCopyLines: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    // Filter-mode search paints yellow; the occurrence/Find highlight paints
    // cyan (amber on the line the arrows sit on). Both go through the same
    // matcher, so Find honours the Regex checkbox while a double-click stays
    // a plain substring.
    val searchMatcher = remember(searchQuery, searchIsRegex) {
        if (searchQuery.isBlank()) null else FilterEngine.SearchMatcher(searchQuery, searchIsRegex)
    }
    val highlightMatcher = remember(highlightNeedle, highlightIsRegex) {
        if (highlightNeedle.isBlank()) null else FilterEngine.SearchMatcher(highlightNeedle, highlightIsRegex)
    }

    val scope = rememberCoroutineScope()
    val pressed = rememberUpdatedState(onLinePressed)
    val selected = rememberUpdatedState(onSelectLines)
    val extended = rememberUpdatedState(onExtendLineSelection)

    Box(modifier) {
        // Right-clicking offers the whole line selection next to the text
        // field's own Copy, which only knows about its own line.
        ContextMenuDataProvider(
            items = {
                val sel = lineSelection
                if (sel == null) emptyList() else listOf(ContextMenuItem(copyLinesLabel(sel), onCopyLines))
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(end = 12.dp)
                    .lineSelectionGestures(
                        listState,
                        scope,
                        onPress = { pressed.value(it) },
                        onSelect = { from, to -> selected.value(from, to) },
                        onExtend = { extended.value(it) },
                    ),
            ) {
                items(count = filteredLines.size, key = { filteredLines[it] }) { pos ->
                    val lineIndex = filteredLines[pos]
                    LogRow(
                        index, provider, lineIndex, searchMatcher, highlightMatcher,
                        isCurrentMatch = pos == currentMatchPosition,
                        isSelected = lineSelection?.contains(pos) == true,
                        onSelectionChange = onSelectionChange,
                    )
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

internal fun copyLinesLabel(sel: IntRange): String {
    val n = sel.last - sel.first + 1
    return "Copy %,d selected line%s".format(n, if (n == 1) "" else "s")
}

/**
 * Whole-line selection across rows. Each row is its own text field, so its
 * text selection can't cross into the next one; this watches the mouse ahead
 * of the rows (the Initial pass) instead. A press remembers its line. A drag
 * that reaches another line takes over from the row's text field — from then
 * on the rows see none of it — and selects every line in between, scrolling
 * while the pointer is past the top or bottom edge. Shift+press selects from
 * the line pressed last. A drag that stays within one line is left entirely
 * to its text field, and so to the occurrence highlight.
 */
private fun Modifier.lineSelectionGestures(
    listState: LazyListState,
    scope: CoroutineScope,
    onPress: (pos: Int) -> Unit,
    onSelect: (from: Int, to: Int) -> Unit,
    onExtend: (pos: Int) -> Unit,
): Modifier = pointerInput(listState) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val press = currentEvent
        // Right-click belongs to the context menu, which copies the selection.
        if (!press.buttons.isPrimaryPressed) return@awaitEachGesture
        val start = listState.lineAt(down.position.y, clamp = false) ?: return@awaitEachGesture

        if (press.keyboardModifiers.isShiftPressed) {
            onExtend(start)
            // The whole click is ours, so the row doesn't also act on it.
            down.consume()
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                event.changes.forEach { it.consume() }
                if (event.changes.none { it.pressed }) break
            }
            return@awaitEachGesture
        }

        onPress(start)
        var selecting = false
        var pointerY = down.position.y
        var autoScroll: Job? = null
        fun beyondEdge(): Float = when {
            pointerY < 0 -> pointerY
            pointerY > size.height -> pointerY - size.height
            else -> 0f
        }
        try {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                pointerY = change.position.y
                if (!selecting) {
                    val here = listState.lineAt(pointerY, clamp = true)
                    selecting = here != null && here != start
                }
                if (selecting) change.consume()
                if (!change.pressed) break
                if (!selecting) continue
                listState.lineAt(pointerY, clamp = true)?.let { onSelect(start, it) }
                if (beyondEdge() != 0f && autoScroll?.isActive != true) {
                    autoScroll = scope.launch {
                        while (true) {
                            val over = beyondEdge()
                            if (over == 0f) break
                            // Faster the further past the edge, never stalled.
                            val step = (over / 3).coerceIn(-40f, 40f)
                            listState.scrollBy(if (step > 0) maxOf(step, 4f) else minOf(step, -4f))
                            listState.lineAt(pointerY, clamp = true)?.let { onSelect(start, it) }
                            delay(16)
                        }
                    }
                }
            }
        } finally {
            autoScroll?.cancel()
        }
    }
}

/**
 * The position of the row under [y], in the list's own coordinates. Past the
 * rows (above, below, or under a short list) it is the nearest row on screen
 * when [clamp], else null.
 */
private fun LazyListState.lineAt(y: Float, clamp: Boolean): Int? {
    val items = layoutInfo.visibleItemsInfo
    if (items.isEmpty()) return null
    items.firstOrNull { y >= it.offset && y < it.offset + it.size }?.let { return it.index }
    if (!clamp) return null
    return if (y < items.first().offset) items.first().index else items.last().index
}

/**
 * One log line as a read-only text field. A text field (rather than plain
 * Text in a SelectionContainer) is what lets us observe the user's selection:
 * selecting a tag or phrase highlights every occurrence of it across the
 * visible lines.
 *
 * Clearing is driven by focus *gain*, not by the selection collapsing: a
 * plain click makes a row newly focused with no selection, so we clear then.
 * Clicking the status-bar next/prev arrows never gives a log row focus (and
 * the collapse it causes is ignored), so navigating keeps the highlight.
 */
@Composable
private fun LogRow(
    index: LogIndex,
    provider: LineTextProvider,
    lineIndex: Int,
    searchMatcher: FilterEngine.SearchMatcher?,
    highlightMatcher: FilterEngine.SearchMatcher?,
    isCurrentMatch: Boolean,
    isSelected: Boolean,
    onSelectionChange: (lineIndex: Int, text: String) -> Unit,
) {
    // A line whose bytes are already cached is resolved during composition, so
    // it draws with its real text immediately. Only a genuine cache miss shows
    // the placeholder and loads off the UI thread — otherwise a fast scroll
    // queues one coroutine per row and the "…" outlives the data by far.
    val cached = remember(lineIndex, provider) { runCatching { provider.cachedLine(lineIndex) }.getOrNull() }
    val text by produceState(cached ?: "…", lineIndex, provider) {
        if (cached == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { provider.line(lineIndex) }.getOrElse { "<read error: ${it.message}>" }
            }
        }
    }
    var fieldValue by remember(lineIndex, text) { mutableStateOf(TextFieldValue(text)) }
    val level = index.level(lineIndex)
    val color = levelColors.getValue(level)
    val rowBackground = when {
        isSelected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)
        level == LogLevel.ERROR || level == LogLevel.FATAL -> Color(0x14FF0000)
        level == LogLevel.WARN -> Color(0x14FFA000)
        else -> Color.Transparent
    }
    // Once a line is part of a multi-line selection, the partial text
    // selection the drag left in it (it began as an ordinary text drag) would
    // just be noise on top of the whole-line tint.
    LaunchedEffect(isSelected) {
        if (isSelected && !fieldValue.selection.collapsed) {
            fieldValue = fieldValue.copy(selection = TextRange(fieldValue.selection.max))
        }
    }
    val transformation = remember(searchMatcher, highlightMatcher, isCurrentMatch) {
        LogHighlightTransformation(searchMatcher, highlightMatcher, isCurrentMatch)
    }

    BasicTextField(
        value = fieldValue,
        onValueChange = { new ->
            // readOnly guarantees the text is unchanged; only selection moves.
            fieldValue = TextFieldValue(text, new.selection)
            val sel = new.selection
            // Only a real (non-collapsed) selection updates the highlight. A
            // collapse is ignored here — clearing is handled on focus gain so
            // that the collapse caused by clicking the nav arrows is harmless.
            if (!sel.collapsed) {
                val selected = text.substring(sel.min, sel.max).trim()
                if (selected.length in MIN_HIGHLIGHT_LENGTH..MAX_HIGHLIGHT_LENGTH) onSelectionChange(lineIndex, selected)
            }
        },
        readOnly = true,
        textStyle = TextStyle(
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            lineHeight = 16.sp,
        ),
        cursorBrush = SolidColor(color),
        visualTransformation = transformation,
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .onFocusChanged { focus ->
                // A plain click gives this row focus with a collapsed (empty)
                // selection -> clear the highlight. Word-selection sets it via
                // onValueChange afterwards. Arrow clicks don't focus any row.
                if (focus.isFocused && fieldValue.selection.collapsed) onSelectionChange(lineIndex, "")
            },
    )
}

/**
 * Paints search matches (yellow) and occurrences of the user's current
 * selection without altering the text, so offsets map 1:1. On the line the
 * next/prev arrows are currently sitting on ([activeSelection]) the selection
 * occurrences are painted amber instead of cyan, so it stands out from
 * neighbouring matches.
 */
private class LogHighlightTransformation(
    private val searchMatcher: FilterEngine.SearchMatcher?,
    private val highlightMatcher: FilterEngine.SearchMatcher?,
    private val activeSelection: Boolean,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val selStyle = if (activeSelection) activeSelectionHighlightStyle else selectionHighlightStyle
        val styled = buildAnnotatedString {
            append(raw)
            searchMatcher?.regex?.let { regex ->
                for (m in regex.findAll(raw)) {
                    if (!m.range.isEmpty()) addStyle(searchHighlightStyle, m.range.first, m.range.last + 1)
                }
            }
            // The occurrence / Find highlight. Goes through a matcher too, so a
            // regex Find paints every match and a double-click stays substring.
            highlightMatcher?.regex?.let { regex ->
                for (m in regex.findAll(raw)) {
                    if (!m.range.isEmpty()) addStyle(selStyle, m.range.first, m.range.last + 1)
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is LogHighlightTransformation && other.searchMatcher == searchMatcher &&
            other.highlightMatcher == highlightMatcher && other.activeSelection == activeSelection

    override fun hashCode(): Int =
        31 * (31 * (searchMatcher?.hashCode() ?: 0) + (highlightMatcher?.hashCode() ?: 0)) +
            activeSelection.hashCode()
}

@Composable
fun EmptyViewer(message: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
