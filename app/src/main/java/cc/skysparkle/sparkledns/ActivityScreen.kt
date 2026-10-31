package cc.skysparkle.sparkledns

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class ActivityFilter(@StringRes val label: Int) {
    ALL(R.string.mon_all),
    BLOCKED(R.string.status_blocked),
    CACHED(R.string.status_cached),
    ERRORS(R.string.mon_errors),
}

private enum class Period(val hours: Int, @StringRes val label: Int) {
    DAY(24, R.string.period_24h),
    WEEK(24 * 7, R.string.period_7d),
}

private val TimeFormat = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

// Colors of the company slices, then the rest
private val SliceColors = listOf(Palette.Accent, Color(0xFFE6E8EE), Color(0xFF7C69E8), Color(0xFFC9A36A))
private val OtherColor = Palette.Track

private class Slice(val label: String, val share: Float, val color: Color)

@Composable
fun ActivityScreen(bottomPadding: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val custom by Blocklist.custom.collectAsStateWithLifecycle()
    val customSet = remember(custom) { custom.toHashSet() }

    var period by rememberSaveable { mutableStateOf(Period.DAY) }
    var entries by remember { mutableStateOf(QueryLog.snapshot()) }
    var summary by remember { mutableStateOf(StatsStore.summary(period.hours)) }
    var filter by rememberSaveable { mutableStateOf(ActivityFilter.ALL) }
    var search by rememberSaveable { mutableStateOf("") }
    var filterMenu by remember { mutableStateOf(false) }

    OnQueryLogChange {
        entries = QueryLog.snapshot()
        summary = StatsStore.summary(period.hours)
    }
    val statsVersion by StatsStore.version.collectAsStateWithLifecycle()
    LaunchedEffect(period, statsVersion) { summary = StatsStore.summary(period.hours) }

    val visible = remember(entries, filter, search) {
        val term = search.trim()
        entries.filter { e ->
            val matchesFilter = when (filter) {
                ActivityFilter.ALL -> true
                ActivityFilter.BLOCKED -> e.status == QueryStatus.BLOCKED
                ActivityFilter.CACHED -> e.status == QueryStatus.CACHED
                ActivityFilter.ERRORS -> e.status == QueryStatus.FAILED
            }
            matchesFilter && (term.isEmpty() || e.domain.contains(term, ignoreCase = true))
        }
    }

    fun toggleBlock(domain: String, unblock: Boolean) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                tryEdit {
                    if (unblock) {
                        Blocklist.removeCustom(context, domain)
                        true
                    } else {
                        Blocklist.addCustom(context, domain)
                    }
                }
            }
            val ok = result.getOrElse {
                showEditError(context, it)
                return@launch
            }
            val message = when {
                !ok -> context.getString(R.string.bl_invalid)
                unblock -> context.getString(R.string.mon_unblocked, domain)
                else -> context.getString(R.string.mon_blocked_added, domain)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    fun copy(domain: String) {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("domain", domain))
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = bottomPadding),
    ) {
        item(key = "header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 44.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ScreenTitle(stringResource(R.string.tab_activity), Modifier.weight(1f))
                PeriodChip(stringResource(period.label)) {
                    period = if (period == Period.DAY) Period.WEEK else Period.DAY
                }
            }
        }

        item(key = "stats") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, start = 4.dp, end = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatBlock(formatCompact(summary.total), stringResource(R.string.stat_requests), Modifier.weight(1f))
                StatBlock(formatCompact(summary.blocked), stringResource(R.string.mon_blocked), Modifier.weight(1f))
                StatBlock(stringResource(R.string.mon_ms, summary.avgLatencyMs), stringResource(R.string.stat_avg), Modifier.weight(1f))
                val cache = if (summary.total == 0L) 0 else summary.cached * 100 / summary.total
                StatBlock("$cache%", stringResource(R.string.mon_cached), Modifier.weight(1f))
            }
        }

        item(key = "companies") {
            CompanyCard(summary, Modifier.padding(top = 20.dp))
        }

        item(key = "recent") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.recent_title),
                    color = Palette.Fg,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        QueryLog.clear()
                        entries = emptyList()
                    },
                    enabled = entries.isNotEmpty(),
                ) {
                    Icon(
                        painterResource(R.drawable.ic_delete_sweep),
                        contentDescription = stringResource(R.string.mon_clear),
                        tint = Palette.Fg2,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }

        item(key = "search") {
            SearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = stringResource(R.string.mon_search),
                modifier = Modifier.padding(top = 6.dp, bottom = 4.dp),
                trailing = {
                    Box {
                        IconButton(onClick = { filterMenu = true }) {
                            Icon(
                                painterResource(R.drawable.ic_filter),
                                contentDescription = stringResource(R.string.filter),
                                tint = if (filter == ActivityFilter.ALL) Palette.Fg2 else Palette.Accent,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            ActivityFilter.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(option.label),
                                            color = if (option == filter) Palette.Accent else Palette.Fg,
                                            fontWeight = if (option == filter) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    },
                                    onClick = {
                                        filter = option
                                        filterMenu = false
                                    },
                                )
                            }
                        }
                    }
                },
            )
        }

        if (visible.isEmpty()) {
            item(key = "empty") {
                EmptyState(
                    R.drawable.ic_tab_monitor,
                    stringResource(if (entries.isEmpty()) R.string.mon_empty else R.string.mon_no_match),
                )
            }
        }

        itemsIndexed(visible, key = { _, entry -> entry.id }, contentType = { _, _ -> "query" }) { index, entry ->
            val blockedByMe = entry.domain in customSet
            QueryRow(
                modifier = Modifier.animateItem(),
                entry = entry,
                first = index == 0,
                blockedByMe = blockedByMe,
                onBlock = { toggleBlock(entry.domain, unblock = false) },
                onUnblock = { toggleBlock(entry.domain, unblock = true) },
                onCopy = { copy(entry.domain) },
            )
        }
    }
}

@Composable
private fun PeriodChip(text: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .border(1.dp, Palette.Track, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = Palette.Fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Icon(painterResource(R.drawable.ic_expand), contentDescription = null, tint = Palette.Fg2, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun CompanyCard(summary: StatsSummary, modifier: Modifier = Modifier) {
    val other = stringResource(R.string.companies_other)
    val slices = remember(summary, other) { companySlices(summary, other) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .appCard()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(stringResource(R.string.companies_title), color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.companies_desc), color = Palette.Fg2, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.size(10.dp))
        if (summary.total == 0L) {
            Text(
                stringResource(R.string.no_data),
                color = Palette.Fg3,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Donut(slices, Modifier.size(104.dp))
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    slices.forEach { slice ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(slice.color)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                slice.label,
                                color = Palette.Fg,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                String.format(Locale.getDefault(), "%.1f%%", slice.share * 100f),
                                color = Palette.Fg,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

// The four companies with most requests, then everything else
private fun companySlices(summary: StatsSummary, otherLabel: String): List<Slice> {
    if (summary.total == 0L) return emptyList()
    val top = Company.entries
        .map { it to summary.companies[it.ordinal] }
        .filter { it.second > 0 }
        .sortedByDescending { it.second }
        .take(SliceColors.size)
    val slices = top.mapIndexed { index, (company, count) ->
        Slice(company.label, count.toFloat() / summary.total, SliceColors[index])
    }
    val rest = (1f - slices.sumOf { it.share.toDouble() }.toFloat()).coerceAtLeast(0f)
    return slices + Slice(otherLabel, rest, OtherColor)
}

@Composable
private fun Donut(slices: List<Slice>, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val stroke = 14.dp.toPx()
        val diameter = size.minDimension - stroke
        val topLeft = Offset((size.width - diameter) / 2f, (size.height - diameter) / 2f)
        val arcSize = Size(diameter, diameter)
        drawArc(OtherColor, 0f, 360f, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke))
        var start = -90f
        slices.forEach { slice ->
            val sweep = slice.share * 360f
            if (sweep > 0f) {
                drawArc(slice.color, start, sweep, useCenter = false, topLeft = topLeft, size = arcSize, style = Stroke(width = stroke))
            }
            start += sweep
        }
    }
}

@Composable
private fun QueryRow(
    modifier: Modifier,
    entry: QueryEntry,
    first: Boolean,
    blockedByMe: Boolean,
    onBlock: () -> Unit,
    onUnblock: () -> Unit,
    onCopy: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val (avatarBackground, avatarColor) = when (entry.status) {
        QueryStatus.BLOCKED -> Palette.Blocked.copy(alpha = 0.14f) to Palette.Blocked
        QueryStatus.CACHED -> CachedColor.copy(alpha = 0.14f) to CachedColor
        QueryStatus.FAILED -> WarningColor.copy(alpha = 0.14f) to WarningColor
        QueryStatus.OK -> Color(0xFF2A2C33) to Palette.Fg
    }
    val detail = when (entry.status) {
        QueryStatus.OK -> stringResource(R.string.mon_ms, entry.latencyMs)
        QueryStatus.CACHED -> stringResource(R.string.status_cached)
        QueryStatus.BLOCKED -> stringResource(if (blockedByMe) R.string.bl_my else R.string.tab_blocklist)
        QueryStatus.FAILED -> stringResource(R.string.status_failed)
    }

    Box(modifier.fillMaxWidth()) {
        Column {
            if (!first) HorizontalDivider(color = Palette.Line)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { menu = true }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(avatarBackground),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(initialOf(entry.domain), color = avatarColor, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        entry.domain,
                        color = Palette.Fg,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${DnsMessage.typeName(entry.type)} · $detail",
                        color = Palette.Fg3,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    if (entry.status == QueryStatus.BLOCKED) {
                        Text(stringResource(R.string.status_blocked), color = Palette.Blocked, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    } else if (entry.status == QueryStatus.FAILED) {
                        Text(stringResource(R.string.status_failed), color = WarningColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(TimeFormat.format(Date(entry.time)), color = Palette.Fg3, fontSize = 12.sp)
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (blockedByMe) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mon_unblock)) },
                    leadingIcon = { MenuIcon(R.drawable.ic_check) },
                    onClick = {
                        menu = false
                        onUnblock()
                    },
                )
            } else if (entry.status != QueryStatus.BLOCKED) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.mon_block)) },
                    leadingIcon = { MenuIcon(R.drawable.ic_tab_block) },
                    onClick = {
                        menu = false
                        onBlock()
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.copy)) },
                leadingIcon = { MenuIcon(R.drawable.ic_copy) },
                onClick = {
                    menu = false
                    onCopy()
                },
            )
        }
    }
}

@Composable
private fun MenuIcon(@DrawableRes icon: Int) {
    Icon(painterResource(icon), contentDescription = null, tint = Palette.Fg2, modifier = Modifier.size(20.dp))
}

// First letter of the main part of a domain: "a" for app-measurement.com, "g" for www.google.com
private fun initialOf(domain: String): String {
    val parts = domain.split('.').filter { it.isNotEmpty() }
    val main = if (parts.size >= 2) parts[parts.size - 2] else parts.firstOrNull().orEmpty()
    return main.firstOrNull { it.isLetterOrDigit() }?.toString() ?: "·"
}
