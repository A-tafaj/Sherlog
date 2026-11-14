package com.sherlog.ui

import androidx.compose.foundation.ContextMenuDataProvider
import androidx.compose.foundation.ContextMenuItem
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
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

internal val searchHighlightStyle = SpanStyle(background = Color(0x66FFEB3B), color = Color.White)
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
    onSelectAll: () -> Unit,
    textScale: Float,
    onZoom: (steps: Int) -> Unit,
    /** Match positions for the scrollbar markers; empty while they're being recounted. */
    matchPositions: IntArray,
    currentMatchIndex: Int,
    onMatchClick: (matchIndex: Int) -> Unit,
    /** Unsaved text by file line, shown instead of the file's. */
    edits: Map<Int, String>,
    onEditLine: (line: Int, text: String, original: String) -> Unit,
    /** Deletes the lines at these view positions. */
    onDeleteLines: (positions: IntRange) -> Unit,
    /** Backspace at a row's start; returns the caret for the row above, or null. */
    onJoinPrevious: (position: Int) -> Int?,
    /** Delete at a row's end; whether the row below joined it. */
    onJoinNext: (position: Int) -> Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    /** Put the caret in the first line now (a new Untitled tab), then say so. */
    focusFirstRow: Boolean,
    onFirstRowFocused: () -> Unit,
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
    val zoom = rememberUpdatedState(onZoom)
    val selection = rememberUpdatedState(lineSelection)
    // The row the caret is in, as a view position: what Ctrl+Shift+K deletes.
    var focusedPosition by remember { mutableIntStateOf(-1) }
    // Where the caret goes next when it leaves its row (Up/Down at an edge,
    // a join). Rows are text fields of their own, so the row at that position
    // takes focus and places the caret itself once it's composed.
    var caretRequest by remember { mutableStateOf<CaretRequest?>(null) }
    LaunchedEffect(focusFirstRow, filteredLines.isNotEmpty()) {
        if (focusFirstRow && filteredLines.isNotEmpty()) {
            caretRequest = CaretRequest(0, offset = 0)
            onFirstRowFocused()
        }
    }

    /** Moves the caret into the row at [request]'s position, scrolling it into view first. */
    fun moveCaret(request: CaretRequest) {
        if (request.position !in filteredLines.indices) return
        caretRequest = request
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == request.position }
        val onScreen = item != null && item.offset >= info.viewportStartOffset &&
            item.offset + item.size <= info.viewportEndOffset
        if (!onScreen) {
            val first = listState.firstVisibleItemIndex
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: first
            scope.launch {
                when {
                    request.position <= first -> listState.scrollToItem(request.position)
                    // One row down off the bottom: scroll a row, as editors do.
                    request.position <= lastVisible + 1 -> listState.scrollToItem(first + 1)
                    else -> listState.scrollToItem(request.position)
                }
            }
        }
    }

    // Keys that act on whole lines. They're seen here only while focus is in
    // the viewer, so the search box and the filter fields keep their own —
    // Ctrl+Z there undoes typing in that box, not the log's edits.
    // A clicked row is a text field whose Ctrl+A would select just that one
    // line's text; in here it means every line in the view. Delete with lines
    // selected removes them, as in an editor; without, it's the row's own.
    Box(
        modifier
            .onPreviewKeyEvent {
                if (it.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val sel = selection.value
                when {
                    it.isCtrlPressed && it.key == Key.A -> { onSelectAll(); true }
                    it.isCtrlPressed && it.key == Key.Z && !it.isShiftPressed -> { onUndo(); true }
                    it.isCtrlPressed && (it.key == Key.Y || (it.key == Key.Z && it.isShiftPressed)) -> { onRedo(); true }
                    sel != null && (it.key == Key.Delete || it.key == Key.Backspace) -> { onDeleteLines(sel); true }
                    it.isCtrlPressed && it.isShiftPressed && it.key == Key.K -> {
                        val target = sel ?: focusedPosition.takeIf { p -> p in filteredLines.indices }?.let { p -> p..p }
                        if (target != null) onDeleteLines(target)
                        true
                    }
                    else -> false
                }
            }
            .ctrlWheelZoom { zoom.value(it) },
    ) {
        // Right-clicking offers the whole line selection next to the text
        // field's own Copy, which only knows about its own line.
        ContextMenuDataProvider(
            items = {
                val sel = lineSelection
                if (sel == null) emptyList() else listOf(
                    ContextMenuItem(copyLinesLabel(sel), onCopyLines),
                    ContextMenuItem(deleteLinesLabel(sel)) { onDeleteLines(sel) },
                )
            },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    // Room for the scrollbar (8 dp) and, left of it, the match markers.
                    .padding(end = 18.dp)
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
                        textScale = textScale,
                        editedText = edits[lineIndex],
                        onSelectionChange = onSelectionChange,
                        onEdit = { text, original -> onEditLine(lineIndex, text, original) },
                        onFocused = { focusedPosition = pos },
                        caretRequest = caretRequest?.takeIf { it.position == pos },
                        onCaretPlaced = { caretRequest = null },
                        onLeaveUp = { column -> moveCaret(CaretRequest(pos - 1, column = column, intoLastLine = true)) },
                        onLeaveDown = { column -> moveCaret(CaretRequest(pos + 1, column = column)) },
                        onBackspaceAtStart = {
                            onJoinPrevious(pos)?.let { caret -> moveCaret(CaretRequest(pos - 1, offset = caret)) }
                        },
                        onDeleteAtEnd = { onJoinNext(pos) },
                    )
                }
            }
        }
        MatchMarkers(
            matches = matchPositions,
            total = filteredLines.size,
            currentMatchIndex = currentMatchIndex,
            onMarkerClick = onMatchClick,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp).width(6.dp).fillMaxHeight(),
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

/**
 * Ctrl+wheel zooms instead of scrolling. Watched in the Initial pass, ahead of
 * the list, which would otherwise scroll. A mouse notch is one step; a
 * trackpad pinch (which Windows sends as Ctrl+wheel in small fractions) adds
 * up to one before it counts, or it would race through every size.
 */
private fun Modifier.ctrlWheelZoom(onZoom: (steps: Int) -> Unit): Modifier = pointerInput(Unit) {
    var pending = 0f
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll || !event.keyboardModifiers.isCtrlPressed) continue
            event.changes.forEach { it.consume() }
            // Wheel up (negative) is bigger, as in browsers and editors.
            pending -= event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
            val steps = pending.toInt()
            if (steps != 0) {
                pending -= steps
                onZoom(steps)
            }
        }
    }
}

internal fun copyLinesLabel(sel: IntRange): String {
    val n = sel.last - sel.first + 1
    return "Copy %,d selected line%s".format(n, if (n == 1) "" else "s")
}

/**
 * Where the caret should land in the row at [position]: at [offset] when
 * given, else at [column] of its first line — or of its last line when
 * [intoLastLine] (moving up into it). Clamped to the line's length.
 */
private data class CaretRequest(
    val position: Int,
    val offset: Int? = null,
    val column: Int = 0,
    val intoLastLine: Boolean = false,
) {
    fun caretIn(text: String): Int {
        offset?.let { return it.coerceIn(0, text.length) }
        val lineStart = if (intoLastLine) text.lastIndexOf('\n') + 1 else 0
        val lineEnd = if (intoLastLine) text.length else text.indexOf('\n').let { if (it < 0) text.length else it }
        return (lineStart + column).coerceAtMost(lineEnd)
    }
}

internal fun deleteLinesLabel(sel: IntRange): String {
    val n = sel.last - sel.first + 1
    return "Delete %,d selected line%s".format(n, if (n == 1) "" else "s")
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
 * One log line as a text field, editable like any editor's line. A text
 * field (rather than plain Text in a SelectionContainer) is also what lets us
 * observe the user's selection: selecting a tag or phrase highlights every
 * occurrence of it across the visible lines.
 *
 * Clearing is driven by focus *gain*, not by the selection collapsing: a
 * plain click makes a row newly focused with no selection, so we clear then.
 * Clicking the status-bar next/prev arrows never gives a log row focus (and
 * the collapse it causes is ignored), so navigating keeps the highlight.
 *
 * Typing reports the new text through [onEdit]; the file is not touched.
 * Enter and pasted line breaks stay inside the row (it grows) until Save
 * writes them out as lines of their own. At the row's edges the caret moves
 * on: Up/Down into the neighbouring row, Backspace at the start and Delete
 * at the end join rows — each row being its own field, the viewer does that.
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
    textScale: Float,
    /** The unsaved text of this line, or null when it is as in the file. */
    editedText: String?,
    onSelectionChange: (lineIndex: Int, text: String) -> Unit,
    onEdit: (text: String, original: String) -> Unit,
    onFocused: () -> Unit,
    /** Set when the caret should move into this row; it takes focus and places it. */
    caretRequest: CaretRequest?,
    onCaretPlaced: () -> Unit,
    onLeaveUp: (column: Int) -> Unit,
    onLeaveDown: (column: Int) -> Unit,
    onBackspaceAtStart: () -> Unit,
    onDeleteAtEnd: () -> Unit,
) {
    // A line whose bytes are already cached is resolved during composition, so
    // it draws with its real text immediately. Only a genuine cache miss shows
    // the placeholder and loads off the UI thread — otherwise a fast scroll
    // queues one coroutine per row and the "…" outlives the data by far.
    val cached = remember(lineIndex, provider) { runCatching { provider.cachedLine(lineIndex) }.getOrNull() }
    // The line as the file has it; null while it is being read.
    val fromFile by produceState(cached, lineIndex, provider) {
        if (cached == null) {
            value = withContext(Dispatchers.IO) {
                runCatching { provider.line(lineIndex) }.getOrElse { "$READ_ERROR${it.message}>" }
            }
        }
    }
    // A placeholder or an error message isn't the line, so it can't be edited.
    val original = fromFile?.takeUnless { it.startsWith(READ_ERROR) }
    val text = editedText ?: fromFile ?: "…"
    // The field keeps its own value while the user types, so the caret stays
    // where it is. When the text changes any other way — the line loading,
    // a save — the field starts over from the new text.
    var fieldValue by remember(lineIndex) { mutableStateOf(TextFieldValue(text)) }
    val value = if (fieldValue.text == text) fieldValue
    else TextFieldValue(text, TextRange(fieldValue.selection.end.coerceAtMost(text.length)))
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
        if (isSelected && !value.selection.collapsed) {
            fieldValue = value.copy(selection = TextRange(value.selection.max))
        }
    }
    val transformation = remember(searchMatcher, highlightMatcher, isCurrentMatch) {
        LogHighlightTransformation(searchMatcher, highlightMatcher, isCurrentMatch)
    }
    val editedMark = MaterialTheme.colorScheme.primary
    val rowFocus = remember { FocusRequester() }
    // Which visual line the caret is on decides whether Up/Down stay in the
    // row (a wrapped or multi-line row) or leave it.
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }

    LaunchedEffect(caretRequest) {
        val request = caretRequest ?: return@LaunchedEffect
        fieldValue = TextFieldValue(text, TextRange(request.caretIn(text)))
        runCatching { rowFocus.requestFocus() }
        onCaretPlaced()
    }

    /** The caret's column within its own line (rows can hold several). */
    fun columnOf(caret: Int): Int = caret - (text.lastIndexOf('\n', caret - 1) + 1)

    BasicTextField(
        value = value,
        onValueChange = { new ->
            // Line breaks stay (Enter, a paste), in one form only.
            val typed = new.text.replace("\r\n", "\n").replace('\r', '\n')
            val next = if (typed.length == new.text.length) new.copy(text = typed)
            else TextFieldValue(typed, TextRange(new.selection.end.coerceAtMost(typed.length)))
            fieldValue = next
            if (typed != value.text) {
                if (original != null) onEdit(typed, original)
                return@BasicTextField
            }
            val sel = next.selection
            // Only a real (non-collapsed) selection updates the highlight. A
            // collapse is ignored here — clearing is handled on focus gain so
            // that the collapse caused by clicking the nav arrows is harmless.
            if (!sel.collapsed) {
                val selected = typed.substring(sel.min, sel.max).trim()
                if (selected.length in MIN_HIGHLIGHT_LENGTH..MAX_HIGHLIGHT_LENGTH) onSelectionChange(lineIndex, selected)
            }
        },
        readOnly = original == null,
        textStyle = TextStyle(
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = (12 * textScale).sp,
            lineHeight = (16 * textScale).sp,
        ),
        cursorBrush = SolidColor(color),
        visualTransformation = transformation,
        onTextLayout = { layout = it },
        modifier = Modifier
            .fillMaxWidth()
            .background(rowBackground)
            // A bar down the left edge marks a line with unsaved changes.
            .drawBehind { if (editedText != null) drawRect(editedMark, size = Size(2.dp.toPx(), size.height)) }
            .padding(horizontal = 8.dp, vertical = 1.dp)
            .focusRequester(rowFocus)
            // The edges of the row, where the caret moves on to another one.
            // Only with a plain caret: Shift extends a selection, which stays
            // in its row, and a selection is the field's to handle.
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || original == null) return@onPreviewKeyEvent false
                val sel = value.selection
                val lines = layout
                if (!sel.collapsed || e.isShiftPressed || e.isCtrlPressed || lines == null) return@onPreviewKeyEvent false
                val caret = sel.start
                when (e.key) {
                    Key.DirectionUp -> (lines.getLineForOffset(caret) == 0).also { if (it) onLeaveUp(columnOf(caret)) }
                    Key.DirectionDown -> (lines.getLineForOffset(caret) == lines.lineCount - 1)
                        .also { if (it) onLeaveDown(columnOf(caret)) }
                    Key.Backspace -> (caret == 0).also { if (it) onBackspaceAtStart() }
                    Key.Delete -> (caret == value.text.length).also { if (it) onDeleteAtEnd() }
                    else -> false
                }
            }
            .onFocusChanged { focus ->
                if (focus.isFocused) onFocused()
                // A plain click gives this row focus with a collapsed (empty)
                // selection -> clear the highlight. Word-selection sets it via
                // onValueChange afterwards. Arrow clicks don't focus any row.
                if (focus.isFocused && value.selection.collapsed) onSelectionChange(lineIndex, "")
            },
    )
}

/** Marks a line that couldn't be read: shown, but not editable. */
private const val READ_ERROR = "<read error: "

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
