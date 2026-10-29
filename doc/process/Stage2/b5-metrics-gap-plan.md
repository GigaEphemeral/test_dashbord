# B5-04a 指标缺口分类与 B5-04b 执行计划

> 输入：`doc/process/Stage2/指标字典.md`（B0-01 快照）、计划 §B5-04、改造方案 §6.2/§9.5/§16.10、真机只读查询。
> 探测时刻：**2026-10-29T10:15Z**；入口 `http://127.0.0.1:8080/prom/api/v1/...`；容器经 `docker ps/inspect/logs/exec` 只读检查。
> 本轮**不改生产代码、不重启容器、不改 Docker 栈、不提交**；本文只做分类与 B5-04b 施工单。
> 决策图例：`WIRE`=B5-04b 新增/修复采集或解析路径；`DERIVED`=不加采集，用现有序列/API/归档换算；`UNWIRED`=确认无源，登记并禁止显示 0；`DEGRADED`=有源但维度受限，显示“采集降级”。

## 0. 结论与字典冲突的实测事实

### 0.1 逐行复核后的状态数（字典头部统计已过期）

| 来源 | OK | 部分 | 未接线 | NO_DATA | 已接线 | 合计 |
|---|---:|---:|---:|---:|---:|---:|
| 字典头部（L16-20） | 169 | 23 | 25 | 3 | - | 220 |
| 逐行复核（7 个指标族） | 169 | **24** | **23** | 3 | 1 | 220 |

差异原因：`redis_up`（L211）已改为“已接线”，另 1 条由“未接线”改为“部分”；头部统计未同步。B5-04b 只改行、不重排统计口径。

### 0.2 与字典/目录冲突、需在 B5-04b 处理或纠正的存量口径

| # | 字典行 | 字典写法 | 真机事实 | 处理 |
|---|---|---|---|---|
| 1 | L176/L177 `cache_size`/`cache_l1_size` | 未接线，词表无此词 | 实际导出名是 **`cache_l1_estimated_size{cache="product"}`**，24h 3 条（app-1/2/3，即时值 1）；目录 `cache.l1.entries` 已用它（`catalog.yml:126-130`）；绑定在 `TwoLevelCache.java:236` | 字典改名并标 OK；不新增代码 |
| 2 | L181 `L2 业务键数量` | 部分，无 keyspace 计数 | **`redis_db_keys{job="redis-exporter",db="db0"}` 有 64 条序列，db0=11107**；目录 `cache.l2.keys` 已用它（`catalog.yml:132-137`） | 字典标 OK；不新增 exporter |
| 3 | L178 `L1 开关状态（toggles）` | 未接线，`/lab/toggles` 被 nginx 404 | 按实例开关已存在：`GET/POST /lab/cache/l1`（`LabCacheL1Controller.java:28,44,49`），控制台走 `/lab-api/chaos/app/{instance}/lab/cache/l1`（`CacheLab.vue:32-39`）；只有遗留 `/lab/toggles` 被 `default.conf:44` 404 | 字典改为“已按实例 API 接线”；遗留 `/lab/toggles` 保持 404 |
| 4 | L115-L118 Tomcat 4 行 | OK（字典正确，不是缺口；列入仅为确认不重做） | 实机 4 target 全有：busy=1、current=19/19/23、max=200、connections=2（jdk8 也在线） | B5-04b 只补目录/UI，不补抓取 |
| 5 | `executor_rejected_total`（目录注释） | `catalog.yml:110-112` 称“未接线，NO_DATA/COLLECTING” | 实机 3 条序列，`name="business"`，值 0（`BusinessPoolConfig.java:27`） | B5-04b 改注释并纳入验收 |
| 6 | L222 `redis_role` | 部分，无 `redis_role` 指标 | `redis_instance_info{job="redis-exporter-ha",role}`：master 1 / slave 2，node 标签齐全 | 字典改 OK（按 role 标签取），不造新指标 |
| 7 | L109 `应用重启次数` | 部分，cAdvisor per-name 不可得 | `changes(process_start_time_seconds{job="seckill-app"}[1h])` 实机 app-1=1、app-2/3=0 | 改为 DERIVED，与 cAdvisor 解耦 |

### 0.3 其它关键实测

- **cAdvisor 降级根因不是泛化的 WSL2**：`docker info` 为 `Storage Driver: overlayfs`、`driver-type: io.containerd.snapshotter.v1`；cAdvisor 日志反复报 `open /rootfs/var/lib/docker/image/overlayfs/layerdb/mounts/<id>/mount-id: no such file or directory`，裸 `/metrics` 只有 `container_*{id="/"}`。containerd 镜像存储下 cAdvisor v0.49.1 的 Docker handler 无法枚举容器。
- **GC 日志文件存在 ≠ 已启用**：`/app/lab-snapshots/gc-app-1.log` 末行是 `2026-10-29T07:35:29Z`（上一次容器），当前 app-1 于 10:01 重启；`jcmd 1 VM.command_line` 无 `-Xlog`；`GET /lab/instances/app-1/config` 为 `gcLogEnabled=false`、`heapDumpOnOutOfMemory=null`；GC 端点返回 `DISABLED`。
- **MySQL Router 没有 Prometheus 端点**：容器内 8443 有 HTTP server，但 `GET https://mysql-router:8443/metrics` → **404**，`/api/20190715/router/status` → **401**（需凭据）；`prometheus.yml` 无 router job。
- **k6 阈值已有真实归档**：`GET /lab-api/lab/runs/run-20261029-004/k6/report` → `200`、4774 B；summary 内 3 组阈值（`server_errors.rate<0.01=false`、`flow_errors.rate<0.01=false`、`http_req_duration{flow:product}.p(95)<300=false / p(99)<800=false`，k6 1.3 的布尔是 “crossed”，false=通过，见 `bench-report.js:298-305`）；`http_req_duration.avg=24.07 ms`、`med=23.72 ms`，Prometheus 无 avg/med 序列。
- **抓取目标共 19 条 `up` 序列、全部为 1**（13 个 job；`seckill-app`/`mysqld-exporter-ha`/`redis-exporter-ha` 各 3 target；含 `seckill-app-jdk8`，字典 L7 “jdk8 down”是 B0 时点快照）；`up{job="rabbitmq"}=1`。

## 1. 全量缺口分类（50 条）

> 行号指 `指标字典.md`；证据为文件:行或本轮只读查询。50 条 = 23 未接线 + 24 部分 + 3 NO_DATA（排除 L211 已接线）。

### 1.1 HTTP / k6（5）

| 指标键（字典行） | 现状态 | 决策 | 具体来源 / 做法 | 目标 | 证据 |
|---|---|---|---|---|---|
| `k6_seckill_expected_409_total`（L53） | NO_DATA | WIRE（已导出，补运行证据） | k6 `Counter('seckill_expected_409')` → remote-write `k6_seckill_expected_409_total{run_id,scenario}` | B5-05 | `code/lab/k6/lib/http.js:20`；词表有该名；`count(...)`=0（窗口内无 run） |
| `k6_seckill_expected_429_total`（L54） | NO_DATA | WIRE（同上） | 同上，`lib/http.js:21` | B5-05 | 词表有该名；无 run |
| `k6_http_req_duration_avg/_med`（L79） | 未接线 | DERIVED | `summary.json.metrics.http_req_duration.avg/med`，经 `/lab/runs/{runId}/k6/report` 读取 | B5-04b | 实跑 `run-20261029-004`：avg=24.074、med=23.722；Prometheus 词表无 k6 avg/med/sum/count |
| k6 threshold PASS/FAIL 与阈值详情（L80） | 未接线 | WIRE | 服务端解析 `summary.json.metrics.*.thresholds`；`crossed=true` = 失败；新增只读端点 | B5-04b | `bench-report.js:247-306`；实跑 summary 3 组阈值；`BenchExecutionController.java:56` 现仅原样下载 |
| 超时/连接错误（L81） | 部分 | DERIVED | `sum by(error_code)(increase(k6_http_reqs_total{run_id="$run",expected_response="false"}[1m]))` | B5-04b | 24h 3369 条 `k6_http_reqs_total` 带 `error/error_code`；无独立 timeout 指标 |

### 1.2 JVM（4）

| 指标键（字典行） | 现状态 | 决策 | 具体来源 / 做法 | 目标 | 证据 |
|---|---|---|---|---|---|
| `process_resident_memory_bytes`（L105） | 部分 | DEGRADED | app job **无** RSS 序列；只有 exporter 进程与 cAdvisor root；RSS 用 JVM heap used 代替时必须改名，不能冒充 RSS | B5-05 | `process_resident_memory_bytes{job="seckill-app"}` n=0；`jvm_memory_used_bytes{job="seckill-app",area="heap"}` n=9 |
| `jvm_parameters_info` / 镜像版本 / 配置变更时间（L108） | 未接线 | DERIVED | `GET /lab-api/lab/instances/{instance}/effective` 返回 `javaToolOptions/imageId/readback/observability`；补 `containerStartedAt` + config revision | B5-04b | `InstanceConfigController.java:58`；实机 200：imageId=`sha256:408b...`、revision=27、readback=OK；`jcmd 1 VM.command_line` 与 `JAVA_TOOL_OPTIONS` 一致 |
| 应用重启次数（L109） | 部分 | DERIVED | `changes(process_start_time_seconds{job="seckill-app"}[1h])` | B5-04b | 实机 app-1=1、app-2=0、app-3=0 |
| 堆转储事件（L110） | 未接线 | WIRE | Docker inspect `.State.OOMKilled/.RestartCount/.State.StartedAt` + 容器日志 grep `OutOfMemoryError` / `Dumping heap` + GC 日志端点；dump 文件需 `-XX:+HeapDumpOnOutOfMemoryError`（配置已支持，默认关） | B5-04b | `docker inspect jl-app-1`：OOMKilled=false、RestartCount=0；config `heapDumpOnOutOfMemory=null`、`gcLogEnabled=false`；`InstanceConfigValidator.java:310-312` |

### 1.3 Web / Tomcat / 业务线程池 / 连接池（3）

| 指标键（字典行） | 现状态 | 决策 | 具体来源 / 做法 | 目标 | 证据 |
|---|---|---|---|---|---|
| `thread_lab_injected_total` 缺 `hold` 标签（L135） | 部分 | DEGRADED | 不新增标签：字典/方案未要求 auto/manual 分列；加标签属 app 指标 schema 变更，收益低于风险；UI 注明“不可区分” | B5-05 | 字典实测标签仅 `mode`；加标签属 app 指标 schema 变更 |
| 快照 Timer 无 `_bucket`（L141） | 部分 | DEGRADED | 用 `_count/_sum/_max`，不展示 P95 | B5-05 | 词表只有 `_count/_sum/_max`；无 `thread_lab_snapshot_duration_seconds_bucket` |
| `hikaricp_connections_acquire_seconds_bucket`（L149） | 未接线 | DEGRADED | 用 `_max` + `_count`，不展示获取 P95 | B5-05 | 词表只有 `_count/_sum/_max`；无 `_bucket` |

### 1.4 Caffeine / Redis 两级缓存（6）

| 指标键（字典行） | 现状态 | 决策 | 具体来源 / 做法 | 目标 | 证据 |
|---|---|---|---|---|---|
| `cache_size`（L176） | 未接线 | WIRE（已具备，改名登记） | 实际 `cache_l1_estimated_size{cache}`；目录 `cache.l1.entries` | B5-04b | `TwoLevelCache.java:236`；实机 3 条；`catalog.yml:126-130` |
| `cache_l1_size`（L177） | 未接线 | WIRE（同上） | 同上 | B5-04b | 同上 |
| L1 开关状态（toggles）（L178） | 未接线 | DERIVED | `GET/POST /lab-api/chaos/app/{instance}/lab/cache/l1`（按实例） | B5-04b | `LabCacheL1Controller.java:28,44,49`；`CacheLab.vue:32-39`；遗留 `/lab/toggles` 被 `default.conf:44` 404 |
| 当前防护模式 `mode` 标签（L179） | 未接线 | DEGRADED | 有意不进 Prometheus；用 `k6_cache_lab_mode_switches_total` 时间窗对齐 | B5-05 | 实机 `cache_*` 无 mode；`k6_cache_lab_mode_switches_total` 24h 有序列 |
| 单键 TTL / 键级状态（L180） | 未接线 | DEGRADED | 按需 `GET /lab/cache/keys`（SCAN 分页），不进指标 | B5-05 | `LabCacheKeysController.java:26,35`；直连 app 返回 401（需 token），属预期 |
| L2 业务键数量（L181） | 部分 | WIRE（已具备，登记） | `redis_db_keys{job="redis-exporter",db="db0"}` | B5-04b | 实机 64 条，db0=11107；`catalog.yml:132-137` |

### 1.5 MySQL / HA / Router（9）

| 指标键（字典行） | 现状态 | 决策 | 具体来源 / 做法 | 目标 | 证据 |
|---|---|---|---|---|---|
| `mysql_up`（L186） | 部分 | DEGRADED | 4 条序列（standalone 无 `member` 标签 + 3 HA member）；Router 无 exporter | B5-05 | 即时 4 条，值 1；`member=""` 仅 standalone |
| `mysql_global_status_questions`（L187） | 部分 | DEGRADED | QPS 用它差分；无 `com_*`；standalone 无 member | B5-05 | 4 条；无 `mysql_global_status_com_*` |
| `mysql_global_status_queries`（L188） | 部分 | DEGRADED | 同上；不能当 TPS | B5-05 | 4 条；词表无 com |
| `mysql_global_status_deadlocks`（L196） | 未接线 | UNWIRED | MySQL 8.4 已移除该状态变量；死锁看 `innodb_row_lock_*` | Future | 词表 0；`innodb_row_lock_waits` 4 条 |
| `mysql_cluster_probe_up`（L200） | 部分 | DEGRADED | 控制器 gauge 无 member/target 标签；仅能判全局探针 | B5-05 | 1 条、值 1；`probe_success/failure` 同样无标签 |
| 查询/事务耗时直方图（L203） | 未接线 | UNWIRED | exporter 无 histogram；perf_schema 未开；用 app `http_server_requests` + `order_accept_seconds` | Future | 词表无 `mysql_*duration*` |
| TPS（com_commit/com_rollback）（L204） | 部分 | DEGRADED | 用 `questions` 近似并明确标注“非 TPS”，不造 com 序列 | B5-05 | exporter 参数仅 `--mysqld.address/username`；com 0 条 |
| MySQL Router 自身指标（L205） | 未接线 | UNWIRED | Router 无 `/metrics`；8443 仅 REST 且需凭据；不加 job | Future | 8443 `/metrics`→404、`/api/.../status`→401；`prometheus.yml` 无 router job |
| 库存/对账结果（L206） | 未接线 | UNWIRED（设计如此） | 只读 SQL + `GET /lab/runs/{runId}/reconciliation` | B5-05 | `ReconciliationController.java:33`；无 Prometheus 序列 |

### 1.6 Redis / RabbitMQ / Nginx / 容器 / 网络（17）

| 指标键（字典行） | 现状态 | 决策 | 具体来源 / 做法 | 目标 | 证据 |
|---|---|---|---|---|---|
| `redis_role`（L222） | 部分 | WIRE（用 role 标签登记） | `redis_instance_info{job="redis-exporter-ha",node,role}` | B5-04b | 实机 master 1（redis-3）/slave 2（redis-1/2） |
| `rabbitmq_up`（L223） | 未接线 | DERIVED | `up{job="rabbitmq"}` | B5-04b | 实机 1 |
| `nginx_http_requests_total`（L234） | 部分 | DEGRADED | 只有总量；无 server/状态码维度 | B5-05 | 词表 9 个 nginx 名；无 status/server |
| `nginx_connections_active`（L235） | 部分 | DEGRADED | 有 active/reading/writing/waiting；无 upstream 时延 | B5-05 | 词表有 6 个 connections 名 |
| `nginx_server_requests_total`（L236） | 未接线 | UNWIRED | stub_status 不提供 per-server | Future | 词表无此名 |
| `container_cpu_usage_seconds_total`（L237） | 部分 | DEGRADED | 仅 `id="/"`；containerd 镜像存储导致无法枚举 | B5-04b（标注） | `count by(id)` = 1，id=`/` |
| `container_memory_working_set_bytes`（L238） | 部分 | DEGRADED | 同上 | B5-04b（标注） | 1 条，id=`/` |
| `container_last_seen`（L239） | 部分 | DEGRADED | 同上 | B5-04b（标注） | 1 条，id=`/` |
| `container_start_time_seconds`（L240） | 部分 | DEGRADED | 重启改用 `process_start_time_seconds` 派生 | B5-04b（标注） | 1 条 id=`/`；app restart`changes` 可用 |
| `container_network_receive_bytes_total`（L241） | 部分 | DEGRADED | 只有 root 的 6 个接口；per-container 不可得 | B5-04b（标注） | `count by(id,interface)` 全为 id=`/` |
| `container_network_transmit_bytes_total`（L242） | 部分 | DEGRADED | 同上 | B5-04b（标注） | 同上 |
| `container_oom_events_total`（L243） | 部分 | DERIVED | Docker inspect `.State.OOMKilled/.RestartCount` + 容器日志 | B5-04b | Prometheus 仅 root=0；inspect app-1 false/0 |
| Redis 角色/主从切换 Sentinel（L248） | 部分 | DEGRADED | 数据节点 role 可查；Sentinel quorum 仍走 `sentinel-status.sh` | B5-05 | `redis_sentinel_*` 0；`redis_master_link_up` 有 |
| `rabbitmq_up`（L249，重复行） | 未接线 | DERIVED | 同 L223，合并为一行 | B5-04b | `up{job="rabbitmq"}`=1 |
| 状态码分布 / upstream 响应时间（L250） | 未接线 | UNWIRED | stub_status 不提供；需 nginx-plus 或日志管道 | Future | 词表无状态码/upstream 名 |
| Toxiproxy 状态（L251） | 未接线 | UNWIRED | `chaos-proxy` profile 未开；仅状态 API | Future | 词表 grep `toxi`=0 |
| 每容器 CPU/内存/重启/健康（L252） | 部分 | DEGRADED | CPU/内存/网络降级；重启/健康可由 Docker inspect 派生 | B5-04b | 同上；inspect 有 `State.Health`、`RestartCount` |

### 1.7 熔断 / 限流 / 业务正确性（6）

| 指标键（字典行） | 现状态 | 决策 | 具体来源 / 做法 | 目标 | 证据 |
|---|---|---|---|---|---|
| `orders_total`（L259） | NO_DATA | DERIVED（别名） | scrape relabel 已改为 `orders_created_total`；字典注明即可 | B5-04b | `prometheus.yml:25-28`；`orders_created_total` 有序列 |
| `resilience4j_circuitbreaker_state_transitions_total`（L271） | 未接线 | DERIVED | `changes(resilience4j_circuitbreaker_state{name="$n"}[30m])` 按 state 求和 | B5-04b | 实机 6 个 state 值（每个 3 实例，共 18 条），无 transitions 名 |
| `resilience4j_ratelimiter_passed_calls_total`（L274） | 未接线 | DEGRADED | 无真实 passed 计数；只能用 `seckill_attempts_total{result="accepted"}` 近似，不能叫 passed | B5-05 | 词表只有 permissions/waiting_threads |
| `resilience4j_ratelimiter_rejected_calls_total`（L275） | 未接线 | DERIVED | `increase(seckill_attempts_total{result="rate_limited"}[5m])` | B5-04b | `result` 8 值齐全，含 `rate_limited` |
| 熔断切换次数（L276） | 未接线 | DERIVED | 同 L271，`sum(changes(...))` | B5-04b | 同上 |
| 限流通过/拒绝（L277） | 部分 | DEGRADED | 拒绝可派生；通过只能近似；`available_permissions` 有序列 | B5-05 | `resilience4j_ratelimiter_available_permissions`=50 |

## 2. B5-04b 优先实施清单（7 项，按价值排序）

### T1. k6 summary 阈值 + avg/med 进入台账/报告（WIRE + DERIVED）

- **现状**：`summary.json` 已有阈值和 avg/med；控制台只提供原样下载，`RunLedger.vue` 唯一相关文案是“报告尚未生成”（L534）。
- **实现**
  - 控制器新增 `GET /lab/runs/{runId}/k6/thresholds`（或扩展 `BenchExecutionState`）：复用 `BenchRunExecutor.report(runId)` 的 runId 安全/大小上限（`BenchRunExecutor.java:126-150,239-258`）。
  - 解析规则：遍历 `metrics.*.thresholds`，`crossed == true` 记为 FAIL（`bench-report.js:298-305`）；**不要**依赖 `k6.log`，`run-20261029-004` 的报告目录只有 `summary.json`（`ls /reports/run-20261029-004` 仅 1 文件）。
  - 同时返回 `metrics.http_req_duration.avg/med`（实跑 24.074/23.722 ms）。
  - UI：RunLedger 报告区新增“阈值明细/总判定”；报告卷无文件时显示“报告未生成”，有文件无阈值时显示“场景未配置阈值”，绝不显示 PASS。
- **暴露位置**：控制器只读端点 + RunLedger；不进 `catalog.yml`（无 PromQL 序列）。
- **缺失时质量**：`NO_SUMMARY` / `NO_THRESHOLDS`（UI 状态；映射到 `MetricQuality` 时为 `NO_DATA`）；`MetricQuality` 无 UNWIRED 枚举（`MetricQuality.java:18-23`）。
- **验收证据**：`curl -H X-Lab-Token ... /lab-api/lab/runs/run-20261029-004/k6/report` 200/4774 B；解析出 3 组阈值；单测用 `code/lab/k6/reports/neg-threshold/summary.json`（`flow_errors.rate<0=true`，必须判 FAIL）和 `run-20261029-004`（全 false，必须判 PASS）各一条。

### T2. Tomcat 线程池进目录与实例表（WIRE，抓取已存在）

- **现状（先验证）**：字典 4 行 Tomcat 已是 OK；实机 `job="seckill-app"` + `job="seckill-app-jdk8"` 共 4 target 全部有值。**B5-04b 不需要补抓取**；缺口只在目录/UI 未消费。
- **实现**
  - `catalog.yml` 新增（`groupByInstance: true`）：
    - `tomcat.threads.busy`：`max by(instance)(tomcat_threads_busy_threads{job=~"seckill-app|seckill-app-jdk8",name="http-nio-8080"})`
    - `tomcat.threads.current`：`tomcat_threads_current_threads`，同一 selector
    - `tomcat.threads.max`：`tomcat_threads_config_max_threads`，同一 selector
    - `tomcat.connections.current`：`tomcat_connections_current_connections`，同一 selector
    - `tomcat.connections.max`：`tomcat_connections_config_max_connections`，同一 selector
  - 使用率 = `busy/current` 或 `busy/max`，在 UI 层算；实例表/拓扑 tooltip 展示“Tomcat busy/current/max”。
  - 不把 Tomcat 与业务池 `executor_*` 混为一条曲线；两者标签分别是 `name="http-nio-8080"` 与 `name="business"`。
- **暴露位置**：`catalog.yml` + `GET /lab/metrics/window` + `InstanceMetricsTable.vue`/`ObservePanel.vue`。
- **缺失时质量**：`NO_DATA`（目标/实例缺失）；右沿在抓取间隔内为 `COLLECTING`；任何情况下都不补 0。
- **验收证据**：实机 busy=1、current=19/19/23、max=200、connections=2；单测断言 5 个 key 均返回 4 条序列（jdk8 profile 开时）；关 jdk8 后 3 条，缺失的是 jdk8 实例而不是 0。

### T3. 容器 CPU/内存/网络降级语义（DEGRADED，不重试 cAdvisor）

- **现状**：cAdvisor 只产出 `id="/"`；根因已定位为 Docker containerd 镜像存储（见 §0.3），不是抓取间隔或 `--docker_only` 配置错误。
- **实现**
  - 不新增 `container.*` 目录键（加了也只会是 NO_DATA/误导）。
  - 在拓扑/实例视图加静态 `degraded` 原因：`network/cpu/mem = "cAdvisor 无法枚举容器（containerd snapshotter）"`，节点灰色并显示“采集降级”，与“无数据”区分。
  - 重启/健康改走 Docker inspect（与 T4 共用）：`RestartCount`、`State.Health.Status`、`State.StartedAt`。
  - 可选 Future：给 cAdvisor 挂 containerd socket 或改用支持 containerd 的采集器；本轮不做。
- **暴露位置**：`TopologyService` 的 note/dataQuality + `TopologyView.vue`/`InstancesPanel.vue`；`catalog.yml` 不加键。
- **缺失时质量**：静态 `DEGRADED`（不是查询失败导致的 DEGRADED）；禁止把 root 接口值当容器值。
- **验收证据**：`docker info` 的 `io.containerd.snapshotter.v1`；cAdvisor 日志 `layerdb/.../mount-id: no such file`；`count by(id)(container_network_receive_bytes_total)` 全为 `/`；UI 断言网络显示“采集降级”且不显示 0。

### T4. OOM / 堆转储 / 重启事件（WIRE，本轮最有价值的“事件”缺口）

- **现状**：app-1 `-XX:+ExitOnOutOfMemoryError`、`OOMKilled=false`、`RestartCount=0`；`gcLogEnabled=false`、`heapDumpOnOutOfMemory=null`；GC 日志文件是 07:35 的陈旧文件。当前无法观测堆转储事件。
- **实现**
  - 控制器新增只读 `GET /lab/instances/{instance}/diagnostics`（或扩展 `EffectiveInstanceView`）：返回 `oomKilled`、`restartCount`、`startedAt`、`health`、`gcLogEnabled`、`heapDumpOnOutOfMemory`、`heapDumpPath`、`lastOomLogLine`。
  - 数据源：`DockerClient.inspectContainerCmd`（`DockerInstanceRebuildGateway.java:326` 已有用法）+ `docker logs --tail N` 扫描 `OutOfMemoryError|Dumping heap`；GC 端点已有（`InstanceLogsController.java:19`）。
  - 若启用 dump：`InstanceConfigValidator.java:310-312` 会生成 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=...`；文件落地后由诊断端点列目录，不读文件内容。
  - 默认不自动打开：dump 会写盘并可能拖慢容器；在实例配置页显示“未启用”，由 B5-05 演练前按需开启。
- **暴露位置**：控制器端点 + 实例页“诊断”卡 + RunLedger 事件时间线（可选）。
- **缺失时质量**：`DISABLED`（gcLog/heapDump 关）；`NO_DATA`（启用但窗口无事件）；`OK`（有 OOM/重启事件）。重启 `changes` 的 0 是真实零（窗口内无重启）。
- **验收证据**：实机 `docker inspect` false/0；`/lab/instances/app-1/logs/gc` 返回 `DISABLED`；单测用 fake ContainerFacts `OOMKilled=true` 断言 `OK` + 事件行；**不做真实 OOM 注入**。

### T5. JVM 实际参数回读进实例页（DERIVED，B4 已有端点）

- **现状**：`GET /lab-api/lab/instances/app-1/effective` 已返回 `javaToolOptions`、`imageId`、`readback=OK`、`observability=FULL-OBSERVABILITY`；`jcmd 1 VM.command_line` 与 env 一致；JDK8 为 `READBACK_LIMITED`。
- **实现**
  - 不新增 Prometheus `jvm_parameters_info`：参数只在重启时变化，B4 API 已能回读，重复建 info 序列没有增益。
  - 在实例配置页展示 effective 的 `javaToolOptions` + `imageId` + `containerStartedAt`（Docker `State.StartedAt`）+ `config revision`（实机 27）。
  - 把 `jcmd VM.command_line` 作为人工核验证据写入 step 文档，不作为 API 依赖（JDK8 无同等回读）。
- **暴露位置**：`InstanceConfigEditor.vue` / `InstancesPanel.vue`，数据走 `InstanceConfigController.java:58`。
- **缺失时质量**：`READBACK_UNAVAILABLE`（Docker gateway 不可用）；JDK8 `READBACK_LIMITED`；不得用存储草稿冒充运行值。
- **验收证据**：端点 200 的 `javaToolOptions` 与 `jcmd VM.command_line` 逐项一致；app-1 `imageId=sha256:408b...`、revision=27；JDK8 断言 `READBACK_LIMITED`。

### T6. MySQL Router：确认无 Prometheus 端点，标 UNWIRED（不实现）

- **现状**：8443 有 HTTP server；`/metrics` 404；`/api/20190715/router/status`、`/routes` 均 401；`prometheus.yml` 无 router job。
- **实现**：不新增 job、不写自定义 exporter（需 Router 凭据与 8443 曝光，超出“只读补采”范围）。拓扑 Router 节点 tooltip 固定显示“未接线（无 /metrics）”，读路径证据仍用 `hikaricp_connections_*{pool}`。
- **暴露位置**：字典 L205 + 拓扑 note；不进 catalog。
- **缺失时质量**：`UNWIRED`（静态），不是 `NO_DATA`，更不能显示 0。
- **验收证据**：容器内 `wget https://mysql-router:8443/metrics` → 404；`/api/20190715/router/status` → 401；`grep job_name prometheus.yml` 无 router。

### T7. 字典/目录的 7 处存量纠正（WIRE，零采集）

按 §0.2 逐行改字典状态，并在 B5-04b 同步：
1. L176/L177 → `cache_l1_estimated_size`，OK；目录 `cache.l1.entries` 保持不变。
2. L178 → “已接线（`/lab/cache/l1` 按实例 API）”；遗留 `/lab/toggles` 仍 404，不恢复。
3. L181 → `redis_db_keys`，OK；`cache.l2.keys` 已存在。
4. L222 → `redis_instance_info{role}`，OK；删除“无 `redis_role`”作为缺口的表述。
5. L223/L249 合并 → `up{job="rabbitmq"}`，DERIVED。
6. `catalog.yml:110-112` 的 `executor.rejected` 注释改为“已接线（B4-01）”；实机 3 条序列已可查。
7. L109 应用重启 → `changes(process_start_time_seconds[1h])`，DERIVED（§0.2 第 7 行）。
- **验收证据**：改后 `grep -n` 对应行；`/lab/metrics/window?keys=cache.l1.entries,cache.l2.keys,executor.rejected&from=...&to=...` 返回 `quality=OK`；A22 不因“曾经 NO_DATA”误判。

## 3. 不值得接线 / 不重复造（供 B5-05 引用）

| 项 | 一句话理由 | 结论 |
|---|---|---|
| MySQL deadlocks | MySQL 8.4 已移除 `Deadlocks` 状态，exporter 无源；`innodb_row_lock_*` 已覆盖锁等待 | UNWIRED/Future |
| MySQL 查询/事务耗时直方图 | exporter 无 histogram，perf_schema 未开；app 侧 HTTP/`order_accept` 已足够 | UNWIRED/Future |
| MySQL TPS `com_commit` | exporter 当前不导出 com_*；用 questions 近似并明确标注，重开 collector 需重启 exporter，收益低 | DEGRADED |
| MySQL Router 原生指标 | 无 `/metrics`，REST 401 需凭据；自定义 exporter 成本 > 演示价值 | UNWIRED/Future |
| Redis Sentinel quorum | Sentinel 说 RESP 非 HTTP，B0-07 有意不抓；`sentinel-status.sh` 已给演练证据 | DEGRADED |
| Toxiproxy 指标 | `chaos-proxy` profile 与 ha/db-ha 互斥，本轮未开；状态 API 足够 | UNWIRED/Future |
| Nginx 状态码/upstream/`server_requests` | stub_status 只有 9 个基础名；需 nginx-plus 或日志管道 | UNWIRED/Future |
| 每容器 CPU/内存/网络 | cAdvisor 在 containerd 镜像存储下无法枚举，重试/调参无效 | DEGRADED |
| JVM RSS | app 不导出 `process_resident_memory_bytes`，cAdvisor 不可用；heap used ≠ RSS，不能替代 | DEGRADED |
| 快照 P95 / Hikari 获取 P95 | Timer 未开直方图；开 distribution histogram 属低收益配置变更，`_max` 已够演示 | DEGRADED |
| `thread_lab_injected_total{hold}` | 加标签属 app 指标 schema 变更，A 类用例不依赖 auto/manual 区分 | DEGRADED |
| `rabbitmq_up` / `orders_total` / `redis_role` 造新指标 | 通用 `up`、relabel 别名、`redis_instance_info{role}` 已等价 | 不新增 |
| k6 expected 409/429 | 不是未接线：名字在词表，24h 无 run 才是 NO_DATA；跑 spike 即自愈 | 不新增 |
| Tomcat 抓取 | 已 OK；只缺目录/UI，不是采集缺口 | 不重做 |

## 4. 执行顺序与 B5-04b 完成定义

1. T7（纯文档/目录纠正，先做，避免后续验收拿旧状态）。
2. T1（k6 阈值/avg/med，控制器 + UI）。
3. T2（Tomcat 目录 + 实例表）。
4. T4（OOM/重启诊断，Docker inspect + 日志）。
5. T5（effective 回读进实例页）。
6. T3（拓扑降级标注）。
7. T6（Router UNWIRED 文案/证据）。

完成定义（建议）：
- 字典 50 条全部落到 `WIRE/DERIVED/UNWIRED/DEGRADED` 之一，无“未定论”；
- `catalog.yml` 新增 Tomcat 5 键，注释与实机一致；`GET /lab/metrics/window` 对 5 键返回 OK/NO_DATA，不补 0；
- `/lab/runs/{runId}/k6/thresholds` 对 `run-20261029-004` 返回 3 组 PASS，对 `neg-threshold` 归档返回 FAIL；
- 实例诊断端点返回 OOMKilled/restart/gcLog/heapDump 状态，关闭时 `DISABLED`；
- effective 页展示运行参数且与 `jcmd` 一致；
- 拓扑对 Router 显示“未接线”、对容器网络显示“采集降级”，均不显示 0；
- 只跑单测 + 只读查询；不触发 OOM、不重启容器、不改 compose。

## 5. B5-04b 实施结果与残留决策（2026-10-29）

已实施（控制器侧；UI 由另一会话负责）：

| 项 | 落点 | 验收 |
|---|---|---|
| 字典/目录纠正 | `指标字典.md`、`resources/metrics/catalog.yml` | 头部 174 OK/21 部分/16 未接线/3 NO_DATA/5 已接线（219 行）；`cache.l1.entries`/`cache.l2.keys`/`executor.rejected` 注释与实机一致 |
| k6 阈值 + avg/med | `GET /lab/runs/{runId}/k6/thresholds`（`BenchThresholdService`） | 解析 `run-20261029-004`：server/flow 与 `http_req_duration{flow:product}` 共 4 条全 PASS；`neg-threshold` 的 `crossed=true` 判 FAIL；无报告 404、超限 413、无阈值 `NO_THRESHOLDS` |
| Tomcat 5 键 | `catalog.yml` `tomcat.*` | 实机 `tomcat_threads_busy_threads/current/config_max`、`tomcat_connections_current/config_max` 均 4 target，`name="http-nio-8080"`；无抓取改动 |
| JVM 实际参数 | `GET /lab/instances/{instance}/effective` 增加 `jvmFlags`/`jvmReadback` | JDK21 容器内 `jcmd 1 VM.flags` 回读（超时/字节上限外置）；JDK8 固定 `READBACK_UNAVAILABLE`，不执行 jcmd |
| OOM/堆转储事件 | `GET /lab/instances/{instance}/events`（`InstanceEventsService`） | 扫描有界日志 `OutOfMemoryError`/`Heap dump file created`/`Dumping heap to <path>`；无匹配空列表 + `NO_DATA`；读不到日志 `UNAVAILABLE` |
| Router | 无代码/无抓取 | 容器内 `/metrics` 404、`/api/20190715/router/status|/routes` 401、`prometheus.yml` 无 router job；拓扑/字典固定 UNWIRED，禁止显示 0 |

残留“不值得接线”的最终决定（B5-05 直接引用）：

| 项 | 最终决定 | 理由 |
|---|---|---|
| MySQL deadlocks | UNWIRED/Future | MySQL 8.4 已移除状态变量；`innodb_row_lock_*` 覆盖锁等待 |
| MySQL 查询/事务耗时直方图 | UNWIRED/Future | exporter 无 histogram、perf_schema 未开；app HTTP/`order_accept` 已够 |
| MySQL TPS `com_commit` | DEGRADED | 用 questions 近似并标注“非 TPS”，不重开 collector |
| MySQL Router 原生指标 | UNWIRED/Future | 8443 `/metrics`=404、REST=401；自定义 exporter 成本高于演示价值 |
| Redis Sentinel quorum | DEGRADED | Sentinel 为 RESP 非 HTTP、B0-07 有意不抓；`sentinel-status.sh` 提供证据 |
| Toxiproxy 指标 | UNWIRED/Future | `chaos-proxy` profile 未开；状态 API 足够 |
| Nginx 状态码/upstream | UNWIRED/Future | stub_status 只有 9 个基础名，需 nginx-plus 或日志管道 |
| 每容器 CPU/内存/网络 | DEGRADED | cAdvisor v0.49.1 在 containerd snapshotter 下无法枚举容器，重试/调参无效 |
| JVM RSS | DEGRADED | app 不导出 `process_resident_memory_bytes`；heap used ≠ RSS，不得冒充 |
| 快照/Hikari 获取 P95 | DEGRADED | Timer 未开直方图；`_max/_count` 已满足演示 |
| `thread_lab_injected_total{hold}` | DEGRADED | 加标签属 app 指标 schema 变更，收益低于风险 |
| `rabbitmq_up`/`orders_total`/`redis_role` 造新指标 | 不新增 | 通用 `up`、relabel 别名、`redis_instance_info{role}` 已等价 |
| k6 expected 409/429 | 不新增 | 名在词表，24h 无 run 是 NO_DATA，跑 run 即自愈 |
| Tomcat 抓取 | 不重做 | 已 OK，只缺目录/UI（B5-04b 已补目录键） |

配置外置（compose/.env 由主会话转发，本次不改）：
`LAB_INSTANCE_JVM_FLAGS_TIMEOUT_SECONDS`、`LAB_INSTANCE_JVM_FLAGS_MAX_BYTES`、
`LAB_INSTANCE_EVENTS_LOG_TAIL`、`LAB_INSTANCE_EVENTS_MAX_EVENTS`、
`LAB_INSTANCE_EVENTS_LINE_MAX_CHARS`。

