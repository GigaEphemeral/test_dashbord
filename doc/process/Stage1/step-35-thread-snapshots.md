> 步骤交接 · §9.3 线程快照（freeze 抓取 `jcmd` bundle + 保留策略 + Grafana annotation）
> 日期：2026-10-27
> 分支 / HEAD：worktree 分支 `worktree-agent-general-purpose-06643407`（基线 `a37abff`，`feature/ha-lab`）/ 本次提交 `feat: capture jcmd thread snapshots on freeze for the lifecycle lab`（`69eea58`）
> 关联任务：M5-2b（设计 §9.3 快照与调试）；上游 step-34 的 `LifecycleSnapshotSink` 接缝；下游 #34 线程操作页 / D6 面板
> 测试 / verify：`cd code && JAVA_HOME=jdk-21.0.12.1 mvn -pl services/seckill-app-jdk21 -am test` → **378 tests, 0 failures / 0 errors / 0 skipped / BUILD SUCCESS**（基线 350，本步新增 28：`ThreadSnapshotServiceTest` 8、`HttpGrafanaSnapshotAnnotatorTest` 5、`ThreadSnapshotEndpointTest` 3、`FreezeSnapshotWiringTest` 1、`ThreadSnapshotPropertiesValidationTest` 9、`LifecycleDemoServiceTest` 由 9 增到 11）
> 运行状态：未执行任何 Docker / compose / 实栈命令（共享栈由另一会话持有）；全部证据来自单元测试与 MockMvc。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- 落地设计 §9.3 的**快照与调试**：`freeze` 与新增的 `POST /lab/thread/snapshot` 会把 `jcmd Thread.print -l`、`GC.heap_info`、`GC.class_histogram` 写入 `<root>/<UTC 时间戳>_<实例>/`，同一目录内附 `metadata.json` 与 `analysis-template.md`。
- **镜像决策（选路线 a）**：`seckill-app-jdk21` 运行时阶段从 `eclipse-temurin:21-jre-alpine` 换成 `eclipse-temurin:21-jdk-alpine`，由 app 进程自己 `jcmd <本进程 pid>`。理由见「设计逻辑」。
- 存储有界：`max-count`（目录数）+ `max-age-seconds`（年龄）双向保留，只删名字符合快照格式的目录；快照根目录仍是仓库 `.gitignore` 里的 `doc/process/Stage1/snapshots/`。
- 失败不影响冻结：抓取失败只 WARN + 计数 + 返回 `captured=false`，部分目录会被删除；`freeze` 的“暂停推进”契约不变。
- 指标新增 `thread_lab_snapshot_*` 家族；Grafana annotation 走 chaos-controller 同款 `/api/annotations` 契约，URL 未配置即关闭。

# 完成功能项

## 1. 快照 bundle（`lab/thread/ThreadSnapshotService`）

一次成功抓取写 5 个文件，目录名 `<yyyyMMdd-HHmmss-SSS>_<sanitized instance>`（UTC，固定宽度，字典序即时间序）：

| 文件 | 来源 |
|---|---|
| `thread-dump.txt` | `jcmd <pid> Thread.print -l`（含 `Locked ownable synchronizers`） |
| `heap-info.txt` | `jcmd <pid> GC.heap_info` |
| `class-histogram.txt` | `jcmd <pid> GC.class_histogram` |
| `metadata.json` | instance、trigger（freeze/manual）、reason、state/stateCode、step、threadId/threadName、capturedAt（ISO + epochMs）、jvmVersion、jvmVendor、uptimeMs、pid、files、commands |
| `analysis-template.md` | 打包在 jar 内的分析模板逐字复制（`lab/thread/snapshot-analysis-template.md`） |

- `<pid>` 是 `ProcessHandle.current().pid()`，因此容器里不需要容器名，也不需要 `docker exec`；同样的命令仍可手敲 `docker exec jl-app-1 jcmd 1 Thread.print -l`。
- `JvmCommandExecutor` 是抽象接缝：生产实现 `ProcessJvmCommandExecutor`（`ProcessBuilder` + 守护线程抽干输出 + 外部化超时 + 非零退出抛 `IOException`），测试注入 canned 输出/失败。
- 命令超时、`jcmd` 路径、根目录全部来自 `app.lab.thread.snapshot.*`。

## 2. 接线

- `FileLifecycleSnapshotSink implements LifecycleSnapshotSink`：`capture()` 直接委托 `ThreadSnapshotService.capture()`；旧的 `LoggingLifecycleSnapshotSink` 占位删除（无其他引用）。
- `LifecycleDemoService` 新增 `snapshot(String reason)`：按当前真实状态/步骤/时钟构造 `LifecycleSnapshot`，不改状态机；`freeze()` 内部改用同一方法，对外行为逐字节不变（既有 9 个测试 + 新增 2 个）。
- 新端点 `POST /lab/thread/snapshot` → `Result<ThreadSnapshotView>`，沿用 `/lab/**` 的 `X-Lab-Token` 保护；demo 在 NEW 时也允许抓（bundle 是 JVM 级的，threadId=0 / state=NEW）。
- `ThreadSnapshotView{captured, trigger, directory, files, durationMs, capturedAtEpochMs, error}`，无凭据无栈内容。

## 3. 存储与保留

- 根目录 `app.lab.thread.snapshot.root-dir`（默认 `doc/process/Stage1/snapshots`，容器内 `/app/doc/process/Stage1/snapshots`）。
- 抓取串行化（`captureLock`）：两个并发抓取不会抢同一个目录名；同毫秒重复触发追加 `-2`、`-3`。
- 保留：先按年龄删（`max-age-seconds`，0 = 只按数量），再按目录名倒序保留 `max-count` 个；只匹配 `^\d{8}-\d{6}-\d{3}_.+$` 的目录，`keep-me/` 之类的无关目录永不触碰。删除失败只 WARN。
- 失败时删除半成品目录：**目录存在 == bundle 完整**。

## 4. 指标（`lab/thread/ThreadSnapshotMetrics`，全部 eager 注册）

| 指标 | 类型 / 标签 | 含义 |
|---|---|---|
| `thread_lab_snapshot_attempts_total{trigger}` | counter，`freeze` / `manual` 预注册 | 开始抓取的次数 |
| `thread_lab_snapshot_success_total{trigger}` | counter | 5 个文件全部写完 |
| `thread_lab_snapshot_failures_total{trigger}` | counter | 抓取失败（freeze 不受影响） |
| `thread_lab_snapshot_skipped_total{reason=disabled}` | counter | 开关关闭，未尝试 |
| `thread_lab_snapshot_duration_seconds{trigger}` | timer（count/sum/max） | 抓取墙钟耗时 |
| `thread_lab_snapshot_annotations_total{result=posted\|skipped\|failed}` | counter | best-effort annotation 结果 |
| `thread_lab_snapshot_retention_deleted_total` | counter | 被保留策略删除的目录数 |

## 5. Grafana annotation（`HttpGrafanaSnapshotAnnotator`）

- 契约与 `chaos-controller` 的 `HttpGrafanaAnnotator` 相同：`POST <base>/api/annotations`，`tags` + `text` + Basic Auth；额外带 `time = capturedAtEpochMs`，标记落在**快照时刻**而不是 HTTP 发出时刻。
- tags：`snapshot`、`thread-lab`、`<instance>`、`<trigger>` + `annotation.tags` 附加项；text 只含实例/触发/状态/步骤/文件数与目录名。
- `grafana-url` 为空或 `annotation.enabled=false` 时跳过并计 `skipped`；非 2xx、超时、连接失败只 WARN 并计 `failed`，绝不抛出。

## 6. 配置（`application.yml` 的 `app.lab.thread.snapshot.*`，`LabThreadProperties.Snapshot` + `validate()`）

| 配置键 | 环境变量 | 默认 | 校验 |
|---|---|---|---|
| `snapshot.enabled` | `LAB_THREAD_SNAPSHOT_ENABLED` | `true` | 关闭时 root/jcmd 可为空 |
| `snapshot.root-dir` | `LAB_THREAD_SNAPSHOT_DIR` | `doc/process/Stage1/snapshots` | 启用时非空 |
| `snapshot.max-count` | `LAB_THREAD_SNAPSHOT_MAX_COUNT` | `20` | `>= 1` |
| `snapshot.max-age-seconds` | `LAB_THREAD_SNAPSHOT_MAX_AGE_SECONDS` | `604800` | `>= 0`（0=关闭年龄规则） |
| `snapshot.command-timeout-ms` | `LAB_THREAD_SNAPSHOT_COMMAND_TIMEOUT_MS` | `5000` | `> 0` |
| `snapshot.jcmd-path` | `LAB_THREAD_SNAPSHOT_JCMD` | `jcmd` | 启用时非空 |
| `snapshot.annotation.enabled` | `LAB_THREAD_SNAPSHOT_ANNOTATION_ENABLED` | `true` | — |
| `snapshot.annotation.grafana-url` | `LAB_THREAD_SNAPSHOT_GRAFANA_URL` | 空（OFF） | 非空时必须是合法 URL，否则启动失败 |
| `snapshot.annotation.user` | `GRAFANA_ADMIN_USER` | 空 | 只从环境变量来 |
| `snapshot.annotation.password` | `GRAFANA_ADMIN_PASSWORD` | 空 | 只从环境变量来，绝不打印 |
| `snapshot.annotation.timeout-seconds` | `LAB_THREAD_SNAPSHOT_ANNOTATION_TIMEOUT_SECONDS` | `3` | `> 0` |
| `snapshot.annotation.tags` | `LAB_THREAD_SNAPSHOT_ANNOTATION_TAGS` | 空 | 逗号分隔 |

## 7. 镜像（`code/services/seckill-app-jdk21/Dockerfile`）

- 运行时阶段：`eclipse-temurin:21-jre-alpine` → `eclipse-temurin:21-jdk-alpine`（+约 180MB），仅为让 `jcmd` 存在；无启动参数、内存、用户、ENTRYPOINT 变化，不新增 volume，不碰 compose。

## 8. 分析模板

- 仓库可见副本：`doc/process/Stage1/snapshot-analysis-template.md`；打包副本：`src/main/resources/lab/thread/snapshot-analysis-template.md`。
- `ThreadSnapshotServiceTest.classpathTemplateMatchesTheRepositoryCopy` 逐字节比较两者，防止漂移。

# 设计逻辑

- **为什么选 jcmd + JDK 镜像（路线 a），而不是纯 JMX（路线 b）**：设计 §9.3 要求的是“类直方图”，而 `ClassLoadingMXBean` 只有 loaded/unloaded 计数，没有按类的实例数/字节数；纯 JMX 要拿到直方图只能走 `com.sun.management:type=DiagnosticCommand`，而该 MXBean 在 jlink 裁剪过的 Alpine JRE 里是否存在本会话无法验证（禁 Docker），一旦缺失就是演示时静默失败。选 JDK 镜像后三份产物是**字面**的 `jcmd` 输出，和设计原文、`docker exec ... jcmd 1 ...` 的教学话术一致，代价只是镜像体积，对本地教学栈可接受。
- **自抓 pid 而非硬编码 1**：`ProcessHandle.current().pid()` 在容器内通常是 1，但不假设；`jcmd` 作为子进程通过 attach socket 连回本 JVM，`/tmp` 可写即可（alpine 默认 1777）。
- **失败语义**：`ThreadSnapshotService.capture` 永不抛出，`freeze` 已在 step-34 用 try/catch 兜住 sink；两层都保留，因为接缝允许换实现。失败时删除半成品目录，避免“目录存在但缺文件”的误导。
- **同步抓取**：`freeze` 在持有命令锁时同步跑 3 次 `jcmd`（每次 ≤ `command-timeout-ms`）再可能等 annotation（≤3s）。教学场景 freeze 是手动动作，换取“响应返回时文件已就绪”的确定性；代价写在缺陷项。
- **保留顺序**：先年龄后数量。年龄用目录名里的 UTC 时间戳（不依赖 mtime，`docker cp` 后仍正确）；数量按名字倒序保留最新，`current` 永不删。
- **instance 命名**：沿用 `observability.instance`（Micrometer 共用标签，容器内即 `INSTANCE_ID=app-1`），文件名做 `[^A-Za-z0-9._-] → -` 清洗，避免路径穿越与非法字符。
- **annotation 的 time 字段**：不写 `time` 时 Grafana 用服务器当前时间，会把标记打在抓取完成之后；写入 `capturedAtEpochMs` 才能和 `thread_lab_demo_state_code` 的时间轴对齐。
- **配置外置**：路径、数量、年龄、超时、jcmd 路径、URL、凭据、tags 全部 `app.lab.thread.snapshot.*` + `${ENV:default}`，Java 侧无默认值，启动校验拒绝空白路径/非法 URL。

# 存在缺陷项&TODO

- **未做实栈验收**：本步禁 Docker/compose，未在 `eclipse-temurin:21-jdk-alpine` 里真正跑过 `jcmd <pid>`（自 attach、`/tmp` socket、只读根文件系统等容器细节待首次 `up.sh` 验证），也未在 Grafana 看过 annotation。下一步必须在实栈上跑一次 `freeze` 并 `docker cp` 出 bundle。
- **compose 未接线**：`x-app-env` 没有挂载 `doc/process/Stage1/snapshots`，也没有向 app 传 `GRAFANA_ADMIN_USER/PASSWORD`（当前只传 chaos-controller）。因此默认部署里快照落在容器内 `/app/doc/process/Stage1/snapshots`（需 `docker cp` 取出），annotation 默认 OFF。本任务被限定只能改 app 模块 + Dockerfile + 本交接，volume/env 需在后续步骤改 compose。
- **镜像变大**：JDK 运行时约 +180MB，未实测；若将来要瘦身，可改回 JRE 并走 `DiagnosticCommand` MXBean（需先证明该模块在 JRE 镜像中存在）。
- **freeze 延迟**：抓取 + annotation 全同步，最坏 `3 × command-timeout-ms + annotation.timeout` ≈ 18s，期间 lifecycle 命令锁被占。演示时点一次 freeze 等一下即可；若体验差，下一步把 capture 移到单线程 daemon 执行器（会牺牲“返回即文件就绪”）。
- **直方图成本**：`GC.class_histogram` 会遍历堆，堆大时几十到几百毫秒并可能触发一次安全点；教学规模（≤512MB）可接受，未在压测下测量。
- **D6 面板未做**：`thread_lab_snapshot_*` 系列已导出，但没有 Grafana panel（属 #34 / D6）。
- **控制台页面未做**：`POST /lab/thread/snapshot` 只在 API 层，没有按钮/文件列表 UI。
- **JDK 8 外壳未做 jstack/jmap 对齐**：设计 §9.3 提到 JDK 8 用 `jstack` + `jmap`，`seckill-app-jdk8` 不在本任务范围；如果后续要双 JDK 对比，需要同构的 sink 实现（`jstack <pid>`、`jmap -histo`）。
- **快照目录不提交**：`doc/process/Stage1/snapshots/` 已在 `.gitignore`，演示产物不会进仓库；模板单独放在 `doc/process/` 下才可提交。
- **没有 IT**：全部 Docker-free 单测 + MockMvc；`ThreadSnapshotEndpointTest` 用 standalone MockMvc，未覆盖 `LabTokenFilter`（沿用既有 `/lab/**` 契约）。
- **`ProcessJvmCommandExecutor` 无独立单测**：真实实现必须 fork `jcmd`，而本任务禁止在测试里跑真 `jcmd`；它的“命令不存在/超时/非零退出”分支只在代码审查层保证，首次实栈 freeze 才能验证。
- **既有 `FaultActionTest` 偶发时序失败（与本步无关，未修复）**：第一次全量 `mvn test` 中 `stopLeavesNoThreadBehind(THREAD_LEAK)` 失败一次（`live participants after stop` = 3），单独重跑 3/3 绿、第二次全量 378/378 绿。根因是 `FaultAction.startThreadLeak()` 的 spawn 循环只在外层检查 `stopped`，stop 恰好落在内层循环窗口时会有一批 worker 在 `teardown()` 快照 `spawnedLeakThreads` 之后才加入，因而没被 interrupt（`hold-ms=100s`）。修复属于 M5-1 的动作层，不在本任务范围，建议下次线程实验室修复时给内层循环补 `!stopped` 条件并在 join 后二次清扫。

# 待定事项和注意事项

- **5 分钟演示脚本（如何读一份快照）**：
  1. `docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --post-data="" http://127.0.0.1:8080/lab/thread/lifecycle/start'`（运行镜像是 `eclipse-temurin:21-jdk-alpine`，**没有 curl**，只有 BusyBox `wget`；`$LAB_TOKEN` 由容器自身环境提供，2026-10-27 更正，原写法照抄会报 `curl: not found`）；
  2. 两次 `.../lifecycle/step`（进入 BLOCKED、再到 WAITING），然后 `.../lifecycle/freeze`——返回 200 即代表 bundle 已落盘；
  3. `docker cp jl-app-1:/app/doc/process/Stage1/snapshots/<dir> .`，先看 `metadata.json`（哪个实例、哪一步、JVM 版本/uptime），再 `grep "java.lang.Thread.State:" thread-dump.txt | sort | uniq -c`，`grep -A 20 '"thread-lab-demo"' thread-dump.txt` 对照六态，`head -20 class-histogram.txt` 看 Top 类；
  4. 配了 `LAB_THREAD_SNAPSHOT_GRAFANA_URL` 时，Grafana 时间轴上会出现 `snapshot` 标签的 annotation，与 `thread_lab_demo_state_code` 的台阶对齐；
  5. 再注入 `heap-leak`，10 秒后 `POST /lab/thread/snapshot` 抓第二份，对比 `heap-info.txt` 的 used 与直方图 `[B` 行——这就是模板第 4 节。
- **annotation 打开方式**：app 容器需要 `LAB_THREAD_SNAPSHOT_GRAFANA_URL=http://grafana:3000`、`GRAFANA_ADMIN_USER`、`GRAFANA_ADMIN_PASSWORD`（compose 改动留给后续步骤）；不配就是 OFF，不影响抓取。
- **保留策略**：默认 20 份 / 7 天；`max-age-seconds=0` 只按数量。目录名时间戳是 UTC，和宿主机时区无关。
- **不要静默降级接缝**：若某实例没有写权限，建议显式 `LAB_THREAD_SNAPSHOT_ENABLED=false`，而不是让每次 freeze 都 WARN + 失败计数。
- **模板防漂移**：改分析模板必须同时改 `doc/process/Stage1/snapshot-analysis-template.md` 与 `src/main/resources/lab/thread/snapshot-analysis-template.md`，否则测试红。
- **freeze 期间命令锁**：演示时不要并发点 step/resume，等 freeze 返回。

# 附录

- 验证命令：`cd code && JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1" mvn -pl services/seckill-app-jdk21 -am test` → `Tests run: 378, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（基线 `a37abff` 同命令为 350）；本步新增的 4 个快照测试类单跑同样全绿（17 个用例）。
- 关键文件：`lab/thread/{ThreadSnapshotService,ThreadSnapshotMetrics,JvmCommandExecutor,ProcessJvmCommandExecutor,FileLifecycleSnapshotSink,HttpGrafanaSnapshotAnnotator,SnapshotAnnotator}.java`、`lab/api/{ThreadSnapshotView,LabThreadController}.java`、`lab/config/ThreadLabConfig.java`、`LabThreadProperties.java`（新增 `Snapshot`/`Annotation`）、`application.yml`、`Dockerfile`、`src/main/resources/lab/thread/snapshot-analysis-template.md`、`doc/process/Stage1/snapshot-analysis-template.md`；测试 `lab/thread/{ThreadSnapshotServiceTest,ThreadSnapshotEndpointTest,HttpGrafanaSnapshotAnnotatorTest,ThreadSnapshotPropertiesValidationTest,FreezeSnapshotWiringTest,LifecycleDemoServiceTest}.java`。
