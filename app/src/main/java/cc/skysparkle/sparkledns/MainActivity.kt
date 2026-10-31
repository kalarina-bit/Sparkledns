package cc.skysparkle.sparkledns

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppLogo.preload(this)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) { AppShell() }
        }
    }
}

internal class TestResult(val ok: Boolean, val text: String)

// How long a tap on Connect, Disconnect or Switch blocks further taps if the VPN state never changes
private const val BUSY_TIMEOUT_MS = 5000L

// Set by a tap until the VPN state changes or the attempt gives up, so a double tap neither
// starts two connections nor switches the VPN back on right after turning it off
@Composable
internal fun rememberVpnBusy(active: DohProvider?): MutableState<Boolean> {
    val busy = remember { mutableStateOf(false) }
    LaunchedEffect(active) { busy.value = false }
    LaunchedEffect(busy.value) {
        if (busy.value) {
            delay(BUSY_TIMEOUT_MS)
            busy.value = false
        }
    }
    return busy
}

// Keeps test results across rotation and process death
internal val TestResultSaver = Saver<TestResult?, String>(
    save = { result -> result?.let { (if (it.ok) "1" else "0") + it.text } },
    restore = { saved -> TestResult(saved.startsWith("1"), saved.drop(1)) },
)

@Composable
internal fun CrashCard(report: String, onCopy: () -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
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

// Hostname of strict Private DNS (DNS-over-TLS) on the current network, null when it is Automatic or Off.
// Android reports the current state on registration and every change after it.
internal fun privateDnsHosts(context: Context): Flow<String?> {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return flowOf(null)
    val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return flowOf(null)
    return callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(linkProperties.privateDnsServerName)
            }

            override fun onLost(network: Network) {
                trySend(null)
            }
        }
        val registered = runCatching { cm.registerDefaultNetworkCallback(callback) }.isSuccess
        awaitClose { if (registered) runCatching { cm.unregisterNetworkCallback(callback) } }
    }.distinctUntilChanged()
}

internal fun openNetworkSettings(context: Context) {
    runCatching {
        context.startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

internal fun hostOf(url: String): String =
    runCatching { Uri.parse(url.trim()).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url

internal fun sendAction(context: Context, action: String) {
    context.startService(Intent(context, DohVpnService::class.java).setAction(action))
}

// Runs from the app process, which is excluded from the VPN, so it tests the server directly
internal fun testProvider(context: Context, provider: DohProvider): TestResult {
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
