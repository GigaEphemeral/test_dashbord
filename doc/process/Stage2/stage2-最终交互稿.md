# Stage2 最终交互稿（需求 / 设计 / 计划对齐版）

> 依据：`doc/design/Stage2/stage2-需求.md`（需求基线 v1 + §十一 11 项确认）、
> `doc/design/Stage2/stage2改造方案.md`（v1.4）、`doc/process/Stage2/stage2-开发测试与验收计划.md`（v1.3）、
> 当前实现（B0–B2，提交 `5836ec6`）。B3–B5 为规划项，本文按设计目标写出，不冒充已实现。
>
> 状态标记：✅ 已实现　◐ 部分实现　○ 规划中（B3–B5）　⚠ 与设计有差异/待确认

---

## 0. 结论摘要：最需要你确认的偏差

| # | 设计/计划要求 | 当前状态 | 结论 |
|---|---|---|---|
| 1 | §7.1 UI 可配置参数（VU、阶段、时长、预热、请求比例、数据规模、阈值、故障剧本、复位策略、对账） | 实验记录页只能填「场景 + 原始 JSON」；Benchmark 页只展示场景目录与背景流量 | ⚠ 交互缺口：建议补**中文结构化参数表单** |
| 2 | §9.8 所有影响运行环境的按钮显示目标、当前值、影响、是否重启 | 容器故障/线程/Toxiproxy 有目标与 TTL；JVM 配置页尚未实现 | ◐ B4 补齐 |
| 3 | §9 场景向导 7 步流程（类型→目标→变量→故障→预检查→执行→报告） | 当前是「选择剧本→逐步执行」的简单向导 | ⚠ 7 步向导属 B5/A13，当前未实现 |
| 4 | §9.2 每实例 JDK/镜像/堆/GC/业务池/Caffeine + 校验/应用/状态 | 实例页当前是只读卡片 + 线程实验室 | ○ B4 |
| 5 | §9.3 缓存实验（L1/L2 开关、目标/实际键数、预热、键查询、A/B） | 有缓存实验室（模式/播种/预热/状态），无目标-实际核验、无键分页、无 A/B 流程 | ◐ B3 补齐 |
| 6 | §9.4/§9.6 Benchmark 分流统计、§9.5 拓扑热度、§7.4 两次 run 对比 | 有 KPI/拓扑基础视图；分流表、热度切换、对比报告未做 | ○ B5 |
| 7 | §5.2/§5.3 10,000 内置商品替换 1/20、库存 0–500、A/B/C/D 对照 | 仍以商品 1/20 为演示基线；缓存 A/B 未固化 | ○ B3 |
| 8 | §7 故障统一 operationId、TTL/手动保持、单项撤销、全局停止 | 容器/Toxiproxy/线程后端已统一；线程 UI 仍走旧代理；stop-all 后 RUNNING run 需再 stop | ◐ 见 §7.4 |
| 9 | §6.2 结束检查：排空、业务对账、数据复位 | k6 结束后有 COMPLETED/FAILED 与报告；对账/复位未接 UI | ○ B3/B5 |
| 10 | §9.8 状态显示「已请求 / 已生效 / 已恢复」分离 | run/operation/k6/动作状态已分离；界面状态码仍为英文枚举 | ✅ 语义已实现，⚠ 文案待确认 |

---

## 1. 信息架构与角色

| 分区 | 设计目标 | 当前实现 | 状态 |
|---|---|---|---|
| 总览 | 实验状态、Run ID、当前场景、启动/停止、故障事件、整体健康 | 有健康/常驻容器/故障/最近 run 摘要 | ✅ |
| 场景向导 | 7 步向导：类型→目标→变量→故障→预检查→执行→报告 | 简单剧本执行（选剧本、逐步/跳过/暂停/恢复/重置） | ◐ |
| 实例与 JVM | 逐实例 JDK/镜像/堆/GC/业务池/Caffeine + 校验/应用/状态 | 只读实例卡片 + 线程实验室 | ○ B4 |
| Benchmark | k6 场景/VU/时长/阈值/配置组/数据规模/故障剧本/复位/对账/分流/对比 | 场景目录、背景流量、stop-all；正式 run 在「实验记录」创建/启动 | ◐ |
| 实时监控 | 真实拓扑热度、KPI、实例表、时间线、Grafana/Tempo | KPI 质量卡 + 拓扑基础视图 | ◐ |
| 故障与缓存实验 | 线程/网络/容器故障、TTL/手动保持、缓存实验、键查询、A/B | 容器故障 + 线程实验室 + 缓存模式/播种/预热 + 动作 TTL/暂停 | ◐ |
| 实验记录（设计的「实验报告与对比」） | run 列表/详情/时间线/报告/两次对比/重复 N 次 | 台账、预检查、start/stop、操作、故障事件、指标、k6 卡 | ◐ B5 补对比/重复 |

> 设计原文第 7 分区叫「实验报告与对比」，B1-05 落地为「实验记录」。这是命名差异，不是能力删除；对比与重复实验在 B5。

---

## 2. 全局交互

| 功能项 | 说明 | 参数（中文名/键） | 状态 |
|---|---|---|---|
| 鉴权 | 所有 `/lab/**`、`/chaos/**` 请求带实验令牌；令牌只存浏览器本地，不写日志 | 实验令牌 `X-Lab-Token`：来源 `.env LAB_TOKEN`，默认 `lab-token` | ✅ |
| 自动刷新 | 容器/故障/台账/k6 状态按周期刷新 | 自动同步开关；刷新周期 `VITE_LAB_SYNC_INTERVAL_MS`，默认 5000ms | ✅ |
| 健康度 | 顶部显示控制器健康、常驻容器 N/M | 常驻容器排除 traffic/init/jdk8/`jl-k6-*` | ✅ |
| 全局停止 | 一键还原环境 | `POST /chaos/stop-all`；结果含容器/网络/traffic/线程/k6 子项 | ✅ |
| 全局重置 | 清理故障与数据（设计 §9.8） | 未在 UI 提供完整「重置范围预览」；`reset-lab.sh` 仍是 CLI | ◐ |
| 错误呈现 | 操作不无限等待；错误带失败原因 | 4xx/5xx 中文提示；局部错误只在卡内显示 | ✅ |
| 无数据语义 | 无数据不得显示 0 | `NO_DATA/COLLECTING/DEGRADED` 徽标 | ✅ |

---

## 3. 功能项逐一说明

### 3.1 总览

**功能说明**：给出「现在环境是否干净、哪个 run 在跑、最近发生了什么」的一屏摘要，是演示的起点和收尾检查页。

| 控件 | 参数（中文名/键） | 功能解释 | 状态 |
|---|---|---|---|
| 健康徽标 | 环境健康：控制器 `/health` | 只表示控制器存活，不表示业务实例健康 | ✅ |
| 常驻容器 | 常驻容器 N/M | 排除按需/一次性/k6 容器后的运行数 | ✅ |
| 故障摘要 | 活动动作数/最近动作 | 快速判断是否还有 TTL 或手动故障 | ✅ |
| 最近实验 | 最近 5 个 run（`VITE_LAB_LEDGER_SUMMARY_LIMIT`） | 跳到台账详情 | ✅ |
| 当前实验卡（设计线框） | Run ID、场景、状态、运行时长、暂停/停止/查看报告 | 设计要求的顶部运行控制；当前在「实验记录」页操作 | ◐ |

### 3.2 场景向导

**当前实现**：选择后端白名单剧本（`code/lab/scenarios/*.json`）后，逐步执行故障事件；支持暂停（冻结 TTL）、恢复、跳过、重置。每一步展示标题、说明、症状/根因/修复/预期。

**设计目标（§9 向导）**：7 步向导 + 草稿 + 变量差异清单 + 预检查门禁 + 执行观测 + 结束检查。当前只实现了「执行剧本」这一段。

| 步骤（设计） | 交互内容 | 参数（中文名/键） | 状态 |
|---|---|---|---|
| 1 选择实验类型 | 基准压测/缓存对照/JVM-GC 对照/线程池对照/高可用故障 | 场景类型、后端场景 ID | ○ 仅剧本向导 |
| 2 选择目标与流量路径 | 入口（Nginx/API）、被测实例、upstream 权重、依赖 | 流量模式 `TRAFFIC_MODE`、目标实例 `TARGET_INSTANCE`、实例多选 | ◐ B2 已在 run 参数支持 |
| 3 配置实验变量 | 基线组/实验组、只改变量、VU/阶段/时长/预热/比例/数据规模/阈值、实例配置 | 见 §4.1/§4.4/§4.5 | ○ 结构化表单未做 |
| 4 配置故障（可选） | 故障类型、目标、触发时刻、持续/手动保持、恢复 | `action/target/ttlSeconds/runId` | ◐ 容器/线程/网络后端已具备 |
| 5 预检查 | 容器、镜像/JDK、配置生效、资源、数据、冲突 run、残留、监控 | 7 项预检查；阻断不可跳过 | ✅ 后端完整；向导内嵌未做 |
| 6 执行与观测 | run ID、阶段、k6 状态、分流、KPI、故障时间线、停止 | run 状态/操作/k6 卡 | ✅ 在「实验记录」页 |
| 7 结束检查与报告 | 停流量、排空、对账、故障恢复、汇总、保存/比较 | 对账/复位 | ○ B3/B5 |

向导参数：`scenarioId` 剧本 ID（必填）；`atSeconds` 步骤相对秒数；`action` 动作；`target` 目标；`ttlSeconds` 自动恢复秒数；`title/text` 标题与讲解；`explain.symptom/rootCause/fix/expectation` 症状/根因/修复/预期。

### 3.3 实例与 JVM（B4 目标）

**功能说明**：三个实例同源码、不同运行时配置；UI 负责「配置实验 → 校验 → 应用/重建 → 健康确认 → 回滚」，并明确显示配置是已保存、待重启还是已生效。

| 配置类别 | UI 字段（中文名/键） | 生效方式 | 状态 |
|---|---|---|---|
| Java 运行时 | JDK 版本 `jdkVersion`（8/21）、镜像 `imageTag` | 重建/替换容器 | ○ |
| JVM 堆 | 初始堆 `-Xms`、最大堆 `-Xmx`（如 256/512 MB） | 重启 JVM | ○ |
| 垃圾回收 | GC 类型 `gcCollector`（G1/Parallel/兼容项）、G1 最大停顿 `maxGcPauseMillis` | 重启 JVM | ○ |
| GC 诊断 | GC 日志开关/路径、堆转储、OOM 诊断 | 多数需重启 | ○ |
| 业务线程池 | 核心线程 `corePoolSize`、最大线程 `maxPoolSize`、队列 `queueCapacity`、空闲存活 `keepAliveSeconds`、拒绝策略 `rejectionPolicy` | 动态或重启（按字段） | ○ |
| 缓存 | Caffeine L1 开关 `caffeineEnabled`、L1 上限 `l1MaxEntries`、L2 TTL `l2TtlSeconds` | 动态/重启 | ◐ B3 |
| 应用韧性 | 熔断/限流/降级开关 | 动态 | ✅ 六开关部分 |
| 数据连接 | Hikari 上限、数据库模式、Redis 模式 | 按实现 | ◐ |
| 配置状态 | `已保存/待重启/应用中/已生效/失败/已回滚` | 只读 | ○ |
| JDK8 标记 | `LIMITED-OBSERVABILITY`、`READBACK_UNAVAILABLE` | 只读 | ○ B4-04 |

**应用流程（设计 §10.7）**：变更差异预览 → 白名单/类型/JDK 兼容/内存预算校验 → 受控重建（显式 `--force-recreate`）→ `nginx -t` + reload → 应用健康 + Nginx 上游 + Prometheus `up` + 真实请求探针 → 失败回滚 → 稳定期内禁止起正式对照。

### 3.4 Benchmark

**已实现**：场景目录（UI 白名单）、背景流量、stop-all；正式 run 在「实验记录」创建/启动；k6 卡展示状态/日志/报告；流量模式与实例归因。

**设计目标（§7.1/§9.4/§9.6）**：完整参数配置、配置组、数据规模、故障剧本、复位/对账、分流统计、两次对比。

| 功能项 | 说明 | 参数（中文名/键） | 状态 |
|---|---|---|---|
| 场景选择 | 只开放已验证场景 | 场景 `scenario`：smoke/load/spike | ✅ |
| 流量模式 | 经 Nginx 或定向实例 | 流量模式 `TRAFFIC_MODE`；目标实例 `TARGET_INSTANCE` | ✅ |
| 并发/阶段 | VU 与阶段曲线 | `LOAD_VUS/LOAD_START_VUS/LOAD_DURATION/LOAD_RAMP_*`；`SPIKE_*`；`SMOKE_*` | ✅ 后端支持，UI 需 JSON |
| 预热 | 正式计时前的预热（设计） | 预热时间/预热 VU | ○ 场景暂未单独暴露 |
| 请求比例/数据规模/热点 | 业务参数与数据规模 | 场景参数/缓存实验参数 | ◐ |
| 阈值 | 失败率、P95/P99、检查通过率 | `P95_MS/P99_MS/ERROR_RATE/CHECKS_RATE` | ✅ |
| 故障剧本 | 时间线注入 | 剧本文件/故障目标/TTL | ◐ CLI/后端已具备 |
| 复位/对账 | 压测前复位、结束后对账 | 复位策略、对账开关 | ○ B3/B5 |
| 启动/停止 | 受控 k6 容器 | `POST /lab/runs/{id}/start|stop` + 幂等键 | ✅ |
| 日志/报告 | tail、summary 下载 | `tail/maxBytes`、`summary.json` | ✅ |
| 分流统计 | 每实例请求数/占比/P95/错误率/拒绝 | 实例级指标 | ○ B5-02 |
| 两次对比 | RPS/P95/错误率/缓存/GC/业务对账差值 | run A/run B | ○ B5-03 |
| 重复 N 次 | 中位数/离散程度/单次证据等级 | N 次、统计量 | ○ B5-03 |

### 3.5 实时监控

**功能说明**：不离开控制台即可看实验期间最常用的 KPI；深入分析跳 Grafana/Tempo。所有指标必须带质量状态，无数据不显示 0。

| 功能项 | 说明 | 参数（中文名/键） | 状态 |
|---|---|---|---|
| KPI 卡 | 13 个核心指标，含值/质量/更新时间 | 指标键 `keys`、时间窗 `from/to`、阶段 `phase` | ✅ |
| 质量徽标 | OK/NO_DATA/COLLECTING/DEGRADED | 无 | ✅ |
| 拓扑 | 节点/连线由当前拓扑生成 | 无 | ◐ 基础视图 |
| 热度维度 | 综合/CPU/内存/线程池/网络 I/O | 维度 `dimension`、阈值 | ○ B5-01 |
| 节点详情 | 名称/健康/JDK/CPU/内存/线程/业务池/请求/P95/更新时间 | tooltip/点击详情 | ○ B5-01 |
| 连线详情 | 方向/每秒请求/响应时间/错误率 | 同上 | ○ B5-01 |
| 实例表 | 每实例 Busy/Active/Queue/Hikari/状态 | 实例、指标组、时间窗 | ○ B5-01 |
| 时间线 | 压测/故障/恢复事件 | run、时间窗 | ◐ 台账内有事件 |
| 深链 | Grafana 当前时间范围、Tempo | `grafanaUrl/tempoUrl` | ✅ |

### 3.6 故障与缓存实验

**功能说明**：手动制造、暂停、恢复、撤销故障；缓存实验控制 L1/L2、目标/实际键数与 A/B 对照。所有故障显示目标、状态、开始时间、剩余 TTL 或手动保持，并提供单项撤销与全局停止。

| 功能项 | 说明 | 参数（中文名/键） | 状态 |
|---|---|---|---|
| 容器故障 | kill/start/restart/freeze/resume | 目标 `name`、动作 `action`、TTL `ttlSeconds`、关联 run `runId` | ✅ |
| 线程故障 | 7 种模式；auto/manual | 实例 `instance`、模式 `mode`、保持 `hold`、TTL、虚拟线程 `virtual` | ✅ 后端；UI 走旧代理 |
| 网络故障 | 延迟/超时（带宽隐藏） | 代理 `proxy`、类型 `type`、参数 `latencyMs/jitterMs/timeoutMs` | ✅ 后端；需 chaos-proxy |
| 动作列表 | 目标/动作/状态/到期/剩余/冻结/操作号/错误 | `GET /chaos/actions` | ✅ |
| 单项撤销 | 撤销一个动作 | 动作 id/stop 路径 | ✅ |
| 全局停止 | 四层恢复 | `POST /chaos/stop-all` | ✅ |
| 手动保持 | 明示不会自动恢复 | `hold=manual` / 无 TTL | ✅ |
| 故障事件 | 写入 run 时间线 | 事件 `source/target/event/operationId` | ✅ |
| 缓存 L1 开关 | 独立开关，关闭=绕过并清空本地 | `caffeineEnabled` | ○ B3-01 |
| 缓存 L2 | 开关/TTL | `l2TtlSeconds` | ◐ |
| 目标/实际键数 | 目标规模 vs 实际观测，分开显示 | 目标 Redis 键数、L1 目标/实际、差值、核验时间 | ○ B3-02 |
| 播种 | 固定 seed 10,000 商品，库存 0–500 | 数量 `count`、seed | ○ B3-02 |
| 预热/清空 | 预热与清空确认 | 预热开关、清空确认 | ◐ |
| 键查询 | SCAN 分页、前缀白名单 | 前缀 `prefix`、游标 `cursor`、上限 `limit` | ○ B3-02 |
| 单键详情 | L1/L2/TTL/截断值 | 键 `key` | ○ B3-02 |
| 一键复位 | 还原实验数据 | 无 | ○ B3-02 |
| A/B 对照 | A=L1+L2，B=L2；同数据集/VU/时长 | 组别、变量清单 | ○ B3-03 |
| C/D 故障组 | Redis 不可用/两级不可用；dev+chaos-proxy | 组别、故障剧本 | ○ B3-03 |

### 3.7 实验记录 / 报告与对比

**功能说明**：每次实验的配置、状态、预检查、操作、故障事件、指标与 k6 结果都落在台账；刷新/重启后可恢复；未来支持两次对比与重复 N 次。

| 功能项 | 说明 | 参数（中文名/键） | 状态 |
|---|---|---|---|
| 列表 | 按场景/状态筛选、分页 | `scenario/status/limit/offset` | ✅ |
| 新建 | 生成 DRAFT | `scenario`、`params`（场景参数） | ✅（JSON 表单） |
| 预检查 | 7 项门禁 | 无 | ✅ |
| 启动/停止 | operationId、幂等、202 轮询 | `Idempotency-Key` | ✅ |
| 时间线 | 状态迁移步骤 | 无 | ✅ |
| 故障事件 | 容器/Toxiproxy/线程 | `faults` | ✅ |
| 指标 | run 窗口 + 质量 | `from/to/keys/phase` | ✅ |
| k6 卡 | 状态/容器/场景/流量/退出码/报告/日志 | 无 | ✅ |
| 操作列表 | 状态、摘要、错误 | `GET /lab/operations` | ✅ |
| UNKNOWN 关闭 | 人工核验后关闭操作 | `status=RESTORED|FAILED`、`note` | ✅ |
| 报告 | 汇总/阈值/证据 | `summary.json` | ✅ |
| 两次对比 | 配置/吞吐/延迟/JVM/池/缓存/DB/故障/业务 | run A/B | ○ B5-03 |
| 重复 N 次 | 中位数/离散程度/证据等级 | N、统计量 | ○ B5-03 |
| 归档 | 报告文件位置、深链 | 无 | ✅ 基础 |

### 3.8 数据与业务正确性（B3）

| 功能项 | 说明 | 参数（中文名/键） | 状态 |
|---|---|---|---|
| 内置商品 | 固定 seed 生成 10,000 条，替换商品 1/20 基线 | 数量 `10000`、seed | ○ |
| 库存 | 0–500，随 seed 确定性生成 | 区间 | ○ |
| 复位 | 库存/订单/缓存还原 | `POST /lab/cache/reset`、CLI | ◐ |
| 对账 | 订单新增、库存扣减、超卖、重复订单、幂等、MQ 排空 | 时间窗、阈值 | ○ B3-04 |
| 证据 | 实际/预期/差值/样本/时间 | 无 | ○ |

### 3.9 安全与审计

| 功能项 | 说明 | 状态 |
|---|---|---|
| 容器白名单 | 只操作 `lab=java-learn`；故障动作再校验 canonical name/探针保护 | ✅ |
| k6 白名单 | 固定镜像/命令/挂载/网络/参数上限；禁止任意脚本/URL/`--out` | ✅ |
| 流量目标 | 固定 app 服务名表，请求不能传 URL/host/port | ✅ |
| 操作审计 | operationId、幂等、before/after、操作者、错误脱敏 | ✅ |
| 令牌 | 只在 header/env，不回显/不写日志 | ✅ |
| 镜像身份 | tag+imageId 写入 manifest/报告（最小闭环） | ✅ |
| 行尾/脚本 | 混合行尾需字节级编辑；shell 存量 CRLF 待单独批次 | ⚠ 见审核 M5 |

### 3.10 状态与无数据语义

| 枚举 | 中文解释 |
|---|---|
| Run：`DRAFT/VALIDATING/READY/RUNNING/STOPPING/VERIFYING/COMPLETED/FAILED/INVALID/ABORTED/UNKNOWN` | 草稿/校验/就绪/运行/停止中/核验中/完成/失败/无效/中止/未知 |
| Operation：`REQUESTED/APPLIED/RESTORED/PENDING_CONFIRMATION/FAILED/UNKNOWN` | 已请求/已生效/已恢复/待确认/失败/未知 |
| k6：`ABSENT/CREATED/RUNNING/PAUSED/RESTARTING/EXITED` | 未启动或已清理/已创建/运行/暂停/重启中/已退出 |
| 动作：`APPLYING/ACTIVE/REVERTING/REVERTED/FAILED/COMPLETED` | 注入中/生效/撤销中/已撤销/失败/一次性完成 |
| 预检查：`PASS/WARN/BLOCKED` | 通过/警告/阻断（不可跳过） |
| 指标：`OK/NO_DATA/COLLECTING/DEGRADED` | 有数据/无序列/未过采集线/查询失败 |
| 向导：`IDLE/RUNNING/PAUSED/COMPLETED` | 空闲/运行/暂停（TTL 冻结）/完成 |

---

## 4. 参数总表

### 4.1 UI 可启动场景参数（已实现）

| 场景 | 键 | 中文名 | 功能解释 | 默认 | 范围 |
|---|---|---|---|---|---|
| smoke | `SMOKE_VUS` | 冒烟并发 VU | 同时发请求的虚拟用户数 | 1 | 1–1000 |
| smoke | `SMOKE_ITERATIONS` | 迭代次数 | 每 VU 执行完整流程次数 | 1 | ≥1 |
| smoke | `SMOKE_DURATION` | 最长时长 | k6 最大运行时长 | 30s | ≤3600s |
| load | `LOAD_START_VUS` | 起始 VU | 爬升起点并发 | 50 | 1–1000 |
| load | `LOAD_VUS` | 峰值 VU | 保持阶段并发 | 200 | ≥起始，≤1000 |
| load | `LOAD_DURATION` | 总时长 | 负载阶段总时长 | 10m | ≤3600s |
| load | `LOAD_RAMP_UP_FRACTION` | 爬升占比 | 总时长中爬升比例 | 0.2 | 与峰值占比之和 <1 |
| load | `LOAD_RAMP_PEAK_FRACTION` | 峰值爬升占比 | 继续升到峰值比例 | 0.2 | 同上 |
| spike | `SPIKE_BASELINE_VUS` | 基线 VU | 尖峰前并发 | 50 | ≥1 |
| spike | `SPIKE_BASELINE_DURATION` | 基线时长 | 尖峰前持续时间 | 30s | duration |
| spike | `SPIKE_RAMP_UP` | 爬升时长 | 基線升到峰值时间 | 10s | duration |
| spike | `SPIKE_VUS` | 峰值 VU | 尖峰并发 | 800 | ≤1000 |
| spike | `SPIKE_DURATION` | 峰值时长 | 峰值保持 | 60s | duration |
| spike | `SPIKE_RAMP_DOWN` | 回落时长 | 峰值回到 0 | 10s | duration |
| 通用 | `P95_MS` | P95 阈值 | 95% 请求延迟上限 | 300 | ≥0 |
| 通用 | `P99_MS` | P99 阈值 | 99% 请求延迟上限 | 800 | ≥0 |
| 通用 | `ERROR_RATE` | 错误率阈值 | 允许的最大错误比例 | 0.01 | 0–1 |
| 通用 | `CHECKS_RATE` | 校验通过率 | k6 check 通过下限 | 0.99 | 0–1 |
| 通用 | `TRAFFIC_MODE` | 流量模式 | `via-nginx` 或 `target-instance` | `via-nginx` | 枚举 |
| 通用 | `TARGET_INSTANCE` | 目标实例 | 定向模式的实例 | 空 | `app-1/2/3` |
| 通用 | `note` | 备注 | 台账/清理标记 | 空 | ≤200 字符 |
| 通用 | `source` | 来源 | 来源标记 | 空 | ≤200 字符 |
| 只读 | `RUN_ID/BASE_URL/K6_PROMETHEUS_RW_*` | 系统字段 | 由 run/配置注入，不可覆盖 | — | — |

### 4.2 故障参数

| 类别 | 参数 | 中文名 | 功能解释 | 默认/约束 |
|---|---|---|---|---|
| 容器 | `name` | 目标容器 | 实验容器 canonical name | 白名单 |
| 容器 | `action` | 动作 | kill/start/restart/freeze/resume | 必填 |
| 容器 | `ttlSeconds` | 自动恢复秒数 | 到点反向动作 | 120，最大 600 |
| 容器 | `runId` | 关联实验 | 写 run 故障时间线 | 可选 |
| 线程 | `instance` | 实例 | app-1/2/3 | 白名单 |
| 线程 | `mode` | 模式 | 7 种线程故障 | 必填 |
| 线程 | `hold` | 保持方式 | auto/manual | auto |
| 线程 | `ttlSeconds` | 自动恢复秒数 | auto 有效 | manual 忽略 |
| 线程 | `virtual` | 虚拟线程 | thread-leak 对照 | 可空 |
| Toxiproxy | `proxy/type/name` | 代理/类型/名称 | redis/mysql；latency/timeout | 白名单 |
| Toxiproxy | `latencyMs/jitterMs/timeoutMs` | 延迟/抖动/超时 | 网络毒性参数 | 整数 |
| Toxiproxy | `runId` | 关联实验 | 写时间线 | 可选 |

### 4.3 实例配置参数（B4 目标）

| 参数 | 中文名 | 功能解释 | 生效方式 |
|---|---|---|---|
| `jdkVersion` | JDK 版本 | 8/21 | 重建 |
| `imageTag` | 镜像 | 已验证镜像 | 重建 |
| `xms`/`xmx` | 初始/最大堆 | JVM 堆大小 | 重启 |
| `gcCollector` | GC 类型 | G1/Parallel 等 | 重启 |
| `maxGcPauseMillis` | G1 最大停顿 | 停顿目标 | 重启 |
| `gcLogEnabled`/`gcLogPath` | GC 日志 | 诊断日志 | 重启 |
| `corePoolSize/maxPoolSize/queueCapacity/keepAliveSeconds/rejectionPolicy` | 业务线程池 | 并发与拒绝策略 | 动态/重启 |
| `caffeineEnabled`/`l1MaxEntries`/`l2TtlSeconds` | 缓存 | L1 开关/容量/L2 TTL | 动态/重启 |
| `hikariMaxPoolSize` | 连接池上限 | DB 连接 | 动态/重启 |
| `memoryLimitMb` | 容器内存上限 | 预算校验 | 重建 |

### 4.4 缓存实验参数（B3 目标）

| 参数 | 中文名 | 功能解释 | 范围 |
|---|---|---|---|
| `l1Enabled` | L1 开关 | 每实例独立；关闭=绕过并清空 | 开/关 |
| `l1TargetEntries` | L1 目标条目 | 目标规模 | 0–10000 |
| `l1MaxEntries` | L1 最大容量 | 淘汰上限 | 1000–20000 |
| `l2Enabled` | L2 开关 | Redis 缓存 | 开/关 |
| `redisTargetKeys` | Redis 目标键数 | 目标规模 | 0–50000 |
| `l2TtlSeconds` | L2 TTL | 过期时间 | 30s–30m |
| `seedCount` | 播种数量 | 内置商品/实验键 | 默认 10000 |
| `prefix` | 键前缀 | SCAN 白名单 | `cache:product:*` |
| `cursor/limit` | 游标/上限 | 分页 | 上限配置 |

### 4.5 指标/查询参数

| 参数 | 中文名 | 功能解释 | 默认 |
|---|---|---|---|
| `from/to` | 时间窗起止 | 指标聚合窗口 | run 窗口 = 开始−grace .. 结束+grace |
| `keys` | 指标键 | 选择要查的指标 | 后端目录 |
| `phase` | 阶段 | 预热/正式/恢复等窗口 | 默认 |
| `dimension` | 热度维度 | 综合/CPU/内存/线程池/网络 | B5 |

### 4.6 预检查与执行器配置

| 键 | 中文名 | 功能解释 | 默认 |
|---|---|---|---|
| `LAB_PRECHECK_ENABLED` | 预检查开关 | 整体启用/禁用 | true |
| `LAB_PRECHECK_TRAFFIC_CONTAINER` | 背景流量容器 | 必须停止 | jl-traffic |
| `LAB_PRECHECK_APP_CONTAINERS` | 实例清单 | 镜像一致性 | jl-app-1/2/3 |
| `LAB_PRECHECK_REQUIRE_REDIS_EXPORTER` | 要求 Redis exporter | 关键序列 | true |
| `LAB_PRECHECK_REQUIRE_MYSQL_PROBE` | 要求 MySQL 探针 | 关键序列 | true |
| `LAB_PRECHECK_EXTRA_QUERIES` | 额外查询 | name=promql | 空 |
| `LAB_PRECHECK_MEMORY_RESERVE_MB` | 内存预留 | 资源预算 | 1024 |
| `LAB_BENCH_EXEC_K6_IMAGE` | k6 镜像 | 固定镜像 | grafana/k6:1.3.0 |
| `LAB_BENCH_EXEC_MAX_VUS` | 最大 VU | 参数上限 | 1000 |
| `LAB_BENCH_EXEC_MAX_DURATION_SECONDS` | 最大时长 | 参数上限 | 3600 |
| `LAB_BENCH_EXEC_TARGET_INSTANCES` | 定向实例表 | 固定服务名 | app-1,app-2,app-3 |
| `LAB_BENCH_EXEC_TIMEOUT_GRACE_SECONDS` | 超时宽限 | 请求时长之外 | 30 |
| `LAB_BENCH_EXEC_STOPPING_SETTLE_GRACE_SECONDS` | STOPPING 宽限 | 卡死补完成 | 30 |

> 完整 `LAB_BENCH_EXEC_*` 26 键与 `LAB_PRECHECK_*` 12 键见 B2 收口文档与 `.env.example`。

---

## 5. 端到端流程（设计 §6.2/§9）

### 5.1 正式压测（A01）

1. 实验记录 → 新建 run（场景 `load`，参数：VU/时长/阈值/流量模式/备注）。
2. 预检查：背景流量停止、无残留故障/未结操作、监控可用、资源与镜像一致；阻断不可跳过。
3. 启动：DRAFT→…→RUNNING，创建固定镜像 k6 容器；返回 operationId（可能 202 待确认）。
4. 观测：k6 卡 RUNNING、日志、KPI；via-nginx 时看三实例分流。
5. 结束：自然退出→COMPLETED；非零/超时→FAILED；报告 summary 可下载。
6. 收尾：stop 释放写锁；stop-all 清残留；必要时空闲复位数据。

### 5.2 定向对照（A17）

1. `TRAFFIC_MODE=target-instance`、`TARGET_INSTANCE=app-2`。
2. 启动后 k6 直连 `http://app-2:8080`，绕过 Nginx。
3. 核对：app-2 业务请求 >0，app-1/3 ≈0，Nginx 窗口无 `/api`；k6 标签 `instance=app-2`。
4. 对照组用 `via-nginx`，三实例约各 1/3；报告标注 `bypassNginx`。

### 5.3 缓存 A/B（B3 目标）

1. 设置目标 L1/L2 键数与 TTL，生成固定 seed 数据并预热。
2. 核验目标/实际/差值/每实例 L1 数量。
3. A 组 L1+L2、B 组 L2，同数据集/VU/时长；C/D 用 chaos-proxy 模拟 Redis 不可用。
4. 对比命中率、回源、P95/P99、错误；业务对账；明确正常关闭与故障组标签不同。

### 5.4 JVM/GC 对照（B4 目标）

1. 逐实例选 JDK/镜像/堆/GC/业务池；校验全部（白名单、兼容、内存预算）。
2. 查看变更差异与影响范围；应用 → 受控重建 → 健康/上游/监控/探针全过 → 稳定期。
3. 失败自动回滚；报告记录旧/新配置、镜像、重启时间、生效结果。
4. JDK8 标 `LIMITED-OBSERVABILITY`，热回读 `READBACK_UNAVAILABLE`，指标来源标注。

### 5.5 故障演练（A09/A12）

1. 创建/启动 run（可选），注入容器/线程/网络故障，带 `runId` 写时间线。
2. 观察动作列表剩余 TTL、暂停冻结、状态；事件进入 run `faults`。
3. 单项撤销或 stop-all：容器/网络→traffic→线程→k6；失败分子项报告。
4. 确认应用健康、线程清空、k6/traffic 无运行容器；stop-all 后 RUNNING run 需再 stop（见 §7.4）。

### 5.6 重启恢复（A21/A29）

1. 控制器重启把 RUNNING/STOPPING/VERIFYING 标 UNKNOWN，并列出清理建议。
2. 未确认操作标 UNKNOWN；人工核验后 `POST /lab/operations/{id}/close` 记 RESTORED/FAILED。
3. UNKNOWN run 由操作员 stop 收盘；锁释放后再启动新 run。

---

## 6. 需求 / 验收对照

### 6.1 建设目标（需求 §2.1）

| # | 目标 | 状态 |
|---|---|---|
| 1 | UI 选择实例/JDK/JVM/应用配置 | ○ B4 |
| 2 | UI 控制缓存策略、数据规模、键数 | ◐ B3 |
| 3 | UI 配置并启动 k6（VU/时长/场景/阈值） | ◐ 后端/JSON，缺结构化表单 |
| 4 | 手动制造/暂停/恢复/撤销故障 | ✅ 后端；UI 线程仍旧代理 |
| 5 | 控制台看 Prometheus/Grafana、按实例/实验/窗口归集 | ✅ 基础；B5 热度/分流 |
| 6 | 对比 JVM/缓存/实例/故障 | ○ B5 |
| 7 | 保存配置/事件/指标/结论，重复与回看 | ✅ 台账；重复 ○ |

### 6.2 最终验收标准（需求 §十）

| # | 标准 | 状态 |
|---|---|---|
| 1 | 控制台启动 k6，无需终端 | ✅ A01 |
| 2 | 控制台看关键指标并进 Grafana | ✅ |
| 3 | 按实例看 JVM/池/缓存/业务指标 | ◐ 基础；B5 完善 |
| 4 | 每实例不同 JDK/堆/配置 | ○ B4 |
| 5 | 三实例同源码，报告可见版本/镜像 | ✅ 台账 env/镜像 |
| 6 | 切 L1、控制数据规模、显示目标/实际 | ◐ B3 |
| 7 | L1 开关同负载对照与差值 | ○ B3 |
| 8 | 造故障、看影响、恢复并确认 | ✅ |
| 9 | 任意两次对比并保存配置/指标/事件 | ○ B5 |
| 10 | 业务数据判断库存/订单/幂等/队列 | ○ B3 |
| 11 | 结束恢复实例/清故障/复位数据 | ◐ 清故障 ✅；复位 B3 |

### 6.3 11 项范围确认（需求 §十一）

| # | 结论 | 状态 |
|---|---|---|
| 1 | 台账复用栈内 MySQL，独立库 | ✅ |
| 2 | 侧边导航工作区 | ✅ |
| 3 | 10,000 固定 seed 商品，替换 1/20，库存 0–500 | ○ B3 |
| 4 | stress/soak 不进 UI | ✅ |
| 5 | bandwidth 隐藏 | ✅ |
| 6 | chaos-proxy 与 HA 互斥 | ✅ 口径 |
| 7 | JDK8 LIMITED-OBSERVABILITY | ○ B4 |
| 8 | 镜像身份最小闭环 | ✅ |
| 9 | 商品基线替换 | ○ B3 |
| 10 | 库存 0–500 | ○ B3 |
| 11 | lab_ledger 10 表 + Flyway + 保留 50 | ✅ |

### 6.4 需要你确认的差异

1. **结构化参数表单**：是否把实验记录的新建表单从 JSON 改为中文分组表单（推荐）。
2. **场景目录中文标签**：是否把 `LOAD_VUS=200` 改为「峰值 VU 200（保持阶段并发）」并加说明。
3. **7 步向导**：当前只有简单剧本向导；完整向导在 B5/A13，是否接受。
4. **实例配置/B3/B5**：这些是计划内未实现项，是否按当前批次顺序继续。
5. **线程 UI**：是否现在从旧代理切到 `/chaos/instances/{name}/thread/**`（可拿到 operationId/事件）。
6. **stop-all 后 RUNNING run**：审核 Minor-3；是否要求 stop-all 同时收盘 run 并释放锁。
7. **A10 HA 切换**：是否接受引用 Stage1/B0 证据，B2 不重跑完整切换。
8. **traffic_mixed**：是否保持恒 false（预检查停背景流量，不做自动检测）。
9. **EXITED k6 容器**：是否保持“保留到 stop-all/重启清扫”。
10. **状态文案**：是否保持英文枚举（`RUNNING`）还是改中文。

---

## 7. 已知差异与风险（不是隐藏项）

| 项 | 说明 | 归属 |
|---|---|---|
| stop-all 后 run 行仍 RUNNING | 停 k6 后 run 行/写锁未收盘，需操作员再 stop；审核 M3 | 专项/B5 |
| 线程 UI 旧代理 | 新入口有 operationId/事件，UI 尚未切换 | B5/专项 |
| Toxiproxy 默认 dev 不可达 | stop-all 的 toxiproxy 子项会报 failed；需 chaos-proxy 档复跑 | B3-03/专项 |
| 防御性常量未外置 | `LOG_WAIT_SECONDS=15`、默认 tail=200、4MiB 上限；审核 M1 | 待裁决 |
| 报告目录 0777 无单测 | Windows 无法断言 POSIX 权限；真机 A01 已验 | 记录 |
| shell 存量 CRLF | `.gitattributes` 与 B2 前存量冲突；单独批次 renormalize | 专项 |
| stress/soak/bandwidth | 已确认 Future | Future |
| 对账/复位 UI | B3/B5 未接 | B3/B5 |
