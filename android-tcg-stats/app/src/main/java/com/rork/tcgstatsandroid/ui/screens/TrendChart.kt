package com.rork.tcgstatsandroid.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rork.tcgstatsandroid.data.CardText
import com.rork.tcgstatsandroid.data.TrendPoint
import com.rork.tcgstatsandroid.ui.components.Dates
import com.rork.tcgstatsandroid.ui.theme.Palette
import kotlin.math.ceil
import kotlin.math.max

/** Weekly usage area chart with a draw-in animation. */
@Composable
fun TrendChart(points: List<TrendPoint>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) {
        reveal.animateTo(1f, tween(800, easing = FastOutSlowInEasing))
    }
    val peak = (points.maxOfOrNull { it.usage } ?: 0.0) * 100
    val yMax = max(5.0, ceil(peak * 1.25 / 5) * 5)
    val step = when {
        yMax <= 20 -> 5.0
        yMax <= 50 -> 10.0
        else -> 25.0
    }
    val labelStyle = TextStyle(fontSize = 10.sp, color = Palette.Muted)
    val summary = points.lastOrNull()?.let { "Latest week ${CardText.percent(it.usage)} usage" } ?: "Usage trend"

    Canvas(modifier.semantics { contentDescription = summary }) {
        val left = 34.dp.toPx()
        val right = 8.dp.toPx()
        val top = 6.dp.toPx()
        val bottom = 22.dp.toPx()
        val plotW = size.width - left - right
        val plotH = size.height - top - bottom
        fun y(v: Double) = top + plotH * (1f - (v / yMax).toFloat())

        var v = 0.0
        while (v <= yMax + 0.001) {
            val yy = y(v)
            drawLine(Palette.Hairline, Offset(left, yy), Offset(left + plotW, yy), strokeWidth = 1.dp.toPx())
            val layout = measurer.measure("${v.toInt()}%", labelStyle)
            drawText(layout, topLeft = Offset(0f, yy - layout.size.height / 2f))
            v += step
        }
        if (points.isEmpty()) return@Canvas

        val t0 = points.first().weekStart
        val span = max(1L, points.last().weekStart - t0).toFloat()
        val pts = points.map { p -> Offset(left + plotW * ((p.weekStart - t0) / span), y(p.usage * 100)) }

        val line = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]
                val b = pts[i]
                val mx = (a.x + b.x) / 2
                cubicTo(mx, a.y, mx, b.y, b.x, b.y)
            }
        }
        val baseline = top + plotH
        val area = Path().apply {
            addPath(line)
            lineTo(pts.last().x, baseline)
            lineTo(pts.first().x, baseline)
            close()
        }

        clipRect(right = left + plotW * reveal.value + 8.dp.toPx()) {
            drawPath(area, Brush.verticalGradient(listOf(Palette.Red.copy(alpha = 0.22f), Palette.Red.copy(alpha = 0.02f)), startY = top, endY = baseline))
            drawPath(line, Palette.Red, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            pts.forEachIndexed { i, p ->
                val isLast = i == pts.lastIndex
                if (isLast) drawCircle(Color.White, radius = 6.dp.toPx(), center = p)
                drawCircle(Palette.Red, radius = if (isLast) 4.5.dp.toPx() else 3.dp.toPx(), center = p)
            }
        }

        val stride = if (points.size > 6) 2 else 1
        points.forEachIndexed { i, p ->
            if ((points.size - 1 - i) % stride != 0) return@forEachIndexed
            val layout = measurer.measure(Dates.short(p.weekStart), labelStyle)
            val x = (pts[i].x - layout.size.width / 2f).coerceIn(left - 4.dp.toPx(), size.width - layout.size.width.toFloat())
            drawText(layout, topLeft = Offset(x, baseline + 6.dp.toPx()))
        }
    }
}
