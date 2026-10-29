# 步骤交接：Stage2 B5-01a 拓扑读契约 + B5-04a 指标缺口分级

> 任务/步骤：**B5-01a** `GET /lab/topology` 统一拓扑/指标/质量状态；**B5-04a** 指标字典缺口分类与 B5-04b 施工单
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `47539ee`（本步改动本地待提交）
> 关联：计划 B5-00/B5-01/B5-04；设计 §9.5/§9.7/§16.10
> 测试：拓扑新测 **14/0**；`Topology*Test + LedgerControllerTest` 定向通过；拓扑真机 200（25 节点/30 边）
> 运行状态：控制器已含拓扑端点；mysql 3/3 ONLINE；console-smoke 145/0；app-1 基线 APPLIED

# 当前进展

- B5-01a 完成：`GET /lab/topology` 返回完整 16 类拓扑节点（含未运行/未启用）、30 条静态边、每节点健康/可观测性/JDK/镜像/指标/质量状态；sidecar 默认隐藏、`includeSidecars=true` 返回 `hidden=true`；2s 读缓存。
- B5-04a 完成：`b5-metrics-gap-plan.md` 逐行复核 220 条，给出 50 条缺口分类（WIRE/DERIVED/UNWIRED/DEGRADED）、B5-04b 前 7 项施工单、无需接线清单；并纠正 7 处字典/目录过期口径。
- 真机样例：app-1 `UP/OK`、cpu 0.127、heap 224MB、poolCore 4；redis/mysql/nginx 有单序列；mysql-router `UNWIRED`（无 Prometheus 源）。

# 完成功能项

- controller `topology/`：`TopologyService/Controller/Views/Properties/Config`；`lab.topology.*` 15 键（job/label/窗口/缓存/sidecar 名单/集群节点数）。
- 指标：app CPU/heap/线程/池/HTTP RPS/均值/P95；redis `redis_up`+内存；mysql/nginx/rabbit 单序列；router/sentinel/toxiproxy 无源 → UNWIRED。
- 启用节点：query `enabled=` 优先，否则最近一个有 `nodes` 的 run，否则全部 app；未启用节点保留但 `enabled=false`。
- `application.yml` + compose + `.env.example` 转发拓扑键（可选覆盖，默认可用）。
- B5-04a 文档：`doc/process/Stage2/b5-metrics-gap-plan.md`；`指标字典.md` 顶部加 3 行指针（不改行）。

# 设计逻辑

- 前端不再自己拼多次 PromQL/容器列表：一个契约返回节点、边、质量状态、启用状态，B5-01b 只做热度和 tooltip。
- 缺数据一律 `null` + `NO_DATA/UNWIRED/PARTIAL`，不补 0；router/sentinel/toxiproxy 明确 UNWIRED。
- 拓扑固定包含设计 §16.3 的 16 类节点；standalone `jl-redis`/`jl-mysql` 不在设计拓扑内，不混入。
- 2s 缓存避免 5s UI 轮询把 Prometheus 查询放大；窗口/缓存外置。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 热度 UI/tooltip | 契约已就绪，MonitorPage/TopologyView 未接 | B5-01b/c |
| nginx/mysql/rabbit 单序列 | 契约无专用字段，暂放 notes | B5-01b 或扩契约 |
| cAdvisor 容器 CPU/内存/网络 | 实机 degrade（containerd snapshotter），拓扑的 memUsed/limit 为 null | B5-04b/DEGRADED |
| 拓扑测试 1 条 flake | 全量 controller 曾报 `LedgerControllerTest` 1 error，定向复跑通过；非本步改动 | 记录 |
| B5-04b | 前 7 项待实施（k6 summary/threshold、Tomcat 目录、GC/OOM 事件、JVM 参数、Router UNWIRED 等） | B5-04b |

# 待定事项和注意事项

1. 下一步 Wave 2：B5-01b/c 热度 UI + A16；B5-04b 指标接线/降级；两者文件不重叠可并行。
2. 拓扑端点 token 保护；`/lab/topology` 经 `/lab-api` 访问，Nginx 300s 不影响。
3. 现场 mysql-3 已 clone 复组，拓扑中 mysql-3 有 exporter 序列。

# 附录

## 真机样例

```json
GET /lab-api/lab/topology -> 200, nodes=25, edges=30, enabled=[app-1,app-2,app-3]
jl-app-1: health=UP dataQuality=OK cpu=0.127 heapUsed=224846528 poolCore=4
jl-mysql-router: dataQuality=UNWIRED, note="MySQL Router exposes no Prometheus series"
```

## 关键文件

- controller：`topology/*`、`application.yml`
- 文档：`doc/process/Stage2/b5-metrics-gap-plan.md`、`指标字典.md` 指针
- 配置：`compose.base.yml`、`.env.example`（LAB_TOPOLOGY_*）
