package cc.skysparkle.sparkledns

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun SettingsScreen(
    bottomPadding: Dp,
    onOpenApps: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val scope = rememberCoroutineScope()
    val side = sidePadding(windowWidth())
    val active by DohVpnService.active.collectAsStateWithLifecycle()

    var selected by remember { mutableStateOf(prefs.providerId) }
    var customUrl by remember { mutableStateOf(prefs.customUrl) }
    var customBootstrap by remember { mutableStateOf(prefs.customBootstrap) }
    var customHttp3 by remember { mutableStateOf(prefs.customHttp3) }
    var keyInput by remember { mutableStateOf(prefs.skysparkleKey) }
    var testResult by rememberSaveable(stateSaver = TestResultSaver) { mutableStateOf<TestResult?>(null) }
    var shownResult by rememberSaveable(stateSaver = TestResultSaver) { mutableStateOf<TestResult?>(null) } // kept while the card animates out
    var testing by remember { mutableStateOf(false) }
    val excludedCount = remember { prefs.excludedApps.size }

    val isCustom = selected == Providers.CUSTOM_ID
    val customValid = remember(customUrl) { customUrl.trim().toHttpUrlOrNull()?.isHttps == true }
    val selectedProvider = remember(selected, customUrl, customBootstrap, customHttp3, keyInput) { prefs.resolve() }
    val canConnect = selectedProvider.url.toHttpUrlOrNull()?.isHttps == true

    // Picking a server never touches a live connection; switching is an explicit action
    val pendingSwitch = active?.let { it != selectedProvider && canConnect } == true
    var busy by rememberVpnBusy(active)

    fun select(id: String) {
        selected = id
        prefs.providerId = id
        testResult = null
    }

    // Signed in from the key field: the key is already saved, show it and select the server
    SignInResult { key ->
        keyInput = key
        selected = Providers.DEFAULT_ID
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
            .verticalScroll(rememberScrollState())
            .padding(start = side, end = side, top = 16.dp, bottom = bottomPadding),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ScreenTitle(stringResource(R.string.tab_settings), Modifier.heightIn(min = 44.dp))

        SectionLabel(stringResource(R.string.servers), Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().appCard()) {
            Providers.builtIn.forEachIndexed { index, provider ->
                if (index > 0) ListDivider()
                ServerRow(
                    title = provider.name,
                    subtitle = hostOf(provider.url) + if (provider.http3) " · HTTP/3" else "",
                    selected = selected == provider.id,
                    onPolicy = provider.policy?.let { url -> { openUrl(context, url) } },
                    onDonate = provider.donate?.let { url -> { openUrl(context, url) } },
                    onClick = { select(provider.id) },
                )
                if (provider.id == Providers.DEFAULT_ID) {
                    AnimatedVisibility(visible = selected == Providers.DEFAULT_ID, enter = Motion.expandIn, exit = Motion.collapseOut) {
                        KeyField(key = keyInput)
                    }
                }
            }
            ListDivider()
            ServerRow(
                title = stringResource(R.string.custom),
                subtitle = if (customUrl.trim() == "https://" || customUrl.isBlank()) "DNS-over-HTTPS (RFC 8484)" else hostOf(customUrl),
                selected = isCustom,
                onClick = { select(Providers.CUSTOM_ID) },
            )
            AnimatedVisibility(visible = isCustom, enter = Motion.expandIn, exit = Motion.collapseOut) {
                Column(
                    Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = customUrl,
                        onValueChange = { customUrl = it; prefs.customUrl = it },
                        label = { Text(stringResource(R.string.custom_url)) },
                        singleLine = true,
                        isError = !customValid,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!customValid) {
                        Text(stringResource(R.string.invalid_url), color = Palette.Blocked, fontSize = 12.sp)
                    }
                    OutlinedTextField(
                        value = customBootstrap,
                        onValueChange = { customBootstrap = it; prefs.customBootstrap = it },
                        label = { Text(stringResource(R.string.custom_bootstrap)) },
                        singleLine = true,
                        shape = RoundedCornerShape(14.dp),
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
                            Text(stringResource(R.string.http3), color = Palette.Fg, fontSize = 15.sp)
                            Text(stringResource(R.string.http3_note), color = Palette.Fg3, fontSize = 12.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        AppSwitch(checked = customHttp3, onCheckedChange = null)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = ::runTest,
                enabled = !testing && canConnect,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_speed), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (testing) R.string.testing else R.string.test), maxLines = 1)
            }

            // The button keeps its label while it animates out
            val switchName = rememberLast(if (pendingSwitch) selectedProvider.displayName(context) else null).orEmpty()
            AnimatedVisibility(visible = pendingSwitch, modifier = Modifier.weight(1f)) {
                Button(
                    onClick = {
                        if (!busy) {
                            busy = true
                            sendAction(context, DohVpnService.ACTION_START)
                        }
                    },
                    enabled = !busy,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_swap), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(R.string.switch_to, switchName),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(visible = testResult != null, enter = Motion.expandIn, exit = Motion.collapseOut) {
            shownResult?.let { result ->
                HintCard(
                    text = result.text,
                    icon = if (result.ok) R.drawable.ic_check else R.drawable.ic_alert,
                    tint = if (result.ok) CachedColor else Palette.Blocked,
                    onDismiss = { testResult = null },
                )
            }
        }

        SectionLabel(stringResource(R.string.settings_general), Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().appCard()) {
            NavRow(
                icon = R.drawable.ic_tab_apps,
                title = stringResource(R.string.settings_apps),
                subtitle = if (excludedCount > 0) stringResource(R.string.apps_count, excludedCount)
                else stringResource(R.string.settings_apps_desc),
                onClick = onOpenApps,
            )
            ListDivider()
            NavRow(
                icon = R.drawable.ic_tab_info,
                title = stringResource(R.string.tab_about),
                subtitle = "${stringResource(R.string.about_version)} ${BuildConfig.VERSION_NAME}",
                onClick = onOpenAbout,
            )
        }
    }
}

// Access key for the Skysparkle server. It comes only from signing in; the address can be
// copied to use the same key in a browser or on another device
@Composable
internal fun KeyField(key: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val hasKey = SkysparkleKey.parse(key) != null
    Column(
        modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.key_status, key.take(4)),
                    color = Palette.Fg2,
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { copyKeyAddress(context, key) }) {
                    Text(stringResource(R.string.key_copy))
                }
            }
        }
        SignInButton(again = hasKey, modifier = Modifier.fillMaxWidth())
    }
}

// Starts the browser sign-in; shows progress while the app waits for it
@Composable
internal fun SignInButton(again: Boolean, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(14.dp)) {
    val context = LocalContext.current
    val signIn by SkysparkleSignIn.state.collectAsStateWithLifecycle()
    val working = signIn == SkysparkleSignIn.State.Waiting || signIn == SkysparkleSignIn.State.Exchanging
    Button(onClick = { SkysparkleSignIn.start(context) }, enabled = !working, shape = shape, modifier = modifier) {
        if (working) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            Spacer(Modifier.width(10.dp))
        }
        Text(
            stringResource(if (working) R.string.sign_in_working else if (again) R.string.sign_in_again else R.string.sign_in),
            fontWeight = FontWeight.Bold,
        )
    }
}

// The key is personal, so the clipboard is told to hide it from previews (Android 13+),
// and Android 13+ confirms the copy itself
private fun copyKeyAddress(context: Context, key: String) {
    val clip = ClipData.newPlainText("SkySparkle", SkysparkleKey.url(key))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, R.string.key_copied, Toast.LENGTH_SHORT).show()
    }
}

// Result of the Skysparkle sign-in, shown once on the screen that started it
@Composable
internal fun SignInResult(onKey: (String) -> Unit) {
    val context = LocalContext.current
    val signIn by SkysparkleSignIn.state.collectAsStateWithLifecycle()
    LaunchedEffect(signIn) {
        when (val s = signIn) {
            is SkysparkleSignIn.State.Done -> {
                onKey(s.key)
                Toast.makeText(context, R.string.sign_in_done, Toast.LENGTH_SHORT).show()
                SkysparkleSignIn.consume()
            }
            is SkysparkleSignIn.State.Failed -> {
                Toast.makeText(context, s.message, Toast.LENGTH_LONG).show()
                SkysparkleSignIn.consume()
            }
            else -> Unit
        }
    }
}

@Composable
private fun ServerRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onPolicy: (() -> Unit)? = null,
    onDonate: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val highlight by animateColorAsState(
        if (selected) Palette.Accent.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = Motion.color,
        label = "highlight",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Read when drawing, so the fade does not recompose the row every frame
            .drawBehind { drawRect(highlight) }
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = Palette.Fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(subtitle, color = Palette.Fg3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onDonate != null) LinkButton(R.drawable.ic_donate, stringResource(R.string.donate), onDonate)
        if (onPolicy != null) LinkButton(R.drawable.ic_policy, stringResource(R.string.about_privacy_policy), onPolicy)
        Spacer(Modifier.width(4.dp))
        if (selected) {
            Icon(
                painterResource(R.drawable.ic_check),
                contentDescription = null,
                tint = Palette.Accent,
                modifier = Modifier.size(22.dp),
            )
        } else {
            Spacer(Modifier.size(22.dp))
        }
        Spacer(Modifier.width(6.dp))
    }
}

// Small button next to a server that opens one of its pages
@Composable
private fun LinkButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(40.dp)) {
        Icon(painterResource(icon), contentDescription = description, tint = Palette.Fg3, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun NavRow(@DrawableRes icon: Int, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Palette.Fg3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Chevron()
    }
}
