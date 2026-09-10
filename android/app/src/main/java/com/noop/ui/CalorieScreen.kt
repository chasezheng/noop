package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.calorie.CalorieTimeline
import com.noop.analytics.calorie.RunningPaceEstimator
import com.noop.analytics.calorie.WalkingPaceEstimator
import com.noop.analytics.calorie.exclusiveEnd
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - Where one day's energy came from
//
// Re-scores one day with the model the app is set to, and shows how that model reached its figure:
// heart rate and each series the model publishes over the activity day, the energy accumulating
// through it, that day at half-hour resolution, and the total split into the terms the model names.
//
// The model is read from the profile and its output is walked by key, so a model this file has never
// heard of still draws. Nothing here decides what a term means; the model's own keys do.
//
// A reader, not a second estimator: every figure comes from the same functions the analytics pass
// stores from, and nothing here is written.

/** Height of each plotted series. */
private val TRACE_HEIGHT = 108.dp

/** Height of the per-minute energy plot. */
private val ACTIVE_ENERGY_HEIGHT = 140.dp

/** How far either side of the cursor a reading is taken. */
private const val CURSOR_RADIUS_S = 300L

/** The hero's corner, matching the hero panel on every other screen. */
private val HERO_RADIUS = 26.dp

private val TABLE_TIME_WIDTH = 56.dp
private val TABLE_KCAL_WIDTH = 68.dp
private val TABLE_SPEED_WIDTH = 76.dp
private val TABLE_SERIES_WIDTH = 132.dp

/** One line a plot draws, under the name and colour the screen gives it. */
private class PlotSpec(val label: String, val color: Color, val points: List<CaloriePoint>)

@Composable
fun CalorieScreen(vm: AppViewModel, day: LocalDate) {
    val context = LocalContext.current
    val profile = remember { ProfileStore.from(context.applicationContext) }
    val zone = remember { ZoneId.systemDefault() }
    val unitSystem = remember { UnitPrefs.system(context) }

    // The route names the day the screen opened on; the chevrons move from there without pushing a
    // back-stack entry, so Back returns to where the wearer came from rather than walking the days.
    var dayOffset by rememberSaveable(day) {
        mutableIntStateOf(ChronoUnit.DAYS.between(day, LocalDate.now()).toInt().coerceAtLeast(0))
    }
    val shown = remember(dayOffset) { LocalDate.now().minusDays(dayOffset.toLong()) }

    var failure by remember(shown) { mutableStateOf<String?>(null) }
    var loaded by remember(shown) { mutableStateOf(false) }
    // A card may show the phone's figure in preference to NOOP's, so without naming the phone's value
    // this screen can differ from the card that opened it by hundreds of kcal with nothing to explain
    // the gap.
    var loadedDay by remember(shown) { mutableStateOf<CalorieDay?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // A settings edit changes every figure below, so the day is scored again. Repainting alone would
    // leave the old model's answer on screen under the new settings.
    var reload by remember(shown) { mutableStateOf(0) }

    LaunchedEffect(shown, reload) {
        // Cleared first, so a re-read after a settings edit shows that it is happening rather than
        // holding the pre-edit figures on screen until it finishes.
        loaded = false
        val outcome = runCatching { loadCalorieDay(vm, profile, shown) }
        loadedDay = outcome.getOrNull()
        failure = outcome.exceptionOrNull()?.let { "${it::class.java.simpleName}: ${it.message ?: ""}" }
        loaded = true
    }

    val subtitle = remember(shown) {
        shown.format(DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()))
    }
    ScreenScaffold(title = uiString(R.string.nav_total_energy), subtitle = subtitle) {
        DayNavBar(selectedOffset = dayOffset, onSelect = { dayOffset = it })
        val scored = loadedDay
        when {
            !loaded -> NoopCard {
                Text(
                    uiString(R.string.total_energy_loading),
                    style = NoopType.subhead,
                    color = Palette.textSecondary,
                )
            }
            failure != null -> NoopCard {
                Text(
                    uiString(R.string.total_energy_read_failed, failure.orEmpty()),
                    style = NoopType.subhead,
                    color = Palette.statusWarning,
                )
            }
            // A future day, or a day the strap never covered. Neither is an error.
            scored == null ||
                (scored.trace.hrSampleCount == 0 && scored.trace.motionSampleCount == 0) -> {
                DataPendingNote(
                    title = uiString(R.string.total_energy_empty_title),
                    body = uiString(R.string.total_energy_empty_body),
                )
                // A tile can still show a figure for such a day, from the phone. Saying so
                // distinguishes an unobserved day from a broken screen.
                scored?.importedKcal?.let { imported ->
                    NoopCard {
                        Text(
                            uiString(R.string.total_energy_empty_imported, kcalString(imported)),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                }
            }
            else -> ScoredDay(scored, profile, zone, unitSystem)
        }
        // Outside the branches: a day with no coverage is the likeliest reason to want the settings.
        NoopButton(
            text = uiString(R.string.total_energy_settings_button),
            fullWidth = true,
            onClick = { showSettings = true },
        )
    }

    if (showSettings) {
        var revision by rememberSaveable { mutableStateOf(0) }
        // The store wraps preferences rather than snapshot state, so reading this counter is what
        // invalidates this screen — and with it the dialog — on an edit.
        @Suppress("UNUSED_VARIABLE") val tick = revision
        CalorieTrackingDialog(
            vm = vm,
            profile = profile,
            onProfileChanged = { revision++ },
            onDismiss = {
                showSettings = false
                // Every stored day was scored under the previous settings, so an edit re-scores the
                // window. Gated on `revision`, so opening the dialog to read it costs nothing.
                if (revision > 0) {
                    vm.rescoreAfterCalorieSettingsChanged()
                    reload++
                }
            },
        )
    }
}

/** Everything a scored day shows, over one shared window and one shared cursor. */
@Composable
private fun ScoredDay(
    scored: CalorieDay,
    profile: ProfileStore,
    zone: ZoneId,
    unitSystem: UnitSystem,
) {
    val timeline = scored.timeline
    val trace = scored.trace
    val chart = rememberCalorieChartState(trace.start..trace.end)
    val heartRateLabel = uiString(R.string.total_energy_trace_heart_rate)
    val plots = remember(scored, heartRateLabel) {
        listOf(PlotSpec(heartRateLabel, Palette.metricRose, trace.hrTrace)) +
            timeline.labeledSeries.keys.mapIndexed { index, key ->
                PlotSpec(seriesLabel(key), seriesTint(index), timeline.pointsFor(key))
            }
    }
    // Energy above resting per minute, which both the cursor reading and the table fold.
    val activePerMinute = remember(timeline) {
        timeline.tsIndex.mapIndexed { i, ts -> ts to timeline.activeKcal[i] }
    }
    // The equivalent-pace curves give gross cost, so they need resting energy as a rate.
    val restingKcalPerS = scored.restingKcal24h / 86_400.0
    val walking = remember(profile.weightKg, restingKcalPerS) {
        WalkingPaceEstimator(profile.weightKg, restingKcalPerS)
    }
    val running = remember(profile.weightKg, restingKcalPerS) {
        RunningPaceEstimator(profile.weightKg, restingKcalPerS)
    }

    HeroSection(
        modelName = uiString(modelNameRes(scored.model)),
        active = timeline.dayActiveKcal,
        resting = scored.restingKcal24h,
    )
    // Only when a phone actually covers this day AND its figure is the one the card used.
    importedWinsKcal(scored.onDeviceKcal, scored.importedKcal, profile.caloriePreferOnDevice)
        ?.let { imported ->
            NoopCard {
                Text(
                    uiString(R.string.total_energy_imported_wins, kcalString(imported)),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    DayTraceCard(plots, chart, zone)
    ActiveEnergyCard(
        activePerMinute, plots, trace.hrTrace, chart, scored.wakeTs, zone,
        walking, running, unitSystem,
    )
    BreakdownCard(timeline, scored.restingKcal24h)
    WorkoutsCard(scored.workouts, activePerMinute, zone, walking, running, unitSystem)
    Text(
        uiString(R.string.total_energy_footnote),
        style = NoopType.footnote,
        color = Palette.textTertiary,
    )
    Text(
        uiString(R.string.total_energy_speed_footnote),
        style = NoopType.footnote,
        color = Palette.textTertiary,
    )
}

// MARK: - Hero

/** The headline, for the model the day was scored with. */
@Composable
private fun HeroSection(modelName: String, active: Double, resting: Double) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(HERO_RADIUS))
            .background(
                Palette.heroFill.copy(alpha = Palette.heroFill.alpha * CardAppearance.opacity),
                RoundedCornerShape(HERO_RADIUS),
            )
            .border(
                1.dp,
                Palette.heroBorder.copy(alpha = Palette.heroBorder.alpha * CardAppearance.opacity),
                RoundedCornerShape(HERO_RADIUS),
            )
            .padding(Metrics.cardPadding),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Overline(modelName)
            Text(
                kcalString(active + resting),
                style = NoopType.chartValueLarge,
                color = Palette.metricAmber,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Metrics.space16)) {
                TermColumn(uiString(R.string.total_energy_term_active), active)
                TermColumn(uiString(R.string.total_energy_term_resting), resting)
            }
            Text(
                uiString(R.string.total_energy_model_in_use),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun TermColumn(label: String, kcal: Double) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space2)) {
        Overline(label, color = Palette.textTertiary)
        Text(kcalString(kcal), style = NoopType.bodyNumber, color = Palette.textPrimary)
    }
}

// MARK: - The day, minute by minute

/**
 * Heart rate over the activity day, then every per-minute series the model published.
 *
 * One x-domain and one axis strip for all of them, so the plots line up vertically. Each series
 * keeps its own y-scale: they are different quantities in different units, and one shared scale
 * would flatten all but the largest.
 */
@Composable
private fun DayTraceCard(plots: List<PlotSpec>, chart: CalorieChartState, zone: ZoneId) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(uiString(R.string.total_energy_trace_overline))
            plots.forEachIndexed { index, plot ->
                TraceHeader(plot.label) { CursorReading(plot.points, chart) }
                CaloriePlot(
                    points = plot.points,
                    color = plot.color,
                    state = chart,
                    height = TRACE_HEIGHT,
                    axLabel = uiString(R.string.total_energy_trace_a11y, plot.points.size),
                    formatAxis = ::numberString,
                    // The plots share one cursor, so the stack is labelled at its outer edges only.
                    timeAbove = index == 0,
                    timeBelow = index == plots.lastIndex,
                )
            }
            CalorieTimeAxis(chart, zone)
        }
    }
}

@Composable
private fun TraceHeader(title: String, detail: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Overline(title, modifier = Modifier.weight(1f), color = Palette.textSecondary)
        detail()
    }
}

/**
 * How a series reads over the ten minutes around the cursor.
 *
 * Its own composable, so moving the cursor repaints this line rather than the card holding it.
 */
@Composable
private fun CursorReading(points: List<CaloriePoint>, chart: CalorieChartState) {
    val text = when {
        points.isEmpty() -> uiString(R.string.total_energy_trace_no_data)
        else -> chart.cursorTs
            ?.let { statsIn(points, (it - CURSOR_RADIUS_S)..(it + CURSOR_RADIUS_S)) }
            ?.let {
                uiString(
                    R.string.total_energy_cursor_range,
                    numberString(it.mean),
                    numberString(it.min),
                    numberString(it.max),
                )
            }
            .orEmpty()
    }
    Text(text, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
}

/** What the wearer earned above resting over the ten minutes around the cursor. */
@Composable
private fun CursorEnergyReading(activePerMinute: List<CaloriePoint>, chart: CalorieChartState) {
    val at = chart.cursorTs
    Text(
        if (at == null) "" else uiString(
            R.string.total_energy_cursor_kcal,
            numberString(sumIn(activePerMinute, (at - CURSOR_RADIUS_S)..(at + CURSOR_RADIUS_S))),
        ),
        style = NoopType.footnote,
        color = Palette.textTertiary,
        maxLines = 1,
    )
}

// MARK: - Cumulative, and the day half an hour at a time

/**
 * What each minute earned above resting, then the day as a table.
 *
 * Active throughout: resting is a flat term that would add the same offset to every minute and bury
 * the ones the wearer actually earned.
 */
@Composable
private fun ActiveEnergyCard(
    activePerMinute: List<CaloriePoint>,
    plots: List<PlotSpec>,
    heartRate: List<CaloriePoint>,
    chart: CalorieChartState,
    wakeTs: Long?,
    zone: ZoneId,
    walking: WalkingPaceEstimator,
    running: RunningPaceEstimator,
    unitSystem: UnitSystem,
) {
    val buckets = remember(activePerMinute, plots, wakeTs, zone) {
        calorieBuckets(
            kcal = activePerMinute,
            series = plots.map { it.points },
            // Heart rate decides whether a half hour is worth a row: it is the one series every
            // model reads, and its absence is what a strap off the wrist looks like.
            coverage = heartRate,
            fromTs = halfHourStart(wakeTs ?: chart.bounds.first, zone),
        )
    }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            TraceHeader(uiString(R.string.total_energy_compare_overline)) {
                CursorEnergyReading(activePerMinute, chart)
            }
            CaloriePlot(
                points = activePerMinute,
                color = Palette.chargeColor,
                state = chart,
                height = ACTIVE_ENERGY_HEIGHT,
                axLabel = uiString(
                    R.string.total_energy_compare_a11y,
                    numberString(activePerMinute.maxOfOrNull { it.second } ?: 0.0),
                ),
                formatAxis = ::numberString,
            )
            CalorieTimeAxis(chart, zone)
            HalfHourTable(buckets, plots, zone, walking, running, unitSystem)
        }
    }
}

/** Every half hour the day recorded, from the wearer's wake time. */
@Composable
private fun HalfHourTable(
    buckets: List<CalorieBucket>,
    plots: List<PlotSpec>,
    zone: ZoneId,
    walking: WalkingPaceEstimator,
    running: RunningPaceEstimator,
    unitSystem: UnitSystem,
) {
    if (buckets.isEmpty()) return
    Overline(uiString(R.string.total_energy_table_overline))
    // The row width is stated rather than filled: inside a horizontal scroll the incoming width
    // constraint is unbounded, and a child that fills it has nothing to fill.
    val tableWidth = TABLE_TIME_WIDTH + TABLE_KCAL_WIDTH + TABLE_SPEED_WIDTH +
        TABLE_SERIES_WIDTH * plots.size
    Column(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Metrics.space4),
    ) {
        Row {
            TableCell(uiString(R.string.total_energy_table_time), TABLE_TIME_WIDTH, Palette.textTertiary)
            TableCell(uiString(R.string.total_energy_table_kcal), TABLE_KCAL_WIDTH, Palette.textTertiary)
            TableCell(uiString(R.string.total_energy_table_speed), TABLE_SPEED_WIDTH, Palette.textTertiary)
            plots.forEach { TableCell(it.label, TABLE_SERIES_WIDTH, it.color) }
        }
        Box(modifier = Modifier.width(tableWidth).height(Metrics.divider).fill(Palette.hairline))
        buckets.forEach { bucket ->
            Row {
                TableCell(hourLabel(bucket.startTs, zone), TABLE_TIME_WIDTH, Palette.textSecondary)
                TableCell(numberString(bucket.kcal), TABLE_KCAL_WIDTH, Palette.textPrimary)
                TableCell(
                    // The row spans the whole half hour whether or not every minute of it was
                    // recorded, so the pace is what the wearer averaged across it.
                    UnitFormatter.paceFromSecPerKm(
                        equivalentPaceSecPerKm(walking, running, bucket.kcal, HALF_HOUR_S.toDouble()),
                        unitSystem,
                    ),
                    TABLE_SPEED_WIDTH,
                    Palette.textSecondary,
                )
                bucket.series.forEach { stats ->
                    TableCell(
                        stats?.let {
                            uiString(
                                R.string.total_energy_cursor_range,
                                numberString(it.mean),
                                numberString(it.min),
                                numberString(it.max),
                            )
                        } ?: "—",
                        TABLE_SERIES_WIDTH,
                        Palette.textPrimary,
                    )
                }
            }
        }
    }
}

@Composable
private fun TableCell(text: String, width: Dp, color: Color) {
    Text(
        text,
        style = NoopType.captionNumber,
        color = color,
        maxLines = 1,
        modifier = Modifier.width(width),
    )
}

// MARK: - Breakdown

/**
 * The day's terms, as a proportional bar and as rows, then whatever else the model reported.
 *
 * Resting is included and is usually most of the bar; omitting it would make the active terms look
 * several times larger than they are.
 */
@Composable
private fun BreakdownCard(timeline: CalorieTimeline, resting: Double) {
    val terms = remember(timeline) {
        timeline.labeledKcal.keys.map { key -> key to timeline.foldLabeled(key) }
    }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(uiString(R.string.total_energy_breakdown_overline))
            SegmentBar(
                segments = listOf(Palette.textTertiary to resting.toFloat()) +
                    terms.mapIndexed { index, (_, kcal) -> seriesTint(index) to kcal.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            BreakdownRow(uiString(R.string.total_energy_term_resting), kcalString(resting), Palette.textTertiary)
            terms.forEachIndexed { index, (key, kcal) ->
                BreakdownRow(seriesLabel(key), kcalString(kcal), seriesTint(index))
            }
            Divider()
            BreakdownRow(
                uiString(R.string.total_energy_term_total),
                kcalString(timeline.dayActiveKcal + resting),
                Palette.metricAmber,
            )

            if (timeline.extras.isNotEmpty()) {
                Divider()
                Overline(uiString(R.string.total_energy_stats_overline))
                timeline.extras.forEach { (key, value) ->
                    StatRow(seriesLabel(key), numberString(value))
                }
            }
        }
    }
}

// MARK: - Workouts

/**
 * Each workout window the day was scored with, and what it earned above resting.
 *
 * A minute is credited to the window it opens in, which is the resolution the models report at.
 */
@Composable
private fun WorkoutsCard(
    workouts: List<LongRange>,
    activePerMinute: List<CaloriePoint>,
    zone: ZoneId,
    walking: WalkingPaceEstimator,
    running: RunningPaceEstimator,
    unitSystem: UnitSystem,
) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Overline(uiString(R.string.total_energy_workouts_overline))
            if (workouts.isEmpty()) {
                // Named rather than left blank: a term the wearer expected to be large and is not
                // traces back to the windows that reached the model.
                Text(
                    uiString(R.string.total_energy_no_workouts),
                    style = NoopType.footnote,
                    color = Palette.metricAmber,
                )
                return@Column
            }
            workouts.forEach { window ->
                val kcal = sumIn(activePerMinute, window)
                BreakdownRow(
                    "${hourLabel(window.first, zone)}–${hourLabel(window.exclusiveEnd, zone)}",
                    kcalString(kcal),
                    Palette.effortColor,
                    detail = UnitFormatter.paceFromSecPerKm(
                        equivalentPaceSecPerKm(
                            walking,
                            running,
                            kcal,
                            (window.exclusiveEnd - window.first).toDouble(),
                        ),
                        unitSystem,
                    ),
                )
            }
            // Every figure here is energy ABOVE resting. A per-session figure elsewhere in the app
            // covers a session's whole duration, resting share included, so the two differ.
            Text(
                uiString(R.string.total_energy_workouts_net),
                style = NoopType.footnote,
                color = Palette.textTertiary,
            )
        }
    }
}

@Composable
private fun BreakdownRow(label: String, value: String, tint: Color, detail: String? = null) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Metrics.space8),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(2.dp))
                .fill(tint),
        )
        Text(label, style = NoopType.subhead, color = Palette.textSecondary, modifier = Modifier.weight(1f))
        if (detail != null) {
            Text(detail, style = NoopType.footnote, color = Palette.textTertiary, maxLines = 1)
        }
        Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary)
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NoopType.footnote, color = Palette.textTertiary, modifier = Modifier.weight(1f))
        Text(value, style = NoopType.captionNumber, color = Palette.textPrimary)
    }
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(Metrics.divider).fill(Palette.hairline))
}

/** A flat colour fill. */
private fun Modifier.fill(color: Color): Modifier = this.drawBehind { drawRect(color) }

// MARK: - Naming a model's own keys

/**
 * A key as a label: the camel-case words it is spelled with, separated and sentence-cased.
 *
 * A model owns its key strings and this screen draws models it has never heard of, so there is no
 * table to translate them through. Every key stays visible under the name its model gave it.
 */
private fun seriesLabel(key: String): String =
    key.replace(CAMEL_CASE_BOUNDARY, " ").replaceFirstChar { it.uppercase() }

private val CAMEL_CASE_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])")

/** The tints series and terms take, in publication order. Adjacent entries never share one. */
private fun seriesTint(index: Int): Color = when (index % 5) {
    0 -> Palette.chargeColor
    1 -> Palette.metricCyan
    2 -> Palette.metricPurple
    3 -> Palette.effortColor
    else -> Palette.accentMuted
}

/** [key]'s per-minute series, with a point only where the minute carried a value. */
private fun CalorieTimeline.pointsFor(key: String): List<CaloriePoint> {
    val series = labeledSeries[key] ?: return emptyList()
    val out = ArrayList<CaloriePoint>(series.size)
    for (i in tsIndex.indices) series.getOrNull(i)?.let { out.add(tsIndex[i] to it) }
    return out
}

/** What the path [key] contributed to the day. */
private fun CalorieTimeline.foldLabeled(key: String): Double {
    var total = 0.0
    for (v in labeledKcal[key] ?: return 0.0) total += v
    return total
}

// MARK: - Formatting

private fun kcalString(v: Double): String = String.format(Locale.US, "%,d kcal", v.roundToInt())

/** A figure whose unit this screen does not know: whole where it is whole, two decimals otherwise. */
private fun numberString(v: Double): String =
    if (abs(v - Math.rint(v)) < 1e-9) String.format(Locale.US, "%,.0f", v)
    else String.format(Locale.US, "%,.2f", v)

// Held rather than rebuilt per call; the formatter is thread-safe.
private val windowStartFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** A window's start hour in the wearer's own clock. */
private fun hourLabel(ts: Long, zone: ZoneId): String =
    Instant.ofEpochSecond(ts).atZone(zone).format(windowStartFormat)
