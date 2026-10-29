# Stage2 开发、测试与步骤验收计划

> 版本：v1.4（2026-10-29）
> 分支基线：`feature/stage2-workbench`（从 `feature/ha-lab` HEAD `13d961e` 拉出）
> 依据：`doc/design/Stage2/stage2-需求.md`（需求基线 v1，含 §十一 范围确认结论）、
> `doc/design/Stage2/stage2改造方案.md`（v1.5）、
> `doc/process/Stage2/架构现状与功能清单.md`（事实底座）、`doc/design/工程规约.md`
> 本文件是 Stage2 的执行计划：批次 → 步骤 → 开发内容 / 测试 / 验收闸门 / 交接文档。
> 每步完成即按工程规约 §2 写 `doc/process/Stage2/step-S2-*.md` 五段式交接并随代码提交。

---

## 0. 全局闸门与现行基线

每个步骤收尾必须至少跑所属模块的测试；每个批次收口必须跑全量闸门并记录新基线。

| 闸门 | 命令 | Stage1 收口基线（起点） | B0 收口基线（现行，2026-10-28） |
|---|---|---|---|
| Java 单测 | `mvn test`（reactor，仓库根 `code/pom.xml`） | 784 tests / BUILD SUCCESS | **851 tests / BUILD SUCCESS**（app-jdk21 469 + chaos 296 + jdk8 59 + core 13 + platform 14） |
| k6 脚本回归 | `node --test "code/lab/k6/test/*.test.mjs"` | 79 pass / 0 fail | 79 pass / 0 fail（未变） |
| 全栈验收 | `bash code/lab/scripts/verify.sh` | 109 PASS / 1 WARN / 0 FAIL（唯一 WARN = cAdvisor/WSL2） | 109 PASS / 1 WARN / 0 FAIL（未变） |
| HTTP 冒烟 | `bash code/lab/scripts/smoke.sh` | 7/7，末行 `SMOKE PASSED.` | 7/7（未变） |
| 控制台冒烟 | `bash code/lab/scripts/console-smoke.sh`（Playwright） | 42 断言 / 0 失败 | **63 断言 / 0 失败**（B1-05 侧边导航重写后的新基线；B0 阶段 42 未重跑） |
| 文档断言 | `bash code/lab/scripts/check-doc-claims.sh` | 32/32 PASS | **37/37 PASS**（B1 新增 5 条：台账 V2/操作契约/指标目录/预检查/侧边导航） |
| 安全静态检查 | `bash code/lab/scripts/test-lab-safety.sh` | 通过 | 通过 |

> **B1 现行基线（2026-10-28，B1-04 后）**：reactor **1029 tests**（chaos-controller **474**，含 Testcontainers
> `LedgerSchemaIT`/`EnvLockIT`/`RunLifecycleIT`/`OperationServiceIT`/`MetricWindowLedgerIT` 与 precheck 全套；app 469 / jdk8 59 / core 13 /
> platform 分别 10+4），闸门：verify 109 PASS/1 WARN、smoke 7/7、k6 79、claims 32、console-smoke **63/0**（B1-05 重写后基线）。
> 真机证据见 `step-S2-B1-01-ledger-persistence.md` 附录 F、`step-S2-B1-02-operation-contract.md` 附录 E、
> `step-S2-B1-03-metrics-aggregation.md` 与 `step-S2-B1-04-run-precheck.md` 的补充节。
> 历史：B1-03 后 990/435；B1-02 后 939/384；B1-01 后 888/333；B0 收口 851/296。
>
> **B2 现行基线（2026-10-28，批次收口）**：reactor **1231**（chaos **676**、app 469、jdk8 59、core 13、
> platform 14）、console-smoke **74/0**、k6 node **105**、claims **41**、verify 109/1、smoke 7/7、env 接线 93。
> 真机 A01/A17/A20/A09/A12/A29 与遗留清单见 `step-S2-B2-00-closure.md`。
> 历史：B2-04 后 chaos 676；B2-03 后 603；B2-02 后 591；B2-01 后 491；B1 收口 474。

> **B3-00 现行基线（2026-10-29，交互对齐实现）**：reactor **1280**（chaos 725、app 469、jdk8 59、core 13、
> platform 14）、console-smoke **132/0**、k6 node **113**、claims **47**、verify 109/1（smoke 7/7 为 B2 基线，本步未重跑）。
> 真机证据与缺口见 `step-S2-B3-00a-interaction-alignment-implementation.md`。
> **B3 批次基线（2026-10-29，B3-01..04 实现）**：reactor **1337**（chaos 756、app 495、jdk8 59、core 13、platform 14）、console-smoke **138/0**、
> k6 node **113**、claims **52**、verify 109/1、smoke 7/7。真机：10,000 商品基线、L1 三实例独立、SCAN 键分页/单键详情/
> 重置、A/B 一对、对账 7 项 PASS。独立 Qwen 审核 **Approved with fixes**（1 Medium 已修，`4e925ae`）。
> 交接见 `step-S2-B3-01-cache-experiments.md`。

> **B4-01 基线（2026-10-29）**：reactor **1385**（chaos 790、app 509、jdk8 59、core 13、platform 14）、smoke 7/7、
> verify 109/1、console-smoke 138/0（B4-01 未改 UI）、claims 54。真机：`/lab/pool`、草稿 708/768、effective 回读、
> JDK8 argv 与三类拒绝。交接 `step-S2-B4-01-instance-config-schema.md`。

> **B4-02 基线（2026-10-29）**：chaos 模块 **812 / 0 fail**、smoke 7/7、verify 109/1、console-smoke **138/0**。真机：app-1
> apply（384m/池 2-4-10）与 rollback（512m/池 4-8-50）9 项检查全 PASS，app-2/3 未变；失败自动恢复 2 次。
> Nginx `/lab-api` 超时 300s、apply 240s、health 150s。交接 `step-S2-B4-02-controlled-rebuild.md`。

规约要点（每一步都适用）：

1. **配置外置**：新增路径/超时/TTL/池大小/端口/键前缀一律进 `application.yml` + `@ConfigurationProperties`、
   compose `${VAR:-default}` 或 `.env.example`；评审必查硬编码，发现即在**同一任务内**修复并补测试。
2. **交接**：一步一文档 `step-S2-<批次>-<序号>-<slug>.md`，五段式 + 附录（命令/提交/证据）；无新增也要写"本步无新增"。
3. **评审**：每批次收口后在独立 Qwen 会话 + 隔离 worktree 做规范与代码审核；High/Medium 在 worktree 修复提交，
   主会话等报告齐后 cherry-pick，再统一 verify。审核会话不动主工作树和共享 Docker 栈。
4. **演示优先**：不引入生产加固、多租户、权限体系；所有新 UI 动作必须可暂停/可还原。
5. **状态语义**：任何"目标配置 vs 实际生效"必须分离显示（`REQUESTED/APPLIED/RESTORED/PENDING_CONFIRMATION/FAILED`），
   无数据一律 `NO_DATA/DEGRADED`，不得用 0 冒充。

批次顺序与门禁（改造方案 §11/§14）：

```
B0（硬性前置门禁）→ B1 → B2 → B3 → B4 → B5
B3 依赖 B0 的 stats/代理/TTL 修复；B4 依赖 B1 台账 + B2 执行层；B5 依赖全部。
```

---

## 1. B0 · Stage1 基线修复与指标可用性审计（B1 的前置门禁）

> **收口状态（2026-10-28）：已收口。** 8 个步骤全部合并、全量闸门见 §0 现行基线；
> 真机补验台账见 `b0-live-verification.md`（R1 六项 + R2 四项 + 收口终验），有意顺延项已在该台账登记。

**收口标准（改造方案 §10.4/§11）**：关键指标有真实序列或明确标记未接线；缓存 TTL/统计可核验；
代理分页正常；router 复位目标正确。B0 未通过前，相关实验只能标 `DEGRADED/INVALID`，不得生成无条件 PASS。

### B0-01 指标字典与接线审计

- 开发：建立 `doc/process/Stage2/指标字典.md`——每项指标登记来源 job、指标名、标签、PromQL 模板、聚合窗口、
  空数据语义（`NO_DATA/COLLECTING/DEGRADED/OK`）、接线状态、验收证据。覆盖改造方案 §6.2 全部目录。
- 测试：对字典中每项跑一次真实 PromQL（经 `/prom/api/v1/query`），核对有无序列、标签是否正确；
  结果写入字典"验收证据"列。字典条目进 `claims.yml` 抽样断言（防文档说反话）。
- 验收：字典存在且 Stage1 已知 39 条缺口（details/README §3.2）中"未接线/口径"类全部有结论；
  禁止出现"面板有图但无真实数据"而未标注的条目。
- 交接：`step-S2-B0-01-metrics-dictionary.md`。

### B0-02 `stock_oversold_total` 真实递增

- 开发：修复真实业务路径递增（库存扣减被 DB 拒绝时计数），或按字典标未接线并以对账 SQL 为准——**推荐两者都做**。
- 测试：单测构造 `UPDATE … WHERE available >= :quantity` 失败路径断言计数 +1；真机造样本（关 `lock` + 高压）核对计数变化。
- 验收：造出可验证样本，证明计数/对账结果随缺陷变化；verify.sh 增加"oversold 恒 0"断言保持不弱化。
- 交接：`step-S2-B0-02-oversold-counter.md`。

### B0-03 Caffeine stats 绑定 Micrometer + `estimatedSize` 导出

- 开发：每实例导出 L1 命中/未命中/命中率/驱逐/`estimatedSize()`（标"估算"）；低基数标签 `instance`。
  L1 关闭时输出"不适用"状态而非 0。
- 测试：单测（缓存读写后 stats 变化）+ 真机 curl 三实例 `/actuator/prometheus` 序列存在且数值真实。
- 验收：Prometheus 有真实序列；UI/D10 区分"无数据"与 0；对应 A04 前置。
- 交接：`step-S2-B0-03-caffeine-stats.md`。

### B0-04 `TwoLevelCache` L2 TTL 外置 + `LAB_CACHE_*` 接线

- 开发：L2 TTL、键前缀、`RETRY_SLEEP_MILLIS`、连接池 `pool=10` 等硬编码外置到 yml + env；
  补进 compose 与 `.env.example`；提供有效值回读端点（纳入后续 run 快照）。
- 测试：配置绑定单测（默认值 + env 覆盖）；真机改 TTL 后 `GET /lab/cache/state`/单键 TTL 三者一致。
- 验收：配置、应用有效值、键 TTL 一致；评审确认无新增硬编码。
- 交接：`step-S2-B0-04-cache-config-externalization.md`。

### B0-05 窄代理修复：query string / body / 响应上限

- 开发：`AppProxyService` 白名单支持 query string 透传；配置请求体、响应体字节上限与超时（全外置）；
  为 B3 的 `/lab/cache/keys?cursor=` 预留路由。
- 测试：单测覆盖带 query/POST body/超限 413 路径；真机经 Nginx → `/lab-api/chaos/app/...` 分页游标可用。
- 验收：A23、A24 前半（分页保留 + 响应受限）。
- 交接：`step-S2-B0-05-proxy-query-string.md`。

### B0-06 `reset-lab.sh` 复位目标按拓扑解析

- 开发：复位目标由当前 profile/拓扑决定（router 模式指向集群，不再默认单机 `jl-mysql`）；执行前打印目标容器/库；`--dry-run` 保留。
- 测试：shell 层用 mock 拓扑断言目标选择；真机在 db-ha 栈跑 `--dry-run` 核对日志指向集群。
- 验收：A25。
- 交接：`step-S2-B0-06-reset-target-resolution.md`。

### B0-07 Sentinel / HA Redis exporter 纳入抓取

- 开发：`prometheus.yml` 增加 sentinel + HA redis exporter 抓取目标与健康检查；无序列时按场景阻断或降级 HA 验收。
- 测试：`--profile ha` 真机核对 target 全 UP、关键序列可查。
- 验收：HA 页面不存在"有图无数据"；对应字典更新。
- 交接：`step-S2-B0-07-ha-exporters-scrape.md`。

### B0-08 镜像身份最小记录 + MySQL 探针与故障目标分离

- 开发：按**最小闭环**（确认 #8，方案 §10.4）——启动/重建后执行一次 `docker compose images` 或
  `docker inspect --format '{{.Image}}'`，把 imageTag + imageId 写入 run manifest/报告；重建显式
  `--force-recreate`；不做来源校验、不归档构建日志、不锁 base digest。探针容器与可杀目标强制分离
  （演练前动态确认 PRIMARY，拒绝对探针执行杀主）。
- 测试：单测（tag/id 读取、探针隔离规则）；真机 db-failover 演练期间 `mysql_cluster_probe_up` 恒 1。
- 验收：运行容器 tag/id 与 manifest 一致；**Stage1 遗留 P0#3（官方 build 溯源证据）以"最小闭环"口径收口**，
  完整溯源进 Future（方案 §15）。
- 交接：`step-S2-B0-08-image-identity-probe-isolation.md`。

### B0 收口

全量闸门重跑并记录新基线；`doc/process/Stage2/step-S2-B0-00-closure.md` 汇总审计结论；
未接线项清单同步进指标字典。**B0 未收口，不开 B1。**

---

## 2. B1 · 持久化台账、状态机、观测底座与环境锁

> **收口状态（2026-10-28）：已收口。** 5 个步骤全部合并并通过真机验证（A19/A21/A22/A26/A29 + console-smoke 63/0），
> 批次闸门与遗留清单见 `step-S2-B1-00-closure.md` 与 §0 现行基线。

**收口标准**：浏览器刷新或控制器重启后状态可恢复；冲突 run 被拒绝；无数据不会显示成 0。

### B1-01 台账持久化（已确认：复用栈内 MySQL）

- 开发：run/operation/配置快照/故障事件/审计记录持久化到**栈内 MySQL 的独立台账库**（standalone 写 `jl-mysql`，
  db-ha 经 Router 6446；目标解析与 B0-06 复位同口径）；不引入嵌入式库或新服务。台账写入必须可重试/可暂存——
  MySQL 杀主演练恰会命中台账写入窗口，`UNKNOWN` 恢复逻辑要覆盖"故障事件自身没记上"。状态机按方案 §10.5：
  `DRAFT→VALIDATING→READY→RUNNING→STOPPING→VERIFYING→COMPLETED / FAILED / INVALID / ABORTED`，
  控制器重启时未结束 run 标 `UNKNOWN` 并列出清理项。
  库名与表清单已定稿（方案 §10.5）：库 `lab_ledger`（与业务库 `seckill` 物理分库），表
  `lab_run`/`lab_run_step`/`lab_run_env`/`lab_run_param`/`lab_operation`/`lab_fault_event`/`lab_metric_window`/`lab_run_check`/`lab_ledger_stash`/`lab_env_lock`；
  schema 用 Flyway 版本化；保留策略默认最近 50 个 run（级联清理，配置外置）。
- 测试：单测状态转换合法性 + 台账库写失败重试路径；重启 chaos 容器后台账仍在、未决动作可见（A21）；
  db-ha 场演练期间台账不丢（暂存-补写路径）。
- 验收：台账 CRUD API + 重启恢复通过；台账库 schema 版本化（Flyway 或等价）；与业务表物理分库。

### B1-02 `operationId` 操作契约 + 幂等与环境锁

- 开发：启动 run、停止 run、应用配置、故障注入/撤销统一返回 `operationId`；幂等键防重复点击；
  超时轮询与"待确认"状态（请求超时 ≠ 失败）；写型 run 环境锁（默认单活动写 run，冲突返回持有者与解除方式）。
- 测试：单测（幂等、锁互斥、超时后后台核验）；真机 A19、A29。
- 验收：同一动作重复提交不产生第二个副作用；锁冲突提示含 run ID。

### B1-03 指标查询聚合层（按实例 × 时间窗）

- 开发：chaos-controller 经 Prometheus HTTP API 提供窗口聚合查询（`[run_start - scrape_grace, run_end + drain_grace]`，
  两个 grace 外置）；统一返回数据质量状态 `OK/NO_DATA/COLLECTING/DEGRADED`。
- 测试：对已知窗口查已知序列；停掉一个 exporter 验证 `NO_DATA` 不显示成 0（A22）。
- 验收：UI 核心指标卡数据源切换到聚合层；Grafana/Tempo 带时间范围深链生成。

### B1-04 环境预检查（run 前门禁）

- 开发：预检查项——背景 `jl-traffic` 停止、无残留故障/toxic、无未确认操作、监控关键序列可用、
  资源预算（按 profile 评估内存）、镜像 digest 一致。阻断项/警告项分组返回。
- 测试：造残留故障 + 开着 traffic 启动 run，必须被阻断（A26）。
- 验收：预检查失败无法强制跳过阻断项。

### B1-05 控制台工作区重构（侧边导航，一次性）+ 台账页

- 开发：按已确认口径（#2）把 4 标签单页**一次性重构为侧边导航工作区**（总览/场景向导/实例与 JVM/Benchmark/
  实时监控/故障与缓存实验/实验记录）；线程、缓存、向导、动画组件原样挂接迁移，不重写业务逻辑；
  台账页（run 列表/详情/状态徽标/审计时间线，刷新自恢复）作为工作区首个新页面落地。
- 测试：`console-smoke` 断言基线随重构一次性重写并记录新基线（原 42 条映射到新导航）；后续批次逐批增补。
- 验收：旧四视图能力在新结构下全部可达；A30/A31 的 UI 半区。

B1 收口交接：`step-S2-B1-00-closure.md`。

---

## 3. B2 · 统一 k6 执行、流量归因与故障闭环

> **收口状态（2026-10-28）：已收口。** 4 个步骤全部合并并通过真机验证（A01/A17/A20/A09/A12/A29），
> 批次闸门与遗留清单见 `step-S2-B2-00-closure.md` 与 §0 现行基线。

**收口标准**：UI/CLI 场景清单一致；定向流量能到达指定实例；超时动作状态可追踪；清理覆盖所有故障资源。

### B2-01 场景白名单单一事实源

- 开发：`bench.sh` 与 `run.sh` 场景清单不一致问题修复；场景目录/schema 落为单一配置源
  （含参数默认值、env 白名单、阈值、是否写库、所需 profile）；
  **`stress`/`soak` 首期不进 UI 白名单**（已确认 #4，CLI 保留，移入方案 §15 Future）。
- 测试：k6 侧 `node --test` 场景清单一致性断言；脚本级 mock。
- 验收：UI 场景列表 = CLI 场景列表 = 目录内容。

### B2-02 k6 执行容器化（后端受控启动）

- 开发：chaos-controller 按白名单启动固定 digest 的 `grafana/k6` 容器：场景只读挂载、输出走受控命名卷 `/reports`、
  镜像/命令/挂载/时长/并发数全白名单，禁止任意脚本路径与 `--out`；支持停止、超时清理、日志分页/字节上限/下载；
  run 生命周期事件写台账。
- 测试：单测（参数校验、拒绝路径）；真机 UI 启动一次 `load` 全程无终端参与（A01）、停止与超时清理（A20）。
- 验收：孤儿 k6/traffic 容器有清理路径；`K6_SUMMARY_EXPORT` 落 `/reports`（避坑 18 不复现）。

### B2-03 流量模式 `via-nginx` / `target-instance`

- 开发：定向模式走受控实例地址（compose 服务名固定表，不由前端传 URL）；manifest 记录路由规则、
  upstream 权重、`traffic_mixed` 标记；k6 侧按实例出请求数/占比/P95。
- 测试：定向到 app-2 时其余实例请求数≈0；Nginx upstream 计数与应用侧计数交叉核对。
- 验收：A17、A06（改造方案 §10.6 口径）。

### B2-04 故障闭环统一

- 开发：容器动作/Toxiproxy/线程故障统一到 operationId 契约；UI 可选目标×动作×TTL（含"不过期"手动保持，
  明示"不会自动恢复"）；`/chaos/actions` 返回 `paused/remainingMs`；`stop-all` 覆盖线程生命周期机、toxic、
  暂停态故障、traffic、k6；故障事件写 run 时间线；提供**人工确认/关闭 UNKNOWN 操作**的端点（B1 审核 M1：
  重启恢复产生的 UNKNOWN 操作目前会永久阻断预检查 `operations.settled`）。
- 测试：单测 + 真机注入→暂停→撤销→stop-all 全链路；**bandwidth 首期不做、UI 隐藏**（已确认 #5，Future），
  latency/timeout 走既有验证路径。
- 验收：A09、A10、A12、A29。

B2 收口交接：`step-S2-B2-00-closure.md`。

---

## 4. B3 · 缓存实验、键查询与 A/B 对照

> **收口状态（2026-10-29）：已收口。** B3-00 交互对齐 + B3-01..04 全部合并并真机验证；独立 Qwen 审核
> **Approved with fixes**（1 Medium 已修，`4e925ae`）；闸门见 §0。收口文档 `step-S2-B3-00-closure.md`，
> 实现交接 `step-S2-B3-00a-interaction-alignment-implementation.md` / `step-S2-B3-01-cache-experiments.md`。

**收口标准**：A/B 变量受控；目标/实际键数、缓存指标与业务对账可追踪；正常关闭与 Redis 故障不混淆。

### B3-00 交互对齐（B3 前置，2026-10-29）

- 开发：按 `doc/design/Stage2/stage2-交互稿演示.html` v4 与方案 §16 落地 6 分区信息架构；向导前置基础配置 + 7 步；
  Java 节点多选门禁；k6 结构化中文表单（smoke/load/spike、VU/时长/阈值/tooltip）；商品 ID 采样范围与平均/正态分布；
  故障时间线（atSeconds、自动/永不恢复）；测试名称、归档/彻底删除（级联）；实时监控时间轴（Prometheus/Grafana 同源）
  与 k6 进度/速度；拓扑/实例表/实例卡显示“未启用”。
- 测试：console-smoke 增加表单/门禁/时间轴/清理断言；API 单测覆盖节点门禁、`PRODUCT_ID_*` 参数、名称/删除级联；
  无数据不显示 0。
- 验收：UI 与交互稿 v4 一致；UI/CLI 场景一致；删除级联无残留；console-smoke 新基线。
- 交接：`step-S2-B3-00-interaction-alignment.md`。

> **实现状态（2026-10-29）：已完成代码实现（B3-00a）。** 交付：V3 台账字段（name/nodes/archived）与归档/彻底删除；
> 节点选择门禁（预检查按启用节点校验、定向实例属于启用节点）；故障时间线（atSeconds 调度、manual kill/freeze 以
> `ttlSeconds=-1` 手动保持、stop-all 可清理）；`load` 商品采样 `PRODUCT_ID_*`（默认 1/1/uniform/0）；控制台 6 分区、
> 7 步向导、监控时间轴/k6 进度/实例表/拓扑“未启用”、实验记录名称/归档/删除/对比。B4 的应用并重启/回滚按钮保持禁用。
> 闸门见 §0；交接 `step-S2-B3-00a-interaction-alignment-implementation.md`；独立审核随 B3 收口执行。

### B3-01 独立 Caffeine L1 开关（含关闭语义）
> **实现状态（2026-10-29）：已完成。** `TwoLevelCache` 增加每实例 `AtomicBoolean` 开关、关闭即清空本地条目并绕过
> L1 读写（L2/Redis 不变），新增 `GET/POST /lab/cache/l1` 与 `cache_l1_enabled` gauge；CacheLab 页有开关与“不适用”提示。
> 真机：app-1 关闭后 app-2 仍启用，键详情 `l1Present=false / l2Present=true`。测试见批次交接。


- 开发：`off` = 绕过 L1 读写并清空本地条目；每实例独立；纳入六开关体系或独立配置 API（按现有风格）；
  状态回读。
- 测试：单测（关闭后条目清空、不再命中）；真机三实例分别开关互不影响。
- 验收：A04 前半；UI 显示"已关闭/不适用"。

### B3-02 数据播种目标规模 + SCAN 键查询 + 单键详情
> **实现状态（2026-10-29）：已完成。** Flyway V4 生成 10,000 条确定性商品（库存 `MOD(id*37,501)`，0–500；
> 商品 1=37、20=239、10000=262），`reset-lab.sh` 与 `POST /lab/cache/reset` 共用同一公式；`POST /lab/cache/seed`
> 支持 `{"count":N}`（上限外置 10000，多行 INSERT，约 7s）；`GET /lab/cache/keys` 为 SCAN 游标分页（前缀白名单、
> 有界批量补齐、limit 上限 1000）；`POST /lab/cache/key` 返回 L1/L2/TTL/截断值；`POST /lab/cache/reset` 支持只清缓存键
> 或同时恢复业务基线。verify 断言 10000 条基线与 Flyway 1–4 已同步。CacheKeysPanel 挂在“实验工具”标签。


- 开发：DB 内置基础商品数据（已确认 #3）：Flyway/初始化脚本按**固定 seed 确定性生成 10,000 条**，
  库存随 seed 随机、每次重置相同；数量与 seed 外置配置（默认 10000/固定种子）。已确认（2026-10-28）：
  **替换商品 1/20 演示基线**——引用商品 1/20 的场景与验收脚本（spike/smoke/verify）随本步骤一并改指内置商品；
  库存区间 **0–500**（随 seed 确定性生成）。`POST /lab/cache/seed` 支持目标键数；`GET /lab/cache/keys`
  （SCAN 分页、白名单前缀如 `cache:product:*`、禁 `KEYS`、limit 上限）；`POST /lab/cache/key` 单键
  L1/L2 状态 + TTL + 值截断预览（响应字节限制）；`POST /lab/cache/reset` 一键还原实验数据（Stage1 缺口收口）。
- 测试：单测（前缀过滤、分页游标、截断）；真机目标 10,000 播种后目标/实际/差值/核验时间齐全（A05、A06、A23、A24）。
- 验收：计数口径按方案 §5.2（业务前缀、估算值标注、每实例分开）。

### B3-03 A/B 公平对照流程
> **实现状态（2026-10-29）：已完成。** Benchmark 页新增 `CacheABPanel`：A=L1 开+逐实例预热，B=L1 关（显示清空条目数），
> 同一 load 参数/商品范围/实例集合/JVM 参数；自动创建、预检查、启动、收盘并用 B1 聚合层生成 9 项差值表；结束自动恢复 L1。
> 真机跑通一对 A/B（run-20261029-003/004，L1 清空 21/23/21 条）。


- 开发：对照模板固化（A：L1 开 + L2 开，B：L1 关 + L2 开；同数据集/热点比例/VU/时长/实例数/JVM 参数；
  A 预热、B 清空确认）；差值报告接 B1 聚合层。C/D（Redis 故障/两级不可用）与"正常关闭 L2"用不同实验标签；
  **执行场已确认（#6）**：C/D 默认 `dev + chaos-proxy`（Toxiproxy 模拟 Redis 不可用），chaos-proxy 与
  ha/db-ha 维持互斥，Redis/MySQL HA 切换走独立场景（容器动作），不叠加 Toxiproxy。
- 测试：真机跑 A/B 各一次，报告能出 RPS/P95/P99/GC/L1 命中率差值且变量清单只含 L1 开关。
- 验收：A04 完整口径。

### B3-04 业务对账器
> **实现状态（2026-10-29）：已完成。** 控制器新增只读业务库快照与 `POST /lab/runs/{id}/reconcile` /
> `GET /lab/runs/{id}/reconciliation`：7 项检查（订单增量/库存扣减/非负/重复订单/重复幂等键/超卖计数/MQ 排空），
> PASS 仅当全部 PASS，未接线/不可达为 DEGRADED；run 启动时抓基线（best effort），结果写 `lab_run_check`。
> 真机：0 流量 run PASS；smoke 新增 4 单后 ordersDelta=4、stockDecrement=4 仍 PASS。RunLedger 详情挂 RunReconciliation。


- 开发：按方案 §10.9 八步实现只读 SQL/API 对账（订单新增量、库存扣减、超卖、重复订单、幂等、MQ 排空限时判定）；
  性能阈值与业务对账分别判定，任一未接线 → `DEGRADED`；失败附证据（实际/预期/差值/样本/时间）。
- 测试：单测 + 真机 spike 后对账；故意留残留故障验证 `INVALID` 不参与比较。
- 验收：A11。

B3 收口交接：`step-S2-B3-00-closure.md`。

---

## 5. B4 · 实例 JVM/GC/线程池配置闭环

**收口标准**：配置应用后证明实际运行镜像与有效参数正确；失败自动回滚；重建后流量恢复并经过稳定期。

### B4-01 实例配置 schema 与白名单校验

- 开发：JDK 镜像、`-Xms/-Xmx`、GC 类型 + 各 GC 参数（按 JDK 版本动态 schema，方案 §4.3）、
  业务线程池 core/max/queue/keepAlive/拒绝策略（方案 §4.4）——全部白名单字段生成参数，禁止字符串拼接；
  资源预算校验（`-Xmx` + Metaspace + 线程栈 + 直接内存 vs `mem_limit`）。
- 字段按 v4（方案 §16.4–§16.7）：JVM 堆/Metaspace/Direct、G1/Parallel/通用 GC 参数
  （不含 `G1ReservePercent`/`G1ConcRefinementThreads`/ZGC）、业务池/Tomcat、Caffeine
  （`maximumSize`/过期/刷新/统计）、GC 日志（路径/滚动/OOM dump）、`extraJvmArgs` 白名单；每个字段有中文说明/tooltip。
- 测试：单测覆盖拒绝路径（不兼容参数、危险选项、预算超限）——A28。
- 验收：校验器不执行任意命令/挂载；错误信息可解释。
> **实现状态（2026-10-29）：已完成代码与真机验证。** app 侧：有界业务线程池（`businessExecutor`）+ `/lab/pool` 回读 +
> `executor_rejected_total`；Caffeine `enabled/expireAfterAccessSeconds` 启动参数。控制器：`instance/` schema、白名单/
> 预算/路径校验、ledger V4 `lab_instance_config` 草稿存储、`GET/PUT/validate/effective` 四端点；JDK8 返回
> `LIMITED-OBSERVABILITY`。真机：目标 `-Xmx384m` vs 容器 `-Xmx512m`、预算 708/768、注入与路径逃逸 400。
> 交接 `step-S2-B4-01-instance-config-schema.md`；B4-02 应用/重启/回滚仍待做。

### B4-02 受控重建 + 健康确认 + 回滚 + 稳定期

- 开发：固定模板/受控 Docker API 重建指定实例（等价 `docker compose up -d --no-deps --force-recreate app-N`，
  显式 `--force-recreate` 属已确认 #8 最小闭环）；记录并核对运行容器 tag/imageId（不做 digest 溯源）→
  `nginx -t` + reload/解析 → 应用健康端点 + Nginx 上游 + Prometheus `up` + 真实请求探针全过；
  失败回滚上一版配置/镜像并记录；重建后清空 L1/计数器/连接池 → 可配置稳定期，期内禁起正式对照。
- 测试：真机 A07、A18、A27；run 中修改启动参数被拒绝。
- UI 语义（v4）：应用配置必须在预检查前完成；run 中禁止修改启动参数；提供“应用并重启/仅保存/回滚”，
  状态 `待重启/应用中/已生效/已回滚`；失败自动回滚并进入稳定期。
- 验收：`待重启/应用中/已生效/应用失败` 状态全程可见；有效参数经 `jcmd`/启动信息回读。
> **实现状态（2026-10-29）：已完成代码与真机验证。** `POST /lab/instances/{id}/apply|rollback`：snapshot → park 旧容器 →
> 复制创建（仅替换 `JAVA_TOOL_OPTIONS`）→ Docker health → 应用探针 → `/lab/pool` 回读 → `nginx -s reload` + 入口探针 →
> Prometheus `up` → 镜像 tag/id；成功写 APPLIED/ROLLED_BACK + `stable_until`，失败自动恢复旧容器并写 FAILED（恢复失败
> DEGRADED）。真机 apply/rollback 均 9 项 PASS；G1 实验参数解锁、Metaspace 128m OOM、冷启动瞬时 unhealthy 三个真机坑
> 已修。交接 `step-S2-B4-02-controlled-rebuild.md`；UI 与 `stableUntil` 展示归 B4-04。

### B4-03 GC 日志与诊断采集

- 开发：GC 日志选项由版本适配器生成（JDK8 `-Xloggc` / 21 `-Xlog:gc*`），路径外置；OOM/堆转储事件进 run 时间线。
- 测试：两种 JDK 各起一次，日志文件生成且路径正确。
- 验收：报告能引用 GC 日志证据。

### B4-04 实例配置页 UI + JDK 8/21 对照（jdk-matrix）

- 开发：改造方案 §9.2 线框落地（逐实例列、变更差异预览、校验全部/应用按钮）；JDK 对照实验模板
  （记录外壳/依赖差异，明示非纯 JVM 因果对照，方案 §10.6 第 4 条）；**JDK 8 外壳纳入每实例配置并标
  `LIMITED-OBSERVABILITY`**（已确认 #7，方案 §4.5）：支持镜像选择、JVM 启动参数、重启生效、容器/进程/GC 日志回读；
  热配置回读与完整应用内指标显式 `READBACK_UNAVAILABLE`，指标用 GC 日志/JMX/外部采集兜底且报告标注来源。
- 实例页按 v4：展示 JVM/GC/Caffeine/GC 日志摘要、应用并重启/回滚；未启用节点置灰并显示“未启用”；
  完整参数编辑器在向导第 3 步（方案 §16.3–§16.7）。
- 测试：console-smoke 增加实例页断言（含 jdk8 列的 LIMITED-OBSERVABILITY 状态呈现）；真机 A08。
- 验收：jdk-matrix 实验必过项 = 8 号壳镜像选择 + 参数配置 + 重启生效 + GC 日志回读；
  三实例源码版本一致且报告可见 commit + tag/imageId。

B4 收口交接：`step-S2-B4-00-closure.md`。

---

## 6. B5 · 拓扑、分流、重复实验与全量验收

### B5-01 实时拓扑负载热度（`/observe`）

- 开发：节点/连线由当前拓扑数据生成（非静态图）；热度维度可切（综合/CPU/内存/线程池/网络 I/O），
  阈值可配置、图例必须存在；tooltip 与点击详情含实例名、健康、JDK、CPU、内存 used/limit、线程、业务池
  active/max/queue/rejected、请求数、平均响应、P95、更新时间；网络不可采集处显示"无数据/未接线"。
- 时间轴（Prometheus/Grafana 同源）与 k6 进度/速度（VU、iterations/s、http_reqs/s）；拓扑全量节点
  （Nginx/app/Redis/Sentinel/MySQL/Router/RabbitMQ/Toxiproxy），未启用置灰并显示“未启用”；exporter/sidecar 默认隐藏。
- 测试：console-smoke 注入一次 freeze 断言节点变红与 tooltip 真实值（A16）。
- 验收：压测中热度变化与 Grafana 同源一致。

### B5-02 实例分流统计面板

- 开发：Benchmark 结果区"负载均衡分流"表（命中数/占比/成功失败/平均响应/P95/P99/RPS/错误率/线程池拒绝/配置组），
  Nginx upstream 与应用侧计数交叉核对，`/actuator/**` 不计入业务命中，口径标注（方案 §9.6）。
- 实例分流表与时间轴口径一致；无数据不显示 0；定向模式只命中目标实例，其余≈0。
- 测试：真机 load run 核对占比与权重一致性；权重不一致时提示分流不均衡。
- 验收：A17 完整口径。

### B5-03 重复 N 次实验与统计呈现

- 开发：同一方案 N 次 run 编排；报告展示每次结果、中位数、离散程度；单次 run 标"演示性结果，未重复验证"。
- 测试：load 场景 ×3 出中位数。
- 验收：A32。

### B5-04 缺口补采与降级标注

- 开发：Router 自身指标、Tomcat 线程池细节、cAdvisor 网络等按字典逐项补采或明确降级标注。
- 验收：字典无"未定论"条目。

### B5-05 A01–A32 全量验收

- 按第 8 节矩阵逐条执行并留证据；任何 FAIL 记录 → 修复 → 复测 → 登记。
- 收口文档：`doc/process/STAGE-02阶段交接-<日期>.md`（五段式收口）。

---

## 7. 步骤验收总则

每个步骤（`step-S2-*`）的"完成"定义，缺一不得视为收口：

1. 代码/脚本/配置提交到 `feature/stage2-workbench`；
2. 对应测试新增并通过（含"验证真实行为"检查，不许弱化 verify 断言）；
3. 涉及运行配置的新参数全部外置且有默认值 + `.env.example` 条目；
4. 全栈类改动批次收口时 verify/smoke/console-smoke/k6 回归基线更新并在交接文档记录数字；
5. 涉及文档口径变化的，同步 `claims.yml` 并 `check-doc-claims.sh` 通过；
6. 交接文档五段式齐全，含最新 commit、测试结果、已知缺陷、下一步；
7. 批次收口做一次独立 Qwen 审核（隔离 worktree），报告齐全后合入并统一 verify。

---

## 8. 验收矩阵映射（改造方案 §12）

| 用例 | 归属步骤 | 用例 | 归属步骤 |
|---|---|---|---|
| A01 | B2-02 | A17 | B2-03 / B5-02 |
| A02 | B1-03（两次 run 对比报告，B3 后完整） | A18 | B4-02 |
| A03 | B1-03 | A19 | B1-02 |
| A04 | B3-01/03 | A20 | B2-02 |
| A05 | B3-02 | A21 | B1-01 |
| A06 | B3-02 | A22 | B0-01 / B1-03 |
| A07 | B4-02 | A23 | B0-05 / B3-02 |
| A08 | B4-04 | A24 | B3-02 |
| A09 | B2-04 | A25 | B0-06 |
| A10 | B0-08 / B2-04 | A26 | B1-04 |
| A11 | B3-04 | A27 | B4-02 |
| A12 | B2-04 | A28 | B4-01 |
| A13 | B1/B2/B5 向导重构步（随 B5 收口，向导模板见方案 §9 场景向导） | A29 | B1-02 / B2-04 |
| A14 | B4-01/02 | A30 | B1-05 / B2-02 |
| A15 | B4-01 | A31 | B1-02 |
| A16 | B5-01 | A32 | B5-03 |

---

## 9. 确认结论（2026-10-27 用户确认，已回写需求 §十一、改造方案 v1.3）

| # | 事项 | 结论 | 落点 |
|---|---|---|---|
| 1 | 台账持久化 | 复用栈内 MySQL（standalone / Router 6446），独立台账库、与业务表物理分库，写入可重试/可暂存 | B1-01；方案 §10.5 |
| 2 | 控制台信息架构 | 一次性重构为侧边导航工作区，现有组件挂接迁移不重写 | B1-05；方案 §9 引言 |
| 3 | 内置基础商品 | 固定 seed 确定性生成 10,000 条，数量与 seed 外置配置（默认 10000/固定种子） | B3-02；方案 §2.2 |
| 4 | `stress`/`soak` UI | 首期不开放，移入 Future；CLI 保留 | B2-01；方案 §7.1/§15 |
| 5 | bandwidth 毒性 | 隐藏不做，移入 Future | B2-04；方案 §8/§15 |
| 6 | chaos-proxy 互斥 | 维持互斥；C/D 组默认 `dev + chaos-proxy`（Toxiproxy 模拟 Redis 故障）；Redis/MySQL HA 切换走独立场景（容器动作），不叠加 Toxiproxy | B3-03；方案 §5.3 |
| 7 | JDK 8 外壳 | 纳入每实例配置，标 `LIMITED-OBSERVABILITY`：镜像选择/JVM 参数/重启生效/GC 日志回读为必过项；热配置回读显式 `READBACK_UNAVAILABLE`，指标 GC 日志/JMX/外部兜底并标注来源；原因写入方案 §4.5 | B4-04；方案 §4.5 |
| 8 | 镜像身份 | 最小闭环：tag+imageId 写 manifest/报告、重建显式 `--force-recreate`；不校验来源、不归档构建日志、不锁 base digest；Stage1 遗留 P0#3 以此口径收口 | B0-08/B4-02；方案 §10.4/§15 |
| 9 | 商品基线替换 | 内置 10,000 商品**替换商品 1/20 演示基线**；引用 1/20 的场景与验收脚本随 B3-02 改指内置商品（2026-10-28 确认） | B3-02；方案 §2.2 |
| 10 | 库存区间 | **0–500**，随 seed 确定性生成、每次重置相同（2026-10-28 确认） | B3-02；方案 §2.2 |
| 11 | 台账库与表清单 | 库名 `lab_ledger`（与业务库 `seckill` 物理分库），10 张表 + Flyway 版本化 + 保留最近 50 个 run（外置配置），清单见方案 §10.5（2026-10-28 定稿） | B1-01；方案 §10.5 |
| 12 | JVM/GC 参数范围（2026-10-29） | 不增加 `G1ReservePercent`、`G1ConcRefinementThreads`、ZGC；其余按方案 §16.4 | B4-01 |
| 13 | 配置应用/重启语义（2026-10-29） | 预检查前完成；run 中禁止改启动参数；失败自动回滚并进入稳定期；UI 显示待重启/应用中/已生效/已回滚 | B4-02/B4-04 |
| 14 | Caffeine 语义（2026-10-29） | 过期/刷新按秒，`0` 不启用；关闭 L1 清空并显示“不适用”，页面有提示 | B3-01/B3-00 |
| 15 | 未启用节点（2026-10-29） | 配置行置灰 + 下拉框排除；拓扑/实例表/实例卡显示“未启用” | B3-00/B4-04 |

三项遗留待确认已全部在 2026-10-28 确认，无遗留项。

---

## 10. 时序与执行约束（本机实况相关）

- **构建中不改被构建文件**（假红教训）；并发构建下的时序型单测会抖，重跑前先判"测试错还是实现错"。
- PATH JDK 21 / Maven JDK 25 / 本机只有 JRE 8：JDK 8 相关验证一律走容器，不在宿主跑。
- 真机步骤（verify、db-failover、ha 演练）需要栈在跑：ha+db-ha 同开约 34 服务/32 常驻容器、9–10GB 内存；
  chaos-proxy 演示单独排场。破坏性动作（删卷、`down --purge`）执行前必须单独请示。
- 独立审核会话不碰共享 Docker 栈，只跑单元/Testcontainers。
- 混合行尾文档（阶段交接、README、claims.yml）修改一律字节级脚本处理，防 glob 断言假 PASS。

## 11. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-10-27 | v1.0 初版：B0–B5 步骤分解、闸门基线、A01–A32 映射、8 项待确认 |
| 2026-10-27 | v1.1：8 项确认结论落位（B0-08 最小闭环、B1-01 MySQL 台账、B1-05 侧边导航重构、B2-01/04 stress/soak 与 bandwidth 入 Future、B3-02/03 内置商品与 C/D 执行场、B4-02/04 tag-id 核对与 JDK8 LIMITED-OBSERVABILITY）；§9 改为确认结论 + 3 项遗留待确认 |
| 2026-10-28 | v1.2：3 项遗留全部确认——内置 10,000 商品替换商品 1/20 演示基线（B3-02）、库存区间 0–500、台账库 `lab_ledger` 与 10 表清单定稿（B1-01/方案 §10.5）；§9 改为 11 项确认结论、无遗留 |
| 2026-10-29 | v1.4：新增 **B3-00 交互对齐**；按交互稿 v4 补充节点门禁、k6 结构化表单、商品 ID 分布、故障时间线、时间轴/k6 进度、测试名称/清理；B4 补 JVM/GC、业务池/Tomcat、Caffeine、GC 日志与重启字段；§9 追加 4 项确认 |
| 2026-10-29 | v1.8：B4-02 实现——受控重建/健康门禁/自动回滚/稳定期/ROLLED_BACK；Nginx 超时放宽；§0 记录 B4-02 基线 |
| 2026-10-29 | v1.7：B4-01 实现——app 业务池/Caffeine 启动参数、控制器实例配置 schema/预算/草稿存储/回读；§0 记录 B4-01 基线 |
| 2026-10-29 | v1.6：B3-01..04 实现——独立 L1 开关、10,000 商品基线 + SCAN 键浏览/单键/重置、A/B 对照面板、业务对账；§0 记录 B3 批次基线 |
| 2026-10-29 | v1.5：B3-00 交互对齐实现完成——V3 台账字段与归档/删除、节点门禁、故障时间线（manual=-1 手动保持）、load 商品采样、6 分区控制台/7 步向导/监控时间轴/实验记录清理；§0 记录 B3-00 基线，B4 应用重启仍禁用 |
| 2026-10-28 | v1.3：B0 收口——§0 闸门表加「B0 收口基线（现行）」列（reactor 851 / app 469 / chaos 296；console-smoke 42 未重跑并标注原因）；§1 顶部登记收口状态与真机台账指针；顺延项（cursor E2E、db-ha 真 `--yes`、failure-container-check 细节）在 `b0-live-verification.md` 登记 |
