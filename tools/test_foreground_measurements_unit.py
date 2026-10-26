import unittest

from foreground_measurements import ControlTimings, owned_processes, percentile95, process_rows, terminal_response, terminal_summary


class ForegroundMeasurementTest(unittest.TestCase):
    def test_process_tree_excludes_other_sessions_and_converts_kib(self):
        rows = process_rows('10 1 10 1024 Mon Oct 26 06:00:00 2026\n'
                            '11 10 11 2048 Mon Oct 26 06:00:01 2026\n'
                            '12 11 11 32 Mon Oct 26 06:00:01 2026\n'
                            '20 1 20 8192 Mon Oct 26 06:00:02 2026\n')
        selected = owned_processes(rows, 10, rows[10]['birth'])
        self.assertEqual([10, 11, 12], [p['pid'] for p in selected])
        self.assertEqual((1024+2048+32)*1024, sum(p['rssBytes'] for p in selected))
        self.assertEqual([], owned_processes(rows, 99, 'gone'))
        with self.assertRaisesRegex(ValueError, 'reused'):
            owned_processes(rows, 10, 'different start')

    def test_malformed_inventory_and_duplicate_pid_fail(self):
        row = '10 1 10 1024 Mon Oct 26 06:00:00 2026\n'
        for text in (row+row, row.replace('1024', '-1'), '10 1 10\n'):
            with self.subTest(text=text), self.assertRaises(ValueError):
                process_rows(text)

    def event(self, request='a', command='status', phase='preparing', active=None):
        return dict(type='control', sessionId='session', sequence=2,
                    payload=dict(requestId=request, command=command, active=active,
                                 application=dict(candidate=dict(phase=phase) if phase else None)))

    def test_acknowledgements_bind_request_command_and_reader_timestamp(self):
        metrics = ControlTimings(); metrics.sent('a', 'status', 10)
        metrics.observed(self.event(request='unmeasured'), 15)
        metrics.observed(self.event(), 30)
        report = metrics.summary()
        self.assertEqual(20, report['samples'][0]['durationNanos'])
        self.assertEqual(1, report['duringBuild']['sampleCount'])
        self.assertTrue(report['duringBuild']['budgetMet'])
        self.assertEqual([], report['unacknowledgedRequests'])
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            metrics.sent('a', 'status', 50)
        for event, when in ((self.event(command='r'), 30), (self.event(), 9)):
            metrics = ControlTimings(); metrics.sent('a', 'status', 10)
            with self.assertRaises(ValueError):metrics.observed(event, when)

    def test_busy_budget_does_not_hide_outlier_or_count_idle_controls(self):
        metrics = ControlTimings()
        for index in range(20):
            request=str(index);metrics.sent(request,'status',0)
            metrics.observed(self.event(request=request),10 if index<19 else 100_000_000)
        metrics.sent('idle','status',0);metrics.observed(self.event(request='idle',phase=None),200_000_000)
        report=metrics.summary()['duringBuild']
        self.assertEqual(20,report['sampleCount']);self.assertEqual(10,report['p95Nanos'])
        self.assertFalse(report['budgetMet']);self.assertEqual(100_000_000,report['maximumNanos'])
        empty=ControlTimings().summary()['duringBuild']
        self.assertIsNone(empty['budgetMet']);self.assertIsNone(empty['p95Nanos'])

    def test_nearest_rank_percentile_and_missing_acknowledgement(self):
        self.assertEqual(19,percentile95(list(range(1,21))))
        self.assertEqual(29,percentile95(list(range(1,31))))
        self.assertIsNone(percentile95([]))
        metrics=ControlTimings();metrics.sent('waiting','status',0)
        self.assertEqual(['waiting'],metrics.summary()['unacknowledgedRequests'])

    def test_terminal_samples_require_matching_key_receipt_and_work_phase(self):
        event=dict(type='control',sessionId='s',sequence=3,
                   payload=dict(command='?',active='build',compositionReady=True))
        sample=terminal_response('initial-build',10,40,event)
        self.assertEqual(30,sample['durationNanos'])
        self.assertEqual(3,sample['sequence'])
        for phase,sent,received,value in (
            ('initial-build',41,40,event),
            ('source-recomposition',10,40,event),
            ('replacement-test',10,40,event),
            ('unknown',10,40,event),
            ('initial-build',10,40,{**event,'payload':{**event['payload'],'command':'r'}}),
            ('initial-build',10,40,{**event,'type':'operation-started'}),
        ):
            with self.subTest(phase=phase,value=value),self.assertRaises(ValueError):
                terminal_response(phase,sent,received,value)

    def test_terminal_budget_requires_all_phases_and_preserves_outliers(self):
        samples=[]
        for phase in ('initial-build','source-recomposition','replacement-test','before-ctrl-c'):
            count=1 if phase=='before-ctrl-c' else 20
            samples.extend(dict(phase=phase,durationNanos=10) for _ in range(count))
        self.assertTrue(terminal_summary(samples)['budgetsMet'])
        self.assertIsNone(terminal_summary(samples)['phases']['before-ctrl-c']['p95Nanos'])
        samples[19]['durationNanos']=100_000_000
        report=terminal_summary(samples)
        self.assertFalse(report['budgetsMet'])
        self.assertEqual(10,report['phases']['initial-build']['p95Nanos'])
        self.assertFalse(report['phases']['initial-build']['budgetMet'])
        self.assertIsNone(terminal_summary(samples[1:])['phases']['initial-build']['budgetMet'])

    def test_terminal_recomposition_requires_an_open_same_session_rebuild(self):
        event=dict(type='control',sessionId='s',sequence=5,
                   payload=dict(command='?',active=None,compositionReady=False))
        started=dict(type='rebuild-started',sessionId='s',sequence=2)
        ended=dict(type='rebuild-finished',sessionId='s',sequence=4)
        sample=terminal_response('source-recomposition',10,40,event,[started])
        self.assertEqual(2,sample['rebuildStartSequence'])
        for events in ([],[started,ended],[{**started,'sessionId':'other'}],
                       [{**started,'sequence':6}]):
            with self.subTest(events=events),self.assertRaises(ValueError):
                terminal_response('source-recomposition',10,40,event,events)


if __name__ == '__main__':unittest.main()
