package cc.skysparkle.sparkledns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// One dark palette for the whole app
object Palette {
    val Bg = Color(0xFF08090B)
    val Card = Color(0xFF17181C)
    val CardHigh = Color(0xFF1E2026)
    val Line = Color(0xFF24262C)
    val Track = Color(0xFF3A3C44)
    val Fg = Color(0xFFF2F3F5)
    val Fg2 = Color(0xFF9A9DA6)
    val Fg3 = Color(0xFF7E828C)
    val Accent = Color(0xFF7FB2FF)
    val AccentSoft = Color(0x297FB2FF)
    val AccentInk = Color(0xFF0A0B0D)
    val Blocked = Color(0xFFFF6B7D)
    val Nav = Color(0xEB1E2026)
    val NavIcon = Color(0xFF8A8E98)
}

internal val AppColors = darkColorScheme(
    primary = Palette.Accent,
    onPrimary = Palette.AccentInk,
    primaryContainer = Color(0xFF22324A),
    onPrimaryContainer = Palette.Accent,
    secondary = Palette.Accent,
    onSecondary = Palette.AccentInk,
    secondaryContainer = Color(0xFF22324A),
    onSecondaryContainer = Palette.Accent,
    background = Palette.Bg,
    onBackground = Palette.Fg,
    surface = Palette.Bg,
    onSurface = Palette.Fg,
    surfaceVariant = Palette.Card,
    onSurfaceVariant = Palette.Fg2,
    surfaceContainerLowest = Palette.Bg,
    surfaceContainerLow = Palette.Card,
    surfaceContainer = Palette.Card,
    surfaceContainerHigh = Palette.CardHigh,
    surfaceContainerHighest = Palette.Card,
    outline = Palette.Track,
    outlineVariant = Palette.Line,
    error = Palette.Blocked,
    onError = Palette.AccentInk,
    errorContainer = Color(0xFF3A1C22),
    onErrorContainer = Color(0xFFFFD9DE),
)

val CardShape = RoundedCornerShape(20.dp)

// On tablets content stays this wide and sits in the middle, so rows do not stretch across the screen
val MaxContentWidth = 560.dp

// From this width Home and Activity show two columns side by side
val WideLayoutWidth = 840.dp
val MaxWideContentWidth = 1040.dp

// Size of the app window in dp
@Composable
fun windowWidth(): Dp = LocalConfiguration.current.screenWidthDp.dp

@Composable
fun windowHeight(): Dp = LocalConfiguration.current.screenHeightDp.dp

// Side padding that keeps content at most [max] wide and at least [min] away from the edges
fun sidePadding(available: Dp, max: Dp = MaxContentWidth, min: Dp = 20.dp): Dp =
    maxOf(min, (available - max) / 2)

fun Modifier.appCard(): Modifier = clip(CardShape).background(Palette.Card)

@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = appSwitchColors(),
    )
}

@Composable
fun appSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Color.White,
    checkedTrackColor = Palette.Accent,
    checkedBorderColor = Palette.Accent,
    uncheckedThumbColor = Palette.Fg2,
    uncheckedTrackColor = Palette.Track,
    uncheckedBorderColor = Palette.Track,
)

// Big bold title of a main tab
@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = Palette.Fg,
        fontSize = 24.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.3).sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
        color = Palette.Fg2,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
    )
}

// Back arrow and title of a page opened from a tab
@Composable
fun PageHeader(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.back), tint = Palette.Fg)
        }
        Text(
            title,
            modifier = Modifier.weight(1f),
            color = Palette.Fg,
            fontSize = 20.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        action?.invoke()
    }
}

// A page with a back header around content that is not built for one
@Composable
fun SubPage(title: String, onBack: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier
                .fillMaxHeight()
                .widthIn(max = MaxContentWidth + 32.dp)
                .fillMaxWidth()
        ) {
            PageHeader(title, onBack)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .navigationBarsPadding(),
                content = content,
            )
        }
    }
}

@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Card)
            .padding(start = 14.dp, end = if (trailing != null) 2.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painterResource(R.drawable.ic_search),
            contentDescription = null,
            tint = Palette.Fg3,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = Palette.Fg3, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = TextStyle(color = Palette.Fg, fontSize = 14.sp),
                cursorBrush = SolidColor(Palette.Accent),
            )
        }
        trailing?.invoke()
    }
}

// Thin line between the rows of a card
@Composable
fun ListDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier = modifier.padding(horizontal = 16.dp), color = Palette.Line)
}

// The arrow at the end of a row that opens something
@Composable
fun Chevron(modifier: Modifier = Modifier) {
    Icon(
        painterResource(R.drawable.ic_chevron),
        contentDescription = null,
        tint = Palette.Fg3,
        modifier = modifier.size(20.dp),
    )
}
