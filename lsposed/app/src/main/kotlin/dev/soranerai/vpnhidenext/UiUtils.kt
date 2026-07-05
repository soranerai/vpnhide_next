package dev.soranerai.vpnhidenext

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException

/**
 * A shimmer effect modifier that creates a "Telegram-style" animated background.
 * Perfect for skeleton loading states.
 */
fun Modifier.shimmer(shape: Shape = RectangleShape): Modifier =
    composed {
        val transition = rememberInfiniteTransition(label = "shimmer")
        val translateAnim =
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1000f,
                animationSpec =
                    infiniteRepeatable(
                        animation =
                            tween(
                                durationMillis = 1200,
                                easing = FastOutSlowInEasing,
                            ),
                        repeatMode = RepeatMode.Restart,
                    ),
                label = "shimmerTranslation",
            )

        val shimmerColors =
            listOf(
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            )

        val brush =
            Brush.linearGradient(
                colors = shimmerColors,
                start = Offset.Zero,
                end = Offset(x = translateAnim.value, y = translateAnim.value),
            )

        background(brush, shape)
    }

/**
 * A thin, stylish progress bar meant to be placed at the top of a screen
 * during asynchronous loads.
 */
@Composable
fun TopProgressBar(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (visible) {
        LinearProgressIndicator(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(3.dp),
            color = MaterialTheme.colorScheme.primary,
            trackColor = Color.Transparent,
        )
    } else {
        // Maintain layout stability
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(3.dp),
        )
    }
}

/**
 * A basic shimmer block with rounded corners.
 */
@Composable
fun ShimmerPlaceholder(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(50),
) {
    Box(
        modifier =
            modifier
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                    shape = shape,
                ).shimmer(shape),
    )
}

/**
 * Full-screen overlay that reacts to a held predictive-back gesture: while
 * the gesture is in progress the content shrinks, slides toward the swipe
 * edge and gains rounded corners — mirroring the system's Material
 * predictive-back animation — so the screen underneath (already composed,
 * just covered) is visibly uncovered instead of the overlay disappearing
 * abruptly. Releasing before completion snaps it back; completing it (or a
 * plain back-press pre-Android 13) calls [onBack].
 */
@Composable
fun PredictiveBackOverlay(
    visible: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    enter: EnterTransition = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
    exit: ExitTransition = slideOutHorizontally(targetOffsetX = { it }) + fadeOut(),
    content: @Composable () -> Unit,
) {
    var gestureProgress by remember { mutableFloatStateOf(0f) }

    PredictiveBackHandler(enabled = visible) { progress ->
        try {
            progress.collect { event -> gestureProgress = event.progress }
            gestureProgress = 0f
            onBack()
        } catch (e: CancellationException) {
            gestureProgress = 0f
        }
    }

    AnimatedVisibility(
        visible = visible,
        enter = enter,
        exit = exit,
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier.fillMaxSize().graphicsLayer {
                    val eased = gestureProgress
                    scaleX = 1f - eased * 0.1f
                    scaleY = 1f - eased * 0.1f
                    translationX = eased * size.width * 0.25f
                    clip = eased > 0f
                    shape = RoundedCornerShape(32.dp * eased)
                },
        ) {
            content()
        }
    }
}
