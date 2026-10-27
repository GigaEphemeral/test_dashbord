# 步骤交接 · Step 48 k6 HA / 缓存场景（`db-failover` + `cache-lab`）

> 日期：2026-10-27
> 分支 / HEAD：worktree `agent-general-purpose-67256333`，基线 `2980874`（`docs: close out the 2026-10-27 batch ...`）
> 关联任务：M3-2 MySQL Router 切换（step-31）、M4 缓存实验（step-25/26/27/28）、step-37 D10；本步只补 k6 场景、Node 测试与文档
> 边界：**只改 `code/lab/k6/**` 与本文档**；未新增/修改场景 JSON；原始交付未执行任何 Docker、k6 实跑、`verify.sh` / `smoke.sh` / `console-smoke.sh`（`db-failover` 的真机运行见第 6 节补充，2026-10-27）
> 验证：`node --test "code/lab/k6/test/*.test.mjs"` → **60 pass / 0 fail**（原始 59 = 基线 41 + 新增 18；第 6 节补 1 个超时契约）；`node --check` 覆盖全部新增/修改 JS；`bash -n code/lab/k6/run.sh` 通过；`db-failover` 真机两轮 + `verify.sh` **104 PASS / 1 WARN / 0 FAIL**
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 1. 契约

## 1.1 `scenarios/db-failover.js`

一个 k6 run 内并行三个 scenario：

| k6 scenario | executor | 默认 | 行为 |
|---|---|---|---|
| `read` | `constant-vus` | 20 VU × `DB_FAILOVER_DURATION=2m` | `ensureLogin()` + `GET /api/products/{PRODUCT_ID}`，经 Nginx |
| `write` | `constant-vus` | 1 VU × 2m，每 2s 一次 | `POST /api/orders`，每次新 `Idempotency-Key` |
| `chaos` | `per-vu-iterations` | 1 VU × 1 iteration | 睡到 `DB_FAILOVER_KILL_AT`，调 `POST /chaos/instances/{target}/actions` 执行 `kill` |

- kill 事件不是新 JSON，而是由 env 构造后走 **同一套 schema v1 校验**：`validateScenario()` 仍会检查 target pattern、datastore allow-list、TTL 上限和事件顺序。默认 target `jl-mysql-2`、TTL `45s`、kill at `20s`。
- `teardown` **无条件**调用 `POST /chaos/stop-all`（best effort），日志打印 `reverted=` / `failed=`；TTL 是第二道保险。
- 写请求的 `http.expectedStatuses` 只在一个时间窗内生效：`[DB_FAILOVER_KILL_AT, DB_FAILOVER_KILL_AT + DB_FAILOVER_ERROR_WINDOW]`（默认 20s–50s）。窗口外的同一 5xx 仍是真实 `flow_errors` / `server_errors`。
- 自定义指标：`db_failover_reads{result}`、`db_failover_writes{result}`、`db_failover_write_success`、`db_failover_expected_write_errors{status}`、`db_failover_write_failures{status}`。
- `http_req_failed{flow:product}` 必须为 **0**；写成功率为有界阈值。

## 1.2 `scenarios/cache-lab.js`

| 阶段 | 行为 |
|---|---|
| `setup()` | 对 `CACHE_LAB_APP_URLS[0]` 调一次 `POST /lab/cache/seed`（app 侧幂等），校验 seed 覆盖读取区间；再把所有 app URL 置为 `mode=off`；返回 `startedAt` 给 VU |
| `read` | 10 VU 按 `CACHE_LAB_MODE_DURATION` × mode 数的总时长，经 `BASE_URL`（Nginx）读 `CACHE_LAB_SEED_START_ID..+COUNT-1`（默认 90001+） |
| `modes` | 1 个 timeline VU 按 canonical 顺序切 `POST /lab/cache/mode`：`off → null-cache → bloom → mutex → logical-expire → ttl-jitter → l1-cache → db-fallback`，最后显式 reset `off` |
| `teardown()` | 再对每个 app URL reset `off`，时间线中途失败也不会留下启用的防护 |

- `CACHE_LAB_MODES` 是 canonical 列表的有序子集；重复、未知、乱序在 init 期直接报错（否则“上一模式 vs 下一模式”的对比会失真）。
- 每个 dwell 结束采样 `GET /lab/cache/state` 与 `/actuator/prometheus`，发出带 `mode` 标签的 gauge；所有指标同时带 `run_id`（`baseOptions.tags`），D10 可按 `run_id` + `mode` 对齐。
- `429` 视为 db-fallback 限流的预期快失败（`42901`），计数 `cache_lab_db_fallback_rejections`，不算读错误。
- 默认 `CACHE_LAB_APP_URLS` 只有 `http://app-1:8080`；要整层一致切换，显式传三个实例（见 README）。

## 1.3 `run.sh` / README

- `run.sh` 白名单新增 `db-failover`、`cache-lab`；`db-failover` 走 chaos 分支（挂载 `/chaos-scenarios`、转发 `LAB_TOKEN` / `CHAOS_*` / `DB_FAILOVER_*`），`cache-lab` 走新的 cache 分支（只转发 `LAB_TOKEN` 与 `CACHE_LAB_*`）。
- 修正 step-24d 记录的转发缺口：`CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` 现在默认在 chaos 分支转发，不再必须靠 `K6_FORWARD_ENV`。
- README 补充两个场景的用途、命令、env 表、阈值理由、指标表，并明确两者 **opt-in**。

## 1.4 Node 测试

零依赖（沿用 `test/k6-loader.mjs` + `test/stubs/`）：

- `test/db-failover-scenario.test.mjs`（8 个）：三 scenario 形状、kill 事件顺序/target/TTL、时间窗边界、阈值、`stop-all` teardown、唯一幂等键、窗口内外 5xx 的差异、窗口配置 fail-fast。
- `test/cache-lab-scenario.test.mjs`（10 个）：read/mode 形状、canonical 模式顺序、重复/未知/乱序拒绝、90001+ id 覆盖、按 elapsed 推导 mode 标签、读请求的 mode 标签与 429 计数、`off` teardown、setup 只 seed 一次并置 off、`run.sh` 白名单、Prometheus 文本解析。
- 测试桩扩展：`k6-metrics` 增加 `Gauge`；`k6-http` 增加 `http.post/get` 与纯文本 `body`，不影响既有 41 个用例。

# 2. 必需环境

两个场景都 **opt-in**：`db-failover` 会停 MySQL 节点，`cache-lab` 会写 1000 行实验数据并切缓存模式；不要与 bench 基线共用窗口。

| 变量 | 场景 | 必填/默认 | 说明 |
|---|---|---|---|
| `LAB_TOKEN` | 两者 | **必填** | `X-Lab-Token`；从 `code/lab/docker/.env` 读取，禁止打印/提交 |
| `BASE_URL` | 两者 | `http://nginx` | 业务读入口；k6 在 `jl-net` |
| `CHAOS_CONTROLLER_URL` | db | `http://chaos-controller:8081` | 控制器 base URL |
| `CHAOS_TARGET_PATTERN` | db | `^jl-app-[0-9]+$`（需改） | 必须放宽到能匹配目标 MySQL，例如 `^jl-mysql-[0-9]+$` |
| `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` | db | 空 = 全拒（需改） | 整名 allow-list，例如 `^jl-mysql-[0-9]+$` |
| `DB_FAILOVER_TARGET` | db | `jl-mysql-2` | 当前主节点需先用 `db-cluster-status.sh` 确认 |
| `DB_FAILOVER_TTL_SECONDS` | db | `45` | 自动 `start` 的 TTL，受 `CHAOS_MAX_TTL` 限制 |
| `DB_FAILOVER_KILL_AT` | db | `20s` | 从 load 起点到 kill 的偏移 |
| `DB_FAILOVER_ERROR_WINDOW` | db | `30s` | 允许写 5xx 的选举窗口；kill+window 必须落在 duration 内 |
| `DB_FAILOVER_DURATION` | db | `2m`（或 `CHAOS_DURATION`） | 总负载时长 |
| `DB_FAILOVER_READ_VUS` / `_WRITE_VUS` | db | `20` / `1` | 读写 VU 数 |
| `DB_FAILOVER_WRITE_INTERVAL` | db | `2s` | 每 VU 下单间隔 |
| `DB_FAILOVER_WRITE_EXPECTED_STATUSES` | db | `500,502,503,504` | 只在窗口内被 `http.expectedStatuses` 接受 |
| `DB_FAILOVER_WRITE_MIN_SUCCESS_RATE` | db | `0.85` | 写成功率阈值（0–1） |
| `CACHE_LAB_APP_URLS` | cache | `http://app-1:8080` | 内部 lab API base URL 列表；整层切换传三个实例 |
| `CACHE_LAB_SEED_START_ID` / `_COUNT` | cache | `90001` / `200` | 读取区间；必须在 `/lab/cache/seed` 的 90000..90999 内 |
| `CACHE_LAB_MODES` | cache | canonical 8 模式 | 有序子集，乱序/重复/未知直接失败 |
| `CACHE_LAB_MODE_DURATION` | cache | `20s` | 每个模式 dwell；读时长 = 模式数 × dwell |
| `CACHE_LAB_VUS` / `_THINK_TIME` | cache | `10` / `200ms` | 读压力 |
| `CACHE_LAB_READ_EXPECTED_STATUSES` | cache | `200,429` | 429 = db-fallback 限流 |
| `CACHE_LAB_SAMPLE_ACTUATOR` | cache | `1` | `0` 关闭 actuator 差值采样 |
| `CACHE_LAB_*_PATH` | cache | `/lab/cache/{seed,mode,state}`、`/actuator/prometheus` | 端点覆盖 |

`run.sh` 示例（token 从 `.env` 读取，不落盘）：

```bash
TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)

LAB_TOKEN="$TOKEN" \
  CHAOS_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  DB_FAILOVER_TARGET=jl-mysql-2 DB_FAILOVER_TTL_SECONDS=45 \
  DB_FAILOVER_KILL_AT=20s DB_FAILOVER_ERROR_WINDOW=30s \
  DB_FAILOVER_DURATION=2m \
  bash code/lab/k6/run.sh db-failover

LAB_TOKEN="$TOKEN" \
  CACHE_LAB_APP_URLS='http://app-1:8080,http://app-2:8080,http://app-3:8080' \
  CACHE_LAB_MODE_DURATION=20s \
  bash code/lab/k6/run.sh cache-lab
```

注意：`cache-lab` 的读流量走 Nginx，模式切换只作用于 `CACHE_LAB_APP_URLS` 里的实例；默认只切 app-1 时，约 1/3 的读会命中被切模式。要做整层实验必须传三个 URL。

# 3. 阈值理由

## 3.1 `db-failover`

- **读：`http_req_failed{flow:product} rate==0`**。Router 的目标就是读在选举期间保持可用；把读失败预算设成共享的 1% 会让“读也挂了”通过验收。Nginx 对 app 层仍是静态 upstream，但 MySQL 切主不应造成读 5xx，故 0 是正确契约。
- **写：`db_failover_write_success rate>0.85`**。默认 1 写 VU / 2s / 2m ≈ 60 次写；30s 选举窗最多损失约 15 次（25%）。85% 的成功率下限接受“选举窗内写失败”，但写在整个窗口后仍失败会跌破阈值。
- **窗口化 expectedStatuses**：若全程把 500/502/503/504 标成 expected，真实回归会被 `http_req_failed` 隐藏。实现只在 `killAt..killAt+errorWindow` 把 5xx 加入 `acceptStatuses`；窗口外同一状态仍进 `flow_errors` 和 `server_errors`。
- **自动回滚**：`kill` 带 TTL 且 teardown `stop-all`；即使 TTL 落在 duration 之后，teardown 也会先恢复。日志会 warn：
  `TTL ... ends after DB_FAILOVER_DURATION=...`。
- **已知副作用**：下单会扣 `PRODUCT_ID` 库存；连续演示前跑 `reset-lab.sh`。

## 3.2 `cache-lab`

- **读：`flow_errors rate<1%` + `http_req_failed{flow:cache_read} rate<1%`**。模式切换本身可能带来一次 L1/L2 抖动，但不允许持续失败。
- **429 不是错误**：db-fallback 的限流是设计内行为（`42901`），通过 `CACHE_LAB_READ_EXPECTED_STATUSES=200,429` 传给 `http.expectedStatuses`，并单独计 `cache_lab_db_fallback_rejections` 与 `cache_lab_reads{result="limited"}`。
- **按 mode 打标**：读的 `mode` 是 **时间表推导** 的，不是从 app 回读；切换瞬间前后可能短暂错标，时间线日志给出权威切换点。actuator/state 采样则在 dwell 结束时按实际切换的 mode 打标。
- **actuator 差值的边界**：`cache_lab_db_fallbacks` 等是每个 dwell 的增量（`max(0, current-previous)`），不是进程累计值；如果 actuator 不可达则跳过并打 warn，不伪造 0。`cache_lab_state_*` 来自 `/lab/cache/state`，不依赖 actuator。
- **opt-in 数据副作用**：seed 写 `catalog_product` 90000..90999、重建 Bloom、预热 L2 雪崩键与热点键；当前没有 `/lab/cache/reset`，清理仍是 SQL DELETE + Redis TTL/clear。

# 4. 一次 live run 应证明什么

## 4.1 `db-failover`

1. `chaos_events{action="kill",target=...,result="accepted"}` ≥ 1；k6 汇总里 `chaos_events` 有 accepted，无 failed。
2. `k6_http_req_failed_rate{run_id,flow="product"} = 0`；`k6_http_req_duration_p95{flow="product"}` 在阈值内；读在 kill 前后都 200。
3. 写：`k6_db_failover_write_success_rate > 0.85`；`k6_db_failover_expected_write_errors_total` 只在 kill..kill+window 内增长；`k6_db_failover_write_failures_total` 不增长（或只在窗口外暴露真实故障）。
4. D11 / `db-cluster-status.sh` 显示 Router 6446 从旧主切到新主，旧主 TTL 后 `ONLINE SECONDARY` 回组；`mysql_cluster_primary{member=...}` 发生翻转（step-31 的探针修复后可用）。
5. teardown 打印 `stop-all reverted/failed`，`GET /chaos/actions` 为空；旧主最终 running。

## 4.2 `cache-lab`

1. `setup()` 日志：seed `inserted`（首次 1000）与 `rowsInRange`；读取区间被 seed 覆盖。
2. `cache_lab_mode_switches{mode,result="accepted"}` 对每个模式 +1；最后 `mode=off`；`GET /lab/cache/mode` 回读为 `off`。
3. 每个 mode 的 `cache_lab_reads` 都有样本；`cache_lab_read_errors` 为 0；db-fallback 期间出现 `cache_lab_db_fallback_rejections` / `cache_lab_reads{result="limited"}`（是否出现取决于限流预算与并发）。
4. D10 上按 `run_id` 过滤：`cache_db_fallbacks_total{reason=...}`、L1/L2 命中、bloom、mutex、logical、ttl-jitter、l1-cache、limiter 的曲线与 k6 的 `cache_lab_*{mode}` 时间窗对齐；重点看 off 与各保护态的 DB 回落差异。
5. `/lab/cache/state` 的 `defense.mode`、`keys.l2ExperimentKeys`、`nullCache.liveMarkerKeys` 与 k6 state gauge 一致；teardown 后三实例都是 `off`。

# 5. 仍未完成 / 后续

## 5.1 两个 live run 都已完成

`db-failover` 见第 6 节，`cache-lab` 见第 7 节（结论：链路绿，但场景没有制造它要防的缺陷）。

## 5.2 其余条目

1. **D10 相关性未验收**：`cache_lab_*` gauge 依赖 `/actuator/prometheus` 与 `/lab/cache/state` 的真实序列；真机已确认 k6 侧 13 个 `k6_cache_lab_*` 序列带 `run_id`/`mode`/`result`/`scenario` 进了 Prometheus，但 **D10 只有 `instance` 变量、没有 `run_id`、也没有任何 k6 面板**，所以「按 run_id 对齐」仍未达成（见 §7.1 第 4 条）。
2. **模式切换是单实例 API**：要整层一致必须传 `CACHE_LAB_APP_URLS` 三个实例；不传时 1/3 流量命中被切模式，D10 的 `instance` 变量会显示差异，这是预期但容易误读。
3. **读的 mode 标签是时间表推导**：与 app 实际切换有毫秒级偏差；没有从响应头/state 回读每个请求的真实模式。
4. **actuator 采样的边界**：若 management endpoint 未暴露、返回 404 或 app 重启，差值会跳过或跨模式累积；`CACHE_LAB_SAMPLE_ACTUATOR=0` 可关闭，state gauge 不受影响。
5. **db-failover 目标需人工确认**：默认 `jl-mysql-2` 是 step-31 演练后的主节点，不是永远的主；重放前必须 `bash code/lab/scripts/db-cluster-status.sh`（或等价脚本）确认，否则会停副本、失去教学意义。（两次真机演练分别打到 `jl-mysql-2` 与 `jl-mysql-1`，主节点确实会变。）
6. **datastore 安全边界**：两个 gate 都必须由 operator 显式放宽；`jl-mysql`（无数字后缀）、`*-exporter`、`jl-mysql-10` 仍应被拒。`run.sh` 只是转发，不替代控制器的白名单。
7. **库存 / 数据清理**：db 写会扣库存，cache seed 会写 1000 行；当前没有自动 reset 集成到这两个场景。**router 模式下 `reset-lab.sh` 默认打的是 standalone `jl-mysql`**（`RESET_MYSQL_CONTAINER`），清不到集群里的订单/库存——真机演练时改用还有库存的 `PRODUCT_ID` 规避。
8. **未新增 JSON**：`db-failover` 用 env 合成事件，所以实验操作台的 `GET /lab/guide/scenarios` 不会自动出现该场景；若要在控制台逐步演示，需要另加 schema v1 JSON（本步未做，避免把目标写死）。

# 6. 补充 · `db-failover` 真机运行（2026-10-27）

> 执行环境：本机 Windows 10 + Docker，32 个 lab 容器（dev + ha + db-ha 同时开），
> app 层 `MYSQL_MODE=router`（写 6446 / 读 6447），演练前 `db-cluster-status.sh` → `RESULT: ok`。
> 演练后已还原 `MYSQL_MODE=standalone` 并 `nginx -s reload`，`verify.sh` 回到 **104 PASS / 1 WARN / 0 FAIL**。

## 6.1 第一次运行：暴露一个真缺陷（`CHAOS_HTTP_TIMEOUT` 太短）

归档 `code/lab/k6/reports/20261027-160742-db-failover/`，target `jl-mysql-2`（当时的 PRIMARY），
`DB_FAILOVER_DURATION=3m`，k6 退出码 **0**（阈值全过），但时间线报错：

```
08:08:04 [db-failover] t=+20.0s action=kill target=jl-mysql-2 ttl=45s
08:08:09 [db-failover] failed action=kill target=jl-mysql-2:
         POST /chaos/instances/jl-mysql-2/actions returned HTTP 0 (expected 200/202)
```

同一时刻容器确实被停了（`db-cluster-status.sh`：`jl-mysql-2 ... is not running`，
Router 6446 已指向 `mysql-1`）。根因：动作接口是**同步**的，要等 Docker stop 返回才回 202，
而 `chaos.stop-grace-seconds` 默认 10s、MySQL 刷 InnoDB 实测 **11s**；k6 侧
`CHAOS_HTTP_TIMEOUT` 默认只有 5s，于是「慢但成功」被读成失败，
`chaos_events{result="failed"}` 计 1、`server_errors` 计 1，不满足第 4.1 节第 1 条验收。

**修复**：`code/lab/k6/lib/chaos.js` 默认 `CHAOS_HTTP_TIMEOUT` 5s → **20s**（stop grace 10s + Docker 余量），
并在 `code/lab/k6/test/db-failover-scenario.test.mjs` 加回归：断言 kill 请求的 `params.timeout` 解析后 ≥ 15000ms。
Node 测试 **59 → 60 pass / 0 fail**。坑位成文 `doc/avoid_common_error/15-k6-action-timeout-vs-docker-stop.md`。

## 6.2 第二次运行：修复后的完整证据

`run_id=k6-20261027-161756-dbfo2`，归档 `code/lab/k6/reports/20261027-161756-db-failover/`，
target `jl-mysql-1`（当时的 PRIMARY），`PRODUCT_ID=2`（product 1 的库存已被第一次跑掉 83 件），
`DB_FAILOVER_TTL_SECONDS=45`、kill at 20s、error window 30s、duration 3m。k6 退出码 **0**，五个阈值全过。

对照第 4.1 节的五条验收：

| # | 验收项 | 实测 |
|---|---|---|
| 1 | `chaos_events{result=accepted}` ≥ 1，无 failed | `k6_chaos_events_total{action="kill",target="jl-mysql-1",result="accepted"} = 1`；日志 `08:18:18` 发出 → `08:18:29` 收到 **202**（stop 耗时 11s） |
| 2 | 读零失败、延迟在阈值内 | `http_req_failed{flow:product} = 0`（3580 次读）；p95 **8.30ms** / p99 **17.08ms** / max 504.8ms |
| 3 | 写成功率 > 0.85，窗口内 expected，窗口外无失败 | `db_failover_write_success = 0.9882`（84/85）；`db_failover_expected_write_errors{status="500"} = 1`，发生在 `08:18:25`（kill 后 7s，落在窗口内）；`db_failover_write_failures_total` **无序列**（从未 +1） |
| 4 | Router 切主 + 旧主 TTL 回队 + 指标跳变 | `mysql_cluster_member_count` 3 → **2**（`08:18:35`）→ **3**（`08:19:35`）；`mysql_cluster_primary{member}` 由 `mysql-1` 翻到 `mysql-2`（`08:18:35`）；Router 6446 → `mysql-2`；旧主 `ONLINE SECONDARY` 回组 |
| 5 | teardown stop-all，`/chaos/actions` 为空 | `teardown stop-all reverted=0 failed=0`（TTL 已先恢复）；`GET /lab-api/chaos/actions` → `data: []`；Nginx `/api/products/2` → 200 |

补充读数：`checks` 3686/3686（100%）、`flow_errors = 0`、`http_reqs = 3688`、
`k6_db_failover_reads_total{result="ok"} = 3580`（无 `result="error"` 序列）。
remote write 正常，上述 `k6_*` 序列都能在 Prometheus 按 `run_id` 查到（D9/D11 可对齐同一窗口）。

## 6.3 阈值预算的算术修正（文档与实现口径一致）

原文写「2m ≈ 60 次写，30s 窗口最多损失 ~15 次（25%），85% 下限接受该窗口」——**这句是错的**：
25% 损失对应 75% 成功率，低于 0.85。正确口径是：1 写 VU、间隔 2s，
窗口 `W` 秒约损失 `W/2` 次、总写约 `duration/2` 次，成功率 ≈ `1 - W/duration`，
所以 0.85 下限等价于 **`W ≤ 0.15 × duration`**：2m 只能容忍 ~18s 选举，3m 才能容忍 ~27s。
`DB_FAILOVER_ERROR_WINDOW=30s` 是「哪些 5xx 算预期」的**分类窗口**，不是预算。
实测本机晋升 ≤17s、只丢 1 次写，故两次运行都过；README 与场景表已按此改写，
并推荐演示用 `DB_FAILOVER_DURATION=3m`。

## 6.4 前置条件（README 已补，重放必看）

1. app 层必须 `MYSQL_MODE=router`（否则杀集群节点对请求路径毫无影响），重建后 **必须 `nginx -s reload`**（Nginx 缓存上游 IP）。
2. `DB_FAILOVER_TARGET` 必须是**当前** PRIMARY（`db-cluster-status.sh`），且不能是探针容器 `CHAOS_MYSQL_CLUSTER_CONTAINER`（默认 `jl-mysql-3`），否则 D11 在演练期间失去 `mysql_cluster_*` 序列。
3. 两个 gate（`CHAOS_TARGET_PATTERN` + `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`）都要显式放宽；控制器仍会独立校验。
4. 写会真实扣库存：3m ≈ 85 单。router 模式下 `reset-lab.sh` 默认打 standalone `jl-mysql`，清不到集群数据；换 `PRODUCT_ID` 或把 `RESET_MYSQL_CONTAINER` 指到集群节点。
5. 演练后把 `MYSQL_MODE` 还原 standalone 并重建 app 层 + reload Nginx，再跑 `verify.sh`（本次 **104 PASS / 1 WARN**）。

## 6.5 本次改动的文件

| 文件 | 改动 |
|---|---|
| `code/lab/k6/lib/chaos.js` | `CHAOS_HTTP_TIMEOUT` 默认 5s → 20s，注释说明必须大于服务端同步 stop 预算 |
| `code/lab/k6/test/db-failover-scenario.test.mjs` | 新增回归：kill 请求超时 ≥ 15s（`parseDurationMs` 解析后比较） |
| `code/lab/k6/README.md` | db-failover 前置条件、阈值预算算术修正、真机证据表、env 表（超时/窗口/时长/目标）与命令示例更新 |
| `doc/avoid_common_error/15-k6-action-timeout-vs-docker-stop.md` + `index.md` | 新坑成文并登记（15 条） |
| `doc/readme.md` | §4.3 补 k6 数据库切换压测入口；修正 D8 的过期陈述 |
| 本文档 | 第 5 节收口 + 第 6 节真机运行 |

**未改动**：控制器与 app 代码（缺陷在 k6 客户端超时，服务端同步语义是设计选择）；
`cache-lab` 场景代码本轮未改（其真机运行见第 7 节）。

# 7. 补充 · `cache-lab` 真机运行（2026-10-27）

> 执行环境：本机 Windows 10 + Docker，32 个 lab 容器；app 层 `MYSQL_MODE=standalone`
> （缓存实验与 MySQL 拓扑无关）；`CACHE_LAB_APP_URLS` 显式传三实例做**整层**切换。
> 命令：`LAB_TOKEN=$TOKEN CACHE_LAB_APP_URLS='http://app-1:8080,http://app-2:8080,http://app-3:8080'
> CACHE_LAB_MODE_DURATION=30s bash code/lab/k6/run.sh cache-lab`
> `run_id=k6-20261027-164307-cache1`，归档 `code/lab/k6/reports/20261027-164307-cache-lab/`（gitignored），
> k6 退出 **0**，两条阈值全绿（`flow_errors` 0.00%、`http_req_failed{flow:cache_read}` 0.00%）。

## 7.1 §4.2 验收逐条结果

| # | 期望 | 实测 |
|---|---|---|
| 1 | seed 覆盖读取区间 | `inserted=0 alreadyPresent=1000 rows=1000`（此前批次已 seed，幂等生效）；`reads [90001,90200]` |
| 2 | 每模式切换 +1，最后回 `off` | `cache_lab_mode_switches=9`（8 个 dwell + 1 次 final reset），每条日志 `apps=3/3`；teardown 后三实例 `GET /lab/cache/mode` 均为 `off` |
| 3 | 每 mode 有读样本、0 读错误 | 11618 reads / 11619 iterations（≈48 rps），`checks_succeeded=100%`，`http_req_failed{flow:cache_read}=0`；HTTP p95 9.36ms / p99 23.13ms / max 451.76ms。**`cache_lab_reads{result="limited"}` 与 `cache_lab_db_fallback_rejections` 全程为 0**：10 VU + 200ms think 打不满 db-fallback 的限流预算 |
| 4 | D10 按 `run_id` 对齐 | **未达成**。k6 侧 13 个 `k6_cache_lab_*` 序列确实进了 Prometheus，标签为 `run_id`/`mode`/`result`/`scenario`；但 D10 只有 `instance` 变量、没有 `run_id`、也没有任何 k6 面板，只能靠时间窗对齐（§7.3） |
| 5 | state gauge 与 `/lab/cache/state` 一致 | `state_redis_reachable=3`（三实例都通）、`state_bloom_size=3066`、`state_l1_keys 561..600`、`state_l2_keys 6..606` |

## 7.2 一个真缺陷：场景没有制造它要防的缺陷

按 dwell 取的 k6 actuator 差值 gauge 显示，八种模式里**只有雪崩被真正触发，而且是被 seed 的 60s TTL 意外触发的**：

| gauge（每 dwell 差值 / 采样值） | off | null-cache | bloom | mutex | logical-expire | ttl-jitter | l1-cache | db-fallback |
|---|---|---|---|---|---|---|---|---|
| `cache_lab_db_fallbacks` | 1 | 0 | **398** | 0 | 0 | 0 | 0 | 0 |
| `cache_lab_l1_hits` | 885 | 1437 | 1093 | 1433 | 1472 | 1470 | 1474 | 1369 |
| `cache_lab_l1_requests` | 1447 | 1467 | 1440 | 1468 | 1472 | 1471 | 1475 | 1372 |
| `cache_lab_bloom_absents` | 0 | 0 | **0** | 0 | 0 | 0 | 0 | 0 |
| `cache_lab_mutex_rebuilds` | 0 | 0 | 0 | **0** | 0 | 0 | 0 | 0 |
| `cache_lab_limiter_rejections` | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **0** |
| `cache_lab_state_l2_keys` | 606 | **6** | 603 | 603 | 603 | 603 | 603 | 603 |

三个成因，都能对上代码/配置：

1. **读集合里没有不存在的 id** → 穿透防护无事可做。`null-cache` 只为「查不到的 id」写标记，`bloom` 只拦截「不在过滤器里的 id」；场景只读 seed 出来的 90001..90200，于是 `bloom_absents=0`、`state_live_markers=0`。（`doc/design/Stage1/details/03-缓存体系.md` 的手工演练用的是不存在的 `99999`，所以能看到效果。）
2. **读集合里没有热点键** → 击穿防护无事可做。热点键是 `start-id + hot-key-index = 90999`（`LAB_CACHE_SEED_HOT_KEY_INDEX:999`，TTL 300s），与读取区间 90001..90200 不相交，所以 `mutex_rebuilds=0`、logical 的 stale-serve 也没有样本。
3. **雪崩是「意外」触发的，且落在错误的模式窗口**。seed 把 200 个键写成同一个 `avalanche-ttl-seconds=60`，seed 后第 60 秒集体过期——正好是本次 `bloom` dwell 的起点：`null-cache` dwell 结束时 L2 只剩 **6** 个键，随后 30s 出现 **398** 次 DB 回落，下一个采样 L2 又回到 603。这既是雪崩的真实症状，也说明 **dwell 与 seed TTL 不同步时，模式对比会被污染**。

结论：`cache-lab` 证明了「canonical 时间表 + 整层切换 + 按 mode 打标 + teardown 复位」这条链路（§7.1 的 1/2/3/5 全绿），
但**证伪了** README 里「重点看 off 与各保护态的 DB 回落差异」这句——off dwell 的 DB 回落是 1 次、bloom dwell 是 398 次，
差异来自 seed TTL 的时序而不是防护模式。后续必须给场景补「制造缺陷」的能力：
读不存在的 id（穿透）、读热点键并让它变冷（击穿）、按 dwell 重新 arm 同 TTL 键（雪崩）。

## 7.3 时间线证据（dwell 与切换点）

k6 日志（UTC）与 30s dwell 完全对齐，每次切换都是 `apps=3/3`：

| t | 模式 | 日志时间 |
|---|---|---|
| +0s | off | 08:43:11 |
| +30s | null-cache | 08:43:41 |
| +60s | bloom | 08:44:11 |
| +90s | mutex | 08:44:41 |
| +120s | logical-expire | 08:45:12 |
| +150s | ttl-jitter | 08:45:42 |
| +180s | l1-cache | 08:46:12 |
| +210s | db-fallback | 08:46:42 |
| +240s | final reset `off` + teardown ×3 | 08:47:12 |

复现查询（Prometheus 未发布宿主端口，走 Nginx 代理）：

```bash
curl -s 'http://127.0.0.1:8080/prom/api/v1/query_range' --data-urlencode \
  'query=sum by (mode) (k6_cache_lab_db_fallbacks{run_id="k6-20261027-164307-cache1"})' \
  --data-urlencode 'start=2026-10-27T08:43:00Z' --data-urlencode 'end=2026-10-27T08:47:40Z' \
  --data-urlencode 'step=30s'
```

## 7.4 本次改动的文件

| 文件 | 改动 |
|---|---|
| `doc/readme.md` | §4.1/§4.4 的 `docker exec jl-app-N sh -c 'curl ...'` 全部改成 `wget`：运行镜像是 `eclipse-temurin:*-alpine`，**只有 BusyBox wget、没有 curl**，照抄原命令会报 `sh: curl: not found`（本次实测）；§4.1 补 k6 `cache-lab` 入口与副作用说明 |
| 本文档 | 第 5 节收口 + 第 7 节真机运行与证伪结论 |

**未改动**：`scenarios/cache-lab.js` 与 compose —— §7.2 的三个成因需要改场景与
`LAB_CACHE_SEED_*` 的 compose 透传（当前 `x-app-env` 锚点里没有任何 `LAB_CACHE_*`，
所以 app 只能用 application.yml 默认值：热点键 TTL 300s、雪崩 TTL 60s），
这部分单独成步。


