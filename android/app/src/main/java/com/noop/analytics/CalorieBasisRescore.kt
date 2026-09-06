package com.noop.analytics

import com.noop.data.WhoopRepository

/**
 * The one-shot repair for the calorie-basis change.
 *
 * Kept out of `IntelligenceEngine`, which it otherwise mirrors: any member added to that class
 * widens the index operands inside its largest method and can push the method past its size budget.
 */
object CalorieBasisRescore {

    /**
     * Rescore the whole history once, unless [flagGet] reports it already ran.
     *
     * A row written by an older build holds the whole-day total where the column now holds the active
     * term alone. Every such day is recomputed from its raw heart rate rather than repaired by
     * subtracting a basal estimate: subtraction cannot tell a legacy total from an already-correct
     * active value, so it would subtract twice for anyone who upgrades after the first pass.
     *
     * Best-effort repair rather than a migration. Imported rows are never rewritten, and a day whose
     * raw heart rate is gone keeps its old value. The flag is injected so this object needs no
     * Android context.
     */
    suspend fun runIfNeeded(
        repo: WhoopRepository,
        profile: UserProfile = UserProfile(),
        importedDeviceId: String = "my-whoop",
        maxHROverride: Double? = null,
        flagGet: () -> Boolean,
        flagSet: () -> Unit,
        historyDays: Int = IntelligenceEngine.EFFORT_RESCORE_HISTORY_DAYS,
        ownerSource: IntelligenceEngine.DayOwnerSource? = null,
    ) {
        if (flagGet()) return
        IntelligenceEngine.analyzeRecent(
            repo = repo,
            profile = profile,
            maxDays = historyDays,
            importedDeviceId = importedDeviceId,
            maxHROverride = maxHROverride,
            ownerSource = ownerSource,
        )
        flagSet()
    }
}
