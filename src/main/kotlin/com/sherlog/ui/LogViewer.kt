package com.sherlog.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
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

private val highlightStyle = SpanStyle(background = Color(0x66FFEB3B), color = Color.White)

@Composable
fun LogViewer(
    index: LogIndex,
    provider: LineTextProvider,
    filteredLines: IntArray,
    searchQuery: String,
    searchIsRegex: Boolean,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val matcher = remember(searchQuery, searchIsRegex) {
        if (searchQuery.isBlank()) null else FilterEngine.SearchMatcher(searchQuery, searchIsRegex)
    }

    Box(modifier) {
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(end = 12.dp)) {
            items(count = filteredLines.size, key = { filteredLines[it] }) { pos ->
                val lineIndex = filteredLines[pos]
                LogRow(index, provider, lineIndex, matcher)
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@Composable
private fun LogRow(
    index: LogIndex,
    provider: LineTextProvider,
    lineIndex: Int,
    matcher: FilterEngine.SearchMatcher?,
) {
    val text by produceState("…", lineIndex, provider) {
        value = withContext(Dispatchers.IO) {
            runCatching { provider.line(lineIndex) }.getOrElse { "<read error: ${it.message}>" }
        }
    }
    val level = index.level(lineIndex)
    val color = levelColors.getValue(level)
    val rowBackground = when (level) {
        LogLevel.ERROR, LogLevel.FATAL -> Color(0x14FF0000)
        LogLevel.WARN -> Color(0x14FFA000)
        else -> Color.Transparent
    }
    Text(
        text = highlighted(text, matcher),
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        modifier = Modifier.fillMaxWidth().background(rowBackground).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

private fun highlighted(text: String, matcher: FilterEngine.SearchMatcher?): AnnotatedString {
    val regex = matcher?.regex ?: return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        for (m in regex.findAll(text)) {
            if (m.range.isEmpty()) continue
            addStyle(highlightStyle, m.range.first, m.range.last + 1)
        }
    }
}

@Composable
fun EmptyViewer(message: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
