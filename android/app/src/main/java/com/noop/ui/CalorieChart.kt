package com.noop.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - The plots one day's energy is read from
//
// Every plot on a screen is handed the same [CalorieChartState], so they share one x-window and one
// cursor: zooming or scrubbing on any of them moves all of them, and a reading taken from two plots
// is a reading taken at one instant.

/** A wall-clock second and the value recorded at it. */
internal typealias CaloriePoint = Pair<Long, Double>

/** The tightest window a plot will zoom to. Ten minutes is also the span a cursor reading covers. */
private const val MIN_SPAN_S = 600L

/** A break longer than this leaves a hole rather than a line. */
private const val MAX_JOIN_S = 120L

/** Reserved on the left of every plot for the value labels. */
private val Y_AXIS_WIDTH = 40.dp

/**
 * The visible span and the cursor, shared by every plot drawn against one day.
 *
 * [bounds] is the whole day; [window] is the part of it on screen.
 */
internal class CalorieChartState(val bounds: LongRange) {

    var window by mutableStateOf(bounds)
        private set

    /** The instant the cursor names, or null when nothing is being read. */
    var cursorTs by mutableStateOf<Long?>(null)
        private set

    fun moveCursor(ts: Long?) {
        cursorTs = ts?.coerceIn(bounds.first, bounds.last)
    }

    fun zoomAndPan(scale: Float, anchorFraction: Float, panSeconds: Long) {
        var next = window
        if (scale != 1f) next = zoomedWindow(next, scale, anchorFraction, bounds, minSpan = MIN_SPAN_S)
        if (panSeconds != 0L) next = pannedWindow(next, panSeconds, bounds)
        window = next
    }

    fun reset() {
        window = bounds
        cursorTs = null
    }
}

@Composable
internal fun rememberCalorieChartState(bounds: LongRange): CalorieChartState =
    remember(bounds) { CalorieChartState(bounds) }

/**
 * One series over the shared window, scaled to the extremes it reaches inside it.
 *
 * Points are placed by timestamp rather than by index: index spacing would compress a dropout and
 * slide every later sample away from the clock beneath it. A break longer than two minutes leaves a
 * hole, so a stretch the strap did not record reads as absence rather than as a straight line.
 *
 * [timeAbove] and [timeBelow] place the cursor's clock time at the ends of the crosshair. A stack of
 * plots sharing one cursor labels only its outer edges, so the time is not repeated between them.
 */
@Composable
internal fun CaloriePlot(
    points: List<CaloriePoint>,
    color: Color,
    state: CalorieChartState,
    height: Dp,
    axLabel: String,
    formatAxis: (Double) -> String,
    timeAbove: Boolean = true,
    timeBelow: Boolean = true,
) {
    val axisPx = with(LocalDensity.current) { Y_AXIS_WIDTH.toPx() }
    val is24h = ClockPrefs.uses24Hour(LocalContext.current)
    val textTertiary = Palette.textTertiary
    val accent = Palette.accent
    val window = state.window

    val axisPaint = remember(textTertiary) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 22f
            textAlign = android.graphics.Paint.Align.RIGHT
            setColor(textTertiary.toArgb())
        }
    }
    // Centred on the crosshair rather than right-aligned in the gutter, so it names the column.
    val timePaint = remember(accent) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 22f
            textAlign = android.graphics.Paint.Align.CENTER
            setColor(accent.toArgb())
        }
    }
    val cursorPaint = remember(color) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = 24f
            textAlign = android.graphics.Paint.Align.RIGHT
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setColor(color.toArgb())
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
            .clearAndSetSemantics { contentDescription = axLabel }
            .pointerInput(state) { caloriePlotGestures(state, axisPx) },
    ) {
        // The line and its axis change only when the data or the window does, so they are cached
        // away from the cursor overlay, which is redrawn on every frame of a scrub.
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawWithCache {
                    val geometry = plotGeometry(size.width, size.height, axisPx, points, window)
                    val runs = geometry?.let { buildRuns(points, it) } ?: emptyList()
                    onDrawBehind {
                        if (geometry == null) return@onDrawBehind
                        for (run in runs) {
                            if (run.path != null) {
                                drawPath(
                                    path = run.path,
                                    color = color,
                                    style = Stroke(
                                        width = 2.5f,
                                        cap = StrokeCap.Round,
                                        join = StrokeJoin.Round,
                                    ),
                                )
                            } else if (run.dot != null) {
                                drawCircle(color = color, radius = 2.5f, center = run.dot)
                            }
                        }
                        drawContext.canvas.nativeCanvas.apply {
                            drawText(
                                formatAxis(geometry.hi),
                                geometry.chartLeft - 6f,
                                geometry.top + 18f,
                                axisPaint,
                            )
                            drawText(
                                formatAxis(geometry.lo),
                                geometry.chartLeft - 6f,
                                geometry.bottom,
                                axisPaint,
                            )
                        }
                    }
                },
        )
        Canvas(modifier = Modifier.matchParentSize()) {
            val at = state.cursorTs ?: return@Canvas
            val geometry = plotGeometry(size.width, size.height, axisPx, points, window)
                ?: return@Canvas
            if (at !in window) return@Canvas
            val x = geometry.xFor(at)
            drawLine(
                color = textTertiary,
                start = Offset(x, geometry.top),
                end = Offset(x, geometry.bottom),
                strokeWidth = 1.5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f)),
            )
            if (timeAbove || timeBelow) {
                val label = clockTimeLabel(at, is24h)
                val half = timePaint.measureText(label) / 2f
                val leftMost = geometry.chartLeft + half
                val labelX = x.coerceIn(leftMost, (size.width - half).coerceAtLeast(leftMost))
                drawContext.canvas.nativeCanvas.apply {
                    if (timeAbove) drawText(label, labelX, geometry.top + 16f, timePaint)
                    if (timeBelow) drawText(label, labelX, geometry.bottom, timePaint)
                }
            }
            val index = nearestPointIndex(points, at) ?: return@Canvas
            val (ts, value) = points[index]
            // Only where the cursor actually sits on the series: a dot dragged across a hole would
            // read as a measurement the minute never carried.
            if (abs(ts - at) > MAX_JOIN_S) return@Canvas
            val y = geometry.yFor(value)
            drawCircle(color = color, radius = 4.5f, center = Offset(x, y))
            drawContext.canvas.nativeCanvas.drawText(
                formatAxis(value),
                geometry.chartLeft - 6f,
                (y + 8f).coerceIn(geometry.top + 18f, geometry.bottom),
                cursorPaint,
            )
        }
    }
}

/**
 * Round wall-clock labels under the plots.
 *
 * One strip for every plot above it, so a label names the same column in all of them.
 */
@Composable
internal fun CalorieTimeAxis(state: CalorieChartState, zone: ZoneId) {
    val window = state.window
    val axisPx = with(LocalDensity.current) { Y_AXIS_WIDTH.toPx() }
    val ticks = remember(window, zone) { chartTimeTicks(window.first, window.last, zone) }
    val span = (window.last - window.first).coerceAtLeast(1L)

    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = {
            ticks.forEach { (_, label) ->
                Text(label, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
            }
        },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val width = constraints.maxWidth
        val height = placeables.maxOfOrNull { it.height } ?: 0
        val chartLeft = axisPx.roundToInt().coerceIn(0, width)
        val chartWidth = (width - chartLeft).coerceAtLeast(1)
        fun xOf(ts: Long, p: androidx.compose.ui.layout.Placeable): Int {
            val frac = (ts - window.first).toFloat() / span
            return (chartLeft + frac * chartWidth - p.width / 2f)
                .roundToInt()
                .coerceIn(0, (width - p.width).coerceAtLeast(0))
        }
        layout(width, height) {
            var lastRight = Int.MIN_VALUE
            ticks.forEachIndexed { i, (ts, _) ->
                val p = placeables[i]
                val x = xOf(ts, p)
                if (x > lastRight) {
                    p.place(x, 0)
                    lastRight = x + p.width + 8
                }
            }
        }
    }
}

// MARK: - Gestures

/**
 * One finger reads, two fingers move, a double tap starts over.
 *
 * A vertical-dominant drag is never consumed, so the page behind the plot keeps scrolling. Watching
 * the initial pass means a claimed gesture cancels the page's own handling of it rather than racing
 * it.
 */
private suspend fun PointerInputScope.caloriePlotGestures(state: CalorieChartState, axisPx: Float) {
    var lastTapAtMs = 0L
    var lastTapPos = Offset.Zero
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        fun cursorAt(x: Float) {
            val chartW = (size.width.toFloat() - axisPx).coerceAtLeast(1f)
            val frac = ((x - axisPx) / chartW).coerceIn(0f, 1f)
            val window = state.window
            state.moveCursor(window.first + (frac * (window.last - window.first)).toLong())
        }
        // Placed on touch-down rather than after the drag threshold, so a tap reads a point. Not
        // consumed: the page still owns the gesture until it proves to be horizontal.
        cursorAt(down.position.x)
        var reading = false
        var moving = false
        var ceded = false
        var moved = false
        var totalX = 0f
        var totalY = 0f
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.changes.none { it.pressed }) {
                val up = event.changes.first()
                if (!moved) {
                    val doubleTapped =
                        up.uptimeMillis - lastTapAtMs <= viewConfiguration.doubleTapTimeoutMillis &&
                            (up.position - lastTapPos).getDistance() <= viewConfiguration.touchSlop * 4
                    if (doubleTapped) {
                        state.reset()
                        lastTapAtMs = 0L
                    } else {
                        lastTapAtMs = up.uptimeMillis
                        lastTapPos = up.position
                    }
                }
                break
            }
            if (ceded) continue
            if (!moving) {
                if (event.changes.count { it.pressed } > 1) {
                    moving = true
                    reading = false
                } else if (!reading) {
                    val pan = event.calculatePan()
                    totalX += pan.x
                    totalY += pan.y
                    if (abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(totalX)) {
                        // The page's scroll owns this one, so the reading it started is withdrawn.
                        state.moveCursor(null)
                        ceded = true
                        moved = true
                        continue
                    }
                    if (abs(totalX) > viewConfiguration.touchSlop) {
                        reading = true
                        moved = true
                    }
                }
            }
            if (moving) {
                val width = size.width.toFloat().coerceAtLeast(1f)
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                val secPerPx = (state.window.last - state.window.first).toDouble() / width
                state.zoomAndPan(
                    scale = zoom,
                    anchorFraction = (event.calculateCentroid().x / width).coerceIn(0f, 1f),
                    panSeconds = (-pan.x * secPerPx).toLong(),
                )
                moved = true
            } else if (reading) {
                cursorAt(event.changes.first().position.x)
            } else {
                continue
            }
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        }
    }
}

// MARK: - Geometry

/** Where a plot puts its values, for one size and one window. */
private class PlotGeometry(
    val chartLeft: Float,
    val chartWidth: Float,
    val top: Float,
    val bottom: Float,
    val windowStart: Long,
    val span: Long,
    val lo: Double,
    val hi: Double,
) {
    fun xFor(ts: Long): Float = chartLeft + ((ts - windowStart).toFloat() / span) * chartWidth

    fun yFor(v: Double): Float {
        val range = (hi - lo).takeIf { it > 0.0 } ?: 1.0
        return bottom - (((v - lo) / range).toFloat().coerceIn(0f, 1f)) * (bottom - top)
    }
}

/** Null when there is nothing to draw: no room, or fewer than two points. */
private fun plotGeometry(
    width: Float,
    height: Float,
    axisPx: Float,
    points: List<CaloriePoint>,
    window: LongRange,
): PlotGeometry? {
    if (width <= axisPx || height <= 0f || points.size < 2) return null
    val visible = points.filter { it.first in window }.ifEmpty { points }
    val lo = visible.minOf { it.second }
    var hi = visible.maxOf { it.second }
    if (hi <= lo) hi = lo + 1.0
    val pad = 10f
    return PlotGeometry(
        chartLeft = axisPx,
        chartWidth = (width - axisPx).coerceAtLeast(1f),
        top = pad,
        bottom = (height - pad).coerceAtLeast(pad + 1f),
        windowStart = window.first,
        span = (window.last - window.first).coerceAtLeast(1L),
        lo = lo,
        hi = hi,
    )
}

/** A stretch the series covered without a break: a stroked path, or a dot for a lone reading. */
private class PlotRun(val path: Path?, val dot: Offset?)

private fun buildRuns(points: List<CaloriePoint>, geometry: PlotGeometry): List<PlotRun> {
    val runs = ArrayList<PlotRun>()
    var i = 0
    while (i < points.size) {
        var j = i + 1
        while (j < points.size && points[j].first - points[j - 1].first <= MAX_JOIN_S) j++
        if (j - i == 1) {
            val (ts, v) = points[i]
            runs.add(PlotRun(null, Offset(geometry.xFor(ts), geometry.yFor(v))))
        } else {
            val path = Path()
            for (k in i until j) {
                val (ts, v) = points[k]
                val x = geometry.xFor(ts)
                val y = geometry.yFor(v)
                if (k == i) path.moveTo(x, y) else path.lineTo(x, y)
            }
            runs.add(PlotRun(path, null))
        }
        i = j
    }
    return runs
}

/** The point nearest [ts] in a list ascending by timestamp, or null when there are none. */
internal fun nearestPointIndex(points: List<CaloriePoint>, ts: Long): Int? {
    if (points.isEmpty()) return null
    var lo = 0
    var hi = points.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (points[mid].first < ts) lo = mid + 1 else hi = mid
    }
    val before = (lo - 1).coerceAtLeast(0)
    return if (abs(points[before].first - ts) <= abs(points[lo].first - ts)) before else lo
}
