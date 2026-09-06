"""Tests for the retroactive basal-raise ramp in dynamic_hrr_oracle.py.

The oracle is a second implementation of the dynamic HRR model, written so that the Kotlin one has
something independent to disagree with. Its whole-day output is pinned in DynamicHrrOracleTest.kt,
but both of that test's fixtures start their heart rate at index 0, so the bound that keeps a ramp
out of the hours before the strap was worn is not reached there. These vectors reach it.

Run: python3 -m unittest Tools.test_dynamic_hrr_oracle -v   (from the repo root)
     or: cd Tools && python3 -m unittest test_dynamic_hrr_oracle -v
"""

import math
import unittest

import dynamic_hrr_oracle as o

NOTHING = float("nan")


def smoothed(basal, windows, hr):
    return o.smooth_basal_raises(basal, windows, hr)


class SmoothBasalRaisesTest(unittest.TestCase):

    def test_ramps_in_proportion_to_how_far_the_rate_ran_above_the_old_one(self):
        # 10 and 30 above the old rate, so the first second takes a quarter of the climb.
        out = smoothed(
            [50.0, 50.0, 50.0, 60.0],
            [50.0, NOTHING, NOTHING, 60.0],
            [50.0, 60.0, 80.0, 60.0],
        )

        self.assertAlmostEqual(52.5, out[1], places=12)
        self.assertAlmostEqual(60.0, out[2], places=12)

    def test_ramps_no_further_back_than_the_first_reading(self):
        # Nothing was read before second 3, so seconds 1 and 2 keep the rate they already held.
        out = smoothed(
            [50.0, 50.0, 50.0, 50.0, 50.0, 60.0],
            [50.0, NOTHING, NOTHING, NOTHING, NOTHING, 60.0],
            [NOTHING, NOTHING, NOTHING, 70.0, 80.0, 60.0],
        )

        self.assertEqual(50.0, out[1])
        self.assertEqual(50.0, out[2])
        self.assertAlmostEqual(54.0, out[3], places=12)
        self.assertAlmostEqual(60.0, out[4], places=12)

    def test_leaves_the_series_alone_where_nothing_was_read(self):
        basal = [50.0, 50.0, 60.0]

        out = smoothed(basal, [50.0, NOTHING, 60.0], [NOTHING, NOTHING, NOTHING])

        self.assertEqual(basal, out)
        self.assertFalse(any(math.isnan(v) for v in out))

    def test_fills_a_gap_in_the_readings_before_weighing_it(self):
        # Seconds 1 and 2 carried nothing. Filled from 50 to 90 they read 63.33 and 76.67.
        out = smoothed(
            [50.0, 50.0, 50.0, 50.0, 60.0],
            [50.0, NOTHING, NOTHING, NOTHING, 60.0],
            [50.0, NOTHING, NOTHING, 90.0, 60.0],
        )

        self.assertAlmostEqual(50.0 + 10.0 / 6.0, out[1], places=12)
        self.assertAlmostEqual(55.0, out[2], places=12)
        self.assertAlmostEqual(60.0, out[3], places=12)

    def test_keeps_the_step_where_nothing_ran_above_the_old_rate(self):
        out = smoothed(
            [50.0, 50.0, 50.0, 60.0],
            [50.0, NOTHING, NOTHING, 60.0],
            [50.0, 48.0, 50.0, 60.0],
        )

        self.assertEqual(50.0, out[1])
        self.assertEqual(50.0, out[2])

    def test_leaves_a_ratchet_down_alone(self):
        basal = [60.0, 55.0, 50.0]

        out = smoothed(basal, [60.0, NOTHING, 50.0], [60.0, 55.0, 50.0])

        self.assertEqual(basal, out)


if __name__ == "__main__":
    unittest.main()
