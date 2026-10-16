package com.rork.tcgstatsandroid.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.rork.tcgstatsandroid.data.CardCategory
import com.rork.tcgstatsandroid.data.CardIdentity
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.ui.theme.Palette

/** Card artwork from the Limitless CDN with a styled placeholder. */
@Composable
fun CardImage(card: CardIdentity?, modifier: Modifier = Modifier, large: Boolean = false, cornerRadius: Dp = 6.dp) {
    val shape = RoundedCornerShape(cornerRadius)
    val context = LocalContext.current
    val url = card?.imageUrl(large)
    val request = remember(url) { ImageRequest.Builder(context).data(url).crossfade(true).build() }
    Box(
        modifier
            .aspectRatio(63f / 88f)
            .clip(shape)
            .background(Palette.CardBack)
            .border(0.5.dp, Color.Black.copy(alpha = 0.08f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when (card?.category) {
                CardCategory.Trainer -> Icons.Filled.Person
                CardCategory.Energy -> Icons.Filled.Bolt
                else -> Icons.Filled.AutoAwesome
            },
            contentDescription = null,
            tint = Palette.Muted.copy(alpha = 0.5f),
            modifier = Modifier.size(if (large) 40.dp else 14.dp),
        )
        if (url != null) {
            AsyncImage(
                model = request,
                contentDescription = card?.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** Deck archetype thumbnail built from Limitless Pokémon icons. */
@Composable
fun DeckIcon(icons: List<String>, modifier: Modifier = Modifier, size: Dp = 48.dp) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .size(size)
            .background(Brush.verticalGradient(listOf(Color(0xFFFFF3E6), Color(0xFFF3EBDD))), shape)
            .border(1.dp, Palette.Gold.copy(alpha = 0.3f), shape),
        contentAlignment = Alignment.Center,
    ) {
        val shown = icons.take(2)
        if (shown.isEmpty()) {
            Icon(Icons.Filled.Layers, contentDescription = null, tint = Palette.Muted.copy(alpha = 0.5f))
        } else {
            val iconSize = if (shown.size > 1) size * 0.6f else size * 0.8f
            Row(horizontalArrangement = Arrangement.spacedBy(-(size * 0.18f))) {
                shown.forEach { icon ->
                    AsyncImage(
                        model = "https://r2.limitlesstcg.net/pokemon/gen9/$icon.png",
                        contentDescription = null,
                        filterQuality = FilterQuality.None,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}

/** Animated circular usage gauge. */
@Composable
fun UsageRing(
    value: Double,
    modifier: Modifier = Modifier,
    lineWidth: Dp = 12.dp,
    caption: String? = "of decks",
    fontSize: TextUnit = 26.sp,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(value) {
        progress.animateTo(value.toFloat(), spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessVeryLow))
    }
    Box(
        modifier.semantics { contentDescription = "Used in ${CardText.percent(value)} of decks" },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = lineWidth.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(Palette.Hairline, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            rotate(-90f) {
                drawArc(
                    Brush.sweepGradient(listOf(Palette.Red.copy(alpha = 0.75f), Palette.Red)),
                    0f,
                    360f * progress.value.coerceIn(0.005f, 1f),
                    false,
                    Offset(inset, inset),
                    arcSize,
                    style = Stroke(stroke, cap = StrokeCap.Round),
                )
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(lineWidth + 4.dp)) {
            Text(
                CardText.percent(value),
                fontSize = fontSize,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.SansSerif,
                color = Palette.Ink,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
            if (caption != null) Text(caption, fontSize = 12.sp, color = Palette.Muted)
        }
    }
}

@Composable
fun PlacingBadge(placing: Int?, modifier: Modifier = Modifier) {
    Text(
        CardText.ordinal(placing),
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        color = if (placing != null && placing <= 4) Palette.placeColor(placing) else Palette.Muted,
        modifier = modifier.width(44.dp),
    )
}

@Composable
fun TrophyIcon(placing: Int?, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Box(modifier.width(36.dp), contentAlignment = Alignment.Center) {
        Icon(
            if ((placing ?: 99) <= 4) Icons.Filled.EmojiEvents else Icons.Filled.MilitaryTech,
            contentDescription = null,
            tint = Palette.placeColor(placing),
            modifier = Modifier.size(size),
        )
    }
}
