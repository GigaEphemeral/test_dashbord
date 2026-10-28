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
> 最新代码提交：`5caadeb`（网关/执行器/端点/解析器 + 单测）、`bd667f3`（yml/compose/.env.example 六个新键 + `jl-k6-reports` 卷挂载）；本文件随 `docs(B2-02)` 提交落盘
> 测试：`mvn -f code/pom.xml -pl services/chaos-controller -am test` → **559 tests, 0 failures, 0 errors, 0 skipped；BUILD SUCCESS**（B2-02a 基线 516，本步 +43：`BenchExecutionControllerTest` 12、`BenchScriptsHostDirResolverTest` 7、`BenchRunExecutorTest` 24）
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
- `BenchRunExecutorTest`（24）：start 建目录+create/start、镜像缺失拒绝（不建目录/不创建）、RUNNING 冲突、EXITED 重建、状态映射、ABSENT/运行中提示、日志夹取与默认值、日志 404、报告读取/缺失/超限、runId 穿越（含手工 plan）、stop 幂等、cleanup stop+remove、sweepOrphans 只返回 k6 清单且不删除。
- `BenchExecutionControllerTest`（12）：ABSENT 200、完整字段、logs 形状与无参默认、报告下载/404/413、40002/40904/50303、日志 404、非数字 tail 400。
- 全部不启 Docker、不启 Spring 上下文、不读库；报告目录用 `@TempDir`。

## 7. 未做项（B2-02b-2）

- `RunLifecycleHook.launch` 接线：从 `lab_run` / `lab_run_param` 取场景与参数、调用 resolver + planner、在 run 进入 RUNNING 后 `start`，失败时更新 run 状态。
- 超时看门狗/自动清理、`sweepOrphans()` 的启动调度与删除策略。
- UI 启动/停止/日志/报告入口；A01（UI 启动 load 全程无终端）与 A20（停止/超时清理）真机验收；console-smoke 基线核对。
- 真容器、命名卷、三个端点的真机验证：本步只跑单测与静态接线，未执行任何 docker/compose 命令。
- `scripts-host-dir` 仍未回填到 `BenchExecProperties`：由 b-2 在调用 planner 前完成，planner 对空值继续 503。

## 最新 commit

`5caadeb`（网关/执行器/端点/解析器 + 单测）、`bd667f3`（六个新键 + `jl-k6-reports` 卷）、本文件随 `docs(B2-02)` 提交落盘（hash 见 `git log --oneline -3`）。
