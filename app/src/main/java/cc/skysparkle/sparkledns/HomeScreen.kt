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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
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
import java.util.Date
import java.util.Locale

private const val CHART_HOURS = 24

@Composable
fun HomeScreen(bottomPadding: Dp, onOpenBlocklist: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val active by DohVpnService.active.collectAsStateWithLifecycle()
    val running = active != null
    val lists by Blocklist.lists.collectAsStateWithLifecycle()
    val total by Blocklist.total.collectAsStateWithLifecycle()
    val blocking by Blocklist.enabled.collectAsStateWithLifecycle()

    var summary by remember { mutableStateOf(StatsStore.summary(CHART_HOURS)) }
    var points by remember { mutableStateOf(StatsStore.hourly(CHART_HOURS)) }
    fun refreshStats() {
        summary = StatsStore.summary(CHART_HOURS)
        points = StatsStore.hourly(CHART_HOURS)
    }
    OnQueryLogChange { refreshStats() }
    val statsVersion by StatsStore.version.collectAsStateWithLifecycle()
    LaunchedEffect(statsVersion) { refreshStats() }

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
        if (running) {
            sendAction(context, DohVpnService.ACTION_STOP)
            return
        }
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else connectVpn()
    }

    val glow by animateFloatAsState(if (running) 1f else 0f, tween(600), label = "glow")

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
            .padding(top = 16.dp, bottom = bottomPadding),
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
            Box(Modifier.padding(horizontal = 20.dp)) {
                CrashCard(
                    report = report,
                    onCopy = {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("SparkleDNS crash", report))
                    },
                    onDismiss = { CrashLog.clear(context); crashVisible = false },
                )
            }
        }

        StatusCard(
            running = running,
            providerName = shown.displayName(context),
            http3 = usesHttp3,
            enabled = !busy && (running || canConnect),
            onToggle = ::toggle,
            modifier = Modifier.padding(horizontal = 20.dp),
        )

        PrivateDnsWarning(privateDnsHost, prefs, Modifier.padding(horizontal = 20.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
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

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.End),
            ) {
                LegendItem(stringResource(R.string.stat_requests), Palette.Accent)
                LegendItem(stringResource(R.string.mon_blocked), Palette.Muted)
            }
            TrafficChart(
                points,
                Modifier
                    .fillMaxWidth()
                    .height(150.dp),
            )
            val startLabel = remember(points) {
                val start = System.currentTimeMillis() - (CHART_HOURS - 1) * 3_600_000L
                SimpleDateFormat("HH:00", Locale.getDefault()).format(Date(start))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(startLabel, color = Palette.Fg3, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(
                    stringResource(R.string.period_24h),
                    color = Palette.Fg2,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(Palette.Card)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
                Text(
                    stringResource(R.string.chart_now),
                    color = Palette.Fg3,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        BlocklistRow(
            info = if (blocking) {
                stringResource(R.string.home_lists_info, lists.count { it.enabled }, formatCount(total.toLong()))
            } else {
                stringResource(R.string.blocking_off)
            },
            onClick = onOpenBlocklist,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
    }
}

@Composable
private fun StatusCard(
    running: Boolean,
    providerName: String,
    http3: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .appCard()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
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
        val dot by animateColorAsState(if (running) Palette.Accent else Palette.Track, Motion.color, label = "dot")
        Row(verticalAlignment = Alignment.CenterVertically) {
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
            )
        }
        Spacer(Modifier.height(10.dp))
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
private fun PrivateDnsWarning(privateDnsHost: String?, prefs: Prefs, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lastHost = remember { arrayOf("") }
    privateDnsHost?.let { lastHost[0] = it }
    var dismissedHost by remember { mutableStateOf(prefs.dismissedPrivateDns) }
    AnimatedVisibility(
        visible = privateDnsHost != null && privateDnsHost != dismissedHost,
        enter = Motion.expandIn,
        exit = Motion.collapseOut,
        modifier = modifier,
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
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(width = 12.dp, height = 3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(color)
        )
        Spacer(Modifier.width(6.dp))
        Text(label, color = Palette.Fg2, fontSize = 11.sp)
    }
}

// Requests (accent line with a soft fill) and blocked queries (grey line) per hour
@Composable
private fun TrafficChart(points: List<HourPoint>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = Palette.Fg3, fontSize = 10.sp)
    Canvas(modifier) {
        val n = points.size
        if (n < 2) return@Canvas
        val maxValue = niceCeiling(points.maxOf { it.total }.coerceAtLeast(1L))
        val inset = 6.dp.toPx()
        val top = 14.dp.toPx()
        val base = size.height - 8.dp.toPx()
        val span = base - top
        val step = (size.width - 2 * inset) / (n - 1)
        fun x(index: Int) = inset + index * step
        fun y(value: Long) = base - value.toFloat() / maxValue * span

        listOf(maxValue / 2, maxValue).forEach { value ->
            val gridY = y(value)
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
                topLeft = Offset(20.dp.toPx(), gridY - 15.dp.toPx()),
                style = labelStyle,
            )
        }

        val line = Path()
        val area = Path()
        val blocked = Path()
        points.forEachIndexed { index, point ->
            val px = x(index)
            if (index == 0) {
                line.moveTo(px, y(point.total))
                area.moveTo(px, base)
                area.lineTo(px, y(point.total))
                blocked.moveTo(px, y(point.blocked))
            } else {
                line.lineTo(px, y(point.total))
                area.lineTo(px, y(point.total))
                blocked.lineTo(px, y(point.blocked))
            }
        }
        area.lineTo(x(n - 1), base)
        area.close()

        drawPath(area, Palette.Accent.copy(alpha = 0.14f))
        drawPath(
            blocked,
            Palette.Muted,
            style = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawPath(
            line,
            Palette.Accent,
            style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        val end = Offset(x(n - 1), y(points.last().total))
        drawCircle(Palette.Bg, radius = 5.dp.toPx(), center = end)
        drawCircle(Palette.Accent, radius = 5.dp.toPx(), center = end, style = Stroke(width = 2.4.dp.toPx()))
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

@Composable
private fun BlocklistRow(info: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .appCard()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Palette.Accent),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_privacy),
                contentDescription = null,
                tint = Palette.AccentInk,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(stringResource(R.string.blocklist_title), color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(info, color = Palette.Fg2, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron()
    }
}
