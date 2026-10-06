package cc.skysparkle.sparkledns

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private const val PRIVACY_POLICY = "https://skysparkle.cc/privacy?lang=en"
private const val SOURCE_CODE = "https://git.skysparkle.cc/kalarina/Sparkledns"
private const val APP_LICENSE = "GPL-3.0-or-later"

private const val APACHE_2 = "Apache License 2.0"
private const val APACHE_2_URL = "https://www.apache.org/licenses/LICENSE-2.0"

private class OpenSourceItem(
    val name: String,
    val author: String,
    val license: String,
    val licenseUrl: String,
    val projectUrl: String,
)

private val Frameworks = listOf(
    OpenSourceItem("Kotlin", "JetBrains", APACHE_2, APACHE_2_URL, "https://kotlinlang.org"),
    OpenSourceItem("Kotlin Coroutines", "JetBrains", APACHE_2, APACHE_2_URL, "https://github.com/Kotlin/kotlinx.coroutines"),
    OpenSourceItem("Jetpack Compose", "The Android Open Source Project", APACHE_2, APACHE_2_URL, "https://developer.android.com/jetpack/compose"),
    OpenSourceItem("Material 3 for Compose", "The Android Open Source Project", APACHE_2, APACHE_2_URL, "https://developer.android.com/jetpack/androidx/releases/compose-material3"),
    OpenSourceItem("AndroidX Activity", "The Android Open Source Project", APACHE_2, APACHE_2_URL, "https://developer.android.com/jetpack/androidx/releases/activity"),
    OpenSourceItem("OkHttp", "Square, Inc.", APACHE_2, APACHE_2_URL, "https://square.github.io/okhttp/"),
    OpenSourceItem("Okio", "Square, Inc.", APACHE_2, APACHE_2_URL, "https://square.github.io/okio/"),
    OpenSourceItem(
        "Android HttpEngine (Cronet)", "The Chromium Authors", "BSD 3-Clause",
        "https://chromium.googlesource.com/chromium/src/+/main/LICENSE", "https://developer.android.com/reference/android/net/http/HttpEngine",
    ),
)

// Not bundled: downloaded only when the user adds them
private val DataSources = listOf(
    OpenSourceItem(
        "HaGeZi DNS Blocklists", "HaGeZi", "GPL-3.0",
        "https://www.gnu.org/licenses/gpl-3.0.html", "https://github.com/hagezi/dns-blocklists",
    ),
)

@Composable
fun AboutScreen(modifier: Modifier = Modifier) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    // Licenses slide in from the side like a pushed page
    AnimatedContent(
        targetState = showLicenses,
        transitionSpec = {
            val forward = targetState
            (slideInHorizontally(Motion.slide) { if (forward) it / 3 else -it / 3 } + fadeIn(Motion.enterFade)) togetherWith
                (slideOutHorizontally(Motion.slide) { if (forward) -it / 3 else it / 3 } + fadeOut(Motion.exitFade))
        },
        modifier = modifier,
        label = "about",
    ) { licenses ->
        if (licenses) {
            LicensesScreen(Modifier.fillMaxSize(), onBack = { showLicenses = false })
        } else {
            AboutContent(Modifier.fillMaxSize(), onOpenLicenses = { showLicenses = true })
        }
    }
}

@Composable
private fun AboutContent(modifier: Modifier, onOpenLicenses: () -> Unit) {
    val context = LocalContext.current
    val http3 = stringResource(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) R.string.about_http3_yes
        else R.string.about_http3_no
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Image(
            painterResource(R.drawable.app_logo),
            contentDescription = null,
            modifier = Modifier
                .size(104.dp)
                .align(Alignment.CenterHorizontally),
        )
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(R.string.about_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // Comes straight from versionName in app/build.gradle.kts
                InfoRow(stringResource(R.string.about_version), BuildConfig.VERSION_NAME)
                InfoRow(stringResource(R.string.about_protocol), "DNS-over-HTTPS (RFC 8484)")
                InfoRow("HTTP/3", http3)
                InfoRow(stringResource(R.string.about_license), APP_LICENSE)
            }
        }

        OutlinedButton(onClick = { openUrl(context, PRIVACY_POLICY) }, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_shield_search), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_privacy_policy))
        }

        OutlinedButton(onClick = { openUrl(context, SOURCE_CODE) }, modifier = Modifier.fillMaxWidth()) {
            Icon(painterResource(R.drawable.ic_android), contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.about_source))
        }

        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clickable(onClick = onOpenLicenses)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(R.drawable.ic_copyright),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                stringResource(R.string.licenses_title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                textDecoration = TextDecoration.Underline,
            )
        }
    }
}

@Composable
private fun LicensesScreen(modifier: Modifier, onBack: () -> Unit) {
    val context = LocalContext.current
    BackHandler(onBack = onBack)

    Column(modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
            }
            Text(stringResource(R.string.licenses_title), style = MaterialTheme.typography.titleLarge)
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.licenses_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(Frameworks, key = { it.name }) { LicenseCard(context, it) }
            item {
                Text(
                    stringResource(R.string.licenses_data),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(DataSources, key = { it.name }) { LicenseCard(context, it) }
        }
    }
}

@Composable
private fun LicenseCard(context: Context, item: OpenSourceItem) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { openUrl(context, item.projectUrl) },
    ) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Text(item.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                "${item.author} · ${item.license}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { openUrl(context, item.licenseUrl) }) {
                Text(stringResource(R.string.license_view))
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.End, modifier = Modifier.weight(1.4f))
    }
}

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
