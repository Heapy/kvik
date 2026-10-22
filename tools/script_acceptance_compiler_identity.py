"""Actual public A/B compiler-distribution ordering gate, with no artifact rewriting.

Runtime B must be built separately from the approved A source by swapping exactly
the two declared compiler JAR entries. This module never builds a runtime.
"""
import copy
import hashlib
import json
from pathlib import Path

FIXTURES = Path(__file__).resolve().parents[1] / 'tests/compiler-identity'
SWAPPED = ('kotlinx-coroutines-core-jvm-1.8.0.jar', 'annotations-13.0.jar')
COMPILE = 'script:plain:jvm:compile'
RUN = 'script:plain:jvm:run'


def prepare(project):
    """Reuse Gate's unchanged standalone fixture, including its exact output audit."""
    assert (project/'Main.kt').is_file() and (project/'Main.kvik.json').is_file()


def ordered_mutation(before, after):
    """Accept only a transposition of real declared entries, preserving every byte pin."""
    assert before['schemaVersion'] == after['schemaVersion'] == 1
    assert before['version'] == after['version'] == '2.4.20'
    expected = copy.deepcopy(before)
    entries = expected['artifacts']
    positions = [next(i for i, item in enumerate(entries) if item['path'] == name) for name in SWAPPED]
    assert positions == [len(entries)-2, len(entries)-1]
    assert len(entries) == 13 and len({a['path'] for a in entries}) == 13
    entries[positions[0]], entries[positions[1]] = entries[positions[1]], entries[positions[0]]
    assert after == expected and after != before, 'Only the exact consumed compiler classpath transposition is allowed'


def normalized_actions(before, after):
    """Check the actual pair, then isolate compiler identity from the composition change."""
    assert before['composition'] != after['composition']
    assert before['toolchains']['jvm-compiler'] != after['toolchains']['jvm-compiler']
    assert set(before['toolchains']) == set(after['toolchains']) == {'jdk', 'jvm-compiler', 'jvm-worker', 'junit'}
    for key in ('jdk', 'jvm-worker', 'junit'):
        assert before['toolchains'][key] == after['toolchains'][key], key
    normalized = copy.deepcopy(after)
    normalized['composition'] = before['composition']
    normalized['toolchains']['jvm-compiler'] = before['toolchains']['jvm-compiler']
    assert normalized == before, 'Unexpected source/options/inputs/worker changes would confound this gate'
    compiler_only = copy.deepcopy(before)
    compiler_only['toolchains']['jvm-compiler'] = after['toolchains']['jvm-compiler']
    return compiler_only


def _file(path):
    assert path.is_file() and not path.is_symlink(), path
    hasher = hashlib.sha256(); size = 0
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            hasher.update(block); size += len(block)
    return dict(hash=hasher.hexdigest(), sizeBytes=size)


def _bundle(runtime):
    root = runtime.parent/'jvm'
    bundle = json.loads((root/'manifest.json').read_bytes())
    distribution = json.loads((root/'compiler-manifest.json').read_bytes())
    assert _file(root/'manifest.json') == json.loads((runtime.parent/'manifest.json').read_bytes())['jvmWorkerBundle']
    assert _file(root/'compiler-manifest.json') == bundle['compilerManifest']['digest']
    assert digest(canonical(distribution)) == bundle['compilerManifest']['digest']
    for role in ('api', 'stdlib', 'junit'):
        assert _file(root/bundle[role]['path']) == bundle[role]['digest']
    paths = {item['path'] for item in bundle['classes']['files']}
    assert {p.relative_to(root/'classes').as_posix() for p in (root/'classes').rglob('*') if p.is_file()} == paths
    for entry in bundle['classes']['files']:
        path = root/'classes'/entry['path']
        assert _file(path) == entry['digest']
        assert bool(path.stat().st_mode & 0o111) == entry['executable']
    return bundle, distribution


def _runtime_pair(gate, runtime_a, runtime_b):
    assert runtime_a != runtime_b, 'Supply a separately built alternate runtime'
    identity_a, identity_b = runtime_identity(runtime_a), runtime_identity(runtime_b)
    bundle_a, distribution_a = _bundle(runtime_a)
    bundle_b, distribution_b = _bundle(runtime_b)
    ordered_mutation(distribution_a, distribution_b)
    assert bundle_a['classes'] == bundle_b['classes'], 'Worker bytecode must remain unchanged'
    for role in ('api', 'stdlib', 'junit', 'worker'):
        assert bundle_a[role] == bundle_b[role], role
    expected_bundle = copy.deepcopy(bundle_a)
    expected_bundle['compilerManifest'] = bundle_b['compilerManifest']
    assert bundle_b == expected_bundle
    lock_a = json.loads((runtime_a.parent/'runtime.lock.json').read_bytes())
    lock_b = json.loads((runtime_b.parent/'runtime.lock.json').read_bytes())
    expected_lock = copy.deepcopy(lock_a)
    # The changed artifact lock is a bootstrap recipe input. It changes the recipe
    # digest; an independent source/build receipt must establish that sole edit.
    assert lock_a['recipe'] != lock_b['recipe']
    expected_lock['recipe'] = lock_b['recipe']
    libraries = expected_lock['libraries']
    coordinates = ['org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0', 'org.jetbrains:annotations:13.0']
    positions = [next(i for i, item in enumerate(libraries) if item['coordinate'] == coordinate) for coordinate in coordinates]
    libraries[positions[0]], libraries[positions[1]] = libraries[positions[1]], libraries[positions[0]]
    assert lock_b == expected_lock, 'Only recipe identity and exact library ordering may differ'
    tools = json.loads((gate.case/'tools.json').read_bytes())
    artifacts = Path(tools['compilerArtifacts'])
    actual = []
    for item in distribution_a['artifacts']:
        value = _file(artifacts/item['path'])
        assert value == item['digest']
        actual.append(dict(path=item['path'], digest=value))
    plugin = artifacts/'kotlin-serialization-compiler-plugin-embeddable-2.4.20.jar'
    assert _file(plugin) == distribution_a['serializationPlugin'] == distribution_b['serializationPlugin']
    return dict(runtimeA=dict(path=str(runtime_a), identity=identity_a, bundle=bundle_a, distribution=distribution_a),
        runtimeB=dict(path=str(runtime_b), identity=identity_b, bundle=bundle_b, distribution=distribution_b),
        actualCompilerArtifacts=actual, swapped=list(SWAPPED),
        limits=['Compiler classpath ordering mutation; no compiler implementation byte/version change',
                'Recipe source/build provenance is a separate required receipt; recipe digest alone does not prove its inputs'])


def _compile(result):
    return next(a for a in result['record']['actions'] if a['actionId'] == COMPILE)


def _audit_execution(gate, result, arguments, compile_status, compiler):
    gate.fresh_output(result, arguments)
    assert [(a['actionId'], a['status']) for a in result['record']['actions']] == [(COMPILE, compile_status), (RUN, 'executed')]
    assert result['specs'][COMPILE]['action']['toolchains']['jvm-compiler'] == compiler
    assert result['specs'][RUN]['action']['toolchains']['jvm-compiler'] == compiler
    assert result['specs'][RUN]['action']['cachePolicy'] == 'never'
    if compile_status == 'executed':
        observation = result['observations'][COMPILE]
        request = json.loads((Path(observation['directory'])/'request.json').read_bytes())
        assert request['compiler'] == compiler


def run(gate):
    global canonical, digest, write, runtime_identity
    from test_scripts_cli import canonical, digest, write, runtime_identity, LITERALS
    assert gate.name == 'plain' and gate.phase == 'compiler-identity'
    assert gate.args.alternate_runtime is not None, '--alternate-runtime is required for this actual gate'
    original_args = gate.args
    runtime_a, runtime_b = original_args.runtime, original_args.alternate_runtime
    evidence = _runtime_pair(gate, runtime_a, runtime_b)
    write(gate.directory/'compiler-distribution-pair.json', evidence)
    gate.args = copy.copy(original_args)
    try:
        gate.resolve('identity-resolve')
        assert json.loads(gate.lock.read_bytes())['selections'] == []
        initial_lock = gate.lock.read_bytes()
        arguments = ['ok', *LITERALS]
        results = []
        for label, runtime, status in [('identity-a-cold', runtime_a, 'executed'), ('identity-a-warm', runtime_a, 'local_hit'),
                ('identity-b-cold', runtime_b, 'executed'), ('identity-b-warm', runtime_b, 'local_hit'),
                ('identity-a-reuse', runtime_a, 'local_hit')]:
            gate.args.runtime = runtime
            result = gate.run(label, arguments)
            compiler = evidence['runtimeA' if runtime == runtime_a else 'runtimeB']['bundle']['compilerManifest']['digest']
            _audit_execution(gate, result, arguments, status, compiler)
            assert gate.lock.read_bytes() == initial_lock
            results.append(result)
            gate.query(label+'-current', result)
        a, a_warm, b, b_warm, a_reuse = results
        for result in (a_warm, a_reuse):
            assert result['specs'] == a['specs']
        assert b_warm['specs'] == b['specs']
        assert all(_compile(result)['outputs'] == _compile(a)['outputs'] for result in results)
        counterfactuals = {}
        for name in (COMPILE, RUN):
            before, after = a['specs'][name]['action'], b['specs'][name]['action']
            compiler_only = normalized_actions(before, after)
            assert digest(canonical(before)) != digest(canonical(compiler_only))
            assert digest(canonical(before)) != digest(canonical(after))
            counterfactuals[name] = dict(actualA=digest(canonical(before)), actualB=digest(canonical(after)),
                compilerOnlyCanonicalKey=digest(canonical(compiler_only)), compilerOnlyAction=compiler_only,
                kind='read-only canonical key calculation, not an executed action')
        directories = [gate.observation(result)['directory'] for result in results]
        assert len(set(directories)) == 5
        effects = (gate.project/'effects.log').read_text().splitlines()
        assert len(effects) == 5 and all(line.startswith('ok\t') for line in effects)
        write(gate.directory/'compiler-identity-action-comparison.json', counterfactuals)
        assert _runtime_pair(gate, runtime_a, runtime_b) == evidence, 'Runtime/compiler bytes changed during gate'
    finally:
        gate.args = original_args
    return gate.finish(dict(compilerIdentity=dict(distributionOrderingChanged=True, compilerImplementationBytesChanged=False,
        successfulExecutions=5, alternateRuntime=evidence['runtimeB']['identity'], exactActionDifferences=['composition', 'toolchains.jvm-compiler'],
        fixedCompositionCounterfactualIsExecution=False)))
