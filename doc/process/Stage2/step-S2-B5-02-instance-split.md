# 步骤交接：Stage2 B5-02 实例分流统计面板

> 任务/步骤：**B5-02** 负载均衡分流（方案 §9.6，验收 A17）
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `3ae92a4`（本步改动本地待提交）
> 测试：`InstanceStatsServiceTest` 3/0、`UpstreamWeightsTest` 4/0；控制器全量 867/0；console-smoke **151/0**
> （新增“分流面板有数据或诚实空态”断言）；dashboard build 通过；A17 真机端点验证通过
> 关联：计划 §6 B5-02；指标字典 HTTP/JVM/线程池族；`b5-metrics-gap-plan.md` §3（Nginx per-peer = UNWIRED）

# 当前进展

- 新增只读端点 `GET /lab/runs/{runId}/instance-stats`，按 run 窗口返回每个实例的命中数、分流占比、成功/失败、平均响应、P95/P99、实例 RPS、错误率、线程池拒绝、upstream 权重、期望占比与配置组摘要。
- 新增控制台面板 `TrafficSplitPanel.vue`，挂在「实验记录 → run 详情」的 Run 指标之前；含口径说明、质量徽标、偏差提示与权重来源。
- 权重来自 **compose 只读挂载的 nginx 配置**（`/etc/javalab/nginx/default.conf`），不在 Java 里复制一份权重表。
- 未做上游交叉核对：nginx stub_status 没有 per-peer 计数（与 B5-04a 的 UNWIRED 结论一致），面板显式写明原因，不显示 0。

# 完成功能项

| 项 | 落点 | 说明 |
|---|---|---|
| 端点 | `traffic/InstanceStatsController` | `GET /lab/runs/{runId}/instance-stats`，token 过滤与其他 `/lab/**` 一致 |
| 聚合 | `traffic/InstanceStatsService` | 8 条 instant 查询：命中/成功/失败/平均/P95/P99/RPS/拒绝；全部按 `instance` 分组 |
| 口径 | `BUSINESS = job="seckill-app",uri!~"/actuator.*"` | 排除 Prometheus 抓取与健康检查，不把探针算作业务命中 |
| 窗口 | 复用 `MetricsService.requireRun/runWindow` | `[start - scrapeGrace, end + drainGrace]`，open run 结束时间取 now |
| 权重 | `traffic/UpstreamWeights` | 解析 `upstream seckill_backend { server app-N:8080 [weight=K]; }`，缺省 weight=1；文件不可读/无该 upstream → `available=false` + 原因 |
| 偏差提示 | `imbalance-warn-points`（默认 5 个百分点） | 实测占比与权重期望相差超阈值时给出 warning，提示先排查实例健康再比较 JVM 性能 |
| 配置组 | `lab_instance_config` 行摘要 | `imageTag · Xmx · GC · status revN`；台账不可读时留空并给 warning |
| UI | `TrafficSplitPanel.vue` + `RunLedger.vue` | 表格 14 列；未启用实例置灰；无序列显示“无数据” |
| 配置外置 | `lab.traffic-split.*` / `LAB_TRAFFIC_SPLIT_*` | nginx 路径、upstream 名、偏差阈值全部可覆盖；compose 挂载只读配置 |

# 设计逻辑

- **只信应用侧**：Prometheus 的 `http_server_requests_seconds_*` 带 `uri/status`，能给出成功/失败与分位数；nginx stub_status 无法按 peer 拆分，硬凑只会制造假数据。
- **null 即无数据**：任何一段序列缺失都不补 0；质量 `OK / NO_DATA / DEGRADED` 由“是否有命中”和“是否有查询失败”决定。
- **权重不复制**：控制器镜像不含 `code/lab`，因此把 nginx 配置只读挂进容器再解析；改 upstream 权重不需要重建控制器。
- **偏差是提示不是结论**：占比偏差可能来自实例未启用、健康检查摘除或权重变化，面板只提示“先排查”，不直接判定 JVM 性能差异。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| A17 真机验收 | 已通过：`run-20261029-005` 端点返回真实占比/分位数/权重，app-1 停机触发偏差 warning；仍需在**新发起**的 load run 上复核一次（本轮沿用已有 run 的窗口） | B5-05 |
| 上游交叉核对 | nginx 开源版无 per-peer 指标；如需真实上游时延/状态码，需 nginx-plus 或日志管道（Future） | Future |
| 背景流量污染 | 预检查已提示 `jl-traffic`；面板只做文字说明，未在数据上扣除 | 记录 |
| P95/P99 可用性 | 依赖 `http_server_requests_seconds_bucket`；若某实例未导出直方图则为 null | 记录 |

# 待定事项和注意事项

1. 部署：`compose.base.yml` 新增 `./nginx/conf.d/default.conf:/etc/javalab/nginx/default.conf:ro` 与三个 `LAB_TRAFFIC_SPLIT_*`，**需重建 `jl-chaos-controller` 容器**才生效；不重建时端点 404。
2. 权重解析只覆盖 `server host[:port] [weight=N];` 形态；出现 `down`、`backup`、变量等写法时仍按默认权重 1 计，并在后续需要时扩展解析器。
3. 面板请求在每次选中 run 时发起一次，不随 5s 全局同步轮询（避免 Prometheus 压力）；需要最新数据用面板上的“刷新分流”。

# 附录

## 真机证据（2026-10-29，`run-20261029-005`）

控制器重建后（本地打包 jar + 运行时镜像，见下），`GET /lab-api/lab/runs/run-20261029-005/instance-stats` 返回：

```text
quality OK   from 2026-10-29T12:17:02Z  to 2026-10-29T12:28:04Z
upstream {"available":true,"source":"/etc/javalab/nginx/default.conf",
          "weights":{"app-1":1,"app-2":1,"app-3":1}}
app-1 hits=0        share=0%     config="jdk21 · Xmx512m · G1 · applied rev27"
app-2 hits=78240.6  share=52.3%  avg=36.6ms  p95=127ms  p99=474ms  rps=118.2
app-3 hits=71296.9  share=47.7%  avg=36.8ms  p95=153ms  p99=590ms  rps=107.7
warnings: app-1 实测占比 0.0% 与权重期望 33.3% 相差 33.3 个百分点 …
```

- app-1 在该 run 窗口末端被停过，命中为 0；warning 按设计提示先排查实例健康。
- 该 run 没有 5xx 序列，`failed/errorRate` 保持 `null`（面板显示无数据并解释原因），不推断为 0。
- `/etc/javalab/nginx/default.conf` 在容器内可读（compose 只读挂载生效）。

## 部署方式（本机镜像回退）

容器内 Maven 无法访问 Maven Central，`docker compose build chaos-controller` 会卡住，因此按项目既有
回退方式构建：

```bash
cd code && mvn -pl services/chaos-controller -am -DskipTests package
cd code/services/chaos-controller && docker build -f ../../../tmp/controller-runtime.Dockerfile \
  -t java-learn-lab/chaos-controller:jdk21 .
cd code/lab/docker && docker compose -f compose.base.yml -f compose.dev.yml --env-file .env \
  up -d --force-recreate chaos-controller
```

（`tmp/` 在 `.gitignore` 内，运行时 Dockerfile 不入库；正式链路仍是 `compose build`。）

## 响应样例（字段形状）

```json
{
  "data": {
    "runId": "run-20261029-005",
    "from": "2026-10-29T12:09:45Z",
    "to": "2026-10-29T12:19:30Z",
    "quality": "OK",
    "note": "命中数 = run 窗口内应用端 http_server_requests 增量（已排除 /actuator/**）…",
    "instances": [
      {"instance":"app-1","enabled":true,"hits":62600,"sharePercent":33.4,"success":61000,
       "failed":0,"avgMs":21.3,"p95Ms":88.1,"p99Ms":210.4,"rps":312.0,"errorRate":0.0,
       "rejected":0,"weight":1,"expectedSharePercent":33.333,"configLabel":"jdk21 · Xmx512m · G1 · applied rev27"}
    ],
    "upstream": {"available":true,"source":"/etc/javalab/nginx/default.conf","weights":{"app-1":1,"app-2":1,"app-3":1}},
    "warnings": []
  }
}
```

## 关键文件

- 后端：`traffic/InstanceStats{Service,Controller,Views}.java`、`traffic/{TrafficSplitProperties,TrafficSplitConfig,UpstreamWeights}.java`
- 复用：`metrics/MetricsService.java`（`requireRun`/`runWindow`/`RunWindow`）
- 前端：`components/TrafficSplitPanel.vue`、`components/RunLedger.vue`、`api.js`
- 配置：`application.yml`（`lab.traffic-split`）、`compose.base.yml` 挂载与环境变量、`.env.example`
