package cc.skysparkle.sparkledns

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val Presets = listOf(
    "HaGeZi Pro++" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.plus.txt",
    "HaGeZi TIF" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/tif.txt",
)

@Composable
fun BlocklistScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val enabled by Blocklist.enabled.collectAsState()
    val total by Blocklist.total.collectAsState()
    val custom by Blocklist.custom.collectAsState()
    val lists by Blocklist.lists.collectAsState()

    var domainInput by rememberSaveable { mutableStateOf("") }
    var domainError by remember { mutableStateOf(false) }
    var urlInput by rememberSaveable { mutableStateOf("") }
    // Downloads live in Blocklist, so they keep running and stay visible across tab switches
    val busy by Blocklist.busy.collectAsState()
    val error by Blocklist.error.collectAsState()
    val lastError = remember { arrayOf("") } // kept while the card animates out
    error?.let { lastError[0] = it }

    fun addDomain() {
        val input = domainInput
        if (input.isBlank()) return
        scope.launch {
            val ok = withContext(Dispatchers.IO) { Blocklist.addCustom(context, input) }
            domainError = !ok
            if (ok) domainInput = ""
        }
    }

    fun addList() {
        val url = urlInput.trim()
        if (url.isNotEmpty() && Blocklist.download(context, url)) urlInput = ""
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(
                    R.drawable.ic_tab_block,
                    tint = if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outline,
                    size = 52.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.bl_title), style = MaterialTheme.typography.titleMedium)
                    Text(
                        formatCount(total.toLong()),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(R.string.bl_total_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = enabled, onCheckedChange = { Blocklist.setEnabled(context, it) })
            }
        }

        HintCard(stringResource(R.string.bl_note), icon = R.drawable.ic_tab_info)

        SectionHeader(stringResource(R.string.bl_my), icon = R.drawable.ic_tab_block)

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .animateContentSize(Motion.size)
                    .padding(vertical = 8.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it; domainError = false },
                        placeholder = { Text(stringResource(R.string.bl_domain_hint), maxLines = 1) },
                        singleLine = true,
                        isError = domainError,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addDomain() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(onClick = ::addDomain, enabled = domainInput.isNotBlank()) {
                        Icon(painterResource(R.drawable.ic_add_domain), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add))
                    }
                }
                AnimatedVisibility(visible = domainError, enter = Motion.expandIn, exit = Motion.collapseOut) {
                    Text(
                        stringResource(R.string.bl_invalid),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                if (custom.isEmpty()) {
                    Text(
                        stringResource(R.string.bl_empty_custom),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                custom.forEach { domain ->
                    InsetDivider(16)
                    Row(
                        modifier = Modifier.padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(domain, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        IconButton(onClick = { scope.launch(Dispatchers.IO) { Blocklist.removeCustom(context, domain) } }) {
                            Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete), Modifier.size(20.dp))
                        }
                    }
                }
            }
        }

        SectionHeader(
            stringResource(R.string.bl_lists),
            icon = R.drawable.ic_list,
            action = {
                TextButton(
                    onClick = { Blocklist.updateAll(context) },
                    enabled = lists.isNotEmpty() && busy == null,
                ) {
                    Icon(painterResource(R.drawable.ic_refresh), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (busy == Blocklist.ALL_LISTS) R.string.bl_updating else R.string.bl_update_all))
                }
            },
        )

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier
                    .animateContentSize(Motion.size)
                    .padding(vertical = 8.dp)
            ) {
                lists.forEachIndexed { index, list ->
                    if (index > 0) InsetDivider(68)
                    ListRow(
                        list = list,
                        updating = busy == list.url || busy == Blocklist.ALL_LISTS,
                        actionsEnabled = busy == null,
                        onRefresh = { Blocklist.download(context, list.url) },
                        onDelete = { scope.launch(Dispatchers.IO) { Blocklist.removeList(context, list.url) } },
                    )
                }
                if (lists.isNotEmpty()) InsetDivider(16)
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text(stringResource(R.string.bl_list_url), maxLines = 1) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { if (busy == null) addList() }),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = ::addList,
                        enabled = urlInput.isNotBlank() && busy == null,
                    ) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.add))
                    }
                }
                // A new list being added has no row yet, so its progress shows under the input
                val addingNew = busy != null && busy != Blocklist.ALL_LISTS && lists.none { it.url == busy }
                AnimatedVisibility(visible = addingNew, enter = Motion.expandIn, exit = Motion.collapseOut) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
        }

        val missingPresets = Presets.filter { (_, url) -> lists.none { it.url == url } }
        AnimatedVisibility(visible = missingPresets.isNotEmpty(), enter = Motion.expandIn, exit = Motion.collapseOut) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SectionHeader(stringResource(R.string.bl_presets))
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    missingPresets.forEach { (name, url) ->
                        AssistChip(
                            onClick = { Blocklist.download(context, url) },
                            enabled = busy == null,
                            leadingIcon = {
                                Icon(painterResource(R.drawable.ic_add), contentDescription = null, modifier = Modifier.size(18.dp))
                            },
                            label = { Text(if (busy == url) stringResource(R.string.bl_updating) else name) },
                        )
                    }
                }
            }
        }

        AnimatedVisibility(visible = error != null, enter = Motion.expandIn, exit = Motion.collapseOut) {
            HintCard(
                stringResource(R.string.bl_download_failed, lastError[0]),
                icon = R.drawable.ic_alert,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun InsetDivider(start: Int) {
    HorizontalDivider(
        modifier = Modifier.padding(start = start.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

@Composable
private fun ListRow(
    list: RemoteList,
    updating: Boolean,
    actionsEnabled: Boolean,
    onRefresh: () -> Unit,
    onDelete: () -> Unit,
) {
    val uri = remember(list.url) { Uri.parse(list.url) }
    val title = uri.lastPathSegment ?: list.url
    val updated = remember(list.updated) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(list.updated))
    }

    Column {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconBadge(R.drawable.ic_list, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    uri.host ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (updating) stringResource(R.string.bl_updating)
                    else stringResource(R.string.bl_list_info, formatCount(list.count.toLong()), updated),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRefresh, enabled = actionsEnabled) {
                Icon(painterResource(R.drawable.ic_refresh), stringResource(R.string.bl_update_all), Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete, enabled = actionsEnabled) {
                Icon(painterResource(R.drawable.ic_delete), stringResource(R.string.delete), Modifier.size(20.dp))
            }
        }
        AnimatedVisibility(visible = updating, enter = Motion.expandIn, exit = Motion.collapseOut) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 68.dp, end = 16.dp, bottom = 8.dp),
            )
        }
    }
}
