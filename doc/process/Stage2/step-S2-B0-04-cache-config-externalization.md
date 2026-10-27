# 步骤交接：S2-B0-04 缓存配置外置 + `LAB_CACHE_*` 接线 + `/lab/config` 有效值回读

> 任务/步骤：Stage2 B0-04 —— `TwoLevelCache` L2 侧硬编码外置、`app.lab.cache.*` 全族进 compose 与 `.env.example`、`GET /lab/config` 回读生效值
> 日期：2026-10-28
> 分支 / HEAD：worktree 自 `feature/stage2-workbench` 的 B0-03 基线 `a624c2d` fork，先 merge 至 `8b5e3a2`（B0-06），本步四笔提交见附录
> 关联任务：`doc/process/Stage2/stage2-开发测试与验收计划.md`「### B0-04」；工程规约 §1（配置外置，强制）
> 测试 / verify：模块全量 `Tests run: 469, Failures: 0, Errors: 0, Skipped: 0`，EXIT=0（基线 457 → 本步 +12：回读器 7 + L2 绑定 4 + L2 生效 1）
> 运行状态：本步为改代码 + 跑单测 + 静态核对 compose/.env 接线；**未跑任何 docker/compose 生命周期命令**，真机改 TTL 的 state/单键一致性验证归主会话（§存在缺陷项）
> 接手说明：前任代理 150 轮耗尽、零提交，留下 24 脏文件 + 3 新文件半成品；本步为收拢→甄别→验证→分笔提交，未重做实现

# 当前进展

- `TwoLevelCache` 的三个编译期常量（L2 写 TTL 5 分钟、键前缀 `cache:`、`clear()` 的 SCAN count 500）已变成 `app.lab.cache.l2.*` 配置，默认值与历史行为逐字节一致。
- L2 键前缀成为单一布局契约：缓存本体、实验播种、`GET /lab/cache/state`、预热服务、TTL 抖动调节器五方都经 `TwoLevelCacheSettings.l2PrefixFor(cacheName)` 解析，前缀改一次全体一致（原 `TwoLevelCacheManager.productL2KeyPrefix()` 由静态改为实例方法，正是为了让它读绑定值而不是读常量）。
- `app.lab.cache.*` 全族 59 个键首次完整进 `compose.base.yml` 的 `x-app-env` 块与 `.env.example`（基线 compose 里 `LAB_CACHE_*` 出现次数为 0），三处键集与默认值已 diff 校验一致。
- `GET /lab/config` 新增 `cacheConfig`：每条键的**生效值 + env 名 + 声明默认值 + 胜出的属性源**，由 `CacheEffectiveConfigReader` 从运行中的 `ConfigurableEnvironment` 现场读出，不建第二张常量表。
- 最新提交：`chore: wire the LAB_CACHE_* family into compose and .env.example`。

# 完成功能项

## 外置项清单（旧硬编码位置 → 新配置键 → env 名 → 默认值）

本步真正新外置的只有 3 项，全部在 L2 写侧：

| 旧硬编码位置 | 新配置键 | env 名 | 默认值 |
|---|---|---|---|
| `catalog/cache/TwoLevelCache.java` `static final Duration L2_TTL = Duration.ofMinutes(5)` | `app.lab.cache.l2.ttl-seconds` | `LAB_CACHE_L2_TTL_SECONDS` | `300` |
| `catalog/cache/TwoLevelCache.java` `static final String KEY_PREFIX = "cache:"` | `app.lab.cache.l2.key-prefix` | `LAB_CACHE_L2_KEY_PREFIX` | `cache:` |
| `catalog/cache/TwoLevelCache.java` `clear()` 内 `ScanOptions...count(500)` | `app.lab.cache.l2.clear-scan-count` | `LAB_CACHE_L2_CLEAR_SCAN_COUNT` | `500` |

其余 56 项**不是新外置**：它们早已在 `application.yml` 里以 `${LAB_CACHE_*:default}` 声明，但 compose 从未下发这些变量（Stage2 缺陷项「`LAB_CACHE_*` 未进 compose」），因此运行期只能靠镜像里的默认值。本步补齐接线，按组统计：

| 组（`app.lab.cache.` 下） | 键数 | env 前缀 | 本步动作 |
|---|---|---|---|
| `seed.*` | 11 | `LAB_CACHE_SEED_*` | compose/.env.example 接线 |
| `state.*` | 1 | `LAB_CACHE_STATE_*` | compose/.env.example 接线 |
| `penetration.*`（mode + null-cache + bloom） | 7 | `LAB_CACHE_NULL_*` / `LAB_CACHE_BLOOM_*` / `LAB_CACHE_DEFENSE_MODE` | compose/.env.example 接线 |
| `breakdown.*`（mutex + logical-expire） | 11 | `LAB_CACHE_MUTEX_*` / `LAB_CACHE_LOGICAL_*` | compose/.env.example 接线 |
| `avalanche.*`（ttl-jitter + l1-cache + db-fallback） | 10 | `LAB_CACHE_TTL_JITTER_*` / `LAB_CACHE_L1_*` / `LAB_CACHE_DB_FALLBACK_*` | compose/.env.example 接线 |
| `preheat.*`（含 `preheat.l1.*` 的 L1 尺寸） | 16 | `LAB_CACHE_PREHEAT_*` / `LAB_CACHE_L1_*` | compose/.env.example 接线 |
| `l2.*` | 3 | `LAB_CACHE_L2_*` | 新外置 + 接线（上表） |

全量 59 条「键 → env → 默认值」清单见附录 A。

## 主代码

- `catalog/cache/TwoLevelCache.java`：删除 `L2_TTL` / `KEY_PREFIX` 常量，写侧改用 `settings.l2Ttl()` / `settings.l2PrefixFor(name)`，`clear()` 改用 `settings.l2ClearScanCount()`；新增包级可见的 `l2Ttl()` / `l2ClearScanCount()` / `redisKeyPrefix()` 供回读与测试；`l2KeyPrefix(String)` 静态方法移除（布局归 settings）。
- `catalog/cache/TwoLevelCacheSettings.java`：record 由 4 组件扩到 7 组件（新增 `l2Ttl` / `l2KeyPrefix` / `l2ClearScanCount`），带启动期校验（TTL 必须为正、前缀不得空白、SCAN hint 必须为正）；新增 `l2PrefixFor(cacheName)` 作为键布局唯一出口；保留 3 参与 4 参便捷构造器 + `DEFAULT_L2_*` 回退值，B0-03 的 `micrometerStatsEnabled` 位置与默认未动。
- `catalog/cache/TwoLevelCacheManager.java`：`productL2KeyPrefix()` 改为实例方法读绑定 settings，新增 `settings()` 访问器。
- `catalog/cache/AvalancheProtectionConfig.java`：`L2TtlAdjuster` bean 的前缀改由 `cacheSettings.l2PrefixFor(PRODUCT_CACHE)` 提供。
- `lab/config/LabCacheL2Properties.java`（新）：绑定 `app.lab.cache.l2.*`，与其它 lab 属性类同口径——Java 侧不写默认值，yml 是默认值唯一来源，`@PostConstruct validate()` 让非法值直接启动失败。
- `lab/cache/preheat/CachePreheatConfig.java`：`twoLevelCacheSettings` bean 合入 L2 三值；`@EnableConfigurationProperties` 加 `LabCacheL2Properties`。
- `lab/cache/CacheExperimentDataService.java`、`lab/cache/preheat/CachePreheatService.java`：不再调静态前缀，改在构造期解析一次 `productL2KeyPrefix` 并复用。
- `lab/api/LabCacheConfigItem.java`（新）、`LabConfigResponse.java`、`LabInfoProvider.java`、`LabController.java`、`lab/config/LabConfig.java`：`/lab/config` 响应体加 `cacheConfig`，回读器由 `ConfigurableEnvironment` 装配。
- `application.yml`：`app.lab.cache` 下新增 `l2.{ttl-seconds,key-prefix,clear-scan-count}` 三项（含 L2 口径注释）。
- `compose.base.yml`：`x-app-env` 内新增 59 条 `${VAR:-default}`；`.env.example` 尾部新增同键注释块（含 L2/池口径中文说明）。

## 测试

- 新增 `lab/config/CacheEffectiveConfigReaderTest.java`（7 用例）：默认生效 / env 覆盖生效（`source=systemEnvironment`）/ 更高优先源覆盖时不得被改写标签 / 只报 `app.lab.cache.` 家族且按 key 排序 / 无占位符的键 env 与 default 为 null / 嵌套占位符 / **对真实 `application.yml` 跑一遍**，断言 59 条键每条都同时具备 env 名、生效值、声明默认值与来源。
- 新增 `lab/config/LabCacheL2PropertiesTest.java`（4 用例）：yml 三个键的 `${ENV:default}` 文本逐字固定；默认值绑定并 validate 通过；override 源在前时绑定值被替换（45 / `lab-l2:` / 1000）；ttl=0、前缀空白或缺失、scan=0 三类非法值启动失败。
- 扩展 `catalog/cache/TwoLevelCacheTest.java`（+1 用例，真 Redis 容器）：用非默认 settings 构造缓存后，值确实落在 `lab-l2:product:1` 且 `cache:product:1` 为空，键 TTL 在 1..30 秒内（历史常量是 300 秒）——证明配置真的驱动了写入，而不只是绑上了。
- 扩展 `lab/api/LabControllerTest.java`：`/lab/config` 断言 `cacheConfig[0]` 的 configKey/env/value/defaultValue/source 五项；测试桩用命名 `MockPropertySource("mockSource")` 承载 yml 形状的占位符。
- 签名适配（无新断言）：`RedisL2TtlAdjusterTest`、`CacheExperimentDataServiceTest`、`CachePreheatServiceTest` 改走 `TwoLevelCacheSettings.defaults()` / 新构造参数。

# 设计逻辑

- **为什么回读端不建第二张常量表**：`CacheEffectiveConfigReader` 只做三件事——从可枚举属性源发现 `app.lab.cache.` 键、用 `environment.getProperty(key)` 取生效值、从 `application.yml` 源里那条**尚未解析的 `${ENV:default}` 原文**反解 env 名与声明默认值。新增一个缓存配置键会自动出现在载荷里，不需要改回读代码；反之，任何"看起来配了其实没生效"的 override 也藏不住。
- **来源归属规则**：`source` 报告真正胜出的属性源名。env 名存在且胜出源正是声明占位符的那个源时，才标 `systemEnvironment`；若 profile 文件、`--arg` 或测试属性源覆盖了键，就按它自己的名字上报，不改标成默认值。验收要的"配置、应用有效值、键 TTL 三者一致"靠这一列区分。
- **L2 口径（与其余 TTL 的边界）**：`app.lab.cache.l2.ttl-seconds` 是 `TwoLevelCache` 写 Redis 时的物理 `SET ... EX` 寿命，只作用于缓存本体写入的键。它**不是**下面这些 TTL：`seed.hot-key-ttl-seconds`/`seed.avalanche-ttl-seconds` 是播种器自己写实验键的 TTL（不经缓存写侧）、`penetration.null-cache.ttl-seconds` 是空值标记 TTL、`breakdown.logical-expire.logical-ttl-seconds` 是值内时间戳表示的逻辑过期（物理保留另有 `retention-seconds`）、`preheat.l1.expire-after-write-seconds` 是 L1 Caffeine 的物理寿命。所以"改 L2 TTL"后核对一致性必须分别看：缓存本体键走新值、播种键保持播种值——这也解释了为什么单键 TTL 与 `GET /lab/cache/state` 的聚合计数要一起看而不能互相推断。
- **键前缀是布局契约而不是字符串**：前缀一旦可变，任何硬编码 `cache:` 的读方都会静默看到 0 键。因此五方（缓存、播种、state 视图、预热、TTL 抖动）统一从同一个 settings bean 求值，`TwoLevelCacheManager.productL2KeyPrefix()` 去静态化是这条约束的必然结果。
- **便捷构造器与回退值的取舍**：`DEFAULT_L2_TTL` 等保留只为让测试/工具的直接构造继续可编译，javadoc 明确"它们绝不是运行中应用的真相来源"；Spring 路径永远传绑定值。这是 B0-03 已确立的同一手法。
- **池口径**：计划 B0-04 条目里的「连接池 `pool=10`」经核查不在缓存侧——`application.yml:102/109` 的 `maximum-pool-size: 10` 是 Hikari MySQL write/read 池。缓存相关的池参数（`LAB_CACHE_LOGICAL_REFRESH_POOL_SIZE=2`、`LAB_CACHE_L1_REFRESH_POOL_SIZE=2`、`LAB_CACHE_PREHEAT_SCHEDULER_POOL_SIZE=1`、`LAB_CACHE_L1_MAXIMUM_SIZE=10000`）此前已外置，本步补齐了它们的 compose 接线。DB 池两项保持未动，作为发现项移交（见「补充」）。

# 存在缺陷项&TODO

- 真机验证全部未做，本步只到单测 + 静态接线核对。
- `doc/process/Stage2/指标字典.md` 未回填：`cacheConfig` 回读视图与 L2 TTL 口径行需登记（避免与并行批次改冲）。
- B0 收口（`step-S2-B0-00-closure.md`）需带上新基线数字 469。
- 已知限制：`CacheEffectiveConfigReader` 的 env 名/默认值反解依赖 yml 保持 `${ENV:default}` 单层占位符写法；带嵌套默认值的键会把内层占位符原样上报为 `defaultValue`（已有用例锁定行为，不会抛异常）。

## 真机样本验证（unverified，归主会话）

| 项目 | 状态 | 备注 |
|---|---|---|
| 设 `LAB_CACHE_L2_TTL_SECONDS=45` 起栈后，`GET /lab/config` 中该键 value=45 且 source=systemEnvironment | unverified | 单测层已证解析与归属规则 |
| 同次运行 `GET /lab/cache/state` 键数与单键 `TTL` 与回读值三者一致 | unverified | 计划 B0-04 验收原句 |
| 播种热键 TTL 不随 L2 TTL 变化（口径隔离的反证） | unverified | 见设计逻辑 L2 口径 |
| `LAB_CACHE_L2_KEY_PREFIX` 改值后 state 视图与播种/预热不出现"0 键"错位 | unverified | 五方共用同一解析点，单测已证前缀生效 |
| compose 起栈后 59 个变量确实下发到 app 容器（`docker compose config` 展开） | unverified | 本步禁止跑 compose 生命周期命令，仅做了 yml/compose/.env 三处键集与默认值 diff |

# 待定事项和注意事项

- 合并后主会话统一 verify；本 worktree 内 Maven 缺兄弟模块快照，命令必须带 `-am`。
- 未动清单（禁区遵守）：chaos-controller、seckill-app-jdk8、`AppProxyService`、`claims.yml`、stage2 三份设计/计划文档；compose 文件只做了编辑。
- B0-03 交接曾提醒"勿重排 `micrometer-stats-enabled` 键位"——compose 与 yml 中该键位置与默认均未动，注释里也标了 owned by B0-03。
- 下一步优先级：真机改 TTL 三态一致性（B0-04 验收）> 指标字典回填 > B0-07/B0-08。

# 补充

- **前任半成品甄别结论（order 包）**：前任把 `OrderApplicationService.MAX_RETRIES=10` / `RETRY_SLEEP_MILLIS=50` 外置成 `app.order.idempotency-retry.{max-attempts,sleep-ms}`（env `ORDER_IDEMPOTENCY_RETRY_*`），连带 3 个测试文件做构造签名适配（`SyncOrderOversoldCounterTest`、`OrderIdempotentRejectMeteringTest`、`SeckillConsumerOversoldCounterTest`）。**已全部回退**（含 yml `app.order` 块、compose 与 `.env.example` 的 `ORDER_IDEMPOTENCY_*` 行）。判定依据：① Stage1 硬编码台账 `doc/process/STAGE-01阶段交接-2026-10-26.md:282` 把该常量归在「订单」条目，与「缓存」条目（:281）分列；② 该重试预算属于订单幂等重放路径，无缓存语义；③ B0-04 的验收对象是缓存配置/`LAB_CACHE_*`/`/lab/config` 回读。计划 B0-04 条目之所以出现 `RETRY_SLEEP_MILLIS` 字样，是继承了 `架构现状与功能清单.md:260` 那条把 `L2_TTL`/`KEY_PREFIX`/`RETRY_SLEEP_MILLIS`/`pool=10` 打包写的缺陷句——同一条也带了明显不属于缓存批次的 DB 连接池项，可作为口径误挂的证据。
- **移交后续批次的发现项**：
  - 订单侧硬编码：`order/app/OrderApplicationService.java:36-37`（`MAX_RETRIES=10` / `RETRY_SLEEP_MILLIS=50L`）仍是常量，属订单域配置外置批次。
  - 数据源池：`application.yml:102` 与 `:109` 的 `maximum-pool-size: 10`（以及 `minimum-idle: 2`）是 yml 字面量、无 env 覆盖，违反工程规约 §1「时间/数量参数」；不在本批缓存范围，建议与 DB/连接类参数一并处理。
  - `chaos-controller` 侧 `DockerContainerGateway.MAX_TAIL=1000` / `LabController.MAX_TAIL=1000`（Stage1 台账同条）本批未触，仍在台账内。
- **收拢过程记录**：merge `feature/stage2-workbench`（快进到 `8b5e3a2`，无冲突）采用 stash→merge→pop，`.env.example` 与 B0-06 改动自动合并成功，已复核无冲突标记且 CRLF 保持。首跑（含 order 改动）为 `Tests run: 457, Failures: 1`，唯一失败是 `LabControllerTest` 断言 `source=mockSource` 而 `MockEnvironment` 默认源名为 `mockProperties`——判定为**测试写错而非实现写错**，改为显式命名源后通过；`CacheEffectiveConfigReaderTest` 在前任代码注释里被引用但从未创建，本步补齐而非删除该引用。另修一处 `TwoLevelCache.countL2Error` 被压成单行的排版缺陷与一处 import 字典序。

# 附录

## A. 全量接线清单（`app.lab.cache.*` 59 条：键 → env → 默认值）

| 键 | env | 默认值 |
|---|---|---|
| `seed.product-count` | `LAB_CACHE_SEED_PRODUCT_COUNT` | 1000 |
| `seed.start-id` | `LAB_CACHE_SEED_START_ID` | 90000 |
| `seed.protected-max-id` | `LAB_CACHE_SEED_PROTECTED_MAX_ID` | 1000 |
| `seed.avalanche-key-count` | `LAB_CACHE_SEED_AVALANCHE_KEY_COUNT` | 200 |
| `seed.avalanche-ttl-seconds` | `LAB_CACHE_SEED_AVALANCHE_TTL_SECONDS` | 60 |
| `seed.hot-key-index` | `LAB_CACHE_SEED_HOT_KEY_INDEX` | 999 |
| `seed.hot-key-ttl-seconds` | `LAB_CACHE_SEED_HOT_KEY_TTL_SECONDS` | 300 |
| `seed.write-batch-size` | `LAB_CACHE_SEED_WRITE_BATCH_SIZE` | 200 |
| `seed.name-prefix` | `LAB_CACHE_SEED_NAME_PREFIX` | 实验商品 |
| `seed.status` | `LAB_CACHE_SEED_STATUS` | ON_SALE |
| `seed.price` | `LAB_CACHE_SEED_PRICE` | 99.00 |
| `state.scan-count` | `LAB_CACHE_STATE_SCAN_COUNT` | 500 |
| `l2.ttl-seconds` | `LAB_CACHE_L2_TTL_SECONDS` | 300 |
| `l2.key-prefix` | `LAB_CACHE_L2_KEY_PREFIX` | `cache:` |
| `l2.clear-scan-count` | `LAB_CACHE_L2_CLEAR_SCAN_COUNT` | 500 |
| `penetration.mode` | `LAB_CACHE_DEFENSE_MODE` | off |
| `penetration.null-cache.key-prefix` | `LAB_CACHE_NULL_KEY_PREFIX` | `null:product:` |
| `penetration.null-cache.ttl-seconds` | `LAB_CACHE_NULL_TTL_SECONDS` | 10 |
| `penetration.null-cache.jitter-seconds` | `LAB_CACHE_NULL_JITTER_SECONDS` | 5 |
| `penetration.bloom.key` | `LAB_CACHE_BLOOM_KEY` | `bloom:product-ids` |
| `penetration.bloom.expected-insertions` | `LAB_CACHE_BLOOM_EXPECTED_INSERTIONS` | 10000 |
| `penetration.bloom.false-probability` | `LAB_CACHE_BLOOM_FALSE_PROBABILITY` | 0.01 |
| `breakdown.mutex.key-prefix` | `LAB_CACHE_MUTEX_KEY_PREFIX` | `lock:product:` |
| `breakdown.mutex.wait-ms` | `LAB_CACHE_MUTEX_WAIT_MS` | 200 |
| `breakdown.mutex.lease-ms` | `LAB_CACHE_MUTEX_LEASE_MS` | 2000 |
| `breakdown.mutex.on-lock-timeout` | `LAB_CACHE_MUTEX_ON_LOCK_TIMEOUT` | db |
| `breakdown.logical-expire.logical-ttl-seconds` | `LAB_CACHE_LOGICAL_TTL_SECONDS` | 30 |
| `breakdown.logical-expire.retention-seconds` | `LAB_CACHE_LOGICAL_RETENTION_SECONDS` | 600 |
| `breakdown.logical-expire.max-entries` | `LAB_CACHE_LOGICAL_MAX_ENTRIES` | 10000 |
| `breakdown.logical-expire.refresh-pool-size` | `LAB_CACHE_LOGICAL_REFRESH_POOL_SIZE` | 2 |
| `breakdown.logical-expire.refresh-queue-capacity` | `LAB_CACHE_LOGICAL_REFRESH_QUEUE_CAPACITY` | 100 |
| `breakdown.logical-expire.keep-alive-seconds` | `LAB_CACHE_LOGICAL_REFRESH_KEEP_ALIVE_SECONDS` | 60 |
| `breakdown.logical-expire.thread-name-prefix` | `LAB_CACHE_LOGICAL_REFRESH_THREAD_PREFIX` | breakdown-refresh |
| `avalanche.ttl-jitter.percent` | `LAB_CACHE_TTL_JITTER_PERCENT` | 20 |
| `avalanche.l1-cache.refresh-interval-seconds` | `LAB_CACHE_L1_REFRESH_INTERVAL_SECONDS` | 10 |
| `avalanche.l1-cache.stale-window-seconds` | `LAB_CACHE_L1_STALE_WINDOW_SECONDS` | 60 |
| `avalanche.l1-cache.max-entries` | `LAB_CACHE_L1_MAX_ENTRIES` | 10000 |
| `avalanche.l1-cache.refresh-pool-size` | `LAB_CACHE_L1_REFRESH_POOL_SIZE` | 2 |
| `avalanche.l1-cache.refresh-queue-capacity` | `LAB_CACHE_L1_REFRESH_QUEUE_CAPACITY` | 100 |
| `avalanche.l1-cache.keep-alive-seconds` | `LAB_CACHE_L1_KEEP_ALIVE_SECONDS` | 60 |
| `avalanche.l1-cache.thread-name-prefix` | `LAB_CACHE_L1_REFRESH_THREAD_PREFIX` | avalanche-l1-refresh |
| `avalanche.db-fallback.permits` | `LAB_CACHE_DB_FALLBACK_PERMITS` | 50 |
| `avalanche.db-fallback.period-ms` | `LAB_CACHE_DB_FALLBACK_PERIOD_MS` | 1000 |
| `preheat.enabled` | `LAB_CACHE_PREHEAT_ENABLED` | true |
| `preheat.top-n` | `LAB_CACHE_PREHEAT_TOP_N` | 200 |
| `preheat.hot-list-key` | `LAB_CACHE_PREHEAT_HOT_LIST_KEY` | `hot:products` |
| `preheat.tracker.capacity` | `LAB_CACHE_PREHEAT_TRACKER_CAPACITY` | 100000 |
| `preheat.tracker.decay-interval-seconds` | `LAB_CACHE_PREHEAT_TRACKER_DECAY_INTERVAL_SECONDS` | 30 |
| `preheat.tracker.flush-interval-seconds` | `LAB_CACHE_PREHEAT_TRACKER_FLUSH_INTERVAL_SECONDS` | 10 |
| `preheat.load.chunk-size` | `LAB_CACHE_PREHEAT_LOAD_CHUNK_SIZE` | 50 |
| `preheat.load.chunk-pause-ms` | `LAB_CACHE_PREHEAT_LOAD_CHUNK_PAUSE_MS` | 50 |
| `preheat.load.startup-delay-ms` | `LAB_CACHE_PREHEAT_STARTUP_DELAY_MS` | 1000 |
| `preheat.load.periodic-interval-seconds` | `LAB_CACHE_PREHEAT_PERIODIC_INTERVAL_SECONDS` | 300 |
| `preheat.load.scheduler-pool-size` | `LAB_CACHE_PREHEAT_SCHEDULER_POOL_SIZE` | 1 |
| `preheat.load.thread-name-prefix` | `LAB_CACHE_PREHEAT_THREAD_PREFIX` | cache-preheat |
| `preheat.l1.maximum-size` | `LAB_CACHE_L1_MAXIMUM_SIZE` | 10000 |
| `preheat.l1.refresh-after-write-seconds` | `LAB_CACHE_L1_REFRESH_AFTER_WRITE_SECONDS` | 10 |
| `preheat.l1.expire-after-write-seconds` | `LAB_CACHE_L1_EXPIRE_AFTER_WRITE_SECONDS` | 60 |
| `preheat.l1.micrometer-stats-enabled` | `LAB_CACHE_L1_MICROMETER_STATS_ENABLED` | true（B0-03 所有，键位未动） |

## B. 提交

- `feat: externalize TwoLevelCache L2 TTL, key prefix and SCAN hint`
- `feat: read back effective cache config through GET /lab/config`
- `chore: wire the LAB_CACHE_* family into compose and .env.example`
- `docs: hand off S2-B0-04 cache config externalization`（本文件）

## C. 复现与证据

- 全量：`cd code && mvn -f pom.xml -pl services/seckill-app-jdk21 -am test > /tmp/b004full.log 2>&1; echo "EXIT=$?"` → `Tests run: 469, Failures: 0, Errors: 0, Skipped: 0`，EXIT=0
- 聚焦：`mvn -f pom.xml -pl services/seckill-app-jdk21 -am test -Dtest='CacheEffectiveConfigReaderTest,LabCacheL2PropertiesTest,LabControllerTest,TwoLevelCacheTest,LabCachePropertiesTest' -Dsurefire.failIfNoSpecifiedTests=false` → 7/4/9/6/11 全绿（`TwoLevelCacheTest` 需 Redis 容器）
- 接线一致性核对：分别抽出 `application.yml` 的 `${LAB_CACHE_*:default}`、`compose.base.yml` 的 `${LAB_CACHE_*:-default}`、`.env.example` 的注释键，排序 diff → 59/59/59，默认值完全一致，无缺失键
- 证据日志：`/tmp/b004f.log`（接手首跑，457/Failures: 1）、`/tmp/b004g.log`（order 回退后、修 MockEnvironment 前的编译失败）、`/tmp/b004-focus.log`、`/tmp/b004full.log`

## D. 关键文件

- 配置链：`application.yml` `app.lab.cache.l2.*` → `LabCacheL2Properties` → `CachePreheatConfig.twoLevelCacheSettings` → `TwoLevelCacheSettings.l2PrefixFor/l2Ttl/l2ClearScanCount` → `TwoLevelCache` / 播种 / state / 预热 / TTL 抖动
- 回读链：`ConfigurableEnvironment` → `CacheEffectiveConfigReader.read()` → `LabInfoProvider.config(toggleService, items)` → `LabConfigResponse.cacheConfig` → `GET /lab/config`
- 接线：`code/lab/docker/compose.base.yml`（`x-app-env`）、`code/lab/docker/.env.example`（尾部缓存族块）
