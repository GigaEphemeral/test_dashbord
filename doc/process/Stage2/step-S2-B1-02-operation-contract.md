# 步骤交接：S2-B1-02 操作契约 / 幂等 / 环境锁

> 任务/步骤：Stage2 B1-02 —— 每一次会改变环境的操作写入 `lab_operation` 并返回 `operationId`；`Idempotency-Key` 幂等窗口；同步等待预算与 `PENDING_CONFIRMATION`；写型 run 环境锁与 `40902`；`GET /lab/operations` 审计查询
> 日期：2026-10-28
> 分支 / HEAD：`feature/stage2-workbench` 基线 `d993c34`（B1-01 已合并验收）；本步提交见附录 B
> 关联任务：`doc/process/Stage2/stage2-开发测试与验收计划.md` §2 B1-02；`doc/design/Stage2/stage2改造方案.md` §10.5、§10.4「故障动作 API 超时与 Docker stop 契约」；验收 A19、A29、A31（契约半区）
> 测试：`mvn -pl services/chaos-controller -am test` → chaos-controller **384 tests / 0 fail / 0 error / 0 skip**（B1-01 基线 333，本步 +51）
> 全量：`mvn test`（reactor）→ **939 tests / BUILD SUCCESS**（10 + 4 + 13 + 469 + 59 + 384）
> 静态安全：`bash code/lab/scripts/test-lab-safety.sh` → `LAB SAFETY TESTS PASSED.`
> 环境接线：`bash code/lab/scripts/test-chaos-env-wiring.sh` → `CHAOS ENV WIRING PASSED (8 chaos keys + 34 ledger keys)`
> Compose 渲染：`docker compose --env-file code/lab/docker/.env.example -f code/lab/docker/compose.base.yml config` → EXIT=0，7 个新键全部展开
> 运行状态：只跑单测/Testcontainers（本机 Docker 起临时 `mysql:8.4.4`）；**未起共享 lab 栈、未执行任何 compose/up/down/verify/reset 命令**；真机演练清单见 §4

# 当前进展

- 统一操作契约落地：run start、run stop、容器故障注入/撤销（`POST /chaos/instances/{name}/actions`）、
  `POST /chaos/stop-all` 全部先写 `lab_operation`，响应带 `operationId`；状态机
  `REQUESTED → APPLIED / RESTORED / FAILED`，超过同步预算 → `PENDING_CONFIRMATION`（后台继续），
  控制器重启由 B1-01 的 `LedgerRecovery` 把 `REQUESTED / PENDING_CONFIRMATION` 标 `UNKNOWN`。
- 幂等键：`Idempotency-Key`（1–128 可见 ASCII，非法 400）；同 key + 同 action + 同 target 在窗口内
  只产生一个副作用并返回同一 `operationId`（HTTP 200 + 原记录）；窗口外 key 自动释放可复用。
  Flyway **V2** 给 `lab_operation` 加 `idempotency_key` + 复合唯一索引，并补 `operator`、
  `before_summary`、`after_summary` 三个审计字段。
- 超时≠失败：操作在**专用 daemon 线程池**（`ledger-operation-*`，池大小/线程名外置，未占用 chaos TTL
  /revert 调度器）执行；`lab.ledger.operation.sync-wait-ms` 到点后 API 回 202 + `PENDING_CONFIRMATION`，
  后台完成时把行改成 `APPLIED/RESTORED/FAILED`。`GET /lab/operations/{id}` 与
  `GET /lab/operations?runId=&status=&limit=&offset=` 供轮询。
- 写型 run 环境锁：`POST /lab/runs/{runId}/start` 先抢 `lab_env_lock`（单活动写 run，TTL 外置），
  拿到锁后 `DRAFT→VALIDATING→READY→RUNNING`；冲突 409/**40902**，消息含持有者 runId、到期时间与
  解除方式（`POST /lab/runs/{holder}/stop`，或等 TTL）。`POST .../stop` 走
  `RUNNING→STOPPING→COMPLETED` 并释放锁；释放结果（含"锁已被 TTL 回收/被别的 run 接管"）写进
  operation 的 `after_summary` 返回。
- 审计：`last_error` 与 before/after 摘要统一过 `AuditSanitizer`（丢弃 password/token/secret/
  credential/apiKey/authorization 键，掩码 `password=…`、`Bearer …`、URL 内嵌口令），凭据不落库；
  `operator` 取 `LAB_LEDGER_OPERATOR`（默认 `local`）。

# 完成功能项

## 1. Schema（`V2__operation_contract.sql`）

| 变更 | 说明 |
|---|---|
| `lab_operation.idempotency_key VARCHAR(128) NULL` | 可空：无 header 与内部操作不参与唯一约束（MySQL 复合唯一键遇到 NULL 不约束） |
| `UNIQUE KEY uk_lab_operation_idempotency (idempotency_key, action, target_kind, target_name)` | 同 key 用于不同 action/target 是不同契约；并发重复插入由索引兜底 |
| `operator VARCHAR(64) NULL` | 审计身份，逐行保存，不受后续配置变更影响 |
| `before_summary JSON NULL` / `after_summary JSON NULL` | 请求前/后摘要（已脱敏），供 `GET /lab/operations/{id}` |

窗口本身不进 schema（`lab.ledger.idempotency.window-seconds` 外置）：查找命中且
`requested_at + window > now` 即回放；过期则把旧行的 `idempotency_key` 置 NULL 后重新执行，
因此 V2 的唯一索引不会永久占用 key。

## 2. 新配置键（7 个，三处默认值一致）

| 键 | env | 默认 |
|---|---|---|
| `lab.ledger.operation.thread-name` | `LAB_LEDGER_OPERATION_THREAD_NAME` | ledger-operation |
| `lab.ledger.operation.pool-size` | `LAB_LEDGER_OPERATION_POOL_SIZE` | 2 |
| `lab.ledger.operation.sync-wait-ms` | `LAB_LEDGER_OPERATION_SYNC_WAIT_MS` | 3000 |
| `lab.ledger.idempotency.window-seconds` | `LAB_LEDGER_IDEMPOTENCY_WINDOW_SECONDS` | 600 |
| `lab.ledger.env-lock.key` | `LAB_LEDGER_ENV_LOCK_KEY` | env:write-run |
| `lab.ledger.env-lock.ttl-seconds` | `LAB_LEDGER_ENV_LOCK_TTL_SECONDS` | 1800 |
| `lab.ledger.env-lock.acquire-attempts` | `LAB_LEDGER_ENV_LOCK_ACQUIRE_ATTEMPTS` | 3 |

`LEDGER_KEYS` 由 `test-chaos-env-wiring.sh` 自动发现，三处缺一即 FAIL（本步 34 个台账键全 PASS）。

## 3. REST 变更

| 方法 | 路径 | 状态码 | 说明 |
|---|---|---|---|
| `POST` | `/lab/runs/{runId}/start` | 200 / 202 / 404 / 409(40901) / 409(40902) / 503(50302) | 抢锁 + 状态机推进；202 = `PENDING_CONFIRMATION`；200 + 原记录 = 幂等回放 |
| `POST` | `/lab/runs/{runId}/stop` | 200 / 202 / 404 / 409 / 503 | `RUNNING→STOPPING→COMPLETED` + 释放锁；`UNKNOWN` run 收口为 `ABORTED` 并释放锁 |
| `GET` | `/lab/operations/{operationId}` | 200 / 404 / 503 | 含 operator、before/after 摘要、idempotentReplay |
| `GET` | `/lab/operations?runId=&status=&limit=&offset=` | 200 / 400 / 503 | 新→旧排序，limit 复用 `query-default/max-limit` |
| `POST` | `/chaos/instances/{name}/actions` | 202 / 200 / 400 / 403 / 404 / 409 / 503 | 响应体仍是 `ChaosActionView`，新增 `operationId`；超预算 202 + operation view（`PENDING_CONFIRMATION`）；幂等回放 200 + operation view |
| `POST` | `/chaos/stop-all` | 200 / 202 / 400 / 503 | `StopAllResult` 新增 `operationId`；**阻塞到完成**（见 §3.4），因此正常路径恒 200 并带真实计数 |

两个可变 chaos 端点接受可选 `Idempotency-Key`；`/lab/runs/{id}/start|stop` 同样接受。
run start/stop 的失败（非法转移、锁冲突）也先落一行 `FAILED` operation，便于审计。

# 设计逻辑

## 3.1 operationId 契约与状态语义

- 每次环境变更请求 **先插入 `lab_operation(REQUESTED)` 再执行**，因此任何响应（含 202/409）都能给
  出 operationId；`operationId = op-<uuid>`，与 B1-01 的 `run-yyyyMMdd-NNN` 不共享命名空间。
- 终态只由 worker 写：`completeOperation` 仅更新 `REQUESTED/PENDING_CONFIRMATION`，所以慢 worker
  永远覆盖不了已被恢复逻辑标成 `UNKNOWN` 的行；`OperationStatus` 显式维护合法转移表并有单测。
- 失败语义：work 抛异常 → 行置 `FAILED` + 脱敏 `last_error`（类名 + 消息，1024 截断），异常继续抛给
  调用方，沿用既有 400/403/404/409/503 映射。
- `RESTORED` 用于"恢复类"动作：容器 `start/resume`、`stop-all`；`kill/freeze/restart`、run start/stop
  记 `APPLIED`。

## 3.2 超时≠失败

- 提交后 `future.get(sync-wait-ms)`；超时先把行 CAS 到 `PENDING_CONFIRMATION`（note 写明预算与轮询路径）
  再回 202。若 worker 恰在超时与 CAS 之间完成，CAS 0 行 → 重新读库返回真实终态（不会把 APPLIED 谎报成待确认）。
- 专用线程池由 `LedgerConfig.ledgerOperationScheduler` 提供，daemon、线程名前缀与池大小外置；
  与 `chaosActionScheduler`（TTL revert/watchdog）彻底分开，慢操作不会拖住自动回滚。
- `stop-all` 例外地使用 `submitBlocking`：它是 teardown/恢复路径，k6 与 UI 需要最终计数，
  把它变成"待确认"反而掩盖环境是否恢复；操作行与幂等仍然生效。

## 3.3 幂等

- 查找键 = `(key, action, targetKind, targetName)`；窗口内命中原行直接返回（`replay=true`，HTTP 200），
  不执行 work；窗口外 `UPDATE ... SET idempotency_key=NULL` 后照常执行。
- 并发重复：两请求同时未命中 → 都尝试插入 → 唯一索引让一个失败 → 捕获 `DuplicateKeyException` 后重新
  读库返回赢家。副作用只有一次，operationId 唯一。
- 无 header 的行为完全不变（不做幂等，唯一索引里的 NULL 不约束）。

## 3.4 环境锁与 start/stop 的"契约先行"边界（重要）

- `lab_env_lock` 以 `lock_key` 为主键，insert-first 抢锁；同 run 重复抢视为成功（幂等回放丢失窗口时仍可重入），
  过期行用 `expires_at` 做 CAS 接管，活锁 → `EnvLockConflictException`（40902），消息含 holder runId、
  到期时间、`POST /lab/runs/{holder}/stop` 与"等 TTL"两条解除路径。
- start：抢锁 → `DRAFT→VALIDATING`（step `validate`）→ hook `precheck` → `VALIDATING→READY`（`ready`）
  → hook `launch` → `READY→RUNNING`（`start`）。任一步失败：若已进入 VALIDATING/READY 则补一次
  `→FAILED`，并尽力释放锁；若 run 仍是 RUNNING/未结束状态则**不释放**（不能偷走真实运行中的锁）。
- stop：`RUNNING→STOPPING→COMPLETED`，释放锁；`after_summary` = `{runStatus, lockReleased,
  lockReleaseNote?, lockHolderRunId?}`，TTL 回收/被接管时 `lockReleased=false` 并点名新 holder。
  `UNKNOWN` run 允许 stop，语义是 B1-01 定义的运维收口 `UNKNOWN→ABORTED` + 释放锁，`unknownCleanup=true`。
- **契约先行**：本批次**没有**真实预检查（B1-04）和 k6 执行（B2-02）。`VALIDATING` / `READY` 是持久化
  检查点，`RunLifecycleHook.precheck/launch` 是默认空实现的接缝；stop 也还不会停止任何容器。
  UI/报告**不得**把这两步读成"流量已启动/已停止"。B1-04 把预检查放进 `precheck`（阻断项会让 start 失败并
  把 run 落 FAILED、释放锁），B2-02 把 k6 启动放进 `launch`（此时 202 + PENDING 就是正常路径）。

## 3.5 数据库不可用时的降级

- run 生命周期 = `strictLedger`：台账不可用 → 50302，不产生不可见的 operation。
- 故障动作 = 非 strict：杀库演练本身就是演示场景，台账写失败只进 `LedgerStash`（新增 `OPERATION` 事件，
  补写按时间序、插入缺失行或结算未决行、首个终态获胜），动作照常执行并返回本地生成的 operationId；
  行在补写前 `GET /lab/operations/{id}` 会 404，`GET /lab/ledger/status` 的 `pendingStash` 可见。
- 已知取舍：若 create 事件因超上限被丢弃、只剩 settle 事件，补写行会插入为终态但丢失
  `idempotency_key`（不再可回放），审计字段仍然完整。

# 存在缺陷项&TODO

- **未接线：chaos 动作的 TTL 自动回滚不更新 operation 行**。`kill/freeze` 的 operation 在 TTL 到期后仍是
  `APPLIED`（故障确实已恢复，但行没有转 `RESTORED`）。B2-04「故障闭环统一」有 `autoRevert` 回调点，
  届时补一条 settle 或新 operation；本步刻意不碰 `ChaosActionService` 的 TTL/pause 语义。
- **未接线：guide / traffic 端点**（`POST /lab/guide/*`、`POST /lab/traffic/*`）仍不写 operation 行。
  改动会牵动向导状态机与 42 条 console-smoke 断言，按任务允许留 TODO，登记给 B1-05/B2-04。
- **契约先行**：VALIDATING/READY 没有真实校验内容；stop 不停止 k6/traffic、不撤销故障；失败回滚不涉及
  环境。见 §3.4。
- `POST /chaos/instances/{name}/actions` 超预算时响应体从 `ChaosActionView` 变为 operation view
  （缺 `id/expiresAt`）。k6 只校验 200/202（`lib/chaos.js`），控制台对容器按钮只做刷新，不影响；
  B1-05 重构控制台时按新契约渲染待确认状态。
- 幂等回放只覆盖"同一接口 + 同 action/target"；跨接口（先用 kill 再用 stop-all）不共享 key，属预期。
- `GET /lab/ledger/recovery` 仍只列 UNKNOWN run / 未确认操作，不单列被 UNKNOWN run 持有的锁；
  解除路径已通过 40902 消息与 `UNKNOWN→ABORTED` 提供。
- 行尾：`compose.base.yml` / `.env.example` / `application.yml` 在 git index 中为 CRLF，本步按字节级
  脚本插入并保持纯 CRLF（`git diff --numstat` 为纯新增，无整文件翻转）；新增 Java/SQL/MD 为 LF。

# 待定事项和注意事项

- **主会话真机演练（本 worktree 不做）**：
  1. 重建 `chaos-controller` 镜像并 `up -d --no-deps --force-recreate`；启动时 Flyway 自动跑到 V2
     （`GET /lab/ledger/status` → `schemaVersion=2`）。
  2. A19/A29 契约：`POST /lab/runs` 造两条 DRAFT → 对 A `start` → 对 B `start` 期望
     **409 + code 40902 + 消息含 A 的 runId**；`stop A` → `lockReleased=true`；再 `start B` 成功。
  3. 幂等：同 key 连续两次 `start`（先 stop 到可启动状态或换 run）→ 同 operationId、`lab_operation`
     只有一行；换 key → 两行。
  4. 超时：临时把 `LAB_LEDGER_OPERATION_SYNC_WAIT_MS=1` 重建控制器，`kill jl-app-2` 应 202 +
     `PENDING_CONFIRMATION`，随后 `GET /lab/operations/{id}` 变 `APPLIED`；再恢复默认值。
  5. 重启恢复：start 一个 run → `docker restart jl-chaos-controller` → run=`UNKNOWN`、
     operation（若仍 REQUESTED/PENDING）=`UNKNOWN`；`POST /lab/runs/{id}/stop` 应收口为 `ABORTED`
     并释放锁。
  6. 杀主演练：停 MySQL 主库期间 `kill jl-app-2` 仍应 202/200（非 strict 暂存），
     `GET /lab/ledger/status.pendingStash > 0`，主库恢复后补写可见。
  7. db-ha：`MYSQL_MODE=router` 下 V2 迁移与上述锁/幂等行为经 Router 6446 复验一次。
- 回归：`smoke.sh` 7/7、`verify.sh` 109 PASS/1 WARN、`console-smoke.sh` 42、k6 79 未在本 worktree 跑，
  合并后由主会话统一 verify；本步未改 `verify.sh`/`claims.yml`/计划基线数字。
- 不建议把 `lab_operation`/`lab_env_lock` 纳入 `reset-lab.sh`（审计与锁都属台账，不是演示数据）。
- 下一步：B1-03（指标聚合）与 B1-04（预检查接 `RunLifecycleHook.precheck`）可直接复用
  `OperationService`、`EnvLockService` 与 `GET /lab/operations`；B2-02 接 `launch`；B2-04 接 TTL settle 与
  guide/traffic 契约。

# 附录

## A. 命令与结果（本 worktree）

```bash
cd code
mvn -pl services/chaos-controller -am test
# chaos-controller: Tests run: 384, Failures: 0, Errors: 0, Skipped: 0 -> BUILD SUCCESS

mvn test
# 10 + 4 + 13 + 469 + 59 + 384 = 939, BUILD SUCCESS

bash code/lab/scripts/test-lab-safety.sh          # LAB SAFETY TESTS PASSED.
bash code/lab/scripts/test-chaos-env-wiring.sh    # PASSED (8 chaos keys + 34 ledger keys)
docker compose --env-file code/lab/docker/.env.example \
  -f code/lab/docker/compose.base.yml config      # EXIT=0，7 个新键展开
```

## B. 提交

| sha | 内容 |
|---|---|
| 见 `git log` | 实现：V2 迁移、operation 契约/幂等/锁/生命周期、REST、配置三处接线 |
| 见 `git log` | 测试：+51 用例（契约、幂等、锁、超时、恢复、脱敏、Schema V2） |
| 见 `git log` | 文档：本交接 |

## C. 测试分布（新增/扩充 51）

| 类 | 用例 | 覆盖 |
|---|---|---|
| `OperationServiceIT` | 7 | 同 key 单副作用/同 operationId、异 key 两次、同 key 异 action 不误回放、窗口过期可复用、慢执行 202→APPLIED、失败 FAILED + 脱敏、断库非 strict 继续执行并暂存 |
| `EnvLockIT` | 5 | 互斥与 holder 消息、同 run 重入不续租、释放后可抢、TTL 过期接管、迟到释放如实报告 |
| `RunLifecycleIT` | 10 | DRAFT→VALIDATING→READY→RUNNING + 锁、第二个 run 40902、重复 start 409、stop 释放锁、锁被接管如实报告、UNKNOWN→ABORTED、慢 hook 202→RUNNING、失败 start 落 FAILED 并释放锁、DRAFT stop 409、同 key 回放 |
| `LedgerSchemaIT`（+3） | 7 | V2 列/唯一索引/Flyway=2、NULL key 不约束、key+action 唯一、审计字段往返 |
| `LedgerControllerTest`（+10） | 18 | 200/202/200 回放、非法 header 400、40902 与 40901、operation 详情/列表/404/503 |
| `ChaosControllerTest`（+5） | 14 | operationId、慢动作 202 operation view、回放 200、非法 header 400、stop-all operationId |
| `LedgerPropertiesTest`（+2） | 11 | 7 个新键默认值/env/边界 |
| `AuditSanitizerTest` | 4 | 口令/Bearer/URL 掩码、摘要丢敏感键、1024 截断 |
| `IdempotencyKeysTest` | 3 | 空/1/128/129、空格与控制字符、非 ASCII |
| `OperationStatusTransitionTest` | 3 | 合法/非法转移、UNKNOWN 只由恢复写、终态不可改写 |
| `LedgerStashTest`（+1） | 5 | OPERATION 事件补写：插入 + 结算 + 幂等回放 |

## D. 关键文件

- 契约/服务：`code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/ledger/`
  （`OperationService`、`EnvLockService`、`RunLifecycleService`、`RunLifecycleHook`、`AuditSanitizer`、
  `IdempotencyKeys`、`OperationStatus`）
- DDL：`.../src/main/resources/db/ledger/V2__operation_contract.sql`
- REST：`ledger/LedgerController.java`、`web/ChaosController.java`、`web/LabExceptionHandler.java`
- 配置：`application.yml`、`code/lab/docker/compose.base.yml`、`code/lab/docker/.env.example`
- 测试：`.../src/test/java/com/javalearn/lab/chaos/ledger/`、`ChaosControllerTest.java`

## E. 补充：主会话真机演练（2026-10-28，合并 `e2feb6f` 后）

本节关闭上文"主会话真机"清单中的四项（A19、A29、幂等、V2 迁移）；杀主暂存窗口仍只有单测证据。

### E.1 镜像与迁移

- 沿用 B1-01 的离线构建路径（容器内 Maven Central TLS 不通）：`mvn -o -B -pl services/chaos-controller -am -DskipTests package`
  → 运行时镜像 `java-learn-lab/chaos-controller:jdk21`，imageId **`0a66c7db0769`**。
- `up -d --no-deps --force-recreate chaos-controller` 后：`GET /lab/ledger/status → schemaVersion="2"`；
  `flyway_schema_history` 为 `1 lab ledger`、`2 operation contract`；
  `lab_operation` 出现 `idempotency_key(varchar128, MUL)` 与 `before_summary/after_summary(json)`。

### E.2 A19 并发写 run（真机 PASS）

| 步骤 | 结果 |
|---|---|
| 创建 `run-20261028-002`、`-003`（DRAFT） | 201 |
| 并发 `POST /lab/runs/{id}/start` | 002 抢锁成功 → RUNNING；003 返回 **409 / 40902** |
| 003 的 409 响应体 | `write-run lock env:write-run is held by run run-20261028-002 until ...; Release it with POST /lab/runs/run-20261028-002/stop ..., or wait for the TTL to expire` |
| `lab_env_lock` | 单行 `env:write-run / run-20261028-002 / ttl 1800s` |
| `POST run-002/stop` | 200，afterSummary `{"runStatus":"COMPLETED","lockReleased":true}`，锁行删除 |
| 再 `POST run-003/start` | 200，afterSummary 含 `lockKey/lockExpiresAt/runStatus=RUNNING` |

### E.3 幂等键（真机 PASS）

| 场景 | 结果 |
|---|---|
| `freeze app-3`（未加 `jl-` 前缀）+ `Idempotency-Key: idem-drill-1` | 404/40401；operation 如实记 `FAILED` + `lastError` |
| 同 key 重放 | **200**、`idempotentReplay=true`、同一 `operationId`，无第二次副作用 |
| `freeze jl-app-3` + `Idempotency-Key: idem-drill-2` | **202**（动作已生效，响应体为 action view + operationId） |
| 同 key 重放 | **200**、同 `operationId`、`status=APPLIED`、`idempotentReplay=true` |
| `lab_operation` 按 key 计数 | `idem-drill-1`、`idem-drill-2` 各 **1 行** |

### E.4 A29 超时待确认（真机 PASS）

以 `LAB_LEDGER_OPERATION_SYNC_WAIT_MS=1` 覆盖重建控制器后注入 `freeze jl-app-2`：

```
POST /chaos/instances/jl-app-2/actions → HTTP 202
  status=PENDING_CONFIRMATION，lastError="no confirmation within 1ms ... poll GET /lab/operations/op-7e05..."
GET /lab/operations/op-7e05... （2s 后轮询）→ status=APPLIED，appliedAt=04:42:54.320
```

随后不带覆盖重建回 3000ms 默认预算（容器内 `LAB_LEDGER_OPERATION_SYNC_WAIT_MS=3000`）。

### E.5 顺带复验 A21 与清理路径

超时演练期间两次重建控制器，把仍在 RUNNING 的 `run-20261028-003` 标成 `UNKNOWN`；随后
`POST run-003/stop` 走"UNKNOWN→ABORTED + 释放锁"路径（beforeSummary `runStatus=UNKNOWN`，
afterSummary `{"runStatus":"ABORTED","lockReleased":true,"unknownCleanup":true}`），`lab_env_lock` 归零。

### E.6 回归闸门（重建后）

| 闸门 | 结果 |
|---|---|
| `mvn -pl services/chaos-controller -am test`（主树复跑） | **384 / 0 fail / 0 error / 0 skip** |
| `smoke.sh` | 7/7，`SMOKE PASSED.` |
| `verify.sh` | **109 PASS / 1 WARN / 0 FAIL**（WARN 仍为 cAdvisor/WSL2） |
| `test-chaos-env-wiring.sh` | 8 chaos + 27 ledger 键全 PASS（新增 7 键自动纳入发现） |

### E.7 遗留（有意保留）

- 台账里留了三笔演练样本：`run-20261028-001`（UNKNOWN，B1-01 的 A21）、`-002`（COMPLETED，A19 holder）、
  `-003`（ABORTED，A29/超时期间的恢复路径），供 B1-05 台账页实样。
- chaos 动作的 TTL 自动撤销**不会**把 operation 从 APPLIED 改为 RESTORED（登记在交接"边界"，归 B2-04）；
  `guide/traffic` 尚未写 operation 行。
- 杀主写入窗口的真实时序仍未演练（同 B1-01 遗留）。
