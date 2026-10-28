# 批次收口：Stage2 B2（统一 k6 执行、流量归因与故障闭环）

> 日期：2026-10-28
> 分支：`feature/stage2-workbench`
> 范围：B2-01 … B2-04（从 B1 收口 `3bee92b` 到本文件提交）
> 计划依据：`doc/process/Stage2/stage2-开发测试与验收计划.md` §0/§3/§7；设计 §7/§8/§10.6

# 当前进展

B2 四个步骤全部合并并完成真机验证，批次闸门如下（2026-10-28 当场输出）：

| 闸门 | 结果 |
|---|---|
| Java 单测（reactor） | **1231 / 0 fail / 0 error / 0 skip**（chaos **676**、app 469、jdk8 59、core 13、platform 10+4） |
| `verify.sh` | **109 PASS / 1 WARN / 0 FAIL**（唯一 WARN 仍是 cAdvisor/WSL2） |
| `smoke.sh` | 7/7 |
| `console-smoke.sh` | **74 PASS / 0 FAIL**（B1 基线 63；B2-01/02/03/04 逐段增补） |
| k6 脚本回归 | **105 pass / 0 fail**（B1 基线 79；B2-01 +21、B2-03 +5） |
| 文档断言 | **41 / 41 PASS**（B1 37 + B2 新增 4 条能力断言） |
| env 接线回归 | **93 PASS / 0 FAIL**（8 chaos + 85 ledger/metrics/precheck/bench/exec） |
| 安全静态检查 | PASS |

# 完成功能项

## B2-01 场景白名单单一事实源
- `code/lab/k6/scenarios/manifest.json`（version 1）成为唯一事实源：run 视图 9 个、bench 视图 5 个、ui 视图 3 个（smoke/load/spike）；traffic 为 serviceOnly；stress/soak CLI 保留、不进 UI。
- `run.sh`/`bench.sh` 删除硬编码清单，经 `lib/scenario-manifest.mjs` 读取；目录↔manifest、视图包含、params/env 双向一致性由 21 条 node 测试钉死。
- `GET /lab/bench/scenarios` 投影同一 manifest；compose 只读挂载 `../k6:/app/k6`，`LAB_BENCH_SCENARIOS_LOCATION` 三处同默认；manifest 缺失/非法 → **503/50303**，不返回空目录。
- 控制台 Benchmark 场景卡只渲染 `views.ui`，带加载/错误/DEGRADED 三态。

## B2-02 k6 执行容器化
- `BenchLaunchPlanner`：仅 UI 白名单场景；参数只认 manifest `params`，类型/上限/控制字符校验；`RUN_ID`/`BASE_URL`/`K6_PROMETHEUS_RW_*` 强制；`note`/`source` 作为 run 元数据接受但不转发。
- 固定镜像与命令由配置渲染，无任意脚本路径/`--out`/挂载/镜像；scripts 只读、reports 命名卷可写。
- `BenchRunLifecycleHook` 接在 READY→RUNNING；stop 停容器；看门狗自然退出→COMPLETED、非零→FAILED、超时→FAILED+清理；STOPPING 卡死按宽限期补完成；孤儿容器启动清扫。
- `GET /lab/runs/{id}/k6`（ABSENT 200）、`/logs`（tail/字节上限）、`/report`（summary.json 下载）；控制台 k6 卡展示状态/容器/退出码/日志/报告。
- 真机 A01/A20：UI 启动 load 无终端、自然退出 COMPLETED、summary 4803 B 可下载；手动 stop；pause 后超时清理、容器删除、锁释放。

## B2-03 流量模式与定向归因
- `via-nginx`（默认）与 `target-instance` 两种受控模式；目标只来自 `LAB_BENCH_EXEC_TARGET_INSTANCES`（默认 app-1/2/3）+ `TARGET_PORT`，请求不能传 URL/host/port。
- k6 每请求带 `instance`/`traffic_mode` 标签，标签不可被场景覆盖；容器标签/执行状态/start afterSummary 记录模式、目标、是否绕过 Nginx、upstream 等权表、`traffic_mixed=false`。
- 真机 A17：target app-2 时 app-2 业务请求 39.7、app-1/3 为 0，Nginx 窗口 0 条 `/api`；via-nginx 三实例 14.2/13.1/14.2，Nginx 日志 38 条（k6 44.25，口径差异已记录）；console-smoke 断言 via-nginx 归因。

## B2-04 故障闭环与 UNKNOWN 关闭
- 动作视图/列表带 `paused`、`remainingMs`（暂停冻结/未暂停按 expiry）。
- 容器动作可选 `runId` 写 `lab_fault_event`；run 详情新增非 null 的 `faults` 数组；控制台独立「故障事件」块。
- Toxiproxy 6 个写端点与线程故障 3 个入口接入 operation 契约：原响应体 + `X-Operation-Id`，pending 返回 OperationView；成功写故障事件（source=toxiproxy/thread）。
- `POST /lab/operations/{id}/close`：UNKNOWN→RESTORED/FAILED，幂等同终态 200、异终态 409、非 UNKNOWN 409、note ≤512。
- `POST /chaos/stop-all` 固定顺序覆盖容器/Toxiproxy → traffic → 线程（逐实例）→ k6；结果新增 traffic/threads/k6 计数，失败按子项隔离。
- 真机 A09：线程 deadlock（manual）带 runId，run 详情出现 `thread:thread-fault`；stop-all 后线程状态清空。
- 真机 A12：stop-all 后 traffic stopped、三实例线程 ok、k6 cleaned、活跃 freeze reverted=1、应用三实例 200、无残留运行容器。
- 真机 A29：UNKNOWN 关闭 RESTORED、重复 200、异终态 409。

# 设计逻辑

- **单一事实源**：三处清单（目录/run.sh/bench.sh）不一致是 Stage1 遗留；manifest + node reader 让 UI/CLI/API 共用一份数据，后端只读投影，缺失时 DEGRADED 不猜。
- **执行器白名单**：k6 容器由控制器创建，镜像/命令/挂载/网络/参数全部来自配置与 manifest；前端不能传任意 URL/脚本/`--out`。`summary.json` 写容器内 `/reports`（避坑 18），报告目录对 uid 12345 可写。
- **生命周期归属**：hook 只做启停，状态与锁仍由 `RunLifecycleService` 拥有；看门狗按 first-verdict-wins 做 CAS，台账暂存时保留锁并下一 tick 重试，STOPPING 宽限期避免抢判正常 stop。
- **流量归因**：target-instance 绕过 Nginx，via-nginx 由 upstream 分流；k6 标签用于 run 维度归因，应用侧 `instance` 指标用于服务端核对；`traffic_mixed` 恒 false 是因为预检查要求背景流量停止，不做自动检测，避免把观察值当因果。
- **故障闭环**：容器/Toxiproxy/线程三类动作统一 operationId；事件只在动作成功后写；stop-all 四层固定顺序，先回滚破坏性层，再停流量，最后清 k6，任何子项失败只影响自身计数。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| stop-all 后的 run 行 | stop-all 停掉 RUNNING run 的 k6 容器后，run 行仍 RUNNING、写锁仍持有；看门狗对 ABSENT 有意不动，需操作员再 stop。行为安全但演示脚本应在 stop-all 后补一次 stop，或后续把 stop-all 纳入 run 收盘 | 专项/B5 |
| A10 HA 切换 | B2 只补了故障时间线与 stop-all；Redis/MySQL 切换本身沿用 Stage1/B0 的 HA 验收证据，未在 B2 重跑完整切换 | B5 全量验收或专项 |
| 线程实验室 UI 仍走旧代理 | `/chaos/app/{instance}/lab/thread/**` 继续用于 GET/lifecycle；线程故障的 operationId/事件只有新入口才有，UI 注入暂不带 runId | B5 UI 或专项 |
| Toxiproxy 在默认 dev 栈不可达 | stop-all 的 toxiproxy 子项报 failed（无 chaos-proxy profile）；未在 chaos-proxy 栈复跑 A12 | B3-03 / 专项 |
| `traffic_mixed` 恒 false | 预检查要求背景 traffic 停止；不做自动混合检测，报告按 false 记录 | 设计口径 |
| `GET /chaos/actions` 的 operationId 为 null | B1 行为：列表视图不携带 operation；POST 响应/详情才有。不影响动作追踪，但列表无法直接跳到 operation | 专项 |
| EXITED k6 容器保留 | 自然退出/手动 stop 后保留日志/报告，stop-all 或控制器重启的孤儿清扫会删除 | 设计口径 |
| A29 真机使用合成 UNKNOWN 行 | 关闭端点真机验证用一条 SQL 合成的 UNKNOWN 操作；真实「重启正好打断 in-flight 操作」时序仍未脚本化 | 专项 |
| console-smoke 未覆盖 UNKNOWN 关闭 | 需要制造 UNKNOWN，未加入冒烟 | B5 或专项 |
| stress/soak UI | 已确认首期不进 UI，CLI 保留 | Future |
| bandwidth 毒性 | 已确认首期隐藏 | Future |

# 独立审核结论（2026-10-28，review-B2）

- 范围：`3bee92b..3012120`（B2-01…04 + 收口 + claims），隔离 worktree，审核会话只跑单元/Testcontainers 与静态脚本，未碰共享栈。
- 计数：**Critical 0 / High 0 / Medium 0 / Minor 5**；结论 **Approved，无修复提交**。
- 审核侧复跑：chaos **676 / 0 / 0 / 0**、node **105 / 0**、claims **41 / 41**、env 接线 **93**、safety PASS、dashboard build 成功。
- Minor（记录，不阻塞）：
  - M1 防御性常量未外置：`DockerK6ContainerGateway.LOG_WAIT_SECONDS=15`、`BenchRunExecutor.DEFAULT_LOG_TAIL=200`、MAX_TAIL/4MiB 兜底；属实现内防御上限，是否入 `LAB_BENCH_EXEC_*` 待裁决。
  - M2 报告目录 0777 无 Windows 单测，只有真机 A01 证据（scripts:ro/reports:rw、summary 4803 B）。
  - M3 stop-all 停 k6 后 run 行仍 RUNNING、锁仍持有（见缺陷表），演示脚本需补 stop 或后续纳入 run 收盘。
  - M4 文档小漂移：B2-04 交接“待定§1”建议 source=network，实现为 toxiproxy；`架构现状与功能清单.md` 流量面行仍引旧 `run.sh:49-57`。
  - M5 `.gitattributes` 声明 `*.sh eol=lf`，但 `run.sh/bench.sh` 在 B2 前已是 CRLF；全仓 renormalize 超出本批范围，单独批次处理。
- 亮点：planner 拒绝矩阵逐条钉住；`OperationContext` 按 id 排除自身不放宽门禁；网关停删前重查 lab+role；close 不碰 run 锁；LAB_BENCH 26 键四表默认一致；claims 升级为 shell 真校验。
- 主会话关注：M3 的 stop-all 后 run 收盘口径；M5 行尾统一单独排期；verify/smoke/console-smoke 三个真栈闸门已由主会话当场复跑（109/1、7/7、74/0）。

# 待定事项和注意事项

1. **独立审核**：按工程规约 §4 派独立 Qwen 会话 + 隔离 worktree 审核 B2 范围（`3bee92b..HEAD`），只跑单元/Testcontainers，不碰共享栈；High/Medium 修复后主会话合并并复跑。
2. **B3 依赖**：`/lab/cache/keys?cursor=` 的分页/响应上限已具备代理开关；内置 10,000 商品替换商品 1/20 基线随 B3-02 做；A/B 对照使用 B2 的 run/指标/报告底座。
3. **B4 依赖**：实例配置应用复用 `RunLifecycleHook` 与 operation 契约；`executor.rejected` 在 B4-01 接线。
4. **B5 依赖**：实时拓扑热度/分流面板/重复实验/全量验收；线程 UI 统一入口可在 B5 顺带。
5. **真机部署**：控制器镜像仍按本地 jar 离线打包（容器内 Maven Central TLS 不通）；网络恢复后按仓库 Dockerfile 重建一次恢复溯源。
6. **演示数据**：B2 的 load 场景只读，不消耗库存；演练后仍建议 `reset-lab.sh --yes` 再跑 smoke。
