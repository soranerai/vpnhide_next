package dev.soranerai.vpnhidenext

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

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
