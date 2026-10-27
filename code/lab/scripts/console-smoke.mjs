/**
 * Task 23 console smoke test: drives the built 实验操作台 in a real browser.
 *
 * It walks the scenario wizard, toggles the background traffic container and
 * reads the log panel, asserting the button-availability contract the
 * chaos-controller documents (step/skip/pause only while running, resume only
 * while paused, reset always). The pending wizard step is skipped, so jl-app-3
 * stays up. The animation-view block injects one short `freeze` (TTL 10s, a
 * safety net only) on jl-app-2 to prove the fault badge, then calls stop-all
 * immediately; start and finally both call stop-all so no fault is ever left
 * behind and the app instances return to healthy.
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
// TTL is a safety net: the smoke clears the fault with stop-all as soon as the
// badge is asserted. Ten seconds leaves room for one manual sync plus a 5s poll
// without pausing jl-app-2 for long enough to affect its healthcheck.
const FAULT_TTL_SECONDS = 10;
// Waiting for cache traffic can take a scrape interval (5s) plus two samples
// for rate()[1m] to leave zero; reuse the existing polling budget, widened.
const MOTION_TIMEOUT_MS = Math.max(ACTION_TIMEOUT_MS * 2, 30000);

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

async function apiRequest(path, { method = 'GET', body } = {}) {
  const headers = { 'X-Lab-Token': LAB_TOKEN };
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

async function main() {
  const chromium = loadChromium();
  // A previous crashed run can leave the wizard RUNNING or traffic generating
  // load; both would make the assertions below meaningless.
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

    // --- Animation view: topology / instances / cache flow ----------------
    await page.getByTestId('nav-animation').click();
    await expectCondition('animation view opens on the live topology', async () => {
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
    await expectCondition('topology draws one node per /lab-api/containers entry', async () => {
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
    check('freeze badge appears on jl-app-2 and stop-all clears it', faultOk, faultDetail);

    await page.getByTestId('animation-tab-instances').click();
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
      if ((await page.getByTestId('instances-prom-note').count()) > 0) {
        return { ok: false, detail: 'prom note visible' };
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
      'three instance cards show live metrics and open thread lab at app-2',
      cards.ok && opened.ok,
      `cards: ${cards.detail}; thread: ${opened.detail}`,
    );

    await page.getByTestId('nav-animation').click();
    await page.getByTestId('animation-tab-cache').click();
    // The tab click starts a refresh that loads the three cache queries; wait
    // for it to finish so the empty state below is real and not first render.
    await waitForCondition(async () => ({
      ok: await page.getByTestId('animation-refresh').isEnabled(),
      detail: ((await page.getByTestId('animation-refresh').textContent()) || '').trim(),
    }));
    await expectCondition('cache flow shows moving data or an explicit idle/empty state', async () => {
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
    check('pause freezes the particles while the 5s poller keeps syncing', pauseOk, pauseDetail);

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
      await reducedPage.getByTestId('nav-animation').click();
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
      'reduced motion starts paused and the play button overrides it',
      reducedPaused.ok && reducedPlay.ok,
      `paused: ${reducedPaused.detail}; play: ${reducedPlay.detail}`,
    );

    check(
      'no in-page error banner',
      (await page.getByTestId('error-banner').count()) === 0
        && (await page.getByTestId('animation-error').count()) === 0,
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
