package com.noop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.analytics.StrainScorer
import com.noop.analytics.calorie.Calories
import com.noop.analytics.calorie.CalorieModels
import com.noop.analytics.calorie.EnergyModel
import com.noop.analytics.calorie.HeartRateGatesRanges
import com.noop.analytics.calorie.HybridModel
import com.noop.analytics.calorie.HybridMet
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - CalorieTrackingDialog
//
// Chooses the daily energy model, shows the settings that model reads, and previews a change by
// re-running that model over one real day, so it can be judged against the wearer's own data.
//
// The preview is read-only: it re-derives from the stored streams and never writes a row. A saved
// setting reaches the stored figure the next time the analytics pass scores that day.

@Composable
fun CalorieTrackingDialog(
    vm: AppViewModel,
    profile: ProfileStore,
    onProfileChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // Hoisted: the result lands at the bottom of a column taller than the dialog, so without
    // scrolling to it a finished preview is invisible and the button reads as dead.
    val scroll = rememberScrollState()
    var previewDay by remember { mutableStateOf(LocalDate.now()) }
    var preview by remember { mutableStateOf<CalorieDay?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var previewing by remember { mutableStateOf(false) }

    // An edit clears the shown preview rather than leaving a stale one attributed to settings that no
    // longer apply.
    fun edited() {
        preview = null
        previewError = null
        onProfileChanged()
    }

    LaunchedEffect(preview, previewError) {
        if (preview == null && previewError == null) return@LaunchedEffect
        // The extent to scroll to is unknown until the result rows are measured, which is after this
        // effect runs. Observing the extent rather than waiting a frame gets both emissions: the
        // stale pre-result one, then the real one, which cancels the short scroll started for it.
        snapshotFlow { scroll.maxValue }
            .distinctUntilChanged()
            .collectLatest { scroll.animateScrollTo(it) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.surfaceOverlay,
        title = {
            Text(
                uiString(R.string.calorie_tracking_title),
                style = NoopType.title2,
                color = Palette.textPrimary,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = Metrics.dialogScrollableMaxHeight)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(Metrics.gap),
            ) {
                ModelSection(profile, ::edited)
                SettingsRowDivider()
                // Only the settings the selected model reads, so no control on screen is inert. The
                // hybrid scores some minutes from heart rate, so both gates are live for it too.
                when (profile.calorieModel) {
                    EnergyModel.HYBRID -> {
                        HybridSection(profile, ::edited)
                        SettingsRowDivider()
                        HeartRateSection(profile, ::edited)
                    }
                    EnergyModel.HEART_RATE -> HeartRateSection(profile, ::edited)
                    EnergyModel.DYNAMIC_HRR -> DynamicHrrSection(profile, ::edited)
                }
                SettingsRowDivider()
                PreviewSection(
                    day = previewDay,
                    preview = preview,
                    preferOnDevice = profile.caloriePreferOnDevice,
                    error = previewError,
                    running = previewing,
                    onDayChange = { previewDay = it; preview = null; previewError = null },
                    onRun = {
                        previewing = true
                        previewError = null
                        scope.launch {
                            // A throw would cancel the scope and leave the button stuck on
                            // "Processing…" with nothing said.
                            val outcome = runCatching {
                                loadCalorieDay(vm, profile, previewDay)
                            }
                            preview = outcome.getOrNull()
                            previewError = outcome.exceptionOrNull()?.let {
                                "${it::class.java.simpleName}: ${it.message ?: ""}"
                            }
                            previewing = false
                        }
                    },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(uiString(R.string.calorie_tracking_done), color = Palette.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = { profile.resetCalorieSettings(); edited() }) {
                Text(uiString(R.string.calorie_tracking_reset), color = Palette.textSecondary)
            }
        },
    )
}

// MARK: - Model choice

@Composable
private fun ModelSection(profile: ProfileStore, onEdited: () -> Unit) {
    val selected = profile.calorieModel
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Overline(uiString(R.string.calorie_tracking_model_overline))
        ModelDropdown(
            selected = selected,
            onSelect = { profile.calorieModel = it; onEdited() },
        )
        // What the chosen model reads, and what it needs, stated where the choice is made rather than
        // discovered from an unscored day.
        Text(
            uiString(modelDetailRes(selected)),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )
        SettingsToggleRow(
            title = uiString(R.string.calorie_tracking_prefer_on_device_title),
            detail = uiString(R.string.calorie_tracking_prefer_on_device_detail),
            checked = profile.caloriePreferOnDevice,
            onCheckedChange = { profile.caloriePreferOnDevice = it; onEdited() },
        )
        // The accuracy caveat belongs in front of the wearer at the moment they opt in, and each
        // model's caveat is its own: one is weakly published, the other barely evidenced at all.
        modelCaveatRes(selected)?.let { caveat ->
            Text(uiString(caveat), style = NoopType.footnote, color = Palette.metricAmber)
        }
    }
}

/** The model in use, opening the rest of the list. */
@Composable
private fun ModelDropdown(selected: EnergyModel, onSelect: (EnergyModel) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Metrics.cornerSm))
                .background(Palette.surfaceInset)
                .clickable { expanded = true }
                .padding(horizontal = Metrics.space14, vertical = Metrics.space10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                uiString(modelNameRes(selected)),
                style = NoopType.subhead,
                color = Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Palette.textSecondary)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            CalorieModels.order.forEach { model ->
                DropdownMenuItem(
                    text = {
                        Text(
                            uiString(modelNameRes(model)),
                            style = NoopType.subhead,
                            color = if (model == selected) Palette.accent else Palette.textPrimary,
                        )
                    },
                    onClick = { expanded = false; onSelect(model) },
                )
            }
        }
    }
}

/**
 * The translated name of a model.
 *
 * An exhaustive `when` over string resources rather than a name built from the model's own token: a
 * generated name cannot be translated.
 */
internal fun modelNameRes(model: EnergyModel): Int = when (model) {
    EnergyModel.HEART_RATE -> R.string.calorie_tracking_preview_hr_model
    EnergyModel.HYBRID -> R.string.calorie_tracking_preview_hybrid_model
    EnergyModel.DYNAMIC_HRR -> R.string.calorie_tracking_dynamic_hrr_model
}

/** What a model's figure cannot be trusted to be, or null for the default. */
private fun modelCaveatRes(model: EnergyModel): Int? = when (model) {
    EnergyModel.HEART_RATE -> null
    EnergyModel.HYBRID -> R.string.calorie_tracking_hybrid_caveat
    EnergyModel.DYNAMIC_HRR -> R.string.calorie_tracking_dynamic_hrr_caveat
}

/** The one line a model is chosen on: what it reads, and what it needs before it will score a day. */
private fun modelDetailRes(model: EnergyModel): Int = when (model) {
    EnergyModel.HEART_RATE -> R.string.calorie_tracking_model_hr_detail
    EnergyModel.HYBRID -> R.string.calorie_tracking_model_hybrid_detail
    EnergyModel.DYNAMIC_HRR -> R.string.calorie_tracking_model_dynamic_hrr_detail
}

// MARK: - Heart-rate model

@Composable
private fun HeartRateSection(profile: ProfileStore, onEdited: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Overline(uiString(R.string.calorie_tracking_hr_overline))
        Text(
            uiString(R.string.calorie_tracking_hr_blurb),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )

        PercentKnob(
            label = uiString(R.string.calorie_tracking_day_gate_label),
            value = profile.calorieDayActiveHRRFraction,
            onChange = { profile.calorieDayActiveHRRFraction = it; onEdited() },
        )
        // The gate is a fraction of reserve, so one percentage lands at a different bpm for every
        // wearer. The resulting bpm can be compared against a known sitting or walking rate.
        Text(
            uiString(
                R.string.calorie_tracking_day_gate_detail,
                gateBpm(profile, profile.calorieDayActiveHRRFraction),
            ),
            style = NoopType.caption,
            color = Palette.textTertiary,
        )

        PercentKnob(
            label = uiString(R.string.calorie_tracking_bout_gate_label),
            value = profile.calorieBoutActiveHRRFraction,
            onChange = { profile.calorieBoutActiveHRRFraction = it; onEdited() },
        )
        // Without this line the label reads as a detection sensitivity, which it is not.
        Text(
            uiString(R.string.calorie_tracking_bout_gate_detail),
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
        // The same units and reserve arithmetic as the day floor above, so it reads the same way.
        Text(
            uiString(
                R.string.calorie_tracking_gate_bpm_assumed,
                gateBpm(profile, profile.calorieBoutActiveHRRFraction),
            ),
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
    }
}

/**
 * The bpm a reserve fraction lands at, from the wearer's maximum heart rate and an assumed resting
 * rate.
 *
 * The resting rate is a stand-in, which the copy states rather than claiming the figure is personal:
 * the real one needs a read across every source, which composition cannot do, and for a fit wearer
 * the two differ by about 14 bpm.
 */
private fun gateBpm(profile: ProfileStore, fraction: Double): Int =
    Calories.activeHrGate(
        restingHR = DEFAULT_PREVIEW_RESTING_HR,
        hrmax = profile.hrMaxExact,
        hrrFraction = fraction,
    ).roundToInt()

// MARK: - Hybrid model

@Composable
private fun HybridSection(profile: ProfileStore, onEdited: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Overline(uiString(R.string.calorie_tracking_hybrid_overline))
        Text(
            uiString(R.string.calorie_tracking_hybrid_blurb),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )

        MetKnob(
            label = uiString(R.string.calorie_tracking_accrual_met_label),
            value = profile.calorieActiveAccrualMET,
            step = MET_STEP,
            onChange = { profile.calorieActiveAccrualMET = it; onEdited() },
        )
        Text(
            uiString(R.string.calorie_tracking_accrual_met_detail),
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
        MetKnob(
            label = uiString(R.string.calorie_tracking_met_gain_label),
            value = profile.calorieDynAccelMETGainPerG,
            step = MET_GAIN_STEP,
            onChange = { profile.calorieDynAccelMETGainPerG = it; onEdited() },
        )
        // The least-evidenced value in either model, so the row shows what the current gain implies
        // for a walk, which the wearer can check against a known effort.
        Text(
            uiString(
                R.string.calorie_tracking_met_gain_detail,
                oneDecimal(HybridMet.metFromDynAccel(WALK_ANCHOR_G, profile.toHybridModelSetting())),
            ),
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
        SettingsToggleRow(
            title = uiString(R.string.calorie_tracking_hr_fallback_title),
            detail = uiString(R.string.calorie_tracking_hr_fallback_detail),
            checked = profile.calorieHrFallbackWhenNoMET,
            onCheckedChange = { profile.calorieHrFallbackWhenNoMET = it; onEdited() },
        )
    }
}

// MARK: - Measured-basal model

@Composable
private fun DynamicHrrSection(profile: ProfileStore, onEdited: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Overline(uiString(R.string.calorie_tracking_dynamic_hrr_overline))
        Text(
            uiString(R.string.calorie_tracking_dynamic_hrr_blurb),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )

        CountKnob(
            R.string.calorie_tracking_dhrr_wear_session_max_silence_label,
            R.string.calorie_tracking_dhrr_wear_session_max_silence_detail,
            profile.calorieDhrrWearSessionMaxSilenceS, step = 60,
        ) { profile.calorieDhrrWearSessionMaxSilenceS = it; onEdited() }
        ShareKnob(
            R.string.calorie_tracking_dhrr_min_coverage_label,
            R.string.calorie_tracking_dhrr_min_coverage_detail,
            profile.calorieDhrrMinHrCoverageFrac,
        ) { profile.calorieDhrrMinHrCoverageFrac = it; onEdited() }

        CountKnob(
            R.string.calorie_tracking_dhrr_spike_window_radius_label,
            R.string.calorie_tracking_dhrr_spike_window_radius_detail,
            profile.calorieDhrrSpikeWindowRadiusS, step = 1,
        ) { profile.calorieDhrrSpikeWindowRadiusS = it; onEdited() }
        NumberKnob(
            R.string.calorie_tracking_dhrr_spike_threshold_sigmas_label,
            R.string.calorie_tracking_dhrr_spike_threshold_sigmas_detail,
            profile.calorieDhrrSpikeThresholdSigmas, step = 0.5, decimals = 1,
        ) { profile.calorieDhrrSpikeThresholdSigmas = it; onEdited() }
        SettingsToggleRow(
            title = uiString(R.string.calorie_tracking_dhrr_peak_clip_enabled_label),
            detail = uiString(R.string.calorie_tracking_dhrr_peak_clip_enabled_detail),
            checked = profile.calorieDhrrPeakClipEnabled,
            onCheckedChange = { profile.calorieDhrrPeakClipEnabled = it; onEdited() },
        )
        CountKnob(
            R.string.calorie_tracking_dhrr_peak_clip_block_label,
            R.string.calorie_tracking_dhrr_peak_clip_block_detail,
            profile.calorieDhrrPeakClipBlockS, step = 30,
        ) { profile.calorieDhrrPeakClipBlockS = it; onEdited() }
        ShareKnob(
            R.string.calorie_tracking_dhrr_peak_clip_kept_label,
            R.string.calorie_tracking_dhrr_peak_clip_kept_detail,
            profile.calorieDhrrPeakClipKeptFrac,
        ) { profile.calorieDhrrPeakClipKeptFrac = it; onEdited() }

        NumberKnob(
            R.string.calorie_tracking_dhrr_still_max_label,
            R.string.calorie_tracking_dhrr_still_max_detail,
            profile.calorieDhrrStillMaxG, step = 0.005, decimals = 3,
        ) { profile.calorieDhrrStillMaxG = it; onEdited() }
        CountKnob(
            R.string.calorie_tracking_dhrr_still_smoothing_label,
            R.string.calorie_tracking_dhrr_still_smoothing_detail,
            profile.calorieDhrrStillSmoothingS, step = 1,
        ) { profile.calorieDhrrStillSmoothingS = it; onEdited() }
        CountKnob(
            R.string.calorie_tracking_dhrr_quiet_stretch_min_length_label,
            R.string.calorie_tracking_dhrr_quiet_stretch_min_length_detail,
            profile.calorieDhrrQuietStretchMinLengthS, step = 30,
        ) { profile.calorieDhrrQuietStretchMinLengthS = it; onEdited() }
        NumberKnob(
            R.string.calorie_tracking_dhrr_quiet_stretch_max_rise_label,
            R.string.calorie_tracking_dhrr_quiet_stretch_max_rise_detail,
            profile.calorieDhrrQuietStretchMaxRiseBpm, step = 1.0, decimals = 0,
        ) { profile.calorieDhrrQuietStretchMaxRiseBpm = it; onEdited() }
        ShareKnob(
            R.string.calorie_tracking_dhrr_quiet_stretch_min_still_label,
            R.string.calorie_tracking_dhrr_quiet_stretch_min_still_detail,
            profile.calorieDhrrQuietStretchMinStillFrac,
        ) { profile.calorieDhrrQuietStretchMinStillFrac = it; onEdited() }
        ShareKnob(
            R.string.calorie_tracking_dhrr_quiet_stretch_min_beat_label,
            R.string.calorie_tracking_dhrr_quiet_stretch_min_beat_detail,
            profile.calorieDhrrQuietStretchMinBeatFrac,
        ) { profile.calorieDhrrQuietStretchMinBeatFrac = it; onEdited() }
        CountKnob(
            R.string.calorie_tracking_dhrr_basal_lower_window_label,
            R.string.calorie_tracking_dhrr_basal_lower_window_detail,
            profile.calorieDhrrBasalLowerWindowS, step = 5,
        ) { profile.calorieDhrrBasalLowerWindowS = it; onEdited() }
        CountKnob(
            R.string.calorie_tracking_dhrr_basal_lower_readings_label,
            R.string.calorie_tracking_dhrr_basal_lower_readings_detail,
            profile.calorieDhrrBasalLowerMinSamples, step = 1,
        ) { profile.calorieDhrrBasalLowerMinSamples = it; onEdited() }
        NumberKnob(
            R.string.calorie_tracking_dhrr_basal_seed_offset_label,
            R.string.calorie_tracking_dhrr_basal_seed_offset_detail,
            profile.calorieDhrrBasalSeedOffsetBpm, step = 1.0, decimals = 0,
        ) { profile.calorieDhrrBasalSeedOffsetBpm = it; onEdited() }

        NumberKnob(
            R.string.calorie_tracking_dhrr_reserve_ramp_label,
            R.string.calorie_tracking_dhrr_reserve_ramp_detail,
            profile.calorieDhrrReserveRampBandBpm, step = 1.0, decimals = 0,
        ) { profile.calorieDhrrReserveRampBandBpm = it; onEdited() }

        NumberKnob(
            R.string.calorie_tracking_dhrr_resting_energy_label,
            R.string.calorie_tracking_dhrr_resting_energy_detail,
            profile.calorieDhrrRestingEnergyKcalPerDay, step = 25.0, decimals = 0,
        ) { profile.calorieDhrrRestingEnergyKcalPerDay = it; onEdited() }
        ShareKnob(
            R.string.calorie_tracking_dhrr_resting_fat_night_label,
            R.string.calorie_tracking_dhrr_resting_fat_night_detail,
            profile.calorieDhrrRestingFatNightFrac,
        ) { profile.calorieDhrrRestingFatNightFrac = it; onEdited() }
        ShareKnob(
            R.string.calorie_tracking_dhrr_resting_fat_day_label,
            R.string.calorie_tracking_dhrr_resting_fat_day_detail,
            profile.calorieDhrrRestingFatDayFrac,
        ) { profile.calorieDhrrRestingFatDayFrac = it; onEdited() }
        NumberKnob(
            R.string.calorie_tracking_dhrr_resting_fat_day_start_label,
            R.string.calorie_tracking_dhrr_resting_fat_day_start_detail,
            profile.calorieDhrrRestingFatDayStartHour, step = 1.0, decimals = 0,
        ) { profile.calorieDhrrRestingFatDayStartHour = it; onEdited() }
        NumberKnob(
            R.string.calorie_tracking_dhrr_resting_fat_day_end_label,
            R.string.calorie_tracking_dhrr_resting_fat_day_end_detail,
            profile.calorieDhrrRestingFatDayEndHour, step = 1.0, decimals = 0,
        ) { profile.calorieDhrrRestingFatDayEndHour = it; onEdited() }
        ShareKnob(
            R.string.calorie_tracking_dhrr_active_fat_zone1_label,
            R.string.calorie_tracking_dhrr_active_fat_zone1_detail,
            profile.calorieDhrrActiveFatZone1Frac,
        ) { profile.calorieDhrrActiveFatZone1Frac = it; onEdited() }
        ShareKnob(
            R.string.calorie_tracking_dhrr_active_fat_zone2_top_label,
            R.string.calorie_tracking_dhrr_active_fat_zone2_top_detail,
            profile.calorieDhrrActiveFatZone2TopFrac,
        ) { profile.calorieDhrrActiveFatZone2TopFrac = it; onEdited() }
    }
}

// MARK: - Preview

@Composable
private fun PreviewSection(
    day: LocalDate,
    preview: CalorieDay?,
    /** Whether the wearer has asked for NOOP's own figure in preference to a phone's. */
    preferOnDevice: Boolean,
    error: String?,
    running: Boolean,
    onDayChange: (LocalDate) -> Unit,
    onRun: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        Overline(uiString(R.string.calorie_tracking_preview_overline))
        Text(
            uiString(R.string.calorie_tracking_preview_blurb),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )

        SettingsFormRow(label = uiString(R.string.calorie_tracking_preview_day_label)) {
            StepperField(
                value = day.toString(),
                accessibility = uiString(R.string.calorie_tracking_preview_day_a11y, day.toString()),
                onMinus = { onDayChange(day.minusDays(1)) },
                // A future day has no data, so the forward step stops at today.
                onPlus = { if (day.isBefore(LocalDate.now())) onDayChange(day.plusDays(1)) },
            )
        }

        NoopButton(
            text = if (running) {
                uiString(R.string.calorie_tracking_preview_running)
            } else {
                uiString(R.string.calorie_tracking_preview_button)
            },
            enabled = !running,
            onClick = onRun,
        )

        if (error != null) {
            Text(
                uiString(R.string.calorie_tracking_preview_failed, error),
                style = NoopType.footnote,
                color = Palette.statusCritical,
            )
        }
        if (preview != null) PreviewResult(preview, preferOnDevice)
    }
}

@Composable
private fun PreviewResult(p: CalorieDay, preferOnDevice: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (p.trace.hrSampleCount == 0) {
            Text(
                uiString(R.string.calorie_tracking_preview_no_data),
                style = NoopType.footnote,
                color = Palette.metricAmber,
            )
        }

        // Only when the phone's figure is the one the tile actually shows.
        importedWinsKcal(p.onDeviceKcal, p.importedKcal, preferOnDevice)?.let { imported ->
            Text(
                uiString(R.string.calorie_tracking_preview_imported_wins, kcal(imported)),
                style = NoopType.footnote,
                color = Palette.metricAmber,
            )
        }

        if (p.trace.hrSampleCount > 0) {
            Overline(uiString(modelNameRes(p.model)))
            PreviewLine(uiString(R.string.calorie_tracking_preview_active), kcal(p.timeline.dayActiveKcal))
            PreviewLine(uiString(R.string.calorie_tracking_preview_basal), kcal(p.timeline.dayBasalKcal))
            PreviewLine(uiString(R.string.calorie_tracking_preview_total), kcal(p.timeline.dayTotalKcal))

            // The MET statistics belong to the hybrid alone: a heart-rate model has no MET to
            // attribute and no minutes to band.
            if (p.model == EnergyModel.HYBRID) {
                val h = p.timeline.extras
                PreviewLine(
                    uiString(R.string.calorie_tracking_preview_active_minutes),
                    minutes(h.getValue(HybridModel.Extra.ACTIVE_MINUTES)),
                )
                PreviewLine(
                    uiString(R.string.calorie_tracking_preview_coverage),
                    uiString(
                        R.string.calorie_tracking_preview_coverage_value,
                        minutes(h.getValue(HybridModel.Extra.COVERAGE_MINUTES)),
                        minutes(h.getValue(HybridModel.Extra.NON_WEAR_MINUTES)),
                    ),
                )
            }
        }

        PreviewLine(
            uiString(R.string.calorie_tracking_preview_inputs),
            uiString(
                R.string.calorie_tracking_preview_inputs_value,
                p.trace.hrSampleCount,
                p.trace.motionSampleCount,
                p.trace.workouts.size,
            ),
        )
        PreviewLine(uiString(R.string.calorie_tracking_preview_owner), p.owner)
        Text(
            uiString(R.string.calorie_tracking_preview_footnote),
            style = NoopType.caption,
            color = Palette.textTertiary,
        )
    }
}

@Composable
private fun PreviewLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, style = NoopType.footnote, color = Palette.textTertiary, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(12.dp))
        Text(value, style = NoopType.captionNumber, color = Palette.textPrimary, textAlign = TextAlign.End)
    }
}

// MARK: - Knob rows

@Composable
private fun PercentKnob(label: String, value: Double, onChange: (Double) -> Unit) {
    SettingsFormRow(label = label) {
        StepperField(
            value = percent(value),
            accessibility = uiString(R.string.calorie_tracking_knob_a11y, label, percent(value)),
            onMinus = { onChange(clampHrr(value - HRR_STEP)) },
            onPlus = { onChange(clampHrr(value + HRR_STEP)) },
        )
    }
}


@Composable
private fun MetKnob(label: String, value: Double, step: Double, onChange: (Double) -> Unit) {
    val shown = oneDecimal(value)
    SettingsFormRow(label = label) {
        StepperField(
            value = shown,
            accessibility = uiString(R.string.calorie_tracking_knob_a11y, label, shown),
            onMinus = { onChange(value - step) },
            onPlus = { onChange(value + step) },
        )
    }
}

/** A whole-number setting, with the line that says what it does under it. */
@Composable
private fun CountKnob(labelRes: Int, detailRes: Int, value: Int, step: Int, onChange: (Int) -> Unit) {
    val label = uiString(labelRes)
    val shown = value.toString()
    SettingsFormRow(label = label) {
        StepperField(
            value = shown,
            accessibility = uiString(R.string.calorie_tracking_knob_a11y, label, shown),
            onMinus = { onChange(value - step) },
            onPlus = { onChange(value + step) },
        )
    }
    KnobDetail(uiString(detailRes))
}

/** A decimal setting, shown to [decimals] places. */
@Composable
private fun NumberKnob(
    labelRes: Int,
    detailRes: Int,
    value: Double,
    step: Double,
    decimals: Int,
    onChange: (Double) -> Unit,
) {
    val label = uiString(labelRes)
    val shown = String.format(Locale.US, "%.${decimals}f", value)
    SettingsFormRow(label = label) {
        StepperField(
            value = shown,
            accessibility = uiString(R.string.calorie_tracking_knob_a11y, label, shown),
            onMinus = { onChange(value - step) },
            onPlus = { onChange(value + step) },
        )
    }
    KnobDetail(uiString(detailRes))
}

/** A share of a whole, edited and shown as a percentage. */
@Composable
private fun ShareKnob(labelRes: Int, detailRes: Int, value: Double, onChange: (Double) -> Unit) {
    val label = uiString(labelRes)
    val shown = percent(value)
    SettingsFormRow(label = label) {
        StepperField(
            value = shown,
            accessibility = uiString(R.string.calorie_tracking_knob_a11y, label, shown),
            onMinus = { onChange(value - SHARE_STEP) },
            onPlus = { onChange(value + SHARE_STEP) },
        )
    }
    KnobDetail(uiString(detailRes))
}

@Composable
private fun KnobDetail(text: String) {
    Text(text, style = NoopType.caption, color = Palette.textTertiary)
}

// MARK: - Steps, ranges and formatting

/** One percentage point of heart-rate reserve, which places a gate between sitting and walking. */
private const val HRR_STEP = 0.01

/** One percentage point, the step every share setting moves by. */
private const val SHARE_STEP = 0.01
private const val MET_STEP = 0.1
private const val MET_GAIN_STEP = 1.0

/** Motion magnitude of a brisk walk (g), the anchor the MET gain is quoted against. */
private const val WALK_ANCHOR_G = 0.1

/**
 * The resting rate the gate's bpm is rendered from before a preview has run.
 *
 * A stand-in for the label only, equal to what the engines fall back to for a day with no measured
 * value.
 */
private val DEFAULT_PREVIEW_RESTING_HR = StrainScorer.defaultRestingHR

private fun clampHrr(v: Double): Double =
    v.coerceIn(HeartRateGatesRanges.HRR_MIN, HeartRateGatesRanges.HRR_MAX)

private fun percent(fraction: Double): String =
    String.format(Locale.US, "%d%%", (fraction * 100).roundToInt())

private fun oneDecimal(v: Double): String = String.format(Locale.US, "%.1f", v)

private fun kcal(v: Double?): String =
    if (v == null) "—" else String.format(Locale.US, "%,d kcal", v.roundToInt())

private fun minutes(v: Double): String = String.format(Locale.US, "%,d min", v.roundToInt())
