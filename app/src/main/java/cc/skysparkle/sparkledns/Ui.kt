package cc.skysparkle.sparkledns

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.NumberFormat

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
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = tint.copy(alpha = 0.10f)),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (action != null) 6.dp else 14.dp, top = 12.dp, bottom = 12.dp),
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

// Polls the in-memory query log once a second while the screen is visible
@Composable
fun rememberLiveStats(): QueryStats {
    var stats by remember { mutableStateOf(QueryLog.stats()) }
    LaunchedEffect(Unit) {
        while (true) {
            stats = QueryLog.stats()
            delay(1000)
        }
    }
    return stats
}

fun formatCount(value: Long): String = NumberFormat.getIntegerInstance().format(value)

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
