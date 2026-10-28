# 批次收口：Stage2 B1（持久化台账、状态机、观测底座与环境锁）

> 日期：2026-10-28
> 分支：`feature/stage2-workbench`
> 范围：B1-01 … B1-05（合并提交 `8d93d6e`、`e2feb6f`、`deced73`、`51ba5b8`、`f2e47cb` 及各自修正/文档提交）
> 计划依据：`doc/process/Stage2/stage2-开发测试与验收计划.md` §0/§2/§7；设计 §10.5/§10.3/§10.1/§9

# 当前进展

B1 五个步骤全部合并并完成真机验证，批次闸门如下（2026-10-28 当场输出）：

| 闸门 | 结果 |
|---|---|
| Java 单测（reactor） | **1029 / 0 fail / 0 error / 0 skip**（chaos 474、app 469、jdk8 59、core 13、platform 10+4） |
| `verify.sh` | **109 PASS / 1 WARN / 0 FAIL**（唯一 WARN 仍是 cAdvisor/WSL2） |
| `smoke.sh` | 7/7 |
| `console-smoke.sh` | **63 PASS / 0 FAIL**（旧 42 基线作废） |
| k6 脚本回归 | 79 pass / 0 fail |
| 文档断言 | **37/37 PASS**（B0 的 32 + B1 新增 5） |
| 安全静态检查 | PASS |
| env 接线回归 | 8 chaos + 59 ledger/metrics/precheck 键，全部 PASS |

# 完成功能项

## B1-01 台账持久化（`8d93d6e`）
- 栈内 MySQL 独立库 `lab_ledger`，Flyway V1 十表；standalone 走 `mysql:3306`、db-ha 走 `mysql-router:6446`（与 reset-lab 同口径）。
- run 状态机（`DRAFT→…→COMPLETED/FAILED/INVALID/ABORTED`，另有重启写入的 `UNKNOWN`）；写失败落本地卷文件暂存并按序补写；重启恢复 `RUNNING/STOPPING/VERIFYING→UNKNOWN`；保留最近 50 run 级联清理。
- REST：`POST/GET /lab/runs`、`GET /lab/runs/{id}`、`GET /lab/ledger/status`、`GET /lab/ledger/recovery`。
- 真机：A21 重启恢复、Router 6446 双拓扑写入、卷 `jl-ledger-stash-data`。

## B1-02 操作契约 / 幂等 / 环境锁（`e2feb6f`）
- Flyway V2：`idempotency_key`、`operator`、`before_summary`、`after_summary`。
- 每笔环境变更一条 `lab_operation` + `operationId`；`Idempotency-Key` 600s 窗口回放；超同步预算 202/`PENDING_CONFIRMATION` 后台结算；`lab_env_lock` 单写 run（409/40902 带 holder 与解除方式）；`POST /lab/runs/{id}/start|stop`。
- 真机：A19 并发写 run、A29 超时待确认、幂等同键单副作用、V2 迁移。start/stop 为**契约先行**（预检查在 B1-04、k6 在 B2-02 接入）。

## B1-03 指标窗口聚合（`deced73`）
- 版本化 catalog 25 项（以指标字典为导出名事实源）；`GET /lab/metrics/window`、`GET /lab/runs/{id}/metrics`；按实例序列 + stats；`OK/NO_DATA/COLLECTING/DEGRADED`；run 上下文写 `lab_metric_window`；Grafana（`localhost:3000`）/Tempo 深链。
- 真机：A22 停 exporter → 过 grace 窗口 `NO_DATA`（无 0）、恢复 → OK、Prometheus 不可达 → DEGRADED、run 查询两批落表。

## B1-04 环境预检查（`51ba5b8` + `1cccfee`）
- 7 项检查（traffic/chaos/toxiproxy/operations/monitoring/resources/images），blocking 无 force；预检查先于状态转移与抢锁，阻断时 run 保持 DRAFT、零锁、operation FAILED；`GET /lab/runs/{id}/precheck`。
- 监控必需项外置：`LAB_PRECHECK_REQUIRE_REDIS_EXPORTER` / `REQUIRE_MYSQL_PROBE` / `EXTRA_QUERIES`。
- 真机 A26：traffic + freeze 下 409/40903（含 `jl-app-2 freeze (14s left)`），清理后重试到 RUNNING 并记录 `precheckVerdict`。

## B1-05 控制台工作区 + 台账页（`f2e47cb` + `889b4aa`）
- 四标签单页一次性重构为七分区侧边导航（总览/场景向导/实例与 JVM/Benchmark/实时监控/故障与缓存实验/实验记录），既有组件原样挂接。
- 台账页：列表筛选/新建 DRAFT/详情时间线/env/params/checks/operations/precheck/start-stop（每次新幂等键、202 轮询）/指标质量卡/localStorage 刷新恢复/503 显示 DEGRADED。
- 真机 console-smoke **63/0**；首轮暴露 `executor.rejected` 未进 KPI 清单的真实缺口，已修为第 13 张卡。

# 设计逻辑

- **为什么台账放栈内 MySQL**：演示环境已有 MySQL/Router，独立库 `lab_ledger` 与 `seckill` 物理分库，保留策略只动台账；不引入嵌入式库或新服务。杀主演练会命中写窗口，因此写路径设计为"暂存-补写"，动作不因台账失败而失败。
- **为什么 root**：业务账号只有 `seckill.*` 权限，建库需要 CREATE；控制器本就挂 docker.sock（root 等价），不扩大边界；密码只在 env，不写 URL/日志。
- **为什么恢复只跑一次**：仅在首次成功迁移后扫描未结束 run，避免 MySQL 短暂抖动把仍在运行的 run 误标 UNKNOWN。
- **为什么预检查先于抢锁**：阻断不应留下"半个 run"（状态前移或锁未释放）；预检查只读，失败后环境与台账状态都不变，换幂等键即可重试。
- **为什么指标质量不用 0 填充**：`NO_DATA`/`COLLECTING` 与真实 0 是不同事实；UI 必须逐项渲染质量徽标，整体 `OK` 只表示"查询成功且至少一项有数"。
- **为什么 UI 一次性重构**：旧四标签把向导、线程、缓存、动画割裂在多页；侧边导航按"观察→准备→调优→压测→看指标→注入故障→复盘"的工作流排列，组件迁移不重写业务逻辑。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 杀主写入窗口真实时序 | 暂存/补写有单测（拒绝连接的 DataSource），未在真实主库 kill 期间演练 | B2/B3 演练或专项 |
| 保留策略真机 | 50 run 级联删除只有单测 | 专项 |
| chaos TTL 到期不转 `RESTORED` | operation 停在 APPLIED；自动撤销需回写 | B2-04 |
| guide/traffic 未写 operation 行 | 统一契约只覆盖 chaos 动作与 run 生命周期 | B2-04 |
| 台账 DEGRADED 的 UI 分支 | 代码有 503/50302 展示，但 console-smoke 未自动化该分支 | B5 或专项 |
| Tempo 深链 | Tempo 3200 未发布宿主端口，链接需先发布 | B5-01 |
| `executor.rejected` 永远是 NO_DATA | 无导出名，等 B4-01 接拒绝计数器 | B4-01 |
| 启动参数/实例配置 | start 只推进状态，没有真实 k6 与配置应用 | B2-02/B4-02 |
| 阶段计划文档行尾 | 一次文本模式重写把 `stage2-开发测试与验收计划.md` 刷成 CRLF，已用 `fda2af9` 恢复 LF；后续一律字节级编辑 | 纪律 |
| 台账演练数据 | `lab_ledger` 留有 a21/a19/a29/a26/console-smoke 样本；保留策略会轮换 | 无需处理 |

# 待定事项和注意事项

1. **B2 开工顺序**：B2-01 场景白名单单一事实源 → B2-02 k6 执行容器化（把执行器接到 `READY→RUNNING` 与 `RUNNING→STOPPING` 之间）→ B2-03 流量模式 → B2-04 故障闭环（含 TTL→RESTORED 与 guide/traffic 入账）。
2. **B3 依赖**：`/lab/cache/keys?cursor=` 的响应上限已可经 `LAB_APP_PROXY_MAX_RESPONSE_BYTES` 放大；分页 E2E 在 B3-02 做。
3. **B4 依赖**：实例配置应用要复用 `RunLifecycleHook` 与 operation 契约；`executor.rejected` 在 B4-01 接线。
4. **重建与部署**：本机容器内到 Maven Central TLS 不通，Java 镜像需按 `~/.m2` 离线打包 + 运行时镜像；console 改动后需在主树 `npm run build`（nginx 挂主树 dist），再跑 `console-smoke.sh --no-build`。
5. **演示数据**：run 前预检查会挡住"背景流量未停/残留故障/未结操作"；演练后跑 `reset-lab.sh --yes` 复位库存与订单，再跑回归。
6. **审核**：按工程规约 §4 派独立 Qwen 会话对 B1 提交范围（`339bf4e..HEAD`）做批次审核，High/Medium 在隔离 worktree 修复后由主会话合并。
