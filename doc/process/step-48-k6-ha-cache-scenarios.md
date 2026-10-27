# 步骤交接 · Step 48 k6 HA / 缓存场景（`db-failover` + `cache-lab`）

> 日期：2026-10-27
> 分支 / HEAD：worktree `agent-general-purpose-67256333`，基线 `2980874`（`docs: close out the 2026-10-27 batch ...`）
> 关联任务：M3-2 MySQL Router 切换（step-31）、M4 缓存实验（step-25/26/27/28）、step-37 D10；本步只补 k6 场景、Node 测试与文档
> 边界：**只改 `code/lab/k6/**` 与本文档**；未新增/修改场景 JSON；未执行任何 Docker、k6 实跑、`verify.sh` / `smoke.sh` / `console-smoke.sh`
> 验证：`node --test "code/lab/k6/test/*.test.mjs"` → **59 pass / 0 fail**（基线 41 + 新增 18）；`node --check` 覆盖全部新增/修改 JS；`bash -n code/lab/k6/run.sh` 通过

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

1. **live run 未做**：本步只做 authoring + Node 测试；`db-failover`、`cache-lab` 都没有对活栈执行，阈值与 D10 曲线没有实栈证据。Docker 由另一会话负责。
2. **D10 相关性未验收**：`cache_lab_*` gauge 依赖 `/actuator/prometheus` 与 `/lab/cache/state` 的真实序列；需要在真栈确认 `cache_db_fallbacks_total`、`cache_l1_requests_total` 等名称/标签与 step-37 一致，并确认 remote-write 后 PromQL 能按 `run_id` + `mode` 聚合。当前 D10 没有 annotation，切换时间点仍靠 k6 日志与 gauge 时间窗。
3. **模式切换是单实例 API**：要整层一致必须传 `CACHE_LAB_APP_URLS` 三个实例；不传时 1/3 流量命中被切模式，D10 的 `instance` 变量会显示差异，这是预期但容易误读。
4. **读的 mode 标签是时间表推导**：与 app 实际切换有毫秒级偏差；没有从响应头/state 回读每个请求的真实模式。
5. **actuator 采样的边界**：若 management endpoint 未暴露、返回 404 或 app 重启，差值会跳过或跨模式累积；`CACHE_LAB_SAMPLE_ACTUATOR=0` 可关闭，state gauge 不受影响。
6. **db-failover 目标需人工确认**：默认 `jl-mysql-2` 是 step-31 演练后的主节点，不是永远的主；重放前必须 `bash code/lab/scripts/db-cluster-status.sh`（或等价脚本）确认，否则会停副本、失去教学意义。
7. **datastore 安全边界**：两个 gate 都必须由 operator 显式放宽；`jl-mysql`（无数字后缀）、`*-exporter`、`jl-mysql-10` 仍应被拒。`run.sh` 只是转发，不替代控制器的白名单。
8. **库存 / 数据清理**：db 写会扣库存，cache seed 会写 1000 行；当前没有自动 reset 集成到这两个场景，重复演示前分别跑 `reset-lab.sh` 与手工清理。
9. **未新增 JSON**：`db-failover` 用 env 合成事件，所以实验操作台的 `GET /lab/guide/scenarios` 不会自动出现该场景；若要在控制台逐步演示，需要另加 schema v1 JSON（本步未做，避免把目标写死）。
10. **未跑门禁**：`verify.sh` / `smoke.sh` / `console-smoke.sh` 未执行；本步的验证边界是 59 个 Node 测试 + 语法检查 + `bash -n`。
