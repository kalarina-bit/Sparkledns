package cc.skysparkle.sparkledns

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.text.NumberFormat
import java.util.Locale

val CachedColor = Color(0xFF7FD1AE)
val WarningColor = Color(0xFFFFB86B)

// Icon on a soft circle of its own color
@Composable
fun IconBadge(@DrawableRes icon: Int, tint: Color = MaterialTheme.colorScheme.primary, size: Dp = 40.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

@Composable
fun SectionHeader(
    title: String,
    @DrawableRes icon: Int? = null,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

@Composable
fun HintCard(
    text: String,
    @DrawableRes icon: Int = R.drawable.ic_tab_info,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    action: (@Composable () -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier.padding(
                start = 14.dp,
                end = when {
                    onDismiss != null -> 4.dp
                    action != null -> 6.dp
                    else -> 14.dp
                },
                top = if (onDismiss != null) 6.dp else 12.dp,
                bottom = if (onDismiss != null) 6.dp else 12.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            action?.invoke()
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.dismiss),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun StatColumn(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        // Numbers roll up when they change
        AnimatedContent(
            targetState = value,
            transitionSpec = {
                (slideInVertically(Motion.slide) { it / 2 } + fadeIn(Motion.enterFade)) togetherWith
                    (slideOutVertically(Motion.slide) { -it / 2 } + fadeOut(Motion.exitFade)) using
                    Motion.sizeTransform()
            },
            label = "stat",
        ) { shown ->
            Text(shown, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun StatDivider() {
    Box(
        Modifier
            .size(width = 1.dp, height = 32.dp)
            .background(MaterialTheme.colorScheme.outlineVariant)
    )
}

// Runs [onChange] when the query log changes, at most once a second and only while the screen
// is started: nothing runs while the app sits in the background
@Composable
fun OnQueryLogChange(onChange: () -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by rememberUpdatedState(onChange)
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            QueryLog.changes().collect { current() }
        }
    }
}

@Composable
fun rememberLiveStats(): QueryStats {
    var stats by remember { mutableStateOf(QueryLog.stats()) }
    OnQueryLogChange { stats = QueryLog.stats() }
    return stats
}

// Called from composition only, i.e. on the main thread; rebuilt when the locale changes
private var countFormat: Pair<Locale, NumberFormat>? = null

fun formatCount(value: Long): String {
    val locale = Locale.getDefault()
    val format = countFormat?.takeIf { it.first == locale }?.second
        ?: NumberFormat.getIntegerInstance(locale).also { countFormat = locale to it }
    return format.format(value)
}

fun cachePercent(stats: QueryStats): String =
    "${if (stats.total == 0L) 0 else stats.cached * 100 / stats.total}%"

// Rows of one visual group share a background; only the outer corners are rounded
fun groupedShape(first: Boolean, last: Boolean, radius: Dp = 16.dp) = RoundedCornerShape(
    topStart = if (first) radius else 0.dp,
    topEnd = if (first) radius else 0.dp,
    bottomStart = if (last) radius else 0.dp,
    bottomEnd = if (last) radius else 0.dp,
)

val groupedBackground: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest

// Short numbers for stat tiles: 812, 7.27k, 48.1k, 312k, 1.2M (decimal separator of the locale)
fun formatCompact(value: Long): String {
    val locale = Locale.getDefault()
    return when {
        value < 1_000 -> formatCount(value)
        value < 10_000 -> String.format(locale, "%.2fk", value / 1_000.0)
        value < 100_000 -> String.format(locale, "%.1fk", value / 1_000.0)
        value < 1_000_000 -> String.format(locale, "%.0fk", value / 1_000.0)
        else -> String.format(locale, "%.1fM", value / 1_000_000.0)
    }
}

@Composable
fun EmptyState(icon: Int, text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, MaterialTheme.colorScheme.onSurfaceVariant, size = 64.dp)
        Spacer(Modifier.height(12.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

enum class StatsPeriod(val hours: Int, @StringRes val label: Int) {
    DAY(24, R.string.period_24h),
    WEEK(24 * 7, R.string.period_7d),
}

// Switches between the last 24 hours and the last 7 days
@Composable
fun PeriodChip(period: StatsPeriod, onChange: (StatsPeriod) -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, Palette.Track, shape)
            .clickable { onChange(if (period == StatsPeriod.DAY) StatsPeriod.WEEK else StatsPeriod.DAY) }
            .heightIn(min = 36.dp)
            .padding(start = 14.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(period.label), color = Palette.Fg, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(4.dp))
        Icon(painterResource(R.drawable.ic_expand), contentDescription = null, tint = Palette.Fg2, modifier = Modifier.size(16.dp))
    }
}
