package cc.skysparkle.sparkledns

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.PaddingValues
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
    var customServers by remember { mutableStateOf(prefs.customServers) }
    var expandedServerId by remember { mutableStateOf<String?>(null) }
    var testResult by rememberSaveable(stateSaver = TestResultSaver) { mutableStateOf<TestResult?>(null) }
    var shownResult by rememberSaveable(stateSaver = TestResultSaver) { mutableStateOf<TestResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    val excludedCount = remember { prefs.excludedApps.size }

    val isCustomServer = selected.startsWith("custom:")
    val canConnect = true
    val selectedProvider = remember(selected, customServers) { prefs.resolve() }

    // Picking a server never touches a live connection; switching is an explicit action
    val pendingSwitch = active?.let { it != selectedProvider && canConnect } == true
    var busy by rememberVpnBusy(active)

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
            }
        }

        // Custom servers section
        if (customServers.isNotEmpty()) {
            SectionLabel(stringResource(R.string.custom_servers), Modifier.padding(top = 8.dp))
            Column(Modifier.fillMaxWidth().appCard()) {
                customServers.forEachIndexed { index, server ->
                    if (index > 0) ListDivider()

                    val isExpanded = expandedServerId == server.id
                    val isSelected = selected == "custom:${server.id}"

                    CustomServerRow(
                        server = server,
                        selected = isSelected,
                        onClick = {
                            // Only toggle expansion, no auto-select
                            expandedServerId = if (isExpanded) null else server.id
                        },
                    )

                    AnimatedVisibility(visible = isExpanded, enter = Motion.expandIn, exit = Motion.collapseOut) {
                        CustomServerEditForm(
                            server = server,
                            isSelected = isSelected,
                            prefs = prefs,
                            onUpdate = { updated ->
                                prefs.updateCustomServer(server.id, updated)
                                customServers = prefs.customServers
                            },
                            onDelete = {
                                prefs.deleteCustomServer(server.id)
                                customServers = prefs.customServers
                                selected = prefs.providerId
                                expandedServerId = null
                            },
                            onSelect = {
                                select("custom:${server.id}")
                            },
                        )
                    }
                }
                ListDivider()
                AddCustomServerRow(
                    onClick = {
                        val newServer = CustomDohServer.create(
                            name = context.getString(R.string.default_custom_server_name, customServers.size + 1),
                            url = "https://",
                            bootstrap = emptyList(),
                            http3 = false
                        )
                        prefs.addCustomServer(newServer)
                        customServers = prefs.customServers
                        expandedServerId = newServer.id
                    }
                )
            }
        } else {
            // Show "Add server" button when no custom servers
            OutlinedButton(
                onClick = {
                    val newServer = CustomDohServer.create(
                        name = context.getString(R.string.default_custom_server_name, 1),
                        url = "https://",
                        bootstrap = emptyList(),
                        http3 = false
                    )
                    prefs.addCustomServer(newServer)
                    customServers = prefs.customServers
                    expandedServerId = newServer.id
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.add_custom_server))
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
@Composable
private fun CustomServerRow(
    server: CustomDohServer,
    selected: Boolean,
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
            .drawBehind { drawRect(highlight) }
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .heightIn(min = 60.dp)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                server.name,
                color = Palette.Fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val subtitle = hostOf(server.url) + if (server.http3) " · HTTP/3" else ""
            Text(subtitle, color = Palette.Fg3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
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

@Composable
private fun AddCustomServerRow(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_add),
            contentDescription = null,
            tint = Palette.Accent,
            modifier = Modifier.size(20.dp)
        )
        Spacer(Modifier.width(12.dp))
        Text(
            stringResource(R.string.add_custom_server),
            color = Palette.Accent,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun CustomServerEditForm(
    server: CustomDohServer,
    isSelected: Boolean,
    prefs: Prefs,
    onUpdate: (CustomDohServer) -> Unit,
    onDelete: () -> Unit,
    onSelect: () -> Unit,
) {
    var name by remember(server) { mutableStateOf(server.name) }
    var url by remember(server) { mutableStateOf(server.url) }
    var bootstrap by remember(server) { mutableStateOf(server.bootstrap.joinToString(", ")) }
    var http3 by remember(server) { mutableStateOf(server.http3) }

    val urlValid = remember(url) { url.trim().toHttpUrlOrNull()?.isHttps == true }

    // Auto-save on changes
    LaunchedEffect(name, url, bootstrap, http3) {
        if (urlValid && name.isNotBlank()) {
            val updated = CustomDohServer(
                id = server.id,
                name = name.trim(),
                url = url.trim(),
                bootstrap = parseIps(bootstrap),
                http3 = http3
            )
            onUpdate(updated)
        }
    }

    Column(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text(stringResource(R.string.server_name)) },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(stringResource(R.string.custom_url)) },
            singleLine = true,
            isError = !urlValid,
            shape = RoundedCornerShape(14.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        if (!urlValid) {
            Text(stringResource(R.string.invalid_url), color = Palette.Blocked, fontSize = 12.sp)
        }

        OutlinedTextField(
            value = bootstrap,
            onValueChange = { bootstrap = it },
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
                    value = http3,
                    role = Role.Switch,
                    onValueChange = { http3 = it },
                )
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.http3), color = Palette.Fg, fontSize = 15.sp)
                Text(stringResource(R.string.http3_note), color = Palette.Fg3, fontSize = 12.sp)
            }
            Spacer(Modifier.width(12.dp))
            AppSwitch(checked = http3, onCheckedChange = null)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Show "Switch" button only when server is NOT selected
            if (!isSelected) {
                Button(
                    onClick = onSelect,
                    enabled = urlValid,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_swap), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.switch_to, name.trim()),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Delete button - always visible, big and clear
            OutlinedButton(
                onClick = onDelete,
                shape = RoundedCornerShape(12.dp),
                modifier = if (isSelected) Modifier.fillMaxWidth().height(44.dp) else Modifier.weight(1f).height(44.dp),
            ) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = null,
                    tint = Palette.Blocked,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.delete_server),
                    color = Palette.Blocked,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
