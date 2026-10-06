package cc.skysparkle.sparkledns

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) { AppShell() }
        }
    }
}

private val AppColors = darkColorScheme(
    primary = Color(0xFF7FB2FF),
    onPrimary = Color(0xFF00285C),
    background = Color(0xFF0F1115),
    surface = Color(0xFF0F1115),
)

private val ProtectedContainer = Color(0xFF16233A)

private class TestResult(val ok: Boolean, val text: String)

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val active by DohVpnService.active.collectAsState()
    val running = active != null
    val scope = rememberCoroutineScope()
    val stats = rememberLiveStats()

    var selected by remember { mutableStateOf(prefs.providerId) }
    var customUrl by remember { mutableStateOf(prefs.customUrl) }
    var customBootstrap by remember { mutableStateOf(prefs.customBootstrap) }
    var customHttp3 by remember { mutableStateOf(prefs.customHttp3) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }
    var shownResult by remember { mutableStateOf<TestResult?>(null) } // kept while the card animates out
    var testing by remember { mutableStateOf(false) }
    val crashReport = remember { CrashLog.read(context) }
    // Strict Private DNS makes Android ignore the VPN's DNS server; re-checked while Home is open
    val privateDnsHost by produceState<String?>(null) {
        while (true) {
            value = readPrivateDnsHost(context)
            delay(3000)
        }
    }
    var crashVisible by remember { mutableStateOf(crashReport != null) }

    val isCustom = selected == Providers.CUSTOM_ID
    val customValid = customUrl.trim().toHttpUrlOrNull()?.isHttps == true
    val canConnect = !isCustom || customValid
    val selectedProvider = remember(selected, customUrl, customBootstrap, customHttp3) { prefs.resolve() }
    val customName = stringResource(R.string.custom)
    fun nameOf(p: DohProvider) = if (p.id == Providers.CUSTOM_ID) customName else p.name
    val shownProvider = active ?: selectedProvider
    val usesHttp3 = shownProvider.http3 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    // Picking a server never touches a live connection; switching is an explicit action
    val pendingSwitch = active?.let { it != selectedProvider && canConnect } == true

    val vpnPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { if (it.resultCode == Activity.RESULT_OK) sendAction(context, DohVpnService.ACTION_START) }

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
        if (running) {
            sendAction(context, DohVpnService.ACTION_STOP)
            return
        }
        val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else connectVpn()
    }

    fun select(id: String) {
        selected = id
        prefs.providerId = id
        testResult = null
    }

    fun runTest() {
        val provider = prefs.resolve()
        testing = true
        testResult = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { testProvider(context, provider) }
            shownResult = result
            testResult = result
            testing = false
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
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

        StatusHero(
            running = running,
            providerName = nameOf(shownProvider),
            http3 = usesHttp3,
            stats = stats,
            canConnect = canConnect,
            switchTo = if (pendingSwitch) nameOf(selectedProvider) else null,
            onToggle = ::toggle,
            onSwitch = { sendAction(context, DohVpnService.ACTION_START) },
        )

        SectionHeader(stringResource(R.string.servers))

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(vertical = 6.dp)) {
                Providers.list.forEachIndexed { index, p ->
                    if (index > 0) RowDivider()
                    ProviderRow(p.name, hostOf(p.url), p.http3, selected == p.id) { select(p.id) }
                }
                RowDivider()
                ProviderRow(
                    title = stringResource(R.string.custom),
                    subtitle = hostOf(customUrl),
                    http3 = customHttp3,
                    selected = isCustom,
                ) { select(Providers.CUSTOM_ID) }
            }
        }

        AnimatedVisibility(visible = isCustom, enter = Motion.expandIn, exit = Motion.collapseOut) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it; prefs.customUrl = it },
                        label = { Text(stringResource(R.string.custom_url)) },
                        singleLine = true,
                        isError = !customValid,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!customValid) {
                        Text(
                            stringResource(R.string.invalid_url),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = customBootstrap,
                        onValueChange = { customBootstrap = it; prefs.customBootstrap = it },
                        label = { Text(stringResource(R.string.custom_bootstrap)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .toggleable(
                                value = customHttp3,
                                role = Role.Switch,
                                onValueChange = { customHttp3 = it; prefs.customHttp3 = it },
                            )
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.http3), style = MaterialTheme.typography.bodyLarge)
                            Text(
                                stringResource(R.string.http3_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Switch(checked = customHttp3, onCheckedChange = null)
                    }
                }
            }
        }

        OutlinedButton(
            onClick = ::runTest,
            enabled = !testing && canConnect,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
        ) { Text(stringResource(if (testing) R.string.testing else R.string.test)) }

        AnimatedVisibility(visible = testResult != null, enter = Motion.expandIn, exit = Motion.collapseOut) {
            shownResult?.let { result ->
                HintCard(
                    text = result.text,
                    icon = if (result.ok) R.drawable.ic_check else R.drawable.ic_alert,
                    tint = if (result.ok) CachedColor else MaterialTheme.colorScheme.error,
                )
            }
        }

        val lastHost = remember { arrayOf("") }
        privateDnsHost?.let { lastHost[0] = it }
        AnimatedVisibility(visible = privateDnsHost != null, enter = Motion.expandIn, exit = Motion.collapseOut) {
            HintCard(
                text = stringResource(R.string.hint, lastHost[0]),
                icon = R.drawable.ic_alert,
                tint = WarningColor,
                action = {
                    TextButton(onClick = { openNetworkSettings(context) }) { Text(stringResource(R.string.open_settings)) }
                },
            )
        }
    }
}

@Composable
private fun StatusHero(
    running: Boolean,
    providerName: String,
    http3: Boolean,
    stats: QueryStats,
    canConnect: Boolean,
    switchTo: String?,
    onToggle: () -> Unit,
    onSwitch: () -> Unit,
) {
    val accent by animateColorAsState(
        if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        animationSpec = Motion.color,
        label = "accent",
    )
    val container by animateColorAsState(
        if (running) ProtectedContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        animationSpec = Motion.color,
        label = "container",
    )

    val ringAlpha by animateFloatAsState(if (running) 1f else 0f, Motion.rotation, label = "ring")

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(124.dp), contentAlignment = Alignment.Center) {
                if (ringAlpha > 0f) PulseRing(accent, ringAlpha)
                Box(
                    modifier = Modifier
                        .size(92.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.18f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painterResource(R.drawable.ic_privacy),
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.size(52.dp),
                    )
                }
            }

            AnimatedContent(
                targetState = running,
                transitionSpec = { fadeIn(Motion.enterFade) togetherWith fadeOut(Motion.exitFade) },
                label = "status",
            ) { on ->
                Text(
                    stringResource(if (on) R.string.status_on else R.string.status_off),
                    style = MaterialTheme.typography.headlineSmall,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.via, providerName),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (http3) {
                    Spacer(Modifier.width(8.dp))
                    Pill("HTTP/3", accent)
                }
            }

            AnimatedVisibility(visible = running, enter = Motion.expandIn, exit = Motion.collapseOut) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    StatColumn(formatCount(stats.total), stringResource(R.string.mon_queries), Modifier.weight(1f))
                    StatDivider()
                    StatColumn(formatCount(stats.blocked), stringResource(R.string.mon_blocked), Modifier.weight(1f))
                    StatDivider()
                    StatColumn(cachePercent(stats), stringResource(R.string.mon_cached), Modifier.weight(1f))
                }
            }

            Button(
                onClick = onToggle,
                enabled = running || canConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            ) {
                AnimatedContent(
                    targetState = running,
                    transitionSpec = { fadeIn(Motion.enterFade) togetherWith fadeOut(Motion.exitFade) },
                    label = "toggle",
                ) { on -> Text(stringResource(if (on) R.string.disconnect else R.string.connect)) }
            }

            // Remembers the last name so the button keeps its label while it animates out
            val lastSwitch = remember { arrayOf("") }
            if (switchTo != null) lastSwitch[0] = switchTo
            AnimatedVisibility(visible = switchTo != null, enter = Motion.expandIn, exit = Motion.collapseOut) {
                OutlinedButton(onClick = onSwitch, modifier = Modifier.fillMaxWidth()) {
                    Icon(painterResource(R.drawable.ic_switch), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.switch_to, lastSwitch[0]))
                }
            }
        }
    }
}

@Composable
private fun PulseRing(color: Color, visibility: Float) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "progress",
    )
    Box(
        Modifier
            .size(124.dp)
            .graphicsLayer {
                val scale = 0.74f + 0.26f * progress
                scaleX = scale
                scaleY = scale
                alpha = (1f - progress) * visibility
            }
            .clip(CircleShape)
            .background(color.copy(alpha = 0.28f))
    )
}

@Composable
private fun CrashCard(report: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_alert), contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.crash_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                report,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
            Row {
                TextButton(onClick = onCopy) { Text(stringResource(R.string.copy)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
            }
        }
    }
}

@Composable
private fun ProviderRow(title: String, subtitle: String, http3: Boolean, selected: Boolean, onClick: () -> Unit) {
    val highlight by animateColorAsState(
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent,
        animationSpec = Motion.color,
        label = "highlight",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(highlight)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (http3) {
            Spacer(Modifier.width(8.dp))
            Pill("HTTP/3", MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 60.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

// Hostname of strict Private DNS (DNS-over-TLS) on the current network, null when it is Automatic or Off
private fun readPrivateDnsHost(context: Context): String? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
    return runCatching { cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName }.getOrNull()
}

private fun openNetworkSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun hostOf(url: String): String =
    runCatching { Uri.parse(url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url

internal fun sendAction(context: Context, action: String) {
    context.startService(Intent(context, DohVpnService::class.java).setAction(action))
}

// Runs from the app process, which is excluded from the VPN, so it tests the server directly
private fun testProvider(context: Context, provider: DohProvider): TestResult {
    val client = DohClient(context, provider)
    return try {
        val t0 = SystemClock.elapsedRealtime()
        client.query(DnsMessage.buildQuery("example.com"))
        val t1 = SystemClock.elapsedRealtime()
        val response = client.query(DnsMessage.buildQuery("example.com"))
        val t2 = SystemClock.elapsedRealtime()
        val rcode = DnsMessage.rcode(response)
        if (rcode == 0) TestResult(true, context.getString(R.string.test_ok, client.lastProtocol ?: "?", t1 - t0, t2 - t1))
        else TestResult(false, context.getString(R.string.test_rcode, rcode))
    } catch (e: Exception) {
        TestResult(false, context.getString(R.string.test_fail, e.message ?: e.javaClass.simpleName))
    } finally {
        client.shutdown()
    }
}
