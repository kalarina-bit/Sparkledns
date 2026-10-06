package cc.skysparkle.sparkledns

import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

// One set of timings for the whole app, based on Material 3 emphasized motion
object Motion {
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    val enterFade = tween<Float>(durationMillis = 280, delayMillis = 60, easing = Emphasized)
    val exitFade = tween<Float>(durationMillis = 140, easing = EmphasizedAccelerate)
    val slide: FiniteAnimationSpec<IntOffset> = tween(durationMillis = 380, easing = Emphasized)
    val size: FiniteAnimationSpec<IntSize> = tween(durationMillis = 320, easing = Emphasized)
    val color = tween<androidx.compose.ui.graphics.Color>(durationMillis = 400, easing = Emphasized)
    val rotation = tween<Float>(durationMillis = 260, easing = Emphasized)

    val expandIn = fadeIn(enterFade) + expandVertically(size)
    val collapseOut = fadeOut(exitFade) + shrinkVertically(size)

    fun sizeTransform() = SizeTransform(clip = false) { _, _ -> size }
}
