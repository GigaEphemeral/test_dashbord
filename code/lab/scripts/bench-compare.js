#!/usr/bin/env node
'use strict';

/*
 * Compare two Task 20a bench runs.
 *
 * Usage:
 *   node bench-compare.js <runA> <runB> [--out <file>]
 *
 * A run may be a run id under BENCH_REPORTS_DIR or a directory containing
 * manifest.json. K6 metrics come from manifest.keyMetrics; JVM/resource peaks
 * are queried from PROM_URL over each run's time window. Prometheus failures
 * are reported as n/a and do not fail the comparison.
 *
 * Environment:
 *   BENCH_REPORTS_DIR (default <repo>/code/lab/k6/reports)
 *   PROM_URL          (default http://127.0.0.1:8080/prom)
 *   BENCH_COMPARE_TIMEOUT_MS (default 5000)
 *   BENCH_COMPARE_WINDOW_GRACE_SECONDS (default 15, per-run peak window margin)
 *   BENCH_COMPARE_OUT  optional default output file
 */

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..', '..', '..');
const reportsDir = process.env.BENCH_REPORTS_DIR || path.join(ROOT, 'code', 'lab', 'k6', 'reports');
const promUrl = (process.env.PROM_URL || 'http://127.0.0.1:8080/prom').replace(/\/+$/, '');

// Positive-number env override with a safe fallback: an empty, non-numeric or
// non-positive value would otherwise turn setTimeout/Abort into an instant
// abort (every Prometheus query silently becomes n/a).
function numberFromEnv(value, fallback) {
  const parsed = Number(value);
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback;
}

const timeoutMs = numberFromEnv(process.env.BENCH_COMPARE_TIMEOUT_MS, 5000);
// PromQL range durations are integer seconds; a fractional grace would render
// e.g. "[8.5s]", fail to parse and silently turn every peak into n/a.
const windowGraceSeconds = Math.ceil(numberFromEnv(process.env.BENCH_COMPARE_WINDOW_GRACE_SECONDS, 15));

function usage() {
  console.error('Usage: compare.sh <runA> <runB> [--out <file>]');
  console.error('A run id resolves under BENCH_REPORTS_DIR; a directory must contain manifest.json.');
}

const args = process.argv.slice(2);
let outFile = process.env.BENCH_COMPARE_OUT || '';
const runs = [];
for (let i = 0; i < args.length; i += 1) {
  if (args[i] === '--out') {
    if (i + 1 >= args.length) {
      usage();
      process.exit(2);
    }
    outFile = args[i + 1];
    i += 1;
  } else if (args[i] === '-h' || args[i] === '--help') {
    usage();
    process.exit(0);
  } else {
    runs.push(args[i]);
  }
}
if (runs.length !== 2) {
  usage();
  process.exit(2);
}

function resolveRun(value) {
  const direct = path.resolve(value);
  if (fs.existsSync(path.join(direct, 'manifest.json'))) {
    return direct;
  }
  const underReports = path.join(reportsDir, value);
  if (fs.existsSync(path.join(underReports, 'manifest.json'))) {
    return underReports;
  }
  throw new Error(`manifest.json not found for run "${value}" (looked at ${direct} and ${underReports})`);
}

function readJson(file) {
  return JSON.parse(fs.readFileSync(file, 'utf8'));
}

function loadRun(value) {
  const dir = resolveRun(value);
  const manifest = readJson(path.join(dir, 'manifest.json'));
  if (!manifest || manifest.schemaVersion !== 1) {
    throw new Error(`${dir}/manifest.json has unsupported schemaVersion`);
  }
  return { dir, manifest };
}

function numberOrNull(value) {
  return typeof value === 'number' && Number.isFinite(value) ? value : null;
}

function metric(run, key) {
  return numberOrNull(run.manifest.keyMetrics ? run.manifest.keyMetrics[key] : null);
}

function fmtNumber(value, digits = 2) {
  const number = numberOrNull(value);
  return number === null ? 'n/a' : number.toFixed(digits);
}

function fmtPercent(value) {
  const number = numberOrNull(value);
  return number === null ? 'n/a' : `${(number * 100).toFixed(2)}%`;
}

function fmtBytes(value) {
  const number = numberOrNull(value);
  return number === null ? 'n/a' : (number / 1024 / 1024).toFixed(1);
}

function fmtCount(value) {
  const number = numberOrNull(value);
  return number === null ? 'n/a' : String(number);
}

async function promMax(expr, time) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    const url = `${promUrl}/api/v1/query?query=${encodeURIComponent(expr)}&time=${time}`;
    const response = await fetch(url, { signal: controller.signal });
    if (!response.ok) return null;
    const payload = await response.json();
    if (!payload || payload.status !== 'success' || !payload.data || !Array.isArray(payload.data.result)) {
      return null;
    }
    const values = payload.data.result
      .map((item) => Number.parseFloat(item.value && item.value[1]))
      .filter((value) => Number.isFinite(value));
    return values.length ? Math.max(...values) : null;
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timer);
  }
}

async function peaks(run) {
  const start = Number(run.manifest.startEpoch);
  const end = Number(run.manifest.endEpoch);
  if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
    return { heap: null, threads: null, gcPauses: null, hikariPending: null, error: 'manifest has no valid startEpoch/endEpoch' };
  }
  // Range queries evaluate at t=end and look backward, so the window must cover
  // the run interval [start,end] plus a small margin (>= one scrape) instead of a
  // fixed 60s floor; a fixed 60s floor pulled ~50s of pre-run (post-reset startup)
  // GC into short smoke runs and inflated the reported GC-pause count.
  const windowSeconds = Math.max(windowGraceSeconds, Math.ceil(end - start) + windowGraceSeconds);
  const at = end;
  const base = 'job="seckill-app"';
  const heap = await promMax(`max(max_over_time(jvm_memory_used_bytes{area="heap",${base}}[${windowSeconds}s]))`, at);
  const threads = await promMax(`max(max_over_time(jvm_threads_live_threads{${base}}[${windowSeconds}s]))`, at);
  const gcPauses = await promMax(`sum(increase(jvm_gc_pause_seconds_count{${base}}[${windowSeconds}s]))`, at);
  const hikariPending = await promMax(`max(max_over_time(hikaricp_connections_pending{${base}}[${windowSeconds}s]))`, at);
  const missing = [heap, threads, gcPauses, hikariPending].some((value) => value === null);
  return { heap, threads, gcPauses, hikariPending, error: missing ? 'one or more Prometheus peaks were unavailable' : '' };
}

function delta(a, b, formatter) {
  const left = numberOrNull(a);
  const right = numberOrNull(b);
  if (left === null || right === null) return 'n/a';
  return formatter ? formatter(right - left) : (right - left).toFixed(2);
}

// Design §11.4 requires 涨跌幅 (up/down percentage), computed on the raw values
// so it stays consistent with the source numbers, not the rounded display.
function pctChange(a, b) {
  const left = numberOrNull(a);
  const right = numberOrNull(b);
  if (left === null || right === null) return 'n/a';
  if (left === 0) return right === 0 ? 'n/a' : 'n/a (from 0)';
  const pct = ((right - left) / Math.abs(left)) * 100;
  return `${pct >= 0 ? '+' : ''}${pct.toFixed(1)}%`;
}

function resetBaseline(run) {
  const reset = run.manifest.reset;
  return reset ? numberOrNull(reset.baselineStock) : null;
}

// 429 rate: expected-429 rejections as a fraction of total requests. Both inputs
// come from the manifest so the n/a path is identical to the count rows.
function rejectionRate(run, countKey) {
  const count = metric(run, countKey);
  const requests = metric(run, 'requests');
  if (count === null || requests === null || requests === 0) return null;
  return count / requests;
}

function tableRow(label, a, b, formatter, deltaFormatter) {
  return `| ${label} | ${formatter ? formatter(a) : a} | ${formatter ? formatter(b) : b} | ${delta(a, b, deltaFormatter)} | ${pctChange(a, b)} |`;
}

async function main() {
  const runA = loadRun(runs[0]);
  const runB = loadRun(runs[1]);
  const [peaksA, peaksB] = await Promise.all([peaks(runA), peaks(runB)]);

  const m = (run, key) => metric(run, key);
  const rows = [
    tableRow('Scenario', runA.manifest.scenario, runB.manifest.scenario),
    tableRow('Run ID', runA.manifest.runId, runB.manifest.runId),
    tableRow('Verdict', runA.manifest.verdict, runB.manifest.verdict),
    tableRow('Git commit', String(runA.manifest.git && runA.manifest.git.commit || '').slice(0, 7), String(runB.manifest.git && runB.manifest.git.commit || '').slice(0, 7)),
    tableRow('Reset baseline stock', resetBaseline(runA), resetBaseline(runB), (v) => fmtCount(v), (v) => v.toFixed(0)),
    tableRow('Duration (s)', runA.manifest.durationSeconds, runB.manifest.durationSeconds, (v) => fmtNumber(v, 0), (v) => v.toFixed(0)),
    tableRow('RPS (avg)', m(runA, 'rps'), m(runB, 'rps'), (v) => fmtNumber(v), (v) => v.toFixed(2)),
    tableRow('Requests', m(runA, 'requests'), m(runB, 'requests'), (v) => fmtCount(v), (v) => v.toFixed(0)),
    tableRow('Iterations', m(runA, 'iterations'), m(runB, 'iterations'), (v) => fmtCount(v), (v) => v.toFixed(0)),
    tableRow('http_req_duration P95 (ms)', m(runA, 'durationP95Ms'), m(runB, 'durationP95Ms'), (v) => fmtNumber(v), (v) => v.toFixed(2)),
    tableRow('http_req_duration P99 (ms)', m(runA, 'durationP99Ms'), m(runB, 'durationP99Ms'), (v) => fmtNumber(v), (v) => v.toFixed(2)),
    tableRow('http_req_duration max (ms)', m(runA, 'durationMaxMs'), m(runB, 'durationMaxMs'), (v) => fmtNumber(v), (v) => v.toFixed(2)),
    tableRow('Failed rate', m(runA, 'failedRate'), m(runB, 'failedRate'), (v) => fmtPercent(v), (v) => `${(v * 100).toFixed(2)}pp`),
    tableRow('Flow error rate', m(runA, 'flowErrorRate'), m(runB, 'flowErrorRate'), (v) => fmtPercent(v), (v) => `${(v * 100).toFixed(2)}pp`),
    tableRow('Server error rate', m(runA, 'serverErrorRate'), m(runB, 'serverErrorRate'), (v) => fmtPercent(v), (v) => `${(v * 100).toFixed(2)}pp`),
    tableRow('Checks rate', m(runA, 'checksRate'), m(runB, 'checksRate'), (v) => fmtPercent(v), (v) => `${(v * 100).toFixed(2)}pp`),
    tableRow('Expected 409', m(runA, 'expected409'), m(runB, 'expected409'), (v) => fmtCount(v), (v) => v.toFixed(0)),
    tableRow('Expected 429', m(runA, 'expected429'), m(runB, 'expected429'), (v) => fmtCount(v), (v) => v.toFixed(0)),
    tableRow('Expected 429 rate', rejectionRate(runA, 'expected429'), rejectionRate(runB, 'expected429'), (v) => fmtPercent(v), (v) => `${(v * 100).toFixed(2)}pp`),
    tableRow('VUs max', m(runA, 'vusMax'), m(runB, 'vusMax'), (v) => fmtCount(v), (v) => v.toFixed(0)),
    tableRow('Peak heap (MiB)', peaksA.heap, peaksB.heap, (v) => fmtBytes(v), (v) => fmtNumber(v / 1024 / 1024, 1)),
    tableRow('Peak threads', peaksA.threads, peaksB.threads, (v) => fmtNumber(v, 0), (v) => v.toFixed(0)),
    tableRow('GC pauses (count)', peaksA.gcPauses, peaksB.gcPauses, (v) => fmtNumber(v, 0), (v) => v.toFixed(0)),
    tableRow('Peak Hikari pending', peaksA.hikariPending, peaksB.hikariPending, (v) => fmtNumber(v, 0), (v) => v.toFixed(0)),
  ];

  const lines = [
    '# Bench comparison',
    '',
    `- A: \`${runA.manifest.runId}\` (${runA.dir})`,
    `- B: \`${runB.manifest.runId}\` (${runB.dir})`,
    '',
    '| Metric | A | B | Δ (B-A) | Δ% (B vs A) |',
    '|---|---:|---:|---:|---:|',
    ...rows,
  ];
  if (runA.manifest.scenario !== runB.manifest.scenario) {
    lines.push('', `> 注意：两次运行的场景不同（${runA.manifest.scenario} vs ${runB.manifest.scenario}），对比用于流程/性能趋势参考，不等价于同场景回归。`);
  }
  if (peaksA.error || peaksB.error) {
    lines.push('', `> 注意：Prometheus 峰值查询不完整（${peaksA.error || peaksB.error}）。请确认 PROM_URL、运行时间窗和保留期。`);
  }
  const markdown = `${lines.join('\n').trimEnd()}\n`;
  process.stdout.write(markdown);
  if (outFile) {
    fs.mkdirSync(path.dirname(path.resolve(outFile)), { recursive: true });
    fs.writeFileSync(path.resolve(outFile), markdown, 'utf8');
    process.stderr.write(`wrote ${path.resolve(outFile)}\n`);
  }
}

main().catch((error) => {
  console.error(`compare: ${error.message}`);
  process.exit(2);
});
