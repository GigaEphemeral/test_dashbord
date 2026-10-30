# 本地高可用 / 高并发教学实验室 · 详细设计文档索引

> 目的：把 `doc/design/Stage1/2026-10-26-ha-concurrency-lab-design.md` 的整体设计，拆成可按主题阅读、可对照代码、可在 5 分钟内演示的详细设计文档。
> 代码基线：`294798a`（01–03 编写时的基线）。04–11 由其他代理在相同/邻近基线上编写，合入时未改写正文基线，行号漂移请按符号名重新定位。
> 文档范围：只描述本仓库已合并的代码；设计稿里有、代码里没有的能力一律标注为「未实现 / 待补」，不按已交付来写。

## 1. 阅读顺序

建议按下面的顺序读。01–03 是底座与主链路；04–11 是按主题拆开的专题文档。

| # | 文档 | 主题 | 状态 |
|---|---|---|---|
| 01 | [01-总体架构与运行拓扑](01-总体架构与运行拓扑.md) | Compose 分层、16/25/25 服务不变式、网络与宿主端口、启动顺序与健康门、内存预算 | 已完成 |
| 02 | [02-业务主链路](02-业务主链路.md) | `catalog` / `inventory` / `order` 三域、Flyway、同步下单与幂等、异步秒杀与补偿、读写路由 | 已完成 |
| 03 | [03-缓存体系](03-缓存体系.md) | L1 Caffeine → L2 Redis → DB、Redis 故障降级、M4-1 穿透、M4-2a 击穿、M4-2b 雪崩、M4-3 预热、实验播种与 `/lab/cache/*` | 已完成 |
| 04 | [04-Redis高可用](04-Redis高可用.md) | Redis 高可用：Sentinel 拓扑、主从切换、客户端发现与演练 | 已完成 |
| 05 | [05-MySQL高可用与读写路由](05-MySQL高可用与读写路由.md) | MySQL 高可用与读写路由：InnoDB Cluster、Router 6446/6447、双数据源接线与切换 | 已完成 |
| 06 | [06-故障注入与实验向导](06-故障注入与实验向导.md) | chaos-controller 容器动作、TTL 看门狗、场景 JSON、向导状态机、操作台 | 已完成 |
| 07 | [07-线程实验室](07-线程实验室.md) | 七种故障线程、单线程生命周期、指标指纹与排查 | 已完成 |
| 08 | [08-双JDK与JVM调优](08-双JDK与JVM调优.md) | JDK 8/21 外壳、镜像与启动参数、对比方法与调优台 | 已完成 |
| 09 | [09-压测与观测](09-压测与观测.md) | k6 场景（含 `cache-lab` / `db-failover` / `ha-failover` / `d7`）、bench/compare、归档、`run_id` 关联、Prometheus/Grafana/Tempo 面板 | 已完成 |
| 10 | [10-操作台与API](10-操作台与API.md) | Vue 操作台、`/lab-api/**`、`/lab/**`、token 与容器白名单 | 已完成 |
| 11 | [11-配置与安全边界](11-配置与安全边界.md) | 配置外置、环境变量、密钥与端口暴露、实验安全边界 | 已完成 |

> 各专题文档正文的「路径:行号」以它们各自头部的基线提交为准；本索引状态只表示文档已合入，不表示文档中的行号在当前 HEAD 仍然精确。不要把「未实现」理解成代码不存在：以第 3.1 节清单为准。

## 2.「代码对照」约定

每篇文档的核心章节是一张 **代码对照表**，固定四列：

| 列 | 含义 |
|---|---|
| 概念/行为 | 读者要理解的设计概念或运行行为 |
| 类或方法 | 承载它的 Java 类/方法、脚本变量/函数或配置键；用反引号包住可在源码中检索的符号 |
| `path/to/file:line` | 仓库根目录相对路径 + **该基线提交**下的行号 |
| 说明 | 该行为的关键语义、边界或已知偏差 |

约定：

- 行号是 **写文档时** 用 `grep -n` / 读文件取得的，不是估算；每篇文档头部记录自己的 `代码基线`。
- 代码一旦继续合并，行号会漂移；请先 `git rev-parse --short HEAD` 对照文档头部的基线，再按符号名重新定位，不要盲信行号。
- 只引用真实存在的文件与真实行；不把设计稿的类名、端点或面板当成已实现代码。
- 表格中的 `代码基线` 与 `path:line` 只对本仓库有效；脚本路径统一从仓库根写起（如 `code/lab/scripts/up.sh`）。

## 3. 当前基线的硬事实

1. **服务数不变式**（`docker compose … config --services` 实测）：`compose.base.yml` + `compose.dev.yml` 默认渲染 **16** 个服务；`--profile ha`（+`compose.ha.yml`）**25**；`--profile db-ha` 口径下的 `compose.mysql.yml`（+9）**25**；`--profile dual-jdk` **17**；`--profile chaos-proxy`（+`compose.toxiproxy.yml`）**18**。
   两个 overlay 同时选中是 **34 个服务**，但 `compose.mysql.yml` 里的 `jl-mysql-cluster-init` 与 `jl-mysql-router-init` 是一次性 job，跑完即退出，所以**常驻容器是 32 个**——引用这两个数字时务必区分「服务数」与「常驻容器数」。
2. **`db-ha` 不是 `up.sh` 的 profile**：`up.sh` 一次只接受一个 profile 且显式拒绝 `db-ha`；MySQL InnoDB Cluster 走独立脚本 `code/lab/scripts/db-ha-up.sh`（集群需要先建组、再引导 Router，有固定顺序）。
3. **应用侧 MySQL 双模式已真机验收**：`MYSQL_MODE=standalone|router` 已实现（`DataSourceAccessProperties`），默认与所有 compose 渲染仍是 `standalone`；`router` 模式写 6446/读 6447，杀主重选、Router 跟随、读路径保持 200 均已真机通过，并带 k6 压测版本（见 `doc/process/Stage1/step-31-mysql-router-live-drill.md`、`step-48` 第 6 节）。
4. **缓存四族防护全部已合并**：M4-1 穿透、M4-2a 击穿（含 publish-through 与 `retention-seconds > hot-key-ttl-seconds` 审核修复）、M4-2b 雪崩（`ttl-jitter` / `l1-cache` / 熔断降级 + 限流）、M4-3 Caffeine 预热（ZSet 热点榜、启动/周期预热、L1 `refreshAfterWrite` 外部化）均已接入读路径；seed 提供同 TTL 键与热点键用于触发。见 03 §12。

### 3.1 未实现 / 未合并清单（统一口径）

| 能力 | 状态 | 证据 / 说明 |
|---|---|---|
| 雪崩策略（`ttl-jitter` / `l1-cache` / 熔断降级 + 限流） | **已合并** | `AvalancheProtectionConfig` + `ProductAvalancheDefense` 已接入读路径；seed 提供同 TTL 键用于触发。见 03 §12 |
| Caffeine 热点预热（ZSet、启动/周期预热、`refreshAfterWrite`） | **已合并** | M4-3（`edaa5e2` 等，merge `6e5d776`）；03 正文章节已同步。 |
| 线程快照（`jcmd Thread.print -l` / 堆直方图落盘） | **已合并** | `FileLifecycleSnapshotSink` + `ThreadSnapshotService`：freeze 落盘 jcmd bundle、保留策略与 Grafana annotation（step-35）；JDK 21 与 JDK 8 两侧均真机产出 bundle（step-52/53）。 |
| 应用侧 MySQL Router 双数据源（`MYSQL_MODE`） | **已合并并真机验收** | `DataSourceAccessProperties` + `MySqlModeSwitchTest`；6446/6447 切换与带压测演练均通过。见 `step-29`、`step-31`、`step-48` §6 |
| 集群状态指标（`mysql_cluster_member_state` / `mysql_cluster_primary_info`） | **已合并** | `MysqlClusterMonitor` 导出 `mysql_cluster_member_state` / `mysql_cluster_primary`（`_info` 被剥）；`mysqld-exporter-ha` job 已内联（step-30/38）。 |
| Toxiproxy / `chaos-proxy` | **已合并** | `compose.toxiproxy.yml` 覆盖层 + `ToxiproxyController`（toxic/reset/disable/enable，step-39/40）；`up.sh --profile chaos-proxy`。 |
| D6 / D7 / D8 / D10 / D11 面板 | **已合并** | `ha-d6/7/8/10/11.json` 均已入库（step-36/37/41/51/38）；D5 由 Tempo UI 承担 |
| M4-2 评审修复（publish-through / retention 校验） | **已合并** | `34c0dfc` / `426e9c9` / `34c5df0`（2026-10-27） |
| k6 `cache-lab` 缺陷制造（穿透探针 / 热点键 / 雪崩重武装） | **已合并** | `code/lab/k6/scenarios/cache-lab.js`；八模式各自 dwell 内只让对应防护族计数变化，真机证据见 `step-56`。 |

### 3.2 未收口清单（2026-10-27 文档整理时按代码逐条证伪后登记）

> 这一节是**唯一的「还有什么没做」口径**。各 `step-*.md` 的「仍未完成」按当时状态写成，
> 已落地的条目以本表为准；本表只登记**代码当前仍然成立**的缺口，全部带证据。
> 分类：`缺陷`=代码里有坑会误导演示；`未接线`=能力存在但环境变量/抓取没接上；`未做`=能力确实不存在；
> `未验证`=代码在但真机没跑过；`口径`=不是 bug，是读法容易被误解。
>
> **2026-10-30 复核**：下表 7 行已收口（行内标「已收口」），其余条目仍按代码成立；域计数与分类计数保留 Stage2 起点快照。

| 域 | 缺口 | 类别 | 证据 | 对演示的影响 |
|---|---|---|---|---|
| 缓存 | `POST /lab/cache/reset` | 已收口（2026-10-30 复核） | `LabCacheKeysController` 的 `/lab/cache/reset` + 控制台缓存页已落地，按前缀 SCAN 限额清理 | 不再需要重启或手工清 Redis |
| 缓存 | `L2_TTL` / `KEY_PREFIX` / clear SCAN 已外置 | 已收口（2026-10-30 复核） | `TwoLevelCacheSettings` + `LabCacheL2Properties`（`LAB_CACHE_L2_*`） | 改 L2 TTL/前缀只需重启，不再重打镜像 |
| 缓存 | 当前防护模式**不进 Prometheus**，app 侧序列永远没有 `mode` 标签 | 口径（有意） | `step-55` §5-3；D10 靠时间窗 + `k6_cache_lab_mode_switches_total` 对齐 | k6 侧与 app 侧是两条时间轴，读图要先认切换点 |
| 缓存 | `cache_db_fallbacks_total{reason="miss"}` 与 `{reason="penetration"}` 对同一次查库**双计**，D10 未做扣减 | 口径 | `catalog/cache/TwoLevelCache.java:116-117,158` + `ProductPenetrationDefense.java:43-45` | D10「占比」面板（id=3）的分子分母口径要在讲解时说清 |
| 缓存 | seed 的 200 个雪崩键共用同一个 TTL（缺陷本身就这么造），app 侧没有 TTL 打散的写入口 | 未做 | `CacheExperimentDataService` 单 TTL 写入；场景侧用 `CACHE_LAB_RESEED_MODES` 绕行 | 必须靠 k6 按 dwell 重 arm；手工演示要自己掐时间点 |
| 缓存 | `LAB_CACHE_*` 已写进 compose/`.env.example` | 已收口（2026-10-30 复核） | `compose.base.yml` 现有 72 处 `LAB_CACHE_*`（seed/jitter/fallback 等） | 改参数走 `.env` + 重建，不再手传 env |
| 缓存 | `app.lab.cache.penetration.mode` 键名与实际承载（穿透+击穿+雪崩）不符，未改名 | 口径 | 03 文档与配置键仍用旧名 | 读配置时容易以为只管穿透 |
| 线程 | `thread_lab_injected_total` 只有 `mode` 标签，没有 `hold` | 未做 | `lab/thread/ThreadLabMetrics.java:37-40` | D6 无法区分 `auto`/`manual` 两种保持方式的注入量 |
| 线程 | 快照耗时 Timer 没开直方图，没有 `_bucket`，D6 只能给均值/`_max`，**做不了 P95** | 未接线 | `lab/thread/ThreadSnapshotMetrics.java:63-66` 无 `publishPercentileHistogram()`/`sla()` | 面板文案已改成「均值/最大值」，别再按 P95 讲 |
| 线程 | `ha-d2.json` 里 `thread_lab_lifecycle_state_code` 的 `legendFormat` 写了不存在的 `{{thread}}` | 缺陷 | `ha-d2.json:394`；该指标只有 `instance`/`mode` 维度 | 图例显示空标签，容易被当成指标没数据 |
| 线程 | 全局 `stop-all` 只覆盖故障注入，不覆盖生命周期演示机 | 未做 | `lab/api/LabThreadController.java:73-81` 只委托 `service.stopAll()` | 演示后生命周期机可能仍停在 ACTIVE，需要单独 `lifecycle/reset` |
| 线程 | `FaultActionTest` 的 stop-leak 用例偶发抖动，未修 | 缺陷 | `step-35` §4（内层循环缺 `!stopped` 判定） | 全量 reactor 偶发红灯；先判定是测试错还是实现错（`doc/avoid_common_error/13`） |
| 订单 | `OrderApplicationService` 的 `MAX_RETRIES=10` / `RETRY_SLEEP_MILLIS=50` 硬编码 | 缺陷 | `order/app/OrderApplicationService.java:36-37` | 重试预算不可调，压测下想改要重打镜像 |
| 订单 | 读写数据源 `pool=10` / `idle=2` 硬编码 | 缺陷 | `step-29` §4；`DataSourceAccessProperties` 未暴露这两项 | router 模式下想压连接池只能改代码 |
| 订单 | RabbitMQ 无 publisher confirm / DLQ / 消费重试；状态 JSON 覆盖写无 CAS；`restore` 的 EXISTS+INCRBY 非原子且无对账 | 未做 | `step-16` §4 清单仍然成立 | 异步秒杀的可靠性边界只能口头讲，演示里真会丢 |
| 订单 | `GlobalExceptionHandler` 对未知路径返回 500 而非 404 | 缺陷 | 无 `NoResourceFoundException` 映射；`smoke.sh` 已按 500 断言 | 误判成应用故障 |
| 韧性 | 开关是**每实例内存态**且 chaos-controller 不做广播 | 未做 | `appproxy/AppProxyService.java` 白名单不含 `/lab/toggles`、`/lab/config`；全仓无 broadcast | 三个实例要逐个 `docker exec` 改，演示容易只改一台得出反结论 |
| 韧性 | 限流只作用在异步秒杀路径；降级 202 不带 requestId | 未做 | `step-17` §4 | 「限流关掉就全绿」只对手动秒杀成立 |
| 韧性 | toggle 变更无审计日志 | 未做 | `step-17` §4 | 演示后无法回答「谁在什么时候关了什么」 |
| 观测 | Redis HA exporter 抓取 | 已收口（2026-10-30 复核） | `redis-exporter-ha` job（redis-1/2，`node` 标签）；Sentinel 本身不暴露 `/metrics` | D11 的 Redis 面板在 HA 下有数据 |
| 观测 | MySQL Router 自身无指标，只有 6446/6447 探测 | 未做 | `db-cluster-status.sh:70-71,134,240-249`；D11「Router 状态」分组是文本面板 | Router 单点只能用间接证据（`hikaricp_connections_*{pool=write-pool|read-pool}`）讲 |
| 观测 | 代码注册名 `mysql_cluster_primary_info` 与面板查询名 `mysql_cluster_primary` 不一致（Micrometer 剥 `_info`） | 口径 | `doc/avoid_common_error/02` | 照代码名写 PromQL 会查不到数 |
| 观测 | 文档断言校验器存在但**未接进 verify/CI** | 未接线 | `grep docclaims code/lab/scripts/verify.sh` 为空 | 「文档说谎」仍靠人工发现（`doc/avoid_common_error/14`） |
| 观测 | Mermaid 与 `diagrams/` 的 PNG/SVG 无渲染校验，也没有 CI 出图 | 未验证 | `step-42` §4；仓库无 `.github/workflows/` | 图只做过围栏配对与图类型检查，视觉效果未证 |
| 混沌 | `code/lab/scenarios/` 只有 3 个 JSON（`quick-failover`、`ha-app-failover`、`redis-sentinel-failover`），没有 `mysql-failover` / `db-failover` / `cache-*` | 未做 | 目录实测；k6 侧 `db-failover.js` 是硬编码时间线 | 控制台向导里选不到数据库切换与缓存实验场景 |
| 混沌 | 控制器重启丢失活跃动作登记（无对账）；暂停态只存内存 | 未做 | `step-21` §4、`step-22b` §4 | 演练中途重建 controller，TTL 看门狗不会接管旧动作 |
| 混沌 | `/chaos/actions` 返回 `paused` / `remainingMs` | 已收口（2026-10-30 复核） | `ChaosActionView` 已带两字段（B2-04a） | 「TTL 冻结」可直接看剩余时间 |
| 混沌 | `redis-sentinel-failover.json` 硬编码打击 `jl-redis-1` | 缺陷 | `code/lab/scenarios/redis-sentinel-failover.json` | 主不在 redis-1 时演练打在副本上，症状全变；跑前必须 `sentinel-status.sh` 核对 |
| 混沌 | Toxiproxy 只演练过 `latency`/`timeout`，`bandwidth` 未验证；无对应 k6 场景；重启丢代理且无 keeper；verify 无 Toxiproxy 断言；`--profile dev` 后可能残留 `jl-toxiproxy*` | 未验证/未做 | `ToxicType.java:16-20`；`ls code/lab/k6/scenarios/`；`step-39` §6、`step-43` §4 | 网络故障这一族只有一半能演示 |
| 混沌 | `CHAOS_APP_PROXY_INSTANCES` 已进 compose | 已收口（2026-10-30 复核） | `compose.base.yml` 注入 `CHAOS_APP_PROXY_*` | 窄代理实例集合由 `.env` 控制 |
| 压测 | `soak.js` / `stress.js` 从未按设计时长跑过（30m / 500 VU 阶梯） | 未验证 | `doc/process/Stage1/benchmark/` 无对应归档 | 长稳与拐点结论都是外推，别当实测引用 |
| 压测 | 没有独立的 Caffeine 预热场景（`caffeine-warmup.js` 不存在） | 未做 | `ls code/lab/k6/scenarios/` | 预热只能靠 `POST /lab/cache/preheat` 手动触发演示 |
| 压测 | `cache-lab` 的 `db-fallback` dwell 与 `limiter_rejections` 还没有正向真机证据 | 未验证 | `step-56` §5 第 1 条（修 `maxDuration` 前被截断） | D10 的限流面板目前仍是空的 |
| 调优 | `tune.sh` 无参数扫描；`benchmark/jvm-comparison/`、`benchmark/jvm-tuning/` 归档目录不存在，compare 未打通 | 未做 | `ls doc/process/Stage1/benchmark/` 只有两份散 `tuning-*.md` | D8 的对比是手工两轮，不可复算 |
| 调优 | 只对比过 `-Xmx512m` vs `-Xmx256m`；G1/ZGC 未跑 | 未验证 | `step-51` §5-4 | 别把「JDK21 一定用 G1」当实验结论 |
| 控制台 | 线程页 Playwright 只读状态，没有生命周期点击/快照断言；快照按钮未真点 | 未验证 | `console-smoke.mjs`（42 条断言无 lifecycle 点击） | 控制台线程页只有 API 级真机证据 |
| 控制台 | D7（双 JDK 对比）没有控制台入口，只有 Grafana 面板 | 未做 | `dashboard/src/components/` 无对应组件 | 讲 JDK 对比要切到 Grafana |
| 数据 | `reset-lab.sh` 按拓扑解析重置目标 | 已收口（2026-10-30 复核） | B0-06 起从当前栈解析；`RESET_MYSQL_CONTAINER` 仍可覆盖 | router 模式下也能清对集群 |

### 3.3 已落地、但个别文档正文仍在说反话的位置

| 位置 | 旧说法 | 现状 |
|---|---|---|
| `details/08:265`、`details/08:375` | D8 调优面板仍缺 | 已回改（2026-10-30）：D8 已落地，正文已翻正 |
| `details/09:447` | 面板缺 D8 | 已回改（2026-10-30）：现为「面板已齐」 |
| `details/diagrams/README.md:24`、`:123` | D5/D7/D8 仍缺图 / D8 待补 | 已回改（2026-10-30）：D7/D8 已落地，D5 仍由 Tempo 承担 |
| `step-49:204` 的「不要当成过期修掉」保护名单 | 列了 D8 面板、缓存控制台页、`/lab/cache/**` 代理 | 历史交接不回改，已加指针（2026-10-30）：仅「SIGSTOP 打主库」仍未做 |
| `details/01:438`「仍未落地」 | D8 调优面板、缓存实验控制台页、`/lab/cache/**` 窄代理 | 已回改（2026-10-30）：只剩 SIGSTOP 演练，其余均已落地 |
| `details/05:307`「Prometheus 未抓取」 | `prometheus/mysql-ha-scrape.yml` 未接进 `prometheus.yml` | 已回改（2026-10-30）：job 已内联，D11 有数据 |
| `code/lab/scripts/verify.sh` 注释 | 旧 baseline 数字 | 已改为 **110 PASS / 1 WARN / 0 FAIL**（2026-10-30，B5-06 三个实例 cache 断言） |
| `db-ha-up.sh` 内存提示 | 「The cluster costs roughly 1.8GB」 | 已改为「声明 2.1GB / 实测约 1.87GB」（2026-10-30） |

其余 `step-*.md` 里「未做 X」的表述，若 X 已落地，一律以 §3.1/§3.2 为准，不再逐份改写历史交接（历史交接保持原样，只在被引用处加指针）。

## 4. 文档状态与维护

- 01–11：全部已交付，包含代码对照表、配置项表、5 分钟演示步骤、已知缺陷/边界与关联文档。
- 专题文档正文按各自基线编写；合并顺序晚于文档基线的能力（M4-3 预热、M4-2 审核修复、51a `MYSQL_MODE`）以第 3.1 节状态为准，正文如未提及不等于代码不存在；「还有什么没做」只看 §3.1 与 §3.2。
- Mermaid 图使用标准 `flowchart` / `sequenceDiagram` / `stateDiagram-v2` 语法；本环境没有渲染器，**图的视觉效果未经验证**，只做了围栏配对与图类型检查。
- 文档只写 `doc/design/Stage1/details/`；不修改源码、测试、compose、脚本、`.env*` 或其他文档。

## 5. 关联入口

- 总体设计：[`../2026-10-26-ha-concurrency-lab-design.md`](../2026-10-26-ha-concurrency-lab-design.md)
- 数据库方案对比：[`../数据库高可用方案对比.md`](../数据库高可用方案对比.md)
- 工程规约：[`../工程规约.md`](../../工程规约.md)
- 阶段交接（含缺陷/边界与未验证项）：[`../../process/STAGE-01阶段交接-2026-10-26.md`](../../../process/STAGE-01阶段交接-2026-10-26.md)
- 运行与演示脚本：`code/lab/scripts/`；Compose：`code/lab/docker/`；场景：`code/lab/scenarios/`；压测：`code/lab/k6/`；操作台：`code/lab/dashboard/`。
