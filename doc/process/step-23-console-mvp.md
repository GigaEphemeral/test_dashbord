> 步骤交接 · M2-5 实验操作台 MVP（向导 / 状态栏 / 背景流量 / Playwright 冒烟）
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / 本次提交 `feat: add lab console wizard and background traffic`（父提交 `e72b7c5`）
> 关联任务：#23（M2-5，含 23a 控制器契约 / 23b 背景流量与 compose / 23c 操作台前端 / 23d 冒烟验收）
> 测试 / verify：全 reactor **272 tests, 0 failures / BUILD SUCCESS**（chaos-controller **114**、app-jdk21 131、seckill-core 13、platform 14）；k6 Node **37 pass / 0 fail**（原 31）；`console-smoke` **26 PASS / 0 FAIL**；`verify.sh` 本步未复跑（最近记录仍是 Task 22b 的 97 PASS / 1 WARN）
> 运行状态：栈保持运行；`/lab-api/containers` 实测 17 个 lab 容器（16 常驻 running + `jl-traffic` exited）；`/lab/guide/state` 为 idle、`steps: []`、`resetMode: manual`；`/lab/traffic/state` 为 `exited` / `running:false`；冒烟全程只 `skip`，未注入任何故障，app-3 未受影响；`/chaos/actions` 为空

# 当前进展

- Task 23 完成，M2 的"操作台 MVP"落地：实验操作台从只读容器/日志面板升级为**场景向导 + 环境状态栏 + 按需背景流量 + 只读日志**，直接消费 `/lab/guide/**`、`/lab/traffic/**`、`/chaos/actions`、`/containers`、`/logs/{name}`。
- 后端契约扩展（23a）：新增 `POST /lab/guide/skip`；`GET /lab/guide/state` 改为结构化 `steps` 数组并透出 `resetMode`，删除 `currentStepTitle`/`currentStepText`；场景 schema v1 新增可选 `events[].explain`（现象/根因/处方/预期曲线）；新增 `chaos.reset-mode`（`manual`/`auto`）；新增背景流量 API `/lab/traffic/state|start|stop`。
- 背景流量（23b）：`code/lab/k6/scenarios/traffic.js` + compose `traffic` 服务（profile 门控、`jl-traffic`、只读挂载 k6 目录）；`up.sh` 只创建不启动、`down.sh` 两条分支都带 profile、`bench.sh` 加"正在打流量就拒绝归档"的护栏。
- 前端（23c）：`src/api.js` 补齐 guide/traffic/faults 调用；新组件 `src/components/ScenarioWizard.vue`；`App.vue` 加状态栏与 5s 自动轮询；`style.css` 加状态栏/pill/wizard 样式；`vite.config.js` 加 dev `/lab-api` 代理。
- 冒烟与文档（23d/E）：`code/lab/scripts/console-smoke.mjs` + `console-smoke.sh`（Playwright 真浏览器，26 项断言）；`lib/chaos-schema.js` 与 Java 侧镜像实现 `EXPLAIN_FIELDS`/`optionalExplain`；`code/lab/k6/test/traffic-scenario.test.mjs` 新增；两份 README 与两个场景 JSON 补齐 `explain`。
- 未做：`verify.sh` 未复跑（本步未改其业务断言，仅 profile 计数需保持 16，已实测 `compose config --services` 仍为 16）；操作台没有做"按时间自动播放"，`atSeconds` 仍只用于展示。

# 完成功能项

## 1. 向导契约扩展（`guide/GuideController`、`guide/GuideService`）

- `POST /lab/guide/skip` → `Result<GuideStateView>`：推进 `currentStep` 但**不调用** `ChaosActionService`，日志记录被跳过的事件；仅 RUNNING 可用，非法状态抛 `GuideConflictException`（409）。到末步同样置 COMPLETED，与 `step` 共用 `advance()`，因此 COMPLETED/FAILED 语义完全一致（`GuideStateMachineTest.skipMovesPastAnEventWithoutExecutingIt` 断言 `verify(chaos, never()).execute(...)`）。
- `GET /lab/guide/state` 响应字段：`scenarioId`、`scenarioName`、`status`、`currentStep`、`totalSteps`、`steps[]`、`activeActions`、`lastError`、`resetMode`；IDLE 时 `steps` 为 `[]`（record 构造里 `List.copyOf` 归一空值）。旧的 `currentStepTitle`/`currentStepText` 两个扁平字段已删除，前端改为用 `steps[currentStep]` 自行取当前步。
- 新增 `guide/GuideStepView`（`atSeconds/action/target/ttlSeconds/title/text/explain`）与 `guide/GuideStepExplain`（`symptom/rootCause/fix/expectation`）；`GuideEvent` 增加第 7 个分量 `explain`。

## 2. 场景 schema v1 的 `explain` 块（`GuideScenarioLoader` + `lib/chaos-schema.js`）

- Java `optionalExplain(source, label, node)`：缺字段/`null` → 不报错；非对象 → `InvalidScenarioException`（400，"explain must be an object when present"）；四个字段各自存在时必须是非空字符串并 `trim()`；**四项全空视为不存在**（返回 null）。
- JS `chaos-schema.js` 同步新增 `EXPLAIN_FIELDS = ['symptom','rootCause','fix','expectation']` 与 `optionalExplain()`，空块归 `null`、`Object.freeze` 结果、非对象抛错——与 Java 侧同语义（两份独立实现，字段规则镜像）。
- `code/lab/scenarios/quick-failover.json`（1 事件）与 `ha-app-failover.json`（3 事件，60/120/150s）每个事件都补了 `explain`；`code/lab/scenarios/README.md` 补字段表一行、校验规则第 2 条和控制台消费方式。

## 3. `chaos.reset-mode`（`config/ResetMode`、`ChaosProperties`、`application.yml`）

- `chaos.reset-mode`（env `LAB_RESET_MODE`，默认 `manual`）：`manual` 时向导到 COMPLETED/FAILED **不**自动回滚，故障留在原地给操作者看日志/面板；`auto` 时 `GuideService.applyResetMode()` 调 `stopAll()` 并打 `reverted/failed/pending` 日志。
- `resetMode` 随 `GuideStateView` 透出，状态栏与向导进度条都直接展示，不再让前端猜配置。
- 回归：`GuideStateMachineTest.autoResetModeRevertsFaultsWhenTheRunEnds`、`autoResetModeRevertsFaultsAfterAFailedStep`、`stateReportsTheConfiguredResetMode`。

## 4. 背景流量 API（`traffic/TrafficController`、`TrafficService`、`TrafficStateView`）

- `GET /lab/traffic/state`、`POST /lab/traffic/start`、`POST /lab/traffic/stop`，返回 `TrafficStateView(container, state, health, running)`；均在 `/lab/**` 下由 `TokenFilter` 保护（`TrafficControllerTest.trafficApiRequiresTheLabToken` 断言 401 且响应不回显 token）。
- 容器名 `chaos.traffic.container-name`（env `CHAOS_TRAFFIC_CONTAINER`，默认 `jl-traffic`）；停止宽限 `chaos.traffic.stop-grace-seconds`（env `CHAOS_TRAFFIC_STOP_GRACE_SECONDS`，默认 `5`）。
- 启停都经 `ContainerGateway`，因此 `lab=java-learn` 白名单与既有 Docker 连接参数复用，没有第二套执行路径。
- profile 未创建容器时：`state` 捕获 `ContainerNotFoundException` 返回 `state:"missing"`、`running:false`（状态栏不因此报错）；`start`/`stop` 把它转成带"用 up.sh 创建"提示的 404。
- **流量不是 chaos 动作**：没有 TTL、不进 `/chaos/actions`、不受 `stop-all` 与向导 pause 影响（`TrafficServiceTest` 与类 Javadoc 都锁定这一语义）。

## 5. k6 背景流量场景与 compose（23b）

- `code/lab/k6/scenarios/traffic.js`：`constant-vus` 单场景，每迭代 `ensureLogin() → product() → sleep(thinkTime)`，**只做登录 + 产品读，不消耗库存**；阈值沿用共享 `config.thresholds`（`flow_errors`/`server_errors`/`checks`）。
- `lib/config.js` 新增 `config.traffic = { vus: integer('TRAFFIC_VUS', 50, 1), duration: duration('TRAFFIC_DURATION', '30m') }`；compose 侧另可覆盖 `TRAFFIC_BASE_URL`/`TRAFFIC_PRODUCT_ID`/`TRAFFIC_USER_PREFIX`/`TRAFFIC_THINK_TIME`/`TRAFFIC_RUN_ID`（默认 `http://nginx` / `1` / `traffic` / `1s` / `console`）。
- `compose.base.yml` 新增 `traffic` 服务：`image: ${K6_IMAGE:-grafana/k6:1.3.0}`、`container_name: jl-traffic`、`profiles: ["traffic"]`、`restart: "no"`、`mem_limit: 256m`、`command: ["run","--quiet","/k6/scenarios/traffic.js"]`、只读挂载 `../k6:/k6:ro`、`jl-net`、标签 `lab: java-learn` + `lab.role: traffic`。
- 因为带 profile，它不出现在 `docker compose config --services`（实测 16 个服务、`grep -c traffic` 为 0），所以 `verify.sh`/`bench.sh`/`reset-lab.sh`/`up.sh` 的 16 服务计数与断言完全不变。
- `up.sh`：在最终摘要前执行 `compose --profile traffic create traffic`，只创建不启动，失败仅 WARN（控制台会显示 `missing`），并新增一行提示如何启动。
- `down.sh`：`down` 与 `down -v` 两条分支都加 `--profile traffic`，避免 created-but-stopped 的 `jl-traffic` 残留。
- `bench.sh`：预检新增流量护栏——用 `compose --profile traffic ps -q "$TRAFFIC_SERVICE"`（`BENCH_TRAFFIC_SERVICE`，默认 `traffic`）解析容器再 `docker inspect` 状态；若 `running` 且未显式 `BENCH_ALLOW_TRAFFIC=1` 则 `exit 1`，并提示三条停止路径（控制台按钮 / `POST /lab-api/lab/traffic/stop` / `docker stop`）。不依赖裸容器名。

## 6. 操作台前端（23c）

- `src/api.js`：新增 `fetchGuideScenarios` / `fetchGuideState` / `startGuide` / `runGuideAction(token, action)` / `fetchTrafficState` / `runTrafficAction` / `fetchActiveFaults` / `stopAllFaults`；抽公共 `readJson`/`getJson`/`postJson`（统一 `Result.data` 解包与 `ApiError(status, code)`）；`fetchContainers`/`fetchLogs`/`fetchHealth` 保持原行为。
- `App.vue`：状态栏 testid `guide-status`、`fault-count`、`container-rollup`、`reset-mode`、`traffic-state`、`traffic-start`、`traffic-stop`、`stop-all`；`syncEnvironment()` 并发拉 guide/faults/traffic；自动轮询间隔 `VITE_LAB_SYNC_INTERVAL_MS`（默认 5000），`localStorage` 键 `ha-lab-auto-sync` 可关，且 busy 期间跳过轮询；每次向导迁移**以响应体为真值**再静默刷新 faults + containers（静默失败不掩盖动作结果）；错误统一走 `describeError`（新增 409 分支"状态冲突"）。
- `src/components/ScenarioWizard.vue`：场景下拉（显示 `name（stepCount 步 · 时长提示）`）+「载入场景」+ 描述；进度条与 `currentStep/totalSteps · 活动故障 · 还原模式`；步骤列表按 done/current/pending 着色；**仅当前步且 RUNNING** 时渲染 `explain` 四条（现象/根因/处方/预期曲线，逐条按字段存在性渲染）；按钮可用性完全由 `state.status` 决定——step/skip/pause 只在 RUNNING、resume 只在 PAUSED、reset 任意状态可用（逃生舱）；`step-error` 展示 `lastError`；动作中文映射与 `durationHint` 分钟/秒换算。
- `style.css`：新增 `.status-bar`/`.status-item`/`.pill`（running/paused/completed/idle/failed/warn 配色）、`.wizard-*`、`.progress-*`、`.step-list`/`.step-explain`、`.btn.danger`、`.fault-tag`、`.traffic-item` 及响应式 `@media`。
- `vite.config.js`：dev server 代理 `/lab-api` → `VITE_LAB_API_TARGET`（默认 `http://127.0.0.1:8080`），使 `npm run dev` 能直连 Nginx 入口；`base: '/lab/'` 与 `build.outDir` 不变。
- 汇总口径修正：`steadyContainers` 排除 `traffic.container`，`container-rollup` 只承诺常驻服务（见"存在缺陷项"第 2 条）。

## 7. Playwright 冒烟（23d）

- `console-smoke.sh` + `console-smoke.mjs`：真浏览器驱动**已构建**的操作台（`http://127.0.0.1:8080/lab/`），走"载入 `quick-failover` → 校验 explain 四标签 → `skip` → completed → `reset` → idle → 背景流量 start/stop → 日志面板 `jl-app-1` → 无 `error-banner`、无 `pageerror`、无失败 `/lab-api` 请求"。**全程不注入任何故障**（唯一事件被 skip，`jl-app-3` 不受影响）。
- 开头与 `finally` 都执行 `POST /lab/traffic/stop` + `POST /lab/guide/reset` 兜底，崩溃/失败不会把向导留在 RUNNING 或让流量继续打。
- 壳脚本约定与 `smoke.sh` 一致：**环境 > `code/lab/docker/.env` > 默认**，`.env` 只按行读值不 source，token 只注入环境变量、绝不打印；可调 `CONSOLE_URL`/`LAB_API_BASE`/`SMOKE_SCENARIO_ID`/`SMOKE_LOG_CONTAINER`/`SMOKE_ACTION_TIMEOUT_MS`（默认 20000）/`SMOKE_HEADED`/`PLAYWRIGHT_MODULE`/`CONSOLE_SMOKE_SCRIPT`。
- dist 缺失或 `src`/`index.html`/`vite.config.js` 任一比 `dist/index.html` 新时自动 `npm run build`（`--no-build` 可跳过）；控制器 `/lab-api/health` 不可达时直接拒绝启动浏览器。
- Playwright 解析顺序：`PLAYWRIGHT_MODULE` → `dashboard/node_modules/playwright(-core)` → 仓库根 `node_modules/playwright` → npx 缓存 `~/AppData/Local/npm-cache/_npx/*/node_modules/playwright`（取最新）。本机 dashboard 未安装 playwright，实际命中 npx 缓存的 `1.64.1`。
- 断言 helper：`expectText`/`expectCondition` 都以 400ms 轮询到超时为止（正例轮询，不瞬时读一次，见"存在缺陷项"第 4 条）。
- 结果：**26 PASS / 0 FAIL**（本次主会话验收输出，见附录）。

## 8. 测试

- chaos-controller 98 → **114**：`GuideScenarioLoaderTest` 19（新增 explain 解析/空块归 null/非对象 400/字段空白 400）、`GuideStateMachineTest` 15（新增 skip 推进不执行、skip 仅 RUNNING、auto 还原模式两处、resetMode 透出）、`GuideControllerTest` 11（steps/explain/resetMode 契约 + `explain.empty` 不外泄）、新增 `TrafficControllerTest` 4（401/state/start-stop/404）与 `TrafficServiceTest` 5（gateway 状态、missing 不算错、start 不注册 TTL、stop 用外置宽限、缺容器提示）；其余 `ChaosActionServiceTest` 25、`ChaosControllerTest` 9、`LabControllerTest` 8、`TokenFilterTest` 8、`ChaosActionStateMachineTest` 7、`ChaosConfigTest` 3。
- k6 Node 31 → **37**：`chaos-schema.test.mjs` +2（explain 裁剪/空块 null、畸形块拒绝），新增 `traffic-scenario.test.mjs` 4（executor 与默认 50/30m、阈值复用共享预算、`TRAFFIC_*` 可覆盖、`TRAFFIC_VUS=0` init fail-fast）。
- `bash -n` 全过：`console-smoke.sh`、`up.sh`、`down.sh`、`bench.sh`；`node --check` 全过：`console-smoke.mjs`、`code/lab/k6/scenarios/traffic.js`。

# 设计逻辑

- **前端不重复校验 schema**：控制台只渲染控制器给的东西，`explain` 的合法性、场景唯一性、TTL 上限都留在 `GuideScenarioLoader`；`steps` 结构化后 UI 不需要"猜哪一条是当前步"，`steps[currentStep]` 就是真值。这也是 Task 22b 交接里"操作台不要在前端重复校验"的落实。
- **响应体优先于轮询**：向导每次迁移（start/step/skip/pause/resume/reset）都返回完整新状态，前端直接用它更新本地态，再静默补拉 faults + containers。轮询只是兜底，避免"点了 step 但界面还停在旧状态"。
- **`skip` 与 `step` 共用 `advance()`**：跳过只影响是否调用 `ChaosActionService`，状态机迁移完全一致，因此冒烟可以在**不注入任何故障**的前提下走完整条向导——这是本步能安全复用共享栈验收的原因。
- **流量与 chaos 分域**：背景流量是"演示环境的组成部分"，不是"被注入的故障"。做成独立 `/lab/traffic/**` + 独立 compose 服务，就天然没有 TTL/回滚/冻结语义，也不会被 `stop-all` 误停；`guide pause` 期间流量继续跑，正是演示"故障期间业务仍在被访问"想要的效果。
- **profile 门控而不是新常驻服务**：默认栈不为演示流量付内存（`mem_limit: 256m`），也不改变 `verify.sh`/`bench.sh` 的 16 服务口径；`up.sh` 用 `create`（不 `up`）让容器"存在但停止"，控制器只负责 start/stop，形状参数（VUS/时长/URL/产品）继续留在 k6 侧与 compose 里。
- **`reset-mode` 是演示语义而非安全开关**：`manual` 把"还原"交回操作者（看完日志再决定），`auto` 面向批量跑；两者都不绕过 TTL 与 `stop-all`，`chaos.reset-mode` 只是"到点要不要自动 stopAll"的策略位。
- **配置外置（对齐 `doc/design/工程规约.md` 第 1 节）**：新增可调项全部有 env 覆盖路径 —— `LAB_RESET_MODE`、`CHAOS_TRAFFIC_CONTAINER`、`CHAOS_TRAFFIC_STOP_GRACE_SECONDS`、`CHAOS_TRAFFIC_*` compose 侧、`TRAFFIC_VUS`/`TRAFFIC_DURATION`/`TRAFFIC_BASE_URL`/`TRAFFIC_PRODUCT_ID`/`TRAFFIC_USER_PREFIX`/`TRAFFIC_THINK_TIME`/`TRAFFIC_RUN_ID`、`K6_IMAGE`、`BENCH_TRAFFIC_SERVICE`/`BENCH_ALLOW_TRAFFIC`、`VITE_LAB_SYNC_INTERVAL_MS`、`VITE_LAB_API_TARGET`、`CONSOLE_URL`/`LAB_API_BASE`/`SMOKE_SCENARIO_ID`/`SMOKE_LOG_CONTAINER`/`SMOKE_ACTION_TIMEOUT_MS`/`SMOKE_BROWSER_CHANNELS`/`PLAYWRIGHT_MODULE`/`CONSOLE_SMOKE_SCRIPT`。compose 用 `${VAR:-default}`，Java 用 `application.yml` + `@ConfigurationProperties`，k6 用 `lib/config.js`，脚本用"环境 > `.env` > 默认"，前端用 `import.meta.env`/`process.env`。
- **前端固定值只做展示用途**：状态栏轮询默认 5s、token 输入默认沿用既有 `lab-token` 占位、`DEFAULT_TAIL` 200、Playwright 轮询间隔 400ms 属于 UI 节奏常量（间隔/超时预算可被 env 覆盖）；未新增运行期的 Docker 路径、端口或 TTL 硬编码。

# 存在缺陷项&TODO

## 本次踩坑（避坑清单，后续任务复用）

1. **Jackson 会把 record 上的 `isEmpty()` 当成属性序列化**。初版给 `GuideStepExplain` 加了 `isEmpty()` 方便 loader 判空，在线 `GET /lab/guide/state` 实测泄漏出 `explain.empty:false`，污染对外 JSON 契约。现改为在 `optionalExplain()` 里用四个局部变量判空、record 体不带任何辅助方法，并由 `GuideControllerTest` 的 `jsonPath("$.data.steps[0].explain.empty").doesNotExist()` 锁定。**经验：`@RestController` 返回的 record/POJO 不要写 `isXxx()`/`getXxx()` 形态的派生方法，判空逻辑放调用方。**
2. **profile 门控的容器会进白名单列表，但不该进"全部运行"汇总**。`jl-traffic` 带 `lab=java-learn` 标签，`/lab-api/containers` 一定返回它，而 created/exited-but-stopped 才是它的常态；实测 `/containers` 返回 17 项、只有 16 running，若状态栏按"全部容器"聚合就永远显示 warn。现由 `steadyContainers` 排除 `traffic.container`，并在 `title` 里注明"背景流量容器按需启停"。**经验：凡是"按需启停"的 lab 容器，都要同时定义它在白名单视图与汇总口径里的角色；`/containers` 的口径 ≠ "常驻服务健康口径"。**
3. **Playwright 的浏览器下载版本与 Playwright 包版本是配对锁定的**。npx 缓存里的 `playwright-core@1.64.1` 要求 chromium `1248`，本机 `~/AppData/Local/ms-playwright` 只有 `chromium-1243`，`chromium.launch()` 直接失败；而离线实验室不能假设 `npx playwright install` 可用（网络受限会挂）。现改为 `launchBrowser()` 先试自带 Chromium，失败后按 `SMOKE_BROWSER_CHANNELS`（默认 `chrome,msedge`）回退系统浏览器（本机 Chrome/Edge 均已安装）。**经验：冒烟脚本要把"浏览器可得性"当环境依赖处理，提供 channel 回退与可覆盖的模块路径，而不是硬要求某个 revision。**
4. **5s 自动轮询与前端 busy 标志会竞争**。`reset` 请求返回后 `busy` 还没清空的那一拍里，`action-reset` 仍是 disabled，而状态 pill 已经翻成 `idle`；断言若"瞬时读一次 disabled"就会误判成 bug。冒烟因此统一用 `expectCondition`/`expectText` 轮询**正例**（等到 enabled / 等到目标文本，400ms 步进、20s 预算），`App.vue` 侧也在 interval 回调里 `if (!busy.value)` 才轮询。**经验：状态迁移类断言只断言"最终会成立的正例"，不要断言瞬时负例。**

## 已知限制 / 后续待办

- **`/lab/guide/state` 契约是破坏性变更**：`currentStepTitle`/`currentStepText` 已删除。仓库内没有其它消费方（k6 不读 state），但任何外部脚本/收藏的 curl 若引用这两个字段需要改。已在 `GuideControllerTest` 用 `steps[0].title` + `steps` 长度锁定新形状。
- **冒烟依赖已构建的 `dist`**：`dist/` 是 gitignored 且被 Nginx 只读挂载，源码比 dist 新时脚本会自动 `npm run build`；如果只改 dist 或改 `.env` 里的 URL，脚本仍可能测到旧界面——改动 UI 后建议显式重建或去掉 `--no-build`。
- **dashboard 未把 playwright 列为 devDependency**：解析顺序能跑通（实测命中 npx 缓存 1.64.1），但换机器/清缓存后冒烟会失败并给出"设置 `PLAYWRIGHT_MODULE`"的提示。若要把冒烟纳入 CI，需决定是显式声明依赖还是继续依赖本机缓存。
- **`traffic.js` 只在容器内跑，未做在线长跑验收**：本轮只通过控制台 start/stop 验证容器可拉起并显示 `running`，随后停止（`TRAFFIC_DURATION` 是上限不是硬停）。向导提前结束时需要人工/冒烟兜底停流量；`bench.sh` 护栏保证它不会污染归档基线。
- **`TRAFFIC_DURATION` 默认 30m**：演示忘记停止时，Prometheus 会持续吃 `run_id="console"` 的 k6 序列；未做自动到期。若需要更保守，可把 compose 的默认值改短或在控制台加倒计时（属 M6 打磨，未做）。
- **前端 `DEFAULT_TAIL=200`、Playwright 轮询 400ms/超时 20s（可覆盖）**：属于 UI/测试节奏常量；`SMOKE_ACTION_TIMEOUT_MS`、`VITE_LAB_SYNC_INTERVAL_MS` 已外置，tail 上限 1–1000 仍是前后端约定值（后端 `InvalidTailException` 为准）。
- **日志面板仍沿用 Task 20/22 的限制**：请求无 Abort/序列保护、无字节上限、容器列表 N+1 inspect、Docker 日志超时可能截断——本步未触碰。
- **状态栏不做 pause 冻结细节**：`/chaos/actions` 仍不暴露 `paused`/`remainingMs`，冻结超过原到期时 `expiresAt` 会显示过去时间（Task 22b 遗留），向导只显示 guide status 与 `activeActions` 计数。
- **向导仍不支持自动播放**：`atSeconds` 只用于展示和 k6 时间线；"按时间自动 step"若要做，应明确与 reset-mode/pause 的相互作用，不在 MVP 范围。
- **`verify.sh` 本步未复跑**：只读快照确认 16 常驻容器全绿、guide idle、traffic exited；下一次收口建议补跑 `verify.sh` + `smoke.sh` 以覆盖"新契约不影响既有断言"。

# 待定事项和注意事项

- **Task 24（M3-1 Redis Sentinel）开始前的交接点**：操作台已具备"载入场景 → 单步 → 观察 → 复位"的完整演示闭环，后续新场景只需在 `code/lab/scenarios/*.json` 补 `explain`，前端不需要改（`steps[currentStep].explain` 自动渲染，字段缺失就不显示该条）。
- **新增场景要同时过两侧校验**：`GuideScenarioLoader`（Java）与 `lib/chaos-schema.js`（k6）是镜像实现，加字段必须两边同改 + 两边补测试，否则会出现"k6 能跑、控制器 400"。
- **禁止对 datastore 做真实故障**：`ha-app-failover` 会停 app-1、冻结 app-2；日常验收只用 `quick-failover`，且冒烟用 `skip`。`CHAOS_GUIDE_TARGET_PATTERN` 与 loader 的 `mysql|redis|rabbit|mongo|postgres` 硬黑名单仍是第一道闸。
- **背景流量与 bench 的关系**：跑 `bench.sh` 前若状态栏显示 `running`，先点"停止"或 `POST /lab-api/lab/traffic/stop`；`bench.sh` 会拒绝归档。确实要"带流量压测"用 `BENCH_ALLOW_TRAFFIC=1` 显式放行，并在结果文档里写明。
- **`reset-mode` 演示前先确认**：状态栏与向导都显示 `manual`。若某场演示希望结束即还原，改 `LAB_RESET_MODE=auto` 并重建/重启控制器容器（本步未新增镜像重建需求，当前镜像已含全部 M2-5 代码）。
- **浏览器侧异常退出时**：先 `POST /lab-api/lab/guide/reset`（stop-all + 解冻 + 清空向导），再 `POST /lab-api/lab/traffic/stop`；向导状态是控制器进程内存态，控制器重启会回 IDLE 而 TTL 仍在 action service 里。
- **token 仍从 `code/lab/docker/.env` 读取，不提交、不打印**；本文档与所有脚本输出都不含 token。`.env`、`dashboard/dist/`、`node_modules/`、`k6/reports/`、`.superpowers/` 都已在 `.gitignore` 中，提交前仍需 `git status --short` 复核。
- 栈保持运行：16 常驻容器 running、`jl-traffic` created/exited 常态、guide idle、`/chaos/actions` 空；本步只做了只读 curl 与本地测试，未重启任何容器。

# 附录

## 关键文件

- 控制器：`code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/guide/`（`GuideController`、`GuideService`、`GuideStepView`、`GuideStepExplain`、`GuideEvent`、`GuideStateView`、`GuideScenarioLoader`）、`.../traffic/`（`TrafficController`、`TrafficService`、`TrafficStateView`）、`.../config/ResetMode.java`、`.../config/ChaosProperties.java`、`src/main/resources/application.yml`
- 控制器测试：`GuideControllerTest`、`GuideScenarioLoaderTest`、`GuideStateMachineTest`、`TrafficControllerTest`、`TrafficServiceTest`
- 前端：`code/lab/dashboard/src/App.vue`、`src/api.js`、`src/components/ScenarioWizard.vue`、`src/style.css`、`vite.config.js`
- 背景流量：`code/lab/k6/scenarios/traffic.js`、`code/lab/k6/lib/config.js`、`code/lab/docker/compose.base.yml`
- 脚本：`code/lab/scripts/console-smoke.sh`、`console-smoke.mjs`、`up.sh`、`down.sh`、`bench.sh`
- 共享 schema 与场景：`code/lab/k6/lib/chaos-schema.js`、`code/lab/k6/test/chaos-schema.test.mjs`、`code/lab/k6/test/traffic-scenario.test.mjs`、`code/lab/scenarios/{quick-failover,ha-app-failover}.json`、`code/lab/scenarios/README.md`、`code/lab/k6/README.md`

## 复现命令

```bash
cd /d/000CODE/JAVA-LEARN

# 控制器单模块测试（JDK 21）
export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1"
cd code && mvn -pl services/chaos-controller -am test && cd ..

# k6 Node 回归：必须用 glob，node --test <目录> 在本机 Windows 会报 Cannot find module
node --test code/lab/k6/test/*.test.mjs

# 静态检查
bash -n code/lab/scripts/console-smoke.sh code/lab/scripts/up.sh \
        code/lab/scripts/down.sh code/lab/scripts/bench.sh
node --check code/lab/scripts/console-smoke.mjs
node --check code/lab/k6/scenarios/traffic.js

# 操作台冒烟（栈需在运行；dist 过期会自动 npm run build；全程不注入故障）
bash code/lab/scripts/console-smoke.sh

# 在线只读契约快照（token 只读不打印）
TOK=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2- | tr -d '"\r')
curl -s -H "X-Lab-Token: $TOK" http://127.0.0.1:8080/lab-api/lab/guide/state
curl -s -H "X-Lab-Token: $TOK" http://127.0.0.1:8080/lab-api/lab/guide/scenarios
curl -s -H "X-Lab-Token: $TOK" http://127.0.0.1:8080/lab-api/lab/traffic/state

# profile 门控验证：服务数仍是 16，traffic 不在默认列表里
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  -f code/lab/docker/compose.dev.yml config --services
```

## 证据

- 全 reactor（本次主会话最终记录，日志尾部）：`app-jdk21` **131**、`chaos-controller` **114**，`BUILD SUCCESS`；`code/services/*/target/surefire-reports` 逐模块汇总 `platform-common` 10 + `platform-observability` 4 + `seckill-core` 13 + `app-jdk21` 131 + `chaos-controller` 114 = **272 tests, 0 failures**。
- 本步复跑（收尾时执行）：`mvn -pl services/chaos-controller -am test` **exit 0**，刷新后的 surefire 汇总 `chaos-controller tests=114 failures=0 errors=0`（`-q` 抑制 INFO，故无 `Tests run:` 行；日志里的 `Revert of kill on jl-app-3 failed ... docker start failed` 是失败路径测试的预期输出）。
- chaos-controller 逐类：`ChaosActionServiceTest` 25、`GuideScenarioLoaderTest` 19、`GuideStateMachineTest` 15、`GuideControllerTest` 11、`ChaosControllerTest` 9、`LabControllerTest` 8、`TokenFilterTest` 8、`ChaosActionStateMachineTest` 7、`TrafficServiceTest` 5、`TrafficControllerTest` 4、`ChaosConfigTest` 3（合计 114）。
- k6 Node 回归（本步复跑）：`# tests 37 / # pass 37 / # fail 0`；`node --test code/lab/k6/test`（目录形式）复现 `Error: Cannot find module '...code\lab\k6\test'`，确认"必须用 glob"这条运行方式坑。
- 在线快照（本步只读复跑，token 未打印）：
  - `GET /lab/guide/state` → `{"scenarioId":null,"scenarioName":null,"status":"idle","currentStep":0,"totalSteps":0,"steps":[],"activeActions":0,"lastError":null,"resetMode":"manual"}`，无 `currentStepTitle`/`currentStepText`。
  - `GET /lab/guide/scenarios` → `ha-app-failover` stepCount **3**（durationHint 240s）、`quick-failover` stepCount **1**（30s）。
  - `GET /lab/traffic/state` → `{"container":"jl-traffic","state":"exited","health":"none","running":false}`。
  - `GET /lab-api/containers` → 17 项、16 running、`jl-traffic:exited`（即第 2 条踩坑的量化证据）。
  - `compose config --services` → 16 行，`traffic` 不出现。
- Playwright 环境：npx 缓存 `playwright`/`playwright-core` 均为 **1.64.1**，`browsers.json` 要求 chromium **1248**，本机 `~/AppData/Local/ms-playwright` 只有 **chromium-1243**，系统 Chrome 与 Edge 可执行文件存在（channel 回退可用）。
- 冒烟结果（本次主会话验收输出）：

```
PASS  console page loads / controller reports online / guide starts idle /
every always-on container is running / reset mode shows the manual default /
traffic container state is known / scenario list is loaded from the controller /
scenario description is rendered / wizard is running after loading the scenario /
wizard shows one pending step / per-step 现象/根因/处方/预期曲线 text is rendered /
explain block carries the four labels / pause is enabled while running /
resume is disabled while running / skipping the only step completes the wizard /
reset stays available as the escape hatch / step is disabled once completed /
pause is disabled once completed / skipping injected no fault /
reset returns the wizard to idle / background traffic start shows in the status bar /
background traffic stop shows in the status bar / log panel renders container output /
no in-page error banner / no uncaught page errors / no failed lab-api requests
console smoke: 26 PASS, 0 FAIL
```
