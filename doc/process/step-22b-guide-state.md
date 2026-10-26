> 步骤交接 · M2-4b 实验向导状态机与 TTL 冻结
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / 本次提交 `feat: add lab guide state machine`（父提交 `0792ad6`）
> 关联任务：#22（M2-4，本步 22b）
> 测试 / verify：全 reactor **228 tests, 0 failures**（chaos-controller 75，原 39；新增 `GuideScenarioLoaderTest` 12、`GuideStateMachineTest` 9、`GuideControllerTest` 11、`ChaosActionServiceTest` +4）；`verify.sh` **97 PASS / 1 WARN**（WARN 仍是 cAdvisor/WSL2）
> 运行状态：控制器镜像已重建并 `up.sh`；16 个 lab 容器运行中；app-3 验收后恢复 healthy，`/chaos/actions` 为空、guide 为 IDLE；MySQL/Redis/RabbitMQ 与 app-1/2 未触碰

# 当前进展

- Task 22b 完成：chaos-controller 新增 scenario v1 的 Java 加载/校验器、`/lab/guide/**` 状态机 API（start/step/pause/resume/reset/state/scenarios），以及 `ChaosActionService` 的 pause/resume TTL 冻结语义。
- 场景目录 `code/lab/scenarios` 以只读方式挂载到控制器 `/app/scenarios`；配置 `chaos.guide.scenario-dir` / `chaos.guide.target-pattern` 与 compose env 全部外置。
- `start` 只加载不执行；`step` 每次恰好执行一个事件并复用既有 `ChaosActionService`（token、`lab=java-learn` 白名单、TTL 上限、annotation、指标不变）；最后一步后 COMPLETED，动作失败则 FAILED 且不自动回滚。
- `pause` 取消所有 active 动作的定时回滚并记录剩余 TTL，看门狗同时停摆；`resume` 只按剩余 TTL 重新调度，不会重置为完整 TTL；`reset` 无论状态都会 stop-all 并解冻、清空向导。
- 真实冻结验收（仅 app-3）：kill TTL 15s 后 1s 内 pause，超过原到期时间 16s 仍为 `exited` 且 action 仍 active；resume 后约 14s（剩余量）自动 start，随后 healthy；Nginx 产品读全程 200；reset 后 actions 为空。
- 未修改应用 Java、k6、dashboard、`verify.sh`、compose 端口/健康检查；未提交 `.env`、token 或临时场景文件。

# 完成功能项

## 1. Java 场景加载/校验（`guide/GuideScenarioLoader`）

- 启动时按请求读取 `chaos.guide.scenario-dir` 下的 `*.json`，不缓存；无效文件在列表时跳过（WARN），start 指定该文件时返回 400 与文件名前缀原因，控制器不崩溃。
- 与 `code/lab/scenarios/README.md` / k6 `chaos-schema.js` 对齐：`schemaVersion=1`、`id` 匹配 `^[a-z0-9-]+$`、`name` 非空、`description/title/text` 存在时必须为非空字符串、`durationHintSeconds` 正数、`events` 非空且按 `atSeconds` 非降序、动作白名单五个、`(atSeconds,target)` 不重复、kill/freeze 必填正整数 TTL 且不超过 `chaos.max-ttl-seconds`、一次性动作禁止 TTL。
- 目标名必须匹配 `chaos.guide.target-pattern`（默认 `^jl-app-[0-9]+$`），并额外硬拒绝 `mysql|redis|rabbit|mongo|postgres`；id 只允许安全字符，无法路径穿越。
- 未知 id/缺失目录 → `ScenarioNotFoundException`（404）；文件内容非法 → `InvalidScenarioException`（400）；列表项含 id/name/description/stepCount/durationHintSeconds。

## 2. 向导状态机（`guide/GuideService` + `GuideController`）

- 状态 `IDLE/RUNNING/PAUSED/COMPLETED/FAILED`；`start` 仅 IDLE/COMPLETED/FAILED，`step` 仅 RUNNING，`pause` 仅 RUNNING，`resume` 仅 PAUSED，`reset` 任意；非法迁移抛 `GuideConflictException`（409）。
- `step` 执行当前事件后 `currentStep++`，最后一步置 COMPLETED；动作异常时记录 `lastError` 并置 FAILED，原异常继续上抛（400/403/404/409/503 与既有错误码一致），不自动回滚。
- `reset` 调 `stopAll`，若服务处于冻结则 `resume` 解冻，再清空 run；IDLE 时也允许。
- API（均受 `/lab/**` token 过滤）：
  - `GET /lab/guide/scenarios`
  - `GET /lab/guide/state` → scenarioId/status/currentStep/totalSteps/currentStepTitle/currentStepText/steps/activeActions/lastError
  - `POST /lab/guide/start` body `{"scenarioId":"..."}`
  - `POST /lab/guide/step|pause|resume|reset`
- 状态为控制器进程内存态，单实例部署；控制器重启后向导回到 IDLE。

## 3. TTL 冻结（`ChaosActionService` / `ChaosAction`）

- `pause()`：全局 paused 标志；逐个动作取消 `ScheduledFuture`，对 ACTIVE 动作记录 `remainingMillis = max(0, expiresAt - now)`；FAILED 动作保留可见。
- `resume()`：仅按记录的剩余毫秒重新调度，并把 `expiresAt` 移到 `now + remaining`；剩余为 0 时立即回滚，不丢动作。
- 冻结期间：TTL 任务若已在途，`autoRevert` 在 claim 前直接跳过；看门狗 sweep 直接返回，不处理过期也不重试 FAILED；`stopAll` 不受 paused 影响，始终取消并 start/SIGCONT。
- 冻结期间新产生的动作（直接调 `/chaos/**`）也不调度，按完整 TTL 冻结，resume 时补调度。
- 既有 CAS、FAILED 可见性、`chaos_active_actions`/`chaos_actions_total`/`chaos_reverts_total` 与 readiness 探测保持不变；`ChaosActionService` 增加 `Clock` 注入（5 参构造仍为 Spring 使用，6 参构造供测试）。

## 4. 配置与部署

- `ChaosProperties.Guide`：`scenario-dir` 默认 `/app/scenarios`、`target-pattern` 默认 `^jl-app-[0-9]+$`。
- `application.yml`：`CHAOS_GUIDE_SCENARIO_DIR`、`CHAOS_GUIDE_TARGET_PATTERN` 可覆盖。
- `compose.base.yml`：chaos-controller 新增 `../scenarios:/app/scenarios:ro` 与两个 env 默认值。
- 镜像重建后 `up.sh` 成功，挂载实测 `D:\000CODE\JAVA-LEARN\code\lab\scenarios -> /app/scenarios (rw=false)`。

## 5. 测试

- `GuideScenarioLoaderTest`（12）：列表/加载、错误版本、未知动作、datastore、非 lab 目标、乱序、缺 TTL、一次性动作带 TTL、TTL 上限、重复偏移、坏文件跳过、缺目录、未知/穿越 id。
- `GuideStateMachineTest`（9）：start 不执行、step→COMPLETED、失败→FAILED/lastError/不 rollback、FAILED 可重启、pause/resume、非法迁移 409、reset 清理/解冻、activeActions 透出、未知场景 404。
- `ChaosActionServiceTest` 新增 4：pause 冻结（含在途 TTL 与看门狗）、resume 仅剩余 TTL、冻结期间新动作、paused 下 stop-all。使用 `MutableClock` + mock `ScheduledExecutorService` 精确断言延迟 30s→20s。
- `GuideControllerTest`（11，MockMvc + 真实 TokenFilter）：无 token 401 且不回显 token、scenarios/state/start/step/pause/resume/reset 200、409/404/400、坏 body 400。

# 设计逻辑

- **状态机薄、执行复用**：向导只负责推进与状态迁移，实际动作仍走 `ChaosActionService`，因此 token、白名单、TTL 上限、annotation、指标和 CAS 回滚行为没有第二套实现。
- **单步推进**：不引入自动定时器；k6 时间线仍是自动化路径，UI（Task 23）负责调用 `step`。`atSeconds` 仅作场景元数据/展示，手动向导不按墙钟自动触发。
- **剩余 TTL 语义**：冻结时把剩余时间保存到动作对象，resume 用 `now + remaining` 重建 `expiresAt`。这样既不会因暂停延长成完整 TTL，也不会因过期丢失回滚；看门狗在解冻后仍可兜底。
- **全局冻结而非仅向导动作**：pause 作用于 `active` 全量，直接 `/chaos` 创建的动作也会被冻结，符合“所有 active 动作”的验收要求；`stop-all`/reset 故意不受冻结限制。
- **请求时加载**：场景文件很小，按请求读取可让运维改 JSON 后无需重启；mtime 缓存留作后续优化。校验器与 JS schema 字段语义对齐，但不共享代码（Java 侧独立实现）。
- **安全边界不变**：场景 target 先过模式与 datastore 黑名单，真正执行仍由网关校验 `lab=java-learn`；`kill` 只是 Docker stop，不删卷。

# 存在缺陷项&TODO

- **`quick-failover` 单事件导致“step 后 pause”无法直接复现**：该场景只有 1 个事件，执行后向导立即 COMPLETED，而 `pause` 按 brief 只允许 RUNNING → 409。冻结验收改用临时两步 app-3 场景 `guide-freeze-check.json`（kill TTL 15s + start），完成后已删除、未提交；行为、TTL 与 quick-failover 等价。若要求用 quick-failover 原样演示，需要 Task 23/场景维护方决定：增加第二个事件，或允许 COMPLETED 且仍有 active 动作时 pause（会改变状态机契约）。
- **暂停只覆盖内存态**：控制器重启会丢失 active 清单与向导状态；重启后无法 resume 原剩余 TTL，只能人工 start/SIGCONT（沿用 Task 21 限制）。
- **`expiresAt` 在暂停期间仍显示原到期时间**：动作 view 不暴露 paused 标志与剩余毫秒；暂停超过原到期时间时 UI 可能看到过去时间，但状态仍 active。操作台可展示 guide status 作为补充，后续可考虑在 `/chaos/actions` 增加 frozen/remainingMs。
- **冻结期间直接创建的动作按完整 TTL 冻结**：没有“暂停开始后已过时间”的扣减；对向导流程无影响，但属于边界语义。
- **无并发控制/多实例语义**：向导 run 是单实例内存态，未做分布式锁；当前 compose 只有 1 个控制器。
- **场景目录缺失只 WARN**：`GET /scenarios` 返回空列表，start 返回 404 “Scenario directory is not available”；没有启动期 fail-fast，便于控制器在无场景挂载时仍可用。
- **`atSeconds` 不参与手动推进**：场景中的时间偏移只用于展示/k6；如果操作台需要“按时间自动播放”，应留给 Task 23 明确设计。
- **列表每次重新解析**：文件多/大时会有重复解析开销，未做 mtime 缓存。

# 待定事项和注意事项

- Task 23 操作台直接消费 `/lab/guide/scenarios|state|start|step|pause|resume|reset`，不要在前端重复校验 schema；按钮禁用应依据 status（RUNNING 才可 step/pause，PAUSED 才可 resume）。
- 演示 `ha-app-failover` 会停 app-1、冻结 app-2；日常验收只用 `quick-failover` 或临时 app-3 场景，禁止对 MySQL/Redis/RabbitMQ 与 app-1/2 做真实故障。
- `pause` 是全局 TTL 冻结：暂停期间 TTL 不会兜底，操作者必须 resume 或 reset；若浏览器/操作台异常退出，先 `POST /lab/guide/reset`。
- token 仍从 `code/lab/docker/.env` 读取，验收命令不要把 token 写入命令行历史/文档；本文档不含 token。
- 栈保持运行；app-3 已恢复 healthy，`/chaos/actions` 为空、guide IDLE。手动恢复仍可用 `POST /lab-api/chaos/stop-all`（带 `X-Lab-Token`）。

# 附录

## 关键文件

- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/guide/`（loader、state machine、controller、views）
- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/action/ChaosActionService.java`、`ChaosAction.java`
- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/config/ChaosProperties.java`
- `code/services/chaos-controller/src/main/resources/application.yml`
- `code/lab/docker/compose.base.yml`
- 测试：`GuideScenarioLoaderTest`、`GuideStateMachineTest`、`GuideControllerTest`、`ChaosActionServiceTest`

## 复现命令

```bash
cd /d/000CODE/JAVA-LEARN
export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1"
cd code && mvn -pl services/chaos-controller -am test
cd ..
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  -f code/lab/docker/compose.dev.yml build chaos-controller
bash code/lab/scripts/up.sh --profile dev
bash code/lab/scripts/verify.sh

TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
curl -s -H "X-Lab-Token: $TOKEN" http://127.0.0.1:8080/lab-api/lab/guide/scenarios
curl -s -X POST -H "X-Lab-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"scenarioId":"quick-failover"}' http://127.0.0.1:8080/lab-api/lab/guide/start
```

## 证据

- 证据目录（gitignored）：`.superpowers/sdd/ha-lab/task-22b-evidence/`
  - `freeze-pause.log`：quick-failover 单步执行后 COMPLETED，pause 返回 409（记录单事件限制）。
  - `freeze-live.log`：临时两步场景；08:23:39 pause，原 TTL 到期 08:23:52Z，至 08:24:09 app-3 仍 `exited` 且 action active；08:24:09 resume，08:24:25 起 `running starting`，08:24:57 healthy；全程 `/api/products/1` 200；08:24:35 reset 后 actions `[]`、guide `idle`。
- `verify.sh`：97 PASS / 1 WARN（cAdvisor/WSL2）。
- 最终栈：16 个 `lab=java-learn` 容器 running/healthy。

# 审核修复（2026-10-26 独立评审）

评审结论：API/状态机/配置外置与 brief 一致，无 Critical；5 个 Important 已在隔离
worktree 修复并提交（`0e341d8` / `e7984a3` / `adadf2c`），主会话 cherry-pick 为
`5d9b649` / `53fb69a` / `39fb4d8`。

- **execute/pause 竞态**：`register` + `paused` 检查 + `schedule` 现在在同一
  `synchronized (this)` 内完成，并带同线程重入后的双检（迟到任务被取消并重新冻结），
  否则 pause 落在检查与挂接之间时 `freeze()` 取消的是尚不存在的 future，残留的
  完整 TTL 任务会在 resume 后提前回滚。回归测试
  `pauseLandingDuringScheduleLeavesNoFullTtlTaskBehind`。
- **resume 与看门狗竞态**：`resume()` 改为“仍在 paused 时先把全部 ACTIVE 到期移到
  `now + remaining`，随后才解冻并补挂任务”；同时 `remainingMillis == -1`（从未冻结）
  不再被 `Math.max(0,-1)` 当成 0 立即回滚，而是保留原到期交给自身任务/看门狗。
  回归测试 `resumeUpdatesEveryExpiryBeforeUnpausingTheWatchdog`。
- **场景加载**：`GuideScenarioLoader` 统一按目录扫描、以内嵌 `id` 为唯一键；跨文件
  重复 id 在 list 跳过并 WARN、在 load 返回 400 并列出冲突文件名；文件名恰等于请求
  id 的坏文件仍给出具体 400 原因（不掩盖成 404）。整数校验 `requireIntegerValue`
  与 k6 `Number.isInteger` 对齐，`1.0/15.0` 两端一致接受。
- **单事件 + pause 契约**：`quick-failover` 只有 1 个事件，step 后立即 COMPLETED，
  pause 按 RUNNING-only 返回 409——定级为 Important 语义缺口而非代码缺陷，
  `GuideService.pause()` Javadoc 明示 Task 23 控制台在 COMPLETED/FAILED 后必须禁用
  pause、reset 是仍持有动作时的逃生舱，并由 `pauseAndResumeAreRejectedAfterCompletion`
  锁定契约。
- 新增 8 个确定性回归测试（chaos-controller 75 → 83）。

## 与 Task 21 复审修复的合并裁决

Task 21 的 `e70aded`/`31d9c12` 与本任务修复改到同一段代码，主会话合并时按以下裁决
（两侧回归测试同时保留并通过）：

- `ChaosAction.expiresAt` 保持 `volatile`（resume 会移动到期），状态初值改为
  Task 21 的 `APPLYING`。
- `ChaosAction.freeze()` 扩展为对 `ACTIVE` **和** `APPLYING` 都记录剩余 TTL：
  Task 21 的 APPLYING 门会让“冻结期间新建的动作”停留在 APPLYING，若不记录，
  resume 只会走 `-1` 防御分支、按原到期由看门狗兜底，本任务的
  `actionCreatedWhilePausedIsFrozenUntilResume` 语义（精确剩余量）就无法成立。
- `execute()` 的到期检查与 stop-all 检查点统一使用注入的 `clock.instant()`
  （而非 `Instant.now()`），保证 `MutableClock` 测试确定性。
- `ChaosActionView` 增加 `lastError`、`StopAllResult` 增加 `pending` 后，
  `GuideStateMachineTest` 的两处构造改为 8 参 / 3 参；`GuideService.reset()` 日志
  同时打印 `pending`。
- 合并后补充的边界说明：若 stop-all 只把一个 APPLYING 动作置为待回滚，而此刻处于
  paused，则回滚等 resume 后由看门狗完成（已写入类 Javadoc 的“Documented
  limitations”）。

复验（JDK 21）：chaos-controller **98 tests**、全 reactor **256 tests, 0 failures**；
`node --test "code/lab/k6/test/*.test.mjs"` 31/31；`test-lab-safety.sh` 通过；
`test-smoke.sh` 5/5。控制器镜像重建后 `verify.sh` **97 PASS / 1 WARN**、`smoke.sh`
**7/7**。真实链路（仅 app-3）：`kill ttl=12` → 容器 exited → TTL 到点自动 start →
`/chaos/actions` 空 → healthy；`/lab/guide/start → pause → state → resume → reset`
全程 200，IDLE 下 pause 返回 409。
