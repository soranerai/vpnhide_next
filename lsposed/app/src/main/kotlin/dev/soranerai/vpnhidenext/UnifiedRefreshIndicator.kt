package dev.soranerai.vpnhidenext

import androidx.compose.animation.*
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex

/** Shared pull-to-refresh indicator used by every refreshable screen. */
@Composable
internal fun UnifiedRefreshIndicator(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.zIndex(100f),
        enter = fadeIn() + expandVertically(expandFrom = Alignment.Top) + scaleIn(transformOrigin = TransformOrigin(0.5f, 0f)),
        exit = fadeOut() + shrinkVertically(shrinkTowards = Alignment.Top) + scaleOut(transformOrigin = TransformOrigin(0.5f, 0f)),
    ) {
        Surface(shape = CircleShape, tonalElevation = 8.dp) {
            CircularProgressIndicator(
                modifier = Modifier.padding(8.dp).size(28.dp),
                strokeWidth = 3.dp,
            )
        }
    }
}
