> 步骤交接 · Step 46 M6-1 动画视图（拓扑 / 实例 / 缓存流动）
> 日期：2026-10-27
> 分支 / HEAD：`feature/ha-lab` 隔离 worktree `agent-general-purpose-1f0addcf` / 功能提交 `2150e91`
> 关联任务：M6-1；设计 §12.1（页面）、§12.3（实时性）、§7.4（面板）；`doc/design/details/diagrams/01-deployment-topology.mmd`、`04-cache-read-path.mmd`
> 验证：`cd code/lab/dashboard && npm ci && npm run build` → **成功**（18 modules；`index.html` 0.46 kB、CSS 19.95 kB / gzip 4.27 kB、JS 122.93 kB / gzip 45.63 kB）。**未启动容器、未跑 Playwright**（实栈归另一会话）；`console-smoke.mjs` 未改。
> 运行状态：只做作者态与构建；栈未在本 worktree 启动。

# 1. 三个视图各显示什么

入口：顶部导航新增「动画视图」（`data-testid="nav-animation"`），页内三个 tab，默认拓扑。三个 tab 共用一个 5s 轮询器，切 tab 立即拉取该 tab 需要的数据。

**拓扑视图**（`TopologyView.vue`，`data-testid="topology-page"`）

- 只画 `GET /lab-api/containers` 返回的容器，按容器名前缀分角色：入口（`jl-nginx`）、应用（`jl-app-1..3`、`jl-seckill-app-jdk8`）、缓存（`jl-redis` / `jl-redis-1..3` / `jl-sentinel-*` / Redis exporter）、数据库（`jl-mysql` / `jl-mysql-1..3` / Router / 初始化任务 / exporter）、消息（`jl-rabbitmq`）、可观测性（Prometheus、Grafana、Tempo、OTel Collector、cAdvisor、Nginx exporter）、实验控制（chaos-controller、Toxiproxy、toxiproxy-init）、负载（`jl-traffic`）。名字不在已知前缀里的落入「未分类」，不会被隐藏。
- 层次之间用带标签的虚线脊（spine）表达请求方向：`/api/ 轮询 · max_fails=2 · fail_timeout=5s`、`L2 / 锁 / 幂等 → 缓存 · 读写池 / Router → DB · 发布/消费 → MQ`。支撑面（观测 / 控制 / 负载）单独成块，并注明「不表示请求顺序」。
- 每张节点卡：容器名、角色、`state` 徽标、`health` 徽标、颜色点（running+healthy 绿、starting 黄、unhealthy 红、exited 灰）。`health=none` 明确提示为「没有 healthcheck，不代表故障」。
- `/chaos/actions` 按 `target` 匹配容器名，直接挂在该节点上（`badge.warn`，hover 显示 `action · status · 到期时间`；`data-testid="topology-faults-<container>"`）。
- dev / HA 的区分完全由「哪些容器在跑」推出：有 `jl-redis-*` 才显示「N 个 Redis 节点 + M 个哨兵」，只有 `jl-redis` 显示「dev 单机 Redis」；MySQL 同理，并注明 ha profile 下 dev 单机仍在运行时的情况。Toxiproxy 节点额外显示 `/chaos/toxiproxy/state` 的「代理 / 启用 / 毒化」计数（仅当该容器存在，失败静默降级）。

**实例视图**（`InstancesView.vue`，`data-testid="instances-page"`）

- 一个 `jl-app-*` 一张卡（`instance-card-app-1`…），显示容器 state/health、活动故障徽标、CPU（单核占比）、堆使用、请求速率、P99、活线程数；每项序列缺失时显示「无数据」而不是 0。
- 卡片底部「线程实验室 →」（`instance-open-thread-app-2`）把该实例作为 `focusInstance` 传给线程页，线程页挂载时直接选中 `app-2`，不注入任何故障；线程页原有的实例下拉仍可改选。
- 头部说明 Prometheus 5s 轮询；若 Prometheus 查询失败，整页出现 `instances-prom-note`，所有卡片保持「无数据」。

**缓存流动**（`CacheFlow.vue`，`data-testid="cache-flow"`）

- 横向四段：入口 `GET /api/products/{id}` → L1 Caffeine → L2 Redis → MySQL；三段连接线各有粒子，分别承载「全部缓存查询」「仅 L1 未命中」「回源」。
- L1/L2 卡片显示命中率条与 hit/miss 速率；DB 卡片显示回源速率与「占 L1 查询」比例；下方列出 `cache_db_fallbacks_total{reason}` 的 reason 拆分（miss / penetration / breakdown / avalanche）。
- 全空（Prometheus 返回空向量）时整卡显示 `cache-empty`「无数据」；序列存在但计数为 0 时正常显示 0 /s 与 `idle` 粒子（不再误报无数据）。
- 卡片头有暂停/播放按钮（`animation-toggle`，`aria-pressed`），暂停只停运动，数值轮询继续。

文件：`src/components/AnimationLab.vue`（页面与轮询）、`TopologyView.vue`、`InstancesView.vue`、`CacheFlow.vue`、`src/labModel.js`（角色分类与格式化，纯函数）、`src/api.js`（`queryProm`、`fetchToxiproxyState`）、`src/App.vue`（导航与视图切换）、`src/components/ThreadLab.vue`（`focusInstance`）、`src/style.css`、`vite.config.js`（dev 下 `/prom` 代理）。

# 2. 数据源与 PromQL

所有 Prometheus 查询走 Nginx 的 `/prom/` 同源代理：`GET /prom/api/v1/query?query=<urlencoded>`（instant query）。**不调用**应用内网的 `/lab/cache/*`。

| 视图 | 数据源 |
|---|---|
| 三个 tab 的状态栏 | `GET /lab-api/lab/guide/state`（向导状态/场景名）、`GET /lab-api/chaos/actions`（活动故障数）、`GET /lab-api/containers`（运行/总数） |
| 拓扑 | `/lab-api/containers` + `/lab-api/chaos/actions`；`jl-toxiproxy` 存在时才加 `GET /lab-api/chaos/toxiproxy/state` |
| 实例 | `/lab-api/containers`（决定卡片与健康）+ 下面 5 条 PromQL |
| 缓存流动 | 下面 3 条 PromQL（另有状态栏的 3 个 lab API） |

实例（`job` 默认 `seckill-app`，可用 `VITE_LAB_PROM_JOB` 覆盖）：

```
max by (instance) (process_cpu_usage{job="seckill-app"})
sum by (instance) (jvm_memory_used_bytes{job="seckill-app",area="heap"})
sum by (instance) (rate(http_server_requests_seconds_count{job="seckill-app"}[1m]))
histogram_quantile(0.99, sum by (le, instance) (rate(http_server_requests_seconds_bucket{job="seckill-app"}[1m])))
max by (instance) (jvm_threads_live_threads{job="seckill-app"})
```

`instance` 标签形如 `app-1:8080`，页面去掉端口后与容器名 `jl-app-1` 去掉 `jl-` 对齐。

缓存：

```
sum by (result) (rate(cache_l1_requests_total{job="seckill-app"}[1m]))
sum by (result) (rate(cache_l2_requests_total{job="seckill-app"}[1m]))
sum by (reason) (rate(cache_db_fallbacks_total{job="seckill-app"}[1m]))
```

派生值都在前端计算：`L1 命中率 = hit / (hit+miss)`、`L2 命中率` 同理（分母是 L2 查询，不是总请求）、`回源占 L1 = fallbackTotal / l1Total`。分母为 0 时返回 `null` → 显示「无数据」，不会出现 NaN。

空结果与零值语义：PromQL 结果为空数组 = 无数据；有序列但值 0 = 空闲。两个状态在页面上是不同文案。

# 3. 动画与教学点

- **粒子速度 ∝ 该段每秒速率**：`speed = clamp(rate × 0.06, 0.05, 2.2)`（每秒走完通道的比例），每帧向目标速度缓动（`lerp 4·dt`），5s 后拿到新速率时是平滑加速/减速，不是跳变。实现是手写 `requestAnimationFrame`：位置存在普通 `Map` 里，只用 `translate3d` 写合成层，不新增依赖、不触发重排。
- **为什么要分段**：入口 → L1 是全部缓存查询；L1 → L2 只承载 `result="miss"`；L2 → DB 承载 `cache_db_fallbacks_total` 的合计。三段同时可见时，能直接看出「L1 命中率越高，下游粒子越稀」。
- **教学点 1（穿透）**：开 null-cache/bloom 后 reason 里出现 `penetration`，DB 段变密，而 L2 段变化不大——说明防护放行后仍回源。
- **教学点 2（击穿/互斥）**：`breakdown` reason 增长对应同一热点重复回源；粒子是「每个回源一次」，与日志里的 `lock:product:*` 等待一起讲。
- **教学点 3（雪崩）**：`avalanche` reason、TTL 抖动前后 DB 段密度对比；L1 命中率条是「挡下多少」的即时读数。
- **教学点 4（拓扑）**：切换 dev / ha / db-ha / chaos-proxy profile 后刷新页面，节点与脊标签随实际容器变化；课程里可以直接说「图上没有的就是没跑」。
- **教学点 5（实例）**：注入 `cpu-burn` / `heap-leak` / `freeze` 后，卡片上的 CPU、堆、速率、P99 与拓扑上的故障徽标在同一屏出现，再点进线程页看同一实例的线程指纹。

拓扑不做逐节点粒子：容器清单没有边信息，凭空画请求路由会造假；方向用带标签的层次脊表达，边的细节留给 §7.4 的 Mermaid/Grafana。

# 4. 可访问性与 reduced-motion

- `prefers-reduced-motion: reduce`：首次进入（没有本地偏好）时动画默认暂停；系统偏好在会话中切换为 reduce 时立即暂停。页面显示 `cache-reduced-motion` 说明，用户仍可手动播放（显式覆盖）。动画相关的 CSS 过渡（进度条、比率条、按钮）在该媒体查询下关闭。
- 暂停按钮是原生 `<button>`，带 `aria-pressed`，文案在「暂停动画 / 播放动画」之间切换，状态同时写入 `localStorage["ha-lab-animation-paused"]`；暂停只停 `rAF` 推进，5s 轮询与数值照常。
- 粒子轨道 `aria-hidden="true"`：屏幕阅读器不会读到装饰元素；所有速率、命中率、reason 都在可见文本里，图例用文字解释「点的速度 = 每秒查询数」。
- 颜色不是唯一信息：state/health 同时有文字徽标，命中有百分比文本，故障有 action 名称与 tooltip；节点、tab 都是原生按钮，可键盘操作。
- `font-variant-numeric: tabular-nums` 让 5s 更新时数字宽度稳定，减少视觉抖动。

# 5. 延后的实栈冒烟（deferred live smoke）

本步按约定不启动 Docker、不跑 Playwright；`console-smoke.mjs` 未扩展。待拥有栈的会话完成 `npm run build` 部署后，建议补以下 **6 条**断言（都只用现有 `data-testid`，无需改实现）：

1. `nav-animation` 打开 `animation-page`，默认 `animation-tab-topology` 激活；`topology-node-jl-nginx` 与 `topology-node-jl-app-1` 可见，节点总数等于 `/lab-api/containers` 的 `data.length`（证明没有画不存在的组件）。
2. 经 API 对 `jl-app-2` 注入 `freeze`（TTL 5s）→ 下一次 5s 轮询后 `topology-faults-jl-app-2` 出现且文本含 `freeze`；`stop-all` 后徽标消失、告警不残留。
3. `animation-tab-instances` → 出现 `instance-card-app-1/2/3`；栈在线时 `instance-cpu-app-1` 与 `instance-rate-app-1` 不是「无数据」；`instance-open-thread-app-2` 跳到 `thread-page` 且 `thread-instance` 的值为 `app-2`。
4. `animation-tab-cache` → `cache-flow` 可见；启用 `jl-traffic` 后 `cache-lane-l1` 速率 > 0，且连续两帧 `flow-particle` 的 `transform` 发生变化；停流量并等待计数归零后该 lane 带 `idle` 类。
5. 点击 `animation-toggle` → `aria-pressed` 变为 `true`、`cache-motion-state` 为「动画暂停」，两帧后粒子 `transform` 不变，但 `animation-last-sync` 仍在推进（暂停不冻结轮询）。
6. `page.emulateMedia({ reducedMotion: 'reduce' })` 后重新加载 → `cache-reduced-motion` 可见且 `cache-motion-state` 为「动画暂停」；手动播放后粒子恢复运动。

可选第 7 条（不需要停栈）：用 `page.route('**/prom/api/v1/query**')` 返回空向量，断言 `cache-empty` 与实例卡片「无数据」出现，而不是显示 0。

**已知边界 / 后续**：

- 三个 tab 的容器与故障数据来自同一个 `Promise.all`，chaos-controller 不可用时整页报错（Prometheus 仍可达也不会单独渲染）。这是刻意的：令牌/控制器是控制台的前置条件；若以后要「只看指标」，需要把 Prometheus 读取从 lab API 失败中解耦。
- 拓扑的绿色来自 Docker health，不来自 Prometheus `up`；容器 running 但未被抓取时仍显示绿。需要在拓扑上表达「抓取是否成功」时，下一版可加 `up{job=...}`。
- 实例卡只覆盖 `jl-app-*`；`jl-seckill-app-jdk8` 只在拓扑里出现（D7 的范围），避免把 JDK 8 的数值混进三实例对比。
- 拓扑是「层次 + 带标签的脊」，不是逐节点边路由：边信息不在 `/containers` 里，硬画会造假。
