package com.rork.tcgstatsandroid.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rork.tcgstatsandroid.ui.theme.Palette

/** White rounded tile with a thin foil edge and soft layered shadow. */
fun Modifier.cardSurface(radius: Dp = 20.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .shadow(10.dp, shape, clip = false, ambientColor = Palette.ShadowTint.copy(alpha = 0.25f), spotColor = Palette.ShadowTint.copy(alpha = 0.22f))
        .background(Palette.Surface, shape)
        .border(
            1.dp,
            Brush.linearGradient(listOf(Palette.Gold.copy(alpha = 0.35f), Color.White.copy(alpha = 0.2f), Palette.Hairline)),
            shape,
        )
}

/** Springy press feedback without a ripple (mirrors the iOS PressableStyle). */
@Composable
fun Modifier.pressable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.97f else 1f,
        spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            alpha = if (pressed) 0.85f else 1f
        }
        .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
}

/** Lets a child grow leftwards under a neighbour without moving the layout edge. */
fun Modifier.extendStart(amount: Dp): Modifier = layout { measurable, constraints ->
    val px = amount.roundToPx()
    val maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + px else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = constraints.minWidth, maxWidth = maxWidth))
    layout((placeable.width - px).coerceAtLeast(0), placeable.height) { placeable.place(-px, 0) }
}

/** Cream canvas with the faint ball motif in the top-right corner. */
@Composable
fun CanvasBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize().background(Palette.Canvas)) {
        BallMotif(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 90.dp, y = (-70).dp)
                .size(260.dp)
                .alpha(0.22f)
        )
        content()
    }
}
