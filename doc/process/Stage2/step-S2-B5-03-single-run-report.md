# 步骤交接：Stage2 B5-03 单次实验统计与导出

> 任务/步骤：**B5-03（改定）** 单次结果摘要 + JSON 导出 + 复制为新草稿；配套监控图表改造与 run 时间线
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `a993673`（本步改动本地待提交）
> 测试：控制器全量 **876/0**（新增 `RunReportServiceTest` 2、`RunReportControllerTest` 4、`RunCloneTest` 3）；
> dashboard build 通过；console-smoke **153/0**（新增六图/GC 类型/结果面板断言）；claims 70/70；
> 真机 `GET …/report`、`…/export`、`…/clone` 验证通过
> 关联：设计 `doc/design/Stage2/b5-03-单次统计与导出.md`；计划 §6 B5-03、A32

# 当前进展

- 用户改定：**不做重复 N 次编排**。人工多次执行，每次 run 完整统计并导出 JSON，事后人工比较。
- 后端：`RunReportService` 汇总 run 身份、环境、k6 summary、B5-02 分流、13 项 KPI；新增 `GET …/report`、`GET …/export`、`POST …/clone`。
- 前端：run 详情新增「本次结果」（导出/复制/阈值/环境/缺失项）、「run 时间线」（k6/JVM 堆/GC 次数）；实时监控拆成 6 张图并支持按实例与 GC 类型分线。
- Grafana 默认凭据改为 `admin/admin`（已重置运行实例并验证）。

# 完成功能项

| 项 | 落点 | 说明 |
|---|---|---|
| 报告端点 | `report/RunReport{Service,Controller,Views,Config}` | `GET /lab/runs/{runId}/report` 内联；`GET …/export` 附件 `run-<id>-report.json` |
| 克隆 | `RunLedgerService.clone` + `RunCloneTest` | 复制 params/config/nodes/故障时间线；模板 RUNNING/UNKNOWN → 409；名称加 `(copy)` 并截断 |
| summary 扩展 | `BenchThresholdService.summary` | 请求/迭代/VU 峰值/RPS/失败率（`value` 或 `rate`）/avg·med·P95·P99/阈值 |
| 只读 KPI | `MetricsService.queryRunReadOnly` | 报告导出不写 `lab_metric_window`；键集 `LAB_METRICS_REPORT_KEYS` 外置 |
| 前端结果卡 | `RunResultPanel.vue` | 单次标注、k6 指标、阈值 PASS/FAIL、环境身份、缺失项、导出/复制 |
| run 时间线 | `RunTimelines.vue` | 按 run 起止窗口查询 k6 VU/迭代、JVM 堆（按实例）、GC 次数（按实例×类型） |
| 共享图表 | `TimelineChart.vue` + `src/timeline.js` | 监控页与 run 时间线共用渲染与数据变换；删除 `MonitorTimeline` 内重复 SVG 逻辑 |
| 监控图表 | `MonitorTimeline.vue` | 6 图：请求、k6、JVM 堆、GC（minor 实线/major 虚线）、线程池（按实例）、连接池（按实例） |
| Grafana | `.env` / `.env.example` | `GRAFANA_ADMIN_PASSWORD=admin`；`grafana cli admin reset-admin-password admin` |

# 设计逻辑

- 报告只组合既有读路径，不新建查询层：台账 → summary → B5-02 → KPI 只读窗口。
- 缺数据规则不变：`null` + `quality.missing`；没有 summary 时 `NO_SUMMARY`，绝不显示 PASS 或 0。
- 图表拆分依据单位与维度：堆（MB）与 GC（次/分）不共轴；池按实例分色、active 实线 / 排队虚线。
- GC 类型只按 `action` 二分（`end of minor/major GC`），采集器名称（G1/Serial）保留在 tooltip 数据中。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| `lab_run_env` 未采集 | 起跑时没有写入镜像/Java/GC 快照；报告环境段回退到 `lab_instance_config`（含 applied 镜像与 revision），无配置台账的实例标“无环境记录” | B5-04b / 后续 |
| jdk-matrix | 移入 Future；JDK21/JDK8 对比改为人工分别执行 + 导出 JSON 比较 | Future |
| 时间线窗口粒度 | 步长 `max(15s, duration/300)`；超长 run（>75 分钟）会自动放宽步长 | 记录 |
| 冒烟不真跑负载 | 冒烟只验证面板/按钮/空态；真实 k6 时间线需一次 load run 人工查看 | B5-05 |

# 待定事项和注意事项

1. 部署需重建控制器镜像（新增端点/挂载/环境变量）并重建 dashboard dist；本轮已重建 `jl-chaos-controller`。
2. `LAB_METRICS_REPORT_KEYS` 与前端默认 KPI 列表需保持一致（当前同为 13 项）；改一处要同步另一处。
3. Grafana 环境变量只影响首次初始化；改密码必须 `grafana cli admin reset-admin-password`（已执行 admin/admin）。
4. 克隆只复制台账草稿字段，不复制实例配置（实例配置是环境状态，由 B4 页面管理）。

# 附录

## 真机证据（2026-10-29）

```text
GET /lab-api/lab/runs/run-20261029-005/report
  reportVersion=1 repeatVerified=false
  run: load / nodes app-1,2,3 / 616s / via-nginx / FAILED
  k6: requests=188168 iterations=187668 vusMax=500 rps=313.12 failedRate=0.1342
      avg=120.1ms med=38.0ms p95=404.6ms p99=1686.2ms
      thresholds: server_errors FAIL, flow_errors FAIL, p95<300 FAIL, p99<800 FAIL
  instances: quality=OK, 3 rows（app-2 52.3% / app-3 47.7% / app-1 0%）
  kpi: 13 metrics quality=OK
  quality.missing: lab_run_env not captured; environment comes from the instance config ledger
GET …/export -> 200, Content-Disposition: attachment; filename="run-run-20261029-005-report.json", 36,505 B
Grafana: admin/admin 200, admin/labadmin 401
```

## 关键文件

- 后端：`bench/BenchThresholdService.java`、`metrics/{MetricsService,MetricsProperties}.java`、`ledger/RunLedgerService.java`、`report/*`
- 前端：`timeline.js`、`components/{TimelineChart,MonitorTimeline,RunTimelines,RunResultPanel,RunLedger}.vue`、`api.js`
- 配置：`application.yml`（`lab.metrics.report-keys`）、`compose.base.yml`、`.env.example`、`.env`
