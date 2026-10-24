#!/usr/bin/env python3
"""Check the embedded worker's real JSON encoder with escaped and multibyte diagnostics."""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', type=Path, required=True)
    parser.add_argument('--compiler-classpath', required=True)
    parser.add_argument('--stdlib', type=Path, required=True)
    args = parser.parse_args()
    embedded = (ROOT / 'core/src/kvik/core/SqlDelightWorkerSources.kt').read_text()
    functions = embedded[embedded.index('internal fun workerResult('):embedded.index('private data class SourceFolder(')]
    probe = r'''
fun main() {
    val short = diagnostic("KVIK_SQLDELIGHT_COMPILATION", "compilation", "missing column", "sql/Item.sq", 3, 8)
    println(workerResult("small", "compilation-error", 1, sequenceOf(short)))
    println(workerResult("count", "compilation-error", 1, generateSequence { short }.take(1001)))
    val escaped = diagnostic("KVIK_SQLDELIGHT_COMPILATION", "compilation", "é界\u0001\\\"".repeat(1500), "sql/é界\\\".sq", 3, 8)
    println(workerResult("large-é界\\\"", "compilation-error", 1, generateSequence { escaped }.take(1000)))
    println(workerResult("empty", "success", 0, emptySequence()))
}
'''
    with tempfile.TemporaryDirectory(prefix='kvik-sqldelight-json-') as directory:
        directory = Path(directory)
        source = directory / 'Probe.kt'
        source.write_text(functions + probe)
        classes = directory / 'classes'
        subprocess.run([str(args.java), '-Xmx512m', '-cp', args.compiler_classpath,
                        'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect',
                        '-classpath', str(args.stdlib), '-d', str(classes), str(source)], check=True)
        output = subprocess.run([str(args.java), '-cp', str(classes) + ':' + str(args.stdlib), 'ProbeKt'],
                                check=True, capture_output=True).stdout.splitlines()
    assert len(output) == 4
    small, count, large, empty = map(json.loads, output)
    assert len(small['diagnostics']) == 1 and small['diagnostics'][0]['path'] == 'sql/Item.sq'
    assert len(count['diagnostics']) == 1001
    for encoded, result in ((output[1], count), (output[2], large)):
        assert len(encoded) <= 2 * 1024 * 1024
        assert result['status'] == 'compilation-error' and result['exitCode'] == 1
        assert result['diagnostics'][-1]['code'] == 'KVIK_SQLDELIGHT_DIAGNOSTICS_TRUNCATED'
        assert result['diagnostics'][-1]['category'] == 'compilation'
    assert 1 < len(large['diagnostics']) < 1000
    assert len(output[2]) > 1024 * 1024
    assert empty['status'] == 'success' and empty['diagnostics'] == []
    print('PASS bounded SQLDelight worker result')


if __name__ == '__main__':
    main()
