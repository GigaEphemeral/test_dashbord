// Zero-dependency tests for bench-report.js (Task 20a review fixes).
// Run: node --test code/lab/scripts/bench-report.test.js
'use strict';

const { test } = require('node:test');
const assert = require('node:assert');
const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const REPORT = path.join(__dirname, 'bench-report.js');

function runReport(dir, extraArgs) {
  const base = [
    '--summary', path.join(dir, 'summary.json'),
    '--log', path.join(dir, 'k6.log'),
    '--html', path.join(dir, 'report.html'),
    '--manifest', path.join(dir, 'manifest.json'),
    '--result-doc', path.join(dir, 'result.md'),
    '--scenario', 'smoke',
    '--report-dir', dir,
  ];
  const proc = spawnSync(process.execPath, [REPORT, ...base, ...extraArgs], {
    encoding: 'utf8',
  });
  return proc;
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

test('missing summary is FAIL but manifest/report/result doc are still written', (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-report-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.writeFileSync(path.join(dir, 'k6.log'), 'no thresholds here\n', 'utf8');
  const proc = runReport(dir, ['--run-id', 'no-summary', '--k6-exit-code', '99']);
  assert.strictEqual(proc.status, 0, proc.stderr);
  assert.match(proc.stdout, /BENCH_VERDICT=FAIL/);
  const manifest = readJson(path.join(dir, 'manifest.json'));
  assert.strictEqual(manifest.verdict, 'FAIL');
  assert.strictEqual(manifest.k6.exitCode, 99);
  assert.match(manifest.summaryError, /not produced/);
  assert.ok(fs.existsSync(path.join(dir, 'report.html')));
  assert.ok(fs.existsSync(path.join(dir, 'result.md')));
});

test('k6 exit 0 with all-checked thresholds in the log is PASS', (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-report-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.writeFileSync(
    path.join(dir, 'summary.json'),
    JSON.stringify({
      metrics: {
        iterations: { count: 1, rate: 0.2 },
        http_reqs: { count: 7, rate: 1.9 },
        checks: { rate: 1, passes: 5, fails: 0, threshold: { 'rate>0.99': false } },
      },
    }),
    'utf8',
  );
  fs.writeFileSync(
    path.join(dir, 'k6.log'),
    [
      '   THRESHOLDS',
      '',
      '     checks',
      `     ✓ 'rate>0.99' rate=100.00%`,
      '',
      'running (6.1s), 0/1 VUs',
    ].join('\n'),
    'utf8',
  );
  const proc = runReport(dir, ['--run-id', 'ok-run', '--k6-exit-code', '0']);
  assert.strictEqual(proc.status, 0, proc.stderr);
  assert.match(proc.stdout, /BENCH_VERDICT=PASS/);
  const manifest = readJson(path.join(dir, 'manifest.json'));
  assert.strictEqual(manifest.verdict, 'PASS');
  assert.strictEqual(manifest.thresholds.results.length, 1);
  assert.strictEqual(manifest.thresholds.results[0].ok, true);
  assert.strictEqual(manifest.thresholds.results[0].rule, 'rate>0.99');
});

test('log ✗ entries override a misleading summary boolean', (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-report-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  // Summary claims the threshold is ok (false), the log says the rule failed.
  fs.writeFileSync(
    path.join(dir, 'summary.json'),
    JSON.stringify({
      metrics: { flow_errors: { rate: 0.5, thresholds: { 'rate<0': false } } },
    }),
    'utf8',
  );
  fs.writeFileSync(
    path.join(dir, 'k6.log'),
    ['   THRESHOLDS', '', '     flow_errors', `     ✗ 'rate<0' rate=50.00%`, ''].join('\n'),
    'utf8',
  );
  const proc = runReport(dir, ['--run-id', 'log-wins', '--k6-exit-code', '99']);
  assert.strictEqual(proc.status, 0, proc.stderr);
  assert.match(proc.stdout, /BENCH_VERDICT=FAIL/);
  const manifest = readJson(path.join(dir, 'manifest.json'));
  const entry = manifest.thresholds.results.find((e) => e.rule === 'rate<0');
  assert.strictEqual(entry.ok, false);
  assert.match(entry.detail, /rate=50\.00%/);
});

test('run id and env overrides are HTML-escaped in report.html', (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-report-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  fs.writeFileSync(path.join(dir, 'k6.log'), '', 'utf8');
  const proc = runReport(dir, [
    '--run-id', 'x"><script>alert(1)</script>',
    '--k6-exit-code', '0',
    '--env', 'EVIL=<img src=x onerror=alert(1)>',
  ]);
  assert.strictEqual(proc.status, 0, proc.stderr);
  const html = fs.readFileSync(path.join(dir, 'report.html'), 'utf8');
  assert.ok(!html.includes('<script>alert(1)</script>'), 'raw script tag leaked');
  assert.ok(!html.includes('<img src=x'), 'raw img tag leaked');
  assert.match(html, /&lt;script&gt;/);
  assert.match(html, /&lt;img src=x onerror=alert\(1\)&gt;/);
});

test('invalid invocation exits non-zero', (t) => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'bench-report-'));
  t.after(() => fs.rmSync(dir, { recursive: true, force: true }));
  const proc = spawnSync(process.execPath, [REPORT, '--scenario', 'smoke'], {
    encoding: 'utf8',
    cwd: dir,
  });
  assert.strictEqual(proc.status, 1);
  assert.match(proc.stderr, /bench-report:/);
});
