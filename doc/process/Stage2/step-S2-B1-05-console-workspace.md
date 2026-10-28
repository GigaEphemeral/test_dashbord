# 当前进展

> 任务/步骤：Stage2 **B1-05 控制台工作区重构（侧边导航一次性重写）+ 台账页**
> 分支：`feature/stage2-workbench`（隔离 worktree `agent-general-purpose-dcf54829`，基线 `fda2af9`）
> 最新代码提交：`34470d4`（smoke 包装脚本补丁）；核心提交 `860e870`..`39e86bc`；本文件单独一笔 `docs(B1-05)` 提交
> 测试：`cd code/lab/dashboard && npm ci && npm run build` → **BUILD 成功**；`node --check code/lab/scripts/console-smoke.mjs`、
> `bash -n code/lab/scripts/console-smoke.sh` 均通过；额外用本地 mock（临时目录，未入库）跑通 Playwright 主链路：
> 七个分区挂载、台账创建/预检查/启动/停止/刷新恢复、指标质量卡、故障页组合挂载，无页面异常。
> 真机：**未起/未停共享 lab 栈，未执行 docker compose / up.sh / down.sh / verify.sh / reset-lab.sh，未跑 console-smoke.sh**；
> nginx 挂载的是主工作树的 `dist`，真机 UI 验收由主会话在合并并重建 dist 后执行（见附录 B）。

控制台从 4 标签单页一次性重构为**侧边导航七分区工作区**；台账页作为首个新页面落地。
旧组件（ScenarioWizard / ThreadLab / CacheLab / AnimationLab 内部的 TopologyView、InstancesView、CacheFlow）
全部原样挂接，只改外层壳与数据入口，没有重写业务逻辑。

# 完成功能项

## 1. 新信息架构与旧视图映射

| 新分区 | testid | 挂接内容 | 旧视图来源 |
|---|---|---|---|
| 总览 | `nav-overview` | 控制器健康、常驻容器池、活动故障、当前 run、台账状态、场景/流量摘要 + 快捷入口 | 实验操作台 status-bar + 容器数据 |
| 场景向导 | `nav-wizard` | `ScenarioWizard` 原样挂接（scenario-select / scenario-start / action-* 全保留） | 实验操作台 |
| 实例与 JVM | `nav-instances` | 子标签：`InstancesView`（实例卡）+ `ThreadLab`（线程实验室） | 线程实验室 + 动画视图实例页 |
| Benchmark | `nav-bench` | traffic 启停、stop-all、容器清单、日志面板、k6 CLI 提示 | 实验操作台 k6/流量/日志卡片 |
| 实时监控 | `nav-observe` | `TopologyView` + B1-03 窗口 KPI 卡（12 项，按实例展开序列） | 动画视图拓扑 + 新聚合 API |
| 故障与缓存实验 | `nav-faults` | `ChaosActions` 注入卡 + `CacheLab` + `AnimationLab`（其内第三标签即 `CacheFlow`） | 缓存实验室 + 动画视图 |
| 实验记录 | `nav-runs` | 新台账页 `RunLedger` | 新页面 |

- 壳层（`App.vue`）只保留全局状态：令牌、健康、容器/向导/流量/故障、toxiproxy、台账摘要、全局 syncTick。
- 令牌输入、`/prom/` 外链、Grafana 外链在顶栏/壳层常驻；Grafana 地址改为 `VITE_LAB_GRAFANA_URL` 可覆盖。
- 侧边导航顺序即操作流程；旧 `nav-console/nav-thread/nav-cache/nav-animation` 已移除，统一 `nav-<section>`。
- 全局「全部停止」在壳层常驻（`global-stop-all`）；Benchmark 仍保留旧 `stop-all`，Faults 页另有 `chaos-clear-all`。

## 2. 台账页能力（首个新页面）

- **列表**：`GET /lab/runs?scenario=&status=&limit=&offset=`，列 runId/scenario/status/operator/sourceCommit/createdAt/updatedAt；分页与状态筛选；状态徽标对 `UNKNOWN / FAILED / INVALID / ABORTED / DRAFT / RUNNING / COMPLETED` 等用不同颜色。
- **新建**：scenario + params(JSON) → `POST /lab/runs`，前端校验 scenario 正则与 JSON 对象形状，成功显示 `runId / DRAFT / createdAt` 并自动选中。
- **详情**：`GET /lab/runs/{id}`，渲染 steps 时间线（from→to / occurredAt / dwellMs / detail）、env 快照、params、checks；`GET /lab/operations?runId=` 展示 operationId/action/status/时间/lastError。
- **预检查**：`GET /lab/runs/{id}/precheck`，分组展示 blocking / warnings / 全部 items 与 detail；`verdict=BLOCKED` 时启动按钮禁用（后端无 force/skip）。
- **操作**：`POST start|stop` 每次点击生成新的 `Idempotency-Key` 并展示返回的 `operationId`；HTTP 202 或 `status=PENDING_CONFIRMATION` 时轮询 `GET /lab/operations/{id}` 直到 `APPLIED/RESTORED/FAILED/UNKNOWN`。409/40903 渲染阻断明细；409/40902 从 message 提取 holder runId 并提示先 stop 持有者或等 TTL。
- **指标**：`GET /lab/runs/{id}/metrics?keys=...` 复用 `MetricCards`，显示窗口、质量、generatedAt 与逐实例序列；默认包含 `executor.rejected` 作为“未接线不得显示 0”的探针。
- **自恢复**：选中 runId 写 `localStorage`（`ha-lab-selected-run`）；浏览器刷新后重新选中；列表/详情随全局 syncTick 刷新，预检查与指标只在选中/操作后重查（避免每 5s 触发 Docker/Prom 检查）。
- **台账不可用**：503/50302 → `DEGRADED` 面板 + `GET /lab/ledger/status` 的 state/mode/schema/pendingStash/lastWriteAt/lastError；此时隐藏列表与计数，不出现空表的 0。

## 3. 质量徽标语义（与 B1-03 一致）

| 徽标 | 含义 | 卡片渲染 |
|---|---|---|
| `OK` | 窗口内有真实样本 | 显示 `stats.last`，按 unit 格式化 |
| `NO_DATA` | 查询成功但无序列（未接线/窗口无数据） | 显示“无数据”，附 reason |
| `COLLECTING` | 无序列且窗口触到当前抓取周期 | 显示“无数据”，附 reason |
| `DEGRADED` | Prometheus 失败/截断/采集异常 | 显示“采集异常”，附 reason |

每张卡都带质量徽标与“更新 HH:MM:SS”；`series[]` 只在 `<details>` 展开器中列出（instance/name/pool、last/avg/min/max/n）。

## 4. 配置（全部可覆盖）

| 键 | 默认 | 用途 |
|---|---|---|
| `VITE_LAB_SYNC_INTERVAL_MS` | `5000` | 全局 sync（沿用） |
| `VITE_LAB_LIVE_WINDOW_SECONDS` | `300` | 实时监控/实例卡的窗口长度 |
| `VITE_LAB_KPI_KEYS` | 12 个目录键 | 实时监控 KPI |
| `VITE_LAB_RUN_METRIC_KEYS` | 12 KPI + `executor.rejected` | 台账 run 指标 |
| `VITE_LAB_LEDGER_PAGE_SIZE` | `20` | 台账列表页大小 |
| `VITE_LAB_OPERATIONS_PAGE_SIZE` | `50` | 台账操作列表上限 |
| `VITE_LAB_OPERATION_POLL_INTERVAL_MS` | `1000` | operation 轮询间隔 |
| `VITE_LAB_OPERATION_POLL_TIMEOUT_MS` | `30000` | operation 轮询总预算 |
| `VITE_LAB_CHAOS_DEFAULT_TTL_SECONDS` | `60` | 故障注入卡默认 TTL |
| `VITE_LAB_LEDGER_SUMMARY_LIMIT` | `5` | 总览“当前 run”取样条数 |
| `VITE_LAB_GRAFANA_URL` | `http://127.0.0.1:3000` | 外链地址（沿用 CacheLab 口径） |

## 5. console-smoke 新基线

`code/lab/scripts/console-smoke.mjs` 一次性重写，**新基线 63 条断言**（原 42 条映射 + 台账/质量新增）：

- 总览/向导/Benchmark/实例/故障/动画/可访问性：原 42 条按新导航重新落点；`nav-<section>` 校验 7 个分区顺序。
- 实时监控：拓扑 + 12 张 KPI 卡、每卡质量徽标与更新时间、`jvm.heap.used` 为 OK 真值、`cache.l2.keys` 按质量契约（OK 时非 0/无数据，否则必须无数据）、`executor.rejected` 必须非 OK 且不是 0。
- 台账：列表可见、唯一 scenario 新建 DRAFT、详情选中、预检查 verdict、启动/停止的 operationId 与时间线、操作列表、run 指标卡、`executor.rejected` 非 0、刷新后仍选中上次 run。
- 预检查 BLOCKED 分支：断言启动禁用、阻断项证据、空时间线、DRAFT 无 operation；两条分支都固定 4 条，基线不随环境漂移。
- 清理：traffic stop、guide reset、stop-all、thread stop-all、cache mode off；并额外关闭本工具自己创建（scenario 前缀 `console-smoke-`）的 RUNNING/UNKNOWN run，避免上次崩溃留下写锁。
- 新环境变量：`SMOKE_RUN_SCENARIO`、`SMOKE_RUN_SCENARIO_PREFIX`、`SMOKE_RUN_PARAMS`、`SMOKE_KPI_COUNT`、`SMOKE_RUN_METRIC_COUNT`（默认 12/13）。

# 设计逻辑

1. **一次性重写壳、不重写能力**。`ScenarioWizard`、`ThreadLab`、`CacheLab`、`AnimationLab`/`TopologyView`/`InstancesView`/`CacheFlow` 全部原样挂接；只有 App 从“视图切换 + 控制台状态”变成“侧边导航壳 + 全局 syncTick”。
2. **全局 sync 只有一份**。`App.vue` 每 `VITE_LAB_SYNC_INTERVAL_MS` 拉一次 guide/faults/traffic/containers 和台账摘要，然后 `syncTick++`；Observe/Instances/RunLedger 监听 tick 拉自己的窗口数据。ThreadLab/CacheLab/AnimationLab 仍保留既有轮询，但只在对应分区挂载时运行，避免多页重复轮询。
3. **质量是数据事实**。KPI 卡不把 `NO_DATA` 变成 0；`executor.rejected` 在目录里本来就没有导出源，因此固定作为“无数据不得显示 0”的可见探针；`cache.l2.keys` 在只跑 HA exporter 的栈上可以是 NO_DATA，smoke 按契约接受“OK 真值 / 非 OK 无数据”两种诚实结果。
4. **操作契约在前端显式化**。202 不等于失败：页面保留 operationId 并轮询到终态；40903 的 `data` 直接复用 `PrecheckReport` 渲染；40902 只拿到 message 时用正则提取 holder runId。每次点击生成新 key，避免 B1-04 登记的“重试复用旧 key 重放 FAILED operation”问题。
5. **台账 DEGRADED 不伪装空表**。503/50302 时列表、分页、计数全部隐藏，改渲染 `/lab/ledger/status` 的 state/lastError；这与 B1-03 的“无数据不是 0”同一口径。
6. **导航可回归**。新 testid 统一为 `nav-<section>`；旧 console-smoke 的 42 条全部映射到新分区并随本步一次性重写，后续批次（实例配置、拓扑 tooltip、k6 执行器）在此基线上逐批增补。

# 存在缺陷项&TODO

| 级别 | 项 | 说明 / 下一步 |
|---|---|---|
| 中 | 真机 console-smoke 未跑 | 本 worktree 不改主树、不重建主树 dist；63 条基线需主会话合并后真机执行（附录 B） |
| 中 | `dist/` 未提交 | 已 gitignore；nginx 挂主工作树 dist，worktree 构建只证明可构建 |
| 中 | DEGRADED 分支未自动化 | 验证需让 MySQL/台账不可用（破坏性），smoke 不覆盖；只做了渲染路径与 API 契约实现 |
| 低 | 预检查每 5s 不重查 | 列表/详情随全局 sync；precheck 只在选中、操作后和手动按钮时重查，避免频繁 Docker/Prom 调用。若需要实时门禁，可在 B2 增加轻量缓存 |
| 低 | 台账列表无删除/归档入口 | 后端无 DELETE；smoke 用唯一 scenario 并只读断言，不依赖空库。保留策略由服务端 50 条处理 |
| 低 | 总览“当前 run”取最新 5 条中的首个非终态 | 台账量大时可能不是全局最新；后续可改用专门 summary 接口 |
| 低 | `VITE_LAB_KPI_KEYS` 改动需同步 smoke 计数 | 默认 12；自定义时设置 `SMOKE_KPI_COUNT`，run 指标同理 `SMOKE_RUN_METRIC_COUNT` |
| 低 | AnimationLab 仍含拓扑/实例标签 | 为满足“组件原样挂接”，faults 页直接挂 AnimationLab（CacheFlow 是其第三标签）；observe 页的 TopologyView 是只读监控入口。若后续去重，需要把 AnimationLab 的拓扑/实例标签拆掉，属于 B5 拓扑页工作 |

# 待定事项和注意事项

1. **主会话真机验收**（合并后）：
   - 在**主工作树** `code/lab/dashboard` 执行 `npm ci && npm run build`（nginx 只读挂载主树 `dist`；worktree 构建不生效）。
   - 确认栈在跑（ha + db-ha，A30/A31 的 UI 半区）；`bash code/lab/scripts/console-smoke.sh`（会按源文件新旧自动重建；已重建可加 `--no-build`）。
   - 期望：`console smoke: 63 PASS, 0 FAIL`；结束后 `GET /lab/runs?status=RUNNING` 无本工具残留（预检查 PASS/WARN 分支会 start 后 stop；BLOCKED 分支 remains DRAFT）。
   - 若预检查被环境阻断，smoke 走 BLOCKED 分支仍应 63 PASS；此时需按页面 `blocking[]` 修环境后再跑一次 PASS 分支（A30 的真实启动半区）。
2. **台账数据**：smoke 每次创建一条 `console-smoke-<epoch>` DRAFT/COMPLETED run，不做删除；服务端保留最近 50 个 run。手动验收后可在台账页按 scenario 过滤查看。
3. **操作轮询**：默认 1s 间隔、30s 总预算；控制器同步预算通常 200/202 立即返回。若真机 202 长期不终态，页面会显示 PENDING_CONFIRMATION 提示而不是报失败。
4. **未新增依赖**：仍是 Vue 3 + fetch，无路由/UI 库；视图切换为 `ref`。`MetricCards`/`RunLedger`/`WorkspaceSidebar` 等新组件在 `src/components/`。
5. **行尾**：`App.vue` 保持 CRLF（字节级确认后改写）；新文件与 `style.css`/`console-smoke.mjs` 为 LF；未整文件翻转其他文件。

# 附录

## A. 提交

| commit | 内容 |
|---|---|
| `860e870` | api.js：台账/操作/指标窗口绑定，ApiError 带 data 与 code |
| `0bfafa2` | MetricCards / ObservePanel / InstancesPanel，TopologyView 文案 |
| `9501a74` | RunLedger 台账页 |
| `f8dfb76` | App 壳 + 侧边导航 + 总览/Benchmark/Faults/Chaos 卡 + 样式 |
| `39e86bc` | console-smoke.mjs/.sh 新基线（63 条） |
| `34470d4` | 包装脚本透传 SMOKE_RUN_* / 卡片计数覆盖项 |
| `docs(B1-05)` | 本文件 |

## B. 真机验收命令（主会话）

```bash
cd /d/000CODE/JAVA-LEARN/code/lab/dashboard
npm ci && npm run build

cd /d/000CODE/JAVA-LEARN
bash code/lab/scripts/console-smoke.sh          # 或 --no-build / --headed
```

只读核对（不重启容器）：

```bash
curl -s -H "X-Lab-Token: $LAB_TOKEN" http://127.0.0.1:8080/lab-api/lab/ledger/status
curl -s -H "X-Lab-Token: $LAB_TOKEN" "http://127.0.0.1:8080/lab-api/lab/runs?limit=5"
```

## C. 关键文件

- `code/lab/dashboard/src/App.vue`（壳/全局状态/syncTick）
- `code/lab/dashboard/src/components/WorkspaceSidebar.vue`
- `code/lab/dashboard/src/components/OverviewPanel.vue`
- `code/lab/dashboard/src/components/BenchmarkPanel.vue`
- `code/lab/dashboard/src/components/InstancesPanel.vue`
- `code/lab/dashboard/src/components/ObservePanel.vue`
- `code/lab/dashboard/src/components/MetricCards.vue`
- `code/lab/dashboard/src/components/FaultsPanel.vue`
- `code/lab/dashboard/src/components/ChaosActions.vue`
- `code/lab/dashboard/src/components/RunLedger.vue`
- `code/lab/dashboard/src/api.js`、`style.css`
- `code/lab/scripts/console-smoke.mjs`、`console-smoke.sh`

## 补充：主会话真机 console-smoke（2026-10-28，合并 `f2e47cb` 后）

nginx 只读挂载主工作树的 `code/lab/dashboard/dist`，所以真机验证只能在合并后由主会话执行：

```bash
cd code/lab/dashboard && npm run build        # 28 modules，JS 185.5 kB / CSS 29.45 kB
bash code/lab/scripts/console-smoke.sh --no-build
# console smoke: 63 PASS, 0 FAIL
```

### 1. 首轮失败与修正（真实缺口）

首轮 63 条在 `observe: unwired executor.rejected ...` 处超时崩溃：
`metric-quality-executor.rejected` 不存在——该 key 未进 `ObservePanel` 的 12 个默认 KPI，
而 smoke 明确用它验证"未接线指标不显示 0"。修正：把 `executor.rejected` 加入默认 KPI（**13 卡**），
`console-smoke.mjs` 的 `SMOKE_KPI_COUNT` 默认值与 `console-smoke.sh` 用法文本同步改 13，重建 dist 后全绿。
这是一条真实缺口（smoke 与页面 KPI 清单不一致），不是 flaky。

### 2. 覆盖到的关键行为（摘录）

- 七分区导航：overview / wizard / instances / bench / observe / faults / runs 全部可达；
- instances：线程实验室六态展示、cpu-burn 注入后 ACTIVE、停止后清空、代理无残留；
- faults：缓存模式切换/播种/预热、动画视图拓扑、`freeze jl-app-2` 徽标与 stop-all 清除、粒子暂停/减动效；
- observe：每张卡带质量徽标与更新时间，`jvm.heap.used` 为 OK 真实值，`cache.l2.keys` 遵守质量契约，`executor.rejected` 为"无数据"；
- runs：页面创建 DRAFT → 详情选中 → pre-check 展示 → start/stop 产生 operation 并轮询到终态 → 时间线含 DRAFT→RUNNING → operation 台账 → run 指标卡 → **浏览器刷新后仍选中该 run**；
- 收尾：无可见错误横幅、无未捕获页面异常、无失败的 `/lab-api` 或 `/prom` 请求。

### 3. 闸门

| 闸门 | 结果 |
|---|---|
| `console-smoke.sh` | **63 PASS / 0 FAIL**（新基线，旧 42 作废） |
| `smoke.sh` | 7/7 |
| `verify.sh` | **109 PASS / 1 WARN / 0 FAIL** |
| Java（无改动） | reactor 1029 / chaos 474（B1-04 基线不变） |

### 4. 遗留

- `DEGRADED` 台账分支未自动化（需先造台账不可用），留 B1 收口后的补测或 B5；
- `CacheFlow` 挂在动画视图的第三个标签内（未单列导航项），符合"组件原样迁移"口径；
- 真机 smoke 会在台账里留下 `console-smoke-*` 记录；脚本启动时会把上次遗留的未结束 run（前缀 `SMOKE_RUN_SCENARIO_PREFIX`）stop 掉（不删除审计行，清理失败只 WARN）。
