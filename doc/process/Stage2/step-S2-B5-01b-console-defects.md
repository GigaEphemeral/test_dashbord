# 步骤交接：Stage2 B5-01b/04b 控制台缺陷修复 + Prometheus 失稳根因

> 任务/步骤：用户反馈 1.1–1.5（监控页 JVM 多曲线、悬浮刷新、全宽布局、Tempo 检索、Prometheus 挂掉）
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `3ae92a4`（本步改动本地待提交）
> 测试：dashboard `vite build` 通过（63 modules）；控制器全量 **867/0**（含 Spring 上下文）；
> console-smoke **151/0**（新增悬浮刷新 / JVM 分线 / 分流面板 3 条断言）；claims **67** 全部通过
> 关联：计划 §6 B5-01/B5-04；`doc/avoid_common_error/20-k6-remote-write-cardinality.md`

# 当前进展

- 1.1 修复：监控时间轴 JVM 堆/GC 改为**按实例多条曲线**（堆实线、GC 虚线，同色=同一 JVM）。
- 1.2 修复：实时监控页增加**常驻悬浮刷新按钮**（右下角，显示上次同步时间），一次点击触发全局同步。
- 1.3 修复：实时监控分区取消 1280px 宽度上限，横向占满视口；其他分区保持原布局。
- 1.4 修复：Tempo 检索链接改指 **Grafana Explore 的 Tempo 数据源**（带当前 run 窗口），不再指向浏览器无法解析的 `http://tempo:3200`。
- 1.5 定位并修复：Prometheus 失稳不是“自己崩”，而是 **k6 远程写基数爆炸**（单族 8151 序列、head 20.9 万）+ **100% trace 采样压垮 Tempo**（其 `/metrics` 抓取耗时 51–118s）。已降基数、降采样、Tempo 抓取降频、内存上限外置。
- 顺带修复未提交 B5-04b 的两处测试缺陷：`BenchThresholdService.MetricsView` 的 JSON 字段名（`http_req_duration`/`iteration_duration`）与 `when()` 二次打桩自触发异常。

# 完成功能项

| # | 落点 | 行为 |
|---|---|---|
| 1.1 | `MonitorTimeline.vue` | JVM 图 PromQL 改 `sum by (instance)`；新增 `splitSeriesPoints()` 与按实例配色/虚线；图例逐实例显示近值 |
| 1.2 | `MonitorPage.vue`、`App.vue` | 新增 `monitor-refresh-fab`：`position: fixed`、显示 `syncAt`；点击 emit `refresh` → `globalSync()` → `syncTick++`，所有子卡片刷新 |
| 1.3 | `App.vue`、`style.css` | `section === 'observe'` 时 `.page.page-wide { max-width: none }` |
| 1.4 | `MetricsService.buildLinks()`、`MetricsProperties`、`application.yml`、compose、`.env.example` | 链接 = `{grafana-url}/explore?left={datasource:tempo,queries:[traceql {}],range}`；配置项 `lab.metrics.tempo-datasource-uid` / `LAB_METRICS_TEMPO_DATASOURCE_UID` 取代 `tempo-url` |
| 1.5 | `k6/lib/config.js`、`compose.base.yml`、`prometheus.yml` | `systemTags` 白名单（status/method/expected_response/error/error_code）；`OTEL_TRACES_SAMPLER*` 默认 10% 比例采样；tempo job 60s/20s；`PROMETHEUS_MEM_LIMIT` 768m、`PROMETHEUS_RETENTION` 7d 外置 |

# 设计逻辑

- **JVM 多曲线**：按实例分色后，同一 JVM 的堆与 GC 同色，实线/虚线区分指标；y 轴仍为共享轴（MB 与 ms/s 同图，沿用原设计），tooltip 仍逐点给出数值。
- **悬浮刷新**：不新增轮询，只复用现有 `syncTick` 广播；`autoSync` 关闭时它也是唯一的手动刷新入口。
- **全宽**：只对监控分区放开上限，避免影响总览/向导/台账的阅读宽度。
- **Tempo 链接**：浏览器只能访问宿主已发布端口（Grafana 3000），Tempo 3200 从不对外；因此把“检索入口”放在 Grafana Explore，并在页面写明用途与端口原因。
- **Prometheus 容量**：远程写的 label 完全由 k6 tag 决定；限制 system tags 是治本，提内存只是兜底。面板只依赖 `run_id`，去掉 `url/name` 不影响任何现有查询。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 内存上限仅兜底 | 768m 是经验值；若后续增加高基数指标需重估 | B5-05 观察 |
| Tempo 抓取仍可能超时 | 采样降到 10% 后 `/metrics` 应恢复；若仍慢，可将 job 整体移除（无面板消费） | 记录 |
| 1.5 真机复测 | 需重建控制器镜像并重跑一次 load 才能验证 tsdb 序列数下降 | B5-05 |

# 待定事项和注意事项

1. 1.4 的配置键改名（`LAB_METRICS_TEMPO_URL` → `LAB_METRICS_TEMPO_DATASOURCE_UID`）：旧 `.env` 里的键会被忽略，需同步本地 `.env`；compose 已有默认值，不配也可启动。
2. 1.5 的 k6 `systemTags` 只影响**新发起**的 run；历史 run 的序列不受影响。
3. Grafana 匿名登录已关闭，Tempo 检索链接会要求 Grafana 登录（顶部 Grafana 链接同源）。

# 附录

## 事故证据（2026-10-29）

```text
docker inspect jl-prometheus: RestartCount=1 ExitCode=0 OOMKilled=false mem_limit=512m
process_resident_memory_bytes{job="prometheus"} = 460,627,968  (~460MB)
/api/v1/status/tsdb headStats.numSeries = 209,258
k6_http_req_duration_max 等族各 8,151 条序列
jl-tempo 日志: GET /metrics 51.33s / 1m58.49s i/o timeout (Prometheus scrape timeout 5s)
```

## 修复后的真机状态（2026-10-29 21:02–21:12）

```text
docker inspect jl-prometheus -> Mem=805306368 (768m), Status=running, /-/healthy 正常
up{job="tempo"} = 1（抓取恢复；job 改为 60s/20s）
Prometheus head series 仍约 20.7 万：旧序列在 head 压缩（约 2h）后才回收，新 run 不再增长
GET /lab-api/lab/metrics/window?...&keys=http.rps -> links.tempoUrl =
  http://localhost:3000/explore?left={"datasource":"tempo","queries":[traceql "{}"],"range":{...}}
```

## 关键文件

- 前端：`MonitorTimeline.vue`、`MonitorPage.vue`、`ObservePanel.vue`、`App.vue`、`style.css`
- 控制器：`MetricsService.java`、`MetricsProperties.java`、`BenchThresholdService.java`
- 配置：`k6/lib/config.js`、`compose.base.yml`、`prometheus.yml`、`.env.example`
- 文档：`doc/avoid_common_error/20-k6-remote-write-cardinality.md`
