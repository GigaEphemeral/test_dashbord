# 步骤 51 · D8 JVM 调优实验与 `tune.sh`

> 步骤交接 · 调优台（脚本 + Grafana 面板）
> 日期：2026-10-27
> 代码基线：`907ba5c`（`docs: flip the cache console and cache proxy claims to positive after step-50`）
> 交付：`code/lab/scripts/tune.sh`、`code/lab/docker/grafana/dashboards/ha-d8.json`、两份真机 benchmark 记录、`code/lab/docclaims/claims.yml` 的 D8 断言翻转、本文档。
> 边界：新增调优脚本与 D8 面板；不做 `/tuning` 页面、自动参数扫描、归档对比；不重建镜像；不修改 `.env`。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

---

## 1. 运行器契约（`tune.sh`）

### 1.1 调用

```bash
# 只改参数（不跑 k6）
bash code/lab/scripts/tune.sh --name xmx256 --flags "-Xmx256m"

# 改参数 + 45s load
bash code/lab/scripts/tune.sh --name xmx256 --flags "-Xmx256m" \
  --scenario load --duration 45s

# 回到第一次记录下来的基线
bash code/lab/scripts/tune.sh --restore
```

| 选项 | 含义 |
|---|---|
| `--name <profile>` | 结果文件名与 annotation 的 profile 名；`[A-Za-z0-9][A-Za-z0-9._-]*` |
| `--flags "<flags>"` | `APP_JAVA_TOOL_OPTIONS`；非空、单行、每个 token 以 `-` 开头 |
| `--scenario smoke\|load` | 可选；改参数后跑一段短 k6（仅这两个场景接受 `--duration`） |
| `--duration <n>s\|m` | 默认 `60s`；`load` → `LOAD_DURATION`，`smoke` → `SMOKE_DURATION` |
| `--no-reset` | k6 前不执行 `reset-lab.sh`（默认执行） |
| `--restore` | 读取保存的基线并重建 app 层，不跑 k6 |
| `-h, --help` | 退出 0 |

退出码：成功 `0`；k6 跑过且失败时返回 k6 的状态码；用法/校验错误 `2`；运行期错误 `1`。

### 1.2 参数校验

- 非空、单行、无 tab；字符集只允许字母、数字、空格和 `. _ = : + , -`。
- 每个 token 必须以 `-` 开头（`-Xmx512m`、`-XX:+UseG1GC`、`-XX:MaxMetaspaceSize=256m`）。
- 显式拒绝 `-javaagent:*`、`-agentlib:*`、`-agentpath:*`、`-XX:OnError=*`、`-XX:OnOutOfMemoryError=*`，避免把调优脚本变成任意命令入口。
- 未知选项、未知场景、`--restore` 混用 `--name/--flags/--scenario` 都直接退出 2。

### 1.3 执行流程

1. 读取 `.env` 的 `GRAFANA_ADMIN_*`（只解析，不 source、不打印），读取 app 容器当前的 `JAVA_TOOL_OPTIONS`。
2. 把“第一次看到的参数”记为基线（`BASELINE_FLAGS`），把“本次之前参数”记为 `PREVIOUS_FLAGS`，写入 `tmp/tuning-state.env`（已被 `.gitignore` 的 `tmp/` 覆盖）。
3. 用 compose 环境覆盖重建 app 层，**只动 `app-1/2/3`**：

   ```bash
   APP_JAVA_TOOL_OPTIONS="$FLAGS" docker compose ... up -d --no-deps \
     --force-recreate --wait --wait-timeout 240 app-1 app-2 app-3
   ```

   `.env` 从未被写入，因此不存在“改坏 `.env` 后无法恢复”的路径。
4. 轮询三个容器的 `State.Status=running` 且 `Health=healthy`；随后 `nginx -t` + `nginx -s reload`（Nginx 在启动时缓存上游地址，重建后必须 reload 才能重新解析），再探测 `http://127.0.0.1:8080/health` 与 `/api/products/1`。
5. POST `GRAFANA_URL/api/annotations`，`tags: [tuning, <profile>]`，文本包含 flags、previous、baseline、operator、scenario、duration、run_id；Basic 认证通过 0600 临时 curl 配置文件传递，失败只 WARN。
6. 若指定场景：先 `reset-lab.sh --yes`（`stop` + `start`，不会重建容器，因此新参数保留），再跑 `k6/run.sh`；`--no-reset` 跳过重置。
7. 写 `doc/process/Stage1/benchmark/tuning-<timestamp>-<profile>.md`：参数、基线、run_id、annotation 状态、窗口 epoch、D8 深链、完整的对比 PromQL。
8. 任一步失败时 ERR trap 会用 `RESTORE_ON_ERR_FLAGS` 尝试恢复上一组参数；恢复失败则打印 `tune.sh --restore` 的补救命令。

### 1.4 状态与还原语义

- 基线只在第一次运行（或 `tmp/tuning-state.env` 不存在）时记录，后续 profile 不会覆盖基线。
- `--restore` 把基线重新应用到 app 层；若当前参数已等于基线，只做健康检查，不重建。
- 还原也会写 annotation（`tags: [tuning, restore]`），但不写新的结果 note。
- 状态文件是机器本地的（`tmp/` 不提交）：换机器后第一次运行会把当前参数当基线。

### 1.5 外置配置（节选）

| 变量 | 默认 | 说明 |
|---|---|---|
| `TUNE_STATE_FILE` | `<repo>/tmp/tuning-state.env` | 基线/上次参数 |
| `TUNE_DOC_DIR` | `<repo>/doc/process/Stage1/benchmark` | 结果 note |
| `TUNE_HEALTH_TIMEOUT_SECONDS` / `_INTERVAL_` | `240` / `3` | app healthy 轮询 |
| `TUNE_HTTP_READY_TIMEOUT_SECONDS` / `_INTERVAL_` | `45` / `2` | Nginx `/health` + `/api/products/1` 轮询 |
| `TUNE_HTTP_TIMEOUT_SECONDS` | `5` | 探测超时 |
| `TUNE_ANNOTATION_TIMEOUT_SECONDS` | `5` | annotation 超时 |
| `TUNE_GRAFANA_URL`（或 `GRAFANA_URL`） | `http://127.0.0.1:3000` | Grafana |
| `TUNE_ANNOTATE` | `1` | `0` 关闭 annotation |
| `TUNE_APP_SERVICES` / `TUNE_APP_PROBE_SERVICE` | `app-1 app-2 app-3` / `app-1` | 目标服务 |
| `TUNE_NGINX_CONTAINER` / `TUNE_NGINX_HEALTH_URL` / `TUNE_NGINX_API_PROBE_URL` | `jl-nginx` / 见脚本 | 入口探测 |
| `TUNE_TOXIPROXY_CONTAINER` | `jl-toxiproxy` | 若在运行则拒绝（chaos-proxy 叠加层会改写 app 依赖地址） |
| `TUNE_OPERATOR` | `git config user.name` | annotation/note 里的操作者 |

### 1.6 真机注意

- 本机 `C:\Users\CZQ\.local\bin\env` 是一个静默 wrapper：`env VAR=... bash ...` 会立即返回 0 且不执行命令。`tune.sh` 改用子 shell `export` + `exec bash`，**不要**把 k6 调用改回 `env`。
- `compose base+dev` 渲染时会警告 HA 叠加层的容器是 orphan（`jl-mysql-*`、`jl-redis-*`、`jl-sentinel-*`）。脚本不加 `--remove-orphans`，警告无害。
- 768m 容器限额下 JVM 自动选择 SerialGC（`UseSerialGC=true`，`jvm_gc_pause_seconds{gc="Copy"}`），不是 G1。演示 GC 参数时应先确认 `gc` 标签变化。
- `reset-lab.sh` 的 `stop`/`start` 不会重建容器，所以调优后的 `JAVA_TOOL_OPTIONS` 在重置后仍然有效。

---

## 2. 本次演示使用的参数表

| profile | `APP_JAVA_TOOL_OPTIONS` | 目的 | run_id | 结果 |
|---|---|---|---|---|
| `baseline512` | `-Xmx512m` | 当前基线；参数相同，跳过重建 | `tune-20261027-121356-baseline512` | 45s load PASS |
| `xmx256` | `-Xmx256m` | 只改堆上限，观察上限阶跃与 GC 余量 | `tune-20261027-121559-xmx256` | 45s load PASS |
| `restore` | `-Xmx512m` | 还原基线 | 不跑 k6 | flags 已还原，annotation 已写入 |

两次实验之间只改 `-Xmx`；GC、线程、Hikari、依赖服务、容器限额（768m）均未改。

---

## 3. 真机前后对比（2026-10-27）

### 3.1 环境与口径

- 3 × `jl-app-N`，Spring Boot 3.5.16，Temurin `21.0.12.1`，`mem_limit: 768m`，SerialGC。
- 同一 `load` 场景：50 → 200 VU，45s（20% ramp-up / 20% peak / 60% hold），1s think time，商品读 + 缓存登录；每次 k6 前 `reset-lab.sh` 重置库存/订单/Redis/队列。
- k6 指标来自 k6 自带 summary；应用侧指标来自 Prometheus 60s 窗口，窗口结束时间分别为 `1793074553`（512m）和 `1793074730`（256m）。
- 单次短跑、未做多轮取中位；数字用于演示方法，不作为容量结论。

### 3.2 k6

| 指标 | `-Xmx512m` | `-Xmx256m` |
|---|---|---|
| RPS（avg） | **152.62** | **152.59** |
| iterations | 6819（148.27/s） | 6819（148.24/s） |
| HTTP P95 | 4.63 ms | 4.55 ms |
| HTTP P99 | 6.49 ms | 6.59 ms |
| product P95 / P99 | 4.34 / 6.09 ms | 4.32 / 6.24 ms |
| max | 142.29 ms | 89.50 ms |
| checks | 7019 / 7019（100%） | 7019 / 7019（100%） |
| `http_req_failed` | 0.00% | 0.00% |
| `flow_errors` / `server_errors` | 0.00% / 0.00% | 0.00% / 0.00% |
| 阈值 | 全部 PASS | 全部 PASS |

### 3.3 Prometheus（3 实例聚合/平均）

| 指标 | `-Xmx512m` | `-Xmx256m` | 变化 |
|---|---|---|---|
| old-gen 堆上限（`max by(instance)` 均值） | **341.4 MiB** | **170.7 MiB** | **-50.0%**（与 `-Xmx` 同步阶跃） |
| 堆使用峰值（每实例均值） | 105.0 MiB | 98.6 MiB | -6.1% |
| GC 暂停总时长 / 60s（3 实例合计） | 0.130 s | 0.099 s | -23.8%（单次短跑噪声） |
| GC 暂停次数 / 60s（合计） | 16.91 | 15.27 | -9.7% |
| 最长暂停（`jvm_gc_pause_seconds_max`） | 0.019 s | **0.324 s** | 出现 1 次 major GC |
| full GC 累计次数 | 0 | **1**（app-1，`MarkSweepCompact`，`Allocation Failure`） | 发生在重建/预热阶段；负载窗口内 `increase=0` |
| 分配速率 | 9.12 MiB/s | 8.94 MiB/s | -2.0% |
| 晋升速率 | 0.27 MiB/s | 0.19 MiB/s | -29.6% |
| 应用侧 RPS（3 实例合计） | 119.4 | 120.1 | +0.6% |
| 应用侧 P95 / P99 | 48 / 50 ms | 48 / 50 ms | 持平（包含登录请求，故高于 k6 product P95） |
| 活跃线程（均值） | 47.3 | 47.3 | 持平 |
| 进程 CPU（均值） | 0.010 核 | 0.010 核 | 持平 |
| `application_ready_time_seconds` | 34.66 s | 34.83 s | +0.5%（基线值来自 reset 的 start，不是重建） |

观察：

- `jvm_memory_max_bytes` 的 old-gen 上限精确减半，证明参数确实生效；`-Xmx` 是整堆上限，SerialGC 下 old-gen 上限约为整堆的 2/3。
- 在 ~150 RPS 的商品读负载下，两个堆上限的吞吐/P99/错误率没有可测差异；堆峰值约 100MiB，256m 仍有约 70MiB old-gen 余量。
- 256m 在重建/预热阶段出现 1 次 Serial full GC，`jvm_gc_pause_seconds_max` 达到 **324ms**（512m 基线为 19ms）。这是“缩小堆换故障余量”的真实代价，也是 D8 最值得讲的一根尖峰。
- GC 总暂停在 256m 反而略低，差异在单次 45s 短跑的噪声范围内，不能据此说小堆更省 GC。

### 3.4 还原与验收

```text
$ bash code/lab/scripts/tune.sh --restore
Saved baseline : -Xmx512m
Current flags  : -Xmx256m
Applying JVM flags (restore baseline): -Xmx512m
... jl-app-1/2/3 Recreated ... Healthy
Nginx reloaded (upstream names re-resolved).
Grafana annotation posted (tags tuning,restore).
Restored app tier to baseline: -Xmx512m
```

验收结果：

- `jl-app-1/2/3`：`running/healthy`，`JAVA_TOOL_OPTIONS=-Xmx512m`；
- `GET /health` 与 `GET /api/products/1` 连续 3 次均 200；
- old-gen 上限回到 `357957632` 字节（341.4 MiB）；
- Grafana annotation 存在 `tuning,baseline512`、`tuning,xmx256`、`tuning,restore` 三条（失败/探针运行的 3 条已删除）；
- D8 已由 provisioning 加载：`/api/dashboards/uid/ha-d8` 返回 12 个 panel，annotation 图层名为 `JVM 调优 / 还原（tags: tuning）`。

---

## 4. D8 面板与 PromQL

### 4.1 面板

`ha-d8.json`（uid `ha-d8`，12 个 panel）：

1. 方法/口径/annotation 查询（text，顶部）；
2. heap used / committed / max；
3. GC pause rate（s/s + pauses/s）；
4. allocation / promotion rate；
5. longest GC pause（P99 代理）；
6. live threads；
7. process CPU；
8. request rate；
9. request P95 / P99；
10. Hikari active/idle/pending/max；
11. startup time（`application_ready_time_seconds`）；
12. 窗口对照 PromQL + 实验记录（text，底部）。

变量：`instance = label_values(up{job="seckill-app"}, instance)`，多选 + All。
annotation 图层：Grafana built-in datasource，`tags: ["tuning"]`（实验写 `tuning,<profile>`，还原写 `tuning,restore`）。

### 4.2 关键查询

```promql
# 堆
sum by (instance) (jvm_memory_used_bytes{job="seckill-app", area="heap", instance=~"$instance"})
sum by (instance) (jvm_memory_committed_bytes{job="seckill-app", area="heap", instance=~"$instance"})
max by (instance) (jvm_memory_max_bytes{job="seckill-app", area="heap", instance=~"$instance"})

# GC
sum by (instance) (rate(jvm_gc_pause_seconds_sum{job="seckill-app", instance=~"$instance"}[$__rate_interval]))
sum by (instance) (rate(jvm_gc_pause_seconds_count{job="seckill-app", instance=~"$instance"}[$__rate_interval]))
max by (instance) (max_over_time(jvm_gc_pause_seconds_max{job="seckill-app", instance=~"$instance"}[$__rate_interval]))

# 分配 / 晋升
sum by (instance) (rate(jvm_gc_memory_allocated_bytes_total{job="seckill-app", instance=~"$instance"}[$__rate_interval]))
sum by (instance) (rate(jvm_gc_memory_promoted_bytes_total{job="seckill-app", instance=~"$instance"}[$__rate_interval]))

# 线程 / CPU / 吞吐 / 延迟
jvm_threads_live_threads{job="seckill-app", instance=~"$instance"}
process_cpu_usage{job="seckill-app", instance=~"$instance"}
sum by (instance) (rate(http_server_requests_seconds_count{job="seckill-app", instance=~"$instance"}[$__rate_interval]))
histogram_quantile(0.95, sum by (le, instance) (rate(http_server_requests_seconds_bucket{job="seckill-app", instance=~"$instance"}[$__rate_interval])))
histogram_quantile(0.99, sum by (le, instance) (rate(http_server_requests_seconds_bucket{job="seckill-app", instance=~"$instance"}[$__rate_interval])))

# 连接池 / 启动
hikaricp_connections_active{job="seckill-app", instance=~"$instance"}
hikaricp_connections_idle{job="seckill-app", instance=~"$instance"}
hikaricp_connections_pending{job="seckill-app", instance=~"$instance"}
hikaricp_connections_max{job="seckill-app", instance=~"$instance"}
application_ready_time_seconds{job="seckill-app", instance=~"$instance"}
```

口径说明：

- `jvm_memory_max_bytes` 每个内存池一条；`max by (instance)` 选中 old-gen/Tenured 池，因此显示值小于 `-Xmx`。看阶跃，不看绝对值。
- GC 只有 `_sum/_count/_max`，没有 `_bucket`，无法算真正的 P99；`max_over_time(_max)` 是“最长单次暂停”的代理，且 Micrometer 的 TimeWindowMax 会衰减（本次 324ms 的 full GC 发生在测量窗口之前，但仍在衰减窗口内）。
- `application_ready_time_seconds` 是启动到 `ApplicationReadyEvent` 的耗时，重建后才重置；不要拿旧进程的值当本轮启动时间。
- `instance` 标签来自 Prometheus 抓取目标（`app-N:8080`），容器重建不会改变它；参数本身没有指标，只能靠 annotation 与 note 追溯。

全部 18 条 panel target 已用 Prometheus instant query 验证：`$instance` → `.*`、`$__rate_interval` → `1m` 后全部 `success` 且返回 3 条序列（Hikari 每条查询 3 条）。

---

## 5. 未完成 / 后续

1. **自动参数扫描**：没有 profile 矩阵、没有多轮重复/取中位、没有自动判定。`tune.sh` 一次只应用一组参数，比较靠人工按 annotation 截窗口。
2. **归档对比**：`doc/process/Stage1/benchmark/tuning-*.md` 只有单次记录，没有 `doc/process/Stage1/benchmark/jvm-tuning/` 归档目录；`bench.sh`/`compare.sh` 不读取调优 note，也没有把两个 run_id 的 RPS/P95/P99/GC 自动对比。
3. **`/tuning` 页面与控制器端点**：设计 §10.3 的 Vue 页面、预设白名单（均衡/128m/1g/G1/ZGC/Parallel/CMS）、按 JDK 版本限制（ZGC/虚拟线程仅 21）、`X-Lab-Token` 鉴权、单实例重建都未实现；本次只有脚本级字符集校验 + agent 拒绝列表。
4. **只调了 `-Xmx`**：GC 选择、`-Xms`、`-Xss`、Metaspace、Tomcat/Hikari 池大小未做真机对照；当前基线是 SerialGC，G1/ZGC 的实验还没跑。
5. **JDK 8 不参与**：脚本只重建 `app-1/2/3`（JDK 21），`seckill-app-jdk8` 不在调优目标内；D7 的双 JDK 对比与 D8 的调优实验仍是两条线。
6. **文档漂移未收口**：`doc/design/Stage1/details/08-双JDK与JVM调优.md`、`doc/design/Stage1/details/09-压测与观测.md`、`doc/design/Stage1/details/README.md`、`doc/process/Stage1/retrospective-2026-10-27.md` 仍写着“D8 未实现”。这些文件不在本步骤允许修改的范围内；`claims.yml` 的 D8 断言已翻转为 positive，并指向本文档。后续收口时应同步这四处的措辞。
7. **状态文件是本机的**：`tmp/tuning-state.env` 不提交；换机器/清理 `tmp/` 后，第一次 `--restore` 只能打印当前参数（没有基线可还原）。
8. **annotation 只增不减**：没有清理策略；Grafana annotation API 的失败只 WARN，不影响实验。
9. **未重建镜像、未改 `.env`**：所有实验都复用 `java-learn-lab/seckill-app:jdk21`，参数通过 compose 环境覆盖注入。
