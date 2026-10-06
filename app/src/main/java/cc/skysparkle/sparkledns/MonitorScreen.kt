package cc.skysparkle.sparkledns

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class MonitorFilter { ALL, BLOCKED, ERRORS }

private val TimeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

@Composable
fun MonitorScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val custom by Blocklist.custom.collectAsState()

    var entries by remember { mutableStateOf(QueryLog.snapshot()) }
    var stats by remember { mutableStateOf(QueryLog.stats()) }
    var filter by rememberSaveable { mutableStateOf(MonitorFilter.ALL) }
    var search by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(Unit) {
        while (true) {
            entries = QueryLog.snapshot()
            stats = QueryLog.stats()
            delay(1000)
        }
    }

    val visible = remember(entries, filter, search) {
        val term = search.trim()
        entries.filter { e ->
            val matchesFilter = when (filter) {
                MonitorFilter.ALL -> true
                MonitorFilter.BLOCKED -> e.status == QueryStatus.BLOCKED
                MonitorFilter.ERRORS -> e.status == QueryStatus.FAILED
            }
            matchesFilter && (term.isEmpty() || e.domain.contains(term, ignoreCase = true))
        }
    }

    fun toggleBlock(domain: String, unblock: Boolean) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                if (unblock) {
                    Blocklist.removeCustom(context, domain)
                    true
                } else {
                    Blocklist.addCustom(context, domain)
                }
            }
            val message = when {
                !ok -> context.getString(R.string.bl_invalid)
                unblock -> context.getString(R.string.mon_unblocked, domain)
                else -> context.getString(R.string.mon_blocked_added, domain)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatColumn(formatCount(stats.total), stringResource(R.string.mon_queries), Modifier.weight(1f))
                    StatDivider()
                    StatColumn(cachePercent(stats), stringResource(R.string.mon_cached), Modifier.weight(1f))
                    StatDivider()
                    StatColumn(formatCount(stats.blocked), stringResource(R.string.mon_blocked), Modifier.weight(1f))
                    StatDivider()
                    StatColumn(
                        stringResource(R.string.mon_ms, stats.avgLatencyMs),
                        stringResource(R.string.mon_latency),
                        Modifier.weight(1f),
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Chips scroll sideways on narrow screens instead of squeezing the clear button
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilterChip(
                        selected = filter == MonitorFilter.ALL,
                        onClick = { filter = MonitorFilter.ALL },
                        label = { Text(stringResource(R.string.mon_all)) },
                    )
                    FilterChip(
                        selected = filter == MonitorFilter.BLOCKED,
                        onClick = { filter = MonitorFilter.BLOCKED },
                        label = { Text(stringResource(R.string.status_blocked)) },
                        leadingIcon = { ChipIcon(R.drawable.ic_tab_block) },
                    )
                    FilterChip(
                        selected = filter == MonitorFilter.ERRORS,
                        onClick = { filter = MonitorFilter.ERRORS },
                        label = { Text(stringResource(R.string.mon_errors)) },
                        leadingIcon = { ChipIcon(R.drawable.ic_alert) },
                    )
                }
                IconButton(
                    onClick = {
                        QueryLog.clear()
                        entries = emptyList()
                        stats = QueryLog.stats()
                    },
                    enabled = entries.isNotEmpty() || stats.total > 0,
                ) {
                    Icon(painterResource(R.drawable.ic_clear), stringResource(R.string.mon_clear), Modifier.size(22.dp))
                }
            }
        }

        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(stringResource(R.string.mon_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                val text = stringResource(if (entries.isEmpty()) R.string.mon_empty else R.string.mon_no_match)
                Box(Modifier.animateItem()) { EmptyState(R.drawable.ic_tab_monitor, text) }
            }
        }

        itemsIndexed(visible, key = { _, e -> e.id }) { index, entry ->
            val inCustom = entry.domain in custom
            QueryRow(
                modifier = Modifier.animateItem(),
                entry = entry,
                first = index == 0,
                last = index == visible.lastIndex,
                showBlock = !inCustom && entry.status != QueryStatus.BLOCKED,
                showUnblock = inCustom,
                onBlock = { toggleBlock(entry.domain, unblock = false) },
                onUnblock = { toggleBlock(entry.domain, unblock = true) },
            )
        }
    }
}

@Composable
private fun ChipIcon(icon: Int) {
    Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
}

@Composable
fun EmptyState(icon: Int, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.onSurfaceVariant, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun QueryRow(
    modifier: Modifier,
    entry: QueryEntry,
    first: Boolean,
    last: Boolean,
    showBlock: Boolean,
    showUnblock: Boolean,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
) {
    val (icon, color: Color) = when (entry.status) {
        QueryStatus.OK -> R.drawable.ic_check to MaterialTheme.colorScheme.primary
        QueryStatus.CACHED -> R.drawable.ic_check to CachedColor
        QueryStatus.BLOCKED -> R.drawable.ic_tab_block to MaterialTheme.colorScheme.error
        QueryStatus.FAILED -> R.drawable.ic_alert to WarningColor
    }
    val label = when (entry.status) {
        QueryStatus.OK -> stringResource(R.string.mon_ms, entry.latencyMs)
        QueryStatus.CACHED -> stringResource(R.string.status_cached)
        QueryStatus.BLOCKED -> stringResource(R.string.status_blocked)
        QueryStatus.FAILED -> stringResource(R.string.status_failed)
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(groupedBackground, groupedShape(first, last))
    ) {
        if (!first) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 60.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
        }
        Row(
            modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(icon, color, size = 36.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.domain, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${TimeFormat.format(Date(entry.time))} · ${DnsMessage.typeName(entry.type)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Pill(label, color)
            when {
                showBlock -> IconButton(onClick = onBlock) {
                    Icon(painterResource(R.drawable.ic_tab_block), stringResource(R.string.mon_block), Modifier.size(20.dp))
                }
                showUnblock -> IconButton(onClick = onUnblock) {
                    Icon(painterResource(R.drawable.ic_check), stringResource(R.string.mon_unblock), Modifier.size(20.dp))
                }
                else -> Spacer(Modifier.width(48.dp))
            }
        }
    }
}
