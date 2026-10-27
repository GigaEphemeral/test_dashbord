> 步骤交接 · 单线程生命周期状态机（§9.2 NEW→…→TERMINATED，真实 JVM 状态 + freeze 快照接缝）
> 日期：2026-10-27
> 分支 / HEAD：worktree 分支 `worktree-agent-general-purpose-d6443679`（基线 `52de4b2`，`feature/ha-lab`）/ 本次提交 `feat: add single-thread lifecycle demo with real JVM states`（`86a65a0`）、`test: pin the lifecycle demo to real JVM states and clean teardown`（`3141e51`）、`docs: record the single-thread lifecycle handover (step-34)`
> 关联任务：#33 的**状态机部分**（设计 9.2）；下游仍是 #33 的快照捕获（jcmd）与 #34 的线程操作页 / D6
> 测试 / verify：`cd code && JAVA_HOME=jdk-21.0.12.1 mvn -pl services/seckill-app-jdk21 -am test` → **291 tests, 0 failures / 0 errors / 0 skipped / BUILD SUCCESS**（本步新增 15：`LifecycleDemoServiceTest` 9、`LifecycleDemoPropertiesValidationTest` 6）；既有线程实验室套件 `ThreadFaultServiceTest`(8)、`FaultActionTest`(9)、`HeapLeakAllocatorTest`(5)、`LabThreadPropertiesValidationTest`(7)、`ThreadLabMetricsTest`(2)、`ThreadStateFingerprintTest`(3) **全绿**。未执行任何 Docker / compose / 实栈调用（共享栈正被其他任务使用）。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

# 当前进展

- 落地设计 §9.2 的**单线程六态状态机**：一条专用演示线程真实经历 `NEW → RUNNABLE → BLOCKED → WAITING → TIMED_WAITING → TERMINATED`，对外暴露 `start / step / freeze / resume / reset`（`/lab/thread/lifecycle/**`，沿用 `Result<T>` + `BizException` + `X-Lab-Token`）。
- 关键约束：**不改动** `LifecycleState` 与 `thread_lab_lifecycle_state_code`（故障 action 的 0/1/2），新状态机使用独立命名 `DemoState` + 新指标 `thread_lab_demo_state_code`（0–5）。命名决定见「设计逻辑」。
- 对外上报的 `state` / `stateCode` 一律来自演示线程的**真实 `Thread.getState()`**，不是记账枚举；`machineStage` 只作为推进阶段的辅助字段单列。
- `freeze` = 取消自动推进 + 立即通过 `LifecycleSnapshotSink` 接缝抓一次快照（本步实现为日志占位，**不含 jcmd**），不冻结 JVM；`resume` 从冻结的那一步继续。
- 全部改动在 worktree 内，未合并、未推送；共享栈与主工作树未改动（主树 `git status` 干净）。

# 完成功能项

## 1. 端点（`lab/api/LabThreadController`，`/lab/**` 由既有 `LabTokenFilter` 保护）

- `GET  /lab/thread/lifecycle` → `Result<LifecycleDemoView>`：轮询用，永不阻塞在 reset 的 join 之后。
- `POST /lab/thread/lifecycle/start` → 从 NEW 建线程并启动（进入 RUNNABLE），同时 arm 自动推进。
- `POST /lab/thread/lifecycle/step` → 立即推进一格并重新 arm 自动推进。
- `POST /lab/thread/lifecycle/freeze` → 取消自动推进、置 frozen、**立即**调用快照接缝一次。
- `POST /lab/thread/lifecycle/resume` → 解冻并从冻结的那一格重新 arm。
- `POST /lab/thread/lifecycle/reset` → 中断并 join 演示线程与监视器 helper，回到 NEW。
- 视图 `LifecycleDemoView{enabled, started, frozen, state, stateCode, step, maxSteps, machineStage, threadName, threadId, alive}`；`state/stateCode` 是真实 JVM 态，`machineStage` 是推进阶段（监视器释放后会出现“真实 RUNNABLE / 阶段仍 BLOCKED”的合法差值）。

## 2. 状态机与真实状态（`lab/thread/LifecycleDemoService`）

- 演示线程（daemon，名字 `app.lab.thread.lifecycle.thread-name`）在 stage 间用**自旋**等待推进许可：自旋期间真实态就是 `RUNNABLE`（故意不用 park/sleep，否则会报 WAITING/TIMED_WAITING，教错状态）。
- `BLOCKED`：helper 线程（`monitor-thread-name`）先进入并持有一把私有监视器 `monitor-hold-ms`，演示线程随后进入同一把监视器的 `synchronized` → 真实 `BLOCKED`；helper 释放后自然回到 RUNNABLE。
- `WAITING`：`Object.wait()` 无超时；`step` 用同一把 `waitMonitor` 置许可并 `notifyAll`。WAITING 阶段在持有 `waitMonitor` 时发布，因此推进命令只能在演示线程真正进入 `wait()` 之后落地，不存在丢通知/跳过 WAITING 的竞态。
- `TIMED_WAITING`：`Thread.sleep(timed-wait-ms)`；睡眠结束后在最终 gate 等待下一步。
- `TERMINATED`：`run()` 返回；线程对象保留到 `reset`，因此 `GET` 可继续看到 5，`alive=false`。
- 步骤语义：`start` 从 NEW→RUNNABLE；随后 4 次推进 `RUNNABLE→BLOCKED→WAITING→TIMED_WAITING→TERMINATED`。`step` 在“上一格尚未被线程消费”时返回 40901，不做静默丢弃。

## 3. 快照接缝（`LifecycleSnapshotSink`）

- `LifecycleSnapshotSink#capture(LifecycleSnapshot)`，快照记录 `{threadId, threadName, state, stateCode, step, capturedAtEpochMs, reason}`（展示安全，无栈内容）。
- 本步唯一实现 `LoggingLifecycleSnapshotSink`：只打一行 INFO，**不调用 jcmd/jstack**（运行时镜像是 JRE，捕获方式与 `doc/process/snapshots/` 落盘属于快照任务）。
- `freeze` 保证每次成功调用恰好触发一次 `capture`；sink 抛异常只记 WARN，不会把 demo 卡在半冻结状态。

## 4. 指标（`lab/thread/LifecycleDemoMetrics`，未改 `ThreadLabMetrics`）

- gauge `thread_lab_demo_state_code`：真实状态 0 NEW / 1 RUNNABLE / 2 BLOCKED / 3 WAITING / 4 TIMED_WAITING / 5 TERMINATED（Grafana state timeline 直接用）。
- gauge `thread_lab_demo_frozen`（0/1）、`thread_lab_demo_step`（已完成推进数）。
- counter（全部 eager 注册，标签序列先零值）：`thread_lab_demo_transitions_total{to}`（6 个目标态）、`thread_lab_demo_steps_total{source=manual|auto}`、`thread_lab_demo_starts_total`、`thread_lab_demo_freezes_total`、`thread_lab_demo_resumes_total`、`thread_lab_demo_resets_total`、`thread_lab_demo_snapshot_requests_total`。
- 有测试钉住：demo 指标**不会**注册 `thread_lab_lifecycle_state_code`，避免与故障生命周期键冲突。

## 5. 配置（`app.lab.thread.lifecycle.*`，`application.yml` 只追加块；`LabThreadProperties.Lifecycle` + `validate()`）

- `LAB_THREAD_LIFECYCLE_ENABLED:true`、`..._THREAD_NAME:thread-lab-demo`、`..._MONITOR_THREAD_NAME:thread-lab-demo-monitor`、`..._AUTO_ADVANCE_INTERVAL_MS:2000`、`..._MONITOR_HOLD_MS:1200`、`..._MONITOR_ACQUIRE_TIMEOUT_MS:5000`、`..._TIMED_WAIT_MS:1500`、`..._MAX_STEPS:6`、`..._SHUTDOWN_JOIN_TIMEOUT_MS:3000`。
- 启动校验：所有时长/间隔 > 0；`max-steps >= 4`（否则永远到不了 TERMINATED）；`shutdown-join-timeout-ms > monitor-hold-ms`（reset 可能在监视器窗口内 join）；线程名非空且互不相同。错误配置直接启动失败。
- 既有 `app.lab.thread.*`（七种故障）配置一字未改。

## 6. 装配（`lab/config/ThreadLabConfig`，独立 `@Configuration`）

- 新增 `LifecycleSnapshotSink`（Logging 占位）、`LifecycleDemoMetrics`、`LifecycleDemoService` 三个 bean；自动推进复用故障实验室的 daemon `FaultTtlScheduler`（上下文关闭即 shutdown）。metrics 在 service 构造后 `bind`（gauge 实时读活线程，同 `ThreadLabMetrics` 的“gauge 读活对象”风格）。

## 7. 测试（Docker-free）

- `LifecycleDemoServiceTest`（9）：NEW 起始与 start 后真实 RUNNABLE；逐步验证 BLOCKED(2)/WAITING(3)/TIMED_WAITING(4)/TERMINATED(5) 的真实 `Thread.getState()`；freeze 停推进（pending 计时器归零、休眠 250ms 状态不变、`step` 被 40901 拒绝）且 sink 恰好一次；resume 从冻结步（0→1）继续；reset 分别在 BLOCKED（监视器窗口）与 WAITING 中拆线程并回 NEW，断言线程 id 与线程名均消失；自动推进经 `ManualTtlScheduler` 走完整机；非法转换全为 40901；disabled 全为 40001；gauge/counter 断言。
- `LifecycleDemoPropertiesValidationTest`（6）：默认值可启动 + 五类非法配置被拒。
- 测试支持：`TestLabThreadProperties.defaults()` 补齐 lifecycle 段（monitor-hold 300ms、join 2000ms、timed-wait 200ms、auto 100ms），沿用 M5-1 的 `ManualTtlScheduler` / `liveThreadIds()`。

# 设计逻辑

- **命名决定（必须写进交接）**：设计 §9.2 原文写的是“暴露 `thread_lab_lifecycle_state_code`（0–5）”，但该指标名在 M5-1 已被故障 action 的 ACTIVE/PAUSED/STOPPED 占用，且 M5-1 评审明确要求保留该键给控制台。本步因此新增 `DemoState` / `LifecycleDemoService` / `thread_lab_demo_state_code`，`LifecycleState` 与 `thread_lab_lifecycle_state_code` **逐字节未改**；两个生命周期语义分开，D6 需要两条时间轴而不是复用一条。
- **真实状态优先**：所有对外可见的 `state` 与 gauge 都调 `Thread.getState()`；`machineStage` 只是“下一步推进从哪里继续”的记账，允许在监视器释放后与真实态短暂不一致（这本身是教学点：BLOCKED 只在等锁时成立）。
- **确定性靠注入**：自动推进走 `FaultTtlScheduler`（测试 `ManualTtlScheduler` 显式 fire），快照时间走 `Clock`（测试 `MutableClock`）；BLOCKED/WAITING/TIMED_WAITING 都是真实窗口，测试用“5 秒预算内必然出现”的采样而不是定长 sleep 断言。
- **freeze 不冻 JVM**：只取消计时器 + 置标志 + 抓一次真实状态；demo 线程仍按当前 stage 运行（例如 TIMED_WAITING 的 sleep 会自然结束，然后停在 RUNNABLE gate）。因此“冻结后状态绝对不变”在 TIMED_WAITING 上不成立，页面动画应以 `state` 为准，`frozen=true` 表示“不再推进 stage”。
- **reset 不死锁**：命令锁只被 HTTP/定时器线程持有，演示线程从不取命令锁（只读 volatile / 原子量），所以 reset 可以持锁 join 演示线程；计时器句柄用独立的 `timerLock`（叶子锁），避免 demo 的 finally 与 reset 交叉等待。所有线程 daemon，join 预算 `shutdown-join-timeout-ms` 大于监视器 hold。
- **自动推进“跟着线程走”**：一个 tick 若发现上一格许可尚未被消费就丢弃并重排下一次，不会把 step 计数推得比真实推进快。

# 存在缺陷项&TODO

- **快照捕获未实现**：`LifecycleSnapshotSink` 只有日志占位；`jcmd Thread.print -l` / `GC.heap_info` / 直方图、JRE 镜像下的替代方案、`doc/process/snapshots/` 落盘、Grafana annotation 全部未做（属 #33 快照任务）。
- **未做实栈验收**：本步禁 Docker/compose，未在运行实例上调用过 `/lab/thread/lifecycle/**`，未在 Grafana 看过 `thread_lab_demo_*`，也未验证真实容器里 freeze 快照的时序。上下文级证据仅来自会启动 `SeckillApplication` 的既有 IT（新 bean 与 `app.lab.thread.lifecycle.*` 绑定能实例化）。
- **RUNNABLE 自旋占一个核**：为诚实展示 RUNNABLE（park/sleep 会变成 WAITING/TIMED_WAITING），gate 用 `onSpinWait + yield`；默认自动推进 2s 即离开，但若有人 freeze 在 RUNNABLE 后长期不管，会持续占一个 CPU 核。这是教学取舍，不是泄漏（reset 可回收）。
- **`step` 在“上一格未消费”时拒绝**：快速连点会得到 40901（“still in flight”），而不是排队；页面应按 `state` 变化后再发下一步。
- **`max-steps` 保护基本不可达**：机器自然 4 步到 TERMINATED，`max-steps>=4` 的校验主要防“配置把终态挡在门外”；没有专门的单元测试触发该分支（依赖 `step >= maxSteps` 的防御式检查）。
- **自动推进会在 TERMINATED 前多 arm 一次**：最后一步被接受时 stage 还是 TIMED_WAITING，会排一个 tick；线程 terminated 后该 tick 空转一次即返回，无泄漏，但会多一条 pending（测试里 `reset` 会取消）。
- **未加控制器层测试**：与 M5-1 一致（`LabThreadController` 无独立 HTTP 测试），错误码契约由 service 测试覆盖；JSON 信封/鉴权沿用既有路径。
- **全局急停不覆盖 lifecycle**：`POST /lab/thread/stop-all` 仍只清七种故障，不会 reset 演示线程；演示线程靠自动推进自然 TERMINATED 或显式 reset。是否需要把 lifecycle 纳入急停（并改响应结构）留待 #34/#35 决定。
- **`enabled=false` 时 reset 仍可用**：这是刻意的“拆除不受开关限制”，但意味着 disabled 状态下若存在历史线程仍可被清理；当前 enabled 是启动期静态配置，不会出现“运行中关掉”的场景。

# 待定事项和注意事项

- **交给 #33 快照任务**：实现真正的 `LifecycleSnapshotSink`（JRE 镜像里没有 jcmd，需要在镜像里加 JDK 工具或改走 `ThreadMXBean` + `getAllStackTraces()` 的等价方案——这是快照任务的决策），把 `LifecycleSnapshot` 落盘到 `doc/process/snapshots/<时间戳>/`，并在 Grafana 打 annotation；`freeze` 已保证“暂停推进 + 立即抓一次”，无需改状态机。
- **交给 #34 页面 / D6**：页面消费 `GET /lab/thread/lifecycle` 的 `state/stateCode` 做状态机动画（注意 `machineStage` 与 `state` 的差值语义）；`thread_lab_demo_state_code` 做 state timeline，`thread_lab_demo_transitions_total{to}` 可做阶段计数；`max-steps`、`auto-advance-interval-ms` 已可调。
- **实栈注意**：`/lab/thread/lifecycle/start` 会创建一条 daemon 演示线程并在 RUNNABLE gate 自旋，演示完务必 `reset`；共享实例上不要长期 freeze 在 RUNNABLE。
- **命名不可回退**：不要为了“贴合设计原文”把 demo 的 gauge 改回 `thread_lab_lifecycle_state_code`；那会破坏 #34 控制台对故障生命周期的键契约（M5-1 评审明确保留）。
- 配置块只追加在 `application.yml` 的 `app.lab.thread.lifecycle.*`；未改 cache / datasource / chaos-controller / `seckill-app-jdk8` / `code/lab/**`。

# 附录

- 验证命令：`cd "/d/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-d6443679/code" && JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1" mvn -pl services/seckill-app-jdk21 -am test` → `Tests run: 291, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`。
- 关键文件：`lab/thread/{DemoState,LifecycleSnapshot,LifecycleSnapshotSink,LoggingLifecycleSnapshotSink,LifecycleDemoMetrics,LifecycleDemoService}.java`、`lab/api/{LifecycleDemoView,LabThreadController}.java`、`lab/config/ThreadLabConfig.java`、`LabThreadProperties.java`（新增 `Lifecycle`）、`application.yml`；测试 `lab/thread/{LifecycleDemoServiceTest,LifecycleDemoPropertiesValidationTest,TestLabThreadProperties}.java`。
