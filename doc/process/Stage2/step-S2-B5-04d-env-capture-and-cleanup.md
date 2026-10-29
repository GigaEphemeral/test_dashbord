# 步骤交接：Stage2 遗留收尾（起跑环境快照 / 诊断事件 UI / 配置外置）

> 任务：收口后处理遗留项——`lab_run_env` 起跑采集、实例诊断事件展示、审核 Minor 的配置外置与脚本口径
> 日期：2026-10-30
> 分支 / HEAD：`feature/stage2-workbench` / `ba794f2`（本步改动本地待提交）
> 测试：控制器全量 **887/0**（含 `RunEnvCaptureHookTest` 3、`RunStartedHooksTest` 2、`UpstreamWeightsTest` 5、
> `TokenFilterTest` 8 上下文测试）；console-smoke **157/0**（新增诊断事件与 run 环境快照断言）；claims **80/80**；
> 真机环境快照验证通过（`quality.missing=[]`）

# 当前进展

- **起跑环境快照已接线**：新增 `RunEnvCaptureHook`，在 READY→RUNNING 后按 run 节点读取容器实际身份
  （镜像 tag/id、Java 版本、`javaToolOptions`/`jvmFlags`、GC 配置），写入 `lab_run_env`。报告环境段不再回退台账，
  `quality.missing` 为空。
- **实例页新增「诊断事件（OOM/堆转储）」**：按需扫描有界容器日志，空结果 `NO_DATA`、不可读 `UNAVAILABLE`，不造事件。
- **审核 Minor 处理**：
  - 分流统计的 `job` 与 URI 过滤外置（`lab.traffic-split.app-job` / `business-uri-filter`），nginx 配置读取加字节上限；
  - Grafana 演示口令 fallback 统一为 `admin`（`up.sh` / `verify.sh`）；
  - 监控时间窗/步长改为 VITE 变量可覆盖。

# 完成功能项

| 项 | 落点 | 说明 |
|---|---|---|
| 多起跑钩子 | `ledger/RunStartedHooks` + `LedgerConfig` | `List<RunStartedHook>` 组合，单钩子失败不影响其他钩子/run 启动 |
| 环境采集 | `instance/RunEnvCaptureHook` + `InstanceConfigConfig` | 复用 `InstanceConfigService.effective`；逐节点 best-effort，缺失容器跳过 |
| 报告环境段 | `RunReportService`（未改逻辑） | `lab_run_env` 有行时直接使用，`missing` 不再出现回退说明 |
| 诊断事件 UI | `InstanceConfigEditor.vue` + `api.js` | 读取按钮、事件列表、NO_DATA/UNAVAILABLE 文案 |
| 分流配置外置 | `TrafficSplitProperties` / `application.yml` / compose / `.env.example` | `LAB_TRAFFIC_SPLIT_APP_JOB`、`_BUSINESS_URI_FILTER`、`_MAX_UPSTREAM_BYTES` |
| nginx 读取上限 | `UpstreamWeights.read(path,name,maxBytes)` | 超过上限返回不可用原因，不读入内存 |
| 脚本口径 | `up.sh` / `verify.sh` | Grafana 默认口令统一 `admin` |
| 监控时间窗 | `MonitorTimeline.vue` | `VITE_LAB_MONITOR_WINDOW_SECONDS` / `_STEP_SECONDS` |

# 设计逻辑

- **环境快照是“起跑时事实”**：只记录容器实际回读，不读草稿；采集失败只记日志，绝不影响 run 状态机。
- **事件不推断**：日志窗口内无匹配就是 `NO_DATA`；只有读到日志才可能产生事件。
- **配置外置**：Prometheus job、URI 过滤、读取上限全部可由 `.env` 覆盖，改名 exporter 不需要改代码。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 环境快照的 JDK8 | 按需 profile 未运行时跳过（不伪造）；运行时可正常采集 | 记录 |
| Tomcat 5 键 UI | 目录已接线，实例表暂未展示（可用 `/lab/metrics/window` 查询） | Future |
| jdk8 采样 | 未重建，仍 100% 采样 | 记录 |

# 待定事项和注意事项

1. 环境快照随 run 启动异步写入；`GET /lab/runs/{id}` 的 `env` 在启动后约 1–2s 出现。
2. 新增 env 键需重建控制器容器才生效（本轮已重建）。
3. 审核 Minor 中的 `InstanceStatsService` PromQL job/uri 已外置；其余 Minor 维持记录。

# 附录

## 真机证据（2026-10-30）

```text
POST /lab/runs {scenario: smoke, nodes:[app-1]} -> run-20261029-006
POST .../start -> 202 -> RUNNING -> COMPLETED（约 20s）
GET /lab/runs/run-20261029-006
  env[0] = jl-app-1 / java-learn-lab/seckill-app:jdk21 / sha256:408b427b685c… / java 21
           jvmArgs = {javaToolOptions, jvmReadback:OK, jvmFlags[33]}
           gc = {collector:G1, maxGcPauseMillis:200, g1NewSizePercent:5, …}
GET .../report -> environment[0] 同上, quality.missing=[]
```

## 关键文件

- 后端：`ledger/RunStartedHooks.java`、`instance/RunEnvCaptureHook.java`、`traffic/{TrafficSplitProperties,InstanceStatsService,UpstreamWeights}.java`
- 前端：`InstanceConfigEditor.vue`、`api.js`、`MonitorTimeline.vue`
- 配置：`application.yml`、`compose.base.yml`、`.env.example`、`up.sh`、`verify.sh`
