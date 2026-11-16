package cc.skysparkle.sparkledns

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Shown once on first launch: what the app does, then how to get the personal access key
// for the Skysparkle server. Skipping leaves the key empty; other servers stay available.
@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { Prefs(context) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var keyInput by rememberSaveable { mutableStateOf(prefs.skysparkleKey) }
    val keyValid = SkysparkleKey.parse(keyInput) != null

    fun finish(useKey: Boolean) {
        if (useKey) {
            prefs.skysparkleKey = SkysparkleKey.parse(keyInput) ?: ""
            prefs.providerId = Providers.DEFAULT_ID
        }
        prefs.onboardingDone = true
        onDone()
    }

    BackHandler(enabled = page > 0) { page-- }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Bg)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 20.dp),
        ) {
            Dots(count = 2, current = page)
            AnimatedContent(
                targetState = page,
                transitionSpec = {
                    val forward = targetState > initialState
                    (fadeIn(Motion.enterFade) + slideInHorizontally(Motion.slide) { if (forward) it / 4 else -it / 4 }) togetherWith
                        (fadeOut(Motion.exitFade) + slideOutHorizontally(Motion.slide) { if (forward) -it / 4 else it / 4 })
                },
                modifier = Modifier.weight(1f),
                label = "onboarding",
            ) { current ->
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 40.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    if (current == 0) {
                        Image(
                            bitmap = AppLogo.get(context),
                            contentDescription = null,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(20.dp)),
                        )
                        Spacer(Modifier.height(8.dp))
                        Title(stringResource(R.string.onb_welcome_title))
                        Body(stringResource(R.string.onb_welcome_text))
                        Spacer(Modifier.height(8.dp))
                        Step(1, stringResource(R.string.onb_point_encrypt))
                        Step(2, stringResource(R.string.onb_point_block))
                        Step(3, stringResource(R.string.onb_point_independent))
                    } else {
                        Title(stringResource(R.string.onb_key_title))
                        Body(stringResource(R.string.onb_key_text))
                        Spacer(Modifier.height(4.dp))
                        Step(1, stringResource(R.string.onb_key_step1))
                        Step(2, stringResource(R.string.onb_key_step2))
                        Step(3, stringResource(R.string.onb_key_step3))
                        Spacer(Modifier.height(4.dp))
                        Column(Modifier.fillMaxWidth().appCard().padding(top = 16.dp)) {
                            KeyField(
                                value = keyInput,
                                valid = keyValid,
                                onChange = { keyInput = it },
                                onGetKey = { openUrl(context, SkysparkleKey.SIGNUP_URL) },
                            )
                        }
                    }
                }
            }

            if (page == 0) {
                Button(
                    onClick = { page = 1 },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.onb_next), fontWeight = FontWeight.Bold) }
            } else {
                Button(
                    onClick = { finish(useKey = true) },
                    enabled = keyValid,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text(stringResource(R.string.onb_done), fontWeight = FontWeight.Bold) }
                TextButton(onClick = { finish(useKey = false) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.onb_skip), color = Palette.Fg3)
                }
            }
        }
    }
}

@Composable
private fun Title(text: String) {
    Text(text, color = Palette.Fg, fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
}

@Composable
private fun Body(text: String) {
    Text(text, color = Palette.Fg2, fontSize = 16.sp, lineHeight = 23.sp)
}

@Composable
private fun Step(number: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            Modifier
                .size(26.dp)
                .clip(CircleShape)
                .background(Palette.AccentSoft),
            contentAlignment = Alignment.Center,
        ) {
            Text(number.toString(), color = Palette.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Text(text, color = Palette.Fg, fontSize = 15.sp, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun Dots(count: Int, current: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier
                    .height(4.dp)
                    .width(if (i == current) 22.dp else 10.dp)
                    .clip(CircleShape)
                    .background(if (i == current) Palette.Accent else Palette.Track),
            )
        }
    }
}
