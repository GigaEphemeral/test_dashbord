# 步骤交接 · Step 41 D7 双 JDK 对比（JDK 8 外壳接入 compose / Prometheus / 面板 / k6）

> 任务 / 步骤：M5 双 JDK（设计 §10），D7 面板与双目标压测接线
> 日期：2026-10-27
> 分支 / HEAD：`feature/ha-lab` / `7045727`（feat）+ 本文档的 `docs:` 提交
> 关联任务：上游 Step 33（JDK 8 外壳，`doc/process/Stage1/step-33-jdk8-shell.md`）；设计 §10.1/§10.2、§6 D7 面板表；下游：JVM 调优台、双 JDK 正式基准归档
> 测试 / verify：`cd code && mvn -pl services/seckill-app-jdk8 -am test` → **BUILD SUCCESS，模块 Tests run: 11, Failures: 0, Errors: 0, Skipped: 0**（基线 11 例，本步未新增应用代码，因此没有新的单测）；compose 服务数 **默认 16 / `--profile ha` 25 / `--profile db-ha` 25 / `--profile dual-jdk` 17**
> 运行状态：jdk8 镜像已在 Docker 实跑（Temurin **1.8.0_504**），`APP_PROFILE=mysql` 连共享 MySQL 通过；两侧 k6 读场景均通过阈值；D7 PromQL 两侧都有数据；验证结束后 **JDK 8 容器已 stop**，默认 16 个服务保持运行
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- D7 的四个缺口全部接上：① jdk8 有镜像与 profile 门控的 compose 服务；② Prometheus 新 job `seckill-app-jdk8`；③ D7 面板 `ha-d7.json`（11 个 panel）；④ 无登录依赖的 k6 场景 `d7.js`。
- **启动耗时没有新增自定义 gauge**：实测发现 Boot Actuator 自带 `application_ready_time_seconds`（`StartupTimeMetricsListener`），Boot 2.7 / Micrometer 1.9 与 Boot 3.5 / Micrometer 1.15 都导出，且值分别与两端启动日志吻合。按任务约定“无指标才加 gauge”，这里选择**省略并说明原因**，避免与内置 meter 重复（第一次实现时自建 `StartupTimeMetrics` 直接与 Boot 自动配置的 bean `startupTimeMetrics` 撞名，`BeanDefinitionOverrideException` 证实内置实现存在）。
- 复核 Step 33 的三条约束均已落实：新 job 名匹配 `seckill-app.*`；D7 用独立只读场景绕开 `/api/login`；不依赖 `jvm_info`；两侧 `-Xmx512m` 对齐。
- 发现并记录：JDK 21 侧真实启动明显慢于 JDK 8（本机同限额下 ready 约 31–32s vs 9.4s），与“JDK 8 更慢”的直觉相反；原因是 JDK 21 外壳要初始化 Redis 哨兵、RabbitMQ、Flyway、缓存等完整实验栈，JDK 8 只做裸读路径。D7 的吞吐/P99 对比也只能在这个前提下解读。

# 完成功能项

## 1. JDK 8 镜像（`code/services/seckill-app-jdk8/Dockerfile`）

- 多阶段：`maven:3.9-eclipse-temurin-21` 构建（模块 `maven.compiler.release=8`，class 版本 52）→ `eclipse-temurin:8-jre-alpine` 运行。
- 运行时实测 `openjdk version "1.8.0_504"`（Temurin），镜像内 `wget` 存在（healthcheck 依赖），非 root 用户 `app`。
- 与 JDK 21 镜像**同版本 OTel Java Agent 2.10.0**（`-javaagent`），否则启动耗时对比会因一侧无探针而失真；agent 支持 Java 8，实跑无错误。
- 入口只保留 `-javaagent` 与 `-XX:+ExitOnOutOfMemoryError`；堆上限由 compose 的 `JAVA_TOOL_OPTIONS` 注入（外置，可改 `.env`/环境变量后重建容器，无需改镜像）。
- 镜像大小实测：`java-learn-lab/seckill-app:jdk8` **469MB**（JDK 21 侧 674MB）。

## 2. Compose 服务（`code/lab/docker/compose.base.yml`）

- 新服务 `seckill-app-jdk8` / 容器 `jl-seckill-app-jdk8`，`profiles: ["dual-jdk"]`，因此默认渲染仍是 16 个服务；带 profile 时 17 个。
- 无宿主端口，仅 `jl-net`，应用内端口 8080；健康检查 `GET /actuator/health`（不是 `/actuator/health/app`，因为 root 还会如实报告 DB）。
- `mem_limit: ${JDK8_MEM_LIMIT:-768m}`，与 JDK 21 实例的 768m 相同。
- 数据源：`APP_PROFILE=${JDK8_APP_PROFILE:-mysql}` + 共享 `MYSQL_*`，与 JDK 21 读同一份 Flyway 种子；需要零依赖启动样本时可 `JDK8_APP_PROFILE=demo`（H2）。
- 观测链路：`OTEL_SERVICE_NAME=seckill-app-jdk8`、OTLP 到 `otel-collector:4317`，metrics 仍走 Actuator，与 JDK 21 一致。
- 可覆盖项（不新增 `.env` 必填键，均有默认值）：`JDK8_APP_PROFILE`、`JDK8_MEM_LIMIT`、`JDK8_JAVA_TOOL_OPTIONS`、`JDK8_INSTANCE_ID`、`APP_JAVA_TOOL_OPTIONS`。
- 为对齐堆上限，`x-app-env` 增加 `JAVA_TOOL_OPTIONS: "${APP_JAVA_TOOL_OPTIONS:--Xmx512m}"`（作用于 app-1/2/3）。

## 3. 脚本（`code/lab/scripts/up.sh`、`down.sh`）

- `up.sh` 新增 `--profile dual-jdk`：base+dev 渲染 + `--profile dual-jdk`，17 个服务；沿用原“profile 追加服务逐个检查 running/unhealthy”的逻辑（原 ha 专用代码泛化为 `ha | dual-jdk`）。
- 未知 profile 仍显式拒绝；组合串（`ha,dual-jdk`、`ha+dual-jdk`）仍报错并说明约束；`db-ha` 仍指向 `db-ha-up.sh`。
- `down.sh` 的 down / down --purge 都追加 `--profile dual-jdk`，避免停止状态的 JDK 8 容器在 teardown 后残留。
- `up.sh` 的 dual-jdk 结尾摘要给出服务名、profile、堆参数、Prometheus job、D7 uid 与两条 k6 命令。

## 4. Prometheus（`code/lab/docker/prometheus/prometheus.yml`）

```yaml
- job_name: seckill-app-jdk8
  metrics_path: /actuator/prometheus
  scrape_interval: 5s
  static_configs:
    - targets: ["seckill-app-jdk8:8080"]
```

- job 名刻意匹配 D1/D2 的 `job=~"seckill-app.*"`（`$instance` 来源），D4 用精确 `job="seckill-app"` 不受影响；D7 同时按 job 正则与 `app` 标签过滤。
- profile 停止时该 target 为 down（`up=0`），与 db-ha exporter 同处理方式；D7 的说明文字写明“down 是预期态”。

## 5. D7 面板（`code/lab/docker/grafana/dashboards/ha-d7.json`，uid `ha-d7`）

11 个 panel：Targets up、Runtime identity（`app` / `jvm_version` 表）、Startup（bargauge）、Throughput、P95、P99、GC 暂停时间+频率、Heap used、Live threads、Process CPU、指标缺口说明（text）。

PromQL（变量 `$app` 来自 `label_values(jvm_memory_used_bytes{job=~"seckill-app.*"}, app)`，默认 All）：

```promql
# 吞吐
sum by (app) (rate(http_server_requests_seconds_count{job=~"seckill-app.*", uri!~"/actuator.*", app=~"$app"}[1m]))
# P95 / P99
histogram_quantile(0.95, sum by (le, app) (rate(http_server_requests_seconds_bucket{job=~"seckill-app.*", uri!~"/actuator.*", app=~"$app"}[1m])))
histogram_quantile(0.99, sum by (le, app) (rate(http_server_requests_seconds_bucket{job=~"seckill-app.*", uri!~"/actuator.*", app=~"$app"}[1m])))
# GC（1.9/1.15 都有）
sum by (app) (rate(jvm_gc_pause_seconds_sum{job=~"seckill-app.*", app=~"$app"}[1m]))
sum by (app) (rate(jvm_gc_pause_seconds_count{job=~"seckill-app.*", app=~"$app"}[1m]))
# 堆 / 线程 / CPU
sum by (app) (jvm_memory_used_bytes{job=~"seckill-app.*", area="heap", app=~"$app"})
sum by (app) (jvm_threads_live_threads{job=~"seckill-app.*", app=~"$app"})
sum by (app) (process_cpu_usage{job=~"seckill-app.*", app=~"$app"})
# 启动（内置指标，勿用 jvm_info）
max by (app) (application_ready_time_seconds{job=~"seckill-app.*", app=~"$app"})
# 运行时身份
count by (app, jvm_version) (jvm_memory_used_bytes{job=~"seckill-app.*", area="heap", app=~"$app"})
```

指标缺口（面板 text panel 已写明，不伪造）：

- `jvm_info`：仅 Micrometer 1.15（JDK 21）导出；Micrometer 1.9（JDK 8）没有。运行时代际统一用每条序列的 `jvm_version` 标签（实测 `1.8.0_504` / `21.0.12.1`）。
- `jvm_gc_overhead`、`jvm_memory_usage_after_gc`、`process_cpu_time_ns_total`：仅 JDK 21 侧有，D7 不使用。
- 两侧共有并已核验：`http_server_requests_seconds_*`、`jvm_gc_pause_seconds_*`、`jvm_memory_used_bytes`、`jvm_threads_live_threads`、`process_cpu_usage`、`application_ready_time_seconds`。

## 6. k6（`code/lab/k6/scenarios/d7.js`、`run.sh`、`README.md`）

- `d7.js`：与 `load.js` 相同的 ramping-vus 阶段与阈值，但**不调用 `ensureLogin()`**，只做 `product()` 读；这样 JDK 8 侧不会因 `POST /api/login` 404 每 VU 每轮记一次 `flow_errors`。
- `run.sh` 白名单加入 `d7`；`README.md` 补 D7 场景表、说明与命令。
- 选择“新场景”而不是“给 jdk8 补 mock login”：不破坏 JDK 8 外壳“HTTP 面 = products + hello + actuator”的既有裁决，也不改 JDK 21 的登录语义。

## 7. 启动耗时：使用内置指标，不新增 gauge

- 指标：`application_ready_time_seconds`，Boot Actuator `StartupTimeMetricsListener` 自动注册，含义为 Spring 应用启动到 `ApplicationReadyEvent`。
- 实测 JDK 8：`8.806`（首次抓取）/`9.371`（重启后），同进程日志 `Started ... in 8.777/9.332`。JDK 21：`31.359/31.975/32.133`，日志 `Started ... in 30.066/30.517/30.642`。
- ready 指标比 “Started” 日志略大是正常的：日志在 `ApplicationStartedEvent` 打印，`ApplicationReadyEvent` 还要等 runners/就绪（JDK 21 侧差约 1.3s，JDK 8 侧差约 0.04s）。
- 因此**没有** `app_startup_seconds` 自定义 gauge，也没有新增测试；D7 面板 text panel 与本文档说明原因。

# 设计逻辑

- **为什么 profile 门控而不是常驻**：一次 D7 演示需要 JDK 8 与 JDK 21 同时被 scrape；但日常 16 服务栈不需要多一个 EOL 运行时。`profiles: ["dual-jdk"]` 让默认渲染保持 16，`up.sh --profile dual-jdk` 明确得到 17，`down.sh` 也不会漏删。
- **为什么 job 名必须是 `seckill-app-jdk8`**：D1/D2 的 `$instance` 变量来自 `up{job=~"seckill-app.*"}`，Prometheus 默认 `honor_labels=false` 会用抓取目标覆盖应用 `instance` 标签。新 job 匹配该正则，JDK 8 实例才能出现在现有 JVM 面板；D7 用 `app` + `job` 过滤，不依赖被覆盖的 `instance`。
- **为什么用 `mysql` profile 而不是 H2**：设计 §10.2 要求“同一套依赖服务”。H2 会让 JDK 8 吞吐被内存库抬高，对比失真。`mysql` profile 读 JDK 21 侧 Flyway 建好的同一批表；本步已真机验证 `ddl-auto=validate` 通过，Step 33 遗留的“未连活体 MySQL”风险关闭。
- **为什么两侧都挂 OTel agent、都 `-Xmx512m`**：设计 §10.2 要求变量唯一。JDK 21 入口有 `-XX:MaxRAMPercentage=70` + agent；JDK 8 用 `JAVA_TOOL_OPTIONS=-Xmx512m`，JDK 21 也用同一环境变量覆盖 `MaxRAMPercentage`（Xmx 优先），agent 版本同为 2.10.0。JDK 8 的 `-XX:+ExitOnOutOfMemoryError` 与 JDK 21 对齐。
- **为什么读路径仍不可完全对齐**：JDK 21 的 `/api/products/{id}` 走 Caffeine L1 + Redis L2，JDK 8 直读 MySQL。D7 的吞吐/P99 差值包含缓存效应，不只是 JVM 差异；面板 text panel 已写明。要纯 JVM 对比，需要 JDK 8 侧补 L2 或 JDK 21 侧关缓存（调优台后续可做）。
- **启动指标语义**：`application_ready_time_seconds` 是 Spring 应用启动到 ready，不含 JVM/agent 冷启动；`Started ... (JVM running for ...)` 的第二个数字含 JVM 启动。两者都记录，D7 用前者做主对比，避免把容器/JVM 启动抖动混进来。
- **外置**：端口、profile、mem limit、堆参数、OTel endpoint 全部 env 可覆盖；k6 的 VU/时长/阈值沿用 `lib/config.js` 既有变量，没有新的硬编码。

# 存在缺陷项&TODO

本步**没有做**（明确边界）：

- **控制台 UI 无 D7 入口**：`code/lab/dashboard` 未改动；D7 只能从 Grafana 打开（uid `ha-d7`），启动/停止 JDK 8 仍用 compose/`docker stop`。
- **JDK 8 侧无线程实验室/快照/toggles/缓存/登录/订单/秒杀/异步**：与 Step 33 的裁决一致，D7 只能比“裸读路径 + JVM/GC/堆/线程/启动”，不能比线程生命周期、快照、缓存防御或业务写路径。
- **未跑 soak / 长稳态**：本步只跑 40s 短读，未验证 30m 内存曲线、GC 稳定性与连接池泄漏。
- **未给 Nginx 加 jdk8 upstream**：按任务“不改 Nginx”，JDK 8 走 `http://seckill-app-jdk8:8080` 直连；JDK 21 仍走 `http://nginx`。
- **D1/D2 的副作用**：job 名匹配 `seckill-app.*` 后，`jl-seckill-app-jdk8` 停止时 D1/D2 的 `$instance` 下拉里会保留一个 `up=0` 的红色目标；这是静态 target 的预期行为（D7 已注明“down 是预期态”），不改 D1/D2/D4。
- **启动样本量**：JDK 8 为 3 次串行重启（8.777/9.423/9.332，中位 9.332），JDK 21 为 3 个实例一次并发重建（30.066/30.517/30.642，中位 30.517）。并发重建有 CPU 争用，严格基准应逐实例串行、多轮取中位后写入 `doc/process/Stage1/benchmark/jvm-comparison/`。
- **k6 数字不是饱和吞吐**：`THINK_TIME=1s` 下 100 VU 约 73 req/s，是被思考时间限制的读压测，不是容量拐点；正式对比应沿用 acceptance 的 2m/200VU 并归档。

**无法验证 / 未验证清单**：

- 未在本机混跑 `ha`/`db-ha` overlay 的状态下执行 `up.sh --profile dual-jdk` 全流程：`up.sh` 的 dev 渲染会重建 app-1/2/3 并丢掉 `compose.ha.yml` 的 Sentinel env/网络，属于既有 overlay 语义，不是本步引入。已验证的是：compose 四种渲染计数（16/25/25/17）、`bash -n`、profile 分支代码路径、以及等价的 `docker compose --profile dual-jdk up -d --wait seckill-app-jdk8` 真机启动。
- 未用浏览器逐面板截图核对 Grafana 渲染；只通过 Grafana API 确认 uid `ha-d7` 已 provision（11 panels、URL `/d/ha-d7/...`）并用 Prometheus API 验证每条 PromQL 两侧有数据。
- 未验证 `application_ready_time_seconds` 在 `APP_PROFILE=demo` 容器下的值；本步所有 jdk8 数字都来自 `mysql` + OTel agent + `-Xmx512m`。
- 未测 JDK 8 下 GC 日志/堆转储（运行镜像是 JRE，无 `jcmd`；需要时改用 JDK 8 基础镜像另建调试镜像）。
- 未提交任何 `.env` 变更；`.env` 仍被 gitignore，未新增必填键。

# 待定事项和注意事项

1. **怎么跑一次完整对比**（在**dev**栈上）：
   ```bash
   bash code/lab/scripts/up.sh --profile dev          # 16 服务
   bash code/lab/scripts/up.sh --profile dual-jdk     # 17 服务（含 jl-seckill-app-jdk8）
   # Grafana -> D7 (uid ha-d7)，时间窗覆盖两次 k6
   LOAD_DURATION=2m bash code/lab/k6/run.sh d7
   BASE_URL=http://seckill-app-jdk8:8080 LOAD_DURATION=2m bash code/lab/k6/run.sh d7
   docker stop jl-seckill-app-jdk8                    # 回到 16 服务
   ```
   若当前栈带 `ha`/`db-ha` overlay，不要直接跑 `up.sh --profile dual-jdk`（会按 dev 渲染重建 app 层）；用 `docker compose ... --profile dual-jdk up -d seckill-app-jdk8` 只起 JDK 8 服务，或先回 dev 栈。
2. **对齐参数**：JDK 8 `java -javaagent:... -XX:+ExitOnOutOfMemoryError -Xmx512m -jar app.jar`；JDK 21 `java -javaagent:... -XX:MaxRAMPercentage=70 -XX:+ExitOnOutOfMemoryError -Xmx512m -jar app.jar`（Xmx 覆盖 MaxRAMPercentage）。内存限额均 768m。
3. **启动耗时查看**：D7 右上 bargauge 取 `max by (app)`；要逐实例看用 `application_ready_time_seconds{job=~"seckill-app.*"}`。容器重启才会产生新样本。
4. **数据状态**：`demo` 是 H2 内存库，进程退出即消失；compose 默认 `mysql` 读共享库，k6 只读不消耗库存，无需 reset。JDK 8 容器停止后 Prometheus 该 target 变为 down，D7 仍保留最后一次启动样本（gauge 不会因 target down 立刻消失，5m 后随 stale marker 处理）。
5. **安全边界**：JDK 8 外壳 EOL，仅本机教学；无鉴权、无 `/lab/**`、无宿主端口。Docker 只动了 `label=lab=java-learn` / project `java-learn-lab` 的容器，未执行 `down.sh --purge`、未删卷、未改 Nginx/chaos-controller/缓存/线程代码。

# 附录

## 实测启动耗时（本机，2026-10-27）

| 运行时 | 样本（`Started` 日志，s） | 中位 | `application_ready_time_seconds`（抓取值） | `JVM/process running for`（s） |
|---|---|---|---|---|
| JDK 8（Temurin 1.8.0_504, mysql, agent, -Xmx512m） | 8.777 / 9.423 / 9.332 | **9.332** | 8.806（首启）/ 9.371（重启后） | 17.337 / 18.879 / 18.652 |
| JDK 21（21.0.12.1, 三实例并发重建, agent, -Xmx512m） | 30.066 / 30.517 / 30.642 | **30.517** | 31.359 / 31.975 / 32.133 | 40.494 / 40.440 / 40.571 |

## k6 短读结果（40s，20→100 VU，THINK_TIME=1s）

| 目标 | iterations | RPS | P95 | P99 | errors |
|---|---|---|---|---|---|
| JDK 21 via `http://nginx` | 2990 | 72.95 | 5.62ms | 8.58ms | 0% |
| JDK 8 via `http://seckill-app-jdk8:8080` | 2989 | 72.99 | 6.47ms | 9.72ms | 0% |

两侧阈值 `flow_errors<1%`、`server_errors<1%`、`p(95)<300ms`、`p(99)<800ms` 全部通过。

## live 证据

- `docker exec jl-nginx wget -qO- http://seckill-app-jdk8:8080/actuator/health` → `{"status":"UP","groups":["app","liveness","readiness"]}`。
- Prometheus `up{job="seckill-app-jdk8"}` = 1；`count by (job, jvm_version) (... jvm_version=~"1.8.*")` = 185 条序列，`jvm_version="1.8.0_504"`。
- D7 全部 PromQL 在 `job=~"seckill-app.*"` 下返回两个 `app` 序列（`seckill-app`、`seckill-app-jdk8`）。
- Grafana API `/api/dashboards/uid/ha-d7` → HTTP 200，11 panels。
- JDK 8 抓取无 `jvm_info`；`jvm_gc_pause_seconds_*`、`application_ready_time_seconds`、`jvm_threads_live_threads`、`process_cpu_usage` 均在。

## 关键文件

- `code/services/seckill-app-jdk8/Dockerfile`
- `code/lab/docker/compose.base.yml`（`seckill-app-jdk8`、`JAVA_TOOL_OPTIONS`）
- `code/lab/docker/prometheus/prometheus.yml`（job `seckill-app-jdk8`）
- `code/lab/docker/grafana/dashboards/ha-d7.json`
- `code/lab/k6/scenarios/d7.js`、`code/lab/k6/run.sh`、`code/lab/k6/README.md`
- `code/lab/scripts/up.sh`、`code/lab/scripts/down.sh`
