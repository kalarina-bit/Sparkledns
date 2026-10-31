package cc.skysparkle.sparkledns

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

// The three tabs of the bottom panel, then the pages opened from them
private enum class Screen(@StringRes val label: Int, @DrawableRes val icon: Int, val isTab: Boolean) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home, true),
    ACTIVITY(R.string.tab_activity, R.drawable.ic_tab_monitor, true),
    SETTINGS(R.string.tab_settings, R.drawable.ic_tab_settings, true),
    BLOCKLIST(R.string.blocklist_title, R.drawable.ic_tab_block, false),
    APPS(R.string.settings_apps, R.drawable.ic_tab_apps, false),
    ABOUT(R.string.tab_about, R.drawable.ic_tab_info, false),
}

@Composable
fun AppShell() {
    var tab by rememberSaveable { mutableStateOf(Screen.HOME) }
    var page by rememberSaveable { mutableStateOf<Screen?>(null) }
    val current = page ?: tab

    // Back closes an open page first, then leads to Home; only Home closes the app
    BackHandler(enabled = page == null && tab != Screen.HOME) { tab = Screen.HOME }
    BackHandler(enabled = page != null) { page = null }

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Room under the last item of a tab, so it can scroll clear of the floating panel
    val tabBottom = navInset + 112.dp
    val pageBottom = navInset + 24.dp

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
    ) {
        AnimatedContent(
            targetState = current,
            transitionSpec = {
                // Pages slide in from the side; tabs drift a little towards the tab that was picked
                val pageMove = targetState.isTab != initialState.isTab
                val forward = if (pageMove) !targetState.isTab else targetState.ordinal > initialState.ordinal
                val divisor = if (pageMove) 3 else 8
                val shift = { width: Int -> if (forward) width / divisor else -width / divisor }
                (fadeIn(Motion.enterFade) + slideInHorizontally(Motion.slide) { shift(it) }) togetherWith
                    (fadeOut(Motion.exitFade) + slideOutHorizontally(Motion.slide) { -shift(it) })
            },
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .imePadding(),
            label = "screens",
        ) { screen ->
            val modifier = Modifier.fillMaxSize()
            when (screen) {
                Screen.HOME -> HomeScreen(
                    bottomPadding = tabBottom,
                    onOpenBlocklist = { page = Screen.BLOCKLIST },
                    onOpenServers = { tab = Screen.SETTINGS },
                    modifier = modifier,
                )
                Screen.ACTIVITY -> ActivityScreen(bottomPadding = tabBottom, modifier = modifier)
                Screen.SETTINGS -> SettingsScreen(
                    bottomPadding = tabBottom,
                    onOpenApps = { page = Screen.APPS },
                    onOpenAbout = { page = Screen.ABOUT },
                    modifier = modifier,
                )
                Screen.BLOCKLIST -> BlocklistScreen(bottomPadding = pageBottom, onBack = { page = null }, modifier = modifier)
                Screen.APPS -> SubPage(stringResource(R.string.settings_apps), onBack = { page = null }, modifier = modifier) {
                    AppsScreen(Modifier.fillMaxSize())
                }
                Screen.ABOUT -> SubPage(stringResource(R.string.tab_about), onBack = { page = null }, modifier = modifier) {
                    AboutScreen(Modifier.fillMaxSize())
                }
            }
        }

        AnimatedVisibility(
            visible = page == null,
            enter = fadeIn(Motion.enterFade),
            exit = fadeOut(Motion.exitFade),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(contentAlignment = Alignment.BottomCenter) {
                // Content scrolling under the panel fades out instead of being cut off
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(120.dp + navInset)
                        .background(Brush.verticalGradient(0f to Color.Transparent, 0.7f to Palette.Bg))
                )
                NavPill(
                    current = tab,
                    onSelect = { tab = it },
                    modifier = Modifier
                        .navigationBarsPadding()
                        .padding(bottom = 20.dp),
                )
            }
        }
    }
}

// Floating bottom panel: dark, with the picked tab lit in the accent color
@Composable
private fun NavPill(current: Screen, onSelect: (Screen) -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(30.dp)
    Row(
        modifier = modifier
            .shadow(18.dp, shape)
            .clip(shape)
            .background(Palette.Nav)
            .border(1.dp, Color.White.copy(alpha = 0.07f), shape)
            .padding(6.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Screen.entries.filter { it.isTab }.forEach { item ->
            val selected = item == current
            val background by animateColorAsState(
                if (selected) Palette.AccentSoft else Color.Transparent,
                animationSpec = Motion.color,
                label = "navBackground",
            )
            val tint by animateColorAsState(
                if (selected) Palette.Accent else Palette.NavIcon,
                animationSpec = Motion.color,
                label = "navTint",
            )
            val label = stringResource(item.label)
            Box(
                modifier = Modifier
                    .size(width = 58.dp, height = 44.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(background)
                    .selectable(selected = selected, onClick = { onSelect(item) }, role = Role.Tab)
                    .semantics { contentDescription = label },
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(item.icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
            }
        }
    }
}
