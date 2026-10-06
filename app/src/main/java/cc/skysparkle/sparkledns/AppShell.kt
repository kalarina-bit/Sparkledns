package cc.skysparkle.sparkledns

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow

private enum class Tab(@StringRes val label: Int, @DrawableRes val icon: Int) {
    HOME(R.string.tab_home, R.drawable.ic_tab_home),
    MONITOR(R.string.tab_monitor, R.drawable.ic_tab_monitor),
    APPS(R.string.tab_apps, R.drawable.ic_tab_apps),
    BLOCKLIST(R.string.tab_blocklist, R.drawable.ic_tab_block),
    ABOUT(R.string.tab_about, R.drawable.ic_tab_info),
}

@Composable
fun AppShell() {
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }

    // Back leads to Home first; only Home closes the app
    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(painterResource(item.icon), contentDescription = null) },
                        label = {
                            Text(stringResource(item.label), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                    )
                }
            }
        },
    ) { padding ->
        // Screens drift a little in the direction of the tab that was picked and cross-fade
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val shift = { width: Int -> if (forward) width / 8 else -width / 8 }
                (fadeIn(Motion.enterFade) + slideInHorizontally(Motion.slide) { shift(it) }) togetherWith
                    (fadeOut(Motion.exitFade) + slideOutHorizontally(Motion.slide) { -shift(it) })
            },
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .imePadding(),
            label = "tabs",
        ) { current ->
            val modifier = Modifier.fillMaxSize()
            when (current) {
                Tab.HOME -> HomeScreen(modifier)
                Tab.MONITOR -> MonitorScreen(modifier)
                Tab.APPS -> AppsScreen(modifier)
                Tab.BLOCKLIST -> BlocklistScreen(modifier)
                Tab.ABOUT -> AboutScreen(modifier)
            }
        }
    }
}
