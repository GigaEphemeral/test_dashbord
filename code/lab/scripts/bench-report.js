#!/usr/bin/env node
/**
 * Bench archive generator (Task 20a). No dependencies.
 *
 * Reads the k6 --summary-export JSON plus run metadata and writes:
 *   - manifest.json  machine-readable run record
 *   - report.html    self-contained HTML report
 *   - <result-doc>   Markdown result page under doc/process/benchmark
 *
 * Threshold verdicts come from the summary's per-metric `thresholds` results.
 * The runner's k6 exit code is authoritative: a crossed threshold exits 99 in
 * k6 1.3 even though the summary is still exported. A missing summary (k6
 * aborted during init) is a FAIL but the archive is still produced.
 *
 * Usage: node bench-report.js --summary <json> --manifest <json> --html <html>
 *   --result-doc <md> --scenario <name> --run-id <id> [...]
 */
'use strict';

const fs = require('fs');
const path = require('path');

function fail(message) {
  process.stderr.write(`bench-report: ${message}\n`);
  process.exit(1);
}

function parseArgs(argv) {
  const values = { env: [] };
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (!arg.startsWith('--')) {
      fail(`unexpected argument "${arg}"`);
    }
    const key = arg.slice(2);
    const value = argv[index + 1];
    if (value === undefined) {
      fail(`missing value for --${key}`);
    }
    index += 1;
    if (key === 'env') {
      values.env.push(value);
    } else {
      values[key] = value;
    }
  }
  return values;
}

const args = parseArgs(process.argv.slice(2));

const scenario = args.scenario;
const runId = args['run-id'];
const summaryPath = args.summary;
const manifestPath = args.manifest;
const htmlPath = args.html;
const resultDocPath = args['result-doc'];
const reportDir = args['report-dir'];
if (!scenario || !runId || !manifestPath || !htmlPath || !summaryPath) {
  fail('scenario, run-id, summary, manifest and html are required');
}

const k6Exit = Number.parseInt(args['k6-exit-code'] || '1', 10);
const startedAt = args['started-at'] || new Date().toISOString();
const finishedAt = args['finished-at'] || new Date().toISOString();
const startEpoch = Number.parseInt(args['start-epoch'] || '0', 10);
const endEpoch = Number.parseInt(args['end-epoch'] || '0', 10);
const thresholdsExpected = args['thresholds-expected'] !== 'false';
const resetExecuted = args['reset-executed'] === 'true';
const resetBaseline = Number.parseInt(args['reset-baseline'] || '0', 10);
const grafanaUrl = (args['grafana-url'] || '').replace(/\/+$/, '');
const grafanaDashboard = args['grafana-dashboard'] || '';
const promUrl = (args['prom-url'] || '').replace(/\/+$/, '');
const remoteWrite = args['remote-write'] || '';

const envOverrides = {};
for (const entry of args.env) {
  const split = entry.indexOf('=');
  if (split > 0) {
    envOverrides[entry.slice(0, split)] = entry.slice(split + 1);
  }
}

let summary = null;
let summaryError = '';
if (fs.existsSync(summaryPath)) {
  try {
    summary = JSON.parse(fs.readFileSync(summaryPath, 'utf8'));
  } catch (error) {
    summaryError = `summary.json is not valid JSON: ${error.message}`;
  }
} else {
  summaryError = 'summary.json was not produced (k6 aborted before its summary).';
}

const metrics = summary && typeof summary.metrics === 'object' ? summary.metrics : {};

function valuesOf(metricName) {
  const metric = metrics[metricName];
  if (!metric || typeof metric !== 'object') {
    return {};
  }
  // k6 1.3 --summary-export writes metric values flat on the metric object;
  // older/alternate shapes nest them under `values`.
  if (metric.values && typeof metric.values === 'object') {
    return metric.values;
  }
  return metric;
}

function metricValue(metricName, key) {
  const values = valuesOf(metricName);
  if (typeof values[key] === 'number' && Number.isFinite(values[key])) {
    return values[key];
  }
  // Rate metrics (checks, flow_errors, http_req_failed) export `value`.
  if (key === 'rate' && typeof values.value === 'number' && Number.isFinite(values.value)) {
    return values.value;
  }
  return null;
}

function firstMetricValue(metricNames, key) {
  for (const name of metricNames) {
    const value = metricValue(name, key);
    if (value !== null) {
      return value;
    }
  }
  return null;
}

const keyMetrics = {
  iterations: metricValue('iterations', 'count'),
  iterationsRate: metricValue('iterations', 'rate'),
  requests: metricValue('http_reqs', 'count'),
  rps: metricValue('http_reqs', 'rate'),
  durationAvgMs: metricValue('http_req_duration', 'avg'),
  durationP95Ms: metricValue('http_req_duration', 'p(95)'),
  durationP99Ms: metricValue('http_req_duration', 'p(99)'),
  durationMaxMs: metricValue('http_req_duration', 'max'),
  productP95Ms: firstMetricValue(
    ['http_req_duration{flow:product}', 'http_req_duration{flow:read}'],
    'p(95)',
  ),
  productP99Ms: firstMetricValue(
    ['http_req_duration{flow:product}', 'http_req_duration{flow:read}'],
    'p(99)',
  ),
  failedRate: metricValue('http_req_failed', 'rate'),
  flowErrorRate: metricValue('flow_errors', 'rate'),
  serverErrorRate: metricValue('server_errors', 'rate'),
  checksRate: metricValue('checks', 'rate'),
  checksPasses: metricValue('checks', 'passes'),
  checksFails: metricValue('checks', 'fails'),
  expected409: metricValue('seckill_expected_409', 'count'),
  expected429: metricValue('seckill_expected_429', 'count'),
  terminalFailed: metricValue('seckill_terminal_failed', 'count'),
  pollTimeouts: metricValue('seckill_poll_timeouts', 'count'),
  vusMax: metricValue('vus_max', 'max'),
};

// --- Thresholds -------------------------------------------------------------
// k6 1.3 summary-export stores thresholds as {"rule": <boolean>} without a
// documented ok/crossed meaning, so the human-readable threshold block in
// k6.log (✓/✗ plus the observed value) is the authoritative source for the
// per-rule verdict. The k6 exit code remains the overall verdict anchor.
let logText = '';
if (args.log && fs.existsSync(args.log)) {
  logText = fs.readFileSync(args.log, 'utf8');
}

const thresholdResults = [];
let thresholdMetric = '';
for (const rawLine of logText.split(/\r?\n/)) {
  const resultMatch = rawLine.match(/^\s*(✓|✗)\s+'([^']+)'\s*(.*)$/);
  if (resultMatch) {
    thresholdResults.push({
      metric: thresholdMetric || '—',
      rule: resultMatch[2],
      detail: resultMatch[3].trim(),
      ok: resultMatch[1] === '✓',
    });
    continue;
  }
  const trimmed = rawLine.trim();
  if (!trimmed || trimmed.includes('THRESHOLDS') || trimmed.startsWith('█')) {
    continue;
  }
  // Metric header lines sit at four-space indentation and contain no spaces.
  if (/^\s{4}[A-Za-z0-9_{}:"-]+$/.test(rawLine)) {
    thresholdMetric = trimmed;
  }
}

const configuredThresholds = {};
for (const entry of thresholdResults) {
  if (!configuredThresholds[entry.metric]) {
    configuredThresholds[entry.metric] = [];
  }
  configuredThresholds[entry.metric].push(entry.rule);
}
for (const [metricName, metric] of Object.entries(metrics)) {
  if (!metric || typeof metric.thresholds !== 'object' || metric.thresholds === null) {
    continue;
  }
  for (const rule of Object.keys(metric.thresholds)) {
    if (!configuredThresholds[metricName]) {
      configuredThresholds[metricName] = [];
    }
    if (!configuredThresholds[metricName].includes(rule)) {
      configuredThresholds[metricName].push(rule);
    }
    if (!thresholdResults.some((entry) => entry.metric === metricName && entry.rule === rule)) {
      // k6 1.3 writes the boolean as "crossed" (true on FAIL, false on PASS);
      // used only when the log block could not be parsed.
      const crossed = metric.thresholds[rule];
      thresholdResults.push({
        metric: metricName,
        rule,
        detail: '',
        ok: typeof crossed === 'boolean' ? !crossed : null,
      });
    }
  }
}

// --- Verdict ----------------------------------------------------------------
let verdict = 'PASS';
let verdictReason = 'k6 exit 0 and every configured threshold passed';
const failedThresholds = thresholdResults.filter((entry) => entry.ok === false);
if (k6Exit !== 0) {
  verdict = 'FAIL';
  verdictReason = `k6 exited ${k6Exit} (crossed threshold or runtime failure)`;
} else if (!summary) {
  verdict = 'FAIL';
  verdictReason = summaryError;
} else if (failedThresholds.length > 0) {
  verdict = 'FAIL';
  verdictReason = `${failedThresholds.length} threshold(s) crossed`;
}

const testRunDurationMs =
  summary && summary.state && typeof summary.state.testRunDurationMs === 'number'
    ? summary.state.testRunDurationMs
    : null;
const durationSeconds =
  testRunDurationMs !== null
    ? Math.round(testRunDurationMs / 100) / 10
    : Math.max(0, endEpoch - startEpoch);

const startMs = startEpoch > 0 ? startEpoch * 1000 : Date.now();
const endMs = endEpoch > 0 ? endEpoch * 1000 : Date.now();
const grafanaLink =
  grafanaUrl && grafanaDashboard
    ? `${grafanaUrl}/d/${encodeURIComponent(grafanaDashboard)}?from=${startMs}&to=${endMs}`
    : '';
const promQuery = `k6_http_reqs_total{run_id="${runId}"}`;
const prometheusLink = promUrl
  ? `${promUrl}/graph?g0.expr=${encodeURIComponent(promQuery)}&g0.tab=0`
  : '';
const reportDirName = reportDir ? path.basename(reportDir) : runId;
const resultDocName = resultDocPath ? path.basename(resultDocPath) : '';

const manifest = {
  schemaVersion: 1,
  scenario,
  runId,
  startedAt,
  finishedAt,
  startEpoch,
  endEpoch,
  durationSeconds,
  git: {
    commit: args['git-commit'] || 'unknown',
    branch: args['git-branch'] || 'unknown',
  },
  k6: {
    image: args['k6-image'] || '',
    script: `scenarios/${scenario}.js`,
    exitCode: k6Exit,
    output: 'experimental-prometheus-rw',
    remoteWrite,
    summaryExport: 'summary.json',
    env: envOverrides,
  },
  reset: {
    executed: resetExecuted,
    baselineStock: resetBaseline,
    log: resetExecuted ? args['reset-log'] || 'reset.log' : null,
  },
  thresholds: {
    expected: thresholdsExpected,
    results: thresholdResults,
    configured: configuredThresholds,
  },
  keyMetrics,
  verdict,
  verdictReason,
  summaryError: summary ? null : summaryError,
  artifacts: {
    summary: 'summary.json',
    k6Log: 'k6.log',
    manifest: 'manifest.json',
    report: 'report.html',
    resetLog: resetExecuted ? args['reset-log'] || 'reset.log' : null,
    resultDoc: resultDocName,
  },
};

fs.mkdirSync(path.dirname(manifestPath), { recursive: true });
fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');

// --- Formatting helpers -----------------------------------------------------
function escapeHtml(value) {
  return String(value === null || value === undefined ? '' : value).replace(
    /[&<>"']/g,
    (character) =>
      ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[character],
  );
}

function formatCount(value) {
  return value === null || value === undefined ? '—' : Math.round(value).toLocaleString('en-US');
}

function formatDecimal(value, digits = 2) {
  return value === null || value === undefined ? '—' : Number(value).toFixed(digits);
}

function formatMs(value) {
  return value === null || value === undefined ? '—' : `${Number(value).toFixed(2)} ms`;
}

function formatPercent(value) {
  return value === null || value === undefined ? '—' : `${(Number(value) * 100).toFixed(2)}%`;
}

const metricRows = [
  ['iterations', formatCount(keyMetrics.iterations)],
  ['iterations/s', formatDecimal(keyMetrics.iterationsRate)],
  ['http_reqs', formatCount(keyMetrics.requests)],
  ['RPS (avg)', formatDecimal(keyMetrics.rps)],
  ['http_req_duration avg', formatMs(keyMetrics.durationAvgMs)],
  ['http_req_duration P95', formatMs(keyMetrics.durationP95Ms)],
  ['http_req_duration P99', formatMs(keyMetrics.durationP99Ms)],
  ['http_req_duration max', formatMs(keyMetrics.durationMaxMs)],
];
if (keyMetrics.productP95Ms !== null) {
  metricRows.push(['product read P95', formatMs(keyMetrics.productP95Ms)]);
}
if (keyMetrics.productP99Ms !== null) {
  metricRows.push(['product read P99', formatMs(keyMetrics.productP99Ms)]);
}
metricRows.push(
  ['http_req_failed rate', formatPercent(keyMetrics.failedRate)],
  ['flow_errors rate', formatPercent(keyMetrics.flowErrorRate)],
  ['server_errors rate', formatPercent(keyMetrics.serverErrorRate)],
  [
    'checks',
    keyMetrics.checksRate === null
      ? '—'
      : `${formatPercent(keyMetrics.checksRate)} (${formatCount(keyMetrics.checksPasses)} pass / ${formatCount(keyMetrics.checksFails)} fail)`,
  ],
  ['seckill 409 (sold out / lock)', formatCount(keyMetrics.expected409)],
  ['seckill 429 (rate limited)', formatCount(keyMetrics.expected429)],
  ['seckill terminal failed', formatCount(keyMetrics.terminalFailed)],
  ['seckill poll timeouts', formatCount(keyMetrics.pollTimeouts)],
  ['VUs max', formatCount(keyMetrics.vusMax)],
);

const verdictClass = verdict === 'PASS' ? 'pass' : 'fail';

function thresholdRowsHtml() {
  if (thresholdResults.length === 0) {
    return `<tr><td colspan="4" class="muted">${
      thresholdsExpected
        ? 'No threshold results in k6.log or summary.json'
        : 'Scenario has no thresholds (record-only)'
    }</td></tr>`;
  }
  return thresholdResults
    .map(
      (entry) =>
        `<tr><td><code>${escapeHtml(entry.metric)}</code></td><td><code>${escapeHtml(
          entry.rule,
        )}</code></td><td>${escapeHtml(entry.detail || '—')}</td><td class="${
          entry.ok === false ? 'fail-text' : 'pass-text'
        }">${entry.ok === false ? 'FAIL' : entry.ok === true ? 'PASS' : 'UNKNOWN'}</td></tr>`,
    )
    .join('\n');
}

const html = `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Bench ${escapeHtml(scenario)} · ${escapeHtml(runId)}</title>
<style>
  :root { color-scheme: light; --ink:#1f2933; --muted:#667085; --line:#e4e7ec;
    --bg:#f8fafc; --card:#ffffff; --pass:#067647; --fail:#b42318; --brand:#175cd3; }
  * { box-sizing: border-box; }
  body { margin:0; padding:32px; background:var(--bg); color:var(--ink);
    font:14px/1.55 -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,"Helvetica Neue",Arial,sans-serif; }
  main { max-width:1080px; margin:0 auto; }
  header { display:flex; align-items:flex-start; justify-content:space-between; gap:24px;
    background:var(--card); border:1px solid var(--line); border-radius:12px; padding:24px; }
  h1 { margin:0 0 6px; font-size:22px; }
  h2 { margin:32px 0 12px; font-size:16px; }
  .muted { color:var(--muted); }
  .badge { display:inline-block; padding:6px 14px; border-radius:999px; font-weight:700;
    letter-spacing:.04em; color:#fff; }
  .badge.pass { background:var(--pass); }
  .badge.fail { background:var(--fail); }
  .grid { display:grid; grid-template-columns:repeat(auto-fit,minmax(220px,1fr)); gap:12px; }
  .card { background:var(--card); border:1px solid var(--line); border-radius:10px; padding:16px; }
  .card .label { color:var(--muted); font-size:12px; text-transform:uppercase; letter-spacing:.05em; }
  .card .value { font-size:18px; margin-top:4px; font-variant-numeric:tabular-nums; }
  table { width:100%; border-collapse:collapse; background:var(--card);
    border:1px solid var(--line); border-radius:10px; overflow:hidden; }
  th, td { text-align:left; padding:10px 14px; border-bottom:1px solid var(--line); }
  th { background:#f2f4f7; font-size:12px; text-transform:uppercase; letter-spacing:.05em; color:var(--muted); }
  tr:last-child td { border-bottom:0; }
  td:first-child { width:52%; }
  code { background:#f2f4f7; padding:1px 5px; border-radius:4px; font-size:12.5px; }
  .fail-text { color:var(--fail); font-weight:600; }
  .pass-text { color:var(--pass); font-weight:600; }
  a { color:var(--brand); }
  ul { margin:8px 0; padding-left:20px; }
  footer { margin-top:32px; color:var(--muted); font-size:12px; }
</style>
</head>
<body>
<main>
<header>
  <div>
    <h1>Benchmark · ${escapeHtml(scenario)}</h1>
    <div class="muted">run_id <code>${escapeHtml(runId)}</code> · ${escapeHtml(startedAt)} → ${escapeHtml(finishedAt)}</div>
    <div class="muted">${escapeHtml(verdictReason)}</div>
  </div>
  <span class="badge ${verdictClass}">${escapeHtml(verdict)}</span>
</header>

<section class="grid" style="margin-top:20px">
  <div class="card"><div class="label">Duration</div><div class="value">${escapeHtml(String(durationSeconds))} s</div></div>
  <div class="card"><div class="label">Requests</div><div class="value">${formatCount(keyMetrics.requests)}</div></div>
  <div class="card"><div class="label">RPS</div><div class="value">${formatDecimal(keyMetrics.rps)}</div></div>
  <div class="card"><div class="label">P95 / P99</div><div class="value">${formatMs(keyMetrics.durationP95Ms)} / ${formatMs(keyMetrics.durationP99Ms)}</div></div>
  <div class="card"><div class="label">Error rate</div><div class="value">${formatPercent(keyMetrics.flowErrorRate)}</div></div>
  <div class="card"><div class="label">Checks</div><div class="value">${formatPercent(keyMetrics.checksRate)}</div></div>
</section>

<h2>Key metrics</h2>
<table>
  <thead><tr><th>Metric</th><th>Value</th></tr></thead>
  <tbody>
    ${metricRows
      .map(([label, value]) => `<tr><td>${escapeHtml(label)}</td><td>${escapeHtml(value)}</td></tr>`)
      .join('\n    ')}
  </tbody>
</table>

<h2>Thresholds</h2>
<table>
  <thead><tr><th>Metric</th><th>Rule</th><th>Observed</th><th>Result</th></tr></thead>
  <tbody>
    ${thresholdRowsHtml()}
  </tbody>
</table>

<h2>Run record</h2>
<table>
  <tbody>
    <tr><td>k6 image</td><td><code>${escapeHtml(args['k6-image'] || '')}</code></td></tr>
    <tr><td>k6 exit code</td><td>${escapeHtml(String(k6Exit))}</td></tr>
    <tr><td>git</td><td><code>${escapeHtml(manifest.git.branch)}</code> / <code>${escapeHtml(manifest.git.commit)}</code></td></tr>
    <tr><td>reset</td><td>${resetExecuted ? `yes, baseline stock ${escapeHtml(String(resetBaseline))}` : 'skipped (--no-reset)'}</td></tr>
    <tr><td>remote write</td><td><code>${escapeHtml(remoteWrite || '—')}</code></td></tr>
    <tr><td>archive</td><td><code>code/lab/k6/reports/${escapeHtml(reportDirName)}/</code></td></tr>
    ${grafanaLink ? `<tr><td>Grafana</td><td><a href="${escapeHtml(grafanaLink)}">D4 · ${escapeHtml(grafanaDashboard)}</a></td></tr>` : ''}
    ${prometheusLink ? `<tr><td>Prometheus</td><td><a href="${escapeHtml(prometheusLink)}">k6_http_reqs_total graph</a></td></tr>` : ''}
    ${resultDocName ? `<tr><td>result doc</td><td><code>doc/process/benchmark/${escapeHtml(resultDocName)}</code></td></tr>` : ''}
  </tbody>
</table>

${summaryError ? `<h2>Summary problem</h2><p class="fail-text">${escapeHtml(summaryError)}</p>` : ''}

<h2>Environment overrides</h2>
${
  Object.keys(envOverrides).length === 0
    ? '<p class="muted">None; k6 used its documented defaults.</p>'
    : `<ul>${Object.entries(envOverrides)
        .map(([key, value]) => `<li><code>${escapeHtml(key)}=${escapeHtml(value)}</code></li>`)
        .join('')}</ul>`
}

<footer>Generated by code/lab/scripts/bench-report.js from k6 summary.json. Files: summary.json, k6.log, manifest.json, report.html.</footer>
</main>
</body>
</html>
`;

fs.mkdirSync(path.dirname(htmlPath), { recursive: true });
fs.writeFileSync(htmlPath, html, 'utf8');

// --- Markdown result doc ----------------------------------------------------
if (resultDocPath) {
  const thresholdLines =
    thresholdResults.length === 0
      ? thresholdsExpected
        ? '| — | — | — | k6.log/summary 中无阈值结果 |'
        : '| — | — | — | 该场景无阈值（record-only） |'
      : thresholdResults
          .map(
            (entry) =>
              `| \`${entry.metric}\` | \`${entry.rule}\` | ${entry.detail || '—'} | ${
                entry.ok === false ? 'FAIL' : entry.ok === true ? 'PASS' : 'UNKNOWN'
              } |`,
          )
          .join('\n');

  const overrideLines =
    Object.keys(envOverrides).length === 0
      ? '- 无（使用 k6 文档默认值）'
      : Object.entries(envOverrides)
          .map(([key, value]) => `- \`${key}=${value}\``)
          .join('\n');

  const markdown = `# 压测结果 · ${scenario} · ${runId}

> 日期：${startedAt}（结束 ${finishedAt}）
> 分支 / commit：\`${manifest.git.branch}\` / \`${manifest.git.commit}\`
> run_id：\`${runId}\`
> 结论：**${verdict}**（k6 exit ${k6Exit}；${verdictReason}）
> 归档：\`code/lab/k6/reports/${reportDirName}/\`

## 关键指标

| 指标 | 值 |
|---|---|
${metricRows.map(([label, value]) => `| ${label} | ${value} |`).join('\n')}

- 持续时间：${durationSeconds}s
- k6 镜像：\`${args['k6-image'] || ''}\`
- 远程写入：\`${remoteWrite || '—'}\`

## 阈值判定

| Metric | Rule | Observed | 结果 |
|---|---|---|---|
${thresholdLines}

## 运行信息

- 场景：\`${scenario}\`（\`scenarios/${scenario}.js\`）
- 时间窗（epoch）：${startMs} → ${endMs}
- Run ID 查询：\`${promQuery}\`
${grafanaLink ? `- Grafana：${grafanaLink}` : '- Grafana：未配置'}
${prometheusLink ? `- Prometheus：${prometheusLink}` : '- Prometheus：未配置'}
- 数据重置：${resetExecuted ? `已执行，baseline stock=${resetBaseline}（reset.log）` : '跳过（--no-reset）'}

### Manifest 摘要

- verdict：\`${verdict}\`（${verdictReason}）
- k6 exit：${k6Exit}；image \`${args['k6-image'] || ''}\`；output \`experimental-prometheus-rw\`
- git：\`${manifest.git.branch}\` / \`${manifest.git.commit}\`
- reset：executed=${resetExecuted}, baselineStock=${resetBaseline}
- 阈值结果：${thresholdResults.length} 条（FAIL ${failedThresholds.length} 条）

### 环境覆盖

${overrideLines}

## 归档文件

- \`report.html\`：本页 HTML 报告
- \`summary.json\`：k6 \`--summary-export\` 原始输出
- \`k6.log\`：k6 标准输出/错误
- \`manifest.json\`：场景、运行时间、git、reset、阈值与 verdict
${resetExecuted ? '- `reset.log`：重置脚本输出\n' : ''}
${summaryError ? `\n> 注意：${summaryError}\n` : ''}`;

  fs.mkdirSync(path.dirname(resultDocPath), { recursive: true });
  fs.writeFileSync(resultDocPath, `${markdown.trimEnd()}\n`, 'utf8');
}

process.stdout.write(`BENCH_VERDICT=${verdict}\n`);
process.stdout.write(`BENCH_REPORT=${htmlPath}\n`);
if (resultDocPath) {
  process.stdout.write(`BENCH_RESULT_DOC=${resultDocPath}\n`);
}
