> 步骤交接 · M5-1 故障线程实验室（七种注入模式 + TTL/manual/pause/resume/stop-all 生命周期）
> 日期：2026-10-27
> 分支 / HEAD：worktree 分支 `worktree-agent-general-purpose-7697f0ee`（基线 `de2602e`，`feature/ha-lab`）/ 本次提交 `feat: add injectable faulty-thread lab with TTL lifecycle`、`test: cover thread-lab lifecycle, cleanup and heap cap`、`docs: record Task M5-1 thread lab handover (step-32)`
> 关联任务：#32（M5-1，设计 9.1）；下游 **#33 生命周期与快照**、**#34 线程操作页与 D6**
> 测试 / verify：`mvn -pl services/seckill-app-jdk21 -am test`（JAVA_HOME=jdk-21.0.12.1）**238 tests, 0 failures / 0 errors / BUILD SUCCESS**（本次新增 23：`ThreadFaultServiceTest` 8、`FaultActionTest` 6、`HeapLeakAllocatorTest` 4、`LabThreadPropertiesValidationTest` 5）；`verify.sh` / `smoke.sh` / console-smoke **本步未复跑**（禁止在共享实栈上执行 Docker/compose/验收命令），既有完整上下文 IT（`OrderApplicationServiceIT` 会启动 `SeckillApplication`，会连带装配 `ThreadLabConfig` 全部新 bean）随 reactor 通过，证明新 bean 与 `app.lab.thread.*` 绑定在真实上下文里可实例化
> 运行状态：共享栈保持原样，未启停任何容器；本步**没有**在实栈上调用过 `/lab/thread/**`，实栈验收（真实指标指纹、Grafana D6、场景 JSON、chaos-controller 广播）留待后续统一进行
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- M5-1 落地：`/lab/thread/**` 一组实验端点，可在运行时注入设计 §9.1 的**七种故障线程**——`deadlock` / `stuck` / `cpu-burn` / `heap-leak` / `oom` / `thread-leak` / `starvation`，每个模式按文档指纹用可控、可完全清理的方式实现，并带 TTL 生命周期（auto 默认 120s 自动回退、manual 保持、pause 冻结剩余、resume 只重排剩余、stop-all 全清）。
- 挂进既有 `/lab/**`（`LabTokenFilter` 以 `X-Lab-Token` 保护），沿用 `Result<T>` 信封、`BizException` + 错误码、properties 类 + `@PostConstruct validate()` 的外部化规约；新增指标族 `thread_lab_*`。
- 工作全部在 worktree `agent-general-purpose-7697f0ee`，未合并、未推送。

# 完成功能项

## 1. 端点（`lab/api/LabThreadController`，`/lab/**` 由既有 `LabTokenFilter` 保护，不新增鉴权路径）

- `POST /lab/thread/fault`，body `FaultStartRequest{mode, hold?, ttlSeconds?, virtual?}`（除 mode 外全可空）→ `Result<ThreadActionView>`。mode 精确匹配、未知 → `40001`；hold 缺省 `auto`，未知 → `40001`；ttlSeconds 仅对 auto 生效且必须 > 0。
- `POST /lab/thread/fault/{mode}/stop`、`/pause`、`/resume` → `Result<ThreadActionView>`；{mode} 无对应活动故障 → `40401`。
- `POST /lab/thread/stop-all` → `Result<List<ThreadActionView>>`（每个已停故障的最终视图）。
- `GET /lab/thread/state` → `Result<List<ThreadActionView>>`（按 mode 稳定排序的当前活动故障）。
- `ThreadActionView{mode, hold, state, startedAtEpochMs, remainingTtlMillis, threadIds}` 为**展示安全**负载（#34 直接消费）：不含配置密钥、不含栈内容；`remainingTtlMillis` 对 manual 恒为 `-1`（永不自动回退），对 STOPPED 为 `0`。

## 2. 生命周期契约（`lab/thread/ThreadFaultService` + `FaultAction`，全部有确定性测试）

- **auto TTL 自动回退**：默认 120s（`LAB_THREAD_DEFAULT_TTL_SECONDS`），到期由 `FaultTtlScheduler` 触发 `revert(reason=ttl)`——取消计时、置 `stopped`、中断并 join 所有参与线程、`drain` 堆泄漏、`shutdownNow` 饥饿池，指标 `thread_lab_reverted_total{mode,reason}`。测试用注入 `Clock` + `ManualTtlScheduler` 显式 fire，确定性且瞬时。
- **manual 保持**：arm 阶段不注册任何计时器（`scheduler` 里 0 条 pending），永不自动回退，只能 stop/stop-all 显式停。
- **pause 冻结剩余**：取消已 arm 的计时器、把 `deadline - now` 存为冻结余额（期间时间流逝不吃预算），状态 `PAUSED`。
- **resume 只重排剩余**：以冻结余额重新 arm，`deadline = now + 余额`，状态回 `ACTIVE`；余额 ≤ 0 时以 0 重排（下一 tick 即回退）。
- **stop-all**：清空注册表并逐个彻底拆除。
- **并发语义**：**同一 mode 二次注入被拒**（`40901 CONFLICT`，选择"拒绝"而非幂等，已测）；**不同 mode 可并发共存**（3 种同活的 stop-all 清理用例证明）；8 路并发抢注同一 mode 恰好 1 成功 + 7 冲突（`synchronized(lock)` 内 `containsKey` 前置校验 + 身份移除 `active.remove(mode, action)` 保证回退与重启不串台）。回退/stop 与 TTL 触发之间的竞态由 `FaultAction` 的 `tornDown` CAS + `synchronized` 收敛，重复调用只生效一次。

## 3. 七种模式实现与指标指纹（`thread_lab_*`，`lab/thread/FaultAction#startThreads`）

- `deadlock`（2 线程反序抢两把监视器，锁内只持一把、取下一把前先释放）→ 制造真实 `BLOCKED` 竞争窗口；**有意不做"永久互锁"**（不可中断 = 无法清理），取舍见"存在缺陷项"。指纹：`thread_lab_participant_threads{mode=deadlock}` + 真线程态 `blocked`。
- `stuck`（N 线程 `await` 一把永不 countDown 的 latch，带超时轮询以便中断）→ `waiting`/`timed_waiting`。
- `cpu-burn`（M 线程自旋 `while(!stopped && !interrupted)`）→ `runnable` + `process_cpu_usage`。
- `heap-leak`（1 线程按 `chunk/interval` 受控灌 `HeapLeakAllocator`，**硬封顶** `capPercent`（默认 80% max）——分配前判 `used+chunk ≤ cap*max`，到顶即停增长并空闲）→ `thread_lab_heap_leaked_bytes` 阶梯上升后封顶；stop 时 `drain()` 清空强引用，回收即可。
- `oom`（复用无封顶分配器）→ **`throwaway-instance=false` 时一律拒绝**（`40001`），只有显式标记的小堆一次性实例才允许，共享 app 无法自杀。
- `thread-leak`（控制器线程按 `spawn-count/interval` 滴灌 daemon 线程，封顶 `max-live-threads`，worker 存活至 stop）→ `thread_lab_participant_threads{mode=thread-leak}` 持续上升；**JDK21 虚拟线程对照**：`virtual`（配置默认 + 每请求可覆盖）时改用 `Thread.ofVirtual()`，表现为堆增长而非平台线程数增长。
- `starvation`（`ThreadPoolExecutor(pool-size,queue-capacity)` + `submit-count` 个阻塞任务，队列满即停提交）→ `thread_lab_pool_active{mode=starvation}` 饱和到 max、`thread_lab_pool_queue_size` 涨满、接口侧超时。

## 4. 指标族（`lab/thread/ThreadLabMetrics`，沿用模块 eager 注册风格，所有 mode 标签序列零值先行）

- `thread_lab_injected_total{mode}`、`thread_lab_reverted_total{mode,reason=ttl|manual|stop-all}`（counter）；
- `thread_lab_fault_active{mode}`(0/1)、`thread_lab_lifecycle_state_code{mode}`(0停/1active/2paused)、`thread_lab_participant_threads{mode}`、`thread_lab_heap_leaked_bytes{mode}`、`thread_lab_pool_active{mode}`、`thread_lab_pool_queue_size{mode}`（gauge，实时读当前活动 `FaultAction`，回退即回零）。

## 5. 配置（`app.lab.thread.*`，全部 `${ENV:default}`、无 Java 侧默认值、`LabThreadProperties.validate()` 启动即校验）

- 全局：`LAB_THREAD_DEFAULT_TTL_SECONDS:120`、`LAB_THREAD_SHUTDOWN_JOIN_TIMEOUT_MS:2000`（每线程 join 预算）。
- oom：`LAB_THREAD_OOM_THROWAWAY:false`（安全门）、`LAB_THREAD_OOM_CHUNK_BYTES:8388608`、`LAB_THREAD_OOM_INTERVAL_MS:50`。
- deadlock：`..._DEADLOCK_THREADS:2`、`..._DEADLOCK_HOLD_MS:50`；stuck：`..._STUCK_THREADS:4`、`..._STUCK_POLL_MS:200`；cpu-burn：`..._CPU_BURN_THREADS:2`、`..._CPU_BURN_YIELD_EVERY_LOOPS:100000`。
- heap-leak：`..._HEAP_LEAK_CHUNK_BYTES:1048576`、`..._HEAP_LEAK_INTERVAL_MS:200`、`..._HEAP_LEAK_CAP_PERCENT:80`（validate 约束 (0,100]）。
- thread-leak：`..._THREAD_LEAK_MAX_LIVE:200`、`..._SPAWN_COUNT:5`、`..._SPAWN_INTERVAL_MS:50`、`..._HOLD_MS:60000`、`..._VIRTUAL:false`。
- starvation：`..._STARVATION_POOL_SIZE:2`、`..._QUEUE_CAPACITY:50`、`..._SUBMIT_COUNT:100`、`..._TASK_BLOCK_MS:30000`。
- 校验：ttl/pool-size/chunk/interval 等正数、cap-percent ∈ (0,100]、负 interval 拒绝——错误配置变启动异常而非静默。

## 6. 装配（`lab/config/ThreadLabConfig`，独立 `@Configuration`，不动 `LabConfig`/M4-1 缓存类）

- `@EnableConfigurationProperties(LabThreadProperties.class)` + `FaultTtlScheduler`(`ExecutorFaultTtlScheduler`，单 daemon 线程、`AutoCloseable` 随上下文 close) + `ThreadLabMetrics`(用 actuator 自动配置的 `MeterRegistry`) + `ThreadFaultService`(`Clock.systemUTC()`)。`application.yml` 只做**追加式**本地新增块，减小合并摩擦。

# 设计逻辑

- **可清理性优先于"完美故障"**：所有参与线程 daemon、命名 `thread-lab-<mode>-*`、循环体频繁检查 `volatile stopped` 并尊重中断；`stop()` 是唯一收口（`tornDown` CAS 幂等），先停计时→置标志→中断→join（有界超时）。这是把"无主题超出其动作而存活 / stop 后无内存残留"钉成验收条件的结构保证，也是 deadlock 采用"可耗尽竞争"而非永久互锁的原因。
- **确定性测试靠注入**：`FaultTtlScheduler` 与 `Clock` 是接口/可替换类型，生产各自用真实定时器/系统时钟，测试用 `ManualTtlScheduler`（捕获 delay、显式 fire/cancel）与 `MutableClock`（钉死并推进时间），于是 TTL 回退、pause 冻结、resume 只补剩余都是瞬时、无 sleep、可断言 delay 数值。
- **堆封顶做成纯函数式分配器**：`HeapLeakAllocator` 把"分配前判定投影用量是否越界"与真实 `Runtime` 探针解耦（`HeapProbe` 可注入），故"永不超过 cap""到顶即停""drain 归零"可在单测里用脚本化堆精确验证，无需等真 GC；生产封顶即"分配前"检查，绝不过冲。
- **oom 用实例级开关而非运行时判断**：`throwaway-instance` 是配置，不是请求能翻的——共享 app 默认 `false`，`POST {mode:"oom"}` 在 `start()` 的加锁前就 `40001` 拒绝，杜绝把共享 JVM 打挂；真正会自杀的分支只在被显式标记的小堆一次性容器上跑，且不在单测里执行（见缺陷项）。
- **同 mode 拒绝、异 mode 共存**：注册表 `Map<FaultMode,FaultAction>` 一 mode 一 action，配合身份移除回调，保证 TTL 自然回退后该 mode 可再次注入、且回退不会误删刚重启的同 mode。
- **指标零值先行 + gauge 读活对象**：仿 `SeckillMetrics` 预注册全部 mode 标签序列，gauge 从"当前活动 action"实时取值，回退后 `active.remove` 使其回落 0，D6（#34）绑标签集而非观测到的流量。

# 存在缺陷项&TODO

- **未做实栈验收**：`/lab/thread/**` 只在单测层证明；未在运行栈上真注入过、未在 Grafana 看过 `thread_lab_*`、未核对 `blocked`/`waiting`/`process_cpu_usage` 真实指纹、未跑 verify/smoke（本任务禁 Docker/compose/验收命令）。后续需在一次性实例上逐模式打流量核对。
- **deadlock 是"竞争式 BLOCKED"非"永久互锁"**：为守住"可清理"，两线程各只持一把监视器、取第二把前先释放，故 thread dump **不保证**稳定出现 `Found one Java-level deadlock`（真死锁不可中断、会永久泄漏线程，违背验收）。D6/排查演示若要"dump 里可见的死锁"，需在**专门的一次性实例**上补一个"不可清理但会被容器重启回收"的强互锁变体（待定）。
- **oom 仅测了拒绝路径**：允许执行（真把实例 OOM 掉）有 `ExitOnOutOfMemoryError` 语义、会杀进程，属破坏性动作，**故意不在共享 JVM 的 `mvn test` 里跑**；允许分支的分配几何靠 `LAB_THREAD_OOM_*` + throwaway 门在实栈演示时验证。
- **清理靠中断，极端情况下受 `shutdown-join-timeout-ms` 约束**：若某参与线程卡在不可中断的系统调用（本 lab 未引入），join 会等满预算后放弃（best-effort）。当前所有模式均可中断，实测 0 泄漏；但这是 daemon 线程池共享同 JVM 的固有权衡。
- **cpu-burn 在测试里会短暂吃一个核**：用 `thread-count:1` + 快速 stop 控制；演示实例上多核自旋属预期症状。
- **本步不含快照/生命周期动画（§9.2/§9.3）**：`/lab/snapshot`、单线程 `NEW→…→TERMINATED` 状态机、`thread_lab_lifecycle_state_code` 的**时间轴动画**、freeze 抓快照落 `doc/process/Stage1/snapshots/` 均留给 **#33**；`thread_lab_lifecycle_state_code` 当前表达的是"故障 action 的 active/paused/stopped"，与 §9.2 的"单线程六态"不是一回事，#33 需另立生命周期状态并区分命名，避免指标语义打架。
- **chaos-controller 广播 / 全局急停（设计 §10）未接**：与 toggles/cache 同 TODO，本步为单实例；"全局急停 → 停全部故障线程"后续由 chaos-controller 代理到各实例的 `stop-all`。

# 待定事项和注意事项

- **交给 #33（生命周期与快照）**：实现 §9.2 单线程六态状态机（`start/step/freeze/resume/reset/state` + `thread_lab_lifecycle_state_code` 0–5 的正确命名）、§9.3 快照落盘（`jcmd Thread.print -l` / `GC.heap_info` / 直方图，JDK8 用 jstack/jmap）与 D6 annotation 打点；freeze=暂停推进+立即抓快照（不冻 JVM）。注意与本步 `LifecycleState`（active/paused/stopped）语义区分，别复用同一指标名。
- **交给 #34（线程操作页与 D6）**：Vue 页消费 `ThreadActionView`（mode/hold/state/startedAt/remainingTtl/threadIds 已齐备，展示安全）与 `thread_lab_*` 指标族做七模式指纹面板、生命周期时间轴、注入/停止/暂停/恢复/急停按钮；`GET /lab/thread/state` 轮询渲染剩余 TTL 倒计时。
- **实栈注意**：`heap-leak` 会把共享 JVM 堆顶到 80%（默认 cap）再回退，`thread-leak`/`starvation` 会拉起线程——演示务必在**独立一次性实例**或可控 TTL 下进行；共享栈上做验收前先约定实例隔离。
- 配置追加块只碰 `application.yml`（`app.lab.thread.*`），未改 M4-1 缓存类、chaos-controller、`code/lab/**`；`/lab/*` 的 `LabTokenFilter` 注册天然覆盖新路径，无需新 nginx/token 配置。
- `.env`/`dist`/`node_modules`/`.r2c`/`reports` 未纳入提交。

# 附录

- 验证命令：`cd code && export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1" && mvn -pl services/seckill-app-jdk21 -am test` → `Tests run: 238, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`。
- 关键文件：`lab/thread/{FaultMode,HoldMode,LifecycleState,LabThreadProperties,FaultTtlScheduler,ExecutorFaultTtlScheduler,HeapLeakAllocator,FaultAction,ThreadLabMetrics,ThreadFaultService}.java`、`lab/config/ThreadLabConfig.java`、`lab/api/{LabThreadController,FaultStartRequest,ThreadActionView}.java`、`application.yml`（`app.lab.thread.*` 追加块）；测试 `lab/thread/{ThreadFaultServiceTest,FaultActionTest,HeapLeakAllocatorTest,LabThreadPropertiesValidationTest,ThreadLabTestSupport,TestLabThreadProperties}.java`。

# 审核修复（2026-10-27 独立评审，隔离 worktree `agent-Qwen-3.8-51c8ea62`）

评审结论：端点信封/错误码（40001/40401/40901）、TTL=120s 默认、manual 保持、pause 冻结剩余、
resume 只补剩余、stop-all 全清、单 mode 冲突拒绝与并发抢注、oom 安全门均与 brief 和 §9.1 一致，
**无 Critical**（实栈验收已如实列为缺陷项，无越界声称）。**3 个 Important + 4 个 Minor**：
3 Important 与 3 Minor 已修复（`fix:` 提交），2 Minor 记录理由后保留。测试 238 → **249**，
`mvn -pl services/seckill-app-jdk21 -am test` 全绿。

## Important-1 · deadlock 保真度（重点裁决项 #1）

- 原实现"可耗尽竞争"能产生真实 BLOCKED 窗口，但**永远不会有** `Found one Java-level deadlock`
  ——§9.1 排查列不可达；默认共享实例上这是合理取舍且已如实登记，但演示模式本身被错标。
- 裁决依据（从代码论证）：真死锁（synchronized hold-and-wait 的阻塞进入）**不可中断、不可回收**，
  "清理有保证"与"dump 可见死锁"在同一 JVM 内互斥。brief 给的两条路线中"可中断+jitter"被否决：
  jitter 只是推迟随机性——成环即永久泄漏、不成环即无症状，两头不得。采用**真互锁 + stop 走
  "注销并等待重启回收"（restart-not-leak）**路线：
- 新增操作员级配置门 `app.lab.thread.deadlock.true-deadlock`（env `LAB_THREAD_DEADLOCK_TRUE`，
  默认 `false`；与 oom 的 throwaway 门同款：配置决定、请求不可翻）。开启后 `startTrueDeadlock`
  造两监视器反序 hold-and-wait：
  - 测试用 `ThreadMXBean.findDeadlockedThreads()`（与 jstack 打印死锁段同一检测源）证明环成立，
    并断言参与线程状态为 `BLOCKED`——dump 可见性等价可证；
  - `stop()`/TTL 回退仍注销故障（注册表清空、指标回 0、该 mode 可再注入——监视器是
    FaultAction 实例字段，新一轮不与旧环接触，测试证明独立成环）；死锁 daemon 线程**只能由
    JVM 重启回收**，故仅适用于一次性实例。
- 默认路径保持原 drainable contention，并新增 `ThreadStateFingerprintTest` 在活 JVM 上采样
  真 `BLOCKED`（竞争版指纹若消失即测试失败）；`defaultDeadlockContentionReallyBlocksParticipants`
  同时把"竞争≠WAITING"钉死。原缺陷项中"需在专门一次性实例上补强互锁变体（待定）"**已被本步实现**
  （门控形式）；真容器上跑 `jstack` 目视输出仍留实栈验收。

## Important-2 · oom 允许分支不可能 OOM（§3 声明与代码不符）

- 原 `startHeapLeak(true)` 传 `capPercent=100`，分配前判 `used+chunk ≤ 100%×max`——"恰在断崖前
  停止增长"：即便 throwaway 小堆实例**也不会抛 OOM、不会 ExitOnOutOfMemoryError 退出、不会有
  容器重启与流量转移**，§9.1 oom 行三个指纹全部落空；且与本文 §3 "oom（复用无封顶分配器）"的
  说法矛盾（代码未达成文档，非虚报验收，但按原样演示必失败）。
- 修复：`HeapLeakAllocator.unbounded(...)`（无封顶语义，永不进 capped 判定），oom 路径改用它。
  回归钉死两端：`unboundedOomSinkAllocatesPastAnyCeiling`（脚本堆越过 100% 界仍继续分配——旧代码
  在此拒绝）与 `oomSinkAllocatesUnboundedAndDrainsToZeroOnStop`（在测试 JVM 用 1KB chunk 走完
  oom 装配：增长可见、stop 中断、stop 后计数归零）。真"打挂进程"分支仍不进 `mvn test`（破坏性，
  维持原实栈延期决定）。

## Important-3 · 回退/停止 与同 mode 重启的指标去发布竞态（ChaosActionService 同型）

- `ThreadLabMetrics.onRevert(mode, reason)` 原按键删除：`stop()` 先摘服务注册表、teardown 后到
  指标层，其间同 mode 重启 `publish(new)` → 旧 action 的回退**把新故障的 gauge 抹成 0**
  （故障活着、指标说没有），且不可自愈。
- 改为 `onRevert(action, ...)` 身份删除 `remove(mode, action)`；`ThreadLabMetricsTest` 确定性复现
  publish(old→new)→revert(old) 交错，断言新故障 fault_active/state_code/participant_threads 不受
  影响、`reverted_total` 每次回退恰好计一次。

## Minor（已修）

- **零残留强化**：teardown 在 join 之后二次 `drain()` + `heapLeaked.set(0)`——首轮 drain 与 worker
  末次分配之间存在竞态残留窗；此后"stop 后无内存残留"是硬保证（oom/heap-leak 测试断言 stop 后
  计数=0）。
- **validate() 真实边界**：`deadlock.thread-count` 由 >0 收紧为 ≥2（单线程无法反序抢锁，配置退化
  即静默无演示）；`true-deadlock` 默认 false 有测试钉住。
- **指纹断言补齐**（评审轴 #9"清理或症状坏掉时测试必须红"）：新增 `ThreadStateFingerprintTest` 3 例
  （deadlock→BLOCKED、stuck→WAITING/TIMED_WAITING、cpu-burn→RUNNABLE）与
  `heapLeakGrowsWithinCapOnRealRuntimeAndDrainsToZero`（真堆增长 ≤80%×max 且回退归零），全部
  采样 `Thread.getAllStackTraces()` 真线程状态，无 mock。

## Minor（保留，理由）

- **stuck 为 TIMED_WAITING 而非纯 WAITING**：`await(poll)` 带超时是可中断清理的必要代价；
  "区分 waiting 与 blocked"教学点仍成立（非 BLOCKED），原 §3 已写 "waiting/timed_waiting"。
- **`thread_lab_lifecycle_state_code` 与 §9.2 六态语义冲突**：维持原延期（#33 另立命名）；本步
  改名会破坏 #34 已约定的键契约。

## 修复后仍属延期的声明（不变）

- 实栈验收：Grafana D6 上的 `thread_lab_*`、打流量核对 blocked/waiting/cpu/heap 真实指纹、
  true-deadlock 实例上 `jstack` 目视输出、throwaway 实例真 OOM 的进程退出与容器重启/流量转移
  ——均留待统一实栈验收；**#33（生命周期与快照）与 #34（线程操作页与 D6）继续延期**，本修复只
  交付既有 `ThreadActionView` 键契约内的内容。
- chaos-controller 广播 / 全局急停仍未接（与 toggles/cache 同 TODO）。

## 验证（评审修复后）

- `cd code && export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1" && mvn -pl services/seckill-app-jdk21 -am test`
  → `Tests run: 249, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`
  （238 → 249，新增 11：FaultActionTest +3、HeapLeakAllocatorTest +1、LabThreadPropertiesValidationTest +2、
  ThreadLabMetricsTest +2、ThreadStateFingerprintTest +3）。

---

## 补充（2026-10-27）：BLOCKED 指纹的可复现性修复

`ThreadStateFingerprintTest.defaultDeadlockContentionReallyBlocksParticipants`（本步审核新增）在并发构建/高负载下会假失败：默认「可排空反转」的两个 worker 同相位循环——一个持 A、一个持 B，交替时**不会同时抢同一个监视器**，BLOCKED 只靠调度抖动偶尔出现；采样用 `getAllStackTraces()` 本身在负载下也很重。

修复 `a37abff`：

- `FaultAction.startDeadlock`：给奇数序 worker 加半个 `hold-ms` 的相位偏移，保证每轮都在同一监视器上相撞（真 BLOCKED，仍可在 stop 时排空）；
- 测试改用 `ThreadMXBean.getThreadInfo(ids)` 精准采样，窗口 250→500ms，最多 5 次有界重试，断言不变（必须真观测到 `Thread.State.BLOCKED`）。

证据：单类连跑 5 次全绿；app 模块随后 406→434（含快照）全绿。
