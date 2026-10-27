> 步骤交接 · M2-4a 场景 JSON 与 k6 故障时间线
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / `feat: add chaos scenario timeline for k6`（父提交 `e388483`）
> 关联任务：#22（M2-4，本步 22a）
> 测试 / verify：`node --check` 3 个 JS 全过；Node schema 校验两个场景通过 + 7 个反例全部拒绝；`bash -n run.sh` 通过；真实 k6 验收 `chaos-quick-20261026160758` exit 0、185 请求 / 183 checks 全过、`chaos_events=1 accepted`、teardown `stop-all` 已调用；另做一次 10s 短跑证明 `stop-all reverted=1`
> 运行状态：16 个 lab 容器运行中；app-3 两次 kill 后均已恢复 healthy，`/chaos/actions` 为空；MySQL/Redis/RabbitMQ 与非 lab 容器未触碰
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

# 当前进展

- Task 22a 完成：新增共享场景目录 `code/lab/scenarios/`（schema v1 README + `ha-app-failover.json` + `quick-failover.json`），新增 k6 驱动 `lib/chaos-schema.js`、`lib/chaos.js`、`scenarios/ha-failover.js`；`run.sh` 只做最小扩展（挂载场景目录 + 转发 `LAB_TOKEN`/`CHAOS_*`，新增 `ha-failover` 场景名）。
- 未改应用 Java、操作台 UI、guide 状态机、`verify.sh`、dashboard；未提交 `.env`/token；无 k6 指标改名，既有五个场景行为不变。
- 真实验收只用 `quick-failover.json` 打 `jl-app-3`：kill 5s / TTL 15s；30s、6 VU 产品读全程 200，app-3 在 08:08:07Z 进入 exited、08:08:20Z 左右 TTL 自动 start、08:09:13Z healthy；第二次 10s 短跑在 teardown 时仍有 active kill，`stop-all` 返回 `reverted=1 failed=0`，随后 `/chaos/actions` 为空。
- 提交：`d73e938 feat: add chaos scenario timeline for k6`（8 files，+841/-7）。
- 未完成：Task 22b（向导状态机/UI）不在本步范围；`durationHintSeconds` 与 `CHAOS_DURATION` 只做告警不强制。

# 完成功能项

## 1. 共享场景 JSON（`code/lab/scenarios/`）

- `README.md`：schema v1 全字段语义、动作行为表、校验规则、安全边界、k6 挂载/读取方式。
- `ha-app-failover.json`：演示场景（240s hint）：60s `kill jl-app-1` TTL 60、120s `freeze jl-app-2` TTL 30、150s `resume jl-app-2`；带中文 `title`/`text` 供后续操作台使用。
- `quick-failover.json`：验收场景：5s `kill jl-app-3` TTL 15，只含 app-3，无 datastore。
- 字段：`schemaVersion`、`id`、`name`、`description`、`durationHintSeconds`、`events[{atSeconds,action,target,ttlSeconds,title,text}]`。

## 2. 校验与客户端（`code/lab/k6/lib/`）

- `chaos-schema.js`（纯 JS，无 k6 import，可被 Node 直接测试）：版本、id/name、`durationHintSeconds`、事件非空且按 `atSeconds` 非降序、偏移为正、动作白名单、`(atSeconds,target)` 不重复、kill/freeze 必填 TTL 且为正整数并受上限约束、一次性动作禁止 TTL、target 正则白名单 + datastore 显式拒绝（`mysql|redis|rabbit|mongo|postgres`）。返回冻结的规范化对象。
- `chaos.js`：环境校验（`CHAOS_CONTROLLER_URL` 绝对 URL、`LAB_TOKEN` 必填且不打印）、`CHAOS_SCENARIO_DIR/FILE` 解析、`CHAOS_HTTP_TIMEOUT`/`CHAOS_TEARDOWN_TIMEOUT`、`CHAOS_START_OFFSET`、`CHAOS_MAX_TTL`、`CHAOS_TARGET_PATTERN`、`CHAOS_DURATION`（兼容 `DURATION`）、`CHAOS_VUS`；`loadScenario()` 在 init 阶段用 k6 `open()` 读取并校验；`action(baseUrl,token,event,timeout)` POST `/chaos/instances/{target}/actions`，`X-Lab-Token`，期望 200/202，非 2xx 抛错；`stopAll(baseUrl,token,timeout)` POST `/chaos/stop-all`，期望 200；`Counter('chaos_events')` 打 `{action,target,result=accepted|failed}`，`run_id` 由全局 tags 注入。

## 3. k6 时间线场景（`code/lab/k6/scenarios/ha-failover.js`）

- 两个并行 scenario：`load`（constant-vus，`CHAOS_VUS` 默认 20，`CHAOS_DURATION` 默认 4m，产品读 `flow.product`）；`chaos`（1 VU / 1 iteration，`exec:'chaosTimeline'`，`startTime=CHAOS_START_OFFSET` 默认 0s）。
- 时间线按 `atSeconds` 相对本 VU 启动时间 sleep，依次调用 action；成功记日志与 accepted，失败记 failed、停止剩余事件；结束打印 `applied=n/total`。
- 阈值沿用 Task 19：`flow_errors`/`server_errors < ERROR_RATE`，产品读 `P95_MS`/`P99_MS`。
- `teardown()` 无论时间线是否失败都调用 `stop-all`（best-effort），日志输出 `reverted/failed`，异常只记录不抛出。

## 4. Runner 与文档

- `run.sh`：新增 `K6_SCENARIOS_DIR`（默认 `code/lab/scenarios`）/`K6_SCENARIOS_MOUNT`（默认 `/chaos-scenarios`）只读挂载；转发 `LAB_TOKEN`、`CHAOS_CONTROLLER_URL`、`CHAOS_SCENARIO_FILE/DIR`、`CHAOS_START_OFFSET`、`CHAOS_HTTP_TIMEOUT`、`CHAOS_TEARDOWN_TIMEOUT`、`CHAOS_MAX_TTL`、`CHAOS_TARGET_PATTERN`、`CHAOS_DURATION/VUS`、`DURATION`；`ha-failover` 加入允许列表；只打印 controller URL/场景名/offset，从不打印 token；既有场景的挂载、输出与退出码逻辑不变。
- `code/lab/k6/README.md`：新增 “Chaos timeline (M2-4a)” 章节（命令、期望动作、teardown、`chaos_events` 查询、env 表、安全规则），布局与场景表更新。

# 设计逻辑

- **场景是数据**：JSON 同时服务 k6 与后续向导/操作台，schema 校验放在无 k6 依赖的纯模块，Node 与 k6 共用同一套规则；k6 只负责时序与 HTTP。
- **init 期读取**：k6 的 `open()` 只能在 init 上下文使用，因此 `loadScenario()` 在模块初始化时执行，错误在开始打流前暴露；时间线 VU 只消费已校验对象并排序。
- **挂载位置偏离 brief 的原因**：brief 建议挂到 `/scripts/scenarios`，但 `/scripts` 本身是只读挂载，Docker 无法在其中创建嵌套挂载点（实测 `mount ... /scripts/chaos-scenarios: read-only file system`），且该路径已是 k6 JS 场景目录，覆盖会破坏 `scenarios/*.js`。最终挂载到根级 `/chaos-scenarios`，保持只读、可配置，并在 README/场景 README 中写明原因。
- **配置全外置**：controller URL、token、场景文件/目录、超时、起始偏移、TTL 上限、目标正则、负载时长/VU 全部 env 可覆盖；代码只保留文档化默认值；token 不进标签、不打印、不落盘。
- **安全边界双层**：schema 默认 target 正则 `^jl-app-[0-9]+$` + datastore 显式拒绝；controller 仍执行 token 校验与 `lab=java-learn` 白名单；`kill` 只是 Docker stop，不删卷。
- **teardown 与 TTL 双保险**：每个 kill/freeze 都有 TTL；k6 `teardown` 再调 `stop-all`。时间线失败只停止后续事件，不跳过 teardown。
- **负载与故障解耦**：产品读保持 constant-vus，故障时间线独立 VU，避免故障动作影响施压节奏；controller 调用单独打 `flow=chaos`，不进入产品阈值。
- **短样本阈值取舍**：验收主跑 30s/6 VU（185 请求）通过；10s/2 VU 的 teardown 证明跑因一次约 2s 的 Nginx 故障切换慢请求把 p95 抬到 2007ms 而 exit 99，属小样本统计效应，不是数据面失败（flow/server error 均 0），已在下方记录。

# 存在缺陷项&TODO

- **短时间线 + 小样本会误触 P95/P99 阈值**：故障瞬间 Nginx 对被杀实例的请求可能等约 2s 后切换上游；请求数少时 p95 直接被单个慢请求拉高（10s/2 VU 实测 p95=2007ms → exit 99）。演示/验收建议 `CHAOS_DURATION >= 60s` 且 `CHAOS_VUS >= 6`，或按场景放宽 `P95_MS/P99_MS`。30s/6 VU 实测 p95=8.40ms、p99=253.40ms，阈值通过。
- **硬杀 k6 不会执行 teardown**：Ctrl+C/容器被杀时 `stop-all` 可能没发出；此时只能依赖 kill/freeze 的 TTL 与看门狗，或人工 `POST /chaos/stop-all`。
- **时间线失败即停止**：第一个 action 失败后剩余事件不再执行，没有逐事件重试/补偿；如需“继续后续独立目标”应在 22b 定义策略。
- **`durationHintSeconds` 仅提示**：与 `CHAOS_DURATION` 不一致时只 `console.warn`（每个 VU init 各打一次，日志会重复），不阻断；是否强制由向导/操作台决定。
- **schema 不校验容器存在性/当前状态**：存在性、lab 标签、409 冲突仍由 controller 运行时判定；预检无法发现“容器名写错但格式合法”。
- **teardown 结果只进 k6 日志**：`stop-all` 的 `reverted/failed` 没有单独 Counter；需要在 Prometheus 长期观察时应查 controller 的 `chaos_reverts_total`/`chaos_actions_total`。
- **挂载路径与 brief 不一致**：`/chaos-scenarios` 而非 `/scripts/scenarios`，原因见上；如后续 k6 镜像支持在只读挂载内建子挂载，可再评估。
- **controller 内存态限制沿用 Task 21**：控制器重启会丢失 active 清单；本任务未做 reconciliation。

# 待定事项和注意事项

- 下一步 Task 22b：向导状态机与操作台读取同一份场景 JSON；不要复制 schema 逻辑，直接复用 `lib/chaos-schema.js` 的字段语义（或 Java 侧实现等价校验）。
- 演示默认 `ha-app-failover.json` 会停 app-1、冻结 app-2；只有明确演示时才运行。日常/验收使用 `CHAOS_SCENARIO_FILE=quick-failover.json`（仅 app-3）。
- `LAB_TOKEN` 从 `code/lab/docker/.env` 读取并通过环境变量传入，不写命令行参数、不提交；`run.sh` 不打印 token。
- 手动恢复：`POST /lab-api/chaos/stop-all`（带 `X-Lab-Token`）；若 controller 重启丢清单，用 `docker start jl-app-N` / `docker kill -s SIGCONT`。
- `bench.sh` 预检要求全部 compose 服务健康，故障期间容器会暂时不健康；如需 bench + chaos 组合，用 `BENCH_REQUIRED_SERVICES` 收窄（Task 20a 既有能力）。
- 栈保持运行；app-3 已恢复 healthy，`/chaos/actions` 为空；未触碰 MySQL/Redis/RabbitMQ 及非 lab 容器。

# 附录

## 关键文件

- `code/lab/scenarios/README.md`
- `code/lab/scenarios/ha-app-failover.json`
- `code/lab/scenarios/quick-failover.json`
- `code/lab/k6/lib/chaos-schema.js`
- `code/lab/k6/lib/chaos.js`
- `code/lab/k6/scenarios/ha-failover.js`
- `code/lab/k6/run.sh`
- `code/lab/k6/README.md`

## 复现命令

```bash
cd /d/000CODE/JAVA-LEARN
node --check code/lab/k6/lib/chaos.js
node --check code/lab/k6/lib/chaos-schema.js
node --check code/lab/k6/scenarios/ha-failover.js
bash -n code/lab/k6/run.sh

TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
LAB_TOKEN="$TOKEN" CHAOS_SCENARIO_FILE=quick-failover.json \
  CHAOS_DURATION=30s CHAOS_VUS=6 \
  bash code/lab/k6/run.sh ha-failover

curl -s -H "X-Lab-Token: $TOKEN" http://127.0.0.1:8080/lab-api/chaos/actions
docker inspect -f '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{end}}' jl-app-3
```

## 证据

- 证据目录（gitignored）：`.superpowers/sdd/ha-lab/task-22a-evidence/`
  - `quick-failover-k6.log`：主验收 run `chaos-quick-20261026160758`，exit 0，185 http_reqs / 183 checks 全过，产品 p95 8.40ms / p99 253.40ms / max 2003.72ms，`chaos_events=1`，`stop-all reverted=0`。
  - `quick-failover-samples.log`：2s 采样显示 app-3 08:08:07Z–08:08:19Z `exited`、Nginx 产品读每轮 200、`/chaos/actions` 在 TTL 前保持 active、08:08:22Z 起 `starting`、08:09:13Z `healthy`、actions 为空。
  - `quick-failover-teardown.log`：`chaos-teardown-20261026160931`，10s/2 VU，`stop-all reverted=1 failed=0`，紧接着 `/chaos/actions` 为空、app-3 恢复 `running starting` 后 healthy；该短跑因单次约 2s 慢请求 p95 超阈值 exit 99（flow/server error 0）。
- Prometheus：`k6_chaos_events_total{action="kill",result="accepted",run_id="chaos-quick-20261026160758",scenario="chaos",target="jl-app-3"} 1`。
- 最终栈：16 个 `lab=java-learn` 容器 running/healthy，app-3 healthy，`/chaos/actions` 为空。

# 审核修复（2026-10-26 独立评审）

评审结论：实现与 brief 一致，live 证据成立；修复 4 个 Medium、3 个 Low，提交
`d35927b` / `15d7133` / `90f7ff5`（均在隔离 worktree，待合并）。

- `lib/chaos.js`：`postJson` 改为使用调用方传入的 `baseUrl`（原先 `action()` 的
  baseUrl 参数被忽略、固定用模块级 URL）；成功状态显式为 action=200/202、
  stop-all=200（与 controller 契约一致）；`CHAOS_START_OFFSET` 的零值形式
  （`0`/`0.0`/`00`）统一规范化为 k6 可接受的 `0s`。
- `scenarios/ha-failover.js`：负载窗口告警从模块 init 移入 `chaosTimeline()`，
  只打印一次（原先每个 VU init 与 teardown 上下文重复打印，证据日志中可见
  6 次重复），并且比较 `loadDuration - CHAOS_START_OFFSET` 的有效窗口，
  offset >= load 时给出专门提示。
- `run.sh`：chaos 场景目录检查、`/chaos-scenarios` 只读挂载、`LAB_TOKEN`/
  `CHAOS_*` 转发与 `scenarios` 打印行全部限定为 `SCENARIO=ha-failover`；
  其余五个场景的挂载、输出、环境与退出码恢复为 22a 之前的原状，
  `K6_FORWARD_ENV` 语义不变。
- 新增可复跑回归测试 `code/lab/k6/test/chaos-schema.test.mjs`（brief 要求的
  Node schema 验证，18 个用例：两个真实场景 + 乱序/0 偏移/重复
  `(atSeconds,target)`/未知动作/一次性动作带 TTL/kill 缺 TTL/TTL 上限/放宽
  pattern 后 datastore 仍拒/冻结返回/稳定排序等）：
  `node --test code/lab/k6/test/chaos-schema.test.mjs`。
- 文档：`scenarios/README.md` 明确 `id` 的 `[a-z0-9-]` 是约定而非校验项；
  `k6/README.md` 记录挂载/转发的 ha-failover 限定范围、零值 offset 规范化与
  测试命令。

复验输出：`node --check` 三个 JS 通过；`bash -n run.sh` 通过；
`node --test` 18/18 通过。既有 live 证据（`quick-failover-*` 日志）不受影响，
本次未运行任何 docker/k6/真实压测。
