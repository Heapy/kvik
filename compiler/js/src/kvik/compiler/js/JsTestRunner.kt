package kvik.compiler.js

/** Uses kotlin.test's Jasmine-compatible bridge; tests are discovered by the compiler, not source parsing. */
internal val jsTestRunnerSource = """
import { closeSync, openSync, readFileSync, writeFileSync, writeSync } from 'node:fs';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';
const request = JSON.parse(readFileSync(process.argv[2], 'utf8'));
const tests = [], suites = [], records = [];
let discoveryComplete = false;
let closed = false;
let ignoredDepth = 0;
let diagnostic = null;
// Append complete observations synchronously so process.exit, cancellation or a timeout
// cannot erase tests which already finished. The final report alone establishes success.
const progress = openSync('test-progress.jsonl', 'wx', 0o600);
let progressBytes = 0, sequence = 0;
function retain(event) {
    const bytes = Buffer.from(JSON.stringify(event) + '\n');
    if (progressBytes + bytes.length > 64 * 1024 * 1024) throw new Error('Test progress exceeds 64 MiB');
    let offset = 0;
    while (offset < bytes.length) {
        const written = writeSync(progress, bytes, offset, bytes.length - offset);
        if (written <= 0) throw new Error('Cannot retain test progress');
        offset += written;
    }
    progressBytes += bytes.length;
}
function bounded(value, limit = 8192) {
    const text = String(value).slice(0, limit);
    // Kotlin's limits count UTF-16 units. Do not serialize half of a supplementary character.
    const last = text.charCodeAt(text.length - 1);
    return last >= 0xD800 && last <= 0xDBFF ? text.slice(0, -1) : text;
}
function suite(name, body, ignored) {
    if (closed || typeof name !== 'string' || typeof body !== 'function') throw new Error('Invalid or late test suite registration');
    suites.push(name); if (ignored) ignoredDepth++;
    try { body(); } finally { suites.pop(); if (ignored) ignoredDepth--; }
}
function test(name, body, ignored) {
    if (closed || typeof name !== 'string' || typeof body !== 'function') throw new Error('Invalid or late test registration');
    const id = [...suites, name].filter(v => v.length > 0).join('.');
    if (!id || id.length > 8192 || /[\u0000-\u001f\u007f]/u.test(id) || tests.some(t => t.id === id)) throw new Error('Invalid or duplicate test identity: ' + bounded(id));
    if (tests.length >= 100000) throw new Error('Test discovery exceeds 100000 tests');
    tests.push({id, name, suite: suites.join('.'), ignored: ignored || ignoredDepth > 0, body});
}
globalThis.describe = (name, body) => suite(name, body, false);
globalThis.xdescribe = (name, body) => suite(name, body, true);
globalThis.it = (name, body) => test(name, body, false);
globalThis.xit = (name, body) => test(name, body, true);
globalThis.describe.skip = globalThis.xdescribe;
globalThis.it.skip = globalThis.xit;
globalThis.kotlinTest = {adapterTransformer: null};
const matcher = request.selection.filter === null ? null : new RegExp('^' + request.selection.filter.split('*').map(s => s.replace(/[.*+?^{}()|[\]\\]/g, '\\' + '\u0024&')).join('.*') + '\u0024', 'u');
let selected = [];
try {
    retain({schemaVersion: 1, kind: 'js-test-progress', requestId: request.requestId, selection: request.selection});
    const module = await import(pathToFileURL(resolve('input', request.selection.entryPoint)).href);
    if (request.selection.target === 'wasmJs' && typeof module.startUnitTests === 'function') module.startUnitTests();
    closed = true;
    selected = tests.filter(t => matcher === null || matcher.test(t.id));
    discoveryComplete = true;
    retain({sequence: sequence++, kind: 'discovered', count: selected.length});
    for (const t of selected) {
        let status = 'skipped', durationNanos = null, failure = null;
        if (!t.ignored) {
            const start = process.hrtime.bigint();
            try { await t.body(); status = 'passed'; }
            catch (e) {
                status = 'failed';
                failure = {kind: 'exception', type: bounded(e?.constructor?.name ?? typeof e), message: bounded(e?.message ?? e), stackTrace: e?.stack == null ? null : bounded(e.stack, 32768)};
            }
            durationNanos = Number(process.hrtime.bigint() - start);
            if (!Number.isSafeInteger(durationNanos)) throw new Error('Test duration exceeds exact integer range');
        }
        records.push({id: t.id, module: request.selection.module, target: request.selection.target, status,
            message: failure?.message ?? null,
            details: {suite: bounded(t.suite), name: bounded(t.name), attempts: [{number: 1, status, durationNanos,
                skipReason: t.ignored ? 'Ignored by kotlin.test' : null, failure, logs: []}]}});
        retain({sequence: sequence++, kind: 'test-result', test: records[records.length - 1]});
    }
} catch (e) {
    diagnostic = bounded(e?.stack ?? e, 32768);
} finally {
    closeSync(progress);
}
const failed = records.filter(t => t.status === 'failed').length;
const skipped = records.filter(t => t.status === 'skipped').length;
const executed = records.length - skipped;
const complete = discoveryComplete && records.length === selected.length;
const status = failed > 0 ? 'failed' : diagnostic !== null || !complete || executed === 0 ? 'incomplete' : 'passed';
const exitCode = status === 'passed' ? 0 : status === 'failed' ? 1 : 3;
if (diagnostic === null && executed === 0) diagnostic = 'No selected tests executed';
const report = {schemaVersion: 1, requestId: request.requestId, selection: request.selection, status, exitCode, tests: records,
    counts: {discovered: discoveryComplete ? selected.length : null, executed, failed, skipped, discoveryComplete: complete}, diagnostic};
writeFileSync('output/test-results.json', JSON.stringify(report) + '\n', {flag: 'wx', mode: 0o600});
process.exitCode = exitCode;
""".trimIndent()
