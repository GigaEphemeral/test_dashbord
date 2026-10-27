# 步骤 54 · 快照可观测性收口（JDK 8 annotation + D6 快照面板）

> 步骤交接 · §9.3 快照在双运行时上的观测补齐
> 日期：2026-10-27
> 代码基线：`0c479be`（`feature/ha-lab`，step-53 之后）
> 交付：`code/services/seckill-app-jdk8/**`（HttpURLConnection 版 annotator、配置、指标、测试）、`code/lab/docker/compose.base.yml`（仅 jdk8 服务 env）、`code/lab/docker/grafana/dashboards/ha-d6.json`（新增 5 个面板，原 12 个未动）、`code/lab/docclaims/claims.yml`、本文档。
> 验证：`cd code && JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1" mvn -pl services/seckill-app-jdk8 -am test` → **59 tests, 0 failures / 0 errors（46 基线 + 13 新增），BUILD SUCCESS**；`python -c "import json;json.load(open('.../ha-d6.json'))"` → OK；`node code/lab/scripts/check-doc-claims.mjs` → 全部 PASS；真机 annotation、双运行时 Prometheus 序列与 D6 PromQL 均有数据（见 §3）。

<!-- live-capture-verified: jdk8-snapshot-annotation host=doc/process/snapshots/20261027-070040-038_app-jdk8 annotation=79 tags=snapshot,thread-lab,app-jdk8,manual -->

## 1. JDK 8 annotation 契约

### 1.1 为什么需要单独实现

JDK 21 的 `HttpGrafanaSnapshotAnnotator` 使用 `java.net.http.HttpClient`（Java 11+），JDK 8 无法加载。本步在 `seckill-app-jdk8` 的 `lab/snapshot` 包新增：

- `SnapshotAnnotator`：与 JDK 21 同名的函数式接口，约定“只在 bundle 完成后调用、绝不抛异常”。
- `HttpGrafanaSnapshotAnnotator`：`HttpURLConnection` 实现，wire contract 与 JDK 21 完全一致：
  - `POST {grafana}/api/annotations`，Basic Auth（`GRAFANA_ADMIN_USER/PASSWORD`，只从环境读取，日志与正文都不含凭据）；
  - JSON `{time, tags, text}`；`time` 取快照的 `capturedAtEpochMs`，不是 HTTP 完成时刻；
  - tags 固定为 `snapshot, thread-lab, <instance>, <trigger>`，再追加 `LAB_SNAPSHOT_ANNOTATION_TAGS` 里的逗号分隔项；
  - 非 2xx、连接失败、读超时只 WARN，并记 `thread_lab_snapshot_annotations_total{result="failed"}`；
  - URL 为空或 `enabled=false` 是受支持的 OFF 状态，记 `result="skipped"`，不建立连接。

调用点在 `ThreadSnapshotService.capture()` 的成功分支：bundle 写完、`success`/`duration` 指标记录之后、`captureLock` 之外。因此 annotation 再慢也不会把成功变成失败，也不会阻塞第二次抓取；其自身耗时不计入 `thread_lab_snapshot_duration_seconds`。

**同时修正的一个时间戳缺陷**：原实现 `metadata.json.capturedAtEpochMs` 与 API/annotation 的 `capturedAtEpochMs` 是两次 `clock.millis()`，真机相差 31 ms。现在一次抓取只取一个时间戳，文件夹名、metadata、API view、annotation 完全一致（真机 id=79 的 `time` 与 metadata 均为 `1793084440038`）。

### 1.2 配置（`app.lab.snapshot.annotation.*`）

| 键 | 环境变量 | 默认 | 校验 |
|---|---|---|---|
| `app.lab.snapshot.annotation.enabled` | `LAB_SNAPSHOT_ANNOTATION_ENABLED` | `true` | 布尔开关，只控制 annotation |
| `app.lab.snapshot.annotation.url` | `LAB_SNAPSHOT_GRAFANA_URL` | 空（OFF） | 非空时必须是绝对 `http(s)` 且带 host；空串合法（OFF） |
| `app.lab.snapshot.annotation.user` | `GRAFANA_ADMIN_USER` | 空 | 空则不发送 Authorization |
| `app.lab.snapshot.annotation.password` | `GRAFANA_ADMIN_PASSWORD` | 空 | 只随 Basic Auth 发送，不落日志 |
| `app.lab.snapshot.annotation.connect-timeout-ms` | `LAB_SNAPSHOT_ANNOTATION_CONNECT_TIMEOUT_MS` | `2000` | >0 且 ≤ `Integer.MAX_VALUE` |
| `app.lab.snapshot.annotation.read-timeout-ms` | `LAB_SNAPSHOT_ANNOTATION_READ_TIMEOUT_MS` | `3000` | >0 且 ≤ `Integer.MAX_VALUE` |
| `app.lab.snapshot.annotation.tags` | `LAB_SNAPSHOT_ANNOTATION_TAGS` | 空 | 逗号分隔，逐项 trim 后追加 |

compose 只改了 `dual-jdk` profile 下的 `seckill-app-jdk8` 服务（默认 16 服务渲染不变）。JDK 8 外壳用自己的 `LAB_SNAPSHOT_*` 命名空间，URL 值镜像 app 层的 `LAB_THREAD_SNAPSHOT_GRAFANA_URL`：

```yaml
LAB_SNAPSHOT_ANNOTATION_ENABLED: ${LAB_SNAPSHOT_ANNOTATION_ENABLED:-true}
LAB_SNAPSHOT_GRAFANA_URL: ${LAB_THREAD_SNAPSHOT_GRAFANA_URL:-http://grafana:3000}
GRAFANA_ADMIN_USER: ${GRAFANA_ADMIN_USER}
GRAFANA_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
LAB_SNAPSHOT_ANNOTATION_CONNECT_TIMEOUT_MS: ${LAB_SNAPSHOT_ANNOTATION_CONNECT_TIMEOUT_MS:-2000}
LAB_SNAPSHOT_ANNOTATION_READ_TIMEOUT_MS: ${LAB_SNAPSHOT_ANNOTATION_READ_TIMEOUT_MS:-3000}
LAB_SNAPSHOT_ANNOTATION_TAGS: ${LAB_SNAPSHOT_ANNOTATION_TAGS:-}
```

容器内实测（只列键名，值不回显）：

```text
GRAFANA_ADMIN_PASSWORD / GRAFANA_ADMIN_USER
LAB_SNAPSHOT_ANNOTATION_{CONNECT_TIMEOUT_MS,ENABLED,READ_TIMEOUT_MS,TAGS}
LAB_SNAPSHOT_GRAFANA_URL=http://grafana:3000
LAB_SNAPSHOT_ANNOTATION_ENABLED=true
```

### 1.3 指标与测试

`SnapshotMetrics` 新增三个 eager 注册的 counter（与 JDK 21 同名同标签）：

```text
thread_lab_snapshot_annotations_total{result="posted"}
thread_lab_snapshot_annotations_total{result="skipped"}
thread_lab_snapshot_annotations_total{result="failed"}
```

新增 13 个单元测试，全部不依赖 Docker：

- `HttpGrafanaSnapshotAnnotatorTest`（5）：本地 `com.sun.net.httpserver.HttpServer` 断言 body 的 tags/text/time、Basic Auth 头、非 2xx（500）记 failed、不可达（127.0.0.1:1）记 failed、空 URL 与开关关闭记 skipped。
- `ThreadSnapshotServiceTest`（+3）：成功抓取后 annotator 收到同一个 view；annotator 抛异常不影响 `captured=true` 与 success 指标；失败抓取不调用 annotator。
- `SnapshotPropertiesValidationTest`（+5）：URL 规范化（尾斜杠、`/api/annotations`）、空 URL 为 OFF、相对 URL 与 `ftp://` 拒绝、connect/read 超时非正数拒绝。

## 2. D6 面板与 PromQL

`ha-d6.json` 从 12 个面板增至 17 个；原 12 个面板与 templating 逐字节未动（校验：`old['panels'] == new['panels'][:12]` 为 True）。新面板 id 13–17：

| id | 类型 | 标题 | PromQL |
|---|---|---|---|
| 13 | timeseries | 快照抓取速率（attempts / success / failures，按 trigger 与运行时） | `sum by (trigger, jvm_version) (rate(thread_lab_snapshot_attempts_total{job=~"seckill-app\|seckill-app-jdk8"}[5m]))`（B/C 同形，分别换 `success_total` / `failures_total`） |
| 14 | timeseries | 快照抓取耗时（均值 / 最大，按运行时；无直方图故无 P95） | A：`sum by (jvm_version) (rate(thread_lab_snapshot_duration_seconds_sum{...}[5m])) / clamp_min(sum by (jvm_version) (rate(thread_lab_snapshot_duration_seconds_count{...}[5m])), 0.000001)`；B：`max by (jvm_version) (thread_lab_snapshot_duration_seconds_max{...})` |
| 15 | timeseries | 快照保留删除（每 5 分钟，按运行时） | `sum by (jvm_version) (increase(thread_lab_snapshot_retention_deleted_total{job=~"seckill-app\|seckill-app-jdk8"}[5m]))` |
| 16 | timeseries | 快照 annotation 结果（每 5 分钟，按结果与运行时） | `sum by (result, jvm_version) (increase(thread_lab_snapshot_annotations_total{job=~"seckill-app\|seckill-app-jdk8"}[5m]))` |
| 17 | text | 两种运行时的快照工具链与读图说明 | —（说明 jcmd 三连 vs jstack+jcmd GC.heap_info+jmap -histo、`jmap -heap` 在 8u 移除、`-histo:live` 的 GC 代价、`jvm_version` 标签口径、P95 限制） |

`{...}` 在实现里均为 `{job=~"seckill-app|seckill-app-jdk8"}`。要点：

- **job 过滤必须写两个**：JDK 21 是 `job="seckill-app"`，jdk8 是 `job="seckill-app-jdk8"`；只用前者会漏掉 JDK 8。
- **不按 `$instance` 过滤**：D6 的 `$instance` 变量取值是 `app-1:8080` 等 JDK 21 实例，若新面板复用它会把 JDK 8 排除；本步不改 templating，避免影响原 12 个面板。运行时用 `jvm_version` 分组区分。
- **P95 不可得，不伪造**：两个运行时的 snapshot Timer 都只导出 `_count/_sum/_max`（`percentiles-histogram` 只对 `http.server.requests` 打开），没有 `_bucket`。面板 14 给均值与历史最大；若要真 P95，需在两个 app 的 `management.metrics.distribution.percentiles-histogram` 里给 `thread_lab_snapshot_duration_seconds` 打开直方图（JDK 21 配置不在本步允许范围）。
- **均值分母 `clamp_min`**：无新抓取时 `rate(_count)=0`，直接相除会得到 NaN；`clamp_min(...,1e-6)` 让 JDK 21 在空闲时显示 0，而不是断线。

JSON 校验与 Grafana 装载：

```text
$ python -c "import json;json.load(open('code/lab/docker/grafana/dashboards/ha-d6.json',encoding='utf-8'))"
json ok
$ curl -s -u "$GRAFANA_ADMIN_USER:$GRAFANA_ADMIN_PASSWORD" http://127.0.0.1:3000/api/dashboards/uid/ha-d6
title=D6 线程实验室 panels=17
```

## 3. 真机证据

### 3.1 抓取与 annotation

`dual-jdk` profile 起 `jl-seckill-app-jdk8` 后，从容器内以 `X-Lab-Token` 调用 `POST /lab/snapshot`（token 由 `.env` 读取，不回显）：

```json
{"code":"0","message":"OK","data":{"captured":true,"trigger":"manual",
 "directory":"/app/lab-snapshots/20261027-070040-038_app-jdk8",
 "files":["thread-dump.txt","heap-info.txt","class-histogram.txt","metadata.json","analysis-template.md"],
 "durationMs":748,"capturedAtEpochMs":1793084440038,"error":null},"success":true}
```

宿主机 bundle（`doc/process/snapshots/`，gitignored）：

| 文件 | 字节 |
|---|---|
| `thread-dump.txt` | 36,439 |
| `heap-info.txt` | 773 |
| `class-histogram.txt` | 720,932 |
| `metadata.json` | 670 |
| `analysis-template.md` | 4,030 |

`metadata.json` 关键字段：

```json
"instance" : "app-jdk8",
"trigger" : "manual",
"toolset" : "jstack+jmap+jcmd",
"jvmVersion" : "1.8.0_504",
"vmVersion" : "25.504-b01",
"gcCollectors" : "Copy,MarkSweepCompact",
"pid" : 1,
"capturedAt" : "2026-10-27T07:00:40.038Z",
"capturedAtEpochMs" : 1793084440038,
"commands" : {
  "thread-dump.txt" : "jstack -l 1",
  "heap-info.txt" : "jcmd 1 GC.heap_info",
  "class-histogram.txt" : "jmap -histo 1"
}
```

Grafana annotation（`GET /api/annotations?tags=snapshot`，逐字）：

```json
{
  "id": 79,
  "created": 1793084440830,
  "updated": 1793084440830,
  "time": 1793084440038,
  "timeEnd": 1793084440038,
  "text": "thread-lab snapshot: instance=app-jdk8 trigger=manual files=5 toolset=jstack+jmap+jcmd dir=20261027-070040-038_app-jdk8",
  "tags": ["snapshot", "thread-lab", "app-jdk8", "manual"],
  "login": "admin",
  "data": {}
}
```

`time == metadata.capturedAtEpochMs == 1793084440038`，tags 含 `app-jdk8`；`created` 比快照时刻晚 792 ms（HTTP 写入耗时），marker 仍落在快照瞬间。

### 3.2 Prometheus：两个运行时的同名序列

同一时刻查询 `thread_lab_snapshot_attempts_total`（节选，逐字）：

```text
{job="seckill-app",instance="app-1:8080",jvm_version="21.0.12.1",trigger="freeze"} 1
{job="seckill-app",instance="app-1:8080",jvm_version="21.0.12.1",trigger="manual"} 1
{job="seckill-app",instance="app-2:8080",jvm_version="21.0.12.1",trigger="freeze"} 0
{job="seckill-app",instance="app-2:8080",jvm_version="21.0.12.1",trigger="manual"} 0
{job="seckill-app",instance="app-3:8080",jvm_version="21.0.12.1",trigger="freeze"} 0
{job="seckill-app",instance="app-3:8080",jvm_version="21.0.12.1",trigger="manual"} 0
{job="seckill-app-jdk8",instance="seckill-app-jdk8:8080",jvm_version="1.8.0_504",trigger="manual"} 1
```

JDK 8 当前进程的计数器（重启后归零，只有本次抓取）：

```text
thread_lab_snapshot_attempts_total{...,trigger="manual"} 1.0
thread_lab_snapshot_success_total{...,trigger="manual"} 1.0
thread_lab_snapshot_failures_total{...,trigger="manual"} 0.0
thread_lab_snapshot_annotations_total{...,result="posted"} 1.0
thread_lab_snapshot_annotations_total{...,result="failed"} 0.0
thread_lab_snapshot_annotations_total{...,result="skipped"} 0.0
thread_lab_snapshot_duration_seconds_max{...,trigger="manual"} 0.748280176
```

### 3.3 D6 表达式真跑结果

用 Prometheus `/api/v1/query` 逐条执行 §2 的 PromQL（5m 窗口，Prometheus 3.x）：

```text
d6 rates:
  21.0.12.1 trigger=freeze 0
  21.0.12.1 trigger=manual 0
  1.8.0_504 trigger=manual 0.003389991389421871
d6 duration avg:
  21.0.12.1 0
  1.8.0_504 0.748280176
d6 duration max:
  21.0.12.1 0
  1.8.0_504 0.748280176
d6 retention (increase over 5m):
  21.0.12.1 0
  1.8.0_504 0
d6 annotations (increase over 5m):
  21.0.12.1 result=posted/skipped/failed 0/0/0
  1.8.0_504 result=posted 1.0169974168265612; skipped 0; failed 0
```

每条表达式都返回非空结果；JDK 8 的 `freeze` 序列缺失符合“只有 manual”的设计。

## 4. 恢复与验收门

- `jl-seckill-app-jdk8` 已 `docker compose ... stop`，状态 `exited`；bundle 与 annotation 保留。
- 默认渲染仍是 **16 services**（`docker compose -f compose.base.yml config --services | wc -l` → 16）；`--profile dual-jdk` 渲染 17。
- `jl-app-1/2/3` 均 `healthy`，`jl-nginx` `healthy`；`http://127.0.0.1:8080/health` → 200。
- 无活跃故障：`GET /lab-api/chaos/app/app-1/lab/thread/state` → `{"code":"0","message":"OK","data":[],"success":true}`。
- `mvn -pl services/seckill-app-jdk8 -am test` → 59 tests（46 基线 + 13 新增），BUILD SUCCESS。
- `ha-d6.json` JSON 有效；原 12 面板与 templating 未动，新增 id 13–17。
- `check-doc-claims.mjs` 全 PASS（新增 `jdk8-snapshot-annotation` 断言）。

## 5. 剩余限制

- **JDK 8 只有 `trigger=manual`**：没有 lifecycle demo，`freeze` 序列永远不会出现；D6 面板按 trigger 分组时 JDK 8 只有一条 manual 线，是预期而非缺失。
- **`jmap -heap` 在 8u 已移除**：heap-info 只能用 `jcmd GC.heap_info`（与 JDK 21 同一条命令）；collector 名称由 metadata 的 `gcCollectors` 补充，`jmap` 本身不再提供堆几何。若教学上必须“只用 jstack/jmap”，备选是 `jstat -gc`，但格式与 JDK 21 不可比。
- **dump 里没有字面量 `"main"`**：Spring Boot 2.7 的 main 在启动完成后退出，原线程被 VM 改名 `DestroyJavaVM`（JDK 21 外壳一致）；验收看 `http-nio-8080-exec-*` 与 `DestroyJavaVM`。
- **耗时 P95 仍不可得**：两个运行时的 snapshot Timer 都没有 `_bucket`，面板 14 只给均值/最大。要 P95 需给该 Timer 打开 `percentiles-histogram`（JDK 21 配置不在本步改动范围），并同步更新面板。
- **annotation 是同步 best-effort**：成功抓取后最多再等 connect 2s + read 3s；bundle 与 success 指标已落盘，不受影响，但 `POST /lab/snapshot` 的响应时间会包含这段。压测场景可改到单线程 daemon 执行器。
- **跨运行时保留是全局计数**：两个运行时共用 `doc/process/snapshots` 根目录，`max-count` 按整个根目录计数；JDK 21 连续抓取可能删掉最老的 JDK 8 bundle（反之亦然）。
- **D6 未扩展 `$instance` 变量**：为避免改动原 12 个面板，新面板按 `jvm_version` 聚合两个 job，不提供单实例筛选；需要时再加独立的 runtime/instance 变量。
- **真机镜像是本地组装**：本机 Docker daemon 出网仍受代理影响，`seckill-app:jdk8` 用 `mvn package` 的 jar + 既有镜像里的 OTel agent 在官方 `eclipse-temurin:8-jdk-alpine` 上组装（临时 Dockerfile 只在 `tmp/`，未提交）；网络恢复后应跑一次 `docker compose --profile dual-jdk build seckill-app-jdk8` 验证仓库 Dockerfile 端到端。
- **计数器随容器重建归零**：重建后 `attempts` 从 0 开始，5m rate 在重建初期只覆盖新进程；真机证据里的 JDK 8 计数为 1 即此原因。
