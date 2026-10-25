#!/usr/bin/env python3
"""Check slot occupancy arithmetic and refuse incomplete measurement inputs."""
import unittest

from test_test_resources_cli import measure_test_actions


class ParallelMeasurementTests(unittest.TestCase):
    def measure(self, intervals, slots):
        record = dict(testCounts=dict(executed=len(intervals)), actions=[dict(
            actionId=f'{i}:jvm:test', status='executed', processUsage=[dict(
                peakRssBytes=(i + 1) * 10, userCpuNanos=2, systemCpuNanos=1)]) for i in range(len(intervals))])
        events = [dict(type='action-timing', payload=dict(actionId=f'{i}:jvm:test',
            startedAfterNanos=start, durationNanos=end-start)) for i, (start, end) in enumerate(intervals)]
        return record, events, measure_test_actions(record, events, slots, 100)

    def test_overlap_occupancy_and_individual_peak_memory(self):
        _, _, result = self.measure([(0, 10), (5, 15)], 2)
        self.assertEqual(result['peakActiveTestActions'], 2)
        self.assertEqual(result['testActionWindowNanos'], 15)
        self.assertAlmostEqual(result['testActionSlotUtilization'], 2 / 3)
        self.assertEqual(result['largestTestChildPeakRssBytes'], 20)
        self.assertEqual(result['testProcessUserCpuNanos'], 4)
        self.assertEqual(result['testProcessSystemCpuNanos'], 2)

    def test_serial_gaps_and_touching_intervals(self):
        _, _, gap = self.measure([(0, 10), (20, 30)], 1)
        self.assertEqual(gap['peakActiveTestActions'], 1)
        self.assertAlmostEqual(gap['testActionSlotUtilization'], 2 / 3)
        _, _, touching = self.measure([(0, 10), (10, 20)], 1)
        self.assertEqual(touching['peakActiveTestActions'], 1)
        self.assertEqual(touching['testActionSlotUtilization'], 1)

    def test_missing_timing_usage_cached_execution_and_overcommit_are_rejected(self):
        record, events, _ = self.measure([(0, 10), (5, 15)], 2)
        for invalid_events, slots in [(events[:1], 2), (events + events[:1], 2), (events, 1)]:
            with self.subTest(events=invalid_events, slots=slots), self.assertRaises(AssertionError):
                measure_test_actions(record, invalid_events, slots, 100)
        record['actions'][0]['status'] = 'local_hit'
        with self.assertRaises(AssertionError):
            measure_test_actions(record, events, 2, 100)
        record['actions'][0]['status'] = 'executed'
        del record['actions'][0]['processUsage']
        with self.assertRaises(AssertionError):
            measure_test_actions(record, events, 2, 100)


if __name__ == '__main__':
    unittest.main()
