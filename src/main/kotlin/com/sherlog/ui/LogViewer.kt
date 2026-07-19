package com.sherlog.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
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
import kotlinx.coroutines.Dispatchers
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

@Composable
fun LogViewer(
    index: LogIndex,
    provider: LineTextProvider,
    filteredLines: IntArray,
    searchQuery: String,
    searchIsRegex: Boolean,
    selectionHighlight: String,
    currentMatchPosition: Int,
    onSelectionChange: (String) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val matcher = remember(searchQuery, searchIsRegex) {
        if (searchQuery.isBlank()) null else FilterEngine.SearchMatcher(searchQuery, searchIsRegex)
    }

    Box(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
            items(count = filteredLines.size, key = { filteredLines[it] }) { pos ->
                val lineIndex = filteredLines[pos]
                LogRow(index, provider, lineIndex, matcher, selectionHighlight, pos == currentMatchPosition, onSelectionChange)
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
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
    matcher: FilterEngine.SearchMatcher?,
    selectionHighlight: String,
    isCurrentMatch: Boolean,
    onSelectionChange: (String) -> Unit,
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
    val rowBackground = when (level) {
        LogLevel.ERROR, LogLevel.FATAL -> Color(0x14FF0000)
        LogLevel.WARN -> Color(0x14FFA000)
        else -> Color.Transparent
    }
    val transformation = remember(matcher, selectionHighlight, isCurrentMatch) {
        LogHighlightTransformation(matcher, selectionHighlight, isCurrentMatch)
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
                if (selected.length in MIN_HIGHLIGHT_LENGTH..MAX_HIGHLIGHT_LENGTH) onSelectionChange(selected)
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
                if (focus.isFocused && fieldValue.selection.collapsed) onSelectionChange("")
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
    private val matcher: FilterEngine.SearchMatcher?,
    private val selection: String,
    private val activeSelection: Boolean,
) : VisualTransformation {

    override fun filter(text: AnnotatedString): TransformedText {
        val raw = text.text
        val selStyle = if (activeSelection) activeSelectionHighlightStyle else selectionHighlightStyle
        val styled = buildAnnotatedString {
            append(raw)
            matcher?.regex?.let { regex ->
                for (m in regex.findAll(raw)) {
                    if (!m.range.isEmpty()) addStyle(searchHighlightStyle, m.range.first, m.range.last + 1)
                }
            }
            if (selection.length >= MIN_HIGHLIGHT_LENGTH) {
                var i = raw.indexOf(selection, 0, ignoreCase = true)
                while (i >= 0) {
                    addStyle(selStyle, i, i + selection.length)
                    i = raw.indexOf(selection, i + selection.length, ignoreCase = true)
                }
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    override fun equals(other: Any?): Boolean =
        other is LogHighlightTransformation && other.matcher == matcher &&
            other.selection == selection && other.activeSelection == activeSelection

    override fun hashCode(): Int =
        31 * (31 * (matcher?.hashCode() ?: 0) + selection.hashCode()) + activeSelection.hashCode()
}

@Composable
fun EmptyViewer(message: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
