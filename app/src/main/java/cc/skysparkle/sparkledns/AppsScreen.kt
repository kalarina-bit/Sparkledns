package cc.skysparkle.sparkledns

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class AppGroup(@StringRes val label: Int, @DrawableRes val icon: Int) {
    SOCIAL(R.string.group_social, R.drawable.ic_group_social),
    VIDEO(R.string.group_video, R.drawable.ic_group_video),
    AUDIO(R.string.group_audio, R.drawable.ic_group_audio),
    GAMES(R.string.group_games, R.drawable.ic_group_games),
    IMAGES(R.string.group_images, R.drawable.ic_group_images),
    NEWS(R.string.group_news, R.drawable.ic_group_news),
    MAPS(R.string.group_maps, R.drawable.ic_group_maps),
    PRODUCTIVITY(R.string.group_productivity, R.drawable.ic_group_productivity),
    OTHER(R.string.group_other, R.drawable.ic_tab_apps),
    SYSTEM(R.string.group_system, R.drawable.ic_group_system),
}

private class AppItem(val label: String, val packageName: String, val group: AppGroup)

// Decoded icons survive scrolling and tab switches instead of being rendered again every time
private val IconCache = LruCache<String, ImageBitmap>(256)

@Composable
fun AppsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    val active by DohVpnService.active.collectAsState()
    val activeExcluded by DohVpnService.activeExcluded.collectAsState()

    var apps by remember { mutableStateOf<List<AppItem>?>(null) }
    var excluded by remember { mutableStateOf(prefs.excludedApps) }
    var search by rememberSaveable { mutableStateOf("") }
    var collapsed by remember { mutableStateOf(setOf(AppGroup.SYSTEM)) }
    val pending = active != null && excluded != activeExcluded
    // Uninstalled apps may still be in the saved set; count only the ones on the device
    val excludedCount = apps?.count { it.packageName in excluded } ?: excluded.size
    val searching = search.isNotBlank()

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { loadApps(context) }
    }

    val groups = remember(apps, search) {
        val term = search.trim()
        apps.orEmpty()
            .filter {
                term.isEmpty() || it.label.contains(term, ignoreCase = true) ||
                    it.packageName.contains(term, ignoreCase = true)
            }
            .groupBy { it.group }
            .toSortedMap()
    }

    fun update(newExcluded: Set<String>) {
        excluded = newExcluded
        prefs.excludedApps = newExcluded
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HintCard(stringResource(R.string.apps_hint), icon = R.drawable.ic_tab_info)
                AnimatedVisibility(visible = pending, enter = Motion.expandIn, exit = Motion.collapseOut) {
                    HintCard(
                        text = stringResource(R.string.apps_pending),
                        icon = R.drawable.ic_refresh,
                        tint = MaterialTheme.colorScheme.primary,
                        action = {
                            TextButton(onClick = { sendAction(context, DohVpnService.ACTION_START) }) {
                                Text(stringResource(R.string.reconnect))
                            }
                        },
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text(stringResource(R.string.apps_search)) },
                leadingIcon = { Icon(painterResource(R.drawable.ic_search), contentDescription = null) },
                singleLine = true,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 4.dp),
            )
        }

        if (excludedCount > 0) {
            item {
                Text(
                    stringResource(R.string.apps_count, excludedCount),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp),
                )
            }
        }

        if (apps == null) {
            item(key = "loading") {
                Box(Modifier.animateItem()) { EmptyState(R.drawable.ic_tab_apps, stringResource(R.string.apps_loading)) }
            }
        }

        groups.forEach { (group, items) ->
            val expanded = searching || group !in collapsed
            item(key = "group:${group.name}") {
                GroupHeader(
                    modifier = Modifier.animateItem(),
                    group = group,
                    apps = items,
                    excluded = excluded,
                    expanded = expanded,
                    onToggleExpand = {
                        collapsed = if (group in collapsed) collapsed - group else collapsed + group
                    },
                    onSetAll = { on ->
                        val packages = items.map { it.packageName }.toSet()
                        update(if (on) excluded - packages else excluded + packages)
                    },
                )
            }
            if (expanded) {
                itemsIndexed(items, key = { _, app -> app.packageName }) { index, app ->
                    AppRow(
                        modifier = Modifier.animateItem(),
                        app = app,
                        enabled = app.packageName !in excluded,
                        last = index == items.lastIndex,
                    ) { on ->
                        update(if (on) excluded - app.packageName else excluded + app.packageName)
                    }
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(
    modifier: Modifier,
    group: AppGroup,
    apps: List<AppItem>,
    excluded: Set<String>,
    expanded: Boolean,
    onToggleExpand: () -> Unit,
    onSetAll: (Boolean) -> Unit,
) {
    val through = apps.count { it.packageName !in excluded }
    val state = when (through) {
        apps.size -> ToggleableState.On
        0 -> ToggleableState.Off
        else -> ToggleableState.Indeterminate
    }

    val chevron by animateFloatAsState(if (expanded) 180f else 0f, Motion.rotation, label = "chevron")

    Row(
        modifier = modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .background(groupedBackground, groupedShape(first = true, last = !expanded))
            .clickable(onClick = onToggleExpand)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(group.icon, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(stringResource(group.label), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.group_count, through, apps.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Checked: whole group goes through SparkleDNS
        TriStateCheckbox(state = state, onClick = { onSetAll(state != ToggleableState.On) })
        Icon(
            painterResource(R.drawable.ic_expand),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(end = 8.dp)
                .size(22.dp)
                .rotate(chevron),
        )
    }
}

@Composable
private fun AppRow(modifier: Modifier, app: AppItem, enabled: Boolean, last: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(IconCache.get(app.packageName), app.packageName) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName).toImageBitmap(96) }
                .getOrNull()
                ?.also { IconCache.put(app.packageName, it) }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .background(groupedBackground, groupedShape(first = false, last = last))
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(start = 64.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = enabled, role = Role.Switch, onValueChange = onToggle)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp)) {
                icon?.let {
                    Image(
                        it,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(10.dp)),
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = enabled, onCheckedChange = null)
        }
    }
}

private fun loadApps(context: Context): List<AppItem> {
    val pm = context.packageManager
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    @Suppress("DEPRECATION")
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map { AppItem(pm.getApplicationLabel(it).toString(), it.packageName, groupOf(it)) }
        .sortedBy { it.label.lowercase() }
}

// Uses the category the developer declared in the manifest; undeclared apps fall back to System/Other
@Suppress("DEPRECATION")
private fun groupOf(info: ApplicationInfo): AppGroup = when {
    info.category == ApplicationInfo.CATEGORY_GAME || info.flags and ApplicationInfo.FLAG_IS_GAME != 0 -> AppGroup.GAMES
    info.category == ApplicationInfo.CATEGORY_SOCIAL -> AppGroup.SOCIAL
    info.category == ApplicationInfo.CATEGORY_VIDEO -> AppGroup.VIDEO
    info.category == ApplicationInfo.CATEGORY_AUDIO -> AppGroup.AUDIO
    info.category == ApplicationInfo.CATEGORY_IMAGE -> AppGroup.IMAGES
    info.category == ApplicationInfo.CATEGORY_NEWS -> AppGroup.NEWS
    info.category == ApplicationInfo.CATEGORY_MAPS -> AppGroup.MAPS
    info.category == ApplicationInfo.CATEGORY_PRODUCTIVITY -> AppGroup.PRODUCTIVITY
    info.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> AppGroup.SYSTEM
    else -> AppGroup.OTHER
}

private fun Drawable.toImageBitmap(size: Int): ImageBitmap {
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    setBounds(0, 0, size, size)
    draw(canvas)
    return bitmap.asImageBitmap()
}
