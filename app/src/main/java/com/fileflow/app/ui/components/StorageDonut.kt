package com.fileflow.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

data class DonutSegment(
    val value: Float,
    val color: Color,
)

/**
 * A ring chart for storage. Arcs are animated from zero so the card settles
 * into place instead of snapping, and small gaps are avoided by drawing each
 * arc with a butt cap — a rounded cap on a 0.3% slice looks like a bug.
 */
@Composable
fun StorageDonut(
    segments: List<DonutSegment>,
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
    thickness: Dp = 12.dp,
    trackColor: Color = Color.Unspecified,
    gapDegrees: Float = 1.5f,
    content: @Composable () -> Unit = {},
) {
    val total = segments.sumOf { it.value.toDouble() }.toFloat()
    val progress by animateFloatAsState(
        targetValue = if (total > 0f) 1f else 0f,
        animationSpec = tween(durationMillis = 650),
        label = "donut",
    )

    Box(
        modifier = modifier.size(diameter),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.size(diameter)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(inset, inset)

            if (trackColor != Color.Unspecified && total <= 0f) {
                drawArc(
                    color = trackColor,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke),
                )
                return@Canvas
            }

            if (total <= 0f) return@Canvas

            var start = -90f
            segments.forEach { segment ->
                if (segment.value <= 0f) return@forEach
                val sweep = (segment.value / total) * 360f * progress
                if (sweep <= 0f) return@forEach
                val drawn = (sweep - gapDegrees).coerceAtLeast(0f)
                drawArc(
                    color = segment.color,
                    startAngle = start + gapDegrees / 2f,
                    sweepAngle = drawn,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Butt),
                )
                start += sweep
            }
        }
        content()
    }
}
