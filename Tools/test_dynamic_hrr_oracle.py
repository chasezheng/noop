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


class ClipBlockPeaksBlockEndTest(unittest.TestCase):
    """Where a block ends, which the whole-day fixtures cannot reach: both set suppress_peaks off."""

    def block_end_values(self, edge):
        """240 seconds: `edge` fills 0..119, then a 79 plateau, a 300 at second 135, and a 150 one.

        Second 135 says where the first block ended. A block that stops on the grid at 119 leaves it
        among mostly 150, whose ninetieth percentile keeps it at 150; a block that runs to 139 ranks
        it against a hundred seconds of 60, whose ninetieth percentile is 79.
        """
        out = []
        for i in range(240):
            if i < 120:
                out.append(edge(i))
            elif i == 135:
                out.append(300.0)
            elif i < 140:
                out.append(79.0)
            else:
                out.append(150.0)
        return out

    def clipped(self, values, sessions=((0, 239),)):
        return o.clip_block_peaks(values, list(sessions), 0, 120, 0.90)

    def test_ends_a_block_on_the_grid_when_the_seconds_before_it_vary_around_one_level(self):
        out = self.clipped(self.block_end_values(lambda i: 60.0 + i % 3))

        self.assertEqual(150.0, out[135])

    def test_moves_a_block_end_past_seconds_that_are_still_climbing(self):
        # Seconds 100..119 climb one bpm a second, which carries the end to 139, where the twenty
        # seconds behind it are the flat plateau and it stops.
        out = self.clipped(self.block_end_values(
            lambda i: 60.0 if i < 100 else 60.0 + (i - 100)))

        self.assertEqual(79.0, out[135])

    def test_leaves_a_block_end_alone_when_the_seconds_before_it_have_a_hole(self):
        values = self.block_end_values(lambda i: 60.0 if i < 100 else 60.0 + (i - 100))
        values[110] = NOTHING

        self.assertEqual(150.0, self.clipped(values)[135])

    def test_leaves_the_end_of_a_block_shorter_than_ninety_seconds_alone(self):
        # The same climb, but the strap went on at second 60, so the first block holds sixty.
        values = self.block_end_values(lambda i: 60.0 if i < 100 else 60.0 + (i - 100))

        self.assertEqual(150.0, self.clipped(values, sessions=((60, 239),))[135])

    def test_extends_a_block_no_further_than_ten_minutes(self):
        # A climb that never settles, stopped only by the ten-minute limit: the first block is
        # 0..599, and the ninetieth percentile of its readings is the one at second 539.
        values = [60.0 + i for i in range(900)]

        out = o.clip_block_peaks(values, [(0, 899)], 0, 300, 0.90)

        self.assertEqual(599.0, out[599])
        self.assertEqual(929.0, out[899])

    def test_moves_a_block_end_no_further_than_the_session(self):
        values = [60.0 + i for i in range(131)]

        self.assertEqual(177.0, o.clip_block_peaks(values, [(0, 130)], 0, 120, 0.90)[130])


if __name__ == "__main__":
    unittest.main()
