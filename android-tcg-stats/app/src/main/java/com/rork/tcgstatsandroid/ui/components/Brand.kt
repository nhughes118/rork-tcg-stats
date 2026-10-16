package com.rork.tcgstatsandroid.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.tcgstatsandroid.ui.theme.Palette

/** Large decorative ball used behind the canvas. */
@Composable
fun BallMotif(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val s = size.minDimension
        val c = center
        drawCircle(Palette.Red, radius = s / 2, center = c)
        drawRect(Palette.Canvas, topLeft = Offset(0f, c.y - s * 0.04f), size = Size(size.width, s * 0.08f))
        drawCircle(Palette.Canvas, radius = s * 0.17f, center = c)
        drawCircle(Palette.Red, radius = s * 0.11f - s * 0.025f, center = c, style = Stroke(width = s * 0.05f))
    }
}

/** Small app logo: red/white ball. */
@Composable
fun LogoMark(modifier: Modifier = Modifier, size: Dp = 34.dp) {
    Canvas(modifier.size(size).shadow(4.dp, CircleShape)) {
        val s = this.size.minDimension
        val r = s / 2
        drawCircle(Color.White, radius = r)
        drawArc(Palette.Red, startAngle = 180f, sweepAngle = 180f, useCenter = true)
        drawRect(Palette.Ink, topLeft = Offset(0f, r - s * 0.04f), size = Size(s, s * 0.08f))
        drawCircle(Color.White, radius = s * 0.18f)
        drawCircle(Palette.Ink, radius = s * 0.18f - s * 0.035f, style = Stroke(width = s * 0.07f))
        drawCircle(Palette.Ink, radius = r - s * 0.03f, style = Stroke(width = s * 0.06f))
    }
}

@Composable
fun BrandHeader(modifier: Modifier = Modifier, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        LogoMark()
        Column {
            Text("TCG Stats", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
            Text("Scan. Discover. Play.", fontSize = 12.sp, color = Palette.Muted)
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }
}
