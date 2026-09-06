package com.noop.analytics

/*
 * CalorieAliases.kt — the calorie types under the name the rest of `com.noop.analytics` knows them by.
 *
 * [com.noop.analytics.calorie.Calories] and its neighbours moved into the `calorie` package when the
 * models were added. Upstream code writes `Calories.foo(...)` unqualified, so without these aliases
 * every file in this package that touches energy needs an import it would not otherwise carry, and a
 * file added later fails to compile for a reason that has nothing to do with what it changed.
 *
 * Aliases only: nothing is declared twice, and the calorie package remains the one definition.
 */

typealias Calories = com.noop.analytics.calorie.Calories
typealias CalorieTimeline = com.noop.analytics.calorie.CalorieTimeline
typealias HeartRateGates = com.noop.analytics.calorie.HeartRateGates
typealias EnergyModel = com.noop.analytics.calorie.EnergyModel
