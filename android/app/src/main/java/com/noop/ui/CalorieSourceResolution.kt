package com.noop.ui

// MARK: - Which calorie number a surface shows
//
// Several surfaces name "active calories" for a day and must not disagree, so the precedence rule
// lives here once rather than being re-expressed per screen.

/**
 * The two calorie sources in precedence order.
 *
 * A phone-reported value above zero outranks NOOP's estimate unless the wearer prefers on-device.
 * Generic in the value, so a caller carrying per-day provenance and one carrying bare numbers apply
 * the same rule.
 */
internal fun <T> calorieSourcesInOrder(onDevice: T, imported: T, preferOnDevice: Boolean): List<T> =
    if (preferOnDevice) listOf(onDevice, imported) else listOf(imported, onDevice)

/**
 * The per-day calorie value a card shows, resolved between NOOP's own estimate and a phone's.
 *
 * Neither order invents or drops a day; only which number wins changes.
 */
internal fun resolveCaloriesByDay(
    onDevice: Map<String, Double>,
    imported: Map<String, Double>,
    preferOnDevice: Boolean,
): Map<String, Double> {
    val order = calorieSourcesInOrder(onDevice, imported, preferOnDevice)
    return (onDevice.keys + imported.keys).mapNotNull { day ->
        order.firstNotNullOfOrNull { it[day] }?.let { day to it }
    }.toMap()
}

/**
 * Every day's active calories, resolved by [resolveCaloriesByDay] over both sources.
 *
 * Empty on a read failure: a missing series is honest where a partial one is not.
 */
internal suspend fun activeCaloriesByDay(vm: AppViewModel): Map<String, Double> = runCatching {
    val onDevice = vm.repo.resolvedSeries(
        "active_kcal", "my-whoop", "0000-00-00", "9999-99-99", strapDeviceId = vm.activeStrapId,
    ).points.associate { it.day to it.value }
    val imported = LinkedHashMap<String, Double>()
    for (r in vm.repo.appleDaily("apple-health", "0000-01-01", "9999-12-31") +
        vm.repo.appleDaily("health-connect", "0000-01-01", "9999-12-31")) {
        r.activeKcal?.takeIf { it > 0 }?.let { imported.putIfAbsent(r.day, it) }
    }
    resolveCaloriesByDay(onDevice, imported, vm.caloriePreferOnDevice())
}.getOrDefault(emptyMap())

/**
 * What a phone reports as active energy for one day, or null when no source covers it.
 *
 * The same read as [activeCaloriesByDay], narrowed to a day, so the two cannot differ on which
 * sources count.
 */
internal suspend fun importedActiveKcal(vm: AppViewModel, day: String): Double? = runCatching {
    (vm.repo.appleDaily("apple-health", day, day) + vm.repo.appleDaily("health-connect", day, day))
        .firstNotNullOfOrNull { it.activeKcal?.takeIf { kcal -> kcal > 0 } }
}.getOrNull()

/**
 * NOOP's own stored active energy for one day, or null when no pass has scored it.
 *
 * The same read [activeCaloriesByDay] resolves against, narrowed to a day. A recomputed figure is no
 * substitute: it is never null, so it cannot express that nothing was stored.
 */
internal suspend fun storedActiveKcal(vm: AppViewModel, day: String): Double? = runCatching {
    vm.repo.resolvedSeries("active_kcal", "my-whoop", day, day, strapDeviceId = vm.activeStrapId)
        .points.firstOrNull { it.day == day }?.value
}.getOrNull()

/** Which of the two calorie sources a surface is showing for a day. */
internal enum class CalorieSource { ON_DEVICE, IMPORTED }

/**
 * Which source wins for one day, or null when neither covers it.
 *
 * Takes both values because only the pair can answer it: under [preferOnDevice], a day NOOP scored
 * and a day it did not differ by nothing else. A null value means that source has no figure for the
 * day.
 */
internal fun calorieWinnerForDay(
    onDevice: Double?,
    imported: Double?,
    preferOnDevice: Boolean,
): CalorieSource? = calorieSourcesInOrder(
    onDevice = onDevice?.let { CalorieSource.ON_DEVICE },
    imported = imported?.let { CalorieSource.IMPORTED },
    preferOnDevice = preferOnDevice,
).firstNotNullOfOrNull { it }

/**
 * The phone's figure when it is the one being shown, or null when NOOP's own estimate wins.
 *
 * A day can carry a phone value that loses, so the question is precedence and not presence.
 */
internal fun importedWinsKcal(onDevice: Double?, imported: Double?, preferOnDevice: Boolean): Double? =
    imported?.takeIf {
        calorieWinnerForDay(onDevice, imported, preferOnDevice) == CalorieSource.IMPORTED
    }
