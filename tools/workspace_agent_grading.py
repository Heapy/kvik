"""Independent behavioral oracles and retained-run grading for workspace trials."""
import re

from test_kode_workflow_cli import test_source


_CHECK = 'library:jvm:test'
_IDENTITIES = ('inputSnapshot', 'configuration', 'dependencyLock', 'composition', 'toolchains')


def _oracle_methods(scenario, role):
    if scenario not in ('disjoint', 'conflict', 'budget') or role not in ('a', 'b', 'integrated'):
        raise ValueError('Unknown oracle scenario or role')
    batch, workers = (20, 2)
    if scenario == 'budget':
        batch, workers = {'a': (40, 2), 'b': (20, 4), 'integrated': (25, 4)}[role]
    methods = {'resourceBudget': f'''        assertEquals({batch}, maxBatch())
        assertEquals({workers}, workerCount())
        assertTrue(maxBatch() > 0 && workerCount() > 0)
        assertTrue(maxBatch().toLong() * workerCount().toLong() <= 100L)'''}
    if scenario == 'disjoint' and role in ('a', 'integrated'):
        methods['trimPreservesContent'] = r'''        assertEquals("", normalizeName(""))
        assertEquals("", normalizeName(" \t\n\u2003\u00a0"))
        assertEquals("Ada", normalizeName("Ada"))
        assertEquals("AdA  LoVe\tLaCe", normalizeName(" \tAdA  LoVe\tLaCe\n "))
        assertEquals("Z", normalizeName("\u2003Z\u00a0"))
        assertEquals("A\u200bB", normalizeName(" A\u200bB "))'''
    if scenario == 'disjoint' and role in ('b', 'integrated'):
        methods['countBoundaries'] = '''        assertEquals(0, boundedCount(Int.MIN_VALUE))
        assertEquals(0, boundedCount(-17))
        assertEquals(0, boundedCount(-1))
        assertEquals(0, boundedCount(0))
        assertEquals(1, boundedCount(1))
        assertEquals(123456789, boundedCount(123456789))
        assertEquals(Int.MAX_VALUE, boundedCount(Int.MAX_VALUE))'''
    if scenario == 'conflict':
        lower = role in ('a', 'integrated')
        collapse = role in ('b', 'integrated')
        simple = 'ada' if lower else 'AdA'
        nonspace = 'a\\u200bb' if lower else 'A\\u200bB'
        methods['nameCornerCases'] = rf'''        assertEquals("", normalizeName(""))
        assertEquals("", normalizeName(" \t\n\u2003\u00a0"))
        assertEquals("{simple}", normalizeName("AdA"))
        assertEquals("{simple}", normalizeName("\u2003AdA\u00a0"))
        assertEquals("{nonspace}", normalizeName(" A\u200bB "))'''
        if collapse:
            expected = 'ada lovelace' if lower else 'AdA LoVeLaCe'
            methods['allKotlinWhitespace'] = rf'''        assertEquals("{expected}", normalizeName(" \tAdA\u2003\u00a0\nLoVeLaCe \r"))
        // Classification comes from the task contract; the expected result is literal.
        // No reference trimming, splitting or normalization implementation is used.
        for (code in Char.MIN_VALUE.code..Char.MAX_VALUE.code) {{
            val whitespace = code.toChar()
            if (whitespace.isWhitespace()) {{
                assertEquals("{expected}", normalizeName("${{whitespace}}AdA${{whitespace}}${{whitespace}}LoVeLaCe${{whitespace}}"), "U+" + code.toString(16))
                assertEquals("", normalizeName("${{whitespace}}${{whitespace}}"))
            }}
        }}'''
        else:
            methods['lowercasePreservesInterior'] = r'''        assertEquals("ada  lovelace", normalizeName(" \tAdA  LoVeLaCe\n"))
        assertEquals("ada\t\nlovelace", normalizeName("AdA\t\nLoVeLaCe"))
        assertEquals("ada\u2003\u00a0lovelace", normalizeName(" AdA\u2003\u00a0LoVeLaCe "))'''
    return methods


def oracle_source(scenario, role):
    """Return the private IntegrationTests replacement for a candidate or integration."""
    methods = _oracle_methods(scenario, role)
    return test_source('IntegrationTests', '\n'.join(
        f'    @Test fun {name}() {{\n{body}\n    }}' for name, body in methods.items()))


def oracle_test_ids(scenario, role):
    """Exact Jupiter leaf IDs contributed by the injected class, excluding agent tests."""
    return {f'[engine:junit-jupiter]/[class:fixture.IntegrationTests]/[method:{name}()]'
            for name in _oracle_methods(scenario, role)}


def _require(condition, message):
    if not condition:
        raise ValueError(message)


def _digest(value):
    return (isinstance(value, dict) and set(value) == {'hash', 'sizeBytes'} and
            isinstance(value['hash'], str) and re.fullmatch('[0-9a-f]{64}', value['hash']) is not None and
            type(value['sizeBytes']) is int and value['sizeBytes'] >= 0)


def validate_run(record, required_ids=None):
    """Return a complete fresh passing record, or raise ValueError without mutating it."""
    _require(isinstance(record, dict), 'Verification record must be an object')
    _require(record.get('status') == 'passed' and record.get('freshnessAtCompletion') == 'current',
             'Verification failed or stale')
    _require(record.get('requestedChecks') == [_CHECK] and record.get('completedChecks') == [_CHECK],
             'Unexpected or incomplete verification scope')
    tests, counts = record.get('tests'), record.get('testCounts')
    _require(isinstance(tests, list) and bool(tests) and isinstance(counts, dict), 'Missing test results')
    _require(all(isinstance(test, dict) and isinstance(test.get('id'), str) and bool(test['id']) and
                 test.get('status') == 'passed' and test.get('module') == 'library' and test.get('target') == 'jvm'
                 for test in tests), 'Nonpassing or wrong-scope test leaf')
    ids = {test['id'] for test in tests}
    _require(len(ids) == len(tests), 'Duplicate test leaf IDs')
    _require(counts.get('discoveryComplete') is True, 'Incomplete test discovery')
    for name, expected in [('discovered', len(tests)), ('executed', len(tests)), ('failed', 0), ('skipped', 0)]:
        _require(type(counts.get(name)) is int and counts[name] == expected, 'Inconsistent test count: '+name)
    if required_ids is not None:
        _require(set(required_ids) <= ids, 'Missing expected oracle test')
    actions = record.get('actions')
    _require(isinstance(actions, list) and bool(actions), 'Missing actions')
    _require(all(isinstance(action, dict) and isinstance(action.get('actionId'), str) and
                 bool(action['actionId']) and _digest(action.get('key')) and
                 action.get('status') in ('executed', 'local_hit', 'remote_hit') for action in actions),
             'Invalid or unsuccessful action')
    action_ids = [action['actionId'] for action in actions]
    _require(len(set(action_ids)) == len(action_ids), 'Duplicate action IDs')
    _require(any(action['actionId'] == _CHECK and action['status'] == 'executed' for action in actions),
             'Test execution was reused or missing')
    for name in _IDENTITIES[:-1]:
        _require(_digest(record.get(name)), 'Missing or invalid identity: '+name)
    tools = record.get('toolchains')
    _require(isinstance(tools, dict) and bool(tools) and all(_digest(value) for value in tools.values()),
             'Missing or invalid toolchain identities')
    return record


def _matching_identity(record):
    return ({name: record[name] for name in _IDENTITIES},
            {action['actionId']: action['key'] for action in record['actions']},
            {test['id'] for test in record['tests']})


def _kode_revision_matches(record, revision):
    source = record.get('source')
    if not isinstance(source, dict) or source.get('root') != '':
        return False
    provenance = source.get('provenance')
    return (isinstance(provenance, dict) and provenance.get('project') == 'workflow' and
            provenance.get('revision') == revision)


def matching_agent_run(records, independent_record, backend, revision):
    """Find an independently reproduced agent run; ignore compilation cache status differences."""
    _require(backend in ('git', 'kode'), 'Unknown workspace backend')
    validate_run(independent_record)
    if backend == 'kode' and not _kode_revision_matches(independent_record, revision):
        return None
    expected = _matching_identity(independent_record)
    for record in records:
        try:
            validate_run(record)
        except ValueError:
            continue
        if backend == 'kode' and not _kode_revision_matches(record, revision):
            continue
        if _matching_identity(record) == expected:
            return record
    return None
