> 步骤交接 · M4-2 缓存击穿防护（mutex / logical-expire）
> 日期：2026-10-27
> 分支 / HEAD：worktree 分支 `worktree-agent-general-purpose-80ed8116`（基线 `bd245af`，`feature/ha-lab`）/ 本次提交 `6f59100 feat: add mutex and logical-expire cache breakdown defenses (M4-2)`、`705d3e8 test: cover cache breakdown decision tables and wiring (M4-2)`、本文件 `docs:` 提交
> 关联任务：M4-2（设计 8.4 击穿行；消费 M4-1 数据集与模式开关；雪崩子任务与 M4-3 预热不在本步）
> 测试 / verify：`mvn -pl services/seckill-app-jdk21 -am test`（reactor 5 模块，JAVA_HOME=21）**BUILD SUCCESS**；`Tests run:` 各模块汇总：platform-common 10、platform-observability 4、seckill-core 13、**seckill-app-jdk21 265**（0 failures / 0 errors / 0 skipped；本 worktree 改动前该模块为 241，本步新增 24：`ProductBreakdownDefenseTest` 15、`LogicalStaleStoreTest` 4、`BreakdownProtectionConfigTest` 5，另有 `LabCachePropertiesTest` +2 与既有类扩展断言）；M4-1 穿透既有测试（`ProductPenetrationDefenseTest` 8、`NullValueMarkerStoreTest`、`ProductBloomFilterServiceTest`、`CachedProductViewServiceTest`、`LabCacheControllerTest`、`CacheExperimentDataServiceTest`、`CacheDefenseModeServiceTest` 等）全绿未回归；未复跑 verify.sh/smoke（禁止 Docker/验收命令），未在实栈调用过任何 `/lab/cache/*`（模块 IT 按既有约定经 Testcontainers 随 reactor 运行）

# 当前进展

- M4-2 击穿防护完成：运行模式枚举在 M4-1 三值（`off`/`null-cache`/`bloom`）之上新增 `mutex`、`logical-expire` 两个击穿策略，`POST/GET /lab/cache/mode` 与 `GET /lab/cache/state` 原契约不变；`off` 仍是缺陷可见基线。
- 读路径挂点不变：防护层仍在 `CachedProductViewService` 的 `@Cacheable` 方法体内（L1+L2 双 miss 才执行）。新增 `ProductBreakdownDefense` 作为统一入口，按模式分发：击穿两态自处理，其余原样委托 `ProductPenetrationDefense`（M4-1 代码零行为改动，仅补 enum 穷尽分支）。
- 全部实现、测试、文档三笔提交分开落地（feat/test/docs），reactor 切片绿色。
- 有意未做：雪崩策略（`ttl-jitter`/`l1-cache`/熔断降级）、Caffeine 预热（M4-3）、console/Grafana/k6/compose、实栈演练，见"待定事项"。

# 完成功能项

## 1. 模式与切换

- `CacheDefenseMode` 新增 `mutex`、`logical-expire`（key 精确匹配、不 trim 不换大小写，与 M4-1 同规则；拼错必 400/40001）。
- 启动种子仍是既有 `app.lab.cache.penetration.mode`（=`LAB_CACHE_DEFENSE_MODE:off`）；运行时 `POST /lab/cache/mode {"mode":"mutex"|"logical-expire"}` 即切，无需重启。该 key 名保留为历史契约（现播种全部防护模式，yml 注释已说明），改名列入待定。

## 2. mutex（Redisson 锁 + 双重检查）

- `catalog/cache/ProductBreakdownDefense.withMutex`：每 key 一把 `lock:product:<id>`（前缀可配）`tryLock(wait-ms, lease-ms)`；持锁者先**双重检查**（Spring 两级缓存探测 `springCacheProbe` → 本实例 stale 存储中"逻辑未过期"的旧值），命中则不查库；未命中才重建并把结果写入 stale 存储（供后续双检与超时兜底复用）。
- 等锁超时：按 `on-lock-timeout` 外置策略回落——`db`（默认，诚实查库，`cache_db_fallbacks{reason="breakdown"}` 照计）或 `stale`（有旧值先答旧值，没有仍查库）。`lease-ms >= wait-ms` 启动校验强制。
- 锁释放走 `finally` + `isHeldByCurrentThread` 防误解锁；Redisson/Redis 不可达（getLock/tryLock 抛错）→ 计 `mutex{result="error"}` 并照常查库，绝不让读失败；中断恢复标志位后同样查库。absent 仍返回 `Optional.empty()` 走原 40401，绝不伪造数据。

## 3. logical-expire（旧值兜底 + 有界异步刷新）

- `catalog/cache/LogicalStaleStore`：Caffeine 有界存储（`max-entries`），每条目自带**逻辑过期时刻**（`logical-ttl-seconds`），物理 `expireAfterWrite = retention-seconds`（启动校验 retention > logical-ttl），到期物理清除防止永久陈旧。ticker 与墙钟均可注入（测试无 sleep）。
- 决策：无条目（冷/超保留期）→ 同步重建；逻辑未过期 → 直接命中返回；逻辑已过期但物理仍保留 → **立即返回旧值** + 触发一次异步刷新。
- 异步刷新防惊群：`ConcurrentHashMap.putIfAbsent` 单飞（每 key 同时至多 1 个刷新，重复请求计 `deduped`）；执行器为独立有界池（`refresh-pool-size`/`refresh-queue-capacity`/`keep-alive-seconds`/`thread-name-prefix`，daemon，AbortPolicy）；池满被拒 → 计 `rejected`、释放单飞标记、旧值继续服务，请求线程绝不代跑查询。
- 刷新失败/商品消失 → 计 `failure`，保留旧值到保留期结束，绝不把演示中途变成 404。
- **刷新成功后把新值同步写回共享缓存**（评审修订，`CacheWriter` 由 `BreakdownProtectionConfig.springCacheWriter` 接 Spring Cache 同名缓存）：stale-serve 返回的旧值会被 `@Cacheable` 拦截器写回 L1/L2，若刷新只更新守卫私有存储，"短暂不新鲜"会退化成"旧值整整活到下一次物理过期"。写回后陈旧窗口 ≈ 一次刷新耗时，符合 8.4 的代价承诺。残余竞态：某次 stale-serve 的方法返回写缓存若恰好晚于刷新写回，会用旧值盖住新值——影响仅限一个物理 TTL 周期且自愈（守卫存储此时已是新值），已列入缺陷节。

## 4. 指标（沿用 M4-1 `cache_*` 命名；本步新增系列）

- `cache_db_fallbacks{cache="product",reason="breakdown"}` —— 击穿防护实际触达 DB 的次数（含异步刷新，测试驱动发现的真实缺陷修正：初版刷新未计数）。
- `cache_breakdown_mutex{cache,result="rebuild"|"double-check-hit"|"timeout"|"stale-serve"|"error"}`。
- `cache_breakdown_logical{cache,result="cold-rebuild"|"fresh-serve"|"stale-serve"}`。
- `cache_breakdown_refresh{cache,result="submitted"|"deduped"|"success"|"failure"|"rejected"}`。
- M4-1 系列（penetration/null-cache/bloom/`reason="penetration"`）原样保留。

## 5. 可观测（演示页数据源）

- `GET /lab/cache/state` 新增 `breakdown` 段：`strategy`（当前模式 key）、`mutexActive`/`logicalExpireActive`、`mutexWaitMs`/`mutexLeaseMs`/`mutexOnLockTimeout`、`logicalTtlSeconds`/`retentionSeconds`/`refreshPoolSize`/`refreshQueueCapacity`、实时 `staleEntriesCached`/`refreshInFlight`。既有五段字段全部不变（只增不改）。

## 6. 配置（`app.lab.cache.breakdown.*`，全部 `${ENV:default}`，`validate()` 启动即校验，无 Java 默认值）

- mutex：`LAB_CACHE_MUTEX_KEY_PREFIX:lock:product:`、`LAB_CACHE_MUTEX_WAIT_MS:200`、`LAB_CACHE_MUTEX_LEASE_MS:2000`、`LAB_CACHE_MUTEX_ON_LOCK_TIMEOUT:db`
- logical-expire：`LAB_CACHE_LOGICAL_TTL_SECONDS:30`、`LAB_CACHE_LOGICAL_RETENTION_SECONDS:600`（评审修订：原 120 小于热点键 L2 生命期，旧值必在击穿前被物理清除，兜底永不触发）、`LAB_CACHE_LOGICAL_MAX_ENTRIES:10000`、`LAB_CACHE_LOGICAL_REFRESH_POOL_SIZE:2`、`LAB_CACHE_LOGICAL_REFRESH_QUEUE_CAPACITY:100`、`LAB_CACHE_LOGICAL_REFRESH_KEEP_ALIVE_SECONDS:60`、`LAB_CACHE_LOGICAL_REFRESH_THREAD_PREFIX:breakdown-refresh`
- 校验钉住：wait/lease 为正且 lease≥wait；on-lock-timeout ∈ {db,stale}；retention>logical-ttl；**retention>seed.hot-key-ttl-seconds**（否则 logical-expire 静默退化成 off）；max-entries/pool/queue/keep-alive 为正；前缀非空。

# 设计逻辑

- **入口收敛为 `ProductBreakdownDefense` 而非改 M4-1 类**：读路径方法体仍只有一个守卫，按模式分发；`default -> penetrationDefense.load(...)` 使 off/null-cache/bloom 的字节级行为与 M4-1 完全一致（既有 `ProductPenetrationDefenseTest` 等全部原样通过）。`ProductPenetrationDefense` 新 enum 分支直连 finder 只是穷尽性安全网——击穿模式不会以它为入口。
- **双重检查先查共享缓存再查本实例 stale 存储**：跨实例场景真正的"第二个检查"是 L2（@Cacheable 已把重建结果写入 L2，后续请求根本进不到方法体）；同实例等待线程之间靠 stale 存储兜住。@Cacheable 的写发生在持锁线程方法返回之后，与本守卫释放锁存在先后窗口，故双检未命中时诚实回落查库——教学上体现为 mutex 省的是"绝大部分"DB 查询而非绝对 1 次，实栈数字以此解读（缺陷节已声明）。
- **逻辑过期放在守卫自己的存储而不是改 L2 序列化格式**：改 `cache:product:<id>` 的 JSON 包一层 envelope 会同时动 seed 暖 key、两级缓存读写、既有 IT 契约，违背"不重设计 M4-1"。守卫级 stale 存储恰好只在方法体（=L2 已空/过期）时被触及，正是击穿语义；物理 TTL 由 retention 承担、逻辑 TTL 随值走，设计文档的"旧值兜底+异步刷新"完整成立。
- **单飞 + 有界池 = 异步路径不产生新惊群**：putIfAbsent 保证每 key 一个在途刷新；池/队列有界、拒绝即计数继续供旧值，刷新风暴不会把演示压垮，也不会占用请求线程。
- **Redis 全链路 best-effort**（与 M4-1 同纪律）：锁拿不到、Redisson 挂了、探测抛错，一律退化为"照常查库/未命中"，防护组件永远不能把 200/404 变成 500。
- **测试不依赖 Docker/真 Redis**：与 M4-1 同一套路——mock `RedissonClient`/`RLock`、注入时钟与 Caffeine ticker、可切换执行器（inline/捕获/拒绝），决策表全部确定性断言，无 sleep 无竞态。

# 存在缺陷项&约束

- **未做实栈验收**：只跑过 reactor 单测/IT；未在运行中的栈上切过 mutex/logical-expire、未看过真实 P99 抬升与陈旧窗口、未接 Grafana。演示前需按下面"演示口径"打一轮。
- **mutex 的跨实例双检窗口**（评审后修正表述）：持锁者在**释放锁之前**已把重建结果写入守卫 stale 存储，故同实例等待线程的双检（probe→stale 存储）能命中，present 商品每轮击穿 rebuild≈1；残余窗口是**跨实例**（对端 L2 要等持锁者的 `@Cacheable` 方法返回才可见）与**absent 商品**（守卫存储不记空值，等待者逐个诚实查库——空值防护本就是 M4-1 穿透策略的职责）。数字预期"rebuild≈1、double-check-hit 高、少量额外 fallback"，不是数学上的恰好 1；全部可用 `cache_breakdown_mutex{result}` 直接观测。
- **logical-expire 刷新写回的次序竞态**：极端交错下某 stale-serve 的 `@Cacheable` 写缓存可能落在刷新写回之后，把旧值盖回 L2 一个物理 TTL 周期；守卫存储不受影响（刷新已更新），下一轮过期即自愈，属可演示可解释的短暂现象（已随评审修订把刷新结果写回共享缓存，正常路径陈旧窗口=一次刷新耗时）。
- **stale 存储是每实例内存态**：多实例各自兜底、互不可见，重启即失（逻辑过期语义在实例内闭环）；跨实例旧值兜底留待 M4-3 l1-cache 方案一并评估。
- **logical-expire 冷启动不防并发**：无任何旧值时并发首个请求各自同步重建（与 mutex 不同，这是策略选择的代价，设计 8.4 只承诺"旧值兜底+异步刷新"）；演示时先让热点键被守卫重建一次再进入陈旧窗口（retention 默认 600s 已覆盖热点键 300s 生命期，重建一次后续周期即进入 stale-serve 形态）。
- `refreshInFlight`/`staleEntriesCached` 是瞬时值，抓取时刻可能恰为 0，state 里是观测辅助不是计数。

# 待定事项（本步有意不做）

- **雪崩（M4-2 另一半）**：`ttl-jitter`、`l1-cache`、熔断降级到 DB + 限流；消费既有 `avalanche-key-count=200` 同 TTL 数据集；建议同样挂 `ProductBreakdownDefense` 分发点并复用 `cache_db_fallbacks` 家族新增 `reason="avalanche"`。
- **M4-3 Caffeine 热点预热**：热点探测/ZSet 榜单/启动与周期预热/refreshAfterWrite。
- **改名**：`app.lab.cache.penetration.mode` 现承载全部防护模式，宜在后续小版本改为 `app.lab.cache.defense.mode`（保留旧 key 别名一个里程碑）。
- console UI 展示 `breakdown` 段、Grafana D10 面板（新 4 个系列）、场景 JSON 增加 mutex/logical-expire 剧本、chaos-controller 广播模式、k6/compose 接线、live Redis 击穿演练（含 mutex P99 对照截图）。
- 实栈演示口径备忘：seed → 等热点 key L2 过期（`hot-key-ttl-seconds=300`，可调小）→ `off` 打并发看 `cache_db_fallbacks{reason="miss"}` 基线（off 委托 M4-1 穿透守卫，**不产生** `reason="breakdown"` 计数）→ 切 `mutex` 看 rebuild≈1 + P99 抬升 + `reason="breakdown"` 收敛 → 切 `logical-expire` 看 stale-serve + refresh 单飞 + 数据短暂陈旧（刷新写回后新值即时可见）。

# M4-2 评审修订（2026-10-27，独立评审）

- **fix（85195f4）**：① logical-expire 刷新成功后把新值写回共享缓存（新增 `CacheWriter` 挂点，`BreakdownProtectionConfig.springCacheWriter` 接线）——修复"stale-serve 被 @Cacheable 写回 L2 后，刷新结果不可见、陈旧窗口=一个物理 TTL"的错误演示症状；② `retention-seconds` 默认 120→600，并新增启动校验 retention>seed.hot-key-ttl-seconds——原默认组合下旧值必在热点键过期前被清除，stale-serve 永远不出现（logical-expire 静默退化为 off）。
- **test（9a90f73）**：+7 例——刷新写回/失败与消失不写回/写回爆炸不影响读、mutex 重建异常仍释放锁、中断等待保中断标志并诚实查库、`springCacheWriter` 接线契约、retention≤热点 TTL 启动拒绝。`ProductBreakdownDefenseTest` 15→20、`LabCachePropertiesTest` 10→11、`BreakdownProtectionConfigTest` 5→6；M4-1 全部既有测试未削弱、全绿。
- **docs（本提交）**：设计逻辑/缺陷/配置/演示口径同步修订（mutex 双检窗口表述精确化：同实例已被锁内写守卫存储闭合，残余为跨实例与 absent 商品；off 基线指标名修正为 `reason="miss"`）。
- **审核确认无问题**：`/lab/cache/{mode,state}` 契约只增不改；`code/lab/**`（k6/dashboard/scenarios）无缓存模式枚举契约需要同步（grep 无命中）；指标命名沿用 `cache_*` 家族；`cache_db_fallbacks{reason="breakdown"}` 含异步刷新；off 基线委托 M4-1 字节级不变。console `breakdown` 段展示、Grafana D10 面板仍归 M4-4（任务 #31）。
