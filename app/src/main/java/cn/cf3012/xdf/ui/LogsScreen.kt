package cn.cf3012.xdf.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.cf3012.xdf.LogParser
import cn.cf3012.xdf.R
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.SmallTitle

private val LEVELS = listOf('V', 'D', 'I', 'W', 'E')

private val LEVEL_COLOR = mapOf(
    'V' to Color(0xFF8A8A8E),
    'D' to Color(0xFF3482FF),
    'I' to Color(0xFF34C759),
    'W' to Color(0xFFFFC42E),
    'E' to Color(0xFFFF6482),
)

/** 日志页：tag/级别 chip 过滤 + 2s 轮询刷新 */
@Composable
fun LogsScreen(tick: Int, context: Context) {
    var selLevel by remember { mutableStateOf('I') } // I 及以上
    var selTag by remember { mutableStateOf<String?>(null) }
    var lines by remember { mutableStateOf<List<LogParser.Line>>(emptyList()) }

    LaunchedEffect(tick) {
        while (true) {
            lines = LogStore.snapshot()
            delay(2000)
        }
    }

    val tags = remember(lines) {
        listOf(null as String?) + lines.map { it.tag }.distinct().sorted()
    }
    val filtered = remember(lines, selLevel, selTag) {
        lines.filter { line ->
            (selLevel == ' ' || line.level >= selLevel) &&
                (selTag == null || line.tag == selTag)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            SmallTitle(text = "标签")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tags.size) { i ->
                    val tag = tags[i]
                    FilterChip(
                        text = tag ?: stringResource(R.string.chip_all),
                        selected = tag == selTag,
                        onClick = { selTag = tag },
                    )
                }
            }
        }
        item {
            SmallTitle(text = "级别")
            LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(LEVELS.size + 1) { i ->
                    val ch = if (i == 0) ' ' else LEVELS[i - 1]
                    FilterChip(
                        text = if (i == 0) stringResource(R.string.chip_all) else ch.toString(),
                        selected = selLevel == ch,
                        onClick = { selLevel = ch },
                    )
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.log_empty),
                        fontSize = 13.sp,
                        color = Color(0xFF8A8A8E),
                    )
                }
            }
        } else {
            items(filtered) { line ->
                LogLineRow(line)
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .background(
                color = if (selected) Color(0xFF3482FF) else Color(0xFFF2F2F7),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = if (selected) Color.White else Color(0xFF3C3C43),
        )
    }
}

@Composable
private fun LogLineRow(line: LogParser.Line) {
    val color = LEVEL_COLOR[line.level] ?: Color(0xFF8A8A8E)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 3.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = line.level.toString(),
            color = color,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 6.dp),
        )
        androidx.compose.foundation.layout.Column {
            Text(
                text = "${line.tag} · ${line.proc}",
                fontSize = 11.sp,
                color = Color(0xFF8A8A8E),
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = line.msg,
                fontSize = 12.sp,
                color = Color(0xFF1C1C1E),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}