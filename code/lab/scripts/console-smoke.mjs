/**
 * Console smoke test for the side-navigation workspace (B1-05).
 *
 * The old four-tab single page is gone: this script walks the seven sections
 * (总览 / 场景向导 / 实例与 JVM / Benchmark / 实时监控 / 故障与缓存实验 / 实验记录)
 * and asserts the same capabilities on their new pages. The ledger block is new:
 * it creates a DRAFT run for the UI-launchable `load` scenario, reads its
 * pre-check, drives start/stop through the operation contract, checks the k6
 * execution card (state, container, log tail, report button), checks the metric
 * quality badges, reloads the page and proves the selected run is restored from
 * localStorage.
 *
 * A BLOCKED pre-check is a valid outcome (the controller refuses the start with
 * 409/40903); in that branch the script asserts the blocking evidence and the
 * disabled start button instead of forcing the run. The blocked branch keeps
 * the pre-existing four assertions; the expected !blocked branch adds four k6
 * assertions and folds the "container no longer RUNNING" condition into the
 * existing stop/COMPLETED check (67 -> 71).
 *
 * Cleanup: traffic stop, guide reset, chaos stop-all, thread stop-all, cache
 * mode off and any RUNNING/UNKNOWN run this tool created. Ownership is proven
 * by the legacy scenario prefix or by the request-param note marker
 * (`console-smoke`) on a UI-launchable scenario; user runs are left alone.
 *
 * Run through code/lab/scripts/console-smoke.sh (it resolves Playwright,
 * CONSOLE_URL and the lab token). Requires the stack running and the dashboard
 * built (`npm run build` in code/lab/dashboard).
 */
import process from 'node:process';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);

const CONSOLE_URL = process.env.CONSOLE_URL || 'http://127.0.0.1:8080/lab/';
const LAB_TOKEN = process.env.LAB_TOKEN || 'lab-token';
const API_BASE = process.env.LAB_API_BASE || 'http://127.0.0.1:8080/lab-api';
const SCENARIO_ID = process.env.SMOKE_SCENARIO_ID || 'quick-failover';
const LOG_CONTAINER = process.env.SMOKE_LOG_CONTAINER || 'jl-app-1';
const ACTION_TIMEOUT_MS = Number(process.env.SMOKE_ACTION_TIMEOUT_MS || 20000);
// The ledger now launches real k6 containers, and the start gate only accepts
// the manifest's views.ui whitelist (smoke/load/spike). A synthetic scenario
// name would be refused with 400/40002 and the run would go FAILED, so the
// default is a short `load`. LOAD_DURATION=30s keeps the run RUNNING while the
// k6 card/log/report assertions read the live container; the watchdog would
// otherwise complete it first. Every value stays env-overridable.
const RUN_SCENARIO_PREFIX = process.env.SMOKE_RUN_SCENARIO_PREFIX || 'console-smoke-';
const RUN_SCENARIO = process.env.SMOKE_RUN_SCENARIO || 'load';
const RUN_PARAMS = process.env.SMOKE_RUN_PARAMS
  || '{"note":"console-smoke","source":"playwright","LOAD_START_VUS":1,"LOAD_VUS":5,"LOAD_DURATION":"30s"}';
// Marker used by stopActiveRuns to close a leftover run this tool created even
// when its scenario is `load` (the legacy prefix no longer matches).
const SMOKE_RUN_MARKER = (() => {
  try {
    const parsed = JSON.parse(RUN_PARAMS);
    return typeof parsed?.note === 'string' ? parsed.note : '';
  } catch {
    return '';
  }
})();
// UI-launchable scenarios; refreshed from the catalog, with the manifest
// defaults as the offline fallback.
let UI_SCENARIOS = ['smoke', 'load', 'spike'];
// Defaults mirror VITE_LAB_KPI_KEYS / VITE_LAB_RUN_METRIC_KEYS (12 + 1 probe).
const KPI_COUNT = Number(process.env.SMOKE_KPI_COUNT || 13);
const RUN_METRIC_COUNT = Number(process.env.SMOKE_RUN_METRIC_COUNT || 13);
// TTL is a safety net: the smoke clears the fault with stop-all as soon as the
// badge is asserted. Ten seconds leaves room for one manual sync plus a 5s poll
// without pausing jl-app-2 for long enough to affect its healthcheck.
const FAULT_TTL_SECONDS = 10;
// Waiting for cache traffic can take a scrape interval (5s) plus two samples
// for rate()[1m] to leave zero; reuse the existing polling budget, widened.
const MOTION_TIMEOUT_MS = Math.max(ACTION_TIMEOUT_MS * 2, 30000);
// Public contract of CacheDefenseMode; the cache page renders exactly these keys.
const CACHE_MODES = [
  'off', 'null-cache', 'bloom', 'mutex',
  'logical-expire', 'ttl-jitter', 'l1-cache', 'db-fallback',
];
const SECTIONS = ['overview', 'wizard', 'instances', 'bench', 'observe', 'faults', 'runs'];
const QUALITY = ['OK', 'NO_DATA', 'COLLECTING', 'DEGRADED'];

let passed = 0;
const failures = [];

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

/** Controller writes wrap their payload in Result.data; /containers does too. */
function unwrap(payload) {
  return payload && typeof payload === 'object' && 'data' in payload ? payload.data : payload;
}

async function apiPost(path, body) {
  return apiRequest(path, { method: 'POST', body });
}

/**
 * A crashed smoke may still hold the write-run lock. Two ownership marks are
 * accepted: the legacy scenario prefix, and a run whose request params carry
 * this tool's note marker while its scenario is UI-launchable (the default is
 * now `load`, so the prefix alone cannot identify it). A user's active demo is
 * left alone.
 */
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

async function isSmokeOwnedRun(run) {
  if (String(run.scenario || '').startsWith(RUN_SCENARIO_PREFIX)) {
    return true;
  }
  if (!SMOKE_RUN_MARKER || !UI_SCENARIOS.includes(run.scenario)) {
    return false;
  }
  try {
    const detail = unwrap(await apiRequest(`/lab/runs/${encodeURIComponent(run.runId)}`));
    const request = (detail?.params || []).find((param) => param.group === 'request');
    return request?.payload?.note === SMOKE_RUN_MARKER;
  } catch (err) {
    console.log(`WARN  could not read params of run ${run.runId}: ${err.message}`);
    return false;
  }
}

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
  // A previous crashed run can leave the wizard RUNNING, traffic generating
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
      if (response.status() >= 400) {
        failedRequests.push(`${response.request().method()} ${url} -> HTTP ${response.status()}`);
      }
    });
  };
  trackPage(page);

  try {
    await page.goto(CONSOLE_URL, { waitUntil: 'load' });
    check('console page loads', /实验操作台/.test(await page.title()), await page.title());

    await page.getByTestId('token-input').fill(LAB_TOKEN);
    await page.getByTestId('connect').click();

    const navButtons = page.locator('.workspace-sidebar [data-testid^="nav-"]');
    const navIds = await navButtons.evaluateAll((nodes) => nodes.map((node) => node.dataset.testid));
    check(
      'sidebar exposes the seven workspace sections in order',
      navIds.length === SECTIONS.length && SECTIONS.every((id, index) => navIds[index] === `nav-${id}`),
      navIds.join(','),
    );

    const healthPill = page.locator('.topbar-meta .pill').first();
    await expectCondition('controller reports online', async () => {
      const text = ((await healthPill.textContent()) || '').trim();
      return { ok: text.includes('在线'), detail: text };
    });

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

    // --------------------------------------------------------------- wizard
    await gotoSection(page, 'wizard');
    const select = page.getByTestId('scenario-select');
    const options = await select.locator('option').allTextContents();
    check('wizard: scenario list is loaded from the controller', options.length >= 1, options.join(' | '));
    await select.selectOption(SCENARIO_ID);
    check(
      'wizard: scenario description is rendered',
      (await page.locator('.scenario-desc').textContent()).length > 10,
    );

    const wizardStatus = page.getByTestId('wizard-status');
    await page.getByTestId('scenario-start').click();
    await expectText(wizardStatus, 'running', 'wizard: running after loading the scenario');
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
    await expectText(wizardStatus, 'completed', 'wizard: skipping the only step completes the wizard');
    await expectCondition('wizard: reset stays available as the escape hatch', async () => ({
      ok: await page.getByTestId('action-reset').isEnabled(),
    }));
    check('wizard: step is disabled once completed', !(await page.getByTestId('action-step').isEnabled()));
    check('wizard: pause is disabled once completed', !(await page.getByTestId('action-pause').isEnabled()));

    await gotoSection(page, 'overview');
    const faultsAfterSkip = await page.getByTestId('fault-count').textContent();
    check('wizard: skipping injected no fault', faultsAfterSkip.trim() === '0', faultsAfterSkip);
    await gotoSection(page, 'wizard');
    await page.getByTestId('action-reset').click();
    await expectText(wizardStatus, 'idle', 'wizard: reset returns the wizard to idle');

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

    // B2-01b: the scenario catalog renders the manifest's views.ui only. The
    // card polls its own 5s sync tick through the same token/active props as
    // the other panels, so the first assertion waits for the settled state.
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
    let cards = await waitForCondition(async () => {
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

    const leftoverResponse = await fetch(`${API_BASE}/chaos/app/app-1/lab/thread/state`, {
      headers: { 'X-Lab-Token': LAB_TOKEN },
    });
    const leftover = await leftoverResponse.json();
    check(
      'instances: proxy reports no active thread fault after cleanup',
      Array.isArray(leftover.data) && leftover.data.length === 0,
      JSON.stringify(leftover.data),
    );

    // --------------------------------------------------------------- faults
    await gotoSection(page, 'faults');
    await expectCondition('faults: cache lab opens with a known mode and D10 link', async () => {
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
      'faults: cache mode switches to null-cache through the proxy',
    );

    await page.getByTestId('cache-mode-select').selectOption('off');
    await page.getByTestId('cache-mode-apply').click();
    await expectText(
      page.getByTestId('cache-mode'),
      'off',
      'faults: cache mode switches back to off through the proxy',
    );

    await page.getByTestId('cache-seed').click();
    await expectCondition('faults: cache seed returns an id range and row count', async () => {
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

    await page.getByTestId('cache-preheat-run').click();
    await expectCondition('faults: cache preheat reports the manual run it just did', async () => {
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

    await expectCondition('faults: animation view opens on the live topology', async () => {
      if ((await page.getByTestId('animation-page').count()) === 0) {
        return { ok: false, detail: 'animation page not rendered' };
      }
      if ((await page.getByTestId('topology-page').count()) === 0) {
        return { ok: false, detail: 'topology tab not rendered' };
      }
      const current = await page.getByTestId('animation-tab-topology').getAttribute('aria-current');
      const nginx = await page.getByTestId('topology-node-jl-nginx').isVisible().catch(() => false);
      const app1 = await page.getByTestId('topology-node-jl-app-1').isVisible().catch(() => false);
      return {
        ok: current === 'page' && nginx && app1,
        detail: `aria-current=${current} nginx=${nginx} app-1=${app1}`,
      };
    });

    // No tolerance: /lab-api/containers includes the stopped one-shot and
    // on-demand containers (init jobs, jdk8, traffic) and the topology draws
    // every entry, so both sides must agree exactly. The probe re-reads the API
    // each round, so a container transitioning between the two calls is retried
    // rather than excused.
    await expectCondition('faults: topology draws one node per /lab-api/containers entry', async () => {
      const list = unwrap(await apiRequest('/containers')) || [];
      const nodes = await page.locator('[data-testid^="topology-node-"]').count();
      return { ok: nodes === list.length, detail: `nodes=${nodes} api=${list.length}` };
    });

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
      await refreshAnimation(page);
      const appeared = await waitForCondition(async () => {
        const badge = page.getByTestId('topology-faults-jl-app-2');
        if ((await badge.count()) === 0) {
          return { ok: false, detail: 'badge not rendered yet' };
        }
        const text = ((await badge.textContent()) || '').trim();
        return { ok: text.includes('freeze'), detail: text };
      });
      await apiPost('/chaos/stop-all');
      await refreshAnimation(page);
      const cleared = await waitForCondition(async () => {
        const badge = await page.getByTestId('topology-faults-jl-app-2').count();
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
    check('faults: freeze badge appears on jl-app-2 and stop-all clears it', faultOk, faultDetail);

    await page.getByTestId('animation-tab-cache').click();
    // The tab click starts a refresh that loads the three cache queries; wait
    // for it to finish so the empty state below is real and not first render.
    await waitForCondition(async () => ({
      ok: await page.getByTestId('animation-refresh').isEnabled(),
      detail: ((await page.getByTestId('animation-refresh').textContent()) || '').trim(),
    }));
    await expectCondition('faults: cache flow shows moving data or an explicit idle/empty state', async () => {
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
    check('faults: pause freezes the particles while the 5s poller keeps syncing', pauseOk, pauseDetail);

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
      await reducedPage.getByTestId('nav-faults').click();
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
      'faults: reduced motion starts paused and the play button overrides it',
      reducedPaused.ok && reducedPlay.ok,
      `paused: ${reducedPaused.detail}; play: ${reducedPlay.detail}`,
    );

    // -------------------------------------------------------------- observe
    await gotoSection(page, 'observe');
    await expectCondition('observe: topology and KPI cards share the live workspace', async () => {
      if ((await page.getByTestId('topology-page').count()) === 0) {
        return { ok: false, detail: 'topology not rendered' };
      }
      const nginx = await page.getByTestId('topology-node-jl-nginx').isVisible().catch(() => false);
      const app1 = await page.getByTestId('topology-node-jl-app-1').isVisible().catch(() => false);
      return { ok: nginx && app1, detail: `nginx=${nginx} app-1=${app1}` };
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
    check('runs: a new DRAFT run can be created from the page', created.ok, created.detail);
    const createdRunId = created.detail;

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

    // A blocked environment keeps the pre-existing four assertions. The
    // expected (passing) environment takes the !blocked branch, which adds four
    // k6 assertions; that is why the documented baseline is 71.
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

      // The start hook launches a real k6 container. These assertions read the
      // k6 card while the 30s load is still RUNNING. ABSENT is a normal 200
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
        await page.getByTestId('run-stop').click();
        const stopped = await waitOperation(page);
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
