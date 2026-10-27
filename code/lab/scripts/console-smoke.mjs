/**
 * Task 23 console smoke test: drives the built 实验操作台 in a real browser.
 *
 * It walks the scenario wizard, toggles the background traffic container and
 * reads the log panel, asserting the button-availability contract the
 * chaos-controller documents (step/skip/pause only while running, resume only
 * while paused, reset always). No fault is ever injected: the pending step is
 * skipped, so jl-app-3 stays up and the shared stack is untouched.
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

async function apiPost(path) {
  const response = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'X-Lab-Token': LAB_TOKEN },
  });
  if (!response.ok) {
    throw new Error(`${path} -> HTTP ${response.status}`);
  }
  return response.json();
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
  // Thread faults are injected through the narrow proxy; stop-all is idempotent
  // and also clears a fault left behind by a crashed previous run.
  try {
    await apiPost('/chaos/app/app-1/lab/thread/stop-all');
  } catch (err) {
    console.log(`WARN  could not stop thread faults: ${err.message}`);
  }
  if (page && (await page.getByTestId('wizard-refresh').count()) > 0) {
    try {
      await page.getByTestId('wizard-refresh').click({ timeout: 5000 });
    } catch {
      // the page is already gone; the API cleanup above is what matters
    }
  }
}

async function main() {
  const chromium = loadChromium();
  // A previous crashed run can leave the wizard RUNNING or traffic generating
  // load; both would make the assertions below meaningless.
  await restoreLabState(null);
  const browser = await launchBrowser(chromium);
  const page = await browser.newPage();
  const pageErrors = [];
  const failedRequests = [];
  page.on('pageerror', (error) => pageErrors.push(String(error)));
  page.on('requestfailed', (request) => {
    if (request.url().includes('/lab-api/')) {
      failedRequests.push(`${request.method()} ${request.url()}`);
    }
  });

  try {
    await page.goto(CONSOLE_URL, { waitUntil: 'load' });
    check('console page loads', /实验操作台/.test(await page.title()), await page.title());

    await page.getByTestId('token-input').fill(LAB_TOKEN);
    await page.getByTestId('connect').click();

    const healthPill = page.locator('.topbar-meta .pill').first();
    await expectCondition('controller reports online', async () => {
      const text = ((await healthPill.textContent()) || '').trim();
      return { ok: text.includes('在线'), detail: text };
    });

    const status = page.getByTestId('guide-status');
    const trafficState = page.getByTestId('traffic-state');
    const rollup = page.getByTestId('container-rollup');
    await expectText(status, 'idle', 'guide starts idle');
    // The container list and the traffic state arrive in separate round trips,
    // and jl-traffic leaves the rollup only once its name is known.
    await expectCondition('every always-on container is running', async () => {
      const text = ((await rollup.textContent()) || '').trim();
      // "0/0" is the first render (containers not fetched yet) and also the
      // shape of a failed fetch whose banner was later cleared; up === total
      // alone would pass on it. Only a positive total can prove the rollup.
      const [up, total] = text.split('/').map((part) => Number(part));
      const ok = Number.isInteger(up) && Number.isInteger(total) && total > 0 && up === total;
      return { ok, detail: text };
    });
    check(
      'reset mode shows the manual default',
      (await page.getByTestId('reset-mode').textContent()).includes('manual'),
    );
    await expectCondition('traffic container state is known', async () => {
      const text = ((await trafficState.textContent()) || '').trim();
      return { ok: ['created', 'exited', 'running', 'restarting', 'missing'].includes(text), detail: text };
    });

    const select = page.getByTestId('scenario-select');
    const options = await select.locator('option').allTextContents();
    check('scenario list is loaded from the controller', options.length >= 1, options.join(' | '));
    await select.selectOption(SCENARIO_ID);
    check('scenario description is rendered',
      (await page.locator('.scenario-desc').textContent()).length > 10);

    await page.getByTestId('scenario-start').click();
    await expectText(status, 'running', 'wizard is running after loading the scenario');
    check('wizard shows one pending step',
      (await page.getByTestId('step-list').locator('.step').count()) >= 1);
    const explain = page.getByTestId('step-explain');
    check('per-step 现象/根因/处方/预期曲线 text is rendered', await explain.isVisible());
    const explainText = await explain.textContent();
    check('explain block carries the four labels',
      ['现象', '根因', '处方', '预期曲线'].every((label) => explainText.includes(label)), explainText);

    await expectCondition('pause is enabled while running', async () => ({
      ok: await page.getByTestId('action-pause').isEnabled(),
    }));
    check('resume is disabled while running', !(await page.getByTestId('action-resume').isEnabled()));

    await page.getByTestId('action-skip').click();
    await expectText(status, 'completed', 'skipping the only step completes the wizard');
    // reset is disabled only while a request is in flight, so waiting for it to
    // become enabled also proves the busy flag has cleared before the checks below.
    await expectCondition('reset stays available as the escape hatch', async () => ({
      ok: await page.getByTestId('action-reset').isEnabled(),
    }));
    check('step is disabled once completed', !(await page.getByTestId('action-step').isEnabled()));
    check('pause is disabled once completed', !(await page.getByTestId('action-pause').isEnabled()));
    const faultsAfterSkip = await page.getByTestId('fault-count').textContent();
    check('skipping injected no fault', faultsAfterSkip.trim() === '0', faultsAfterSkip);

    await page.getByTestId('action-reset').click();
    await expectText(status, 'idle', 'reset returns the wizard to idle');

    await page.getByTestId('traffic-start').click();
    await expectText(trafficState, 'running', 'background traffic start shows in the status bar');
    await page.getByTestId('traffic-stop').click();
    await expectText(trafficState, 'exited', 'background traffic stop shows in the status bar');

    await page.getByTestId('log-container').selectOption(LOG_CONTAINER);
    await page.getByTestId('log-refresh').click();
    await expectCondition('log panel renders container output', async () => {
      const logs = ((await page.getByTestId('log-view').textContent()) || '').trim();
      return { ok: logs.length > 20, detail: `${logs.length} chars` };
    });

    // --- Thread lab page (proxy + 5s poll) --------------------------------
    const demoStates = ['NEW', 'RUNNABLE', 'BLOCKED', 'WAITING', 'TIMED_WAITING', 'TERMINATED'];
    await page.getByTestId('nav-thread').click();
    await expectCondition('thread lab page opens with the six-state display', async () => {
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
    await expectCondition('injected cpu-burn fault is listed as ACTIVE', async () => {
      const row = page.getByTestId('thread-fault-cpu-burn');
      if ((await row.count()) === 0) {
        return { ok: false, detail: 'not listed' };
      }
      const text = (await row.textContent()) || '';
      return { ok: text.includes('ACTIVE') && text.includes('auto'), detail: text.trim() };
    });

    await page.getByTestId('thread-stop-cpu-burn').click();
    await expectCondition('stopping the fault empties the thread fault list', async () => ({
      ok: (await page.getByTestId('thread-fault-cpu-burn').count()) === 0,
    }));

    const leftoverResponse = await fetch(`${API_BASE}/chaos/app/app-1/lab/thread/state`, {
      headers: { 'X-Lab-Token': LAB_TOKEN },
    });
    const leftover = await leftoverResponse.json();
    check(
      'proxy reports no active thread fault after cleanup',
      Array.isArray(leftover.data) && leftover.data.length === 0,
      JSON.stringify(leftover.data),
    );

    check('no in-page error banner', (await page.getByTestId('error-banner').count()) === 0);
    check('no uncaught page errors', pageErrors.length === 0, pageErrors.join(' | '));
    check('no failed lab-api requests', failedRequests.length === 0, failedRequests.join(' | '));
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
async function expectCondition(label, probe) {
  const deadline = Date.now() + ACTION_TIMEOUT_MS;
  let detail = 'condition never ran';
  while (Date.now() < deadline) {
    try {
      const result = await probe();
      detail = result.detail ?? '';
      if (result.ok) {
        check(label, true, detail);
        return true;
      }
    } catch (err) {
      detail = err.message;
    }
    await new Promise((resolve) => setTimeout(resolve, 400));
  }
  check(label, false, detail);
  return false;
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
