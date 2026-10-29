# 步骤交接：Stage2 B3-00 交互对齐（实现）

> 任务/步骤：Stage2 **B3-00 交互对齐**：后端草案契约、故障时间线、6 分区控制台、7 步向导、监控时间轴与 k6 进度、实验记录名称/归档/删除
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `554a55a`（本步改动**尚未提交**）
> 关联任务：设计阶段交接见 `step-S2-B3-00-interaction-alignment.md`；计划 v1.4 B3-00；方案 §16；交互稿 v4
> 测试 / verify：reactor **1280 tests / 0 fail**（chaos 725、app 469、jdk8 59、core 13、platform 14）；k6 node **113 pass**；console-smoke **132 PASS / 0 FAIL**；doc claims **47/47 PASS**；verify **109 PASS / 1 WARN / 0 FAIL**（唯一 WARN=cAdvisor/WSL2）
> 运行状态：dev 栈运行中；`jl-chaos-controller` 已用新 jar 重建并 healthy（imageId `0ec4a2f3…`，Flyway v3）；验证用的草稿 run 已删除，台账无遗留 RUNNING、无 `jl-k6-*`
> 口径指针：2026-10-29 本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次可能已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- B3-00 的**代码实现**完成：后端草案字段与契约、故障时间线执行器、6 分区控制台、7 步实验向导、监控时间轴/k6 进度、实验记录名称/归档/删除与报告对比。
- 闸门：reactor **1280/0**（B2 收口 1231 → +49）；console-smoke **132/0**（B2 74 → +58，覆盖新 IA/向导/归档删除）；k6 node **113**（+8）；claims **47**（+6）。
- 真机验证：带名称/节点/配置组/故障时间线的草稿可创建；节点门禁预检查按启用节点数校验；调度故障按偏移注入；manual kill/freeze 无自动回滚、stop-all 可清理；归档隐藏、取消归档、彻底删除（含报告目录）全部通过（证据见附录）。
- 未做：B4 的“应用并重启/回滚”真实生效与参数回读（向导/实例页按钮已按 B4 禁用并提示）；B3-01 的独立 Caffeine 开关、B3-02 的 10,000 商品 seed 与键查询；独立 Qwen 批次审核与提交/推送。

# 完成功能项

## 后端（chaos-controller）

- **台账 V3 迁移** `db/ledger/V3__run_draft_fields.sql`：`lab_run` 增加 `name`、`nodes`、`archived` 与 `(archived, created_at)` 索引；V3 已在真机 Flyway 应用。
- **草案契约**：`POST /lab/runs` 接受 `name`、`nodes`、`config`、`faultSchedule`；`request`/`config`/`faultSchedule` 分别落 `lab_run_param` 组；名称、节点白名单（`app-N` 形态、去重、`lab.ledger.max-nodes`）、payload 上限、敏感键校验沿用台账守卫。
- **节点门禁**：预检查 `monitoring.ready` 在草稿有节点选择时按启用节点的抓取目标数校验（真机 `requiredAppTargets=2, appUp=2`）；`images.consistent` 只检查启用节点对应容器；定向流量目标必须属于启用节点（`BenchLaunchPlanner`）。
- **归档/删除**：`POST /lab/runs/{id}/archive|unarchive`、`DELETE /lab/runs/{id}`；只允许草稿、终态或 UNKNOWN；删除前清理 k6 容器与报告目录，行删除级联全部子表；`GET /lab/runs` 新增 `includeArchived`；保留策略**不清理**归档 run，归档也不占 keep 名额。
- **故障时间线**（`chaos/schedule/`）：创建时校验/规范化条目（`atSeconds`、`target`、`action`、`recovery`、`ttlSeconds`）；`RunLifecycleService` 在 k6 启动后、READY→RUNNING 前 `arm`，失败走 `closeFailedStart`（停 k6、run FAILED）；stop/failed-start/stop-all 取消未执行项；每个到期动作走统一 operation 契约并写 `lab_fault_event`，失败不写“已注入”。
- **手动保持**：`ChaosActionService` 对 kill/freeze 接受 `ttlSeconds=-1`（无 expiry、无自动回滚），`/chaos/actions` 返回 `expiresAt=null, remainingMs=null`；`stop-all` 仍可回滚；`recovery=manual` 条目由调度器映射为 `-1`。
- **商品采样**：`load` 场景新增 `PRODUCT_ID_MIN/MAX/DISTRIBUTION/CONCENTRATION`（默认 `1/1/uniform/0`，保持旧行为）；k6 `lib/product-sampling.js` 提供 uniform/normal（集中度 0–100）与解析校验；只作用于 `load` 读路径。
- 配置外置：`LAB_LEDGER_MAX_NODES`、`LAB_LEDGER_MAX_FAULT_ITEMS`、`LAB_FAULT_SCHEDULE_MAX_OFFSET_SECONDS/THREAD_NAME/POOL_SIZE` 进 `application.yml` + `compose.base.yml` + `.env.example`（默认一致）。

## 控制台（Vue）

- **6 分区 IA**：总览 / 场景向导 / 实例与 JVM / Benchmark / 实时监控与操作 / 实验记录；`nav-faults` 移除，故障与缓存能力进入监控页的“实时操作/实验工具”标签，原组件与 testid 保留。
- **7 步向导**（`ExperimentWizard.vue` + `ConfigGroupEditor`/`K6ParamsForm`/`FaultTimelineEditor`/`ProductSamplingField`）：基础配置（Java 节点多选、MySQL 单机/集群、Redis HA、chaos-proxy、sidecars）→ 实验目标（门禁）→ 目标与流量 → 变量与配置组（JVM/GC/业务池/Tomcat/Caffeine/GC 日志，中文 tooltip）→ k6 结构化表单 + 商品采样预览 + JSON 预览 → 故障时间线（manual 时 TTL 置灰）→ 预检查与执行（测试名称、创建草稿、预检查、启动）→ 报告与对比；旧“故障演练”作为同页第二标签保留。
- **实例页**：未启用节点置灰并显示“未启用”；卡片注明“完整参数在向导第 3 步”；应用/重启按 B4 预留（不伪实现）。
- **实时监控与操作**：监控标签含 KPI、k6 进度（VU/iterations/s/http_reqs/s，按 `run_id`）、四类 Prometheus 时间轴（请求性能 / k6 / JVM-GC / 线程池-连接池）、实例指标表、完整拓扑（未启用置灰、保留全量节点）；实时操作/实验工具保留容器故障、缓存、线程、动画能力；无数据显示“无数据”，不显示 0。
- **实验记录**：测试名称列/筛选、显示已归档开关、归档/取消归档、删除二次确认（运行中禁用并提示先停止）、详情 kv（名称/节点/流量/写锁/归档）、两次 run 对比表（RDI/P95 等取聚合层，缺数据显示“无数据”）。

## 测试

- 新增/更新 Java 测试：草案字段与服务校验（`LedgerSchemaIT`/`LedgerControllerTest`/`LedgerRetentionTest`）、节点门禁（`RunPrecheckServiceTest`/`BenchLaunchPlannerTest`）、调度器与手动保持（`schedule/*`、`ChaosActionServiceTest`、`ChaosActionStateMachineTest`、`RunLifecycleFaultScheduleTest`、`EnvironmentStopAllServiceTest`）。
- k6：`product-sampling.test.mjs` 与 manifest 默认值断言；console-smoke 重写为 132 条（新 IA、向导、归档删除、无数据语义）。
- `claims.yml` 新增 6 条（草案字段、归档删除、节点门禁、故障时间线、商品采样、6 分区），共 47 条。

# 设计逻辑

- **草稿 ≠ 运行态**：节点选择、配置组、故障时间线只写台账草稿；未启用节点不停止容器，只缩小实验范围。因此“未启用”只在配置/观测/门禁上生效，**via-nginx 仍会打到全部 upstream**（精确对照必须用 target-instance），这是本步明确的已知限制。
- **门禁在服务端也要成立**：预检查不能只看 UI，`monitoring.ready` 按节点重算、定向实例必须属于启用节点；否则“2 节点实验”会被 3 节点抓取目标卡住或被偷换目标。
- **故障时间线属于实验**：arm 失败就让 run FAILED 并停 k6，而不是静默少注入；每个动作走 operation 契约，时间线只有真实生效才记录注入事件。
- **手动保持复用已有语义**：`ttlSeconds=-1` → 无 expiry、无自动回滚，但仍留在 active 列表，stop-all 照常清理；不改 pause=TTL 冻结的全局语义。
- **删除边界**：只允许草稿/终态/UNKNOWN；先清 k6 与报告目录再删行；归档 run 不被保留策略清掉，避免“归档=即将被删”。
- **配置组只做交互与持久化**：JVM/GC/线程池/Caffeine/GC 日志字段在 B3-00 只做表单、校验提示与草稿存储；真正应用/回滚/回读属 B4，按钮禁用并写明“B4 落地”，不伪装已生效。
- **监控数据源同源**：前端直接查 `/prom/api/v1/query_range`（与 Grafana 同源），k6 指标按 remote-write 的 `run_id` 过滤；无序列显示“无数据”。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 配置组未生效 | 向导第 3 步的 JVM/GC/池/Caffeine/GC 日志只存草稿；应用/重启/回滚按钮禁用 | B4-01/02/04 |
| 配置组无 schema 校验 | 现场按白名单字段渲染，但服务端未校验 `config` 组内容（仅 payload/敏感键限制） | B4-01 |
| via-nginx 不排除未启用节点 | Nginx upstream 仍含全部实例；定向模式才精确隔离；页面已提示 | B5/专项 |
| 商品采样只覆盖 load | smoke/spike 仍用固定商品；10,000 seed 未落 | B3-02 |
| JVM/池执行器指标缺口 | `executor_rejected_total` 未注册、`executor_*` 当前无样本；实例表按“无数据”显示 | B5-04 |
| 手动保持的 operation 记录 | 调度故障的 `lab_operation.ttl_seconds=-1`，`lab_fault_event` 的 ttl 为 null；两者口径不同但都有据 | 记录 |
| 归档保留策略 | 归档 run 不参与保留清理；长期归档会无限增长（本地实验可接受） | 记录 |
| B3 批次审核 | 本步未做独立 Qwen 审核/提交；按计划在 B3 收口统一审核 | B3 收口 |
| 行尾 | 文档 LF；`application.yml/compose/.env.example/App.vue/claims.yml` 保持 CRLF；新 Java/k6 文件 LF | 纪律 |

# 待定事项和注意事项

1. **下一步**：B3-01 独立 Caffeine L1 开关；B3-02 10,000 商品 seed + SCAN 键查询；随后 B3-03 A/B 对照、B3-04 对账。
2. **提交/推送**：本步改动未提交、未推送；按发布纪律等待明确授权，再按 `feat(Stage2): B3-00 ...` 提交并更新计划基线。
3. **重建控制器**：本机 Maven Central TLS 不可用时按既有离线回退——宿主 `mvn -o -pl services/chaos-controller -am -DskipTests package`，再用仅运行时临时 Dockerfile 打 `java-learn-lab/chaos-controller:jdk21`，`compose ... up -d --no-deps --force-recreate chaos-controller`；仓库 Dockerfile 未改。
4. **数据状态**：验证草稿已删除；`jl-app-1` 曾按时间线 restart 一次（已 healthy）；无 RUNNING run、无 k6 容器、traffic 停止。
5. **Windows 编码**：Git Bash 直接用 `-d '{"name":"中文"}'` 会把中文按 GBK 发出并被服务端拒绝为 invalid JSON；验证/脚本用 UTF-8 文件 + `--data-binary` 或 node fetch。
6. **前端构建**：`code/lab/dashboard/dist` 已重建；Nginx 直接托管该目录，不需要重启容器。

# 附录

## 真机证据（2026-10-29）

- 草稿 `run-20261028-027`：`name="B3-00a live check"`、`nodes=["app-1","app-2"]`、param 组 `request,config,faultSchedule`。
- 预检查：`verdict=WARN`（唯一 WARN = toxiproxy 未起），`monitoring.ready PASS`，detail `{"requiredAppTargets":2,"enabledNodes":["app-1","app-2"],"appUp":2,...}`。
- 启动后 t≈4s：`/chaos/actions` → `jl-app-2 freeze`，`ttlSeconds=null, expiresAt=null, remainingMs=null, status=active`；run faults 记录 `source=container,event=freeze`。
- t≈10s：第二条 `jl-app-1 restart` 事件 `status=completed`。
- 停止 run → `COMPLETED`、k6 `EXITED`；`stop-all` → `reverted=1`，actions 清空，app-2 healthy。
- 归档：默认列表不含该 run，`includeArchived=true` 含且 `archived=true`；删除 → `{"deleted":true,"artifacts":"{k6ContainerRemoved=false, k6ReportRemoved=true}"}`，随后详情 404、报告目录不存在。
- 控制台：`console-smoke.sh --no-build` → **132 PASS / 0 FAIL**（连跑 3 次由实现代理执行，主会话复核 1 次）。
- 控制器：Flyway `2 → 3`；容器 imageId `sha256:0ec4a2f38…`。

## 验证命令

```bash
cd code && mvn -o test                                    # 1280 tests / 0 fail
node --test "code/lab/k6/test/*.test.mjs"                 # 113 pass
bash code/lab/scripts/console-smoke.sh                    # 132 PASS / 0 FAIL
bash code/lab/scripts/check-doc-claims.sh                 # 47/47 PASS
bash code/lab/scripts/verify.sh                           # 109 PASS / 1 WARN / 0 FAIL
```

## 关键文件

- 迁移：`code/services/chaos-controller/src/main/resources/db/ledger/V3__run_draft_fields.sql`
- 台账：`ledger/{LedgerController,LedgerViews,RunLedgerService,RunLedgerRepository,LedgerWriter,LedgerStash,RunRecord,RunLifecycleService}.java`
- 预检查/规划：`precheck/RunPrecheckService.java`、`bench/exec/BenchLaunchPlanner.java`
- 调度：`schedule/{FaultScheduleService,FaultScheduleValidator,FaultScheduleEntry,FaultScheduler,FaultScheduleProperties,FaultScheduleConfig}.java`
- 手动保持：`action/{ChaosAction,ChaosActionService}.java`
- 前端：`lab/dashboard/src/{App.vue,api.js,style.css}`、`components/{ExperimentWizard,ConfigGroupEditor,K6ParamsForm,FaultTimelineEditor,ProductSamplingField,MonitorPage,MonitorTimeline,K6Progress,InstanceMetricsTable,RunLedger,InstancesView,InstancesPanel,TopologyView,ObservePanel}.vue`
- k6：`lab/k6/lib/product-sampling.js`、`scenarios/{manifest.json,load.js}`、`test/product-sampling.test.mjs`
- 冒烟：`lab/scripts/console-smoke.{sh,mjs}`
