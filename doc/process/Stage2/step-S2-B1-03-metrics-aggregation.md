# 步骤交接：S2-B1-03 指标查询聚合层（窗口 × 实例 × 数据质量）

> 任务/步骤：Stage2 B1-03 —— chaos-controller 通过 Prometheus `/api/v1/query_range` 提供窗口聚合查询；
> 版本化指标目录；统一 `OK / NO_DATA / COLLECTING / DEGRADED`；按实例序列 + 统计摘要；
> `lab_metric_window` 落台账；Grafana/Tempo 深链；分页/上限/超时全部外置
> 日期：2026-10-28
> 分支 / HEAD：`feature/stage2-workbench` 基线 `b4e80e7`（B1-02 已合并并真机验收）
> 关联任务：`doc/process/Stage2/stage2-开发测试与验收计划.md` §0、§2 B1-03、§7、§10；
> `doc/design/Stage2/stage2改造方案.md` §6、§10.5（`lab_metric_window`）、§10.3；验收 A22、A02/A03 的数据源半区
> 测试：`mvn -pl services/chaos-controller -am test` → chaos-controller **435 tests / 0 fail / 0 error / 0 skip**
> （B1-02 基线 384，本步 +51）；platform-common / platform-observability 照常通过，BUILD SUCCESS
> 静态安全：`bash code/lab/scripts/test-lab-safety.sh` → `LAB SAFETY TESTS PASSED.`
> 环境接线：`bash code/lab/scripts/test-chaos-env-wiring.sh` → `CHAOS ENV WIRING PASSED (8 chaos keys + 47 ledger/metrics keys)`
> Compose 渲染：`docker compose --env-file code/lab/docker/.env.example -f code/lab/docker/compose.base.yml config` → EXIT=0
> 运行状态：只跑单测/Testcontainers（本机临时 `mysql:8.4.4`）与**只读** Prometheus 查询；
> **未起/未停共享 lab 栈，未执行任何 compose/up/down/verify/reset 命令**；真机演练清单见 §待定事项和注意事项。

# 当前进展

- 新增只读聚合层 `com.javalearn.lab.chaos.metrics`：调用方只能按 `keys` 选择目录条目，PromQL 不经过 HTTP 入参。
- 两个端点：
  - `GET /lab/runs/{runId}/metrics?from=&to=&keys=&phase=`：run 上下文；缺省窗口为
    `[runStart - scrape_grace, runEnd + drain_grace]`；`from`/`to` 同时给出时按显式窗口精确查询。
  - `GET /lab/metrics/window?from=&to=&keys=&phase=`：无 run 的实时查看；`from`/`to` 必填。
- 每个指标返回按 Prometheus result series 展开的 `series[]{labels, points[[epochSeconds,value]], stats{last,avg,min,max,samples}}`；
  无标签的聚合指标（`sum`/`max` 不带 label）返回单序列 `labels={}`。
- 统一质量：Prometheus 不可达/超时/HTTP 错误/error 信封/畸形 JSON → 该项 `DEGRADED` 且整体 `DEGRADED`；
  查询成功但无序列 → `NO_DATA`；窗口右端进入 `scrape_grace` 且无样本 → `COLLECTING`；
  真实样本 → `OK`；部分失败逐项保留质量，整体按 `DEGRADED > COLLECTING > OK > NO_DATA` 汇总。
- 版本化目录 `src/main/resources/metrics/catalog.yml`：25 个核心 KPI，启动时校验 key 唯一/字符集/PromQL 非空/未知占位符/条目数上限。
- run 上下文查询把每个指标的窗口、PromQL、stats、quality 写入 `lab_metric_window`（phase 默认 `adhoc`）；
  写入失败只 WARN + 响应 `warnings`，不影响查询结果；同 run 可多次查询，每次一批行。
- 响应带 `links{grafanaUrl, tempoUrl}`：Grafana 用 epoch 毫秒 + 外置基地址，UID 空时退化为 Explore；
  Tempo 用 epoch 秒的 search API。无硬编码面板 UID。
- 13 个 `lab.metrics.*` / `LAB_METRICS_*` 键在 `application.yml`、compose、`.env.example` 三处一致；
  `test-chaos-env-wiring.sh` 的自动发现已扩到 `LAB_METRICS_*`。

# 完成功能项

## 1. REST 契约

| 方法 | 路径 | 参数 | 行为 |
|---|---|---|---|
| GET | `/lab/runs/{runId}/metrics` | `from?`、`to?`、`keys?`、`phase?` | run 必须存在（404/40401；台账不可用 503/50302）；`from`/`to` 成对出现；缺省用 run 窗口；200 聚合视图 |
| GET | `/lab/metrics/window` | `from`、`to` 必填；`keys?`、`phase?` | 实时窗口；缺参/非法时间 400/40001；200 聚合视图 |

- `keys`：逗号分隔的目录 key；缺省 = 目录全部；非空 token 数 > `max-keys` → 400；未知 key → 400 并列出可用 key。
- `phase`：`[A-Za-z0-9_-]{1,32}`，默认 `adhoc`，只影响台账行，不影响查询。
- `from`/`to`：ISO-8601（含偏移）或 epoch 秒/毫秒；数值 `>= 1e11` 视为毫秒；窗口至少 1 秒。
- 响应信封沿用 `Result`：`code/message/data`；`data` 关键字段：

```json
{
  "source": "run|window",
  "runId": "run-20261028-004",
  "phase": "adhoc",
  "window": {"from": "ISO", "to": "ISO", "fromEpochSeconds": 0, "toEpochSeconds": 0,
             "stepSeconds": 15, "range": "15s"},
  "quality": "OK|NO_DATA|COLLECTING|DEGRADED",
  "reason": "整体原因的摘要或 null",
  "generatedAt": "ISO",
  "metrics": [
    {"key": "http.rps", "label": "HTTP 请求速率", "unit": "req/s",
     "promql": "解析后的 PromQL（${range} 已替换）", "groupByInstance": true,
     "quality": "OK", "reason": null,
     "series": [{"labels": {"instance": "app-1:8080"},
                 "points": [[1793140800, 1.5]],
                 "stats": {"last": 1.5, "avg": 1.5, "min": 1.5, "max": 1.5, "samples": 1}}]}
  ],
  "links": {"grafanaUrl": "...", "tempoUrl": "..."},
  "warnings": ["ledger write failed for N metric(s); the query result is unaffected"]
}
```

## 2. 窗口 / grace / step

- run 窗口：`start = startedAt ?? createdAt`；`end = endedAt ?? now`；
  `from = start - scrape_grace(15s)`，`to = end + drain_grace(30s)`。
- 显式 `from`/`to` 覆盖 run 窗口时**不再叠加** grace（调用方给的是精确查询窗）。
- 实时窗口：`from`/`to` 必须同时给出。
- `step = max(min_step_seconds, ceil(windowSeconds / (max_points - 1)))`；`${range}` 替换为 `"<step>s"`。
  `max_points - 1` 是因为 query_range 含首尾两个端点；这样返回点数不会超过上限。
  例：300s 窗口 + `max-points=10` → step 34s（10 个点）；默认 240s 窗口 → step 15s。
- 每序列最多 `max-points` 个点（Prometheus 理论上按 step 返回；若仍超限，保留**最新**尾部并置 `DEGRADED`）。
- 全局最多 `max-series` 条序列；超出按请求/目录顺序截断，受影响指标 `DEGRADED`，已返回的序列仍保留。
- Prometheus 请求超时/连接超时分别为 `request-timeout-seconds` / `connect-timeout-seconds`。

## 3. 质量判定规则

| # | 条件 | 指标质量 | 序列 | 整体质量 |
|---|---|---|---|---|
| 1 | 连接拒绝 / 超时 / 非 2xx / `status:error` / 畸形 JSON / 非 matrix | `DEGRADED` + 原因 | `[]`（绝不补 0） | 只要有 `DEGRADED` → `DEGRADED` |
| 2 | 查询成功，至少一条序列含有限样本 | `OK` | 按实例/标签展开 + stats | 无 DEGRADED 时按下一行 |
| 3 | 查询成功，无序列，且 `to >= now - scrape_grace` | `COLLECTING` + “首次可用时间未知/下个 scrape 再试” | `[]` | 无 DEGRADED 但有 COLLECTING → `COLLECTING` |
| 4 | 查询成功，无序列，且窗口右端已老于 grace | `NO_DATA` + “窗口内无序列” | `[]` | 全 NO_DATA → `NO_DATA` |
| 5 | 有 OK 也有 NO_DATA | 各自保留 | 各自保留 | `OK`（查询成功；缺数由单项 NO_DATA 呈现） |
| 6 | 个别 key 失败、其余成功 | 失败项 `DEGRADED`，成功项 `OK` | 各自保留 | `DEGRADED` + 失败项原因摘要 |
| 7 | `max-series`/`max-points` 截断 | 该指标 `DEGRADED` | 保留未截断部分 | `DEGRADED` |

- 所有数值都来自真实样本；`NaN`/`+Inf`/`-Inf` 样本被丢弃（例如空 `histogram_quantile`），不会变成 0。
- `instance` 与其余标签来自 Prometheus 结果并经过 deny-list 过滤：`__name__`、`url`、`raw_url`、
  `trace_id/span_id`、`user_id/order_id/request_id/idempotency_key/session_id`、`query` 等不会出现在响应里。

## 4. 指标目录（25 项）

> 导出名以 `doc/process/Stage2/指标字典.md` 为准；`${range}` 在查询前替换为 step。
> `groupByInstance` 是给 UI 的元数据；实际“按实例”由目录里的 `sum/max by (instance)` 保证。

| key | 主要 PromQL | unit | 导出名 | groupByInstance |
|---|---|---|---|---|
| `http.rps` | `sum by(instance)(rate(http_server_requests_seconds_count{job="seckill-app"}[${range}]))` | req/s | `http_server_requests_seconds_count` | true |
| `http.success.ratio` | `1 - 5xx_rate / total_rate`（按 instance） | ratio | 同上 | true |
| `http.failure.ratio` | `5xx_rate / total_rate`（按 instance） | ratio | 同上 | true |
| `http.status.rps` | `sum by(instance,status)(rate(http_server_requests_seconds_count[${range}]))` | req/s | 同上 | true |
| `http.latency.p95` | `histogram_quantile(0.95, sum by(instance,le)(rate(http_server_requests_seconds_bucket[${range}])))` | s | `http_server_requests_seconds_bucket` | true |
| `http.latency.p99` | 同上 0.99 | s | 同上 | true |
| `jvm.heap.used` | `sum by(instance)(jvm_memory_used_bytes{job="seckill-app",area="heap"})` | bytes | `jvm_memory_used_bytes` | true |
| `jvm.heap.max` | `sum by(instance)(jvm_memory_max_bytes{area="heap"})` | bytes | `jvm_memory_max_bytes` | true |
| `jvm.gc.pause.max` | `max by(instance)(jvm_gc_pause_seconds_max)` | s | `jvm_gc_pause_seconds_max` | true |
| `jvm.cpu.usage` | `max by(instance)(process_cpu_usage)` | ratio | `process_cpu_usage` | true |
| `jvm.threads.live` | `max by(instance)(jvm_threads_live_threads)` | threads | `jvm_threads_live_threads` | true |
| `hikari.active` | `sum by(instance,pool)(hikaricp_connections_active)` | connections | `hikaricp_connections_active` | true |
| `hikari.pending` | `sum by(instance,pool)(hikaricp_connections_pending)` | connections | `hikaricp_connections_pending` | true |
| `executor.active` | `sum by(instance,name)(executor_active_threads)` | threads | `executor_active_threads` | true |
| `executor.queue` | `sum by(instance,name)(executor_queued_tasks)` | tasks | `executor_queued_tasks` | true |
| `executor.rejected` | `sum by(instance,name)(increase(executor_rejected_total[${range}]))` | tasks | **当前无导出**（见缺陷） | true |
| `cache.l1.hit.ratio` | `avg by(instance)(cache_l1_hit_ratio)` | ratio | `cache_l1_hit_ratio` | true |
| `cache.l1.entries` | `sum by(instance)(cache_l1_estimated_size)` | entries | `cache_l1_estimated_size`（估算） | true |
| `cache.l2.keys` | `sum(redis_db_keys{job="redis-exporter",db="db0"})` | keys | `redis_db_keys` | false |
| `cache.l2.memory` | `sum(redis_memory_used_bytes{job="redis-exporter"})` | bytes | `redis_memory_used_bytes` | false |
| `business.orders.created` | `sum by(instance)(increase(orders_created_total[${range}]))` | orders | `orders_created_total` | true |
| `business.stock.sold_out` | `sum by(instance)(increase(seckill_attempts_total{result="sold_out"}[${range}]))` | events | `seckill_attempts_total` | true |
| `business.stock.oversold` | `sum by(instance)(increase(stock_oversold_total[${range}]))` | events | `stock_oversold_total` | true |
| `mysql.cluster.probe.up` | `max(mysql_cluster_probe_up{job="chaos-controller"})` | bool | `mysql_cluster_probe_up` | false |
| `targets.up` | `up{job=~"seckill-app\|...\|cadvisor"}` | bool | `up` | true |

- 25 项已在运行中的 Prometheus 上逐条做**只读 instant query**：24 项有序列；`executor.*` 3 项为 0 序列（如实 NO_DATA）。
- `targets.up` 当前 19 条序列，`http.status.rps` 13 条，合计约 92 条，低于 `max-series=120`。

## 5. 深链格式

- Grafana（UID 空）：`{grafana-url}/explore?from=<epochMillis>&to=<epochMillis>`
- Grafana（配置了 UID）：`{grafana-url}/d/{uid}?from=<epochMillis>&to=<epochMillis>`
- Tempo：`{tempo-url}/api/search?start=<epochSeconds>&end=<epochSeconds>`
- 时间范围 = 实际查询窗口（含 grace）；基地址全部来自 `lab.metrics.grafana-url` / `tempo-url`，代码不硬编码面板 UID。
- Grafana 是宿主机唯一发布的观测入口（`127.0.0.1:3000`）；Tempo 只在 compose 网络 `expose 3200`。
  浏览器点击深链时，把 `LAB_METRICS_GRAFANA_URL` 覆盖为 `http://127.0.0.1:3000`；Tempo 直链是 search API，
  需要端口转发或改走 Grafana Explore 的 Tempo 数据源（见 §待定）。

## 6. 新增配置（13 个键，三处一致）

| 键 | env | 默认 |
|---|---|---|
| `lab.metrics.prometheus-url` | `LAB_METRICS_PROMETHEUS_URL` | `http://prometheus:9090` |
| `lab.metrics.connect-timeout-seconds` | `LAB_METRICS_CONNECT_TIMEOUT_SECONDS` | 2 |
| `lab.metrics.request-timeout-seconds` | `LAB_METRICS_REQUEST_TIMEOUT_SECONDS` | 10 |
| `lab.metrics.scrape-grace-seconds` | `LAB_METRICS_SCRAPE_GRACE_SECONDS` | 15 |
| `lab.metrics.drain-grace-seconds` | `LAB_METRICS_DRAIN_GRACE_SECONDS` | 30 |
| `lab.metrics.max-points` | `LAB_METRICS_MAX_POINTS` | 300 |
| `lab.metrics.min-step-seconds` | `LAB_METRICS_MIN_STEP_SECONDS` | 15 |
| `lab.metrics.max-series` | `LAB_METRICS_MAX_SERIES` | 120 |
| `lab.metrics.max-keys` | `LAB_METRICS_MAX_KEYS` | 40 |
| `lab.metrics.grafana-url` | `LAB_METRICS_GRAFANA_URL` | `http://grafana:3000` |
| `lab.metrics.tempo-url` | `LAB_METRICS_TEMPO_URL` | `http://tempo:3200` |
| `lab.metrics.grafana-dashboard-uid` | `LAB_METRICS_GRAFANA_DASHBOARD_UID` | 空（Explore） |
| `lab.metrics.catalog-location` | `LAB_METRICS_CATALOG_LOCATION` | `classpath:metrics/catalog.yml` |

`MetricsProperties.validate()` 在启动时检查 URL 前缀、范围、UID 字符集；`catalog-location` 缺失或目录非法会让上下文启动失败（有意 fail-fast）。

## 7. 台账

- 仅 run 上下文写 `lab_metric_window`：`run_id, phase, metric_key, promql, window_start, window_end, stats(JSON), data_quality`。
- `stats` 只存摘要（`seriesCount`、`samples`、每序列 `labels/stats`、失败原因），不存整段 points，避免台账膨胀。
- `window_start/end` 是实际发送给 Prometheus 的查询窗口（含 grace 或显式 from/to）。
- 每个指标一次 insert，不包大事务；失败 WARN 并继续下一项；响应 `warnings` 提示“结果不受影响”。
- 同 run 多次查询按 `phase` 分批；默认 `adhoc`，调用方可传 `during` / `after` 等。

## 8. 测试分布（新增 51）

| 类 | 用例 | 覆盖 |
|---|---|---|
| `MetricsPropertiesTest` | 5 | 真实 yml 默认值、13 个 env 键、env 覆盖、URL/范围/UID 校验 |
| `MetricCatalogTest` | 6 | 随包目录加载、25 key 唯一/字符集/非空、重复 key、非法 key、未知占位符、max-keys、缺失资源 |
| `MetricsTimeTest` | 3 | ISO/偏移、epoch 秒/毫秒、非法输入 400 |
| `HttpPrometheusGatewayTest` | 8 | 真实本地 HTTP server：路径/start/end/step/query 原文、NaN/Inf 丢弃、空 matrix、500、error 信封、畸形 JSON/vector、连接拒绝、超时 |
| `MetricsServiceTest` | 21 | 按实例 stats、NO_DATA/COLLECTING 边界、DEGRADED 不补 0、部分失败、step 提升、series/points 截断、标签过滤、深链、run grace、显式窗口、404/503、台账失败不影响响应、未知/超量 key、非法 phase、run 台账写入 |
| `MetricsControllerTest` | 4 | 两端点路由/信封、400、404 |
| `MetricWindowLedgerIT` | 4 | Testcontainers MySQL：每指标一行、同 run 两批、DEGRADED 行含原因、写失败仍返回结果 |

# 设计逻辑

- **为什么目录而不是任意 PromQL**：前端只允许传 key，目录随代码版本走，评审能逐条对照指标字典；
  同时把“哪些指标需要按实例聚合”固定在 `sum/max by(instance)` 里，避免把 `url`/`trace_id` 等高基数标签带进响应。
- **为什么 `instance` 来自结果标签而不是自己拼**：Prometheus 的 `instance` 是抓取目标身份（如 `app-1:8080`），
  低基数且跨 job 一致；响应再做 deny-list，只保留安全标签。
- **为什么失败是 DEGRADED 而不是 NO_DATA**：两者对 UI 的含义不同——NO_DATA 是“采集正常但窗口没有序列”，
  DEGRADED 是“查询链路坏了”。把失败写成 NO_DATA 会掩盖 Prometheus 故障；写成 0 更会伪造业务结论。
- **COLLECTING 的边界**：窗口右端进入 `[now - scrape_grace, ...]` 时，最后一次抓取可能尚未完成/尚未写入，
  空结果不能断言“没有数据”，因此给 COLLECTING + “首次可用时间未知，下个 scrape 再试”；窗口完全落在 grace 之前才是 NO_DATA。
- **step 与点数**：先按 `max-points` 反推 step，再用 `min-step` 兜底，保证 rate/increase 至少有可用窗口；
  `max-points` 仍作为硬上限，防御 Prometheus 未遵守 step 的异常响应。
- **max-series 的截断顺序**：按请求/目录顺序先到先得；被截断的指标单独 DEGRADED 并保留已取到的序列，
  这样 UI 能显示“部分数据 + 降级”，而不是整页空白。
- **台账 best-effort**：窗口查询是只读观测，台账只是证据；MySQL 主库演练期间台账不可用不应让监控页也消失。
  `findRun` 是 run 窗口的必要输入，无法 best-effort，因此单独映射 503。
- **深链不硬编码 UID**：默认只给 Explore；面板 UID 由 `LAB_METRICS_GRAFANA_DASHBOARD_UID` 外置，
  这样 B1-05 换面板/换 UID 不需要改代码。

# 存在缺陷项&TODO

- **`executor.rejected` 目前无导出名**：Micrometer 1.15 的 `ExecutorServiceMetrics` 只导出
  `executor_active_threads` / `executor_queued_tasks` / `executor_completed_tasks_total` 等，没有 rejected 计数器；
  B0-01 字典也没有该导出名。目录保留该 key，实际返回 `NO_DATA`/`COLLECTING`，**不会补 0**；
  B4-01 业务线程池配置闭环补计数器后自动出数。
- **`cache.l2.keys` 使用 `redis_db_keys`**：B0-01 字典写的是“无按 db 的 keys 序列”，但真机 redis-exporter
  实际导出 `redis_db_keys{db="db0"}`（本次只读核对：db0 ≈1033 keys）。若评审要求严格按字典，可切到
  `k6_cache_lab_state_l2_keys`（仅 run 期有 remote-write，且无 run 过滤会混历史 run）。
- **Tempo 直链是 search API**：Tempo 未在宿主机发布端口，浏览器不能直接打开 `http://tempo:3200`;
  需要端口转发，或由 B1-05 改用 Grafana Explore 的 Tempo 数据源。Grafana 深链默认也是 compose 内地址，
  浏览器使用前需把 `LAB_METRICS_GRAFANA_URL` 覆盖为 `http://127.0.0.1:3000`。
- **run 查询强依赖台账**：`findRun` 失败 → 503；即使显式给了 from/to 也不放行。若后续希望“台账挂了仍能看实时图”，
  可在 from/to 齐全时跳过 findRun（本步不做，避免产生无 run 证据的窗口行）。
- **`groupByInstance` 只是元数据**：聚合层不改写目录 PromQL；新增目录项必须自己写 `by(instance)` 或
  `sum(...)`（无标签聚合），否则响应会按目录原样展开多条序列。
- 未改 Vue 控制台（B1-05 接入）、未做 B1-04 预检查、未改 `verify.sh`/`smoke.sh`/`claims.yml`、未改计划基线数字。
- 行尾：既有 `compose.base.yml` / `.env.example` / `application.yml` 在 git index 是 CRLF，本步保持纯 CRLF；
  新增 Java/YAML/资源/MD 为 LF。

# 待定事项和注意事项

- **主会话真机演练（本 worktree 不做）**：
  1. 重建 `chaos-controller` 镜像并 `up -d --no-deps --force-recreate chaos-controller`（离线构建回退见 B1-01 附录）。
     确认容器 env 出现 13 个 `LAB_METRICS_*`，`GET /lab/metrics/window?from=<now-5m>&to=<now>&keys=http.rps`
     返回 `quality=OK` 且 3 个 instance 序列。
  2. **A22**：`docker stop jl-redis-exporter` 后查询
     `GET /lab/metrics/window?from=<now-10m>&to=<now-2m>&keys=cache.l2.keys,cache.l2.memory`
     → 两项必须 `NO_DATA` 且 `series=[]`（不是 0）。若把 `to` 设成接近 now，看到 `COLLECTING` 也是符合语义的；
     恢复 exporter 后重新查询 → `OK`。
  3. run 上下文：`POST /lab/runs` 造一笔 run（或复用台账里的已完成 run），
     `GET /lab/runs/{runId}/metrics?keys=http.rps,jvm.heap.used` → 窗口应为 `start-15s .. end+30s`；
     `SELECT phase,metric_key,data_quality,window_start,window_end FROM lab_metric_window WHERE run_id=...`
     应每个指标一行；同 run 再查一次 → 行数翻倍（两批）。
  4. Prometheus 故障：用 shell env 覆盖 `LAB_METRICS_PROMETHEUS_URL=http://127.0.0.1:1` 重建控制器，
     查询 → 整体/逐项 `DEGRADED`、`series=[]`、原因含 “unreachable”；恢复默认值重建。
  5. 深链：把 `LAB_METRICS_GRAFANA_URL=http://127.0.0.1:3000` 写进 `.env` 后看响应里的
     `links.grafanaUrl` 是否带 `from/to` 毫秒并可打开；Tempo 链接按 §缺陷说明处理。
  6. 浏览器/控制台接入留 B1-05；本步只保证 API 与数据质量语义。
- 合并后请复跑全量闸门；本步未改 `verify.sh`/`claims.yml`/计划 §0 基线数字，由主会话统一回写。
- 下一步：B1-04 预检查可直接用 `GET /lab/metrics/window` 查关键序列可用性（无序列 → 阻断/降级）；
  B1-05 控制台按本契约渲染 `quality/reason/series/links`；B3-04 对账报告可复用同一窗口与质量口径。

# 附录

## A. 命令与结果（本 worktree）

```bash
cd code
mvn -pl services/chaos-controller -am test
# chaos-controller: Tests run: 435, Failures: 0, Errors: 0, Skipped: 0 -> BUILD SUCCESS
# platform-common / platform-observability: SUCCESS

bash code/lab/scripts/test-lab-safety.sh          # LAB SAFETY TESTS PASSED.
bash code/lab/scripts/test-chaos-env-wiring.sh    # PASSED (8 chaos keys + 47 ledger/metrics keys)
docker compose --env-file code/lab/docker/.env.example \
  -f code/lab/docker/compose.base.yml config      # EXIT=0，13 个 LAB_METRICS_* 全部展开
```

只读 Prometheus 核对（不触碰栈生命周期）：用 25 条目录 PromQL（`${range}`=15s）逐条 query，
24 条有序列；`executor.active/queue/rejected` 为 0 序列（如实的 NO_DATA）。

## B. 提交

| sha | 内容 |
|---|---|
| `4139097` | `feat(B1-03): aggregate catalog metrics over run windows`（实现/目录/配置/接线脚本） |
| `98d8cbe` | `test(B1-03): prove quality states, caps, deep links and ledger rows`（7 个测试类，51 用例） |
| `f8f7daa` | `docs(B1-03): hand off the metrics aggregation step`（本交接） |
| `fc398b4` | `fix(B1-03): keep query_range points inside the configured cap`（step 预留首尾端点 + instance 值防护） |

## C. 关键文件

- 实现：`code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/metrics/`
  （`MetricsService`、`HttpPrometheusGateway`、`MetricCatalog`、`MetricsProperties`、`MetricsController`、`MetricsConfig`）
- 目录：`code/services/chaos-controller/src/main/resources/metrics/catalog.yml`
- 错误映射：`.../error/InvalidMetricsRequestException.java`、`.../web/LabExceptionHandler.java`
- 配置：`application.yml`、`code/lab/docker/compose.base.yml`、`code/lab/docker/.env.example`
- 接线检查：`code/lab/scripts/test-chaos-env-wiring.sh`
- 测试：`.../src/test/java/com/javalearn/lab/chaos/metrics/`、`.../ledger/MetricWindowLedgerIT.java`
