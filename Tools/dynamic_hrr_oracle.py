#!/usr/bin/env python3
"""Reference implementation of the measured-basal calorie model, from the notebook it came from.

Sections 4 and 5 of `analysis/rr_intervals.ipynb` describe a day-energy model NOOP now ships in
Kotlin as `DynamicHrrModel`. The two were written from the same description rather than from each
other, so running both over one day and comparing the answer is what stops the port drifting; the
repository asks for exactly that whenever a computation has a second implementation.

Standard library only, so it runs anywhere the repository does. Run it to print the day figures and
the per-minute series for the synthetic day below; those numbers are the expected literals in
`DynamicHrrOracleTest`.
"""
from __future__ import annotations

import math

NOT_MEASURED = float("nan")

# Thermochemistry: kcal released per litre of oxygen on pure fat and on pure carbohydrate.
E_FAT = 4.686
E_CHO = 5.047
# The brain's share of resting metabolism, which runs on glucose alone.
BRAIN_BASAL_FRACTION = 0.20
KCAL_PER_G_FAT = 9.4
KCAL_PER_G_CHO = 4.1
# The hour the overnight fat plateau is held from, and the axis the diurnal curve wraps onto.
NIGHT_ANCHOR_HOUR = 2.0
# How many of the smoothed seconds must carry a motion reading before the mean stands.
HR_GAP_FILL_MAX_S = 60
MOTION_SMOOTH_MIN_SAMPLES = 3


class Setting:
    """One wearer's settings, defaulting to the shipped values."""

    def __init__(self, **overrides):
        self.session_gap_s = 1800
        self.min_hr_coverage_frac = 0.5
        self.hampel_radius_s = 5
        self.hampel_sigmas = 3.0
        self.suppress_peaks = True
        self.peak_block_s = 300
        self.peak_percentile = 0.97
        self.motion_still_g = 0.02
        self.motion_smooth_s = 10
        self.basal_min_window_s = 300
        self.basal_hr_range_bpm = 10.0
        self.basal_repair_grace_s = 300
        self.basal_still_frac = 0.98
        self.basal_beat_coverage_frac = 0.5
        self.rest_smooth_s = 30
        self.rest_smooth_min_samples = 20
        self.basal_hr_seed_offset_bpm = 5.0
        self.reserve_ramp_band_bpm = 10.0
        self.measured_basal_kcal_day = 0.0
        self.basal_fat_night = 0.80
        self.basal_fat_day = 0.30
        self.basal_fat_day_start_hour = 10.0
        self.basal_fat_day_end_hour = 20.0
        self.active_fat_at_zone1 = 1.0
        self.active_fat_at_zone2_top = 0.66
        for key, value in overrides.items():
            setattr(self, key, value)


# --------------------------------------------------------------------------- body


# Revised Harris-Benedict coefficients: alpha, weight, height (metres), age. The third set is the
# midpoint of the first two.
RESTING_COEFFICIENTS = {
    "male": (88.362, 13.397, 479.9, 5.677),
    "female": (447.593, 9.247, 309.8, 4.33),
    "nonbinary": (267.9775, 11.322, 394.85, 5.0035),
}


def resting_kcal_per_s(sex: str, weight_kg: float, height_cm: float, age: float) -> float:
    """Revised Harris-Benedict basal metabolic rate, per second."""
    alpha, weight, height, age_term = RESTING_COEFFICIENTS.get(sex.lower(), RESTING_COEFFICIENTS["nonbinary"])
    bmr = alpha + weight * weight_kg + height * (height_cm / 100.0) - age_term * age
    return max(0.0, bmr) / 86400.0


def vo2max_uth(hrmax: float, resting_hr: float) -> float:
    """Uth-Sorensen estimate of maximal oxygen uptake, ml/kg/min."""
    return 15.3 * hrmax / resting_hr


def zone_anchors(hrmax: float, lower_bounds=None) -> tuple[float, float, float]:
    """Bottom of zone 1, top of zone 2 and bottom of zone 5, in bpm.

    `lower_bounds` are the wearer's own five zone starts; without them the conventional
    percentages of the maximum apply.
    """
    if lower_bounds is not None:
        return lower_bounds[0], lower_bounds[2], lower_bounds[4]
    return 0.50 * hrmax, 0.70 * hrmax, 0.90 * hrmax


# --------------------------------------------------------------------------- fuel


RESERVE_RAMP_WEIGHTS = (0.25, 0.50, 0.75)


def ramp_weighted(bpm: float, resting_hr: float, band_bpm: float) -> float:
    """The weighted beats between the resting rate and bpm; see HrReserveRamp."""
    remaining = bpm - resting_hr
    total = 0.0
    for weight in RESERVE_RAMP_WEIGHTS:
        if remaining <= band_bpm:
            return total + weight * remaining
        total += weight * band_bpm
        remaining -= band_bpm
    return total + remaining


def kcal_per_litre_o2(fat_fraction: float) -> float:
    f = min(1.0, max(0.0, fat_fraction))
    return 1.0 / (f / E_FAT + (1.0 - f) / E_CHO)


def interpolate(xs: list[float], ys: list[float], x: float) -> float:
    if x <= xs[0]:
        return ys[0]
    for i in range(1, len(xs)):
        if x > xs[i]:
            continue
        width = xs[i] - xs[i - 1]
        if width <= 0.0:
            return ys[i]
        return ys[i - 1] + (ys[i] - ys[i - 1]) * (x - xs[i - 1]) / width
    return ys[-1]


def basal_fat_fraction(setting: Setting, hour_of_day: float) -> float:
    day_start = min(max(setting.basal_fat_day_start_hour, NIGHT_ANCHOR_HOUR + 1.0), NIGHT_ANCHOR_HOUR + 24.0)
    day_end = min(max(setting.basal_fat_day_end_hour, day_start), NIGHT_ANCHOR_HOUR + 24.0)
    hours = [NIGHT_ANCHOR_HOUR, day_start - 1.0, day_start, day_end, NIGHT_ANCHOR_HOUR + 24.0]
    shares = [setting.basal_fat_night, setting.basal_fat_night,
              setting.basal_fat_day, setting.basal_fat_day, setting.basal_fat_night]
    wrapped = hour_of_day + 24.0 if hour_of_day < hours[0] else hour_of_day
    return interpolate(hours, shares, wrapped) * (1.0 - BRAIN_BASAL_FRACTION)


def active_fat_fraction(setting: Setting, hrmax: float, bpm: float, zone_lower_bounds=None) -> float:
    z1, z2_top, z5 = zone_anchors(hrmax, zone_lower_bounds)
    share = interpolate([z1, z2_top, z5],
                        [setting.active_fat_at_zone1, setting.active_fat_at_zone2_top, 0.0], bpm)
    return min(1.0, max(0.0, share))


# --------------------------------------------------------------------------- signals


def median_of(values: list[float]) -> float:
    if not values:
        return NOT_MEASURED
    ordered = sorted(values)
    middle = len(ordered) // 2
    if len(ordered) % 2 == 1:
        return ordered[middle]
    return (ordered[middle - 1] + ordered[middle]) / 2.0


def window_median(values, session, lo, hi) -> float:
    first, last = session
    return median_of([values[i] for i in range(max(first, lo), min(last, hi) + 1)
                      if not math.isnan(values[i])])


def hampel(values, sessions, radius_s, sigmas):
    out = list(values)
    median = [NOT_MEASURED] * len(values)
    deviation = [NOT_MEASURED] * len(values)
    for session in sessions:
        for i in range(session[0], session[1] + 1):
            median[i] = window_median(values, session, i - radius_s, i + radius_s)
            if not math.isnan(values[i]) and not math.isnan(median[i]):
                deviation[i] = abs(values[i] - median[i])
        for i in range(session[0], session[1] + 1):
            robust_sigma = 1.4826 * window_median(deviation, session, i - radius_s, i + radius_s)
            if robust_sigma > 0.0 and deviation[i] > sigmas * robust_sigma:
                out[i] = median[i]
    return out


def clip_block_peaks(values, sessions, window_start_utc, block_s, percentile):
    out = list(values)
    for session in sessions:
        block_start = session[0]
        while block_start <= session[1]:
            block_last = ((window_start_utc + block_start) // block_s + 1) * block_s - window_start_utc - 1
            block_end = min(session[1], block_last)
            _clip_block(values, out, block_start, block_end, percentile)
            block_start = block_end + 1
    return out


def _clip_block(values, out, block_start, block_end, percentile):
    """Pulls one block's readings down to its own percentile, reading nothing outside the block."""
    present = [values[i] for i in range(block_start, block_end + 1) if not math.isnan(values[i])]
    if not present:
        return
    present.sort()
    # Nearest rank, counting from one, so the two languages cannot disagree about an interpolation.
    rank = min(max(math.ceil(percentile * len(present)), 1), len(present))
    ceiling = present[rank - 1]
    for i in range(block_start, block_end + 1):
        if not math.isnan(values[i]) and values[i] > ceiling:
            out[i] = ceiling


def trailing_mean(values, sessions, width_s, min_samples):
    out = [NOT_MEASURED] * len(values)
    for session in sessions:
        for i in range(session[0], session[1] + 1):
            kept = [values[j] for j in range(max(session[0], i - width_s + 1), i + 1)
                    if not math.isnan(values[j])]
            if len(kept) >= min_samples:
                out[i] = sum(kept) / len(kept)
    return out


def trailing_median(values, sessions, width_s, min_samples):
    out = [NOT_MEASURED] * len(values)
    for session in sessions:
        for i in range(session[0], session[1] + 1):
            kept = [values[j] for j in range(max(session[0], i - width_s + 1), i + 1)
                    if not math.isnan(values[j])]
            if len(kept) >= min_samples:
                out[i] = median_of(kept)
    return out


def quiet_window_min_hr(hr, sessions, still, beat, setting):
    out = [NOT_MEASURED] * len(hr)
    still_count = _prefix_count(still)
    beat_count = _prefix_count(beat)
    for session in sessions:
        start = session[0]
        lowest = NOT_MEASURED
        measured = NOT_MEASURED
        last_live = -1
        paused_since = -1

        def close():
            if last_live >= 0 and last_live - start + 1 >= setting.basal_min_window_s:
                out[last_live] = measured

        for r in range(session[0], session[1] + 1):
            # Read against the stretch behind this second, before this second joins it.
            if hr[r] - lowest > setting.basal_hr_range_bpm:
                close()
                start, lowest, measured, last_live, paused_since = (
                    r, NOT_MEASURED, NOT_MEASURED, -1, -1,
                )
            if not math.isnan(hr[r]) and (math.isnan(lowest) or hr[r] < lowest):
                lowest = hr[r]
            span = r - start + 1
            still_share = (still_count[r + 1] - still_count[start]) / span
            beat_share = (beat_count[r + 1] - beat_count[start]) / span
            if still_share >= setting.basal_still_frac and beat_share >= setting.basal_beat_coverage_frac:
                last_live, measured, paused_since = r, lowest, -1
            else:
                if paused_since < 0:
                    paused_since = r
                if r - paused_since + 1 > setting.basal_repair_grace_s:
                    close()
                    start, lowest, measured, last_live, paused_since = (
                        r + 1, NOT_MEASURED, NOT_MEASURED, -1, -1,
                    )
        close()
    return out


def interpolated_short_gaps(values, max_gap_s):
    out = list(values)
    last = -1
    for i, v in enumerate(values):
        if math.isnan(v):
            continue
        if last >= 0 and 1 <= i - last - 1 <= max_gap_s:
            span = float(i - last)
            for j in range(last + 1, i):
                out[j] = values[last] + (values[i] - values[last]) * (j - last) / span
        last = i
    return out


def _interpolated_gaps(values):
    out = list(values)
    last = -1
    for i, v in enumerate(values):
        if math.isnan(v):
            continue
        if last < 0:
            for j in range(0, i):
                out[j] = v
        else:
            span = float(i - last)
            for j in range(last + 1, i):
                out[j] = values[last] + (v - values[last]) * (j - last) / span
        last = i
    if last >= 0:
        for j in range(last + 1, len(values)):
            out[j] = values[last]
    return out


def _ramp_to(out, readings, start, r, prev, new):
    total = sum(max(0.0, readings[t] - prev) for t in range(start, r))
    if total <= 0.0:
        return
    weighed = 0.0
    for t in range(start, r):
        weighed += max(0.0, readings[t] - prev)
        ramped = prev + (new - prev) * (weighed / total)
        out[t] = max(prev, min(ramped, readings[t]))


def smooth_basal_raises(basal_hr, quiet_window_hr, hr):
    """Each raise ramped back over the stretch that produced it; see the note in the model."""
    out = list(basal_hr)
    first_reading = next((i for i, v in enumerate(hr) if not math.isnan(v)), -1)
    if first_reading < 0:
        return out
    readings = _interpolated_gaps(hr)
    last_change = -1
    last_report = -1
    for r in range(len(basal_hr)):
        reported = not math.isnan(quiet_window_hr[r])
        if reported and r > 0 and basal_hr[r] > basal_hr[r - 1]:
            start = max(max(last_change, last_report) + 1, first_reading)
            _ramp_to(out, readings, start, r, basal_hr[r - 1], basal_hr[r])
        if r > 0 and basal_hr[r] != basal_hr[r - 1]:
            last_change = r
        if reported:
            last_report = r
    return out


def _prefix_count(flags):
    out = [0] * (len(flags) + 1)
    for i, flag in enumerate(flags):
        out[i + 1] = out[i] + (1 if flag else 0)
    return out


def track_basal_hr(quiet_window_hr, ratchet_hr, seed_bpm):
    out = [NOT_MEASURED] * len(quiet_window_hr)
    current = seed_bpm
    for i in range(len(quiet_window_hr)):
        if not math.isnan(quiet_window_hr[i]):
            current = quiet_window_hr[i]
        elif not math.isnan(current) and not math.isnan(ratchet_hr[i]):
            current = min(current, ratchet_hr[i])
        out[i] = current
    return out


# --------------------------------------------------------------------------- the model


def score_day(start_utc, end_utc, hr_samples, motion_samples, beat_seconds,
              weight_kg, height_cm, age, hrmax, resting_hr, setting,
              sex="male", vo2max_override=0.0, zone_lower_bounds=None):
    """The day's per-minute energy and basal heart rate, or None where the model declines the day."""
    seconds = end_utc - start_utc
    hr = [NOT_MEASURED] * seconds
    for ts, bpm in hr_samples:
        if start_utc <= ts < end_utc and math.isnan(hr[ts - start_utc]):
            hr[ts - start_utc] = float(bpm)

    sessions, first, previous = [], -1, -1
    for i in range(seconds):
        if math.isnan(hr[i]):
            continue
        if first < 0:
            first = i
        elif i - previous > setting.session_gap_s:
            sessions.append((first, previous))
            first = i
        previous = i
    if first >= 0:
        sessions.append((first, previous))

    covered = sum(1 for s in sessions for i in range(s[0], s[1] + 1) if not math.isnan(hr[i]))
    total = sum(s[1] - s[0] + 1 for s in sessions)
    coverage = covered / total if total else 0.0
    if not sessions:
        return None
    filled = interpolated_short_gaps(hr, HR_GAP_FILL_MAX_S)

    motion = [NOT_MEASURED] * seconds
    for ts, g in motion_samples:
        if g is None or not (start_utc <= ts < end_utc) or not math.isnan(motion[ts - start_utc]):
            continue
        motion[ts - start_utc] = g
    vo2max_ml = vo2max_override if vo2max_override > 0.0 else (
        vo2max_uth(hrmax, resting_hr) if resting_hr else None)
    vo2max_l_per_min = vo2max_ml * weight_kg / 1000.0 if vo2max_ml is not None else None
    if vo2max_l_per_min is None:
        return None

    beat = [False] * seconds
    for ts in beat_seconds:
        if start_utc <= ts < end_utc:
            beat[ts - start_utc] = True

    filtered = hampel(filled, sessions, setting.hampel_radius_s, setting.hampel_sigmas)
    settled = clip_block_peaks(filtered, sessions, start_utc, setting.peak_block_s,
                               setting.peak_percentile) \
        if setting.suppress_peaks else filtered

    smoothed = trailing_mean(motion, sessions, setting.motion_smooth_s, MOTION_SMOOTH_MIN_SAMPLES)
    still = [not math.isnan(v) and v <= setting.motion_still_g for v in smoothed]
    # Read before the peaks are clipped; see the note in the model.
    windows = quiet_window_min_hr(filtered, sessions, still, beat, setting)
    ratchet = trailing_median(settled, sessions, setting.rest_smooth_s, setting.rest_smooth_min_samples)
    seed = resting_hr + setting.basal_hr_seed_offset_bpm if resting_hr else NOT_MEASURED

    # None where there is nothing to pin the bands to, which counts every beat in full.
    ramp = None
    if resting_hr and resting_hr > 0.0 and setting.reserve_ramp_band_bpm > 0.0:
        def ramp(bpm, _r=float(resting_hr), _b=setting.reserve_ramp_band_bpm):
            return ramp_weighted(bpm, _r, _b)

    basal_hr = smooth_basal_raises(track_basal_hr(windows, ratchet, seed), windows, filtered)
    quiet_windows = sum(1 for v in windows if not math.isnan(v))
    if quiet_windows == 0 and math.isnan(seed):
        return None

    basal_kcal_per_s = (setting.measured_basal_kcal_day / 86400.0
                        if setting.measured_basal_kcal_day > 0.0
                        else resting_kcal_per_s(sex, weight_kg, height_cm, age))
    minutes = (seconds + 59) // 60
    active_kcal = [0.0] * minutes
    total_kcal = [0.0] * minutes
    fat_kcal = [0.0] * minutes
    delta_hr_sum = [0.0] * minutes
    delta_hr_count = [0] * minutes
    minute_basal_hr = [None] * minutes
    final_basal_hr = NOT_MEASURED

    for i in range(seconds):
        minute = i // 60
        fat_share_of_basal = basal_fat_fraction(setting, (i // 60) / 60.0)
        basal_vo2 = basal_kcal_per_s * 60.0 / kcal_per_litre_o2(fat_share_of_basal)
        active_this_second = 0.0
        fat_share_of_active = 0.0
        bpm, anchor = settled[i], basal_hr[i]
        if not math.isnan(bpm) and not math.isnan(hr[i]):
            delta_hr_sum[minute] += bpm - hr[i]
            delta_hr_count[minute] += 1
        if not math.isnan(bpm) and not math.isnan(anchor):
            final_basal_hr = anchor
            if ramp is None:
                reserve, above = hrmax - anchor, bpm - anchor
            else:
                reserve = ramp(hrmax) - ramp(anchor)
                above = ramp(bpm) - ramp(anchor)
            fraction = min(1.0, max(0.0, above / reserve)) if reserve > 0.0 else 0.0
            active_vo2 = max(0.0, fraction * (vo2max_l_per_min - basal_vo2))
            fat_share_of_active = active_fat_fraction(setting, hrmax, bpm, zone_lower_bounds)
            active_this_second = active_vo2 * kcal_per_litre_o2(fat_share_of_active) / 60.0
        active_kcal[minute] += active_this_second
        total_kcal[minute] += basal_kcal_per_s + active_this_second
        fat_kcal[minute] += basal_kcal_per_s * fat_share_of_basal + active_this_second * fat_share_of_active
        if not math.isnan(anchor):
            minute_basal_hr[minute] = anchor

    day_active = 0.0
    day_total = 0.0
    day_fat = 0.0
    fat_percentage = []
    delta_hr = []
    for m in range(minutes):
        day_active += active_kcal[m]
        day_total += total_kcal[m]
        day_fat += fat_kcal[m]
        share = fat_kcal[m] / total_kcal[m] if total_kcal[m] > 0.0 else None
        fat_percentage.append(None if share is None else share * 100.0)
        delta_hr.append(delta_hr_sum[m] / delta_hr_count[m] if delta_hr_count[m] > 0 else None)

    return {
        "totalKcal": day_total,
        "activeKcal": day_active,
        "quietWindowCount": float(quiet_windows),
        # Only where a stretch measured it; with none the anchor was the seed the day assumed.
        "finalBasalHrBpm": final_basal_hr if quiet_windows > 0 else NOT_MEASURED,
        "fatGrams": day_fat / KCAL_PER_G_FAT,
        "choGrams": (day_total - day_fat) / KCAL_PER_G_CHO,
        "hrCoverageFrac": coverage,
        "minuteActiveKcal": active_kcal,
        "minuteBasalHrBpm": minute_basal_hr,
        "minuteDeltaHrBpm": delta_hr,
        "minuteFatPercentage": fat_percentage,
    }


# --------------------------------------------------------------------------- the fixtures


class Fixture:
    """One synthetic wearer and one synthetic window, described the same way in both languages."""

    def __init__(self, name, span_s, worn, bpm_at, motion_at, has_beat, body, setting,
                 sample_every=1):
        self.name = name
        self.span_s = span_s
        # Print one minute in this many. A whole day's four series are a hundred thousand digits;
        # the day figures already integrate every second, so the series are pinned as a spread.
        self.sample_every = sample_every
        # Half-open second ranges the strap was worn over.
        self.worn = worn
        self.bpm_at = bpm_at
        self.motion_at = motion_at
        self.has_beat = has_beat
        self.body = body
        self.setting = setting

    def seconds(self):
        return [i for lo, hi in self.worn for i in range(lo, hi)]

    def score(self):
        worn = self.seconds()
        return score_day(
            start_utc=0,
            end_utc=self.span_s,
            hr_samples=[(i, self.bpm_at(i)) for i in worn],
            motion_samples=[(i, self.motion_at(i)) for i in worn],
            beat_seconds=[i for i in worn if self.has_beat(i)],
            setting=self.setting,
            **self.body,
        )


def _quiet_a(i: int) -> bool:
    return i < 1800 or 3600 <= i < 5400 or 30600 <= i < 34200 or 37800 <= i < 41400


def _bpm_a(i: int) -> int:
    """A day that alternates quiet and working stretches, morning and midday."""
    if i < 1800:
        return 52
    if i < 3600:
        return 100 + (i % 37)
    if i < 5400:
        return 56
    if i < 7200:
        return 130 - (i % 23)
    if i < 34200:
        return 54
    if i < 37800:
        return 110 + (i % 31)
    return 58


def _bpm_b(i: int) -> int:
    if i < 2400:
        return 64
    if i < 6000:
        return 96 + (i % 19)
    return 68


def _quiet_b(i: int) -> bool:
    return i < 2400 or i >= 6000


# A whole day, so the diurnal fuel curve is read at every hour and the two wear sessions are split
# on the silence between them.
FIXTURE_A = Fixture(
    name="A",
    span_s=86400,
    worn=[(0, 7200), (30600, 41400)],
    bpm_at=_bpm_a,
    motion_at=lambda i: 0.005 if _quiet_a(i) else 0.4,
    has_beat=lambda i: i % 3 != 0,
    body=dict(weight_kg=80.0, height_cm=180.0, age=35.0, sex="male",
              hrmax=180.0, resting_hr=50.0, vo2max_override=0.0, zone_lower_bounds=None),
    setting=Setting(),
    sample_every=60,
)

# A second wearer, so the inputs fixture A holds at their defaults all move: a different sex and so
# different resting coefficients, a measured maximal oxygen uptake in place of the estimate, the
# wearer's own zone starts under the active fuel curve, and every setting off its shipped value.
FIXTURE_B = Fixture(
    name="B",
    span_s=10800,
    worn=[(0, 10800)],
    bpm_at=_bpm_b,
    motion_at=lambda i: 0.02 if _quiet_b(i) else 0.5,
    has_beat=lambda i: i % 2 == 0,
    body=dict(weight_kg=58.0, height_cm=164.0, age=48.0, sex="female",
              hrmax=172.0, resting_hr=62.0, vo2max_override=41.0,
              zone_lower_bounds=[88.0, 110.0, 132.0, 154.0, 166.0]),
    setting=Setting(
        suppress_peaks=False, session_gap_s=600, min_hr_coverage_frac=0.4,
        hampel_radius_s=3, hampel_sigmas=2.0, motion_still_g=0.03, motion_smooth_s=6,
        basal_min_window_s=240, basal_hr_range_bpm=6.0, basal_still_frac=0.9,
        basal_beat_coverage_frac=0.3, rest_smooth_s=20, rest_smooth_min_samples=15,
        basal_hr_seed_offset_bpm=3.0, reserve_ramp_band_bpm=7.0,
        measured_basal_kcal_day=1400.0,
        basal_fat_night=0.7, basal_fat_day=0.25,
        basal_fat_day_start_hour=8.0, basal_fat_day_end_hour=18.0,
        active_fat_at_zone1=0.9, active_fat_at_zone2_top=0.5,
    ),
)

DAY_FIGURES = ("totalKcal", "activeKcal", "quietWindowCount", "finalBasalHrBpm",
               "fatGrams", "choGrams", "hrCoverageFrac")
SERIES = ("minuteActiveKcal", "minuteDeltaHrBpm", "minuteBasalHrBpm", "minuteFatPercentage")


def main() -> None:
    for fixture in (FIXTURE_A, FIXTURE_B):
        result = fixture.score()
        for key in DAY_FIGURES:
            print(f"{fixture.name}.{key} {result[key]!r}")
        for key in SERIES:
            sampled = result[key][::fixture.sample_every]
            print(f"{fixture.name}.{key} " + ",".join(
                "null" if v is None else repr(v) for v in sampled))


if __name__ == "__main__":
    main()
