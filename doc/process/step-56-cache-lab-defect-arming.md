# 步骤 56 · k6 cache-lab 造出缺陷本身（穿透探针 / 热点键 / 雪崩重 arm）+ 时间线预算修复

> 步骤交接 · 收口 step-48 §7.2 与 step-55 §5 第 1、2 条（「场景本身没造出缺陷，四族防护计数恒为 0」）
> 日期：2026-10-27
> 代码基线：`96939cf`（`feature/ha-lab`；特性提交 `be84c13`，预算修复 `96939cf`，前置 `3e7a9ed`）
> 交付：`code/lab/k6/scenarios/cache-lab.js`（+403 行造缺陷逻辑与两条 init 门）、`code/lab/k6/run.sh`（转发 8 个 `CACHE_LAB_*` 与新 CONFIG_VARS 行）、`code/lab/k6/test/cache-lab-scenario.test.mjs`（+424 行）、`code/lab/k6/test/stubs/k6-metrics.mjs`（Gauge 支持 `add()`）、`doc/避坑/17`、`doc/避坑/18`、本文档。
> 验证：Node 回归 **79 pass / 0 fail**；真机 armed 演练 `run_id=k6-20261027175755-3961`（`code/lab/k6/reports/20261027-175754-cache-lab-armed/`，目录被 `.gitignore` 排除）——bloom / null-cache / mutex / logical-expire 四族计数第一次真机出数（§3.2）；两处坑各有一条能复现的命令（§3.3、§3.4）。

## 1. 契约

### 1.1 为什么要改场景

step-48 §7.2 用真数据证伪了 README 里「对比 `off` 与各防护态」的说法：`cache-lab.js` 只读 `CACHE_LAB_SEED_START_ID..+COUNT-1` 里**存在且还活着**的 id，于是

- 没有 miss → 布隆没有 `absent` 可拒（真机 164307 run：`bloom_absents` 全 mode 恒 0）；
- 热点键从不变冷 → mutex 抢不到锁（`mutex_rebuilds` 恒 0）；
- `null_cache_hits` / `logical_*` / `refresh_submitted` **整条序列都不存在**（app 侧计数器只在第一次非零 increment 时才出现在 `/actuator/prometheus` 上）；
- 数据库从不被砸穿 → `limiter_rejections` 恒 0。

场景测的是防护**机制**，但从来没有**制造出防护要接住的那个故障**。实验室的定位就是「制造缺陷 → 观察症状 → 执行处理 → 对比效果」，缺的是第一步。

### 1.2 三类缺陷由场景自己造

| 缺陷 | 造法 | 期望被谁接住 | 计数 |
|---|---|---|---|
| 穿透 | 每 `CACHE_LAB_PROBE_EVERY`（默认 10）次读，改读 `CACHE_LAB_ABSENT_IDS`（默认 `99990..99999`）里的 id；flow `cache_probe`，期望 `404` + 业务码 `40401` | 空值缓存 / 布隆过滤器 | `cache_lab_probes`（Counter）+ `cache_lab_null_cache_hits` / `cache_lab_bloom_absents`（Gauge，dwell 差值） |
| 击穿 | 每 `CACHE_LAB_HOT_KEY_EVERY`（默认 25）次读 seed 响应里的 `hotKeyId`（`CACHE_LAB_HOT_KEY_ID` 可覆盖）；seed 的热键 TTL 300s 且读路径每次命中都续期，所以只有**被冻在 L2 之外**的模式才会真去重建 | mutex / logical-expire | `cache_lab_hot_key_reads` + `cache_lab_mutex_rebuilds` / `cache_lab_mutex_double_check_hits` / `cache_lab_logical_*` |
| 雪崩 | `CACHE_LAB_RESEED_MODES` 列出的 dwell 开头重打一次 `POST /lab/cache/seed`，200 个同 TTL（`CACHE_LAB_AVALANCHE_TTL_SECONDS`，默认 60s）的键在**本 dwell 内**集体过期 | ttl-jitter / l1-cache / db-fallback | `cache_lab_reseeds` + `cache_lab_limiter_rejections` / `cache_lab_db_fallbacks` |

探针 id 与读区间不重叠是硬约束：`parseAbsentIds()`（`code/lab/k6/scenarios/cache-lab.js:135`）在 id 落进 `[seedStartId, seedStartId+seedCount)` 时直接抛错，否则「穿透」会变成一次正常读，症状消失且无人报错。

### 1.3 两条 init 门（不是运行时才发现）

1. **arm 门**（`:326-342`）：`CACHE_LAB_RESEED_MODES` 非空时要求
   `CACHE_LAB_MODE_DURATION ≥ CACHE_LAB_AVALANCHE_TTL_SECONDS×1000 + CACHE_LAB_ARM_MARGIN`（默认 ≥ 75s）。
   否则重 arm 的同 TTL 键会在**下一个** dwell 才集体过期，把那个 dwell 的对比污染掉——正是 step-55 §1.3 text 面板写死的那个陷阱，现在由门挡住而不是写在面板上。
2. **margin 门**（`:326-334`）：`CACHE_LAB_ARM_MARGIN`（默认 15s）必须大于 `CACHE_LAB_STORM_SAMPLE_DELAY`（默认 2s），否则风暴采样点落在过期之前，采到的是「还没崩」的快照。

### 1.4 时间线预算必须由 dwell 推导（`96939cf`）

`modes` scenario 用 `per-vu-iterations`（1 VU × 1 iteration 跑完整个 `modeTimeline()` 循环），而 k6 给这个执行器的 `maxDuration` **默认 10m0s**。循环时长是 `modes.length × dwell`，dwell 是调用方调的，所以默认值必然在长 dwell 下把循环拦腰截断。

```js
// code/lab/k6/scenarios/cache-lab.js:354-360
const readDurationMs = modes.length * modeDuration.ms;
const readDuration = { raw: `${readDurationMs}ms`, ms: readDurationMs };
const timelineSlack = duration('CACHE_LAB_TIMELINE_SLACK', '30s');
const timelineMaxMs = readDurationMs + timelineSlack.ms;
// :441
maxDuration: `${timelineMaxMs}ms`,
```

读场景（`constant-vus`）的 `duration` 早就是推导值，只有 mode 预算漏了——不对称的根源是读场景从第一天就按 modes × dwell 算，而时间线场景当初只有一个短 dwell。slack 单独可配，因为 dwell 结束后还有 state/actuator 采样与末尾的 `mode=off` 复位。

### 1.5 代码对照

| 概念 | 符号 | `path:line` | 说明 |
|---|---|---|---|
| 探针 id 解析与重叠校验 | `parseAbsentIds` | `code/lab/k6/scenarios/cache-lab.js:135` | 非法/重复/越界 id 在 init 抛错 |
| reseed mode 白名单 | `parseReseedModes` | `code/lab/k6/scenarios/cache-lab.js:172` | 只允许 `CACHE_LAB_MODES` 的子集，重复即抛 |
| arm 门 | `requiredMs` 检查 | `code/lab/k6/scenarios/cache-lab.js:326` | dwell < TTL+margin 直接拒绝启动 |
| 时间线预算 | `timelineMaxMs` / `options.scenarios.modes.maxDuration` | `code/lab/k6/scenarios/cache-lab.js:360` / `:441` | `CACHE_LAB_TIMELINE_SLACK` 默认 30s |
| 本次迭代读哪个 | `iterationKind` | `code/lab/k6/scenarios/cache-lab.js:471` | `seed` / `probe` / `hot`，hot 只在 `hotKeyAvailable` 时排班 |
| 穿透请求 | `probeAbsentProduct` | `code/lab/k6/scenarios/cache-lab.js:963` | 期望 `404,429` + `40401`，记 `cache_lab_probes` |
| 风暴重 arm | `reseed` | `code/lab/k6/scenarios/cache-lab.js:823` | dwell 开头打 seed，记 `cache_lab_reseeds` |
| 时间线主体 | `modeTimeline` | `code/lab/k6/scenarios/cache-lab.js:846` | 每 dwell：切 mode → reseed → 风暴采样 → dwell 末采样；末尾复位 `off` |
| actuator 差值 | `sampleActuatorCounters` | `code/lab/k6/scenarios/cache-lab.js:722` | 差值 `.add()` 到 **Gauge**，见下方口径提醒 |
| 变量转发 | `IS_CACHE` 分支的 `CONFIG_VARS` | `code/lab/k6/run.sh:178-188` | 8 个新 `CACHE_LAB_*` 全部在此，缺一个就静默用默认 |
| 启动横幅 | `cache reseed:` / `cache probe:` | `code/lab/k6/run.sh:267-277` | 每次运行都会打印实际生效的造缺陷参数，是核对的第一现场 |

**口径提醒（踩过一次）**：防护计数是 **Gauge**，每个 dwell 用 `.add(delta)` 覆盖，所以 `summary.json` 里只剩**最后一个 dwell** 的值——`max` 才是跨 dwell 的真峰值。看 per-mode 对比必须查 Prometheus（`max_over_time(k6_cache_lab_bloom_absents[6h])`），不能读 summary。另：Prometheus 即时查询有 5m lookback，run 结束几小时后再查会返回**空序列而不是 0**，容易误判成「指标没接线」。

## 2. 必需环境

- 栈正常运行（`up.sh --profile dev`，16 服务 / 32 常驻容器口径见 `doc/design/安装与打包手册.md` §2）；`jl-app-1/2/3` 健康并已在 8080 上绑定。
- `LAB_TOKEN` 从 `code/lab/docker/.env` 读，不回显、不写进文档或历史。
- `CACHE_LAB_APP_URLS='http://app-1:8080,http://app-2:8080,http://app-3:8080'`——三实例逐个切换与采样，只写 `http://nginx` 会让 mode 只落在一侧。
- Prometheus 在 `jl-net` 内，宿主无端口；核对用 `docker exec jl-app-1 wget -qO- 'http://prometheus:9090/api/v1/query?...'`（**运行镜像没有 curl、没有 bash**，只有 BusyBox `wget`/`nc`）。
- 至少 `CACHE_LAB_AVALANCHE_TTL_SECONDS` 与 app 侧 seed TTL 一致：`setup()` 会比对 `/lab/cache/state` 报的 TTL，不一致时**先 WARN**（`:570-580`），因为 seed 端才是真实值。
- 与 `MYSQL_MODE=router` 无冲突：cache-lab 走读路径，standalone 即可；但 **`reset-lab.sh` 在 router 模式下会打错库**（step-48 §5.2）。

推荐的一次跑法（arm 全 8 个 mode、dwell 满足 arm 门）：

```bash
TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
LAB_TOKEN="$TOKEN" \
  CACHE_LAB_APP_URLS='http://app-1:8080,http://app-2:8080,http://app-3:8080' \
  CACHE_LAB_MODE_DURATION=90s \
  CACHE_LAB_RESEED_MODES='off,null-cache,bloom,mutex,logical-expire,ttl-jitter,l1-cache,db-fallback' \
  bash code/lab/k6/run.sh cache-lab
```

## 3. 验证

### 3.1 静态与回归

- `node --test code/lab/k6/test/*.test.mjs` → **79 pass / 0 fail**（`be84c13` 带 18 条造缺陷用例，`96939cf` 再带 2 条预算用例）。Windows 下必须用 glob 形式，`node --test <目录>` 会 `MODULE_NOT_FOUND`。
- 预算用例把契约钉死：默认 5s dwell 时 `maxDuration === '70000ms'`（8×5s+30s）；`CACHE_LAB_MODE_DURATION=90s` 时 `=== '750000ms'`（8×90s+30s）且严格大于 `readDurationMs`；`CACHE_LAB_TIMELINE_SLACK=45s` 时随之变大。
- `bash -n code/lab/k6/run.sh` → OK；`CACHE_LAB_TIMELINE_SLACK` 已进 `CONFIG_VARS`（`:188`），未进的场景不会把变量带进容器。

### 3.2 真机 armed 演练：四族防护首次出数

`run_id=k6-20261027175755-3961`（2026-10-27 17:57:55 起，dwell 90s，8 mode 全 arm）。
`max_over_time(...[6h])` 按 `mode` 展开，与改造前 run（`k6-20261027-164307-cache1`，dwell 30s、未 arm）逐项对照：

| mode | 关键计数（armed） | 改造前同一指标 | 判读 |
|---|---|---|---|
| `off` | `db_fallbacks=301`，四族防护全 0 | `db_fallbacks=1` | **基线**：不启用防护时同 TTL 键集体过期把 DB 打穿 301 次 |
| `null-cache` | `null_cache_hits=194` | 序列不存在 | 空值缓存接住了 194 次穿透探针 |
| `bloom` | `bloom_absents=243` | `bloom_absents=0`（全 mode） | 布隆拦下 243 次不存在 id；`null_cache_hits=2`、`bloom_absents` 在 mutex dwell 出现 2 —— dwell 交界的读串味，属预期 |
| `mutex` | `mutex_rebuilds=311`，`db_fallbacks=933` | `mutex_rebuilds=0` | 热点键重建 311 次；933 是重建期间并发读仍落 DB 的回落，mutex 不承诺挡住全部读 |
| `logical-expire` | `logical_cold_rebuilds=245`、`logical_stale_serves=61`、`refresh_submitted=62` | 三条序列都不存在 | 逻辑过期：物理键未过期时先给旧值（61）并异步提交刷新（62），过期后同步冷重建（245） |
| `ttl-jitter` | 防护计数≈0（仅 `logical_cold_rebuilds=1` 串味），`db_fallbacks=592` | 全 0 | **正确**：TTL 打散后没有集体过期，风暴不出现，所以无需防护出手 |
| `l1-cache` | `state_l2_keys=12`（其余 dwell 均 600-603）、`l1_hits=2686/3039` | `l2=603`、`l1_hits=1474` | 风暴采样点抓到 L2 只剩 12 个键——**这就是雪崩发生的那一瞬间**，同时 L1 命中率最高（0.88），L1 顶住了塌掉的 L2 |
| `db-fallback` | **本次没有序列** | `db_fallbacks=0`、`limiter_rejections=0` | 时间线在 dwell 7 之后被截断，见 §3.3；`limiter_rejections` 至今没有正向证据 |

其他同 run 的横切证据：`state_bloom_size=3066`（seed 重建的布隆含 3066 个 id）、`state_redis_reachable=3`（三实例全在线）、`reads count=32112` / `probes count=2793` / `hot_key_reads count=1390` / `vus_max=11`。

### 3.3 时间线截断：`exit 0` + `checks 100%` 也会少跑一个 dwell

同一次 run 的日志（`reports/20261027-175754-cache-lab-armed/run.log`）：

```
[cache-lab] t=+0s    mode=off            apps=3/3
[cache-lab] t=+90s   mode=null-cache     apps=3/3
[cache-lab] t=+180s  mode=bloom          apps=3/3
[cache-lab] t=+270s  mode=mutex          apps=3/3
[cache-lab] t=+360s  mode=logical-expire apps=3/3
[cache-lab] t=+450s  mode=ttl-jitter     apps=3/3
[cache-lab] t=+540s  mode=l1-cache       apps=3/3
（无 t=+630s db-fallback，也无 final reset mode=off）
```

`cache_lab_mode_switches count=7`、`cache_lab_reseeds count=7`，而 `off/null-cache/…/db-fallback` 是 8 个 mode + 末尾复位 = 应为 9。k6 的启动横幅里其实写了答案：

```
scenarios: (100.00%) 2 scenarios, 11 max VUs, 12m5s max duration (incl. graceful stop):
  * modes: 1 iterations for each of 1 VUs (maxDuration: 10m0s, exec: modeTimeline, gracefulStop: 5s)
  * read: 10 looping VUs for 12m0s (exec: readSeededProduct, gracefulStop: 5s)
```

12m 的时间线配 10m 的预算 → 第 8 个 dwell 的 arm 点（t=+630s）越界，`modeTimeline()` 被中断，**末尾的 `mode=off` 复位也一起没执行**（本次靠 `teardown()` 兜回 off）。退出码 0、`thresholds` 全过、summary 里 `✓ [100%]`——三个信号全都看不见截断。

已提炼为 `doc/避坑/17-k6-per-vu-iterations-max-duration.md`；三条自检：`grep -c '\[cache-lab\] t=+'` 是否等于 mode 数、`mode_switches == modes.length + 1`、最后一个 mode 的序列是否存在。

### 3.4 早一批：`K6_SUMMARY_EXPORT` 指到宿主路径导致 exit 107

同一天第一次 armed 尝试（`reports/20261027-175434-cache-lab-armed/k6.log`）根本没跑起来：

```
time="..." level=error msg="could not open 'summary.json': open summary.json: permission denied"
（容器工作目录 /scripts 是 :ro 挂载）
```

`run.sh` 用 `-v "$K6_MOUNT_DIR:/scripts:ro" -w /scripts`，所以任何落在 `/scripts` 下的写路径都会失败；`K6_SUMMARY_EXPORT` 必须是**容器内**路径且位于 `K6_REPORT_MOUNT`（默认 `/reports`）下。`3e7a9ed` 加了 init 期拦截，本次真机复跑确认消息逐字为：

```
ERROR: K6_SUMMARY_EXPORT is a path INSIDE the k6 container, not on the host: it must start with /reports/ (for example /reports/summary.json).
```

已提炼为 `doc/避坑/18-k6-summary-export-is-container-path.md`。顺带一条命名约定：`*_DIR` 是宿主路径、`*_MOUNT` 是容器内路径，`run.sh` 里成对出现（`K6_REPORT_DIR` / `K6_REPORT_MOUNT`）。

### 3.5 另一批：应用刚重启时开跑会 `HTTP 0`

第一次 armed 尝试还撞上 `jl-app-1` 在 `09:54:02.853Z` 重建（`RestartCount=0`，人工重建非自愈），`Started SeckillApplication in 33.881 seconds` 到 `09:54:44.406Z` 才绑上 8080，而 k6 `setup()` 在 `09:54:38Z` 就 POST 了 → `dial tcp 172.19.0.23:8080: connect: connection refused` → `returned HTTP 0`。`run.sh` **没有应用健康预检**，只打印配置。这一条记在 §5 第 3 项，尚未做成 `waitForReady()`（属新范围，不塞进本次文档整理）。

## 4. 结论

- step-48 §7.2 / step-55 §5 第 1 条的根因修好了：cache-lab 现在**自己造出**穿透、击穿、雪崩三类缺陷，并且每类都在自己的 dwell 里被对应防护接住——§3.2 的第一列全部由「恒 0 / 序列不存在」变成有值，「对比效果」这一步第一次有真数据可比。
- 两条 init 门把「dwell 太短会污染下一个 dwell」这类只有跑完 12 分钟才发现的错误提前到启动报错，配合 arm 门的**最小 75s** 约束，决定了推荐演示口径是 `CACHE_LAB_MODE_DURATION=90s`（完整 8 mode 时间线约 12 分钟，超出一讲 5 分钟窗口时用 `CACHE_LAB_MODES='off,bloom'` 之类子集，`parseModes` 要求保持规范顺序）。
- 时间线预算与 dwell 建立推导关系（`CACHE_LAB_TIMELINE_SLACK`），默认值不再是隐式契约；两条回归用例把它钉住。
- D10（step-55 的 114/115 面板）当初「本次 run 全 0，面板留待场景补造缺陷能力后直接出数」的注释现在兑现了：按 `run_id=k6-20261027175755-3961` 过滤即可看到 bloom/mutex/logical 三族在各自 dwell 抬升。

## 5. 仍未完成

1. **`db-fallback` dwell 与 `limiter_rejections` 仍无正向证据**。截断修好后需要复跑一次 §2 的命令，目标是抓到 `t=+630s mode=db-fallback`、`mode_switches=9`，以及 `k6_cache_lab_limiter_rejections{mode="db-fallback"}` > 0（需要 db-fallback 的 dwell 内真的发生 DB 风暴——`CACHE_LAB_RESEED_MODES` 必须包含 `db-fallback`，且 `DB_FALLBACK` 的 permits 要小于风暴并发，否则限流器不会拒）。
2. **`cache-lab` 造缺陷的 README 章节缺**：`code/lab/k6/README.md`「Cache experiment」只写了 `CACHE_LAB_MODE_DURATION` 等旧变量，`CACHE_LAB_ABSENT_IDS` / `_PROBE_EVERY` / `_PROBE_EXPECTED_STATUSES` / `_PROBE_EXPECTED_CODE` / `_HOT_KEY_EVERY` / `_HOT_KEY_ID` / `_RESEED_MODES` / `_AVALANCHE_TTL_SECONDS` / `_ARM_MARGIN` / `_STORM_SAMPLE_DELAY` / `_TIMELINE_SLACK` 全都没登记，指标表也缺 `cache_lab_probes` / `_hot_key_reads` / `_reseeds` / `_null_cache_hits` / `_logical_stale_serves` / `_logical_cold_rebuilds` / `_refresh_submitted` / `_mutex_double_check_hits`。本轮文档整理补。
3. **`run.sh` 无应用健康预检**（§3.5）：建议在 `IS_CACHE` 分支前对 `BASE_URL` 做一次 `waitForReady`，新 knob `CACHE_LAB_READY_TIMEOUT`（默认 90s）/ `CACHE_LAB_READY_POLL_MS`（默认 2000）。探针必须用裸 `http.request` + try/catch，不能复用 `labRequest()`/`envelope()`——它们带 `expectedStatuses(200)` 且非 200 抛错，会把预检变成中止。
4. **Gauge 差值口径**：`summary.json` 只留最后一个 dwell。可接受（Prometheus 才是 source of truth），但 D10 与 README 需要一句明确提醒，避免下一个人又去读 summary 判「指标没接线」。
5. **dwell 交界串味**：`bloom` dwell 的 `null_cache_hits=2`、`mutex` dwell 的 `bloom_absents=2`、`ttl-jitter` dwell 的 `logical_cold_rebuilds=1` 都来自上一个 dwell 遗留的在途读；差值窗口按采样时刻切，无法完全避免。演示讲解时按「量级」而非「精确归属」读。
6. **主会话动作**：复跑 §2 命令补 §5.1 → 更新 step-48 §7.2 指向本文档 → `verify.sh` / docclaims 复跑（本文档相关的 `k6-cache-lab-*` 断言若新增，需同步 `code/lab/docclaims/claims.yml`，注意该文件 git blob 是 **CRLF**，只能用字节级替换）。
