# 当前进展

> 任务/步骤：Stage2 **B2-02a 受控 k6 执行器核心（白名单校验 + 启动计划渲染）**
> 分支：`feature/stage2-workbench`（隔离 worktree `agent-general-purpose-d6f7705d`，基线 `1679a0b`）
> 隔离自证：`git rev-parse --show-toplevel` → `D:/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-d6f7705d`，不等于主工作树 `D:/000CODE/JAVA-LEARN`
> 最新代码提交：`d1fecd9`（planner + 单测）、`4906c8a`（yml/compose/.env.example/接线测试）；本文件随后续 `docs(B2-02)` 提交落盘
> 测试：`mvn -f code/pom.xml -pl services/chaos-controller -am test` → **516 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**（基线 491，新增 25 全部来自 `BenchLaunchPlannerTest`）
> `bash code/lab/scripts/test-chaos-env-wiring.sh` → **80 PASS / 0 FAIL**，汇总行 `CHAOS ENV WIRING PASSED (8 chaos keys + 72 ledger/metrics/precheck/bench keys)`（B2-01b 后为 60，本步 +12）
> 未做：未执行任何 docker/compose 生命周期命令（无 build/up/down/restart/rm），未启动真实 k6，未改 `code/lab/k6/**`、dashboard、`claims.yml`，未接线 run 生命周期与 UI

本步只做“把一次 k6 启动变成可校验、可断言的数据结构”：从 manifest 白名单里挑场景，校验用户参数，
再用配置拼出容器名 / argv / env / binds / labels / 网络 / 报告路径。**不创建容器、不读写文件系统、不起 Spring 上下文要求**，
因此全部契约都能在没有 Docker 的环境里单测。真正的 Docker 启动、停止、超时清理、日志与报告端点、
`RunLifecycleHook.launch` 接线、UI 表单与 A01/A20 真机验收都留给 B2-02b。

# 完成功能项

## 1. `BenchExecProperties`（`lab.bench.exec`，12 个键全部外置）

新增包 `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/bench/exec/`：

| yml 键 | env 键 | 安全默认值 | 用途 |
|---|---|---|---|
| `lab.bench.exec.k6-image` | `LAB_BENCH_EXEC_K6_IMAGE` | `grafana/k6:1.3.0` | 镜像；请求不可覆盖 |
| `network` | `LAB_BENCH_EXEC_NETWORK` | `jl-net` | 容器网络 |
| `scripts-host-dir` | `LAB_BENCH_EXEC_SCRIPTS_HOST_DIR` | 空 | 宿主机 k6 目录；空 → 计划拒绝（503/50303） |
| `scripts-mount` | `LAB_BENCH_EXEC_SCRIPTS_MOUNT` | `/scripts` | 容器内只读挂载点 |
| `reports-mount` | `LAB_BENCH_EXEC_REPORTS_MOUNT` | `/reports` | 容器内报告挂载点 |
| `reports-volume` | `LAB_BENCH_EXEC_REPORTS_VOLUME` | `jl-k6-reports` | 报告命名卷 |
| `prometheus-rw-url` | `LAB_BENCH_EXEC_PROMETHEUS_RW_URL` | `http://prometheus:9090/api/v1/write` | remote write |
| `base-url` | `LAB_BENCH_EXEC_BASE_URL` | `http://nginx` | 受测入口 |
| `container-prefix` | `LAB_BENCH_EXEC_CONTAINER_PREFIX` | `jl-k6-` | 容器名前缀 |
| `max-vus` | `LAB_BENCH_EXEC_MAX_VUS` | `1000` | VU 类参数上限 |
| `max-duration-seconds` | `LAB_BENCH_EXEC_MAX_DURATION_SECONDS` | `3600` | 时长类参数上限（秒） |
| `summary-file` | `LAB_BENCH_EXEC_SUMMARY_FILE` | `summary.json` | 报告文件名 |

三处默认值一致：`application.yml` 的 `${LAB_BENCH_EXEC_*:default}` 占位、`compose.base.yml` 的
`${LAB_BENCH_EXEC_*:-default}` 转发、`.env.example` 条目。`scripts-host-dir` 三处都为空：
controller 无法凭空知道 `/scripts` 背后的宿主目录（`run.sh` 在 Windows 上还要 `cygpath -m`），
B2-02b 负责解析真实路径；在解析出来之前宁可拒绝，也不渲染一个 source 为空的 bind。

`test-chaos-env-wiring.sh` 未改发现逻辑：B2-01b 的正则 `\${LAB_BENCH_[A-Z0-9_]*:` 本就覆盖
`LAB_BENCH_EXEC_*`，本步只更新注释说明前缀包含 exec；实测 12 个新键全部 PASS（总数 68 → 80）。

## 2. `BenchLaunchPlan`（不可变计划）

record 字段：`runId / scenario / containerName / image / argv / env / binds / labels / network /
reportContainerPath / reportHostPath`，构造时 `List.copyOf` + 不可变 `LinkedHashMap`。
`Bind(source, destination, readOnly)` 对应一条 `-v`。`reportContainerPath` 是**容器内**路径
`<reports-mount>/<runId>/<summary-file>`（避坑 18）；`reportHostPath` 是同一路径的控制器视角，
B2-02b 通过命名卷读取时才真正用到。

## 3. `BenchLaunchPlanner`（纯校验/渲染）

输入为 `Snapshot` + runId + 场景名 + 用户 params（run 创建请求里 group `request` 的那份参数）：

- `plan(runId, scenario, params)`：先 `BenchScenarioCatalog.load()` 再走快照重载；
- `plan(snapshot, runId, scenario, params)`：不碰 catalog/文件系统的纯函数，单测走这条。

校验顺序：场景白名单 → runId → 参数（强制键/未知键/类型/边界）→ 配置（`scripts-host-dir`）。
场景必须命中 `views.ui`（当前 smoke/load/spike）。参数只认该场景 `params` 的键，类型必须与 manifest
默认值同类（数字/字符串/布尔/数组），数字 ≥ 0 且 VU 类（`*_VUS`）≤ `max-vus`，时长类
（`*_DURATION`/`*_RAMP_UP`/`*_RAMP_DOWN`）按 k6 duration 语法解析后 ≤ `max-duration-seconds`；
字符串拒绝 `\x00-\x1F`/`\x7F`；未知键直接拒绝，不做宽松透传。params 缺省用 manifest 默认值。

## 4. 计划契约（全部由白名单字段拼出）

| 项 | 规则 | 示例（smoke，`run-20261028-001`） |
|---|---|---|
| 容器名 | `container-prefix + sanitized runId` | `jl-k6-run-20261028-001` |
| argv | `run --quiet -o experimental-prometheus-rw --summary-export <reports>/<runId>/<summary-file> <scripts>/<manifest.script>`，列表、无 shell | `["run","--quiet","-o","experimental-prometheus-rw","--summary-export","/reports/run-20261028-001/summary.json","/scripts/scenarios/smoke.js"]` |
| env | 场景 `env` 名单里“有值”的项 + 强制值；没有 `--out`、没有任意 `-e` | `RUN_ID`、`BASE_URL=http://nginx`、5 个场景参数、`K6_PROMETHEUS_RW_*` 3 个 |
| 强制 env | `RUN_ID=runId`；`BASE_URL=base-url`；`K6_PROMETHEUS_RW_SERVER_URL=prometheus-rw-url`；`TREND_STATS=p(95),p(99),max`；`PUSH_INTERVAL=5s`（后两者为常量，镜像 `run.sh:112-113`，不加配置键） | 用户传入这些键 → 拒绝 |
| binds | `scripts-host-dir -> scripts-mount` **只读**；`reports-volume -> reports-mount` **读写** | 共 2 条 |
| labels | `lab=java-learn`、`lab.role=k6`、`lab.run-id=<runId>`、`lab.scenario=<scenario>` | 与现有 compose 标签口径一致 |
| 网络 / 镜像 | 仅配置 | `jl-net` / `grafana/k6:1.3.0` |
| 报告 | 容器内 `<reports>/<runId>/<summary.json>`，同时给出控制器视角路径 | 避免“summary 写到宿主机路径”的坑 18 |

不允许任意 `--out`、任意脚本路径、任意挂载、任意镜像、任意命令：argv 只有固定动词 + 配置挂载点 +
manifest 的 `script` 字段（catalog 已校验为 `scenarios/<name>.js`）；runId 只影响容器名/报告目录，
非法字符统一替换为 `-`，首字符非字母数字直接拒绝。

## 5. 拒绝矩阵（`BenchLaunchRejectedException`，测试逐条钉住）

| 输入 | 结果 | 信息要点 |
|---|---|---|
| `stress` / `soak` | 拒绝 | “CLI-only”，在 `views.bench` 不在 `views.ui` |
| `traffic` | 拒绝 | “service-only”，由 compose 服务运行 |
| `d7`（以及 ha-failover/db-failover/cache-lab） | 拒绝 | “CLI-only”，在 `views.run` 不在 `views.ui` |
| 未知场景 | 拒绝 | “unknown scenario” + UI 白名单列表 |
| 未知参数键（如 smoke 传 `PRODUCT_ID`） | 拒绝 | “unknown parameter” + 该场景可接受键 |
| 类型不符（`SMOKE_VUS="10"`、`SMOKE_DURATION=30`） | 拒绝 | “expects a number/string (manifest default …)” |
| 负数 / NaN / Infinity | 拒绝 | “>= 0” / “finite” |
| `*_VUS` > `max-vus` | 拒绝 | 提示 `lab.bench.exec.max-vus` |
| 时长 > `max-duration-seconds`（`2h`、`1h1s`） | 拒绝 | 提示 `lab.bench.exec.max-duration-seconds` |
| 非法 duration（`soon`、`1m30`、空串） | 拒绝 | “must be a k6 duration (e.g. 30s, 10m, 1h, 1m30s)” |
| 字符串含换行/控制字符 | 拒绝 | “must not contain newlines or control characters” |
| 覆盖 `RUN_ID`/`BASE_URL`/`K6_PROMETHEUS_RW_*` | 拒绝 | “forced value … cannot be overridden” |
| runId 空白 / 清洗后不以字母数字开头 | 拒绝 | 拒绝不安全的容器/路径片段 |
| `scripts-host-dir` 空白 | `BenchUnavailableException`（503/50303） | 提示 `LAB_BENCH_EXEC_SCRIPTS_HOST_DIR` 与 B2-02b |

`BenchLaunchRejectedException` 是请求拒绝（B2-02b 暴露端点时映射为 4xx 语义），
`BenchUnavailableException` 是可用性问题（沿用 B2-01b 的 503/50303）；两者刻意区分，文档即本节。

## 6. 单测 `BenchLaunchPlannerTest`（25 个，无 Spring、无 Docker、无文件）

覆盖：smoke/load/spike 默认计划（argv 精确匹配、含 `/reports/<runId>/summary.json`、无 `--out`）；
强制 env 与常量；自定义合法参数生效；null params 用默认；stress/soak（CLI-only）、traffic
（service-only）、d7（run-only）、未知/空白场景；未知键、类型不符、null 值、负数/NaN、超 `max-vus`
（含 1000 边界）、超时长（含 1h 边界与 `1m30s` 复合）、非法 duration、控制字符；5 个强制键逐一拒绝；
runId 空白/不安全/清洗；`scripts-host-dir` 空白 → `BenchUnavailableException`；配置全量覆盖后
镜像/网络/前缀/挂载/卷/报告名/URL 全部来自配置；duration 解析器语法（30s/10m/1h/1m30s/30/1.5s/500ms）。

# 设计逻辑

1. **planner 是纯函数，Docker 是执行器的另一侧**。B2-02a 只产出 `BenchLaunchPlan`；B2-02b 拿 plan 调
   Docker、流式读日志、复制报告、处理超时。这样白名单/参数边界可以用毫秒级单测钉死，不需要起栈，
   也不会出现“校验代码藏在 Docker 调用里、只能靠真机验证”的结构。
2. **白名单只认 `views.ui`**。manifest 的 `views.ui` 是 B2-01 已确认口径（stress/soak CLI-only），
   planner 不再维护第二份清单；拒绝信息区分 bench-only / run-only / service-only，便于 UI 解释
   “为什么这个场景不能从控制台启动”。
3. **“强制值”优先于“未知键”判断**。若先按未知键拒绝，`RUN_ID` 的报错会是“不认识的参数”，
   掩盖“这是被系统占用的名字”这一事实；强制键先判，信息更准确。
4. **时长按 k6 语法本地解析**。不引依赖、不调 shell：支持 `30s/10m/1h/1m30s/500ms/30`（裸数字=秒），
   段间不允许空隙；上限比较用秒（double），`1h` 恰好等于 3600 放行、`1h1s` 拒绝。
5. **默认值可信、用户值不可信**。manifest 的 `params` 就是场景作者声明的默认值，不做上限复核
   （例如 spike 默认 800 VU 在默认 1000 上限内）；只有请求带来的覆盖值才校验上限，避免把
   “调整默认值”误判成攻击，同时保证用户不能把 800 改到 5000。
6. **报告路径双份表达**。`reportContainerPath` 用于 argv（容器内 `/reports/...`，避坑 18），
   `reportHostPath` 是同一路径的控制器视角；B2-02b 通过命名卷读取，不去猜宿主机绝对路径。

# 存在缺陷项&TODO

1. **Docker 未接线（B2-02b）**：容器创建/启动/停止、超时清理、日志分页与字节上限、报告下载端点均未实现；
   本步没有任何 `docker` 调用。
2. **run 生命周期未接线（B2-02b）**：`RunLifecycleHook.launch(runId)` 仍是空实现；B2-02b 需要从
   `lab_run`（scenario）与 `lab_run_param`（group `request`）取数，调用 planner 后再启动容器。
   planner 的 `plan(snapshot, ...)` 已为“已有快照”的调用方预留。
3. **`scripts-host-dir` 无人解析**：当前默认空，任何真实启动都会被 503 拒绝；B2-02b 需决定
   宿主路径来源（compose 变量 / 控制器挂载信息 / 显式配置）。
4. **报告命名卷未创建**：`jl-k6-reports` 只是计划里的卷名，compose 尚未声明该命名卷（B2-02b 一并处理）。
5. **端点与 UI 未做**：没有 `POST /lab/bench/runs` 之类的入口，控制台也没有参数表单；A01（UI 启动
   `load` 全程无终端）与 A20（停止/超时清理）都未在真机验证。
6. **数组参数只做了通用校验**：ui 场景当前没有数组参数；若将来 stress 进 UI（需先评审），
   `STRESS_STAGES`（对象数组）会被明确拒绝，需另设受控的 stages 表单。

# 待定事项和注意事项

1. **B2-02b 的接口边界**：调用方负责“run 存在、状态为 READY、参数已持久化”，planner 负责
   “场景/参数/配置是否允许以及计划长什么样”，执行器负责“按计划创建容器并回报”。planner 不查库、
   不改 run 状态、不写台账。
2. **异常语义**：请求类拒绝用 `BenchLaunchRejectedException`；配置/目录不可用用
   `BenchUnavailableException`（503/50303）。B2-02b 若新增错误码，应保持这两类不混用。
3. **常量与 `run.sh` 的同步**：`K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),max`、
   `K6_PROMETHEUS_RW_PUSH_INTERVAL=5s` 取自 `run.sh:112-113`；若 CLI 口径变化，需要同时改
   planner 常量与测试，否则会出现“UI 启动的 k6 与 CLI 指标口径不同”。
4. **runId 清洗**：正常 runId 形如 `run-20261028-001`，清洗是防御性措施；容器名与报告目录使用
   清洗后的同一段字符串，保证两者一致、可互相定位。
5. **不要把 `--out` 加回来**：k6 的 `-o experimental-prometheus-rw` 是唯一输出；报告只走
   `--summary-export` + `/reports` 卷，任何“顺便导出 raw 数据”的想法都需要重新评审白名单。
6. **配置三处一致是硬约束**：新键必须同时进 `application.yml`、`compose.base.yml`、`.env.example`，
   并跑 `test-chaos-env-wiring.sh`；只改 Java 默认值不会让 `.env` 生效（compose 显式转发环境变量）。

## 最新 commit

`d1fecd9`（`feat(B2-02)` planner + 单测）、`4906c8a`（`feat(B2-02)` 配置三处 + 接线回归），本文件随
`docs(B2-02)` 提交落盘（hash 见 `git log --oneline -3`）。

# 补充：B2-02b-1（k6 容器执行层）

> 任务/步骤：Stage2 **B2-02b-1 Docker 启停/状态/日志/报告端点 + 宿主脚本目录解析**
> 分支：`feature/stage2-workbench`（隔离 worktree `agent-general-purpose-010140d1`，基线 `b6be7b7`）
> 隔离自证：`git rev-parse --show-toplevel` → `D:/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-010140d1`，不等于主工作树 `D:/000CODE/JAVA-LEARN`
> 最新代码提交：`5caadeb`（网关/执行器/端点/解析器 + 单测）、`bd667f3`（yml/compose/.env.example 六个新键 + `jl-k6-reports` 卷挂载）、`db3b31c`（summary 路径约束）；本文件随 `docs(B2-02)` 提交落盘
> 测试：`mvn -f code/pom.xml -pl services/chaos-controller -am test` → **560 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**（B2-02a 基线 516，本步 +44：`BenchExecutionControllerTest` 12、`BenchScriptsHostDirResolverTest` 7、`BenchRunExecutorTest` 25）
> 接线：`bash code/lab/scripts/test-chaos-env-wiring.sh` → **86 PASS / 0 FAIL**，汇总行 `CHAOS ENV WIRING PASSED (8 chaos keys + 78 ledger/metrics/precheck/bench keys)`（B2-02a 后为 80，本步 +6）
> 未执行任何 docker/compose 生命周期命令（无 build/up/down/restart/rm/pull），未启动真实 k6；真容器、卷与端点真机验证留给 B2-02b-2

本步把 B2-02a 的 `BenchLaunchPlan` 接到 Docker：解析宿主机脚本目录、按计划创建/启动容器、读取有上限的状态/日志/报告，并暴露三个只读端点。**不接 run 生命周期、不调度看门狗、不改 run 状态、不读库、不改 UI**。

## 1. 宿主脚本目录解析（`BenchScriptsHostDirResolver`）

controller 在容器内运行，`/app/k6` 只是 bind 挂载点；Docker daemon 创建 k6 容器时需要宿主机绝对路径。解析顺序：

1. `lab.bench.exec.scripts-host-dir`（`LAB_BENCH_EXEC_SCRIPTS_HOST_DIR`）非空 → 直接采用并 trim，**不再 inspect**；
2. 否则 inspect `lab.bench.exec.controller-container`（默认 `jl-chaos-controller`），找到 `Mount.Destination` 等于 `lab.bench.exec.controller-k6-mount`（默认 `/app/k6`，比较时容忍一个尾部 `/`）的唯一挂载，返回其 `Mount.Source`。

失败一律 `BenchUnavailableException`（503/50303），消息带补救动作：controller 容器不存在、无匹配挂载、多个匹配（要求显式配置消歧）、source 为空、配置键为空。**绝不回退到空路径或容器内路径**。`DockerControllerMountSource` 只读 `inspectContainerCmd`，不创建/启动任何东西；`lab.docker.enabled=false` 时解析器同样返回 503，而不是让 Spring 上下文启动失败。

| 新增 yml 键 | env 键 | 默认值 | 用途 |
|---|---|---|---|
| `lab.bench.exec.controller-container` | `LAB_BENCH_EXEC_CONTROLLER_CONTAINER` | `jl-chaos-controller` | 被 inspect 的 controller 容器名 |
| `controller-k6-mount` | `LAB_BENCH_EXEC_CONTROLLER_K6_MOUNT` | `/app/k6` | 要反查 source 的容器内挂载点 |
| `report-max-bytes` | `LAB_BENCH_EXEC_REPORT_MAX_BYTES` | `4194304` | 单份 summary 下载上限 |
| `logs-max-bytes` | `LAB_BENCH_EXEC_LOGS_MAX_BYTES` | `65536` | 单次日志字节上限 |
| `logs-max-tail` | `LAB_BENCH_EXEC_LOGS_MAX_TAIL` | `1000` | 日志行数上限 |
| `stop-grace-seconds` | `LAB_BENCH_EXEC_STOP_GRACE_SECONDS` | `10` | k6 容器 stop 宽限 |

六个键三处默认一致（`application.yml` 占位 / `compose.base.yml` 转发 / `.env.example`），接线脚本的 `LAB_BENCH_*` 自动发现已覆盖，无需改脚本。B2-02b-2 的调用顺序应为：`resolver.resolve()` → 赋给 `scripts-host-dir`（或经参数传入 planner）→ `planner.plan(...)`；planner 对空值仍然拒绝，解析器是唯一合法来源。

## 2. `K6ContainerGateway`（接口 + Docker 实现）

接口位于 `bench/exec/K6ContainerGateway.java`，Docker 实现 `DockerK6ContainerGateway`；Spring 由 `BenchExecConfig` 装配（`ObjectProvider<DockerClient>`，禁用 Docker 时给 503 桩）。

| 方法 | 契约 |
|---|---|
| `ensureImage(image)` | `inspectImageCmd` 验证本地存在；缺失 → 503/50303，消息给出 `docker pull <image>`；**绝不自动拉取** |
| `createAndStart(plan)` | 只用 plan 的 image/argv/env/labels/network/binds；`autoRemove=false`、`RestartPolicy.noRestart()`、`tty=false`；同名容器 RUNNING/CREATED/RESTARTING/PAUSED → 409 语义，EXITED/DEAD → 先 remove 再创建；返回容器 id |
| `inspect(runId)` | 先按 `lab.run-id` 标签查（`showAll`），再按 `containerPrefix + runId` 名字查；找不到 → `Snapshot(status=ABSENT)` 而不是异常；返回 status/containerId/exitCode/startedAt/finishedAt/image/scenario |
| `logs(containerName, tail, maxBytes)` | tail 夹到 1..10000、字节预算夹到 1..4 MiB 硬上限；超限丢弃并返回 `truncated=true` + `originalBytes`；容器不存在 → `ContainerNotFoundException`（404） |
| `stop(containerName, graceSeconds)` | 幂等：已停止/不存在返回 `false`，成功发出 stop 返回 `true` |
| `remove(containerName)` | best-effort：不存在/仍在运行/daemon 失败均返回 `false`，不抛异常 |
| `listK6Containers()` | 只列 `lab=java-learn` 且 `lab.role=k6` 的容器 → `ContainerSummary`，供 `sweepOrphans()` 使用 |

安全边界：创建容器必须带 `lab=java-learn` 与 `lab.role=k6`（plan 已带 `lab.run-id`/`lab.scenario`），否则网关拒绝创建；logs/stop/remove 前重新检查这两个标签，非 k6 容器 → `ContainerNotAllowedException`（403）；**不会误删/误读其他 lab 容器**。

## 3. `BenchRunExecutor`

- `start(plan)`：校验 runId（`[A-Za-z0-9][A-Za-z0-9._-]*`，拒绝 `..`/分隔符/空白/超 128 字符）→ `ensureImage` → 创建 `<reports-mount>/<runId>/`（`normalize()+startsWith` 穿越防护）→ 同名 live 冲突 409、stale 先 remove → `createAndStart` → 返回 `BenchExecutionState`。
- `state(runId)`：Docker 元数据 + 报告文件可用性/字节数；ABSENT 也返回正常状态，并给 `note`（未创建/运行中报告未生成/退出但缺报告/报告超限）。
- `logs(runId, tail, maxBytes)`：先 `inspect`，ABSENT → 404；查询参数越界**夹取**到配置上限（不返回 400，除非法数字由 Spring 转 400）。
- `report(runId)`：读本地 reports 目录（与 k6 通过命名卷共享）；缺失 → `BenchReportNotFoundException`（404）；超过 `report-max-bytes` → `BenchReportTooLargeException`（413/41301）；返回原始字节 + attachment 文件名 `<runId>-<summary-file>`。
- `stop(runId)` / `cleanup(runId)`：best-effort stop + remove；报告目录保留，容器删除后仍可下载 summary。
- `sweepOrphans()`：本步只做**只读清单**（`listK6Containers`），不删除、不调度；真正启动时清理由 B2-02b-2 决定。
- 状态/日志路径不读库、不改 `lab_run` 状态。

## 4. 只读端点（`BenchExecutionController`）

`TokenFilter` 已覆盖 `/lab/**`，无新增鉴权。

| 端点 | 响应 |
|---|---|
| `GET /lab/runs/{runId}/k6` | `Result<BenchExecutionState>`：`{code,message,data:{status, containerName, containerId, scenario, exitCode, startedAt, finishedAt, reportAvailable, reportBytes, note}}`；容器不存在 → **200 + status=ABSENT** |
| `GET /lab/runs/{runId}/k6/logs?tail=&maxBytes=` | `Result<{text, truncated, bytes}>`；参数越界夹取，非数字 400/40001 |
| `GET /lab/runs/{runId}/k6/report` | `application/json` 原始字节 + `Content-Disposition: attachment; filename="<runId>-summary.json"`；缺失 404，超限 413 |

错误码：`40002 BENCH_REQUEST_REJECTED`（launch/runId 拒绝，400）、`40904 K6_CONTAINER_CONFLICT`（live 同名容器，409）、`50303 BENCH_UNAVAILABLE`（镜像缺失/目录不可用/daemon 不可用，503）、`40401`（报告或容器不存在）、`41301 PAYLOAD_TOO_LARGE`（报告超限）。`BenchLaunchRejectedException` 仍在 `bench.exec` 包，异常处理器已单独映射。

## 5. 卷与挂载

- 顶层 `volumes:` 新增 `jl-k6-reports`，实际 Docker 名 `name: ${LAB_BENCH_EXEC_REPORTS_VOLUME:-jl-k6-reports}`，与 planner 的 `reports-volume` 使用同一 env。
- controller 增加读写挂载 `jl-k6-reports:/reports`（在 ledger stash 挂载之后）；k6 容器由 plan 绑定同一命名卷到 `/reports`，因此 summary 写入后 controller 可直接读取，无需 `docker cp`。
- 脚本仍走 `../k6:/app/k6:ro`；解析器把这个挂载的 host source 交给 k6 容器绑定到 `/scripts:ro`。

## 6. 单测

- `BenchScriptsHostDirResolverTest`（7）：单挂载命中、显式覆盖优先且不 inspect、无匹配、多个匹配、空 source、controller 不存在传播 503、controller-k6-mount 空白提前拒绝。
- `BenchRunExecutorTest`（25）：start 建目录+create/start、镜像缺失拒绝（不建目录/不创建）、RUNNING 冲突、EXITED 重建、状态映射、ABSENT/运行中提示、日志夹取与默认值、日志 404、报告读取/缺失/超限、runId 穿越（含手工 plan）、stop 幂等、cleanup stop+remove、sweepOrphans 只返回 k6 清单且不删除；`summary-file` 越出 run 目录被拒绝。
- `BenchExecutionControllerTest`（12）：ABSENT 200、完整字段、logs 形状与无参默认、报告下载/404/413、40002/40904/50303、日志 404、非数字 tail 400。
- 全部不启 Docker、不启 Spring 上下文、不读库；报告目录用 `@TempDir`。

## 7. 未做项（B2-02b-2）

- `RunLifecycleHook.launch` 接线：从 `lab_run` / `lab_run_param` 取场景与参数、调用 resolver + planner、在 run 进入 RUNNING 后 `start`，失败时更新 run 状态。
- 超时看门狗/自动清理、`sweepOrphans()` 的启动调度与删除策略。
- UI 启动/停止/日志/报告入口；A01（UI 启动 load 全程无终端）与 A20（停止/超时清理）真机验收；console-smoke 基线核对。
- 真容器、命名卷、三个端点的真机验证：本步只跑单测与静态接线，未执行任何 docker/compose 命令。
- `scripts-host-dir` 仍未回填到 `BenchExecProperties`：由 b-2 在调用 planner 前完成，planner 对空值继续 503。

## 最新 commit

`5caadeb`（网关/执行器/端点/解析器 + 单测）、`bd667f3`（六个新键 + `jl-k6-reports` 卷）、`db3b31c`（summary 路径约束）、本文件随 `docs(B2-02)` 提交落盘（hash 见 `git log --oneline -5`）。


# 补充：B2-02b-2a（run 生命周期接线 + 退出/超时看门狗 + 孤儿清理）

> 任务/步骤：Stage2 **B2-02b-2a k6 执行层接入 run 生命周期**
> 分支：`feature/stage2-workbench`（隔离 worktree `agent-general-purpose-2e156591`，基线 `2644b93`）
> 隔离自证：`git rev-parse --show-toplevel` → `D:/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-2e156591`，不等于主工作树 `D:/000CODE/JAVA-LEARN`
> 最新代码提交：`60ea3a6`（hook 契约 / 真实 hook / watchdog / planner 重载 / 单测）、`045d650`（四个配置键三处外置）；本文件随 `docs(B2-02)` 提交落盘
> 测试：`mvn -f code/pom.xml -pl services/chaos-controller -am test` → **580 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**（基线 560，本步 +20：`BenchRunLifecycleHookTest` 6、`BenchRunWatchdogTest` 11、`RunLifecycleIT` +3）
> 接线：`bash code/lab/scripts/test-chaos-env-wiring.sh` → **90 PASS / 0 FAIL**，汇总行 `CHAOS ENV WIRING PASSED (8 chaos keys + 82 ledger/metrics/precheck/bench keys)`（B2-02b-1 后为 86，本步 +4）
> 未执行任何 docker/compose 生命周期命令（无 build/up/down/restart/rm/pull），未启动真实 k6，未改 UI / console-smoke；真机验收留给 B2-02b-2b 与主会话

本步把 B2-02b-1 的容器执行层接到 run 状态机：启动时真正创建 k6 容器，操作员停止时停容器，自然退出/非零退出/超时由看门狗收口，控制器重启后的孤儿 k6 容器在台账就绪后清理。**不做 UI、不加端点、不跑真机。**

## 1. Hook 契约与 afterSummary

`RunLifecycleHook` 的两个方法都改为返回 `LaunchResult`（`Map<String,Object> details()` + `static none()`；默认实现仍返回空结果，`RunLifecycleHook.none()` 行为不变）：

| 方法 | 调用位置 | 返回 details（真实 hook） |
|---|---|---|
| `LaunchResult launch(runId)` | `RunLifecycleService.doStart`：READY 之后、RUNNING 之前 | `k6Container`、`k6ContainerId`（非空时）、`k6Scenario`、`k6ReportPath`、`k6Status` |
| `LaunchResult stop(runId)` | `doStop`：RUNNING→STOPPING 之后；`closeFailedStart`：READY/VALIDATING 收尾为 FAILED 之前 | `k6Stopped`、`k6Status`、`k6Container`、`k6ExitCode`（非空时）、`k6ReportAvailable`；状态读取失败时附 `k6StateError` |

- start operation 的 afterSummary 在原有 `runStatus/lockKey/lockExpiresAt/precheckVerdict/precheckWarnings` 之后合并 launch details。
- stop operation 的 afterSummary 先合并 stop details；`hook.stop` 抛异常时写 `k6StopError`（`AuditSanitizer.describe`），**仍完成 STOPPING→COMPLETED 并释放锁**，不把操作员关闭 run 的请求卡在 RUNNING。
- `closeFailedStart` 在 run 处于 READY/VALIDATING 时先 best-effort `hook.stop` 再写 FAILED，防止“容器已创建、READY→RUNNING 失败”留下运行中的 k6；若竞争路径已把 run 推进到 RUNNING，则不动容器也不抢锁。
- 真实 hook `BenchRunLifecycleHook` 只依赖 repository / catalog / planner / resolver / executor / ObjectMapper：读 `lab_run.scenario` 与 `lab_run_param` 的 `request` 组（缺失按空 Map，JSON 损坏按 503 语义），`resolver.resolve()` 得到宿主 k6 目录，再走新增重载 `planner.plan(snapshot, runId, scenario, params, hostDir)`；planner 原有校验顺序与拒绝语义不变，空 hostDir 仍是 503/50303。`BenchUnavailableException` 会补上 run/scenario/hostDir 上下文后原类型抛出；`BenchLaunchRejectedException` 原样向上抛，由既有 FAILED + 释放锁路径收口。
- Bean 接线：`LedgerConfig.runLifecycleHook()` 不再是 `none()`，改为注入 `BenchRunLifecycleHook`；依赖方向只有 hook → executor，不存在回环。

## 2. 状态迁移路径（为何不经 VERIFYING）

看门狗与操作员 stop 使用同一套边：

| 判定 | 迁移 | stepKey | 锁 |
|---|---|---|---|
| 容器 `EXITED` 且 exitCode == 0 | RUNNING→STOPPING→COMPLETED | `k6-exit` → `complete` | 第二跳成功且 ledger 已写后释放 |
| 容器 `EXITED` 且 exitCode != 0（含 exitCode 缺失） | RUNNING→FAILED | `k6-exit-failed` | 写成功后释放 |
| 容器 `RUNNING` 且超过截止时间 | RUNNING→FAILED | `k6-timeout` | 写成功后释放 |
| 容器 `ABSENT` | 不迁移 | — | 不动（legacy/契约先行 run 无容器，不能误杀） |
| 其它状态（CREATED/DEAD/…） | 不迁移，仅 debug 日志 | — | 不动 |

RUNNING→COMPLETED 不是合法边，因此自然结束必须经过 STOPPING；VERIFYING 留给 B3 的指标/质量判定，本步不在 VERIFYING 写任何东西。看门狗不读取也不改写 operation 记录：状态迁移与锁仍是 `LedgerWriter` + `EnvLockService` 的唯一职责。

## 3. 超时公式

```
requestedSeconds = Σ value(k)
  k ∈ scenario params 的键，且 k 以 _DURATION / _RAMP_UP / _RAMP_DOWN 结尾
  value = manifest 默认值被 lab_run_param(request) 覆盖后的值
  解析复用 BenchLaunchPlanner.parseDurationSeconds（k6 duration 语法）
无法计算（场景未知/值为空/语法非法/总和 <= 0）→ max-duration-seconds

deadline = (容器 startedAt，缺失时用 lab_run.started_at)
         + ceil(requestedSeconds + timeout-grace-seconds) 秒
clock.instant() > deadline → 超时
```

- load：`LOAD_DURATION` 本身就是总时长（默认 600s）。
- spike：`SPIKE_BASELINE_DURATION + SPIKE_RAMP_UP + SPIKE_DURATION + SPIKE_RAMP_DOWN`（默认 30+10+60+10 = 110s）。
- smoke：`SMOKE_DURATION`（默认 30s）。
- 无 startedAt 时不猜截止时间（不超时），等容器退出或操作员停止。

超时路径的顺序是**先做 RUNNING→FAILED 的 compare-and-set，成功后才 stop+cleanup**。这样若操作员 stop/另一 tick 已推进该 run，本 tick 直接放弃，绝不会先停掉一个已被别人接管的容器；若写入被 ledger 暂存（返回 false），容器也不会被清理，下一 tick 还能重试。迁移失败时容器留给孤儿清理兜底。

## 4. 看门狗调度与孤儿清理时机

- 调度：`BenchExecConfig` 创建 `BenchRunWatchdog`（`@Bean(destroyMethod="stop")`），`start()` 自建单线程 daemon `ScheduledExecutorService`，首 tick 立即执行、之后 `watchdog-interval-seconds` 固定延迟；**没有新增 `@EnableScheduling` / `@Scheduled`**。单测只调 `tick()`，不启线程。
- tick 先检查 ledger 就绪（`lab.ledger.enabled && LedgerBootstrap.isReady()`）；未就绪直接返回并 debug 日志，不读 run 表、不扫孤儿，下一 tick 再试。
- 每个 RUNNING run 独立 try/catch：一个 run 的 Docker/台账异常不会终止整轮，更不会让调度线程死掉。
- 孤儿清理：**台账就绪后的首个成功 tick**（等价于 ApplicationReadyEvent 语义，首 tick 立即执行，未就绪自然顺延）执行一次；`lab.bench.exec.orphan-cleanup-enabled=false` 时跳过。列出 `lab.role=k6` 容器，对每个 `lab.run-id`：run 不存在或状态不是 RUNNING → `stop` + `remove`；run 仍 RUNNING → 保留。单个容器失败（含 ledger 查询失败）不置完成标记，下一 tick 重试；整轮成功（含 0 个容器）后置 `orphanSweepDone`，不再重复扫描。
- **first-verdict-wins**：`LedgerWriter.transition` 抛 `LedgerConflictException` 时记 info 并跳过，不重试、不改锁；返回 false（ledger 暂存）时保留锁，下一 tick 重试。

## 5. 新增配置（三处默认一致）

| yml 键（`lab.bench.exec.*`） | env 键 | 默认 | 用途 |
|---|---|---|---|
| `watchdog-enabled` | `LAB_BENCH_EXEC_WATCHDOG_ENABLED` | `true` | 是否启动看门狗调度线程 |
| `watchdog-interval-seconds` | `LAB_BENCH_EXEC_WATCHDOG_INTERVAL_SECONDS` | `5` | tick 间隔（秒，最小 1） |
| `timeout-grace-seconds` | `LAB_BENCH_EXEC_TIMEOUT_GRACE_SECONDS` | `30` | 请求时长之外的宽限 |
| `orphan-cleanup-enabled` | `LAB_BENCH_EXEC_ORPHAN_CLEANUP_ENABLED` | `true` | 是否在首个成功 tick 清理孤儿 k6 容器 |

四个键同时写入 `application.yml` 占位、`compose.base.yml` 转发、`.env.example` 文档；`test-chaos-env-wiring.sh` 的 `LAB_BENCH_*` 自动发现已覆盖，无需改脚本。三个配置文件保持 CRLF，文档保持 LF。

## 6. 单测

- `BenchRunLifecycleHookTest`（6，无 Spring/DB/Docker）：launch 走真实 resolver+planner，断言 planner 收到宿主目录、`LOAD_DURATION` 被 request 参数覆盖、details 各键；request 组缺失回落 manifest 默认；planner 拒绝（stress CLI-only）原样上抛且不启动容器；宿主目录不可解析时 503 消息含 run/scenario/hostDir；stop 对 ABSENT 幂等；stop 后状态读取失败仍保留 `k6Stopped` 并给 `k6StateError`。
- `BenchRunWatchdogTest`（11，mock 台账/执行器 + 固定时钟）：exit 0 → `k6-exit`+`complete`+释放锁；exit 2 → `k6-exit-failed`+释放锁且不 stop；超时 → `k6-timeout`+stop+cleanup+释放锁；未到期不动；`LedgerConflictException` 跳过且不释放锁；transition false 不释放锁、下一 tick 重试成功；ABSENT 跳过；孤儿清理只 remove 非 RUNNING/不存在 run 的容器且只扫一轮；ledger 未就绪延迟到下一 tick；duration 求和覆盖 load 600s、spike 110s、spike 覆盖 170s、smoke 30s/45s、未知场景与非法值回落。
- `RunLifecycleIT` +3（真实 MySQL）：launch details 进入 start afterSummary；stop details 进入 stop afterSummary；stop 抛异常时 afterSummary 有 `k6StopError` 且 run 仍 COMPLETED/锁已释放；并强化既有失败 launch 用例，断言收尾会调用 `hook.stop`。

## 7. 未做项

- UI 启动/停止/日志/报告入口、`console-smoke.mjs` 与 console 基线核对：B2-02b-2b。
- A01（UI 启动 load 全程无终端）与 A20（停止/超时清理）真机验收：B2-02b-2b 或主会话。
- 真容器退出码、真实超时触发、真实孤儿容器清理、命名卷写入与报告读取均未在真机验证；本步只跑单测与静态接线，未执行任何 docker/compose 命令。
- 控制器重启恢复（RUNNING→UNKNOWN）后的容器处理依赖孤儿清理；未新增“UNKNOWN run 自动关闭”逻辑（沿用 B1 既有操作员路径）。

## 最新 commit

`60ea3a6`（hook 契约 / 真实 hook / watchdog / planner 重载 / 单测）、`045d650`（四个配置键三处外置），本文件随后续 `docs(B2-02)` 提交落盘（hash 见 `git log --oneline -4`）。


## 补充：STOPPING 卡死修正（主会话抽查，2026-10-28）

- **问题**：自然结束走 `RUNNING→STOPPING→COMPLETED` 两跳。若第二跳遇到台账暂存（`transition` 返回 false）或停容器 hook 后进程异常，run 会停在 STOPPING 且继续持有写 run 锁；原看门狗只扫 RUNNING，下一 tick 不会重试，只能等控制器重启标 UNKNOWN。
- **修正**：看门狗新增 STOPPING 扫描。run 在 STOPPING 超过 `LAB_BENCH_EXEC_STOPPING_SETTLE_GRACE_SECONDS`（默认 30s）后，若 k6 容器 ABSENT/EXITED，则 `STOPPING→COMPLETED`（step `complete`，detail 记 `settledFromStopping`/`stoppingAgeSeconds`）并释放锁；容器仍 RUNNING/CREATED 时留给操作员 stop，不抢判。宽限期用于避开正常 stop 窗口（STOPPING 先于停容器写入）。`settle()` 增加 status 守卫，STOPPING 只走新路径。
- **配置**：`LAB_BENCH_EXEC_STOPPING_SETTLE_GRACE_SECONDS` 三处同默认 30（yml/compose/.env.example），env 接线自动发现。
- **测试**：`BenchRunWatchdogTest` 新增 4 条（STOPPING 过宽限完成、宽限内不动、容器仍活不动、第二跳暂存后下一 tick 补完成）；全模块 **584 / 0 / 0 / 0**，env 接线 **91 PASS**。
- **仍未验证**：真实台账暂存窗口下的 STOPPING 恢复（真机 A20 会覆盖常规 stop/超时路径，台账故障注入不在本步）。


## 补充：run 元数据 `note`/`source`（真机 A01 首次启动发现，2026-10-28）

- **问题**：A01 真机用 `{"note":"b2-02-live","LOAD_START_VUS":1,"LOAD_VUS":2,"LOAD_DURATION":"5s"}` 创建 run 后 start 返回 400，run 变 FAILED：planner 把 `note` 当成未知 k6 参数拒绝。`note` 是 run 标签/清理 marker，不是 k6 参数。
- **修正**：planner 增加元数据白名单 `note`/`source`：必须是 ≤200 字符、无控制字符的非空字符串；只留在 `lab_run_param`，**不渲染进 k6 容器环境**。未知参数仍拒绝；强制值仍拒绝。
- **测试**：planner 新增 2 条（元数据被接受且不转发；非法元数据被拒），`BenchLaunchPlannerTest` **27/27**；全模块 **586 / 0 / 0 / 0**。
- **口径**：console-smoke 用 `note=console-smoke` 作为自有 run 的清理 marker（不再依赖非白名单 scenario 前缀）。


## 补充：预检查自我阻断修正（真机 A01 第二次启动发现，2026-10-28）

- **问题**：start 的操作行在 worker 之前插入。预检查 `operations.settled` 只忽略 `REQUESTED` 的自身 start；一旦预检查耗时超过 3s 同步预算，自身操作已翻成 `PENDING_CONFIRMATION`，于是被判为"未结操作"而自我阻断（真机首次表现为 `PrecheckBlockedException: operations.settled (1 unsettled ... op-... start ... PENDING_CONFIRMATION)`，run 停在 DRAFT、operation FAILED）。
- **修正**：新增 `OperationContext`（ThreadLocal），`OperationService` 在 worker 线程绑定当前 operationId；`RunPrecheckService.enforce` 按**精确 operationId** 排除自身（不论状态），GET 报告仍不忽略任何操作；无上下文时保留旧的 `REQUESTED` 启发式作为回退。其他 PENDING_CONFIRMATION/UNKNOWN 兄弟仍阻断。
- **测试**：`RunPrecheckServiceTest` 新增 1 条（自身 PENDING_CONFIRMATION 被忽略、其他 PENDING 仍阻断）；全模块 **587 / 0 / 0 / 0**。
- **口径**：这不是放宽门禁，只是把"自身 in-flight 操作"从"未结兄弟"里按 id 摘出来。


## 补充：B2-02b-2b 控制台 k6 卡与 console-smoke（2026-10-28）

### UI（run 详情「k6 执行」卡）

- 位置：`RunLedger.vue` 的 run 详情之后、预检查之前；根节点 `data-testid="k6-run-card"`。

| 字段 | testid | 说明 |
|---|---|---|
| 状态徽标 | `k6-status` | `ABSENT` 渲染「未启动/已清理」，其余原样 `CREATED/RUNNING/EXITED`，颜色按 exitCode 区分 |
| 容器名 | `k6-container` | 计划容器名（ABSENT 时也有，`jl-k6-<runId>`） |
| scenario | `k6-scenario` | `lab.scenario` 标签 |
| exitCode | `k6-exit-code` | 运行中/ABSENT 为 — |
| started/finished | `k6-started`、`k6-finished` | Docker ISO 时间转本地 |
| 报告 | `k6-report-available`、`k6-report-bytes` | 可用/未生成 + B/KB/MB |
| note | `k6-note` | 后端提示（无容器/报告未生成/超限） |
| 日志 | `k6-log-view`、`k6-log-truncated` | tail=100；`truncated` 显示「已截断」 |

- 轮询：选中 run 立即读一次 `GET /lab/runs/{id}/k6`；RUNNING/STOPPING/VERIFYING/UNKNOWN 或 k6 仍 CREATED/RUNNING 时随 5s `syncTick` 继续；run 进入终态时若 k6 仍是 RUNNING 会再读一次（避免看门狗抢先完成时卡片停在 RUNNING），随后停止；503 错误也不逐 tick 重试；start/stop 动作后强制刷新一次。
- 日志：`k6-log-refresh` 调 `GET …/k6/logs?tail=100`；容器不存在（404）显示「尚未启动或已清理」提示，不当作告警；200 但空文本显示「（k6 暂无输出）」，保证日志区始终可读。
- 报告：`k6-report-download` 仅 `reportAvailable=true` 且不忙时可用；带 `X-Lab-Token` fetch → blob → 临时 `<a download>`；404（未生成）/413（超限）保留原状态语义并显示。
- 失败隔离：503/50303 只在卡内显示「k6 执行层不可用」，不设置全局 DEGRADED、不使用 `role="alert"`，run 详情/预检查/操作/指标不受影响。
- 新建表单：`fetchBenchScenarios` 的 `views.ui` 填 `run-create-scenario-options` datalist，并提示「其它场景 start 会被 400/40002 拒绝，params 只接受 manifest 声明的键」；目录 503 只退化为手动输入提示，不阻塞创建。
- 常驻口径：`App.vue` 的 `steadyContainers` 无条件排除 `jl-k6-` 前缀（即使 `VITE_LAB_ON_DEMAND_CONTAINERS` 显式配置）；`labModel.js` 新增 role `k6`（label「按需压测」、group `load`），`GET /containers` 里的按需 k6 不会抬高常驻计数。

### console-smoke

- 默认 `SMOKE_RUN_SCENARIO=load`；默认 `SMOKE_RUN_PARAMS={"note":"console-smoke","source":"playwright","LOAD_START_VUS":1,"LOAD_VUS":5,"LOAD_DURATION":"30s"}`（均可 env 覆盖）。30s 保证断言期间 run 仍 RUNNING，不被看门狗抢先完成。
- `stopActiveRuns()`：保留旧 scenario 前缀清理；另对 RUNNING/UNKNOWN run 读详情，若 `params` 中 group=request 的 `payload.note` 等于本次 `RUN_PARAMS.note` 且 scenario ∈ `views.ui`（目录不可用时回退 smoke/load/spike）才 stop。只关闭明确带 marker 的 run，用户 run 不动。
- `!blocked` 分支 start 后新增 4 条断言：k6 卡可见且 `RUNNING`、容器名 `jl-k6-` 前缀、刷新后 `k6-log-view` 非空、报告按钮此时禁用（不把 ABSENT 当失败）。stop 后的「k6 不再 RUNNING（EXITED/ABSENT）」并入既有 stop/COMPLETED 断言，保持 67→71 的计数；blocked 环境仍是原来的 4 条（67）。
- 本步未跑 console-smoke（需要真栈 + 重建 dist）；预计非 blocked **71 PASS / 0 FAIL**，真机数字由主会话回写。

### 构建与静态检查

- `code/lab/dashboard`：`npm ci` + `npm run build` 成功（28 modules；JS 196.00 kB / gzip 69.34 kB，CSS 30.18 kB / gzip 5.90 kB）。
- `node --check code/lab/scripts/console-smoke.mjs` 通过；未改 shell。

### 风险（已在主会话修正）

- `note`/`source` 元数据被 planner 当成未知 k6 参数、慢预检查把自身 start 操作算作未结操作，这两个问题在真机 A01 首跑/二跑暴露；已由主会话修正：`fbd9a7b`（run 元数据白名单，只存台账不转发给 k6）、`21bd69b`（`OperationContext` 按 operationId 排除自身 start 操作）。本段 UI/smoke 逻辑按修正后的后端工作。

### 未验证真机项

- console-smoke 的实际 PASS 数、真实 k6 容器 RUNNING→EXITED、日志 tail 与 `summary.json` 下载（含 413）。
- A01（UI 启动 load 全程无终端）与 A20（stop/超时清理）。
- 无 Docker 时 503/50303 的卡内呈现，以及 k6 容器在 `GET /containers` 下不抬高常驻计数。

### 最新 commit

`f1813a1`（UI k6 卡 / views.ui 提示 / 常驻口径）、`e31fa01`（空日志占位）、`c0c3996`（终态再读一次）、`199c343`（console-smoke）、`787e1a8`（基线注释）；本文件随后续 `docs(B2-02)` 提交落盘（hash 见 `git log -1`）。


## 补充：真机 A01 挂载与报告缺陷（2026-10-28）

- **挂载读写反转**：docker-java 的 `AccessMode.fromBoolean(true)` 语义是 **rw**，网关把 plan 的 `readOnly` 直接传进去，导致 k6 容器 `scripts` 变 rw、`reports` 变 ro。真机表现为 k6 日志 `could not open '/reports/<runId>/summary.json': read-only file system`，但 k6 仍 exit 0。修法：`DockerK6ContainerGateway.toBind` 显式 `readOnly ? ro : rw`，并加 2 条 bind 单测。
- **无报告仍判完成**：退出 0 但 summary 缺失时旧看门狗直接 COMPLETED。现在若 manifest 场景 `thresholdsExpected=true` 且 `reportAvailable=false`，转 `RUNNING→FAILED`（step `k6-report-missing`）并释放锁；新增单测。`thresholdsExpected=false`（stress 等）不受影响。
- **测试**：全模块 **590 / 0 / 0 / 0**（新增 bind 2 + 报告缺失 1）。
- **真机复验**：修正后需重跑 A01，确认 k6 容器 `scripts:ro`、`reports:rw`，summary 落盘并可下载。


## 补充：报告目录权限（真机 A01 三跑，2026-10-28）

- **问题**：`grafana/k6` 镜像以 uid **12345** 运行；控制器（root）在共享卷上创建的 `/reports/<runId>` 是 0755，k6 无法写 `summary.json`，且仍 exit 0（无日志报错），看门狗按上一条规则把 run 判 FAILED。
- **修正**：`BenchRunExecutor.start` 创建 run 目录后显式 `setPosixFilePermissions(0777)`；k6 进程保持非 root，只有该 run 的专用目录可写。非 POSIX 文件系统（Windows 单测）忽略该调用。
- **真机复验**：k6 容器 `scripts rw=false`、`reports rw=true`；修正后重跑 A01，确认 summary 落盘与下载。
