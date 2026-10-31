package cc.skysparkle.sparkledns

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val HOUR_MS = 3_600_000L

// One bar of the chart: an hour, or a day in the 7-day view
private class Bar(val total: Long, val blocked: Long, val label: String)

@Composable
fun HomeScreen(
    bottomPadding: Dp,
    onOpenBlocklist: () -> Unit,
    onOpenServers: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val active by DohVpnService.active.collectAsStateWithLifecycle()
    val running = active != null
    val lists by Blocklist.lists.collectAsStateWithLifecycle()
    val total by Blocklist.total.collectAsStateWithLifecycle()
    val blocking by Blocklist.enabled.collectAsStateWithLifecycle()
    val listsReady by Blocklist.ready.collectAsStateWithLifecycle()
    val download by Blocklist.busy.collectAsStateWithLifecycle()
    val downloadError by Blocklist.error.collectAsStateWithLifecycle()

    var period by rememberSaveable { mutableStateOf(StatsPeriod.DAY) }
    var summary by remember { mutableStateOf(StatsStore.summary(period.hours)) }
    var bars by remember { mutableStateOf(chartBars(period)) }
    fun refreshStats() {
        summary = StatsStore.summary(period.hours)
        bars = chartBars(period)
    }
    OnQueryLogChange { refreshStats() }
    val statsVersion by StatsStore.version.collectAsStateWithLifecycle()
    LaunchedEffect(statsVersion, period) { refreshStats() }

    // The server is picked in Settings; this screen is composed anew each time it is shown
    val selected = remember { prefs.resolve() }
    val canConnect = selected.id != Providers.CUSTOM_ID || selected.url.toHttpUrlOrNull()?.isHttps == true
    val shown = active ?: selected
    val usesHttp3 = shown.http3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    val crashReport = remember { CrashLog.read(context) }
    var crashVisible by remember { mutableStateOf(crashReport != null) }
    // Strict Private DNS makes Android ignore the VPN's DNS server; followed while Home is visible
    val privateDnsHost by remember { privateDnsHosts(context) }.collectAsStateWithLifecycle(initialValue = null)

    // Set by a tap until the VPN state changes or the attempt gives up, so a double tap neither
    // starts two connections nor switches the VPN back on right after turning it off
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(active) { busy = false }
    LaunchedEffect(busy) {
        if (busy) {
            delay(BUSY_TIMEOUT_MS)
            busy = false
        }
    }

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (it.resultCode == Activity.RESULT_OK) sendAction(context, DohVpnService.ACTION_START)
        else busy = false
    }

    fun connectVpn() {
        val permission = VpnService.prepare(context)
        if (permission != null) vpnPermission.launch(permission)
        else sendAction(context, DohVpnService.ACTION_START)
    }

    // The status notification is optional: connect whatever the user answers
    val notificationPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { connectVpn() }

    fun toggle() {
        if (busy) return
        busy = true
        haptics.performHapticFeedback(if (running) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
        if (running) {
            sendAction(context, DohVpnService.ACTION_STOP)
            return
        }
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else connectVpn()
    }

    // What the blocklist card offers: nothing, switching blocking back on, or a first list
    val proList = lists.firstOrNull { it.url == HAGEZI_PRO_URL }
    val offer = when {
        !listsReady -> BlocklistOffer.NONE
        !blocking -> BlocklistOffer.SWITCH_ON
        total == 0 && lists.none { it.enabled } -> BlocklistOffer.FIRST_LIST
        else -> BlocklistOffer.NONE
    }
    fun acceptOffer() {
        when (offer) {
            BlocklistOffer.SWITCH_ON -> Blocklist.setEnabled(context, true)
            BlocklistOffer.FIRST_LIST ->
                if (proList != null) scope.editBlocklist(context) { Blocklist.setListEnabled(context, HAGEZI_PRO_URL, true) }
                else Blocklist.download(context, HAGEZI_PRO_URL)
            BlocklistOffer.NONE -> Unit
        }
    }

    val glow by animateFloatAsState(if (running) 1f else 0f, tween(600), label = "glow")
    val wide = windowWidth() >= WideLayoutWidth
    val side = sidePadding(windowWidth(), if (wide) MaxWideContentWidth else MaxContentWidth)
    // A tall screen gets a taller chart instead of empty space under the content
    val chartHeight = if (wide) {
        (windowHeight() - 420.dp).coerceIn(160.dp, 360.dp)
    } else {
        (windowHeight() - 700.dp).coerceIn(150.dp, 320.dp)
    }

    val status: @Composable () -> Unit = {
        StatusCard(
            running = running,
            providerName = shown.displayName(context),
            http3 = usesHttp3,
            enabled = !busy && (running || canConnect),
            onToggle = ::toggle,
            onOpenServers = onOpenServers,
        )
        PrivateDnsWarning(privateDnsHost, prefs)
    }
    val stats: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatBlock(formatCompact(summary.total), stringResource(R.string.stat_requests), Modifier.weight(1f))
            StatBlock(
                formatCompact(summary.blocked),
                stringResource(R.string.mon_blocked),
                Modifier.weight(1f),
                valueColor = Palette.Accent,
            )
            StatBlock(stringResource(R.string.mon_ms, summary.avgLatencyMs), stringResource(R.string.stat_avg), Modifier.weight(1f))
        }
    }
    val chart: @Composable () -> Unit = {
        ChartSection(bars, period, chartHeight, onPeriod = { period = it })
    }
    val blocklist: @Composable () -> Unit = {
        BlocklistCard(
            info = stringResource(R.string.home_lists_info, lists.count { it.enabled }, formatCount(total.toLong())),
            offer = offer,
            downloading = download == HAGEZI_PRO_URL,
            error = if (offer == BlocklistOffer.FIRST_LIST) downloadError else null,
            canAccept = download == null,
            onAccept = ::acceptOffer,
            onClick = onOpenBlocklist,
        )
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .drawBehind {
                // Soft light behind the logo while protected
                if (glow > 0f) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(Palette.Accent.copy(alpha = 0.22f * glow), Color.Transparent),
                            center = Offset(size.width / 2f, 0f),
                            radius = size.width * 0.65f,
                        ),
                        radius = size.width * 0.65f,
                        center = Offset(size.width / 2f, 0f),
                    )
                }
            }
            .padding(start = side, end = side, top = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Image(
            painterResource(R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier
                .size(44.dp)
                .align(Alignment.CenterHorizontally),
        )

        AnimatedVisibility(visible = crashVisible, enter = Motion.expandIn, exit = Motion.collapseOut) {
            val report = crashReport.orEmpty()
            CrashCard(
                report = report,
                onCopy = {
                    context.getSystemService(ClipboardManager::class.java)
                        .setPrimaryClip(ClipData.newPlainText("SparkleDNS crash", report))
                },
                onDismiss = { CrashLog.clear(context); crashVisible = false },
            )
        }

        if (wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    status()
                    blocklist()
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    stats()
                    chart()
                }
            }
        } else {
            status()
            stats()
            chart()
            blocklist()
        }
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    providerName: String,
    http3: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onOpenServers: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .appCard()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        AnimatedContent(
            targetState = running,
            transitionSpec = { fadeIn(Motion.enterFade) togetherWith fadeOut(Motion.exitFade) },
            label = "status",
        ) { on ->
            Text(
                stringResource(if (on) R.string.home_protected else R.string.home_unprotected),
                color = Palette.Fg,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp,
                textAlign = TextAlign.Center,
            )
        }
        // Tapping the server opens the server list in Settings
        val dot by animateColorAsState(if (running) Palette.Accent else Palette.Track, Motion.color, label = "dot")
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = stringResource(R.string.change_server), onClick = onOpenServers)
                .heightIn(min = 40.dp)
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(dot)
            )
            Spacer(Modifier.width(8.dp))
            val via = stringResource(R.string.home_via, providerName) + if (http3) " · HTTP/3" else ""
            Text(
                if (running) via else stringResource(R.string.home_off_desc),
                color = Palette.Fg2,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(Modifier.width(2.dp))
            Chevron(Modifier.size(16.dp))
        }
        Spacer(Modifier.height(8.dp))
        PowerToggle(on = running, enabled = enabled, onToggle = onToggle)
    }
}

// The big ON / OFF switch; the white knob slides across
@Composable
private fun PowerToggle(on: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val track by animateColorAsState(if (on) Palette.Accent else Palette.Track, Motion.color, label = "track")
    val knob by animateDpAsState(if (on) 70.dp else 0.dp, tween(320, easing = Motion.Emphasized), label = "knob")
    val description = stringResource(if (on) R.string.disconnect else R.string.connect)
    Box(
        modifier = Modifier
            .size(width = 120.dp, height = 50.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f }
            .clip(RoundedCornerShape(25.dp))
            .background(track)
            .toggleable(value = on, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .semantics { contentDescription = description }
            .padding(5.dp),
    ) {
        Text(
            stringResource(if (on) R.string.toggle_on else R.string.toggle_off),
            color = if (on) Palette.AccentInk else Palette.Fg2,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.6.sp,
            modifier = Modifier
                .align(if (on) Alignment.CenterStart else Alignment.CenterEnd)
                .padding(horizontal = 12.dp),
        )
        Box(
            modifier = Modifier
                .offset(x = knob)
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(14.dp)) {
                val stroke = 1.6.dp.toPx()
                listOf(0.29f, 0.5f, 0.71f).forEach { fraction ->
                    drawLine(
                        color = Palette.Fg3,
                        start = Offset(size.width * fraction, size.height * 0.21f),
                        end = Offset(size.width * fraction, size.height * 0.79f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
    }
}

@Composable
private fun PrivateDnsWarning(privateDnsHost: String?, prefs: Prefs) {
    val context = LocalContext.current
    val lastHost = remember { arrayOf("") }
    privateDnsHost?.let { lastHost[0] = it }
    var dismissedHost by remember { mutableStateOf(prefs.dismissedPrivateDns) }
    AnimatedVisibility(
        visible = privateDnsHost != null && privateDnsHost != dismissedHost,
        enter = Motion.expandIn,
        exit = Motion.collapseOut,
    ) {
        HintCard(
            text = stringResource(R.string.hint, lastHost[0]),
            icon = R.drawable.ic_alert,
            tint = WarningColor,
            action = {
                TextButton(onClick = { openNetworkSettings(context) }) { Text(stringResource(R.string.open_settings)) }
            },
            onDismiss = {
                dismissedHost = lastHost[0]
                prefs.dismissedPrivateDns = lastHost[0]
            },
        )
    }
}

@Composable
fun StatBlock(value: String, label: String, modifier: Modifier = Modifier, valueColor: Color = Palette.Fg) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value, color = valueColor, fontSize = 21.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
        Text(label, color = Palette.Fg2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ChartSection(bars: List<Bar>, period: StatsPeriod, height: Dp, onPeriod: (StatsPeriod) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PeriodChip(period, onPeriod)
            Spacer(Modifier.weight(1f))
            LegendItem(stringResource(R.string.stat_requests), Palette.Accent)
            Spacer(Modifier.width(14.dp))
            LegendItem(stringResource(R.string.mon_blocked), Palette.Blocked)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(height),
            contentAlignment = Alignment.Center,
        ) {
            if (bars.all { it.total == 0L }) {
                Text(
                    stringResource(R.string.chart_empty),
                    color = Palette.Fg3,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            } else {
                TrafficChart(bars, Modifier.fillMaxWidth().height(height))
            }
        }
        // Hours: first hour on the left, now on the right. Days: a label under every bar.
        Row(Modifier.fillMaxWidth()) {
            if (period == StatsPeriod.DAY) {
                Text(bars.firstOrNull()?.label.orEmpty(), color = Palette.Fg3, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.chart_now), color = Palette.Fg3, fontSize = 11.sp)
            } else {
                bars.forEachIndexed { index, bar ->
                    Text(
                        bar.label,
                        color = if (index == bars.lastIndex) Palette.Fg else Palette.Fg3,
                        fontSize = 11.sp,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.Fg2, fontSize = 11.sp)
    }
}

// One bar per hour or day: requests in the accent color with the blocked part at the bottom.
// Empty slots show a short stub, so a mostly empty day reads as empty instead of broken.
@Composable
private fun TrafficChart(bars: List<Bar>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.Fg3, fontSize = 10.sp)
    Canvas(modifier) {
        val n = bars.size
        if (n == 0) return@Canvas
        val maxValue = niceCeiling(bars.maxOf { it.total }.coerceAtLeast(1L))
        val top = 16.dp.toPx()
        val base = size.height - 2.dp.toPx()
        val span = base - top
        val slot = size.width / n
        val barWidth = (slot * if (n > 10) 0.62f else 0.5f).coerceAtLeast(2.dp.toPx())
        val radius = CornerRadius((barWidth / 2).coerceAtMost(4.dp.toPx()))
        fun height(value: Long) = value.toFloat() / maxValue * span

        listOf(maxValue / 2, maxValue).filter { it > 0 }.forEach { value ->
            val gridY = base - height(value)
            drawLine(
                color = Palette.Line,
                start = Offset(0f, gridY),
                end = Offset(size.width, gridY),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 4.dp.toPx())),
            )
            drawText(
                textMeasurer = measurer,
                text = formatCompact(value),
                topLeft = Offset(0f, gridY - 14.dp.toPx()),
                style = labelStyle,
            )
        }

        bars.forEachIndexed { index, bar ->
            val x = index * slot + (slot - barWidth) / 2
            if (bar.total == 0L) {
                val stub = 2.dp.toPx()
                drawRoundRect(Palette.Line, Offset(x, base - stub), Size(barWidth, stub), CornerRadius(stub / 2))
                return@forEachIndexed
            }
            val current = index == n - 1
            val h = height(bar.total).coerceAtLeast(4.dp.toPx())
            drawRoundRect(
                if (current) Palette.Accent else Palette.Accent.copy(alpha = 0.6f),
                Offset(x, base - h),
                Size(barWidth, h),
                radius,
            )
            if (bar.blocked > 0) {
                val hb = height(bar.blocked).coerceIn(2.dp.toPx(), h)
                if (hb >= h - radius.x) {
                    drawRoundRect(Palette.Blocked, Offset(x, base - hb), Size(barWidth, hb), radius)
                } else {
                    drawRect(Palette.Blocked, Offset(x, base - hb), Size(barWidth, hb))
                }
            }
        }
    }
}

// 1, 2 or 5 times a power of ten, at least [value]: keeps the grid labels round
private fun niceCeiling(value: Long): Long {
    var magnitude = 1L
    while (magnitude * 10 <= value) magnitude *= 10
    for (factor in longArrayOf(1, 2, 5)) {
        if (factor * magnitude >= value) return factor * magnitude
    }
    return 10 * magnitude
}

// 24 hourly bars, or 7 bars for the calendar days of the last week (today last)
private fun chartBars(period: StatsPeriod, now: Long = System.currentTimeMillis()): List<Bar> {
    val hours = StatsStore.hourly(period.hours, now)
    val firstHour = now / HOUR_MS - (hours.size - 1)
    if (period == StatsPeriod.DAY) {
        val format = SimpleDateFormat("HH:00", Locale.getDefault())
        return hours.mapIndexed { index, point ->
            Bar(point.total, point.blocked, format.format(Date((firstHour + index) * HOUR_MS)))
        }
    }
    val calendar = Calendar.getInstance()
    fun dayKey(time: Long): Int {
        calendar.timeInMillis = time
        return calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)
    }
    val dayStarts = (6 downTo 0).map { back ->
        calendar.timeInMillis = now
        calendar.add(Calendar.DAY_OF_YEAR, -back)
        calendar.timeInMillis
    }
    val keys = dayStarts.map { dayKey(it) }
    val totals = LongArray(7)
    val blocked = LongArray(7)
    hours.forEachIndexed { index, point ->
        val day = keys.indexOf(dayKey((firstHour + index) * HOUR_MS))
        if (day >= 0) {
            totals[day] += point.total
            blocked[day] += point.blocked
        }
    }
    val format = SimpleDateFormat("EEE", Locale.getDefault())
    return dayStarts.mapIndexed { index, time -> Bar(totals[index], blocked[index], format.format(Date(time))) }
}

private enum class BlocklistOffer { NONE, SWITCH_ON, FIRST_LIST }

@Composable
private fun BlocklistCard(
    info: String,
    offer: BlocklistOffer,
    downloading: Boolean,
    error: String?,
    canAccept: Boolean,
    onAccept: () -> Unit,
    onClick: () -> Unit,
) {
    val subtitle = when {
        error != null -> stringResource(R.string.bl_download_failed, error)
        offer == BlocklistOffer.SWITCH_ON -> stringResource(R.string.blocklist_cta_master)
        offer == BlocklistOffer.FIRST_LIST -> stringResource(R.string.blocklist_cta_lists)
        else -> info
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .appCard()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val idle = offer != BlocklistOffer.NONE
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(if (idle) Palette.Track else Palette.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_privacy),
                contentDescription = null,
                tint = if (idle) Palette.Fg2 else Palette.AccentInk,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.blocklist_title), color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                color = if (error != null) Palette.Blocked else Palette.Fg2,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        if (offer == BlocklistOffer.NONE) {
            Chevron()
        } else {
            Button(
                onClick = onAccept,
                enabled = canAccept && !downloading,
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
                modifier = Modifier.heightIn(min = 40.dp),
            ) {
                if (downloading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Palette.Fg2)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.downloading), fontSize = 13.sp, maxLines = 1)
                } else {
                    Text(stringResource(R.string.turn_on), fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1)
                }
            }
        }
    }
}
