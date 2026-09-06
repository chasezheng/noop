package com.noop.analytics.calorie

import com.noop.data.GravitySample
import com.noop.data.HrSample

/**
 * Every stream a model may score a span from.
 *
 * One type rather than four parameters because each model chooses which of them it reads. Each field
 * defaults to empty, so a caller supplies only what it has.
 */
data class CalorieInputs(
    val hr: List<HrSample> = emptyList(),
    val gravity: List<GravitySample> = emptyList(),
    /**
     * A wall-clock second and the MET a ring reported for it, which wins over the motion transfer
     * wherever it exists.
     *
     * No stream in this build produces these; only a caller holding its own ring data can supply one.
     */
    val ringMET: List<Pair<Long, Double>> = emptyList(),
    /** Half-open `[start, end)` windows a workout occupies, in UTC seconds. */
    val workouts: List<LongRange> = emptyList(),
    /**
     * A wall-clock second and how many beat-to-beat intervals were recorded in it.
     *
     * Folded to a count at the port rather than carried as rows: a model reads this as evidence that
     * the strap held a clean optical lock, and a day of beats is tens of thousands of rows.
     */
    val rr: List<Pair<Long, Int>> = emptyList(),
) {

    /**
     * The same inputs with heart rate reduced to `[startUtc, endUtc)`, in timestamp order.
     *
     * Heart rate only, because a sample's weight comes from the gap to the next one: a sample just
     * past the span would change what the last sample inside it is worth. Motion, ring MET, beats and
     * workout windows are read one at a time and need no clipping.
     */
    fun clippedTo(startUtc: Long, endUtc: Long): CalorieInputs =
        // A stable sort. Two samples stamped with the same second keep the order they arrived in,
        // and the rasteriser reads the first of them.
        copy(hr = hr.filter { it.ts >= startUtc && it.ts < endUtc }.sortedWith { a, b -> a.ts.compareTo(b.ts) })
}
