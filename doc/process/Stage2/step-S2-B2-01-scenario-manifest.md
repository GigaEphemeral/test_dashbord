# 当前进展

> 任务/步骤：Stage2 **B2-01a 场景白名单单一事实源**（manifest + `run.sh`/`bench.sh` 消费 + k6 侧 node 测试 + k6 README）
> 分支：`feature/stage2-workbench`（隔离 worktree `agent-general-purpose-c152c159`，基线 `3bee92b`）
> 隔离自证：`git rev-parse --show-toplevel` → `D:/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-c152c159`，不等于主工作树 `D:/000CODE/JAVA-LEARN`
> 最新代码提交：`e72bf68`（`feat(B2-01)`，manifest + reader + 两个脚本 + 测试）；本文件与 README 随 `docs(B2-01)` 单独提交（hash 见 `git log --oneline -2`）
> 测试：`node --test "code/lab/k6/test/*.test.mjs"` → **100 pass / 0 fail**（基线 79 + 新增 21）；
> `bash -n code/lab/k6/run.sh`、`bash -n code/lab/scripts/bench.sh` 均通过
> 未做：未执行任何 docker/compose 生命周期命令，未跑真实 k6、bench 全流程、Java 构建、dashboard 构建、verify/smoke/console-smoke

基线 `3bee92b` 存在三套互不一致的场景清单：目录 10 个脚本；`run.sh` 接受 9 个（无 `traffic`）；
`bench.sh` 接受 5 个（`smoke|load|stress|spike|soak`）。本步把清单收敛到
`code/lab/k6/scenarios/manifest.json`，两个 shell 入口都通过 `code/lab/k6/lib/scenario-manifest.mjs`
读取，不再各留一份 case 分支；`stress`/`soak` 首期不进 UI 的已确认口径也落到 `views.ui`。

# 完成功能项

## 1. 唯一事实源 `scenarios/manifest.json`

schema 版本用 `version: 1` 表达；10 个脚本每个一条，字段如下：

| 字段 | 含义 | 本步取值 |
|---|---|---|
| `script` | 相对路径 `scenarios/<name>.js` | 10 条，测试断言与目录双向一致 |
| `serviceOnly` | 只由 compose 服务运行 | 仅 `traffic: true` |
| `views`（顶层） | `run` / `bench` / `ui` 三个视图 | run=9、bench=5、ui=3 |
| `requires.chaos` | 需要 chaos-controller 变量与场景 JSON 挂载 | `ha-failover`、`db-failover` |
| `requires.cache` | 需要 cache-lab 内部 API/token 转发 | `cache-lab` |
| `requires.profile` | 所需 compose profile | 默认栈=`dev`；`db-failover`=`db-ha`；`traffic`=`traffic` |
| `writesData` | 是否写业务库/消耗库存 | `smoke`/`spike`/`db-failover`/`cache-lab` 为 true，其余 false |
| `thresholdsExpected` | bench 归档是否预期 k6 阈值 | 仅 `stress` 为 false（脚本故意无 threshold） |
| `env` | 该场景接受的变量名 | 与 `run.sh` 转发清单双向一致（见 §3） |
| `params` | 参数默认值 | 与 `lib/config.js`/场景模块实际默认逐键相等（测试钉死） |

顶层另有 `runtimeEnv`（3 个 `K6_PROMETHEUS_RW_*`，runner 级而非场景参数）与
`forwarding.common/chaos/cache/service`（前三者镜像 `run.sh` 的三个静态数组，`service` 镜像 compose
`traffic` 服务的变量）。

## 2. 读取器 `lib/scenario-manifest.mjs`

纯 node（无 jq、无 npm 依赖），默认路径 `scenarios/manifest.json`，可用 `LAB_SCENARIOS_MANIFEST` 覆盖。
CLI：`validate`、`list run|bench|ui`、`check-run <name>`、`check-bench <name>`；退出码
`0` 通过、`2` 用法/manifest 错误、`3` 未知场景、`4` 已知但不在该视图、`5` service-only。
`validate` 强制：`version` 正整数；三视图成员存在且 `ui ⊆ bench ⊆ run`；serviceOnly 不进任何视图；
非 serviceOnly 必须在 `run`；`params` 键必须出现在 `env`；`env` 必须是被 `forwarding`/`runtimeEnv`
转发的名字；每个转发名至少被一个场景或 `runtimeEnv` 消费（双向无孤儿）。

## 3. `run.sh` 消费（`code/lab/k6/run.sh:46,58-83`）

- 删除 `smoke|load|...` 的 case 分支；`node lib/scenario-manifest.mjs check-run "$SCENARIO"` 返回
  `<chaos> <cache>` 两个 0/1 标志，驱动原有的 `IS_CHAOS`/`IS_CACHE` 分支（挂载与转发表不变）。
- 未知场景 → 退出码 2，错误信息含实际场景名与 `run` 视图清单；`traffic` → 退出码 2，明确
  “service-only background load: it runs as the compose traffic service (profile "traffic")”。
- 三个静态数组（common/chaos/cache）保留原样，只由测试与 manifest 双向比对，避免改行为。
- 新增 node 依赖：manifest 读取在场景文件检查之前；缺 node/reader/manifest 都是退出码 2 + 明确提示。
- `K6_DIR` 解析顺序保持“未知场景先于场景文件检查”，已支持场景的挂载、打印行、退出码不变。

## 4. `bench.sh` 消费（`code/lab/scripts/bench.sh:30-92,214-234`）

- 删除 `smoke|load|stress|spike|soak` 的 case 分支；`check-bench` 拒绝未知/非 bench/service-only，
  全部映射退出码 2，原因文本由读取器统一生成。
- `usage` 的 `<...>` 列表由 `list bench` 生成（`@BENCH_SCENARIOS@` 占位后 sed 注入），其余帮助文本不变。
- `thresholds_expected` 由 `thresholdsExpected` 取代原来的 `if [[ "$SCENARIO" == "stress" ]]` 特例，
  `stress` 仍为 false，其余仍为 true。
- `--run-id`、`--no-reset`、`--` 覆盖、保留变量正则、archive 冲突检查、退出码全部未改动。

## 5. 测试（`code/lab/k6/test/scenario-manifest.test.mjs`，21 个用例）

| 组 | 用例 |
|---|---|
| 目录↔manifest | 每个 `.js` 恰有一条、每条 `script` 存在、三视图与目录脚本集合一致 |
| 视图口径 | run/bench/ui 精确集合；stress/soak 不在 ui；traffic 不在任何视图；d7/ha/db/cache 不在 ui |
| serviceOnly | 仅 traffic；profile=traffic；非 serviceOnly 都在 run |
| params | 与 `lib/config.js`/`chaosConfig`/`cacheLabConfig`/`dbFailoverConfig` 的默认值逐键 deepEqual；params 键 ⊆ env |
| env | `run.sh` 三个数组与 `forwarding.common/chaos/cache` 双向相等；runtimeEnv ⊆ common；场景 env 与转发并集双向相等；chaos/cache/service 分组不串场 |
| reader | `validate`/`check-run`/`check-bench` 返回值；四类篡改（ui 越界、service-only 入 run、params 未声明、env 删项）被拒 |
| 脚本 mock | run.sh 未知场景→2；run.sh traffic→2 + 提示；run.sh d7 走到镜像检查；bench.sh 未知→2；bench.sh d7→2“not a bench scenario”；bench 5 场景（含 stress/soak）全部通过校验走到镜像检查；`--help` 首行与 bench 视图一致 |

脚本级 mock 用临时目录里的假 `docker`（`image inspect` 返回 1）截断在“镜像不存在”分支，断言的是
“白名单校验通过后才会走到下一步”，不会触碰真实 Docker。

同时更新了既有 `test/cache-lab-scenario.test.mjs` 中“run.sh 白名单”用例：原断言钉住已被删除的
`ha-failover|db-failover) IS_CHAOS=1` 文本，现改为断言读取器接线 + manifest 中两条 `requires.chaos`
与 `cache-lab.requires.cache`，未降低覆盖面。

## 6. 文档

`code/lab/k6/README.md`：新增 “Scenario whitelist (single source of truth, Stage2 B2-01)” 小节
（三视图表、traffic 特例、stress/soak 不进 UI 的口径与理由、manifest 字段说明、覆盖变量），
Layout 增补 manifest/reader/test，环境变量表增补 `LAB_SCENARIOS_MANIFEST`/`K6_MANIFEST_TOOL`。

## 7. 视图与证据

| 视图 | 成员 | 证据 |
|---|---|---|
| `run`（9） | smoke, load, stress, spike, soak, d7, ha-failover, db-failover, cache-lab | 基线 `run.sh:49-57` 的 case 清单（9 个，不含 traffic） |
| `bench`（5） | smoke, load, stress, spike, soak | 基线 `bench.sh:174-181` 的 case 清单；`reports/<run_id>/` 归档口径 |
| `ui`（3） | smoke, load, spike | 设计 §7.1“经验证的白名单场景”；§15 与验收计划 #4：`stress`/`soak` 首期不进 UI、CLI 保留 |
| 不在 ui | d7 / ha-failover / db-failover / cache-lab | 分别由双 JDK 对照、故障时间线、DB 演练、缓存实验室面板/CLI 触发（README 各自小节） |
| serviceOnly | traffic | `compose.base.yml:619` `profiles: ["traffic"]`；`traffic.js` 头注释“compose service traffic” |

`requires.profile` 取默认演练所需：`ha-failover` 默认 `ha-app-failover.json` 目标是 app-1/app-2（默认
16 容器栈，记 `dev`）；`db-failover` 要求 app 走 MySQL Router（`db-ha` 覆盖层）；`traffic` 为
`traffic` profile。README 也记录了两个“非默认”变体：`ha-failover` + `redis-sentinel-failover.json`
需要 `--profile ha`，`d7` 指向 JDK 8 外壳需要 `--profile dual-jdk`——它们不是默认演练，未写进
`requires.profile`（见“待定事项”）。

## 8. writesData 证据（脚本代码行）

| 场景 | 值 | 证据 |
|---|---|---|
| smoke | true | `scenarios/smoke.js:36` `order({ replay: true });`、`:37` `seckill();`（每次 1 单 + 1 次秒杀） |
| spike | true | `scenarios/spike.js:32` `seckill();`，文件头注明可能耗尽 `SECKILL_PRODUCT_ID` 余量 |
| db-failover | true | `scenarios/db-failover.js:231` `exec: 'writeOrder'`，`:273` 起 `writeOrder()` 创建幂等订单；README 说明 3m ≈ 85 单 |
| cache-lab | true | `scenarios/cache-lab.js:549-552` `setup()` `POST /lab/cache/seed`（写实验数据集） |
| load/stress/soak/d7/traffic | false | `load.js:31-32`、`stress.js:25-26`、`soak.js:28-29`、`d7.js:45`、`traffic.js:31-32` 仅 `product()` 只读 |
| ha-failover | false | `ha-failover.js:67-68` 负载仅 `product()`；chaos 只停/冻容器，不写业务库 |

# 设计逻辑

1. **清单只读一次，错误文案归读取器**。`run.sh`/`bench.sh` 只负责把任何拒绝映射成退出码 2，
   避免两处再各写一份“expected ...”清单；未知场景、traffic 特例、非 bench 场景的文本都来自
   manifest + 读取器，新增场景时无需改 shell。
2. **视图表达“谁可以跑”，不表达“脚本存在”**。`run` 是 CLI 可执行集合，`bench` 是带阈值判定/归档的
   子集，`ui` 是“已验证且 UI 可启动”的子集；`traffic` 是 `serviceOnly`，三者都不进。
   `ui` 不追求等于 `run`：`stress`/`soak` 的排除是已确认决策，而不是遗漏。
3. **参数默认值必须可证伪**。manifest 的 `params` 不抄 README，直接与 `lib/config.js`（以及
   `chaosConfig`/`cacheLabConfig`/`dbFailoverConfig`）逐键 deepEqual；`LOAD_*` 比例从派生 stages
   反推，`SPIKE_*` 从 `config.spike.stages` 反推，避免测试里再抄一份魔法数。
4. **env 双向一致、分组不串场**。`forwarding` 镜像 `run.sh` 三个数组；每个场景 `env` 只能取自己
   family 的组（chaos 场景不得带 `CACHE_LAB_*`，反之亦然），`traffic` 只取 compose 服务实际转发的
   7 个变量；`params` 键必须同时是 `env` 名，保证“manifest 里的默认值都能被环境覆盖”。
5. **不碰行为**。转发数组、`IS_CHAOS`/`IS_CACHE` 分支、bench 的 `--` 覆盖与保留变量、退出码语义
   全部保留；新增的只有读取器调用与错误文本，配置路径全部可覆盖（`LAB_SCENARIOS_MANIFEST`、
   `K6_MANIFEST_TOOL`、`BENCH_MANIFEST_TOOL`）。

# 存在缺陷项&TODO

1. **B2-01b 未做**：后端 `GET /lab/bench/scenarios`（返回 manifest 投影）与 UI 场景列表/参数表单、
   受控 k6 执行器均未实现；本步只产出单一事实源与 CLI 消费。验收口径“UI 场景列表 = CLI 场景列表 =
   目录内容”目前体现为“目录每个脚本都在 manifest 中有归属”，UI 侧仍是 3 个验证场景（stress/soak
   按确认结论排除），字面相等要等 B2-01b 用 `views.ui` 落地后再核。
2. **controller 镜像不含 `code/lab/**`**：B2-01b 若让后端直接读 manifest，必须把文件打进
   controller 资源或显式挂载（否则镜像内无此文件）。
3. **`cache-lab.js:37` 注释漂移**：头注释称“writes 1000 experiment rows”，而
   `CACHE_LAB_SEED_COUNT` 默认 200（`cache-lab.js:295`）。本步范围未改场景脚本，登记待 B3 缓存步骤
   一并订正。
4. **profile 表达力不足**：`requires.profile` 是单值，`ha-failover` 的 Sentinel 变体（需 `ha`）与
   `d7` 的 JDK 8 变体（需 `dual-jdk`）只能写在 README/本文件；若 B2 执行器要按变体选 profile，
   需扩展为 `profiles` 数组（本次刻意不扩 schema）。
5. **`tune.sh` 仍有自己的 `smoke|load` 清单**（`code/lab/scripts/tune.sh:542-543`）。它不属于本次
   “三处清单”（目录/run.sh/bench.sh），且是调参工具的内部子集，未改；若后续要彻底单一事实源，
   应改为查 `views.run` 的子集口径。
6. **`bench-report.js` 仍拼 `scenarios/<scenario>.js`**（`bench-report.js:363`）。manifest 的
   `script` 字段已保证同名约定且测试钉住，但归档脚本尚未从 manifest 取值；B2-01b/02 可一并切换。
7. **真机未验证**：Docker 镜像/网络、真实 k6 运行、bench 归档、compose traffic 服务生命周期均未
   执行；脚本级测试用假 `docker` 只证明“校验通过后走到镜像检查”。

# 待定事项和注意事项

1. **`profile` 语义**：`dev` 指默认 16 容器栈（base+dev），不是 compose 的 profile 选择器；
   `db-ha`/`traffic` 是覆盖层/profile。`ha` 值在枚举里保留但当前未使用——默认 `ha-failover`
   演练走 dev 栈，Sentinel 变体见 §7。B2-01b 若需展示“所需 profile”，请按默认演练口径呈现。
2. **新增场景的固定动作**：加 `scenarios/<name>.js` → 加 manifest 条目（`script`/`serviceOnly`/
   `requires`/`writesData`/`thresholdsExpected`/`env`/`params`）→ 若要 CLI/bench/UI 可用，分别加入
   `views.run`/`views.bench`/`views.ui` → `node code/lab/k6/lib/scenario-manifest.mjs validate` →
   `node --test "code/lab/k6/test/*.test.mjs"`。测试会拒绝“目录有脚本但 manifest 无条目”。
3. **`stress`/`soak` 口径别再改回去**：二者在 `run`+`bench`，不在 `ui`；将来真机跑满 14m/30m 后
   再按 §15 恢复条件评审迁入 `ui`，届时只改 `views.ui` 与验收，不动 `run`/`bench`。
4. **覆盖变量**：`LAB_SCENARIOS_MANIFEST` 同时被 `run.sh`/`bench.sh` 读取；`K6_MANIFEST_TOOL`、
   `BENCH_MANIFEST_TOOL` 只覆盖读取器位置（便于宿主侧复制）。三者都未写入任何 compose 文件。
5. **`traffic` 的 env 口径**：manifest 只列 compose `traffic.environment` 实际转发的 7 个变量
   （`TZ` 不算 k6 变量）；手工 `run.sh` 不会运行 traffic，`TRAFFIC_*` 也只出现在
   `forwarding.service`，不进 `run.sh` 的 common 数组。
6. **参数默认值的唯一权威仍是 `lib/config.js`/场景模块**：manifest 是镜像，测试逐键钉死；调整默认值
   时必须同时改两处并跑测试，否则 `manifest params equal ...` 会失败。
