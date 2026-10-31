package cc.skysparkle.sparkledns

import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

private class Preset(val name: String, val url: String, @StringRes val description: Int)

private const val HAGEZI_HOME = "https://github.com/hagezi/dns-blocklists"

// Offered on Home when no list is switched on
internal const val HAGEZI_PRO_URL = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt"

private val Presets = listOf(
    Preset("HaGeZi Pro", HAGEZI_PRO_URL, R.string.preset_pro_desc),
    Preset("HaGeZi Pro++", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.plus.txt", R.string.preset_proplus_desc),
    Preset("HaGeZi TIF", "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.txt", R.string.preset_tif_desc),
)

private val TopShape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp)
private val BottomShape = RoundedCornerShape(bottomStart = 20.dp, bottomEnd = 20.dp)

@Composable
fun BlocklistScreen(bottomPadding: Dp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(context) }
    val side = sidePadding(windowWidth())

    val enabled by Blocklist.enabled.collectAsStateWithLifecycle()
    val custom by Blocklist.custom.collectAsStateWithLifecycle()
    val lists by Blocklist.lists.collectAsStateWithLifecycle()
    // Downloads live in Blocklist, so they keep running and stay visible when the page is closed
    val busy by Blocklist.busy.collectAsStateWithLifecycle()
    val error by Blocklist.error.collectAsStateWithLifecycle()
    val lastError = remember { arrayOf("") } // kept while the card animates out
    error?.let { lastError[0] = it }

    var search by rememberSaveable { mutableStateOf("") }
    var domainsOpen by rememberSaveable { mutableStateOf(false) }
    var domainInput by rememberSaveable { mutableStateOf("") }
    var domainError by remember { mutableStateOf(false) }
    var showAddList by rememberSaveable { mutableStateOf(false) }
    var showUpdates by rememberSaveable { mutableStateOf(false) }
    var interval by remember { mutableStateOf(prefs.updateInterval) }
    var wifiOnly by remember { mutableStateOf(prefs.updateWifiOnly) }

    val term = search.trim()
    fun matches(vararg texts: String) = term.isEmpty() || texts.any { it.contains(term, ignoreCase = true) }

    val presetUrls = remember { Presets.map { it.url }.toSet() }
    val ownLists = lists.filter { it.url !in presetUrls }
    val shownDomains = if (term.isEmpty()) custom else custom.filter { it.contains(term, ignoreCase = true) }

    fun addDomain() {
        val input = domainInput
        if (input.isBlank()) return
        scope.launch {
            val result = withContext(Dispatchers.IO) { tryEdit { Blocklist.addCustom(context, input) } }
            result.onSuccess { ok ->
                domainError = !ok
                if (ok) domainInput = ""
            }.onFailure { showEditError(context, it) }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = PaddingValues(start = side, end = side, bottom = bottomPadding),
    ) {
        item(key = "header") {
            PageHeader(
                title = stringResource(R.string.blocklist_title),
                onBack = onBack,
                action = {
                    AppSwitch(checked = enabled, onCheckedChange = { Blocklist.setEnabled(context, it) })
                },
            )
        }

        item(key = "search") {
            SearchField(
                value = search,
                onValueChange = { search = it },
                placeholder = stringResource(R.string.bl_search),
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        item(key = "updates") {
            UpdatesRow(
                interval = interval,
                wifiOnly = wifiOnly,
                onClick = { showUpdates = true },
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        item(key = "error") {
            AnimatedVisibility(visible = error != null, enter = Motion.expandIn, exit = Motion.collapseOut) {
                Box(Modifier.padding(top = 12.dp)) {
                    HintCard(
                        stringResource(R.string.bl_download_failed, lastError[0]),
                        icon = R.drawable.ic_alert,
                        tint = Palette.Blocked,
                    )
                }
            }
        }

        items(Presets.filter { matches(it.name) }, key = { "preset:${it.url}" }) { preset ->
            val list = lists.firstOrNull { it.url == preset.url }
            val downloading = busy == preset.url || (busy == Blocklist.ALL_LISTS && list?.enabled == true)
            BlocklistCard(
                title = preset.name,
                description = stringResource(preset.description),
                source = "hagezi",
                sourceUrl = HAGEZI_HOME,
                info = listInfo(list, downloading),
                checked = list?.enabled == true || busy == preset.url,
                // A list that is not downloaded yet waits for a running download to finish
                switchEnabled = list != null || busy == null,
                progress = downloading,
                onCheckedChange = { on ->
                    if (list == null) {
                        if (on) Blocklist.download(context, preset.url)
                    } else {
                        scope.editBlocklist(context) { Blocklist.setListEnabled(context, preset.url, on) }
                    }
                },
                modifier = Modifier
                    .animateItem()
                    .padding(top = 12.dp),
            )
        }

        items(ownLists.filter { matches(it.url) }, key = { "list:${it.url}" }) { list ->
            val uri = remember(list.url) { Uri.parse(list.url) }
            val downloading = busy == list.url || (busy == Blocklist.ALL_LISTS && list.enabled)
            BlocklistCard(
                title = uri.lastPathSegment ?: uri.host ?: list.url,
                description = stringResource(R.string.list_added_by_you),
                source = uri.host ?: "",
                sourceUrl = null,
                info = listInfo(list, downloading),
                checked = list.enabled,
                switchEnabled = true,
                progress = downloading,
                onCheckedChange = { on -> scope.editBlocklist(context) { Blocklist.setListEnabled(context, list.url, on) } },
                onDelete = if (busy == null) {
                    { scope.editBlocklist(context) { Blocklist.removeList(context, list.url) } }
                } else {
                    null
                },
                modifier = Modifier
                    .animateItem()
                    .padding(top = 12.dp),
            )
        }

        item(key = "domains") {
            MyDomainsHeader(
                count = custom.size,
                open = domainsOpen,
                onToggle = { domainsOpen = !domainsOpen },
                shape = if (domainsOpen) TopShape else CardShape,
                modifier = Modifier.padding(top = 12.dp),
            ) {
                // Input to add a domain; the accent button matches the rest of the app
                Row(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it; domainError = false },
                        placeholder = { Text(stringResource(R.string.bl_domain_hint), maxLines = 1) },
                        singleLine = true,
                        isError = domainError,
                        shape = RoundedCornerShape(14.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addDomain() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = ::addDomain,
                        enabled = domainInput.isNotBlank(),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 18.dp),
                        modifier = Modifier.height(56.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add), fontWeight = FontWeight.Bold)
                    }
                }
                AnimatedVisibility(visible = domainError, enter = Motion.expandIn, exit = Motion.collapseOut) {
                    Text(
                        stringResource(R.string.bl_invalid),
                        color = Palette.Blocked,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
                if (custom.isEmpty()) {
                    Text(
                        stringResource(R.string.bl_empty_custom),
                        color = Palette.Fg3,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    )
                }
            }
        }

        // Own domains are lazy rows, so hundreds of them are not all composed at once.
        // Together with the header above they look like one card.
        if (domainsOpen) {
            itemsIndexed(shownDomains, key = { _, domain -> "domain:$domain" }, contentType = { _, _ -> "domain" }) { index, domain ->
                val last = index == shownDomains.lastIndex
                DomainRow(
                    domain = domain,
                    shape = if (last) BottomShape else RectangleShape,
                    onDelete = { scope.editBlocklist(context) { Blocklist.removeCustom(context, domain) } },
                    modifier = Modifier.animateItem(),
                )
            }
            if (shownDomains.isEmpty()) {
                item(key = "domains-end") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(BottomShape)
                            .background(Palette.Card)
                    )
                }
            }
        }

        item(key = "add") {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Button(
                    onClick = { showAddList = true },
                    enabled = busy == null,
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 22.dp),
                    modifier = Modifier.height(48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.bl_add_list), fontWeight = FontWeight.ExtraBold)
                }
            }
        }
    }

    if (showAddList) {
        AddListDialog(
            busy = busy != null,
            onAdd = { url -> Blocklist.download(context, url) },
            onDismiss = { showAddList = false },
        )
    }

    if (showUpdates) {
        UpdatesDialog(
            interval = interval,
            wifiOnly = wifiOnly,
            canUpdateNow = busy == null && lists.any { it.enabled },
            onInterval = {
                interval = it
                prefs.updateInterval = it
                AutoUpdate.apply(context)
            },
            onWifiOnly = {
                wifiOnly = it
                prefs.updateWifiOnly = it
                AutoUpdate.apply(context)
            },
            onUpdateNow = {
                Blocklist.updateAll(context)
                showUpdates = false
            },
            onDismiss = { showUpdates = false },
        )
    }
}

@Composable
private fun listInfo(list: RemoteList?, downloading: Boolean): String = when {
    downloading -> stringResource(R.string.bl_updating)
    list == null -> stringResource(R.string.list_not_downloaded)
    else -> stringResource(
        R.string.bl_list_info,
        formatCount(list.count.toLong()),
        DateUtils.getRelativeTimeSpanString(list.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
    )
}

@Composable
private fun UpdatesRow(interval: UpdateInterval, wifiOnly: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    val label = stringResource(intervalLabel(interval))
    val wifi = stringResource(R.string.auto_wifi)
    val summary = if (interval != UpdateInterval.OFF && wifiOnly) "$label · $wifi" else label
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Palette.Line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.ic_update), contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.auto_update), color = Palette.Fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(summary, color = Palette.Fg2, fontSize = 12.sp)
        }
        Chevron()
    }
}

@StringRes
private fun intervalLabel(interval: UpdateInterval): Int = when (interval) {
    UpdateInterval.OFF -> R.string.auto_off
    UpdateInterval.DAILY -> R.string.auto_daily
    UpdateInterval.WEEKLY -> R.string.auto_weekly
}

@Composable
private fun BlocklistCard(
    title: String,
    description: String,
    source: String,
    sourceUrl: String?,
    info: String,
    checked: Boolean,
    switchEnabled: Boolean,
    progress: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .appCard()
            .padding(start = 16.dp, end = 10.dp, top = 6.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(
                title,
                color = Palette.Fg,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onDelete != null) {
                IconButton(onClick = onDelete) {
                    Icon(
                        painterResource(R.drawable.ic_delete),
                        contentDescription = stringResource(R.string.delete),
                        tint = Palette.Fg3,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            AppSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = switchEnabled)
        }
        Text(
            description,
            color = Palette.Fg2,
            fontSize = 12.sp,
            lineHeight = 17.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 6.dp),
        )
        Row(
            modifier = Modifier.padding(top = 6.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (source.isNotEmpty()) {
                Text(
                    source,
                    color = Palette.Accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = if (sourceUrl != null) Modifier.clickable { openUrl(context, sourceUrl) } else Modifier,
                )
                Text(" · ", color = Palette.Fg3, fontSize = 12.sp)
            }
            Text(info, color = Palette.Fg3, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        AnimatedVisibility(visible = progress, enter = Motion.expandIn, exit = Motion.collapseOut) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, end = 6.dp),
                color = Palette.Accent,
                trackColor = Palette.Track,
            )
        }
    }
}

@Composable
private fun MyDomainsHeader(
    count: Int,
    open: Boolean,
    onToggle: () -> Unit,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val chevron by animateFloatAsState(if (open) 180f else 0f, Motion.rotation, label = "chevron")
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Card),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.bl_my), color = Palette.Fg, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    stringResource(R.string.my_domains_desc),
                    color = Palette.Fg2,
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                )
            }
            Spacer(Modifier.width(10.dp))
            Text(
                formatCount(count.toLong()),
                color = Palette.Accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.AccentSoft)
                    .padding(horizontal = 8.dp, vertical = 3.dp),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                painterResource(R.drawable.ic_expand),
                contentDescription = null,
                tint = Palette.Fg3,
                modifier = Modifier
                    .size(20.dp)
                    .graphicsLayer { rotationZ = chevron },
            )
        }
        AnimatedVisibility(visible = open, enter = Motion.expandIn, exit = Motion.collapseOut) {
            Column { content() }
        }
    }
}

@Composable
private fun DomainRow(domain: String, shape: Shape, onDelete: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Palette.Card)
    ) {
        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp), color = Palette.Line)
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                domain,
                color = Palette.Fg,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDelete) {
                Icon(
                    painterResource(R.drawable.ic_delete),
                    contentDescription = stringResource(R.string.delete),
                    tint = Palette.Fg3,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun AddListDialog(busy: Boolean, onAdd: (String) -> Boolean, onDismiss: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (url.isNotBlank() && onAdd(url.trim())) onDismiss()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bl_add_list)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.bl_note), color = Palette.Fg2, fontSize = 13.sp)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.bl_list_url)) },
                    placeholder = { Text("https://", maxLines = 1) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = ::submit, enabled = url.isNotBlank() && !busy, shape = RoundedCornerShape(14.dp)) {
                Text(stringResource(R.string.add), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
        containerColor = Palette.CardHigh,
    )
}

@Composable
private fun UpdatesDialog(
    interval: UpdateInterval,
    wifiOnly: Boolean,
    canUpdateNow: Boolean,
    onInterval: (UpdateInterval) -> Unit,
    onWifiOnly: (Boolean) -> Unit,
    onUpdateNow: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.auto_update)) },
        text = {
            Column {
                UpdateInterval.entries.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .selectable(selected = option == interval, onClick = { onInterval(option) }, role = Role.RadioButton)
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == interval, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(stringResource(intervalLabel(option)), color = Palette.Fg, fontSize = 15.sp)
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp), color = Palette.Line)
                val wifiEnabled = interval != UpdateInterval.OFF
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(value = wifiOnly, enabled = wifiEnabled, role = Role.Switch, onValueChange = onWifiOnly)
                        .graphicsLayer { alpha = if (wifiEnabled) 1f else 0.45f }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.auto_wifi), color = Palette.Fg, fontSize = 15.sp)
                        Text(stringResource(R.string.auto_wifi_desc), color = Palette.Fg3, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    AppSwitch(checked = wifiOnly, onCheckedChange = null, enabled = wifiEnabled)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
        dismissButton = {
            TextButton(onClick = onUpdateNow, enabled = canUpdateNow) { Text(stringResource(R.string.auto_update_now)) }
        },
        containerColor = Palette.CardHigh,
    )
}

// Blocklist edits write to storage, which can fail (a full disk, for one)
internal inline fun <T> tryEdit(action: () -> T): Result<T> =
    try {
        Result.success(action())
    } catch (e: IOException) {
        Result.failure(e)
    }

internal fun CoroutineScope.editBlocklist(context: Context, action: () -> Unit) = launch {
    withContext(Dispatchers.IO) { tryEdit(action) }.onFailure { showEditError(context, it) }
}

internal fun showEditError(context: Context, error: Throwable) {
    val reason = error.message ?: error.javaClass.simpleName
    Toast.makeText(context, context.getString(R.string.test_fail, reason), Toast.LENGTH_LONG).show()
}
