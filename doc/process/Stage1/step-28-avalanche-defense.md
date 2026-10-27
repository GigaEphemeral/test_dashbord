> 步骤交接 · M4-2 缓存雪崩防护（ttl-jitter / l1-cache / db-fallback）
> 日期：2026-10-27
> 分支 / HEAD：worktree 分支 `worktree-agent-general-purpose-6412944d`（基线 `6e5d776`，`feature/ha-lab`）/ `064cea4 feat: add TTL-jitter, L1-first and bounded DB-fallback avalanche defenses`、`7bf833b test: prove avalanche modes and fix two test expectations`、本文件 `docs:` 提交
> 关联任务：M4-2（设计 8.4 雪崩行，`doc/design/Stage1/2026-10-26-ha-concurrency-lab-design.md:340`）；消费 seed 的 200 个同 TTL 键（`90000..90199`，60s）；D10 面板 / console 归 M4-4
> 测试 / verify：`mvn -pl services/seckill-app-jdk21 -am test` **BUILD SUCCESS**；reactor 各模块 `Tests run`：platform-common 10、platform-observability 4、seckill-core 13、**seckill-app-jdk21 394**（0 failures / 0 errors / 0 skipped，改动前同为 394、其中 2 处测试期望错误）。M4-1/M4-2a/M4-3 既有缓存套件全绿：`ProductPenetrationDefenseTest` 8、`ProductBreakdownDefenseTest` 20、`TwoLevelCacheTest` 5、`LabCachePreheat*`、`CacheExperimentDataServiceTest` 11。未跑 Docker/compose、未跑 verify.sh/smoke、未在实栈调用任何 `/lab/cache/*`（遵守本会话边界）
> 运行状态：仅 reactor 单测/IT；实栈未运行、未演练
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- M4-2 雪崩防护完成并落地：`CacheDefenseMode` 在原有五个模式之上新增 `ttl-jitter`、`l1-cache`、`db-fallback` 三值；读路径仍在 `CachedProductViewService` 的 `@Cacheable` 方法体内（L1+L2 双 miss 才执行），由新增的统一入口 `ProductAvalancheDefense` 分发。`off` 及全部旧模式走 `default -> breakdownDefense.load(...)`，与 M4-2 击穿步骤的路径逐字节一致（既有 `ProductPenetrationDefenseTest` / `ProductBreakdownDefenseTest` / `CachedProductViewServiceTest` 原样全绿，off 用例直接断言返回的是击穿层同一个 `Optional` 实例）。
- **实现与 7 个测试类来自上一个被中断的会话**（`064cea4` 只提交了生产代码，测试与配置类留在工作区未提交）。本会话完成抢救：先确认工作区/分支，再把未跟踪测试全部跑通并提交；修掉上一会话遗留的两处**测试**期望错误（见"设计逻辑"末条），生产代码无需改动。
- 全模块 394 例 0 失败；`feat`（064cea4，上一会话）→ `test`（7bf833b，本会话）→ `docs`（本文件）三笔提交分离。
- 有意未做：D10 面板（另一会话负责）、console UI、k6/compose/chaos 广播、live Redis 演练、跨实例 L1 一致性，见"待定事项"。

# 完成功能项

## 1. 模式与切换

- `CacheDefenseMode` 新增 `TTL_JITTER("ttl-jitter")`、`L1_CACHE("l1-cache")`、`DB_FALLBACK("db-fallback")`；key 仍是精确匹配（不 trim、不换大小写），拼错 40001。共 8 个模式。
- 启动种子仍用历史 key `app.lab.cache.penetration.mode`（`LAB_CACHE_DEFENSE_MODE:off`）；运行时 `POST /lab/cache/mode {"mode":"ttl-jitter"|"l1-cache"|"db-fallback"}` 即切，无需重启。开关是每实例内存态，chaos-controller 广播仍待接。
- `ProductPenetrationDefense` 的 enum 分支补上三个新值（诚实查库安全网），击穿/雪崩模式不会以它为入口。

## 2. ttl-jitter（读侧诚实 miss + 写侧 TTL 抖动）

- 读侧：`ProductAvalancheDefense.load` 对 `TTL_JITTER` 直接 `querySource`——本模式不叠加穿透/击穿防护，一次真实源读计一次 `cache_db_fallbacks{reason="avalanche"}`。
- 写侧：`AvalancheCacheResolver`（`@Bean name="avalancheCacheResolver"`，只挂在商品读的 `@Cacheable(cacheResolver=...)` 上）把原始 `product` 缓存包成 `AvalancheTtlCache`；后者在 `put` 之后、且仅当模式为 `ttl-jitter` 时读取 L2 刚写入的剩余 TTL（`RedisL2TtlAdjuster`，key 前缀复用 `TwoLevelCacheManager.productL2KeyPrefix()`），再用 `TtlJitter` 的 `±percent` 均匀抖动重写 `EXPIRE`。
- `TtlJitter`：因子 `1 + (U[0,1)*2-1)*(percent/100)`，结果取整秒且至少 1s（0 TTL 会删键）；随机源可注入，生产用 `ThreadLocalRandom`。
- 抖动是 best-effort：TTL 读不到（toggle 关、非商品 key、键已消失）计 `skipped`；Redis/解析异常计 `error` 并只告警，绝不让读失败。缓存 toggle 关闭时管理器发 `NoOpCache`，resolver 原样返回、不包一层，因此不产生任何额外 Redis 调用；`evict`/`clear` 仍直达原始缓存。
- 注意：抖动只作用于经 `@Cacheable` 写回的值；seed 的 `warmAvalancheKeys` 是裸 `SET`，刻意保持同一 TTL 以制造雪崩组。演示需在切模式后让键重建（或重新 seed 后再等重建），历史键不会追溯改 TTL。

## 3. l1-cache（L1-first 镜像 + 单飞异步刷新）

- `LogicalStaleStore`（bean `avalancheL1Store`）：Caffeine 有界（`max-entries`）每实例镜像；条目的逻辑 TTL = `refresh-interval-seconds`，物理保留 = `stale-window-seconds`（启动校验窗口必须大于刷新间隔）。
- 决策：无条目（冷启动 / 超过窗口）→ 同步诚实重建（唯一阻塞 MySQL 的情形）；距上次重建 < 刷新间隔 → `fresh-serve` 直接返回；在 `[刷新间隔, 陈旧窗口]` → `stale-serve` 立即返回旧值并触发一次后台刷新。
- 单飞：`ConcurrentHashMap.putIfAbsent` 保证每 key 同时至多一个在途刷新（重复计 `deduped`）；刷新池为独立有界 `ThreadPoolExecutor`（固定大小 + 固定队列 + daemon + AbortPolicy），饱和被拒计 `rejected`、释放单飞标记、旧值继续服务；刷新抛错或商品消失计 `failure`，旧值保留到窗口结束，绝不中途伪造 404。
- 刷新成功：除更新镜像与逻辑 deadline 外，还通过 `CacheWriter` 把新值**写回共享 Spring 缓存**。原因：stale-serve 返回的旧值会被 `@Cacheable` 拦截器写回 L1/L2，若只更新私有镜像，"短暂不新鲜"会退化成旧值整个物理 TTL。
- 缺失商品（冷读 `Optional.empty()`）不写镜像，保持 `40401` 契约。

## 4. db-fallback（熔断降级到 DB + 限流）

- `AvalancheLimiter`：固定窗口（非令牌桶），每实例最多 `permits` 次 DB 读 / `period-ms`；窗口内超预算立即抛 `BizException(42901 RATE_LIMITED)`，绝不入队等待。
- 预算内照常 `querySource`，absent 仍返回 `Optional.empty()`（40401 不变）。窗口边界可突发至多 2×permits，属固定窗口的已知代价；跨实例各算各的预算（见缺陷）。
- 构造参数校验为正；时钟可注入，测试确定性。

## 5. 指标（`cache_*` 家族，全部带 `cache="product"`）

| 指标 | 标签 | 含义 |
|---|---|---|
| `cache_db_fallbacks` | `reason="avalanche"` | 雪崩阶段真实触达 DB 的次数（含 l1 异步刷新、db-fallback 放行；`ttl-jitter` 每次 miss 计 1） |
| `cache_avalanche_l1` | `result=fresh-serve\|stale-serve\|cold-rebuild` | l1-cache 的三类决策 |
| `cache_avalanche_l1_refresh` | `result=submitted\|deduped\|success\|failure\|rejected` | 异步刷新的提交/去重/成功/失败/池满拒绝 |
| `cache_avalanche_limiter` | `result=acquired\|rejected` | DB 预算放行/快速失败 |
| `cache_avalanche_ttl_jitter` | `result=applied\|skipped\|error` | L2 TTL 重写结果 |

## 6. 可观测

- `GET /lab/cache/state` 只增不改，新增 `avalanche` 段：`strategy`（当前模式 key）、`ttlJitterActive`/`ttlJitterPercent`、`l1CacheActive`/`l1RefreshIntervalSeconds`/`l1StaleWindowSeconds`/`l1StaleEntriesCached`/`l1RefreshInFlight`、`dbFallbackActive`/`limiterPermits`/`limiterPeriodMs`/`limiterAcquired`/`limiterRejected`。

## 7. 配置（`app.lab.cache.avalanche.*`，`application.yml` 是唯一默认来源，无 Java 默认值；`validate()` 启动即校验）

| 配置 | 环境变量 | 默认 |
|---|---|---|
| `ttl-jitter.percent` | `LAB_CACHE_TTL_JITTER_PERCENT` | `20` |
| `l1-cache.refresh-interval-seconds` | `LAB_CACHE_L1_REFRESH_INTERVAL_SECONDS` | `10` |
| `l1-cache.stale-window-seconds` | `LAB_CACHE_L1_STALE_WINDOW_SECONDS` | `60` |
| `l1-cache.max-entries` | `LAB_CACHE_L1_MAX_ENTRIES` | `10000` |
| `l1-cache.refresh-pool-size` | `LAB_CACHE_L1_REFRESH_POOL_SIZE` | `2` |
| `l1-cache.refresh-queue-capacity` | `LAB_CACHE_L1_REFRESH_QUEUE_CAPACITY` | `100` |
| `l1-cache.keep-alive-seconds` | `LAB_CACHE_L1_KEEP_ALIVE_SECONDS` | `60` |
| `l1-cache.thread-name-prefix` | `LAB_CACHE_L1_REFRESH_THREAD_PREFIX` | `avalanche-l1-refresh` |
| `db-fallback.permits` | `LAB_CACHE_DB_FALLBACK_PERMITS` | `50` |
| `db-fallback.period-ms` | `LAB_CACHE_DB_FALLBACK_PERIOD_MS` | `1000` |

- 校验钉住：`percent ∈ [0,100)`；`stale-window > refresh-interval`；`max-entries`/`pool`/`queue`/`keep-alive`/`permits`/`period` 为正；线程前缀非空。任一不满足启动失败，不静默退化。

## 8. 代码落点

- `catalog/cache/ProductAvalancheDefense.java`（分发与三策略）、`AvalancheTtlCache.java`、`AvalancheCacheResolver.java`、`TtlJitter.java`、`L2TtlAdjuster.java`、`RedisL2TtlAdjuster.java`、`AvalancheLimiter.java`、`AvalancheProtectionConfig.java`、`LabCacheAvalancheProperties.java`。
- `CachedProductViewService` 改为调用 `avalancheDefense.load(...)`；`CacheExperimentDataService`/`CacheStateResponse` 增加 avalanche 段。
- 测试：`ProductAvalancheDefenseTest` 16、`AvalancheTtlCacheTest` 7、`AvalancheCacheResolverTest` 5、`TtlJitterTest` 9、`AvalancheLimiterTest` 5、`RedisL2TtlAdjusterTest` 5、`LabCacheAvalanchePropertiesTest` 7；`CacheDefenseModeServiceTest` 扩展到 8。

# 设计逻辑

- **独立雪崩阶段 + 统一枚举，而不是改 M4-1/M4-2 类**：一个运行时开关必须只有一个分发点；`off`（以及 null-cache/bloom/mutex/logical-expire）走 `default -> breakdownDefense.load`，与雪崩接入前的读路径完全相同——测试用 `isSameAs(同一个 Optional)` 钉住"不重查、不重包装"，并断言 avalanche 计数为 0、L1 镜像为空、finder 未被调用。
- **ttl-jitter 用 `Cache` 装饰器而不是改 `TwoLevelCache`**：L2 写 TTL 是两级缓存的固定常量；装饰器只在商品读的 `@Cacheable` 上生效，其它消费者（evict/clear、击穿守卫、测试）仍拿原始缓存。读侧不额外查询、不缓存，只计一次 avalanche fallback，语义最接近"没有额外防护、只有过期时间被打散"。
- **抖动相对"刚写入的真实 TTL"计算**：不复制 L2 TTL 常量，未来 TTL 外置也不会漂移；读 TTL 与重写都 best-effort，Redis 故障时读路径仍可用（与 M4-1 的 Redis 纪律一致）。
- **l1-cache 不改 L2 序列化格式**：在守卫内维护每实例有界镜像，命中逻辑新鲜期直接返回；过期后先答旧值、后台单飞重建并**写回共享缓存**，把陈旧窗口收敛到"一次刷新"而不是"一个物理 TTL"；超过 `stale-window-seconds` 物理条目被 Caffeine 清除，转入诚实重建，避免永久陈旧。刷新池有界且拒绝不阻塞读者，异步路径不会制造第二次惊群。
- **db-fallback 用固定窗口限流**：实现与解释成本最低、可在 console 上直接讲清，注入时钟后测试完全确定。设计 8.4 只承诺"熔断降级 + 限流"的效果，本实验用"预算内放行、超预算 42901"表达，不引入完整熔断状态机（见缺陷）。
- **失败边界**：除 db-fallback 的 42901 是有意快速失败外，防护组件不把 200/404 变成 500；Redis/锁/发布失败都退化为"照常读或继续供旧值"。
- **测试不依赖 Docker**：与 M4-1/M4-2 同一套路——注入墙钟/纳秒 ticker/执行器，mock `ProductBreakdownDefense`，用内存 `CacheWriter` 观测写回，无 sleep、无真 Redis。
- **本会话两处测试期望修正（均为测试错、实现无错）**：
  1. `offDelegatesToTheBreakdownStageByteForByte`：`breakdownDefense` 是 Mockito mock，未 stub 时对 `Optional` 返回 `Optional.empty()`，与"应返回 OLD"矛盾；修正为 stub 一个 `Optional` 并断言 `isSameAs`，比原先的 `contains` 更强地证明字节级透传。
  2. `l1CacheServesStaleInsideTheWindowWhileOneRefreshPublishesTheNewValue`：该用例自建的 lambda finder 未累加 `finderCalls`，却断言 `finderCalls == 2`；修正为在 lambda 内计数。实现本身确实只读源 2 次（冷重建 1 + 单飞刷新 1），最终 fresh-serve 不再触源。

# 存在缺陷项&约束

- **未做实栈验收**：只跑 reactor 单测/IT；未在运行中的栈切过三种模式、未采集 DB QPS / 429 比例 / 陈旧窗口证据，未接 Grafana。演示前需按下述口径打一轮。
- **l1-cache 是每实例内存态**：多实例各自镜像、互不可见，重启即失；跨实例一致性未解决（设计只承诺"不一致窗口 ≈ 刷新间隔、最长不超过 L1 TTL 60s"）。冷启动无旧值并发时仍会各自同步重建（与 logical-expire 相同的策略代价）；镜像只在真实源读后填充，演示前需让目标实例先读过热点/雪崩键。
- **l1-cache 写回次序竞态**：stale-serve 返回旧值后，`@Cacheable` 拦截器会把它写回 L1/L2；若该写恰好晚于异步刷新的写回，会用旧值覆盖新值一个物理 TTL 周期。镜像内已是新值，下一轮过期自愈；与 M4-2 logical-expire 的残余竞态同源，属可解释的短暂现象。
- **db-fallback 不是完整熔断器**：固定窗口、每实例独立，无 half-open/closed 状态机，也非集群全局预算；窗口边界最多突发 2×permits。跨实例总量需按实例数×permits 解读。
- **ttl-jitter 不追溯**：只影响经 `@Cacheable` 的后续写；seed 的裸 Redis 写保持同 TTL（这是实验前提）。切换后若键一直命中，不会产生抖动证据。
- **`ProductAvalancheDefense` 持有但未读取 `properties` 字段**（构造器依赖冗余）：不影响行为，建议下次清理或用于运行期限额，避免误以为有未生效的配置。（评审后已清理，见文末评审记录）
- `l1StaleEntriesCached` / `l1RefreshInFlight` 是瞬时值，抓取时刻可能为 0，state 只作观测辅助。

# 待定事项

- **D10 Grafana 面板：另一会话负责**，本步不碰面板 JSON；需要新系列 `cache_avalanche_l1`、`cache_avalanche_l1_refresh`、`cache_avalanche_limiter`、`cache_avalanche_ttl_jitter` 与既有 `cache_db_fallbacks{reason="avalanche"}`。
- **console UI**：展示 `state.avalanche` 段与三条策略的实时计数/参数；模式按钮接 `POST /lab/cache/mode`。
- **live Redis 演练（本会话明确不做，禁止 Docker）**：seed 200 个同 TTL 键 → `off` 并发看 `cache_db_fallbacks{reason="miss"}` 峰值 → 切 `ttl-jitter` 观察重建写入的 TTL 分散（`cache_avalanche_ttl_jitter{result="applied"}` 增长、同秒过期消失）→ 切 `l1-cache` 看 `cold-rebuild` 后 `fresh-serve`/`stale-serve` 与单飞 `refresh`、数据短暂陈旧 → 切 `db-fallback` 看 `limiter{acquired}` 封顶、超预算 42901 比例。通过标准引用设计验收表第 7 行：峰值 DB QPS 降 > 80%（D10）。
- **跨实例 L1 一致性**：当前不做共享逻辑过期/版本号；若演示需要两实例同值，可缩短 L2 TTL 或预热后同时读，长期方案留待评估。
- **chaos-controller 广播**三种新模式、k6/compose 场景接线、场景 JSON 剧本（`cache-avalanche` 标签已存在）。
- **改名**：`app.lab.cache.penetration.mode` 现承载全部 8 个模式，宜后续改为 `app.lab.cache.defense.mode` 并保留旧 key 一个里程碑。

# 独立评审记录（M4-2b，隔离 worktree）

- 结论：**PASS WITH FIXES**。规范符合性核对（设计 8.4 雪崩行：ttl-jitter ±20%、l1-cache 陈旧窗口 ≤ stale-window、熔断降级 + 限流 42901→HTTP 429 经 `GlobalExceptionHandler` 验证）通过；M4-1/M4-2a/M4-3 既有套件（`ProductPenetrationDefenseTest` 8、`ProductBreakdownDefenseTest` 20、`CachedProductViewServiceTest` 6、preheat/state/controller 套件）确认全绿，未被击穿。
- 评审发现与处置：
  1. （Minor，已修）`ProductAvalancheDefense` 与 `CacheExperimentDataService` 各有一个从未读取的构造依赖（`properties` / `ttlJitter`）——删除死依赖并同步 `AvalancheProtectionConfig`、`CacheLabConfig` 接线与测试。
  2. （Minor，已修）`CachedProductViewService` javadoc 仍写"其余模式委托 M4-1 穿透层"，实际委托链是 雪崩→击穿→穿透；改为准确描述。
  3. （Minor，测试已强化）`AvalancheTtlCacheTest` 的 NoOpCache 用例原本只断言 `getName()`，属空断言；改为断言 resolver 所依赖的 NoOp 契约（存不进取不出、valueLoader 诚实透传）。`everyOtherModeLeavesTheTtlAlone` 补钉"非 ttl-jitter 模式连 TTL 读都不发生"（模式门在 adjuster 之前），坐实 off 路径无额外 Redis 调用；resolver 的 NoOpCache 直通用例补端到端断言（ttl-jitter 模式下写 NoOp 缓存：零 adjuster 调用、零 `cache_avalanche_ttl_jitter` 计器注册）。
  4. （Minor，测试已强化）l1-cache 与 db-fallback 决策用例补 `verifyNoInteractions(breakdownDefense)`，钉住"单开关、模式互斥、旧层不被叠加执行"。
- 证伪核对：交接首行"394 例"在本合并 HEAD 上不可复现——实测 surefire 报告 seckill-app-jdk21 **406** 例（多出的 12 例来自作者基线 `6e5d776` 之后合入的 `MySqlModeSwitchTest` 新增 11 例与 `HotKeyTrackerTest` +1 例），0 失败 0 错误 0 跳过；`platform-common` 10、`platform-observability` 4、`seckill-core` 13 不变。394 是作者分支基线（`6e5d776`）口径。
- 未修（维持原判断）：写回次序竞态、跨实例 L1 一致性、固定窗口边界 2×permits 突发——均为文档已声明的可解释代价；D10 雪崩面板归任务 #31（本步不碰 dashboard/console/code/lab）。
