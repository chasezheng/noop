package com.noop.analytics.calorie

/*
 * CalorieTimelineReads.kt — the one read of a scored day the type does not offer.
 *
 * Test-only. A test folds a labelled term often enough that spelling the map access out at every
 * site buried what the assertion was about.
 */

/** What the path [label] contributed to the day, or zero when no minute took that path. */
internal fun CalorieTimeline.kcalFrom(label: String): Double {
    var total = 0.0
    for (v in labeledKcal[label] ?: return 0.0) total += v
    return total
}
