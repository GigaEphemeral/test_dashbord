/**
 * Console smoke test for the B3-00 six-section workspace.
 *
 * The old seven-section sidebar (总览 / 场景向导 / 实例与 JVM / Benchmark /
 * 实时监控 / 故障与缓存实验 / 实验记录) is gone. The console now exposes
 * exactly six sections; the old 故障与缓存实验 page lives inside
 * 实时监控与操作 as the 实时操作 / 实验工具 tabs, and the fault runner is the
 * 故障演练 tab of 场景向导. ScenarioWizard, ThreadLab, CacheLab, ChaosActions
 * and AnimationLab keep their testids, so the deep assertions moved with them.
 *
 * New coverage on top of the moved blocks:
 *   - shell: exact six nav ids/order, no nav-faults, token input, auto-sync,
 *     controller online;
 *   - experiment wizard: eight steps, node scoping (app-3 unchecked excludes
 *     its config row / target option, <2 nodes locks the instance goal), the
 *     k6 load form, the product-sampling preview, a manual + an auto fault row
 *     (manual disables the TTL), draft → DRAFT + pre-check, start → jump to
 *     observe with a RUNNING jl-k6- container, stop → COMPLETED;
 *   - observe: 13 KPI cards with quality badges, four charts, k6 progress,
 *     three instance rows, topology == /containers, then the legacy cache /
 *     chaos / thread / animation blocks on the new tabs;
 *   - ledger: named DRAFT, pre-check, start/k6/log/report, two-run compare,
 *     archive/unarchive, delete through the modal + 404 afterwards, reload
 *     restore.
 *
 * Ownership: every run this suite creates is named
 * `SMOKE_RUN_MARKER-<timestamp>-<tag>` and recorded in `ownedRunIds`; cleanup
 * stops a still-running owned run and deletes it. Runs created by anyone else
 * (no marker name, no legacy prefix/note) are never touched.
 *
 * Run through code/lab/scripts/console-smoke.sh (it resolves Playwright,
 * CONSOLE_URL, the lab token and the expected pass count). Requires the stack
 * running and the dashboard built (`npm run build` in code/lab/dashboard).
 */
import process from 'node:process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

const CONSOLE_URL = process.env.CONSOLE_URL || 'http://127.0.0.1:8080/lab/';
const LAB_TOKEN = process.env.LAB_TOKEN || 'lab-token';
const API_BASE = process.env.LAB_API_BASE || 'http://127.0.0.1:8080/lab-api';
// Guide scenario used by the 故障演练 tab (legacy ScenarioWizard).
const SCENARIO_ID = process.env.SMOKE_SCENARIO_ID || 'quick-failover';
const LOG_CONTAINER = process.env.SMOKE_LOG_CONTAINER || 'jl-app-1';
const ACTION_TIMEOUT_MS = Number(process.env.SMOKE_ACTION_TIMEOUT_MS || 20000);
// The ledger block creates runs through the UI; they launch real k6 containers.
// LOAD_DURATION=30s keeps them RUNNING while the k6 card/log/report assertions
// read the live container; the smoke stops each run itself.
const RUN_SCENARIO = process.env.SMOKE_RUN_SCENARIO || 'load';
const LOAD_DURATION = process.env.SMOKE_LOAD_DURATION || '30s';
const LOAD_VUS = Number(process.env.SMOKE_LOAD_VUS || 5);
// Every run this suite creates carries this name prefix (unique per run via the
// timestamp). It is the ownership mark used by stop/delete cleanup.
const RUN_NAME_MARKER = process.env.SMOKE_RUN_MARKER || 'SMOKE_RUN_MARKER';
// Legacy ownership marks from the previous suite revision (a crashed run of the
// old script may still hold the write-run lock).
const RUN_SCENARIO_PREFIX = process.env.SMOKE_RUN_SCENARIO_PREFIX || 'console-smoke-';
const LEGACY_NOTE = 'console-smoke';
const RUN_PARAMS = process.env.SMOKE_RUN_PARAMS || JSON.stringify({
  note: RUN_NAME_MARKER,
  source: 'playwright',
  LOAD_START_VUS: 1,
  LOAD_VUS,
  LOAD_DURATION,
  TRAFFIC_MODE: 'via-nginx',
});
const RUN_STAMP = new Date().toISOString().replace(/[-:TZ.]/g, '');
// UI-launchable scenarios; refreshed from the catalog, with the manifest
// defaults as the offline fallback.
let UI_SCENARIOS = ['smoke', 'load', 'spike'];
// Defaults mirror VITE_LAB_KPI_KEYS / VITE_LAB_RUN_METRIC_KEYS (12 + 1 probe).
const KPI_COUNT = Number(process.env.SMOKE_KPI_COUNT || 13);
const RUN_METRIC_COUNT = Number(process.env.SMOKE_RUN_METRIC_COUNT || 13);
// The suite is expected to pass at least this many checks on a healthy stack.
// 0 disables the gate (e.g. when experimenting with a partial branch).
const MIN_PASS_COUNT = Number(process.env.SMOKE_MIN_PASS_COUNT || 0);
// TTL is a safety net: the smoke clears the fault with stop-all as soon as the
// paused badge is asserted. 30s leaves room for one 5s environment sync plus
// the poll interval without leaving app-2 frozen for long.
const FAULT_TTL_SECONDS = 30;
// Waiting for cache traffic can take a scrape interval (5s) plus two samples
// for rate()[1m] to leave zero; reuse the existing polling budget, widened.
const MOTION_TIMEOUT_MS = Math.max(ACTION_TIMEOUT_MS * 2, 30000);
// Public contract of CacheDefenseMode; the cache page renders exactly these keys.
const CACHE_MODES = [
  'off', 'null-cache', 'bloom', 'mutex',
  'logical-expire', 'ttl-jitter', 'l1-cache', 'db-fallback',
];
// Exact order of the six workspace sections (App.vue SECTIONS).
const SECTIONS = ['overview', 'wizard', 'instances', 'bench', 'observe', 'runs'];
const QUALITY = ['OK', 'NO_DATA', 'COLLECTING', 'DEGRADED'];

let passed = 0;
const failures = [];
// Every run this suite created (wizard draft + ledger draft). Cleanup stops a
// still-running entry and deletes it; the marker-name scan catches a run whose
// id never reached this set because the console failed mid-flow.
const ownedRunIds = new Set();

function check(label, condition, detail = '') {
  if (condition) {
    passed += 1;
    console.log(`PASS  ${label}`);
  } else {
    failures.push(`${label}${detail ? ` (${detail})` : ''}`);
    console.log(`FAIL  ${label}${detail ? `: ${detail}` : ''}`);
  }
  return condition;
}

function loadChromium() {
  const candidates = [process.env.PLAYWRIGHT_MODULE, 'playwright', 'playwright-core'].filter(Boolean);
  for (const specifier of candidates) {
    try {
      return require(specifier).chromium;
    } catch {
      // try the next resolution strategy
    }
  }
  throw new Error(
    `Playwright is not resolvable (tried: ${candidates.join(', ')}). `
    + 'Install it (npm i -D playwright) or point PLAYWRIGHT_MODULE at its directory.',
  );
}

/**
 * Playwright's managed Chromium may be absent on an offline lab host (the
 * browser download is version-pinned). Fall back to the locally installed
 * Chrome/Edge rather than requiring network access.
 */
async function launchBrowser(chromium) {
  const headless = !process.env.SMOKE_HEADED;
  try {
    return await chromium.launch({ headless });
  } catch (error) {
    console.log(`WARN  managed chromium unavailable (${error.message.split('\n')[0]}); trying installed browsers`);
    const channels = (process.env.SMOKE_BROWSER_CHANNELS || 'chrome,msedge').split(',');
    for (const channel of channels) {
      try {
        const browser = await chromium.launch({ headless, channel });
        console.log(`INFO  using browser channel "${channel}"`);
        return browser;
      } catch {
        // try the next channel
      }
    }
    throw error;
  }
}

function newIdempotencyKey() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID();
  return `smoke-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

async function apiRequest(path, { method = 'GET', body, headers: extraHeaders = {} } = {}) {
  const headers = { 'X-Lab-Token': LAB_TOKEN, ...extraHeaders };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
  }
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    throw new Error(`${method} ${path} -> HTTP ${response.status}`);
  }
  return response.json();
}

/** One request that keeps the status instead of throwing (used for 404 checks). */
async function apiStatus(path, { method = 'GET' } = {}) {
  const response = await fetch(`${API_BASE}${path}`, {
    method,
    headers: { 'X-Lab-Token': LAB_TOKEN },
  });
  return response.status;
}

/** Controller writes wrap their payload in Result.data; /containers does too. */
function unwrap(payload) {
  return payload && typeof payload === 'object' && 'data' in payload ? payload.data : payload;
}

async function apiPost(path, body) {
  return apiRequest(path, { method: 'POST', body });
}

function uniqueRunName(tag) {
  return `${RUN_NAME_MARKER}-${RUN_STAMP}-${tag}`;
}

async function loadUiScenarios() {
  try {
    const catalog = unwrap(await apiRequest('/lab/bench/scenarios'));
    const ui = catalog?.views?.ui;
    if (Array.isArray(ui) && ui.length > 0) {
      UI_SCENARIOS = ui;
    }
  } catch (err) {
    console.log(`WARN  could not read views.ui (${err.message}); using ${UI_SCENARIOS.join(',')}`);
  }
}

/**
 * A crashed smoke may still hold the write-run lock. Ownership marks: the run's
 * name prefix (current), the legacy scenario prefix, or a run whose request
 * params carry this tool's note marker on a UI-launchable scenario. A user's
 * active demo is left alone.
 */
async function isSmokeOwnedRun(run) {
  if (String(run.name || '').startsWith(RUN_NAME_MARKER)) return true;
  if (String(run.scenario || '').startsWith(RUN_SCENARIO_PREFIX)) return true;
  if (ownedRunIds.has(run.runId)) return true;
  if (!UI_SCENARIOS.includes(run.scenario)) return false;
  try {
    const detail = unwrap(await apiRequest(`/lab/runs/${encodeURIComponent(run.runId)}`));
    const request = (detail?.params || []).find((param) => param.group === 'request');
    const note = request?.payload?.note;
    return note === RUN_NAME_MARKER || note === LEGACY_NOTE;
  } catch (err) {
    console.log(`WARN  could not read params of run ${run.runId}: ${err.message}`);
    return false;
  }
}

/** Stop a leftover RUNNING/UNKNOWN run that this tool owns. */
async function stopActiveRuns() {
  await loadUiScenarios();
  const seen = new Set();
  for (const status of ['RUNNING', 'UNKNOWN']) {
    try {
      const page = unwrap(await apiRequest(`/lab/runs?status=${status}&limit=50`));
      for (const run of page?.items || []) {
        if (seen.has(run.runId)) continue;
        seen.add(run.runId);
        if (!(await isSmokeOwnedRun(run))) continue;
        try {
          await apiRequest(`/lab/runs/${encodeURIComponent(run.runId)}/stop`, {
            method: 'POST',
            headers: { 'Idempotency-Key': newIdempotencyKey() },
          });
          console.log(`INFO  stopped leftover ${status} run ${run.runId}`);
        } catch (err) {
          console.log(`WARN  could not stop leftover run ${run.runId}: ${err.message}`);
        }
      }
    } catch (err) {
      console.log(`WARN  could not list ${status} runs: ${err.message}`);
    }
  }
}

const TERMINAL_STATUSES = ['COMPLETED', 'FAILED', 'ABORTED', 'INVALID'];
const ACTIVE_STATUSES = ['RUNNING', 'STOPPING', 'VALIDATING', 'READY', 'UNKNOWN'];

/** Stop (if needed), wait for a terminal status and hard-delete one owned run. */
async function deleteOwnedRun(runId) {
  let status = '';
  try {
    const detail = unwrap(await apiRequest(`/lab/runs/${encodeURIComponent(runId)}`));
    status = detail?.run?.status || '';
  } catch {
    // Already gone: nothing to delete.
    return;
  }
  if (ACTIVE_STATUSES.includes(status)) {
    try {
      await apiRequest(`/lab/runs/${encodeURIComponent(runId)}/stop`, {
        method: 'POST',
        headers: { 'Idempotency-Key': newIdempotencyKey() },
      });
    } catch (err) {
      console.log(`WARN  could not stop owned run ${runId}: ${err.message}`);
    }
    await waitForCondition(async () => {
      try {
        const detail = unwrap(await apiRequest(`/lab/runs/${encodeURIComponent(runId)}`));
        const now = detail?.run?.status || '';
        return { ok: TERMINAL_STATUSES.includes(now), detail: now };
      } catch {
        return { ok: true, detail: 'gone' };
      }
    }, ACTION_TIMEOUT_MS);
  }
  try {
    await apiRequest(`/lab/runs/${encodeURIComponent(runId)}`, { method: 'DELETE' });
    ownedRunIds.delete(runId);
    console.log(`INFO  deleted owned run ${runId}`);
  } catch (err) {
    console.log(`WARN  could not delete owned run ${runId}: ${err.message}`);
  }
}

/** Delete every run that carries this tool's name marker or was recorded here. */
async function cleanupOwnedRuns() {
  const ids = new Set(ownedRunIds);
  try {
    const page = unwrap(await apiRequest('/lab/runs?limit=100&includeArchived=true'));
    for (const run of page?.items || []) {
      if (String(run.name || '').startsWith(RUN_NAME_MARKER)) ids.add(run.runId);
    }
  } catch (err) {
    console.log(`WARN  could not list runs for cleanup: ${err.message}`);
  }
  for (const runId of ids) {
    await deleteOwnedRun(runId);
  }
}

/** Best-effort cleanup so a failed smoke never leaves the lab in a stuck state. */
async function restoreLabState(page) {
  try {
    await apiPost('/lab/traffic/stop');
  } catch (err) {
    console.log(`WARN  could not stop background traffic: ${err.message}`);
  }
  try {
    await apiPost('/lab/guide/reset');
  } catch (err) {
    console.log(`WARN  could not reset the guide: ${err.message}`);
  }
  // Container faults (the animation block injects freeze) are cleared here as
  // well: stop-all is idempotent, resumes a paused app and also clears anything
  // a crashed previous run left behind.
  try {
    await apiPost('/chaos/stop-all');
  } catch (err) {
    console.log(`WARN  could not stop container faults: ${err.message}`);
  }
  // Thread faults are injected through the narrow proxy; stop-all is idempotent
  // and also clears a fault left behind by a crashed previous run.
  try {
    await apiPost('/chaos/app/app-1/lab/thread/stop-all');
  } catch (err) {
    console.log(`WARN  could not stop thread faults: ${err.message}`);
  }
  // The cache mode is per-instance and survives a crashed run; normalize every
  // instance back to off so the next demo starts from the documented baseline.
  for (const instance of ['app-1', 'app-2', 'app-3']) {
    try {
      await apiPost(`/chaos/app/${instance}/lab/cache/mode`, { mode: 'off' });
    } catch (err) {
      console.log(`WARN  could not reset ${instance} cache mode to off: ${err.message}`);
    }
  }
  await stopActiveRuns();
  await cleanupOwnedRuns();
  // The fault runner lives in the wizard's 故障演练 tab now; refresh it when the
  // page is parked there so the next reader sees the reset state.
  if (page && (await page.getByTestId('wizard-refresh').count()) > 0) {
    try {
      await page.getByTestId('wizard-refresh').click({ timeout: 5000 });
    } catch {
      // the page is already gone; the API cleanup above is what matters
    }
  }
}

/** Force the animation view to sync instead of waiting for its 5s timer. */
async function refreshAnimation(page) {
  try {
    await page.getByTestId('animation-refresh').click({ timeout: 5000 });
  } catch {
    // busy or gone: the 5s poller (or the assertion's own deadline) takes over
  }
}

async function gotoSection(page, id) {
  const button = page.getByTestId(`nav-${id}`);
  await button.click();
  await button.waitFor({ state: 'visible', timeout: ACTION_TIMEOUT_MS });
}

/** Read the settled operation card after start/stop. */
async function waitOperation(page, timeoutMs = ACTION_TIMEOUT_MS) {
  return waitForCondition(async () => {
    const idLoc = page.getByTestId('run-operation-id');
    if ((await idLoc.count()) === 0) {
      return { ok: false, detail: 'operation id not rendered' };
    }
    const id = ((await idLoc.textContent()) || '').trim();
    const statusLoc = page.getByTestId('run-operation-status');
    const status = ((await statusLoc.textContent()) || '').trim();
    return {
      ok: Boolean(id) && ['APPLIED', 'RESTORED', 'FAILED', 'UNKNOWN'].includes(status),
      detail: `${id} ${status}`,
    };
  }, timeoutMs);
}

async function main() {
  const chromium = loadChromium();
  // A previous crashed run can leave the guide RUNNING, traffic generating
  // load or a RUNNING run holding the write lock; all would skew the checks.
  await restoreLabState(null);
  const browser = await launchBrowser(chromium);
  const page = await browser.newPage();
  const pageErrors = [];
  const failedRequests = [];
  const trackPage = (target) => {
    target.on('pageerror', (error) => pageErrors.push(String(error)));
    target.on('requestfailed', (request) => {
      const url = request.url();
      if (!url.includes('/lab-api/') && !url.includes('/prom/')) return;
      const reason = request.failure()?.errorText || 'unknown';
      // A reload/close cancels in-flight 5s polls; that is not a failed
      // request, and it says nothing about the console.
      if (reason.includes('ERR_ABORTED')) return;
      failedRequests.push(`${request.method()} ${url} (${reason})`);
    });
    target.on('response', (response) => {
      const url = response.url();
      if (!url.includes('/lab-api/') && !url.includes('/prom/')) return;
      // B3-04 documents GET .../reconciliation 404 as "no result yet"; the
      // panel renders it as 无数据. It is an empty-state contract, not a failure.
      const reconciliationEmpty = response.status() === 404
        && /\/lab-api\/lab\/runs\/[^/]+\/reconciliation$/.test(url);
      if (response.status() >= 400 && !reconciliationEmpty) {
        failedRequests.push(`${response.request().method()} ${url} -> HTTP ${response.status()}`);
      }
    });
  };
  trackPage(page);

  try {
    await page.goto(CONSOLE_URL, { waitUntil: 'load' });
    check('console page loads', /实验操作台/.test(await page.title()), await page.title());

    const tokenInput = page.getByTestId('token-input');
    await tokenInput.fill(LAB_TOKEN);
    check(
      'shell: the token input is masked and carries the lab token',
      (await tokenInput.getAttribute('type')) === 'password'
        && (await tokenInput.inputValue()) === LAB_TOKEN,
    );
    await page.getByTestId('connect').click();

    const navButtons = page.locator('.workspace-sidebar [data-testid^="nav-"]');
    const navIds = await navButtons.evaluateAll((nodes) => nodes.map((node) => node.dataset.testid));
    check(
      'shell: sidebar exposes exactly the six workspace sections in order',
      navIds.length === SECTIONS.length && SECTIONS.every((id, index) => navIds[index] === `nav-${id}`),
      navIds.join(','),
    );
    check(
      'shell: the legacy faults section is gone',
      (await page.getByTestId('nav-faults').count()) === 0,
    );

    const healthPill = page.locator('.topbar-meta .pill').first();
    await expectCondition('controller reports online', async () => {
      const text = ((await healthPill.textContent()) || '').trim();
      return { ok: text.includes('在线'), detail: text };
    });
    check('shell: auto-sync is enabled so panels keep polling', await page.getByTestId('auto-sync').isChecked());

    // ------------------------------------------------------------- overview
    await expectText(page.getByTestId('guide-status'), 'idle', 'overview: guide starts idle');
    const rollup = page.getByTestId('container-rollup');
    await expectCondition('overview: every always-on container is running', async () => {
      const text = ((await rollup.textContent()) || '').trim();
      // "0/0" is the first render (containers not fetched yet) and also the
      // shape of a failed fetch whose banner was later cleared; up === total
      // alone would pass on it. Only a positive total can prove the rollup.
      const [up, total] = text.split('/').map((part) => Number(part));
      const ok = Number.isInteger(up) && Number.isInteger(total) && total > 0 && up === total;
      return { ok, detail: text };
    });
    check(
      'overview: reset mode shows the manual default',
      (await page.getByTestId('reset-mode').textContent()).includes('manual'),
    );
    const ledgerState = ((await page.getByTestId('overview-ledger-state').textContent()) || '').trim();
    check(
      'overview: ledger state is readable',
      ['OK', 'DEGRADED', 'NO_DATA'].includes(ledgerState),
      ledgerState,
    );

    // ------------------------------------------------- wizard experiment tab
    await gotoSection(page, 'wizard');
    await expectCondition('wizard: the page opens on the experiment tab', async () => {
      if ((await page.getByTestId('wizard-page').count()) === 0) {
        return { ok: false, detail: 'wizard-page not rendered' };
      }
      const current = await page.getByTestId('wizard-tab-experiment').getAttribute('aria-current');
      return { ok: current === 'page', detail: `aria-current=${current}` };
    });
    const stepIds = await page
      .locator('[data-testid^="wizard-step-"]')
      .evaluateAll((nodes) => nodes.map((node) => node.dataset.testid));
    check(
      'wizard: the stepper exposes the eight steps in order',
      stepIds.length === 8 && stepIds.every((id, index) => id === `wizard-step-${index}`),
      stepIds.join(','),
    );
    const missingPanels = [];
    for (let index = 0; index < 8; index += 1) {
      await page.getByTestId(`wizard-step-${index}`).click();
      const visible = await page.getByTestId(`wizard-panel-${index}`).isVisible().catch(() => false);
      if (!visible) missingPanels.push(index);
    }
    check('wizard: every stepper step opens its own panel', missingPanels.length === 0, missingPanels.join(','));
    check('wizard: step 7 exposes the report entry point', (await page.getByTestId('wizard-report').count()) === 1);
    await page.getByTestId('wizard-step-0').click();
    await page.getByTestId('wizard-next').click();
    const nextWorked = await page.getByTestId('wizard-panel-1').isVisible();
    await page.getByTestId('wizard-prev').click();
    const prevWorked = await page.getByTestId('wizard-panel-0').isVisible();
    check('wizard: next/prev move exactly one step', nextWorked && prevWorked);

    // Node scoping: 3 -> 1 node locks the instance goal, 2 nodes unlock it and
    // leave app-3 out of every later step.
    await page.getByTestId('wizard-node-app-2').uncheck();
    await page.getByTestId('wizard-node-app-3').uncheck();
    await page.getByTestId('wizard-step-1').click();
    await expectCondition('wizard: fewer than two nodes locks the instance goal', async () => {
      const goal = page.getByTestId('wizard-goal-instance');
      const disabled = await goal.isDisabled();
      const text = (await goal.textContent()) || '';
      return {
        ok: disabled && text.includes('需 ≥2 个 Java 节点'),
        detail: `disabled=${disabled} text=${text.replace(/\s+/g, ' ').trim().slice(0, 80)}`,
      };
    });
    check(
      'wizard: throughput stays selectable with one node',
      await page.getByTestId('wizard-goal-throughput').isEnabled(),
    );
    await page.getByTestId('wizard-step-0').click();
    await page.getByTestId('wizard-node-app-2').check();
    await page.getByTestId('wizard-step-1').click();
    await expectCondition('wizard: two nodes unlock the instance goal again', async () => ({
      ok: await page.getByTestId('wizard-goal-instance').isEnabled(),
    }));
    check(
      'wizard: all eight experiment goals are rendered',
      (await page.locator('[data-testid^="wizard-goal-"]').count()) === 8,
    );

    await page.getByTestId('wizard-step-2').click();
    const targetInstance = page.getByTestId('wizard-target-instance');
    check(
      'wizard: the target instance is disabled while traffic goes via nginx',
      await targetInstance.isDisabled(),
    );
    await page.getByTestId('wizard-traffic-mode').selectOption('target-instance');
    const targetOptions = await targetInstance
      .locator('option')
      .evaluateAll((nodes) => nodes.map((node) => node.value));
    check(
      'wizard: target options list only the two enabled nodes',
      targetOptions.length === 2 && targetOptions[0] === 'app-1' && targetOptions[1] === 'app-2',
      targetOptions.join(','),
    );
    await page.getByTestId('wizard-traffic-mode').selectOption('via-nginx');
    check(
      'wizard: traffic mode returns to the nginx default',
      (await page.getByTestId('wizard-traffic-mode').inputValue()) === 'via-nginx'
        && (await targetInstance.isDisabled()),
    );

    await page.getByTestId('wizard-step-3').click();
    await expectCondition('wizard: app-3 is excluded from the config group editor', async () => {
      const row = page.getByTestId('wizard-config-row-app-3');
      if ((await row.count()) === 0) {
        return { ok: false, detail: 'app-3 row missing' };
      }
      const rows = await page.locator('[data-testid^="wizard-config-row-"]').count();
      const text = (await row.textContent()) || '';
      const options = await page
        .getByTestId('wizard-config-instance')
        .locator('option')
        .evaluateAll((nodes) => nodes.map((node) => node.value));
      const editDisabled = await page.getByTestId('wizard-config-edit-app-3').isDisabled();
      return {
        ok: rows === 3
          && text.includes('未启用')
          && text.includes('未启用节点不参与本实验')
          && options.join(',') === 'app-1,app-2'
          && editDisabled,
        detail: `rows=${rows} options=${options.join(',')} editDisabled=${editDisabled}`,
      };
    });

    await page.getByTestId('wizard-step-4').click();
    const scenarioSelect = page.getByTestId('wizard-scenario');
    const scenarioOptions = await scenarioSelect
      .locator('option')
      .evaluateAll((nodes) => nodes.map((node) => node.value));
    check(
      'wizard: k6 scenarios are exactly smoke/load/spike',
      scenarioOptions.join(',') === 'smoke,load,spike',
      scenarioOptions.join(','),
    );
    check('wizard: the k6 scenario defaults to load', (await scenarioSelect.inputValue()) === 'load');
    await page.getByTestId('wizard-param-LOAD_VUS').fill(String(LOAD_VUS));
    // k6 rejects LOAD_VUS < LOAD_START_VUS at script load (exit 107), which
    // would fail the run instantly; the form default is 50.
    await page.getByTestId('wizard-param-LOAD_START_VUS').fill('1');
    await page.getByTestId('wizard-param-LOAD_DURATION').fill(LOAD_DURATION);
    const paramsPreview = await page.getByTestId('wizard-params-json').inputValue();
    check(
      'wizard: params preview carries LOAD_VUS, LOAD_START_VUS, LOAD_DURATION and the traffic selector',
      paramsPreview.includes(`"LOAD_VUS": ${LOAD_VUS}`)
        && paramsPreview.includes('"LOAD_START_VUS": 1')
        && paramsPreview.includes(`"LOAD_DURATION": "${LOAD_DURATION}"`)
        && paramsPreview.includes('"TRAFFIC_MODE": "via-nginx"'),
      paramsPreview.replace(/\s+/g, ' ').slice(0, 160),
    );
    await expectCondition('wizard: product sampling previews the in/out-of-range share', async () => {
      const block = page.getByTestId('wizard-product-sampling');
      if ((await block.count()) === 0 || !(await block.isVisible())) {
        return { ok: false, detail: 'sampling block not rendered' };
      }
      const summary = ((await page.getByTestId('wizard-product-summary').textContent()) || '').trim();
      const bars = await page.getByTestId('wizard-product-histogram').locator('i').count();
      return {
        ok: summary.includes('库内约 33%')
          && summary.includes('越界约 67%')
          && summary.includes('缓存穿透路径')
          && bars === 28,
        detail: `${summary} bars=${bars}`,
      };
    });
    // Keep the started run inside the seeded id range: the penetration preset is
    // meant for cache-penetration drills and crosses the k6 flow-error threshold.
    await page.getByTestId('wizard-product-preset').selectOption('hot');
    await expectCondition('wizard: the in-range preset rewrites the preview and removes the out-of-range ids', async () => {
      const summary = ((await page.getByTestId('wizard-product-summary').textContent()) || '').trim();
      return {
        ok: summary.includes('库内约 100%')
          && summary.includes('本次没有越界 ID'),
        detail: summary.slice(0, 90),
      };
    });
    await page.getByTestId('wizard-product-distribution').selectOption('normal');
    await expectCondition('wizard: normal distribution rewrites the preview shape', async () => {
      const summary = ((await page.getByTestId('wizard-product-summary').textContent()) || '').trim();
      return { ok: summary.includes('正态分布下中间 ID 更热'), detail: summary.slice(0, 80) };
    });

    await page.getByTestId('wizard-step-5').click();
    await page.getByTestId('wizard-fault-add').click();
    await page.getByTestId('wizard-fault-add').click();
    await expectCondition('wizard: two fault rows are scheduled', async () => ({
      ok: (await page.locator('[data-testid^="wizard-fault-row-"]').count()) === 2,
    }));
    const manualRow = page.getByTestId('wizard-fault-row-0');
    const autoRow = page.getByTestId('wizard-fault-row-1');
    await manualRow.locator('select').nth(2).selectOption('manual');
    await expectCondition('wizard: a manual-hold row disables the TTL input', async () => {
      const manualTtl = manualRow.locator('input[type="number"]').nth(1);
      const autoTtl = autoRow.locator('input[type="number"]').nth(1);
      return {
        ok: (await manualTtl.isDisabled()) && !(await autoTtl.isDisabled()),
        detail: `manualDisabled=${await manualTtl.isDisabled()} autoDisabled=${await autoTtl.isDisabled()}`,
      };
    });
    await manualRow.locator('input[type="number"]').first().fill('60');
    await page.getByTestId('wizard-fault-remove-1').click();
    await expectCondition('wizard: removing a row keeps only the manual hold on the timeline', async () => {
      const rows = await page.locator('[data-testid^="wizard-fault-row-"]').count();
      const markers = await page.getByTestId('wizard-fault-preview').locator('.marker').count();
      const text = (await page.getByTestId('wizard-fault-preview').textContent()) || '';
      return {
        ok: rows === 1 && markers === 1 && text.includes('60s') && text.includes('app-1'),
        detail: `rows=${rows} markers=${markers}`,
      };
    });

    // Draft + pre-check. A WARN/PASS verdict may start; BLOCKED keeps the
    // documented disabled-start branch instead of forcing the run.
    const wizardName = uniqueRunName('wizard');
    await page.getByTestId('wizard-step-6').click();
    await page.getByTestId('wizard-name').fill(wizardName);
    await page.getByTestId('wizard-create').click();
    await expectText(
      page.getByTestId('wizard-run-status'),
      'DRAFT',
      'wizard: creating the draft renders it as DRAFT',
    );
    const wizardPrecheck = await waitForCondition(async () => {
      const block = page.getByTestId('wizard-precheck');
      if ((await block.count()) === 0) {
        return { ok: false, detail: 'precheck block missing' };
      }
      const text = (await block.textContent()) || '';
      const verdict = /verdict (PASS|WARN|BLOCKED)/.exec(text)?.[1] || '';
      const runId = /\brun-\d{8}-\d+\b/.exec(text)?.[0] || '';
      const items = await block.locator('table tbody tr').count();
      return {
        ok: Boolean(verdict) && Boolean(runId) && items >= 7,
        detail: `${verdict} ${runId} items=${items}`,
      };
    }, ACTION_TIMEOUT_MS * 2);
    check(
      'wizard: the draft pre-check verdict and evidence table are rendered',
      wizardPrecheck.ok,
      wizardPrecheck.detail,
    );
    const wizardVerdict = wizardPrecheck.detail.split(' ')[0] || '';
    const wizardRunId = /\brun-\d{8}-\d+\b/.exec(wizardPrecheck.detail)?.[0] || '';
    if (wizardRunId) ownedRunIds.add(wizardRunId);

    if (wizardVerdict && wizardVerdict !== 'BLOCKED' && wizardRunId) {
      await page.getByTestId('wizard-start').click();
      const navigated = await waitForCondition(async () => {
        const conflict = page.getByTestId('wizard-start-conflict');
        if ((await conflict.count()) > 0) {
          return { ok: false, detail: `start refused: ${(await conflict.textContent()).replace(/\s+/g, ' ').trim().slice(0, 160)}` };
        }
        const monitor = page.getByTestId('monitor-page');
        const visible = (await monitor.count()) > 0 && (await monitor.isVisible().catch(() => false));
        return { ok: visible, detail: visible ? 'monitor-page' : 'still on the wizard' };
      }, ACTION_TIMEOUT_MS * 2);
      check('wizard: starting the experiment jumps to 实时监控与操作', navigated.ok, navigated.detail);

      const progress = await waitForCondition(async () => {
        const idLoc = page.getByTestId('k6-run-id');
        if ((await idLoc.count()) === 0) {
          return { ok: false, detail: 'k6 progress shows no run yet' };
        }
        const text = ((await idLoc.textContent()) || '').trim();
        const bar = await page.getByTestId('k6-progress-bar').count();
        return { ok: text === wizardRunId && bar === 1, detail: `${text} bar=${bar}` };
      }, ACTION_TIMEOUT_MS * 2);
      check('wizard: the started run is RUNNING in the k6 progress block', progress.ok, progress.detail);

      // The k6 execution card and the run record live in 实验记录.
      await gotoSection(page, 'runs');
      await page.getByTestId('run-list-name-filter').fill(wizardName);
      const wizardRow = page.getByTestId(`run-row-${wizardRunId}`);
      const selected = await waitForCondition(async () => {
        if ((await wizardRow.count()) === 0) {
          return { ok: false, detail: 'created run not listed' };
        }
        await wizardRow.click();
        const idLoc = page.getByTestId('run-detail-run-id');
        if ((await idLoc.count()) === 0) return { ok: false, detail: 'detail not rendered' };
        const text = ((await idLoc.textContent()) || '').trim();
        return { ok: text === wizardRunId, detail: text };
      }, ACTION_TIMEOUT_MS * 2);
      check('wizard: the created run is selected in the ledger', selected.ok, selected.detail);

      await expectText(
        page.getByTestId('run-status-badge'),
        'RUNNING',
        'wizard: the started run is RUNNING in the ledger',
      );
      // The start action keeps the card busy while it refreshes detail, k6 and
      // the metrics window; stop stays disabled until that settles. Wait for it
      // before spending the run's 30s budget on the k6 assertions.
      await waitForCondition(async () => ({
        ok: await page.getByTestId('run-stop').isEnabled(),
        detail: `status=${((await page.getByTestId('run-status-badge').textContent()) || '').trim()}`,
      }), ACTION_TIMEOUT_MS * 2);
      const wizardK6 = await waitForCondition(async () => {
        const statusLoc = page.getByTestId('k6-status');
        if ((await statusLoc.count()) === 0) return { ok: false, detail: 'k6 card not rendered' };
        const status = ((await statusLoc.textContent()) || '').trim();
        const container = ((await page.getByTestId('k6-container').textContent()) || '').trim();
        return { ok: status === 'RUNNING' && container.startsWith('jl-k6-'), detail: `${status} ${container}` };
      }, ACTION_TIMEOUT_MS * 2);
      check('wizard: the k6 card reports a RUNNING jl-k6- container', wizardK6.ok, wizardK6.detail);

      const wizardMode = ((await page.getByTestId('k6-traffic-mode').textContent()) || '').trim();
      const wizardTarget = ((await page.getByTestId('k6-target-instance').textContent()) || '').trim();
      check(
        'wizard: the k6 card shows the via-nginx traffic attribution',
        wizardMode === 'via-nginx' && wizardTarget === 'nginx',
        `mode=${wizardMode} target=${wizardTarget}`,
      );

      const wizardRunName = ((await page.getByTestId('run-name').textContent()) || '').trim();
      const wizardNodes = ((await page.getByTestId('run-nodes').textContent()) || '').trim();
      const wizardParams = (await page.getByTestId('run-params').textContent()) || '';
      check(
        'wizard: ledger detail keeps the test name and the two enabled nodes',
        wizardRunName === wizardName
          && wizardNodes.includes('app-1')
          && wizardNodes.includes('app-2')
          && !wizardNodes.includes('app-3'),
        `name=${wizardRunName} nodes=${wizardNodes}`,
      );
      check(
        'wizard: ledger params keep the manual fault hold and drop the disabled node',
        wizardParams.includes('"recovery": "manual"')
          && wizardParams.includes('"ttlSeconds": null')
          && wizardParams.includes('"app-1"')
          && wizardParams.includes('"app-2"')
          && !wizardParams.includes('app-3'),
        wizardParams.replace(/\s+/g, ' ').slice(0, 200),
      );

      try {
        await page.getByTestId('k6-log-refresh').click({ timeout: 5000 });
      } catch {
        // the card is busy or the run moved on; the poller below decides
      }
      const wizardLog = await waitForCondition(async () => {
        const view = page.getByTestId('k6-log-view');
        if ((await view.count()) === 0) return { ok: false, detail: 'log view not rendered' };
        const text = ((await view.textContent()) || '').trim();
        return { ok: text.length > 0, detail: `${text.length} chars` };
      });
      check('wizard: the k6 log tail is rendered after refresh', wizardLog.ok, wizardLog.detail);

      const wizardReport = page.getByTestId('k6-report-download');
      check(
        'wizard: report download stays disabled while k6 runs',
        (await wizardReport.count()) > 0 && !(await wizardReport.isEnabled()),
      );

      const wizardTimeline = await waitForCondition(async () => {
        const steps = await page.locator('[data-testid^="run-step-"]').count();
        return { ok: steps >= 2, detail: `steps=${steps}` };
      });
      check('wizard: timeline records the DRAFT → RUNNING transitions', wizardTimeline.ok, wizardTimeline.detail);

      const wizardStopReady = await waitForCondition(async () => ({
        ok: await page.getByTestId('run-stop').isEnabled(),
        detail: `status=${((await page.getByTestId('run-status-badge').textContent()) || '').trim()}`,
      }), 5000);
      if (wizardStopReady.ok) {
        await page.getByTestId('run-stop').click();
      }
      const wizardStopped = wizardStopReady.ok
        ? await waitOperation(page)
        : { ok: false, detail: 'stop not offered (run already terminal)' };
      const wizardCompleted = await waitForCondition(async () => {
        const text = ((await page.getByTestId('run-status-badge').textContent()) || '').trim();
        return { ok: text === 'COMPLETED', detail: text };
      });
      const wizardK6Idle = await waitForCondition(async () => {
        const status = ((await page.getByTestId('k6-status').textContent()) || '').trim();
        return { ok: ['EXITED', 'ABSENT', '未启动/已清理'].includes(status), detail: status };
      });
      check(
        'wizard: stop completes the run and the k6 container leaves RUNNING',
        wizardStopped.ok && wizardCompleted.ok && wizardK6Idle.ok,
        `${wizardStopped.detail}; status=${wizardCompleted.detail}; k6=${wizardK6Idle.detail}`,
      );
      const wizardReportReady = await waitForCondition(async () => {
        const available = ((await page.getByTestId('k6-report-available').textContent()) || '').trim();
        const enabled = await wizardReport.isEnabled().catch(() => false);
        return { ok: available === '可用' && enabled, detail: `${available} enabled=${enabled}` };
      });
      check('wizard: the k6 report is downloadable once k6 exits', wizardReportReady.ok, wizardReportReady.detail);
      const wizardOps = await waitForCondition(async () => {
        const rows = await page.locator('[data-testid^="run-operation-row-"]').count();
        return { ok: rows >= 2, detail: `rows=${rows}` };
      });
      check('wizard: the operation ledger reflects start and stop', wizardOps.ok, wizardOps.detail);
    } else {
      check(
        'wizard: blocking pre-check keeps start disabled',
        !(await page.getByTestId('wizard-start').isEnabled()),
        wizardPrecheck.detail,
      );
      check(
        'wizard: blocking pre-check leaves the run in DRAFT',
        ((await page.getByTestId('wizard-run-status').textContent()) || '').trim() === 'DRAFT',
      );
    }

    // ------------------------------------------------------ wizard guide tab
    await gotoSection(page, 'wizard');
    await page.getByTestId('wizard-tab-guide').click();
    await expectCondition('wizard: the 故障演练 tab keeps the legacy scenario runner', async () => {
      const tab = page.getByTestId('wizard-tab-guide');
      const current = await tab.getAttribute('aria-current');
      return {
        ok: current === 'page' && (await page.getByTestId('scenario-select').count()) === 1,
        detail: `aria-current=${current}`,
      };
    });
    const select = page.getByTestId('scenario-select');
    const options = await select.locator('option').allTextContents();
    check('wizard: scenario list is loaded from the controller', options.length >= 1, options.join(' | '));
    await select.selectOption(SCENARIO_ID);
    check(
      'wizard: scenario description is rendered',
      (await page.locator('.scenario-desc').textContent()).length > 10,
    );

    const guideStatus = page.getByTestId('wizard-status');
    await page.getByTestId('scenario-start').click();
    await expectText(guideStatus, 'running', 'wizard: running after loading the scenario');
    check(
      'wizard: shows one pending step',
      (await page.getByTestId('step-list').locator('.step').count()) >= 1,
    );
    const explain = page.getByTestId('step-explain');
    check('wizard: per-step 现象/根因/处方/预期曲线 text is rendered', await explain.isVisible());
    const explainText = await explain.textContent();
    check(
      'wizard: explain block carries the four labels',
      ['现象', '根因', '处方', '预期曲线'].every((label) => explainText.includes(label)),
      explainText,
    );

    await expectCondition('wizard: pause is enabled while running', async () => ({
      ok: await page.getByTestId('action-pause').isEnabled(),
    }));
    check('wizard: resume is disabled while running', !(await page.getByTestId('action-resume').isEnabled()));

    await page.getByTestId('action-skip').click();
    await expectText(guideStatus, 'completed', 'wizard: skipping the only step completes the wizard');
    await expectCondition('wizard: reset stays available as the escape hatch', async () => ({
      ok: await page.getByTestId('action-reset').isEnabled(),
    }));
    check('wizard: step is disabled once completed', !(await page.getByTestId('action-step').isEnabled()));
    check('wizard: pause is disabled once completed', !(await page.getByTestId('action-pause').isEnabled()));

    await gotoSection(page, 'overview');
    const faultsAfterSkip = await page.getByTestId('fault-count').textContent();
    check('wizard: skipping injected no fault', faultsAfterSkip.trim() === '0', faultsAfterSkip);
    await gotoSection(page, 'wizard');
    await page.getByTestId('wizard-tab-guide').click();
    await page.getByTestId('action-reset').click();
    await expectText(guideStatus, 'idle', 'wizard: reset returns the wizard to idle');

    // ---------------------------------------------------------------- bench
    await gotoSection(page, 'bench');
    const trafficState = page.getByTestId('traffic-state');
    await expectCondition('bench: traffic container state is known', async () => {
      const text = ((await trafficState.textContent()) || '').trim();
      return { ok: ['created', 'exited', 'running', 'restarting', 'missing'].includes(text), detail: text };
    });
    await page.getByTestId('traffic-start').click();
    await expectText(trafficState, 'running', 'bench: background traffic start shows running');
    await page.getByTestId('traffic-stop').click();
    await expectText(trafficState, 'exited', 'bench: background traffic stop shows exited');

    await page.getByTestId('log-container').selectOption(LOG_CONTAINER);
    await page.getByTestId('log-refresh').click();
    await expectCondition('bench: log panel renders container output', async () => {
      const logs = ((await page.getByTestId('log-view').textContent()) || '').trim();
      return { ok: logs.length > 20, detail: `${logs.length} chars` };
    });
    check(
      'bench: stop-all is available as the global clear action',
      await page.getByTestId('stop-all').isEnabled(),
    );
    await page.getByTestId('stop-all').click();
    await expectCondition('bench: stop-all summary reports traffic/threads/k6 sub-results', async () => {
      const summary = page.getByTestId('stop-all-summary');
      if ((await summary.count()) === 0) {
        return { ok: false, detail: 'summary not rendered' };
      }
      const text = ((await summary.textContent()) || '').trim();
      return {
        ok: text.includes('traffic=') && text.includes('threads=') && text.includes('k6='),
        detail: text,
      };
    });

    const scenarioCard = page.getByTestId('bench-scenarios');
    await expectCondition('bench: scenario catalog card is visible', async () => ({
      ok: (await scenarioCard.count()) > 0,
    }));
    await expectText(
      page.getByTestId('bench-scenarios-state'),
      'OK',
      'bench: scenario catalog state is OK',
    );
    await expectCondition('bench: scenario catalog lists exactly smoke/load/spike', async () => {
      const ids = await page
        .locator('[data-testid^="bench-scenario-"]')
        .evaluateAll((nodes) => nodes.map((node) => node.dataset.testid));
      const expected = ['bench-scenario-smoke', 'bench-scenario-load', 'bench-scenario-spike'];
      return {
        ok: ids.length === expected.length && expected.every((id) => ids.includes(id)),
        detail: ids.join(','),
      };
    });
    check(
      'bench: stress/soak are absent from the scenario catalog',
      (await page.getByTestId('bench-scenario-stress').count()) === 0
        && (await page.getByTestId('bench-scenario-soak').count()) === 0,
    );

    // ------------------------------------------------------------ instances
    await gotoSection(page, 'instances');
    const cards = await waitForCondition(async () => {
      if ((await page.getByTestId('instances-page').count()) === 0) {
        return { ok: false, detail: 'instances page not rendered' };
      }
      const missing = [];
      for (const name of ['app-1', 'app-2', 'app-3']) {
        if ((await page.getByTestId(`instance-card-${name}`).count()) === 0) {
          missing.push(name);
        }
      }
      if (missing.length) {
        return { ok: false, detail: `missing cards ${missing.join(',')}` };
      }
      const cpu = ((await page.getByTestId('instance-cpu-app-1').textContent()) || '').trim();
      const rate = ((await page.getByTestId('instance-rate-app-1').textContent()) || '').trim();
      return { ok: cpu !== '无数据' && rate !== '无数据', detail: `cpu=${cpu} rate=${rate}` };
    });
    let opened = { ok: false, detail: 'instance card not clickable' };
    if (cards.ok) {
      await page.getByTestId('instance-open-thread-app-2').click();
      opened = await waitForCondition(async () => {
        if ((await page.getByTestId('thread-page').count()) === 0) {
          return { ok: false, detail: 'thread page not rendered' };
        }
        const value = await page.getByTestId('thread-instance').inputValue();
        return { ok: value === 'app-2', detail: `thread-instance=${value}` };
      });
    }
    check(
      'instances: three cards show live metrics and open the thread lab at app-2',
      cards.ok && opened.ok,
      `cards: ${cards.detail}; thread: ${opened.detail}`,
    );

    const demoStates = ['NEW', 'RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING', 'TERMINATED'];
    await expectCondition('instances: thread lab opens with the six-state display', async () => {
      const threadPage = page.getByTestId('thread-page');
      if ((await threadPage.count()) === 0) {
        return { ok: false, detail: 'thread page not rendered' };
      }
      const pageText = (await threadPage.textContent()) || '';
      const statePill = ((await page.getByTestId('thread-lifecycle-state').textContent()) || '').trim();
      const ok = demoStates.every((state) => pageText.includes(state))
        && demoStates.includes(statePill);
      return { ok, detail: `state=${statePill}` };
    });

    await page.getByTestId('thread-mode').selectOption('cpu-burn');
    await page.getByTestId('thread-hold').selectOption('auto');
    await page.getByTestId('thread-ttl').fill('5');
    await page.getByTestId('thread-inject').click();
    await expectCondition('instances: injected cpu-burn fault is listed as ACTIVE', async () => {
      const row = page.getByTestId('thread-fault-cpu-burn');
      if ((await row.count()) === 0) {
        return { ok: false, detail: 'not listed' };
      }
      const text = (await row.textContent()) || '';
      return { ok: text.includes('ACTIVE') && text.includes('auto'), detail: text.trim() };
    });

    await page.getByTestId('thread-stop-cpu-burn').click();
    await expectCondition('instances: stopping the fault empties the thread fault list', async () => ({
      ok: (await page.getByTestId('thread-fault-cpu-burn').count()) === 0,
    }));

    await expectCondition('instances: proxy reports no active thread fault after cleanup', async () => {
      const response = await fetch(`${API_BASE}/chaos/app/app-2/lab/thread/state`, {
        headers: { 'X-Lab-Token': LAB_TOKEN },
      });
      const leftover = await response.json();
      return {
        ok: Array.isArray(leftover.data) && leftover.data.length === 0,
        detail: JSON.stringify(leftover.data),
      };
    });

    // --------------------------------------------------------------- observe
    // The old 故障与缓存实验 page is now three tabs inside 实时监控与操作.
    await gotoSection(page, 'observe');
    await expectCondition('observe: the monitor page opens on the 监控 tab with three tabs', async () => {
      if ((await page.getByTestId('monitor-page').count()) === 0) {
        return { ok: false, detail: 'monitor-page not rendered' };
      }
      const ids = await page
        .locator('[data-testid^="monitor-tab-"]')
        .evaluateAll((nodes) => nodes.map((node) => node.dataset.testid));
      const expected = ['monitor-tab-monitor', 'monitor-tab-ops', 'monitor-tab-tools'];
      const current = await page.getByTestId('monitor-tab-monitor').getAttribute('aria-selected');
      return {
        ok: ids.length === 3 && expected.every((id, index) => ids[index] === id) && current === 'true',
        detail: `${ids.join(',')} monitor=${current}`,
      };
    });

    await expectCondition('observe: one KPI card per catalog key is rendered', async () => {
      const count = await page.locator('.metric-card').count();
      return { ok: count === KPI_COUNT, detail: `cards=${count} expected=${KPI_COUNT}` };
    });
    await expectCondition('observe: every KPI card carries a quality badge and update time', async () => {
      const cards = await page.locator('.metric-card').evaluateAll((nodes) => nodes.map((node) => {
        const badge = node.querySelector('[data-testid^="metric-quality-"]');
        const updated = node.querySelector('[data-testid^="metric-updated-"]');
        return {
          quality: badge ? badge.textContent.trim() : '',
          updated: updated ? updated.textContent.trim() : '',
        };
      }));
      const bad = cards.filter((card) => !QUALITY.includes(card.quality) || !card.updated.startsWith('更新'));
      return { ok: cards.length > 0 && bad.length === 0, detail: JSON.stringify(bad.slice(0, 3)) };
    });

    const heapQuality = ((await page.getByTestId('metric-quality-jvm.heap.used').textContent()) || '').trim();
    const heapValue = ((await page.getByTestId('metric-value-jvm.heap.used').textContent()) || '').trim();
    check(
      'observe: a wired KPI (heap) is OK with a real value',
      heapQuality === 'OK' && heapValue !== '无数据' && heapValue !== '0',
      `quality=${heapQuality} value=${heapValue}`,
    );

    // cache.l2.* is OK when the Redis exporter is scraped (dev stack) and
    // NO_DATA/COLLECTING otherwise; both are honest, a synthetic 0 is not.
    const l2Quality = ((await page.getByTestId('metric-quality-cache.l2.keys').textContent()) || '').trim();
    const l2Value = ((await page.getByTestId('metric-value-cache.l2.keys').textContent()) || '').trim();
    const l2Ok = l2Quality === 'OK'
      ? l2Value !== '无数据' && l2Value !== '0'
      : ['NO_DATA', 'COLLECTING'].includes(l2Quality) && l2Value === '无数据';
    check(
      'observe: cache.l2.keys follows the quality contract without a fake 0',
      l2Ok,
      `quality=${l2Quality} value=${l2Value}`,
    );

    const rejectedQuality = ((await page.getByTestId('metric-quality-executor.rejected').textContent()) || '').trim();
    const rejectedValue = ((await page.getByTestId('metric-value-executor.rejected').textContent()) || '').trim();
    check(
      'observe: unwired executor.rejected shows no data, never 0',
      rejectedQuality !== 'OK' && rejectedValue !== '0' && rejectedValue !== '0.00',
      `quality=${rejectedQuality} value=${rejectedValue}`,
    );

    await expectCondition('observe: the four monitor charts are rendered', async () => {
      if ((await page.getByTestId('monitor-timeline').count()) === 0) {
        return { ok: false, detail: 'monitor-timeline missing' };
      }
      const missing = [];
      for (const id of ['chart-requests', 'chart-k6', 'chart-jvm', 'chart-pool']) {
        if ((await page.getByTestId(id).count()) === 0) missing.push(id);
      }
      return { ok: missing.length === 0, detail: missing.join(',') };
    });
    await expectCondition('observe: the k6 progress block renders (无数据 is honest without a run)', async () => {
      const block = page.getByTestId('k6-progress');
      if ((await block.count()) === 0) return { ok: false, detail: 'k6-progress missing' };
      const empty = await page.getByTestId('k6-empty').count();
      const runIdLoc = page.getByTestId('k6-run-id');
      const runId = (await runIdLoc.count()) ? ((await runIdLoc.textContent()) || '').trim() : '';
      const bar = await page.getByTestId('k6-progress-bar').count();
      return {
        ok: empty === 1 || (Boolean(runId) && bar === 1),
        detail: `empty=${empty} run=${runId} bar=${bar}`,
      };
    });
    await expectCondition('observe: the instance table lists the three app rows', async () => {
      if ((await page.getByTestId('instance-metrics-table').count()) === 0) {
        return { ok: false, detail: 'instance table missing' };
      }
      const names = ['app-1', 'app-2', 'app-3'];
      const missing = [];
      for (const name of names) {
        if ((await page.locator(`[data-testid="instance-row-${name}"]:visible`).count()) === 0) {
          missing.push(name);
        }
      }
      return { ok: missing.length === 0, detail: missing.join(',') };
    });

    // No tolerance: /lab-api/containers includes the stopped one-shot and
    // on-demand containers (init jobs, jdk8, traffic) and the topology draws
    // every entry. 实时监控与操作 hides the ObservePanel's embedded copy, so only
    // the visible nodes are counted (the hidden duplicate stays in the DOM).
    await expectCondition('observe: topology draws one node per /lab-api/containers entry', async () => {
      const list = unwrap(await apiRequest('/containers')) || [];
      const nodes = await page.locator('[data-testid^="topology-node-"]:visible').count();
      return { ok: nodes === list.length, detail: `nodes=${nodes} api=${list.length}` };
    });

    // --------------------------------------------- observe → 实时操作 (ops)
    await page.getByTestId('monitor-tab-ops').click();
    await expectCondition('observe: the cache lab opens on the ops tab with a known mode and D10 link', async () => {
      if ((await page.getByTestId('cache-page').count()) === 0) {
        return { ok: false, detail: 'cache page not rendered' };
      }
      const instanceValue = await page.getByTestId('cache-instance').inputValue();
      const mode = ((await page.getByTestId('cache-mode').textContent()) || '').trim();
      const strategy = (await page.getByTestId('cache-strategy').textContent()) || '';
      const link = page.getByTestId('cache-grafana-link');
      const href = (await link.getAttribute('href')) || '';
      const target = await link.getAttribute('target');
      const ok = instanceValue === 'app-1'
        && CACHE_MODES.includes(mode)
        && strategy.includes('defense.mode')
        && strategy.includes('击穿防护')
        && href.includes('/d/ha-d10')
        && target === '_blank';
      return {
        ok,
        detail: `instance=${instanceValue} mode=${mode} href=${href} target=${target}`,
      };
    });

    await page.getByTestId('cache-mode-select').selectOption('null-cache');
    await page.getByTestId('cache-mode-apply').click();
    await expectText(
      page.getByTestId('cache-mode'),
      'null-cache',
      'observe: cache mode switches to null-cache through the proxy',
    );

    await page.getByTestId('cache-mode-select').selectOption('off');
    await page.getByTestId('cache-mode-apply').click();
    await expectText(
      page.getByTestId('cache-mode'),
      'off',
      'observe: cache mode switches back to off through the proxy',
    );

    await page.getByTestId('cache-seed').click();
    await expectCondition('observe: cache seed returns an id range and row count', async () => {
      const result = page.getByTestId('cache-seed-result');
      if ((await result.count()) === 0) {
        return { ok: false, detail: 'seed result not rendered' };
      }
      const range = ((await page.getByTestId('cache-seed-range').textContent()) || '').trim();
      const rows = ((await page.getByTestId('cache-seed-rows').textContent()) || '').trim();
      const enabled = await page.getByTestId('cache-seed').isEnabled();
      const ok = /^ids \d+–\d+$/.test(range) && /^rows \d+$/.test(rows) && enabled;
      return { ok, detail: `${range}; ${rows}; buttonEnabled=${enabled}` };
    }, ACTION_TIMEOUT_MS * 2);

    // B3-01: the L1 switch is independent of the eight defence modes; off
    // clears the instance's entries and the hit ratio reads "不适用".
    await expectText(page.getByTestId('cache-l1-state'), '启用', 'ops: L1 starts enabled');
    await page.getByTestId('cache-l1-toggle').click();
    await expectText(
      page.getByTestId('cache-l1-state'),
      '已关闭',
      'ops: the L1 switch clears the local entries and marks the instance off',
    );
    await page.getByTestId('cache-l1-toggle').click();
    await expectText(
      page.getByTestId('cache-l1-state'),
      '启用',
      'ops: the L1 switch turns back on for the remaining checks',
    );

    await page.getByTestId('cache-preheat-run').click();
    await expectCondition('observe: cache preheat reports the manual run it just did', async () => {
      const last = page.getByTestId('cache-preheat-last');
      if ((await last.count()) === 0) {
        return { ok: false, detail: 'last run not rendered' };
      }
      const text = (await last.textContent()) || '';
      return {
        ok: text.includes('manual') && text.includes('requested'),
        detail: text.trim(),
      };
    });

    // Park the legacy runner on a pending step and pause it: a paused guide
    // freezes the TTL of every tracked action, which is what the badge asserts.
    await gotoSection(page, 'wizard');
    await page.getByTestId('wizard-tab-guide').click();
    const pauseStatus = page.getByTestId('wizard-status');
    await page.getByTestId('scenario-select').selectOption(SCENARIO_ID);
    await page.getByTestId('scenario-start').click();
    const pauseReady = await waitForCondition(async () => ({
      ok: ((await pauseStatus.textContent()) || '').trim() === 'running'
        && (await page.getByTestId('action-pause').isEnabled()),
      detail: ((await pauseStatus.textContent()) || '').trim(),
    }));
    if (pauseReady.ok) {
      await page.getByTestId('action-pause').click();
      await waitForCondition(async () => ({
        ok: ((await pauseStatus.textContent()) || '').trim() === 'paused',
        detail: ((await pauseStatus.textContent()) || '').trim(),
      }));
    }
    await gotoSection(page, 'observe');
    await page.getByTestId('monitor-tab-ops').click();

    // One short container fault proves the badge wiring. freeze is a paused
    // JVM (harmless, auto-resumed by TTL even if stop-all were lost); stop-all
    // is the documented clear path and also restores app-2's health.
    let faultOk = false;
    let faultDetail = 'not injected';
    try {
      await apiPost('/chaos/instances/jl-app-2/actions', {
        action: 'freeze',
        ttlSeconds: FAULT_TTL_SECONDS,
      });
      // The action list is polled by the page's 5s environment sync.
      const pausedRow = await waitForCondition(async () => {
        const rows = page.locator('[data-testid^="chaos-fault-row-"]');
        if ((await rows.count()) === 0) {
          return { ok: false, detail: 'no active fault row yet' };
        }
        const row = rows.first();
        const remaining = ((await row.getByTestId('chaos-fault-remaining').textContent()) || '').trim();
        const paused = await row.getByTestId('chaos-fault-paused').count();
        return {
          ok: paused === 1 && remaining !== '' && remaining !== '—' && remaining !== '不过期/一次性',
          detail: `remaining=${remaining} paused=${paused}`,
        };
      }, ACTION_TIMEOUT_MS * 2);
      check(
        'observe: active action row shows remaining TTL and the frozen badge',
        pausedRow.ok,
        pausedRow.detail,
      );

      await page.getByTestId('monitor-tab-tools').click();
      // B3-02: the SCAN key browser lives in 实验工具 and lists the seeded keys.
      await expectCondition('tools: the cache key browser lists SCAN keys', async () => {
        if ((await page.getByTestId('cache-keys-panel').count()) === 0) {
          return { ok: false, detail: 'key panel not rendered' };
        }
        await page.getByTestId('cache-keys-load').click();
        return { ok: true, detail: 'load clicked' };
      });
      const keyRows = await waitForCondition(async () => {
        const rows = await page.locator('[data-testid^="cache-key-row-"]').count();
        return { ok: rows > 0, detail: `rows=${rows}` };
      });
      check('tools: key browser renders SCAN-paged rows', keyRows.ok, keyRows.detail);

      const appeared = await waitForCondition(async () => {
        const badge = page.locator('[data-testid="topology-faults-jl-app-2"]:visible').first();
        if ((await badge.count()) === 0) {
          return { ok: false, detail: 'badge not rendered yet' };
        }
        const text = ((await badge.textContent()) || '').trim();
        return { ok: text.includes('freeze'), detail: text };
      });
      await apiPost('/chaos/stop-all');
      // stop-all keeps the paused flag by design; reset returns the guide (and
      // the watchdog) to idle before the ledger section.
      await apiPost('/lab/guide/reset');
      await refreshAnimation(page);
      const cleared = await waitForCondition(async () => {
        const badge = await page.locator('[data-testid="topology-faults-jl-app-2"]:visible').count();
        const count = ((await page.getByTestId('animation-fault-count').textContent()) || '').trim();
        const actions = unwrap(await apiRequest('/chaos/actions')) || [];
        return {
          ok: badge === 0 && count === '0' && actions.length === 0,
          detail: `badge=${badge} count=${count} api=${actions.length}`,
        };
      });
      const healthy = await waitForCondition(async () => {
        const list = unwrap(await apiRequest('/containers')) || [];
        const apps = list.filter((item) => /^jl-app-\d+$/.test(item.name));
        const unhealthy = apps.filter((item) => item.state !== 'running' || item.health !== 'healthy');
        return {
          ok: apps.length === 3 && unhealthy.length === 0,
          detail: apps.map((item) => `${item.name}:${item.state}/${item.health}`).join(' '),
        };
      }, ACTION_TIMEOUT_MS * 2);
      faultOk = appeared.ok && cleared.ok && healthy.ok;
      faultDetail = `appeared ${appeared.detail}; cleared ${cleared.detail}; health ${healthy.detail}`;
    } catch (err) {
      faultDetail = err.message;
      // A failed injection must not leave the app frozen for the next demo.
      try {
        await apiPost('/chaos/stop-all');
      } catch {
        // the finally block retries
      }
    }
    check('observe: freeze badge appears on jl-app-2 and stop-all clears it', faultOk, faultDetail);

    // ------------------------------------------- observe → 实验工具 (tools)
    await expectCondition('observe: the 实验工具 tab opens the thread lab', async () => {
      if ((await page.getByTestId('thread-page').count()) === 0) {
        return { ok: false, detail: 'thread page not rendered' };
      }
      return { ok: true, detail: 'thread-page' };
    });
    await expectCondition('observe: the animation view opens on the live topology', async () => {
      if ((await page.getByTestId('animation-page').count()) === 0) {
        return { ok: false, detail: 'animation page not rendered' };
      }
      if ((await page.getByTestId('topology-page').count()) === 0) {
        return { ok: false, detail: 'topology tab not rendered' };
      }
      const current = await page.getByTestId('animation-tab-topology').getAttribute('aria-current');
      const nginx = await page.locator('[data-testid="topology-node-jl-nginx"]:visible').count();
      const app1 = await page.locator('[data-testid="topology-node-jl-app-1"]:visible').count();
      return {
        ok: current === 'page' && nginx > 0 && app1 > 0,
        detail: `aria-current=${current} nginx=${nginx} app-1=${app1}`,
      };
    });

    await page.getByTestId('animation-tab-cache').click();
    // The tab click starts a refresh that loads the three cache queries; wait
    // for it to finish so the empty state below is real and not first render.
    await waitForCondition(async () => ({
      ok: await page.getByTestId('animation-refresh').isEnabled(),
      detail: ((await page.getByTestId('animation-refresh').textContent()) || '').trim(),
    }));
    await expectCondition('observe: cache flow shows moving data or an explicit idle/empty state', async () => {
      if ((await page.getByTestId('cache-flow').count()) === 0) {
        return { ok: false, detail: 'cache-flow missing' };
      }
      if ((await page.getByTestId('animation-prom-note').count()) > 0) {
        return {
          ok: false,
          detail: ((await page.getByTestId('animation-prom-note').textContent()) || '').trim(),
        };
      }
      if ((await page.getByTestId('cache-empty').count()) > 0) {
        return { ok: true, detail: 'cache-empty (Prometheus returned no series)' };
      }
      const lanes = {};
      for (const id of ['l1', 'l2', 'db']) {
        const lane = page.getByTestId(`cache-lane-${id}`);
        if ((await lane.count()) === 0) {
          return { ok: false, detail: `lane ${id} missing` };
        }
        const cls = (await lane.getAttribute('class')) || '';
        const rate = ((await lane.locator('.lane-rate').textContent()) || '').trim();
        // A rendered lane must carry a formatted rate (or 无数据), never NaN/
        // undefined, and an idle lane must be idle because the rate is zero.
        if (rate !== '无数据' && !rate.endsWith('/s')) {
          return { ok: false, detail: `lane ${id} rate "${rate}"` };
        }
        if (cls.includes('idle') && rate !== '0 /s' && rate !== '无数据') {
          return { ok: false, detail: `lane ${id} idle with rate "${rate}"` };
        }
        lanes[id] = { cls, rate };
      }
      for (const id of ['l1', 'l2']) {
        const ratio = ((await page.getByTestId(`cache-hit-ratio-${id}`).textContent()) || '').trim();
        if (ratio !== '无数据' && !ratio.endsWith('%')) {
          return { ok: false, detail: `hit ratio ${id} "${ratio}"` };
        }
      }
      const summary = Object.entries(lanes)
        .map(([id, lane]) => `${id}:${lane.rate}${lane.cls.includes('idle') ? ' idle' : ''}`)
        .join(' ');
      if (lanes.l1.cls.includes('idle')) {
        return { ok: true, detail: `${summary} (explicit idle)` };
      }
      const particle = page.getByTestId('cache-lane-l1').locator('.flow-particle').first();
      const before = await particle.evaluate((el) => el.style.transform);
      await new Promise((resolve) => setTimeout(resolve, 400));
      const after = await particle.evaluate((el) => el.style.transform);
      return {
        ok: before !== after,
        detail: `${summary} · particles ${before} -> ${after}`,
      };
    });

    // Pausing only means something while something moves. Cache rates cover a
    // 1m window; the traffic start/stop above usually keeps them warm. If not,
    // generate read traffic through the controller instead of skipping.
    const l1Moving = async () => {
      const lane = page.getByTestId('cache-lane-l1');
      if ((await lane.count()) === 0) {
        return { ok: false, detail: 'lane missing' };
      }
      const cls = (await lane.getAttribute('class')) || '';
      if (cls.includes('idle')) {
        return { ok: false, detail: 'l1 lane idle' };
      }
      const particle = lane.locator('.flow-particle').first();
      const before = await particle.evaluate((el) => el.style.transform);
      await new Promise((resolve) => setTimeout(resolve, 400));
      const after = await particle.evaluate((el) => el.style.transform);
      return { ok: before !== after, detail: `${before} -> ${after}` };
    };
    let motion = await waitForCondition(l1Moving, 4000);
    let startedTraffic = false;
    if (!motion.ok) {
      try {
        await apiPost('/lab/traffic/start');
        startedTraffic = true;
      } catch (err) {
        console.log(`WARN  could not start background traffic for the pause test: ${err.message}`);
      }
      motion = await waitForCondition(l1Moving, MOTION_TIMEOUT_MS);
    }
    let pauseOk = false;
    let pauseDetail = `no moving particles to pause (${motion.detail})`;
    if (motion.ok) {
      await page.getByTestId('animation-toggle').click();
      const stateAtPause = await waitForCondition(async () => {
        const pressed = await page.getByTestId('animation-toggle').getAttribute('aria-pressed');
        const label = ((await page.getByTestId('cache-motion-state').textContent()) || '').trim();
        return {
          ok: pressed === 'true' && label === '动画暂停',
          detail: `aria-pressed=${pressed} state=${label}`,
        };
      }, 5000);
      const particle = page.getByTestId('cache-lane-l1').locator('.flow-particle').first();
      const stillSample = async () => {
        const before = await particle.evaluate((el) => el.style.transform);
        await new Promise((resolve) => setTimeout(resolve, 400));
        const after = await particle.evaluate((el) => el.style.transform);
        return { ok: before === after, detail: `${before} -> ${after}` };
      };
      // The rAF loop eases speed to zero over ~1s, so poll for the first
      // stable pair instead of assuming the freeze is instant.
      const frozen = await waitForCondition(stillSample, 8000);
      const syncBefore = ((await page.getByTestId('animation-last-sync').textContent()) || '').trim();
      const polled = await waitForCondition(async () => {
        const now = ((await page.getByTestId('animation-last-sync').textContent()) || '').trim();
        const pressed = await page.getByTestId('animation-toggle').getAttribute('aria-pressed');
        return {
          ok: now !== syncBefore && pressed === 'true',
          detail: `last-sync ${syncBefore} -> ${now}`,
        };
      }, 15000);
      const stillAfterPoll = polled.ok
        ? await waitForCondition(stillSample, 4000)
        : { ok: false, detail: 'poller did not advance' };
      pauseOk = stateAtPause.ok && frozen.ok && polled.ok && stillAfterPoll.ok;
      pauseDetail = `pause ${stateAtPause.detail}; frozen ${frozen.detail}; poll ${polled.detail}; after-poll ${stillAfterPoll.detail}`;
      try {
        await page.getByTestId('animation-toggle').click();
      } catch {
        // leave paused; the next page load starts from the stored preference
      }
    }
    if (startedTraffic) {
      try {
        await apiPost('/lab/traffic/stop');
      } catch (err) {
        console.log(`WARN  could not stop background traffic after the pause test: ${err.message}`);
      }
    }
    check('observe: pause freezes the particles while the 5s poller keeps syncing', pauseOk, pauseDetail);

    // Accessibility: a fresh context with reduced motion (no stored preference)
    // must start paused, and the visible button must still be able to override.
    const reducedContext = await browser.newContext({ reducedMotion: 'reduce' });
    const reducedPage = await reducedContext.newPage();
    trackPage(reducedPage);
    let reducedPaused = { ok: false, detail: 'not run' };
    let reducedPlay = { ok: false, detail: 'not run' };
    try {
      await reducedPage.goto(CONSOLE_URL, { waitUntil: 'load' });
      await reducedPage.getByTestId('token-input').fill(LAB_TOKEN);
      await reducedPage.getByTestId('connect').click();
      await reducedPage.getByTestId('nav-observe').click();
      await reducedPage.getByTestId('monitor-tab-tools').click();
      await reducedPage.getByTestId('animation-tab-cache').click();
      reducedPaused = await waitForCondition(async () => {
        if ((await reducedPage.getByTestId('cache-reduced-motion').count()) === 0) {
          return { ok: false, detail: 'cache-reduced-motion note missing' };
        }
        const label = ((await reducedPage.getByTestId('cache-motion-state').textContent()) || '').trim();
        const pressed = await reducedPage.getByTestId('animation-toggle').getAttribute('aria-pressed');
        return {
          ok: label === '动画暂停' && pressed === 'true',
          detail: `state=${label} aria-pressed=${pressed}`,
        };
      });
      if (reducedPaused.ok) {
        await reducedPage.getByTestId('animation-toggle').click();
        reducedPlay = await waitForCondition(async () => {
          const label = ((await reducedPage.getByTestId('cache-motion-state').textContent()) || '').trim();
          const pressed = await reducedPage.getByTestId('animation-toggle').getAttribute('aria-pressed');
          return {
            ok: label === '动画播放中' && pressed === 'false',
            detail: `state=${label} aria-pressed=${pressed}`,
          };
        }, 5000);
      }
    } finally {
      await reducedContext.close();
    }
    check(
      'observe: reduced motion starts paused and the play button overrides it',
      reducedPaused.ok && reducedPlay.ok,
      `paused: ${reducedPaused.detail}; play: ${reducedPlay.detail}`,
    );

    // ---------------------------------------------------------------- runs
    await gotoSection(page, 'runs');
    await expectCondition('runs: ledger list is visible', async () => {
      const list = page.getByTestId('run-list');
      if ((await list.count()) === 0) {
        return { ok: false, detail: 'run-list not rendered' };
      }
      const degraded = await page.getByTestId('ledger-degraded').count();
      return { ok: degraded === 0, detail: degraded ? 'ledger DEGRADED' : 'list rendered' };
    });
    check(
      'runs: name filter and archived toggle are offered',
      (await page.getByTestId('run-list-name-filter').count()) === 1
        && (await page.getByTestId('run-list-archived-toggle').count()) === 1,
    );

    const ledgerRunName = uniqueRunName('ledger');
    await page.getByTestId('run-create-name').fill(ledgerRunName);
    await page.getByTestId('run-create-scenario').fill(RUN_SCENARIO);
    await page.getByTestId('run-create-params').fill(RUN_PARAMS);
    await page.getByTestId('run-create-submit').click();
    const created = await waitForCondition(async () => {
      const result = page.getByTestId('run-create-result');
      if ((await result.count()) === 0) {
        const err = page.getByTestId('run-create-error');
        return { ok: false, detail: (await err.count()) ? ((await err.textContent()) || '').trim() : 'no result yet' };
      }
      const runId = ((await page.getByTestId('run-created-id').textContent()) || '').trim();
      return { ok: Boolean(runId), detail: runId };
    }, ACTION_TIMEOUT_MS * 2);
    check('runs: a uniquely named DRAFT run can be created from the page', created.ok, created.detail);
    const createdRunId = created.detail;
    if (createdRunId) ownedRunIds.add(createdRunId);

    await expectText(
      page.getByTestId('run-status-badge'),
      'DRAFT',
      'runs: the new run is shown as DRAFT in the detail view',
    );
    const detailId = await waitForCondition(async () => {
      const idLoc = page.getByTestId('run-detail-run-id');
      if ((await idLoc.count()) === 0) {
        return { ok: false, detail: 'detail not rendered' };
      }
      const text = ((await idLoc.textContent()) || '').trim();
      return { ok: text === createdRunId, detail: text };
    }, ACTION_TIMEOUT_MS * 2);
    check('runs: the created run is selected in the detail view', detailId.ok, detailId.detail);
    check(
      'runs: the detail view shows the test name',
      ((await page.getByTestId('run-name').textContent()) || '').trim() === ledgerRunName,
      await page.getByTestId('run-name').textContent(),
    );

    const precheckReady = await waitForCondition(async () => {
      const verdictLoc = page.getByTestId('run-precheck-verdict');
      if ((await verdictLoc.count()) === 0) {
        const err = page.getByTestId('run-precheck-error');
        return { ok: false, detail: (await err.count()) ? ((await err.textContent()) || '').trim() : 'verdict not rendered' };
      }
      const text = ((await verdictLoc.textContent()) || '').trim();
      return { ok: ['PASS', 'WARN', 'BLOCKED'].includes(text), detail: text };
    }, ACTION_TIMEOUT_MS * 2);
    check('runs: pre-check verdict is rendered with blocking/warning sections', precheckReady.ok, precheckReady.detail);
    const verdict = precheckReady.ok ? precheckReady.detail : '';
    const blocked = verdict === 'BLOCKED';

    if (!blocked) {
      await page.getByTestId('run-start').click();
      const started = await waitOperation(page);
      const running = await waitForCondition(async () => {
        const text = ((await page.getByTestId('run-status-badge').textContent()) || '').trim();
        return { ok: text === 'RUNNING', detail: text };
      });
      check(
        'runs: start returns an operation and moves the run to RUNNING',
        started.ok && running.ok,
        `${started.detail}; status=${running.detail}`,
      );
      // Wait out the start action's post-refresh (actionBusy keeps stop
      // disabled) before reading the k6 card, so the stop step below still has
      // most of the run's 30s budget left.
      await waitForCondition(async () => ({
        ok: await page.getByTestId('run-stop').isEnabled(),
        detail: `status=${((await page.getByTestId('run-status-badge').textContent()) || '').trim()}`,
      }), ACTION_TIMEOUT_MS * 2);

      // The start hook launches a real k6 container. ABSENT is a normal 200
      // ("not started/cleaned up") and must not be reported as an error.
      const k6Card = await waitForCondition(async () => {
        if ((await page.getByTestId('k6-run-card').count()) === 0) {
          return { ok: false, detail: 'k6 card not rendered' };
        }
        const statusLoc = page.getByTestId('k6-status');
        if ((await statusLoc.count()) === 0) {
          return { ok: false, detail: 'k6 status not rendered' };
        }
        const status = ((await statusLoc.textContent()) || '').trim();
        return { ok: status === 'RUNNING', detail: `status=${status}` };
      });
      check('runs: the k6 card reports the started container as RUNNING', k6Card.ok, k6Card.detail);

      const k6Container = ((await page.getByTestId('k6-container').textContent()) || '').trim();
      check(
        'runs: the k6 container name carries the jl-k6- prefix',
        k6Container.startsWith('jl-k6-'),
        k6Container,
      );

      const k6Mode = ((await page.getByTestId('k6-traffic-mode').textContent()) || '').trim();
      const k6Target = ((await page.getByTestId('k6-target-instance').textContent()) || '').trim();
      check(
        'runs: the k6 card shows the via-nginx traffic attribution',
        k6Mode === 'via-nginx' && k6Target === 'nginx',
        `mode=${k6Mode} target=${k6Target}`,
      );

      if (k6Card.ok) {
        try {
          await page.getByTestId('k6-log-refresh').click({ timeout: 5000 });
        } catch {
          // the card is busy or the run moved on; the poller below decides
        }
      }
      const k6Log = await waitForCondition(async () => {
        const view = page.getByTestId('k6-log-view');
        if ((await view.count()) === 0) {
          return { ok: false, detail: 'log view not rendered' };
        }
        const text = ((await view.textContent()) || '').trim();
        return { ok: text.length > 0, detail: `${text.length} chars` };
      });
      check('runs: the k6 log tail is rendered after refresh', k6Log.ok, k6Log.detail);

      const reportLoc = page.getByTestId('k6-report-download');
      const reportPresent = (await reportLoc.count()) > 0;
      const reportEnabled = reportPresent && (await reportLoc.isEnabled());
      check(
        'runs: report download stays disabled until k6 exits',
        reportPresent && !reportEnabled,
        `present=${reportPresent} enabled=${reportEnabled}`,
      );

      const timeline = await waitForCondition(async () => {
        const steps = await page.locator('[data-testid^="run-step-"]').count();
        return { ok: steps >= 2, detail: `steps=${steps}` };
      });
      check('runs: timeline records the DRAFT → RUNNING transitions', timeline.ok, timeline.detail);

      if (running.ok) {
        const stopReady = await waitForCondition(async () => ({
          ok: await page.getByTestId('run-stop').isEnabled(),
          detail: `status=${((await page.getByTestId('run-status-badge').textContent()) || '').trim()}`,
        }), 5000);
        if (stopReady.ok) {
          await page.getByTestId('run-stop').click();
        }
        const stopped = stopReady.ok
          ? await waitOperation(page)
          : { ok: false, detail: 'stop not offered (run already terminal)' };
        const completed = await waitForCondition(async () => {
          const text = ((await page.getByTestId('run-status-badge').textContent()) || '').trim();
          return { ok: text === 'COMPLETED', detail: text };
        });
        // The container is stopped with the run. ABSENT is the rendered label
        // for "not started/cleaned up"; either terminal value is acceptable.
        const k6Idle = await waitForCondition(async () => {
          const statusLoc = page.getByTestId('k6-status');
          if ((await statusLoc.count()) === 0) {
            return { ok: false, detail: 'k6 status not rendered' };
          }
          const status = ((await statusLoc.textContent()) || '').trim();
          return {
            ok: ['EXITED', 'ABSENT', '未启动/已清理'].includes(status),
            detail: status,
          };
        });
        check(
          'runs: stop completes the run and the k6 container leaves RUNNING',
          stopped.ok && completed.ok && k6Idle.ok,
          `${stopped.detail}; status=${completed.detail}; k6=${k6Idle.detail}`,
        );
        const reportReady = await waitForCondition(async () => {
          const available = ((await page.getByTestId('k6-report-available').textContent()) || '').trim();
          const enabled = await reportLoc.isEnabled().catch(() => false);
          return { ok: available === '可用' && enabled, detail: `${available} enabled=${enabled}` };
        });
        check('runs: the k6 report becomes downloadable after exit', reportReady.ok, reportReady.detail);

        // B3-04: the run-scoped business reconciliation returns the seven
        // invariant checks; DEGRADED is a valid answer when a probe is unwired.
        await page.getByTestId('reconcile-run').click();
        const reconcile = await waitForCondition(async () => {
          const verdict = ((await page.getByTestId('reconcile-verdict').textContent()) || '').trim();
          const items = await page.locator('[data-testid^="reconcile-item-"]').count();
          return {
            ok: ['PASS', 'DEGRADED', 'FAIL'].includes(verdict) && items === 7,
            detail: `verdict=${verdict} items=${items}`,
          };
        });
        check('runs: business reconciliation returns seven invariant checks',
          reconcile.ok, reconcile.detail);
      } else {
        check('runs: stop returns an operation and completes the run', false, 'start did not reach RUNNING');
      }

      const expectedOps = running.ok ? 2 : 1;
      const ops = await waitForCondition(async () => {
        const rows = await page.locator('[data-testid^="run-operation-row-"]').count();
        return { ok: rows >= expectedOps, detail: `rows=${rows} expected>=${expectedOps}` };
      });
      check('runs: operation ledger reflects the start/stop attempts', ops.ok, ops.detail);
    } else {
      // Blocking pre-check: the start button is disabled and the evidence is
      // rendered in the same shape the 409/40903 refusal returns.
      check(
        'runs: blocking pre-check keeps start disabled',
        !(await page.getByTestId('run-start').isEnabled()),
      );
      const evidence = await page.locator('[data-testid="run-precheck-blocking"] li code').count();
      const evidenceText = ((await page.locator('[data-testid="run-precheck-blocking"] li').first().textContent()) || '').trim();
      check(
        'runs: blocking pre-check lists the blocking item',
        evidence > 0 && evidenceText.length > 0,
        `items=${evidence} text=${evidenceText}`,
      );
      check(
        'runs: a blocked DRAFT keeps an explicit empty timeline',
        (await page.getByTestId('run-steps-empty').count()) > 0,
      );
      const stillDraft = ((await page.getByTestId('run-status-badge').textContent()) || '').trim() === 'DRAFT';
      const noOps = (await page.getByTestId('run-operations-empty').count()) > 0;
      check(
        'runs: a blocked pre-check leaves the DRAFT with no operation',
        stillDraft && noOps,
        `draft=${stillDraft} emptyOps=${noOps}`,
      );
    }

    const runMetrics = await waitForCondition(async () => {
      const count = await page.locator('.metric-card').count();
      const probe = page.getByTestId('metric-quality-executor.rejected');
      if (count !== RUN_METRIC_COUNT || (await probe.count()) === 0) {
        return { ok: false, detail: `cards=${count} expected=${RUN_METRIC_COUNT}` };
      }
      return { ok: true, detail: `cards=${count}` };
    }, ACTION_TIMEOUT_MS * 2);
    check('runs: run metrics reuse the quality-badge cards', runMetrics.ok, runMetrics.detail);
    const runRejectedQuality = ((await page.getByTestId('metric-quality-executor.rejected').textContent()) || '').trim();
    const runRejectedValue = ((await page.getByTestId('metric-value-executor.rejected').textContent()) || '').trim();
    check(
      'runs: unwired executor.rejected in run metrics is not rendered as 0',
      runRejectedQuality !== 'OK' && runRejectedValue !== '0' && runRejectedValue !== '0.00',
      `quality=${runRejectedQuality} value=${runRejectedValue}`,
    );

    // Refresh self-recovery: the runId lives in localStorage, so a full reload
    // must return to the same audit trail without re-creating anything.
    await page.reload({ waitUntil: 'load' });
    await page.getByTestId('token-input').fill(LAB_TOKEN);
    await page.getByTestId('connect').click();
    await gotoSection(page, 'runs');
    const restored = await waitForCondition(async () => {
      const idLoc = page.getByTestId('run-detail-run-id');
      if ((await idLoc.count()) === 0) {
        return { ok: false, detail: 'detail not restored' };
      }
      const text = ((await idLoc.textContent()) || '').trim();
      return { ok: text === createdRunId, detail: text };
    }, ACTION_TIMEOUT_MS * 2);
    check('runs: a browser reload restores the last selected run', restored.ok, restored.detail);

    // ------------------------------------------------------ report compare
    const compareA = page.getByTestId(`run-compare-toggle-${wizardRunId}`);
    const compareB = page.getByTestId(`run-compare-toggle-${createdRunId}`);
    const compareReady = (await compareA.count()) === 1 && (await compareB.count()) === 1;
    if (compareReady && wizardRunId) {
      await compareA.check();
      await compareB.check();
      await expectCondition('runs: the compare table lists both selected runs', async () => {
        const table = page.getByTestId('run-compare-table');
        if ((await table.count()) === 0) {
          return { ok: false, detail: 'compare table not rendered' };
        }
        const rows = await table.locator('tbody tr').count();
        const text = ((await page.getByTestId('run-compare').textContent()) || '').trim();
        return {
          ok: rows === RUN_METRIC_COUNT
            && text.includes(wizardRunId)
            && text.includes(createdRunId),
          detail: `rows=${rows}`,
        };
      }, ACTION_TIMEOUT_MS * 2);
    } else {
      check('runs: the compare table lists both selected runs', false, 'compare toggles not listed');
    }

    // --------------------------------------------------- archive / unarchive
    await page.getByTestId(`run-archive-${wizardRunId}`).click();
    await expectCondition('runs: archiving hides the run from the default list', async () => {
      const row = await page.getByTestId(`run-row-${wizardRunId}`).count();
      const chip = await page.getByTestId(`run-archived-chip-${wizardRunId}`).count();
      return { ok: row === 0 && chip === 0, detail: `row=${row} chip=${chip}` };
    });
    await page.getByTestId('run-list-archived-toggle').check();
    await expectCondition('runs: the archived toggle brings the run back with its chip', async () => {
      const row = page.getByTestId(`run-row-${wizardRunId}`);
      if ((await row.count()) === 0) {
        return { ok: false, detail: 'archived row not listed' };
      }
      const chip = ((await page.getByTestId(`run-archived-chip-${wizardRunId}`).textContent()) || '').trim();
      return { ok: chip === '已归档', detail: chip };
    });
    await page.getByTestId(`run-archive-${wizardRunId}`).click();
    await expectCondition('runs: unarchive clears the archived chip', async () => {
      const chip = ((await page.getByTestId(`run-archived-chip-${wizardRunId}`).textContent()) || '').trim();
      return { ok: chip === '未归档', detail: chip };
    });
    await page.getByTestId('run-list-archived-toggle').uncheck();
    await expectCondition('runs: the unarchived run stays in the default list', async () => ({
      ok: (await page.getByTestId(`run-row-${wizardRunId}`).count()) === 1,
    }));

    // ------------------------------------------------------- delete (modal)
    await page.getByTestId(`run-delete-${createdRunId}`).click();
    await expectCondition('runs: the delete action opens the confirmation modal', async () => ({
      ok: (await page.getByTestId('run-delete-modal').count()) === 1,
    }));
    await page.getByTestId('run-delete-cancel').click();
    await expectCondition('runs: cancelling the modal keeps the run', async () => ({
      ok: (await page.getByTestId('run-delete-modal').count()) === 0
        && (await page.getByTestId(`run-row-${createdRunId}`).count()) === 1,
    }));

    await page.getByTestId(`run-delete-${createdRunId}`).click();
    await page.getByTestId('run-delete-confirm').click();
    await expectCondition('runs: confirming the delete removes the row and clears the detail', async () => {
      const row = await page.getByTestId(`run-row-${createdRunId}`).count();
      const modal = await page.getByTestId('run-delete-modal').count();
      const detail = await page.getByTestId('run-detail').count();
      return { ok: row === 0 && modal === 0 && detail === 0, detail: `row=${row} modal=${modal} detail=${detail}` };
    }, ACTION_TIMEOUT_MS * 2);
    const goneStatus = await apiStatus(`/lab/runs/${encodeURIComponent(createdRunId)}`);
    if (goneStatus === 404) ownedRunIds.delete(createdRunId);
    check('runs: the deleted run detail returns 404', goneStatus === 404, `HTTP ${goneStatus}`);

    check(
      'no visible error banner',
      (await page.locator('[role="alert"]').count()) === 0,
      (await page.locator('[role="alert"]').allTextContents()).join(' | '),
    );
    check('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' | '));
    check('no failed /lab-api or /prom requests', failedRequests.length === 0, failedRequests.join(' | '));
  } finally {
    await restoreLabState(page);
    await browser.close();
  }
}

async function expectText(locator, expected, label = expected) {
  try {
    await locator.waitFor({ state: 'attached', timeout: ACTION_TIMEOUT_MS });
    const deadline = Date.now() + ACTION_TIMEOUT_MS;
    while (Date.now() < deadline) {
      const text = ((await locator.textContent()) || '').trim();
      if (text === expected) {
        check(label, true);
        return true;
      }
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
    check(label, false, `stayed "${((await locator.textContent()) || '').trim()}"`);
    return false;
  } catch (err) {
    check(label, false, err.message);
    return false;
  }
}

/** Poll an arbitrary in-page condition; the probe reports its last observed value. */
async function waitForCondition(probe, timeoutMs = ACTION_TIMEOUT_MS) {
  const deadline = Date.now() + timeoutMs;
  let detail = 'condition never ran';
  while (Date.now() < deadline) {
    try {
      const result = await probe();
      detail = result.detail ?? '';
      if (result.ok) {
        return { ok: true, detail };
      }
    } catch (err) {
      detail = err.message;
    }
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  return { ok: false, detail };
}

/** Same poller, but emits one PASS/FAIL line. */
async function expectCondition(label, probe, timeoutMs = ACTION_TIMEOUT_MS) {
  const result = await waitForCondition(probe, timeoutMs);
  check(label, result.ok, result.detail);
  return result.ok;
}

main()
  .then(() => {
    if (MIN_PASS_COUNT > 0 && passed < MIN_PASS_COUNT) {
      failures.push(`passes ${passed} < SMOKE_MIN_PASS_COUNT ${MIN_PASS_COUNT}`);
    }
    console.log(`\nconsole smoke: ${passed} PASS, ${failures.length} FAIL`);
    if (failures.length) {
      failures.forEach((item) => console.log(`  - ${item}`));
      process.exitCode = 1;
    }
  })
  .catch((error) => {
    console.error(`console smoke crashed: ${error.stack || error.message}`);
    process.exitCode = 1;
  });
