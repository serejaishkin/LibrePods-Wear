package me.kavishdevar.librepods.wear.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Pulsing animation effect for status indicators
 * Similar to the pulsing bell animation in the alarm simulator
 */
@Composable
fun PulsingIndicator(
    modifier: Modifier = Modifier,
    color: Color = LibrePodsColors.Blue600,
    targetScale: Float = 1.2f,
    animationDuration: Int = 1000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = targetScale,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Box(
        modifier = modifier
            .scale(scale)
            .background(
                color.copy(alpha = alpha),
                CircleShape
            )
    )
}

/**
 * Bouncing animation for interactive elements
 * Similar to the bouncing bell icon in the alarm simulator
 */
@Composable
fun BouncingIndicator(
    modifier: Modifier = Modifier,
    animationDuration: Int = 800
) {
    val infiniteTransition = rememberInfiniteTransition(label = "bounce")
    val offsetY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = -10f,
        animationSpec = infiniteRepeatable(
            animation = tween(animationDuration, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "offsetY"
    )

    Box(modifier = modifier.offset(y = offsetY.dp)) {
        // Content will be wrapped in this bouncing box
    }
}

/**
 * Fade-in animation for screen transitions
 * Similar to the fade-in effects in the alarm simulator
 */
@Composable
fun FadeInAnimation(
    content: @Composable () -> Unit
) {
    val animatedAlpha by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
        label = "fade"
    )

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.alpha(animatedAlpha)
    ) {
        content()
    }
}

/**
 * Scale-in animation for content appearance
 * Similar to the zoom-in effects in the alarm simulator
 */
@Composable
fun ScaleInAnimation(
    content: @Composable () -> Unit
) {
    val animatedScale by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
        label = "scale"
    )

    androidx.compose.foundation.layout.Box(
        modifier = Modifier.scale(animatedScale)
    ) {
        content()
    }
}

/**
 * Status indicator with pulsing animation
 * Useful for connection status, battery alerts, etc.
 */
@Composable
fun PulsingStatusDot(
    isActive: Boolean,
    activeColor: Color = LibrePodsColors.Emerald500,
    inactiveColor: Color = LibrePodsColors.Amber500,
    modifier: Modifier = Modifier
) {
    if (isActive) {
        PulsingIndicator(
            modifier = modifier.size(8.dp),
            color = activeColor
        )
    } else {
        Box(
            modifier = modifier
                .size(8.dp)
                .background(inactiveColor, CircleShape)
        )
    }
}