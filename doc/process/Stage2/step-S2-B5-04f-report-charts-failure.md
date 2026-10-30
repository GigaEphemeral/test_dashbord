# 步骤交接：报告图表补齐与一次失败实验的复盘

> 任务：用户反馈——报告缺 GC 停顿/缓存命中/数据库压力图；实验失败需定位
> 日期：2026-10-30
> 分支 / HEAD：`feature/stage2-workbench` / `59e120d`（本步改动本地待提交）
> 测试：dashboard build；console-smoke **161/0**（新增 7 图断言）；claims **84/84**；真机 7 图数据核对

# 当前进展

## 1. 报告图表补齐（实验记录 → run 详情 → run 时间线）

新增 4 张图（原有 k6/JVM 堆/GC 次数共 7 张）：

| 图 | PromQL（按 run 窗口） |
|---|---|
| GC 停顿时间（按实例） | `sum by (instance)(rate(jvm_gc_pause_seconds_sum{job="seckill-app"}[1m]))` ×1000 → ms/s |
| 缓存 L1 命中率（按实例） | `max by (instance)(cache_l1_hit_ratio{job="seckill-app"})` ×100 |
| 数据库 QPS（按节点） | `sum by (job, member)(rate(mysql_global_status_queries{job=~"mysqld-exporter|mysqld-exporter-ha"}[1m]))` |
| 数据库运行线程（按节点） | `max by (job, member)(mysql_global_status_threads_running{job=~"mysqld-exporter|mysqld-exporter-ha"})` |

- 真机核对（run-20261030-001）：GC 停顿 app-1 0.6 / app-2 0.9 / app-3 0.3 ms/s；缓存命中率三实例 ~32%；
  DB QPS mysql-1 28.8、mysql-2 4.3、mysql-3 4.8、standalone 292.4 /s；运行线程 4/4/4/2。
- **关于“GC 次数是否指 full GC”**：不是单一 full GC 指标。图表按 `action` 分
  `end of minor GC` / `end of major GC` 两条线；Serial/Parallel 的 major 基本等价 full GC，
  G1 的 major 是老年代回收（不保证整堆压缩）。停顿图按实例给出 GC 暂停速率。

## 2. 失败实验复盘（run-20261030-001）与修复

- 现象：`load` 10 分钟、200 VU，`FAILED`；step `k6-exit-failed`，`exitCode=99`。
- 证据：`flow_errors.value=0.1438`（阈值 `<0.01` 被击穿）；`server_errors=0`；p95/p99 阈值通过；
  运行参数 `PRODUCT_ID_MIN=4000, MAX=11000`；库内仅 1–10000。
- **根因（脚本语义缺陷）**：越界 ID 本就是缓存穿透的合法场景，但 `product()` 把 404 当成了
  `flow_errors`；越界占比 `1000/7001≈14.3%`，与错误率一致（`/api/products/10500 → 404`，`/9000 → 200`）。
  也就是说失败来自 **k6 流程对 404 的判定**，不是集群/控制器故障，也不该用“别越界”来规避。
- **修复**：`PRODUCT_DB_MAX`（默认 10000，k6 env 可覆盖）之上的 ID 视为穿透：
  `acceptStatuses=[200,404]`、`requireCode=false`，404 计入新计数器 `product_missing`（带 `flow=product` 标签），
  不再写入 `flow_errors`；库内 ID 仍严格要求 200 + id 匹配。
- **验证**：`bench.sh load -- PRODUCT_ID_MIN=9000 PRODUCT_ID_MAX=11000 LOAD_DURATION=20s` →
  `flow_errors=0`、`product_missing.count=142`、`checks=100%`、`BENCH PASSED`。
- 向导提示同步改为“404 计入 product_missing，不计入 flow_errors”。

## 3. 环境恢复（顺带发现并处理）

- 发现 `jl-mysql-2/3`、`jl-redis-2/3` 被正常停止（exit 0），InnoDB Cluster 实际为 OFFLINE
  （`mysql_cluster_probe_up=0` 导致预检查 BLOCKED，Toxiproxy 不可达为 WARN）。
- 处理：启动 redis-2/3；mysql-1 重新 `group_replication_bootstrap_group`，mysql-2/3 `START GROUP_REPLICATION`；
  3/3 ONLINE、Router 重启后 healthy、exporter `up=1`、probe=1；预检查恢复 WARN。
- 已确认真机崩溃原因非本轮代码；停止事件无日志可追溯（无 DC 事件缓存），后续若再现，用 `docker events` 捕获。

# 完成功能项

- `RunTimelines.vue`：4 张新图 + 节点名（standalone/mysql-N）解析。
- `ProductSamplingField.vue`：越界占比警告。
- `console-smoke.mjs`：run 启动后断言 7 张报告图存在。
- 环境：MySQL 集群/路由/Redis 恢复，Prometheus 指标恢复。

# 设计逻辑

- 报告按 run 窗口取数，与 Grafana 同源；缺数据显示“无数据”，不补 0。
- 数据库指标同时覆盖 standalone 与 3 个 HA 成员，按 `member` 分线、standalone 单独命名。
- 越界采样是缓存穿透的**有意能力**，因此只警告不阻断；正式压测需自行选择库内范围。

# 存在缺陷项&TODO

| 项 | 说明 |
|---|---|
| k6 状态码分布 | 除 `product_missing` 外仍无通用按状态码计数器；后续可按需补充 |
| 集群自动恢复 | 容器被外部停止后 GR 不会自动 bootstrap，需要人工（本轮已手工恢复并记录步骤） |
| Toxiproxy 预检查 | dev 栈未启用 chaos-proxy 时固定 WARN；可考虑按 profile 自动关闭 |

# 待定事项和注意事项

1. 报告时间线仅在 run 已启动后渲染（DRAFT 无窗口），冒烟断言已按此顺序放置。
2. 数据库图依赖 exporter；若节点 exporter down，该节点不显示（无数据），不会补 0。
3. 本轮仅前端/脚本变更，已重建 dist；无需重建控制器。

# 附录：核对命令

```text
GET /prom/api/v1/query?query=up{job="mysqld-exporter-ha"}        -> mysql-1/2/3 = 1
GET /prom/api/v1/query?query=mysql_cluster_probe_up              -> 1
performance_schema.replication_group_members                     -> 3/3 ONLINE
浏览器 run-20261030-001：7 张图 legend 均有真实值
```
