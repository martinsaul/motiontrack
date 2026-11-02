package io.saul.motiontrack.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.saul.motiontrack.fusion.Sample
import androidx.compose.foundation.background
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Dark-surface chart tokens (validated categorical slots, dark mode). */
object Viz {
    val surface = Color(0xFF1A1A19)
    val textPrimary = Color(0xFFFFFFFF)
    val textSecondary = Color(0xFFC3C2B7)
    val textMuted = Color(0xFF8A8980)
    val grid = Color(0xFF2E2E2C)
    val axis = Color(0xFF52514E)
    val blue = Color(0xFF3987E5)
    val orange = Color(0xFFD95926)
    val aqua = Color(0xFF199E70)
    val yellow = Color(0xFFC98500)
    val magenta = Color(0xFFD55181)
    val green = Color(0xFF008300)
}

enum class YScale { ZERO_BASED, SYMMETRIC }

class Series(
    val name: String,
    val color: Color,
    /** Value in display units; NaN = no point. */
    val value: (Sample) -> Double,
    /** Draw as discrete dots (sparse raw measurements) instead of a line. */
    val dots: Boolean = false,
)

/** Plot-area gutter shared by every stacked chart so their time axes line up for scrubbing. */
val ChartGutter: Dp = 44.dp

/**
 * One panel of the stacked small-multiples graph. Header carries the title and a legend whose
 * entries show the live value (or the scrubbed value while the user is dragging).
 */
@Composable
fun TimeChart(
    title: String,
    unit: String,
    series: List<Series>,
    history: List<Sample>,
    revision: Int,
    tStart: Double,
    tEnd: Double,
    scrubT: Double?,
    yScale: YScale,
    minSpan: Double,
    format: (Double) -> String,
    showTimeAxis: Boolean,
    modifier: Modifier = Modifier,
) {
    val readoutIdx = if (history.isEmpty()) -1 else if (scrubT != null) nearestIndex(history, scrubT) else history.size - 1
    val readout = if (readoutIdx < 0) null else history[readoutIdx]
    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "$title ($unit)",
                color = Viz.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            series.forEach { s ->
                LegendEntry(s, if (readoutIdx < 0) null else legendValue(history, readoutIdx, s), format)
            }
        }
        val measurer = rememberTextMeasurer()
        val labelStyle = TextStyle(color = Viz.textMuted, fontSize = 10.sp)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            @Suppress("UNUSED_EXPRESSION") revision // read so the canvas redraws on every new sample
            val gutter = ChartGutter.toPx()
            val bottomPad = if (showTimeAxis) 16.dp.toPx() else 4.dp.toPx()
            val plotW = size.width - gutter
            val plotH = size.height - bottomPad - 4.dp.toPx()
            val top = 4.dp.toPx()
            if (plotW <= 0 || plotH <= 0) return@Canvas

            val i0 = lowerBound(history, tStart)
            val i1 = history.size

            // Y range from visible data.
            var lo = Double.POSITIVE_INFINITY; var hi = Double.NEGATIVE_INFINITY
            for (i in i0 until i1) for (s in series) {
                val v = s.value(history[i])
                if (v.isFinite()) { lo = min(lo, v); hi = max(hi, v) }
            }
            if (!lo.isFinite()) { lo = 0.0; hi = minSpan }
            when (yScale) {
                YScale.ZERO_BASED -> { lo = min(0.0, lo); hi = max(hi, lo + minSpan) }
                YScale.SYMMETRIC -> { val m = max(max(abs(lo), abs(hi)), minSpan / 2); lo = -m; hi = m }
            }
            val step = niceStep((hi - lo) / 3)
            lo = floor(lo / step) * step
            hi = ceil(hi / step) * step
            if (hi - lo < 1e-9) hi = lo + step

            val span = max(tEnd - tStart, 1e-6)
            fun x(t: Double) = (gutter + (t - tStart) / span * plotW).toFloat()
            fun y(v: Double) = (top + (hi - v) / (hi - lo) * plotH).toFloat()

            // Recessive grid + y labels.
            var g = lo
            while (g <= hi + step * 0.01) {
                val yy = y(g)
                drawLine(if (abs(g) < step * 1e-3) Viz.axis else Viz.grid, Offset(gutter, yy), Offset(size.width, yy), 1.dp.toPx())
                val label = measurer.measure(format(g), labelStyle)
                drawText(label, topLeft = Offset(gutter - label.size.width - 6.dp.toPx(), yy - label.size.height / 2f))
                g += step
            }

            if (showTimeAxis) {
                val tStep = clockStep(span / 4)
                var tt = ceil(tStart / tStep) * tStep
                while (tt <= tEnd) {
                    val label = measurer.measure(formatClock(tt), labelStyle)
                    val xx = x(tt) - label.size.width / 2f
                    if (xx >= gutter - 4 && xx + label.size.width <= size.width + 4) {
                        drawText(label, topLeft = Offset(xx, top + plotH + 3.dp.toPx()))
                    }
                    tt += tStep
                }
            }

            clipRect(gutter, top, size.width, top + plotH) {
                // Raw dots go underneath so the fused line stays readable where they crowd together.
                for (s in series.sortedBy { !it.dots }) {
                    if (s.dots) {
                        val r = 2.5.dp.toPx()
                        for (i in i0 until i1) {
                            val v = s.value(history[i])
                            if (v.isFinite()) drawCircle(s.color, r, Offset(x(history[i].t), y(v)))
                        }
                    } else {
                        drawPath(
                            linePath(history, i0, i1, s.value, plotW.toInt(), ::x, ::y),
                            s.color,
                            style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                        )
                    }
                }
            }

            if (scrubT != null) {
                val xx = x(scrubT)
                drawLine(
                    Viz.textSecondary, Offset(xx, top), Offset(xx, top + plotH), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx())),
                )
                if (readout != null) for (s in series) {
                    val v = s.value(readout)
                    if (v.isFinite()) {
                        val c = Offset(x(readout.t), y(v))
                        drawCircle(Viz.surface, 5.dp.toPx(), c)
                        drawCircle(s.color, 4.dp.toPx(), c)
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendEntry(s: Series, value: Double?, format: (Double) -> String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
        Box(
            Modifier.size(width = if (s.dots) 8.dp else 12.dp, height = if (s.dots) 8.dp else 3.dp)
                .clip(if (s.dots) CircleShape else RoundedCornerShape(2.dp))
                .background(s.color)
        )
        Spacer(Modifier.width(4.dp))
        Text(s.name, color = Viz.textSecondary, fontSize = 11.sp)
        Spacer(Modifier.width(4.dp))
        Text(
            if (value == null || !value.isFinite()) "--" else format(value),
            color = Viz.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * Builds the line, decimating to min/max per pixel column when there are more samples than pixels
 * so peaks survive at any zoom.
 */
private fun linePath(
    h: List<Sample>, i0: Int, i1: Int, value: (Sample) -> Double, columns: Int,
    x: (Double) -> Float, y: (Double) -> Float,
): Path {
    val path = Path()
    val n = i1 - i0
    if (n <= 0) return path
    var started = false
    fun add(t: Double, v: Double) {
        if (!v.isFinite()) return
        if (!started) { path.moveTo(x(t), y(v)); started = true } else path.lineTo(x(t), y(v))
    }
    if (n <= columns * 2) {
        for (i in i0 until i1) add(h[i].t, value(h[i]))
        return path
    }
    val perBucket = n.toDouble() / columns
    var b = 0
    while (b < columns) {
        val s = i0 + (b * perBucket).toInt()
        val e = min(i1, i0 + ((b + 1) * perBucket).toInt())
        if (s < e) {
            var minI = s; var maxI = s
            for (i in s until e) {
                val v = value(h[i])
                if (v < value(h[minI])) minI = i
                if (v > value(h[maxI])) maxI = i
            }
            val first = min(minI, maxI); val second = max(minI, maxI)
            add(h[first].t, value(h[first]))
            if (second != first) add(h[second].t, value(h[second]))
        }
        b++
    }
    return path
}

/** Sparse (dot) series show the most recent measurement within the last ~2 s. */
private fun legendValue(h: List<Sample>, idx: Int, s: Series): Double {
    if (!s.dots) return s.value(h[idx])
    val t = h[idx].t
    var i = idx
    while (i >= 0 && t - h[i].t <= 2.0) {
        val v = s.value(h[i])
        if (v.isFinite()) return v
        i--
    }
    return Double.NaN
}

/** First index with t >= [t]. */
fun lowerBound(h: List<Sample>, t: Double): Int {
    var lo = 0; var hi = h.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (h[mid].t < t) lo = mid + 1 else hi = mid
    }
    return lo
}

fun nearestIndex(h: List<Sample>, t: Double): Int {
    val i = lowerBound(h, t).coerceIn(0, h.size - 1)
    return if (i > 0 && abs(h[i - 1].t - t) < abs(h[i].t - t)) i - 1 else i
}

fun niceStep(raw: Double): Double {
    if (raw <= 0 || !raw.isFinite()) return 1.0
    val mag = 10.0.pow(floor(log10(raw)))
    val f = raw / mag
    val nf = when { f <= 1 -> 1.0; f <= 2 -> 2.0; f <= 2.5 -> 2.5; f <= 5 -> 5.0; else -> 10.0 }
    return nf * mag
}

fun clockStep(raw: Double): Double =
    listOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0, 900.0, 1800.0, 3600.0, 7200.0)
        .firstOrNull { it >= raw } ?: 7200.0

fun formatClock(t: Double): String {
    val s = t.toInt()
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
