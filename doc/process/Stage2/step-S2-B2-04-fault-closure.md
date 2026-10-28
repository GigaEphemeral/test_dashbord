# 步骤交接：S2-B2-04a 故障动作可观测性与 UNKNOWN 操作关闭

> 任务/步骤：Stage2 **B2-04a** —— `GET /chaos/actions` 暂停/剩余 TTL、故障动作写入 run 时间线、UNKNOWN 操作人工关闭端点
> 日期：2026-10-28
> 分支 / HEAD：`feature/stage2-workbench` 的基线 `074e557`，隔离 worktree 自检通过（`git rev-parse --show-toplevel` = 隔离 worktree，≠ 主仓库根）
> 关联任务：`stage2-开发测试与验收计划.md`「B2-04」；B1 审核 Medium「重启恢复把未确认操作标 UNKNOWN 后 `operations.settled` 永久阻断」
> 测试：chaos-controller `Tests run: 636, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（基线 **603** → 本步 +33）；k6 node `# tests 105 / # pass 105 / # fail 0`（基线 105，不变）；env wiring `PASS`（8 chaos + 85 其它键，无新键）
> 运行状态：**只跑单测与静态接线核对**；未执行任何 docker/compose 生命周期命令，未跑真机
> 范围边界：toxiproxy/线程动作统一、stop-all 扩展、UI 展示、真机 A09/A10/A12/A29 归 **B2-04b / 主会话**

# 当前进展

- `ChaosActionView` 新增 `paused` 与 `remainingMs`：列表与 POST 动作响应同一口径，均用注入的 `Clock` 计算，不再出现"暂停了但倒计时照走"的展示。
- `POST /chaos/instances/{name}/actions` 接受可选 `runId`；动作真正成功后写一行 `lab_fault_event`（`source=container`），失败动作不写"已注入"事件。台账写失败只记 warn，动作结果不变。
- `GET /lab/runs/{runId}` 详情新增 `faults` 数组（`FaultEventView`），无事件时为空数组，不是 `null`。
- 新增 `POST /lab/operations/{operationId}/close`：只允许把 `UNKNOWN` 关闭为 `RESTORED`/`FAILED`，同终态重复关闭幂等，另一终态 409；解决 B1 审核 Medium 的预检查永久阻断。
- 未改任何配置键；`note` 长度上限是校验常量（见下），未引入新依赖，未改 dashboard/compose/claims/k6。

# 完成功能项

## 1. 动作视图：`paused` / `remainingMs`

`ChaosActionView` 追加两个末尾字段（JSON 同名），保留 8 参/9 参兼容构造器与 `withOperationId`，新增 `withRuntime(paused, remainingMs)`：

| 场景 | `paused` | `remainingMs` |
|---|---|---|
| 正常倒计时（kill/freeze 带 TTL） | `false` | `max(0, expiresAt - now)` |
| guide 已暂停（`ChaosActionService.isPaused()`） | `true` | 冻结的 `remainingMillis`（暂停期间时钟前进也不缩水） |
| 暂停后恢复 | `false` | `max(0, 新 expiresAt - now)`（恢复时按剩余 TTL 重排） |
| 一击动作 start/restart/resume（`status=completed`） | 当前暂停状态 | `null`（无 TTL） |
| `REVERTED` / `FAILED` | 当前暂停状态 | `null`（倒计时已无意义；FAILED 由 watchdog 重试） |

实现位置：`ChaosAction.remainingViewMillis(Instant now, boolean paused)` + `ChaosActionService.activeActions()/viewOf()`。`activeActions()` 仍按 `expiresAt` 升序，`operationId` 的既有口径不变（服务层视图仍为 `null`，仅 POST 响应由 REST 层附加）。

## 2. 故障事件进入 run 时间线

- `ChaosActionRequest` 新增可选 `runId`（JSON `runId`；保留 `(action, ttlSeconds)` 兼容构造器，guide 调用不受影响）。`runId` 透传为 `OperationRequest.runId`，`beforeSummary` 同时记录 `runId/runStatus`。
- **runId 校验**：提供时先查 `lab_run`；不存在 → **404/40401**（与 `GET /lab/runs/{id}` 一致）；格式不合法（非 `[A-Za-z0-9][A-Za-z0-9._-]{0,63}` 或含 `..`）→ 400/40001。**不要求 run 为 RUNNING**：操作员可以先注入再启动，预检查会拦截启动。台账在此时不可达则记 warn 并放行动作（非严格契约，MySQL 故障演练优先），该次动作不写时间线。
- **事件写入**：仅当 `ChaosActionService.execute` 成功返回后写一行（APPLIED 与 RESTORED 都算一次事件）。`source="container"`、`target=<容器名>`、`event=<动作名>`、`occurred_at=clock.instant()`，`detail={ttlSeconds,status,operationId,expiresAt,runStatus}`。失败动作在 `execute` 抛出前不会走到写入点，因此不写"已注入"事件（operation 行本身记录 FAILED）。
- **失败不影响动作**：`FaultEventService.recordContainerAction` 捕获 `DataAccessException`（及 detail 序列化失败）记 warn，返回 `false`；动作视图与 HTTP 202 保持不变。
- **详情视图**：`RunDetailView` 追加 `faults: List<FaultEventView>`，`FaultEventView{operationId,source,target,event,occurredAt,detail}` 直接来自 `RunLedgerRepository.findFaultEvents`，按 `occurred_at,id` 升序；构造器把 `null` 归一为空数组。
- 幂等重放（200）不重跑 work，因此不会重复写事件；超时 202 由后台 worker 完成后写一次事件。

## 3. UNKNOWN 操作关闭端点

`POST /lab/operations/{operationId}/close`

Body：`{"status":"RESTORED"|"FAILED","note":"..."}`

| 条件 | 结果 |
|---|---|
| 当前 `UNKNOWN`，关闭为 `RESTORED` | 200；`status=RESTORED`、`restored_at=now`、`last_error=note`（note 提供时） |
| 当前 `UNKNOWN`，关闭为 `FAILED` | 200；`status=FAILED`、不写 `restored_at`、`last_error=note`（提供时） |
| 当前已是请求的终态（重复 close 同一终态） | 200，返回当前 `OperationView`，**不重复写**（首次 note/restored_at 保留） |
| 当前是另一终态，或 `APPLIED`/`RESTORED`/`FAILED`/`REQUESTED` 等非 `UNKNOWN` | 409/40905 `OPERATION_NOT_CLOSEABLE` |
| 操作不存在 | 404/40401 |
| `status` 缺失/非 `RESTORED\|FAILED` | 400/40001 |
| `note` 超过 512 字符或含控制字符（含换行/制表） | 400/40001 |
| ledger DEGRADED / 查询写失败 | 503/50302 |

- 状态大小写不敏感（trim + upper）；`note` 空白视为未提供。
- **note 落点**：`last_error`。提供 note 时覆盖；省略时用 `COALESCE` 保留恢复期写入的原因（"unconfirmed after controller restart…"），不伪造恢复结论。`note` 上限是 `OperationService.MAX_CLOSE_NOTE_LENGTH = 512` 校验常量（与 `last_error` 列预算一致），**不做配置键**，因此三处配置无需改动。
- 写路径是 `UPDATE … WHERE status='UNKNOWN'` 的 CAS；并发关闭同一终态由后到者读回幂等结果，另一终态 409，绝不覆盖首个终态。
- **不触碰 run 状态与写 run 锁**：只关闭 `lab_operation` 行。UNKNOWN run 的环境锁仍由 run stop/超时路径释放；操作员需要先关闭 run 才能解除启动阻断。
- 该端点不包 operation 契约（不改环境，只改台账判定），直接返回 `Result<OperationView>`。

## 4. 测试

| 套件 | 结果 | 新增覆盖（+33） |
|---|---|---|
| `mvn -f code/pom.xml -pl services/chaos-controller -am test` | **636 / 0 fail / 0 error / 0 skip**，BUILD SUCCESS | `ChaosActionServiceTest` +1（暂停前/后/恢复/无 TTL/POST 口径）；`ChaosActionStateMachineTest` +2（REVERTED/FAILED 为 null、冻结值优先）；`ChaosControllerTest` +4（runId 透传与事件、无 runId 不写、run 不存在 404、事件失败不影响 202）；`FaultEventServiceTest` +7（detail 形状、无 runId、写失败吞掉、校验/404/不可达/非法格式）；`OperationCloseTest` +13（RESTORED/FAILED、非 UNKNOWN 409、404、同终态幂等、异终态 409、并发两种结果、非法状态/note、note 省略保留原因、503）；`LedgerControllerTest` +5（faults 空数组与非空形状、close 的 200/409/404/400）；`OperationServiceIT` +1（真实 schema 的 CAS/幂等/COALESCE） |
| `node --test "code/lab/k6/test/*.test.mjs"` | **105 / 105 pass**，0 fail | 未改 `code/lab/k6/**`，无新增 |
| `bash code/lab/scripts/test-chaos-env-wiring.sh` | **PASS**（8 chaos + 85 其它键） | 无新配置键，结果不变 |

# 设计逻辑

- **为什么剩余 TTL 由服务算而不是记录里存**：暂停的权威值是 `ChaosAction.remainingMillis`（`freeze` 时冻结），未暂停的权威值是 `expiresAt`。视图层用注入 `Clock` 二选一，避免把展示时间写进状态机，也避免 `System.currentTimeMillis()` 让测试不可控。
- **为什么失败/已恢复返回 null 而不是 0**：`FAILED` 的倒计时已不驱动任何任务（watchdog 重试），`REVERTED` 已结束；返回 0 会被 UI 误读成"马上回滚"。
- **为什么失败动作不写注入事件**：`lab_fault_event` 是"实际发生过的故障"时间线；失败动作的 operation 行已经是 FAILED。选择"失败不写"而不是再写 `failed` 事件，保持 `event=action` 的单一语义，避免同一动作出现两条相反事件。
- **为什么事件写失败不能翻转动作结果**：MySQL 故障本身就是一等演练；故障已注入而台账不可写时，返回失败会让操作员重试第二次注入。与 B1-02 非严格 operation 契约一致：动作优先，审计降级为 warn + `GET /lab/ledger/status` 可见的 lastError。
- **为什么允许非 RUNNING run**：手工流程常常先注入故障再启动 run，预检查负责拦截"环境不干净"的启动；此处只要求 run 存在，detail 记录当时状态，便于复盘。
- **为什么 close 只碰 operation 行**：环境锁的释放规则属于 run 生命周期（stop/超时）；若 close 顺手解锁，会绕过单写者语义并可能与仍在收尾的 run 竞争。关闭操作只是"给 UNKNOWN 一个终态判定"，让预检查的 `operations.settled` 可以继续。
- **为什么同终态幂等、异终态 409**：重复调用是操作员的安全网；但把 RESTORED 改成 FAILED（或反之）会改写历史结论，必须显式拒绝。

# 存在缺陷项&TODO

### 未验证项（本步明确不做）

- **真机 close**：未在真栈上制造 UNKNOWN 操作后调用关闭端点；CAS/COALESCE 由 Testcontainers MySQL 集成测试覆盖，HTTP 由 MockMvc 覆盖。
- **真机 faults 时间线**：未实际注入带 `runId` 的故障并读取 `GET /lab/runs/{id}`；写入/读取形状由单测与 schema 集成测试覆盖。
- **UI**：`GET /chaos/actions` 的 `paused/remainingMs`、`GET /lab/runs/{id}` 的 `faults`、关闭按钮均未接线，归 B2-04b。
- **toxiproxy / 线程动作统一**：网络毒化与线程实验室动作尚未走同一 fault 事件契约；`stop-all` 未扩展为逐动作事件；归 B2-04b。
- **A09/A10/A12/A29**：故障时间线、暂停展示、UNKNOWN 关闭的验收口径与真机执行归主会话/B2-04b。

### 已知边界

- `runId` 校验时 ledger 不可达：动作照常执行但**不写**时间线（warn 可见）；此时无法区分"run 不存在"与"库不可用"，这是非严格故障路径的有意取舍。
- close 只关闭 operation，不关闭 UNKNOWN run，也不释放环境锁；操作员需另走 `POST /lab/runs/{id}/stop`。
- `GET /chaos/actions` 服务层视图的 `operationId` 仍为 `null`（B1-02 既有口径，未在本步改动）；POST 响应带 operationId。
- guide（`GuideService`）直接调用 `chaosActionService.execute`，不经过 `POST /chaos/instances/{name}/actions`，因此本步不为 guide 动作写 fault 事件。

# 待定事项和注意事项

1. **B2-04b 接线顺序建议**：先让 toxiproxy/线程动作共用 `FaultEventService`（source 分别 `network`/`thread`），再把 `stop-all` 扩展为逐动作 REVERTED 事件；最后接 UI 的 `paused/remainingMs` 与 `faults` 时间线。
2. **close 的 note 语义**：note 写入 `last_error`，会覆盖恢复期原因；需要保留恢复原因时省略 note（`COALESCE` 保留）。
3. **预检查联动**：close 成 RESTORED/FAILED 后，`operations.settled` 不再把该行视为未结；但若 run 自身仍是 UNKNOWN，`POST /lab/runs/{id}/start` 仍会被 run 状态阻断，这是预期。
4. **不要用 `remainingMs` 判断是否已回滚**：它只描述倒计时；动作是否结束看 `status`。
5. **事件 detail 的 `status` 是动作状态**（`active`/`completed`），不是 operation 状态；operation 状态在 operation 行/`GET /lab/operations/{id}`。

## 最新 commit

- `0f60c47` `feat(B2-04): expose fault liveness and close UNKNOWN operations`
- docs（本文件）单独提交，见 `git log -- doc/process/Stage2/step-S2-B2-04-fault-closure.md`


---

# 补充：B2-04b1（stop-all 覆盖 traffic / k6 / 线程实验室）

> 本步不做 UI、不跑真机；toxiproxy 与线程故障动作的 operationId 统一留给 B2-04b2。

## 1. 覆盖矩阵

| 层 | stop-all 实际动作 | 结果字段 / 取值 |
|---|---|---|
| 容器动作 + toxiproxy | `ChaosActionService.stopAll()` 原样保留：逐动作 revert（start/SIGCONT）、APPLYING 延迟、FAILED 保留；`NetworkFaultCleaner.resetAll()` | `reverted` / `failed` / `pending` / `toxiproxy`（既有语义，构造器未改） |
| background traffic | `TrafficService.stop()`（沿用 `chaos.traffic.stop-grace-seconds`） | `traffic`: `stopped` \| `not-running` \| `failed` |
| 线程实验室 | 对 `chaos.app-proxy.instances` 每个实例 `forward(instance,"POST","/lab/thread/stop-all",null,null)`；token 由代理内部携带 | `threads`: 实例 → `ok` \| `failed` |
| k6 run 容器 | `BenchRunExecutor.sweepOrphans()` 列 `lab.role=k6`；RUNNING/CREATED/PAUSED（含 RESTARTING）先 `stop(runId)`，随后所有列出的容器 `cleanup(runId)`（removed）；EXITED 只清理 | `k6Stopped` / `k6Cleaned` / `k6Failed` |

- 编排入口：`com.javalearn.lab.chaos.action.EnvironmentStopAllService`（新增 `@Service`；未改 `ChaosActionService` 构造器与既有单测）。
- `ChaosController.stopAll` 只换成调用编排服务：仍是 `operations.submitBlocking(...)`、`environment/lab/stop-all` 目标、调用方 `Idempotency-Key` 语义不变。
- `StopAllResult` 新增 5 个尾部组件；3/4/5 参旧构造器与 `withOperationId` 全部保留，旧调用点零改动。

## 2. 执行顺序（固定）

1. **容器动作 + toxiproxy**：破坏性最大，必须最先回滚；沿用 APPLYING 延迟与暂停标志。
2. **traffic**：先停掉新负载，避免线程/k6 清理期间继续加压。
3. **线程实验室**：逐实例恢复死锁/卡顿等线程故障。
4. **k6**：停止活动 run 并 remove 所有残留容器。

后三步互不依赖；某一步失败不改变后续顺序，也不阻断后续执行。

## 3. 失败语义

| 子项 | 失败判定 | 处理 |
|---|---|---|
| 容器 / toxiproxy | `ChaosActionService` 内既有 per-action catch | 保持既有；若该方法意外抛 Runtime，编排层记 `failed=1` 且 toxiproxy `failed`，继续后三步，不返回 500 |
| traffic | `ContainerNotFoundException` → `not-running`；其它 Runtime → `failed` | 只记录，继续线程与 k6 |
| 线程 | forward 抛异常或非 2xx → 该实例 `failed` | 每个实例独立 try/catch，其它实例照常收到 stop-all |
| k6 | `runId` 空/不可用、stop 或 cleanup 抛异常 → 该容器 `failed`；inventory 读取失败 → `k6Failed=1` | stop 失败后仍尝试 cleanup；单个容器最多计一次 failed；已不存在的容器不计失败 |

- 任一子项失败都不抛出：HTTP 仍为 200 + `StopAllResult`，operation 仍为 `RESTORED`；已回滚的容器不会因 traffic/k6 失败被报成整体失败。
- 幂等回放沿用 B1-02：同一 `Idempotency-Key` 在窗口内不执行 work，直接返回存储的 operation 视图；`afterSummary` 里是首次执行的 traffic/threads/k6 记录，回放不重复清理。

## 4. 结果字段（JSON）

```json
{
  "reverted": 2,
  "failed": 0,
  "pending": 1,
  "toxiproxy": {"status": "reset", "proxiesReset": 2, "proxiesTotal": 2, "errors": []},
  "traffic": "stopped",
  "threads": {"app-1": "ok", "app-2": "ok", "app-3": "failed"},
  "k6Stopped": 1,
  "k6Cleaned": 2,
  "k6Failed": 0,
  "operationId": "op-..."
}
```

- `traffic=null` 只可能来自旧构造器（该子项未评估）；HTTP 响应由编排服务生成，正常不为 null。
- `threads` 在记录构造时做防御性拷贝并只读，避免调用方修改响应状态。
- operation 的 `afterSummary` 同步写入 `traffic` / `threads` / `k6Stopped` / `k6Cleaned` / `k6Failed`，回放时可直接解释清理范围。

## 5. 测试

| 套件 | 结果 | 新增覆盖（+15） |
|---|---|---|
| `mvn -f code/pom.xml -pl services/chaos-controller -am test` | **651 / 0 fail / 0 error / 0 skip**，BUILD SUCCESS（基线 636） | `EnvironmentStopAllServiceTest` +9（四项全成功计数、traffic 抛异常仍执行其余、单实例失败/非 2xx 隔离、k6 stop/cleanup 异常计数、空 runId、inventory 失败、traffic 缺失、base 抛异常、空场景）；`StopAllResultTest` +4（3/4/5 参构造器、withOperationId 保留新字段、threads 只读）；`ChaosControllerTest` +2（响应与 afterSummary 新字段、幂等回放不重复清理） |
| `node --test "code/lab/k6/test/"*.test.mjs` | **105 / 105 pass** | 未改 k6 脚本 |
| `bash code/lab/scripts/test-chaos-env-wiring.sh` | **PASS**（8 chaos + 85 其它键） | 无新配置键，数字不变 |

## 6. 未验证项

- **真机 stop-all**：未在真栈验证 traffic 容器被停、三个实例的线程故障全部恢复、RUNNING/EXITED k6 容器被停止并 removed（设计 A12 清理恢复验收）；本步仅由 Mockito 单测覆盖编排调用。
- **UI**：控制台未展示 `traffic/threads/k6*`，一键还原按钮未接新字段；归 UI 批次。
- **B2-04b2**：toxiproxy 与线程故障动作的 operationId / fault 事件未在本步统一；本步只扩展 stop-all 的覆盖范围。
- **Docker 竞态**：inventory 与 cleanup 之间 daemon 抖动、容器在 stop 与 remove 之间消失等时序未真机演练；按 best-effort 计入 `k6Failed` 或自然消失。

## 最新 commit

- `e488c59` `feat(B2-04): extend stop-all to traffic, thread lab and k6`
- docs（本文件）单独提交，见 `git log -- doc/process/Stage2/step-S2-B2-04-fault-closure.md`

## 补充：B2-04b2（toxiproxy / 线程故障统一 operation 契约）

本段把 `ToxiproxyController` 的 6 个写端点与新增的线程故障统一入口接入 B1-02 operation 契约，并在给出 `runId` 时把成功动作写入 `lab_fault_event`。不改 UI、不跑真机。

### 1. 端点与响应形状

| 端点 | 同步结算（HTTP 202） | pending（HTTP 202） | 幂等回放（HTTP 200） |
|---|---|---|---|
| `POST /chaos/toxiproxy/{proxy}/toxics`（body 原样 `ToxicRequest`） | `Result<ToxicView>` | `Result<OperationView>`（PENDING_CONFIRMATION） | `Result<OperationView>`（`idempotentReplay=true`） |
| `DELETE /chaos/toxiproxy/{proxy}/toxics/{name}` | `Result<Void>`（路径与返回语义不变） | 同上 | 同上 |
| `POST /chaos/toxiproxy/{proxy}/reset` | `Result<ProxyResetView>` | 同上 | 同上 |
| `POST /chaos/toxiproxy/{proxy}/disable`、`/enable` | `Result<ProxyEnabledView>` | 同上 | 同上 |
| `POST /chaos/toxiproxy/reset-all` | `Result<NetworkResetReport>` | 同上 | 同上 |
| `POST /chaos/instances/{name}/thread/fault`（body 原样转发） | app 原始 JSON 响应体（`application/json`） | 同上 | 同上 |
| `POST /chaos/instances/{name}/thread/fault/{mode}/{action}` | app 原始 JSON 响应体 | 同上 | 同上 |
| `POST /chaos/instances/{name}/thread/stop-all` | app 原始 JSON 响应体 | 同上 | 同上 |

- 所有写响应都带 `X-Operation-Id`；`GET /chaos/toxiproxy/state` 保持只读、无 operation。
- pending 时只返回 operation 视图，不伪造结算结果；幂等回放返回存储的 operation 视图，不重复调用 toxiproxy / app。
- 线程入口同步结算时原样中继 app 的 `Result` 信封（HTTP 202、UTF-8 JSON），不做二次包装。
- 旧的 `/chaos/app/{instance}/lab/thread/**` 代理保留（GET 状态、lifecycle、snapshot 继续用），`AppProxyService` 白名单与行为未改。

### 2. operation 映射与状态

| 入口 | targetKind | targetName | action | 结算状态 |
|---|---|---|---|---|
| toxiproxy add / disable | `toxiproxy` | `{proxy}` | `toxiproxy-add` / `toxiproxy-disable` | APPLIED |
| toxiproxy remove / reset / enable | `toxiproxy` | `{proxy}` | `toxiproxy-remove` / `-reset` / `-enable` | RESTORED |
| toxiproxy reset-all | `toxiproxy` | `all` | `toxiproxy-reset-all` | RESTORED |
| thread `/fault`、`/fault/{mode}/{pause,resume}` | `thread` | `{instance}` | `thread-fault` | APPLIED |
| thread `/fault/{mode}/stop` | `thread` | `{instance}` | `thread-fault` | RESTORED |
| thread `/stop-all` | `thread` | `{instance}` | `thread-stop-all` | RESTORED |

- 全部为 `strictLedger=false`（故障动作在 MySQL 演练期间仍须可用），`ttlSeconds=null`（toxiproxy 无线程 TTL）。
- 线程入口的 operation 幂等键按 `action + targetKind + targetName` 复用；同一 `Idempotency-Key` 用于不同 mode 会被视为同一 `thread-fault` 请求而回放——调用方应为不同 mode 使用不同键（B2-04c UI 遵守）。

### 3. runId 与 lab_fault_event

- `runId` 是写端点上的**可选 query 参数**（不改 JSON body）。先经 `FaultEventService.validateRun`：格式非法 400/40001，run 不存在 404/40401（均在创建 operation 之前）；ledger 不可达时动作照常执行，写入阶段失败被吞掉。
- 成功（APPLIED/RESTORED）且给了 `runId` 时写一行 `lab_fault_event`，`detail` 恒定含 `operationId`、`runStatus` 与请求摘要：
  - toxiproxy：`source=toxiproxy`，`target={proxy}`（reset-all 为 `all`），`event=toxiproxy-add|remove|reset|disable|enable|reset-all`；
  - thread：`source=thread`，`target={instance}`，`event=thread-fault`（body 入口）、`thread-fault-{mode}-{action}`（路径入口）或 `thread-stop-all`。
- `reset-all` 仅在报告为 `reset` / `partial`（确有代理被恢复）时写事件；`failed` / `disabled` 只落 operation 行，事件列不谎报恢复。
- 失败动作不写事件；事件写失败（`recordEvent` 返回 false）不影响 HTTP 结果。`FaultEventService` 新增通用 `recordEvent(...)`，原 `recordContainerAction(...)` 委托到它，容器事件语义不变。

### 4. 白名单与拒绝矩阵

| 场景 | HTTP / 错误码 | 说明 |
|---|---|---|
| toxiproxy proxy 不在 `chaos.toxiproxy.proxies` | 404 / 40401 | `ToxiproxyService` 既有校验，动作未执行 |
| `chaos.toxiproxy.enabled=false` | 503 / 50301 | 动作未执行 |
| toxic 类型/参数非法、重复 toxic | 400 / 40001、409 / 40901 | 既有映射不变 |
| thread 实例不在 `chaos.app-proxy.instances` | 404 / 40401 | 与 `/chaos/app/**` 同口径，未创建 operation |
| thread mode 不在 7 个白名单 | 400 / 40001 | 未创建 operation |
| thread action 不是 `stop\|pause\|resume` | 400 / 40001 | 未创建 operation |
| app 返回非 2xx | 502 / 50201 | operation FAILED，消息含实例/状态，不含 token |
| app 不可达 / 非 JSON | 503 / 50301、502 / 50201 | operation FAILED（既有代理异常映射） |
| `runId` 非法 / 不存在 | 400 / 40001、404 / 40401 | 在 operation 创建前拒绝 |

- 线程目标只由 `chaos.app-proxy.instances` 约束、app 路径为固定常量；toxiproxy 目标只由 `chaos.toxiproxy.proxies` 约束。未新增任意 URL/target，`lab=java-learn` 的 token 边界未改。
- 本步未新增配置键，env wiring 数字不变。

### 5. 与 B2-04c 的边界

- 本步只提供后端契约（端点、头、operation、事件、拒绝矩阵）与单测；**不接 UI**、不改 dashboard/claims/compose/k6 脚本。
- B2-04c/主会话负责：控制台改用新线程入口与 `X-Operation-Id`、pending 轮询 `GET /lab/operations/{operationId}`、以及真机验收（A09/A10/A12/A29）。

### 6. 测试

| 套件 | 结果 | 变化 |
|---|---|---|
| `mvn -f code/pom.xml -pl services/chaos-controller -am test` | **676 / 0 fail / 0 error / 0 skip**，BUILD SUCCESS（基线 651） | `ToxiproxyControllerTest` 12→21（每个写端点一次 operation + 原视图 + 头、回放、pending、runId/404/400、事件失败、reset-all 事件条件、错误映射）；新增 `ThreadFaultControllerTest` 16（三种入口转发方法/路径/body、实例/mode/action 拒绝、事件、非 2xx→FAILED 语义、pending/回放、runId） |
| `node --test "code/lab/k6/test/"*.test.mjs` | **105 / 105 pass** | 未改 k6 脚本 |
| `bash code/lab/scripts/test-chaos-env-wiring.sh` | **PASS**（8 chaos + 85 其它键） | 无新配置键 |

### 7. 未验证项

- **真机 A09/A10/A12/A29**：toxiproxy add/remove/reset/enable-disable/reset-all 与线程 fault/stop/pause/resume/stop-all 在真栈上的 operationId 返回、事件落库、reset-all 报告未跑；本步仅 Mockito + MockMvc 单测。
- **真机 pending**：`sync-wait-ms` 超时后 202 OperationView 与后台结算需在真 MySQL 上复核；单测以 stub 覆盖分支。
- **真实幂等窗口**：同一键跨进程/并发去重依赖 `lab_operation` 唯一索引，本步未做并发真机验证。
- **UI**：控制台尚未消费新线程入口、`X-Operation-Id` 或 pending 视图；归 B2-04c。

### 最新 commit

- `44e8fa9` `feat(B2-04): unify toxiproxy and thread faults under the operation contract`
- docs（本文件）单独提交，见 `git log -- doc/process/Stage2/step-S2-B2-04-fault-closure.md`
