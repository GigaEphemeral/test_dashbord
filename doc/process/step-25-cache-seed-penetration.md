> 步骤交接 · M4-1 缓存实验数据与穿透防护（seed / state / mode）
> 日期：2026-10-26
> 分支 / HEAD：worktree 分支 `worktree-agent-general-purpose-6c535ef8`（基线 `ab4ca38`，`feature/ha-lab`）/ 本次提交 `feat: add cache experiment seeding and penetration defense`、`test: cover cache seeding and penetration defense`、`docs: record Task 25 cache seed and penetration handover`
> 关联任务：#25（M4-1，设计 8.4；后续 M4-2/M4-3 消费本步数据集）
> 测试 / verify：`mvn -pl services/seckill-app-jdk21 -am test`（JAVA_HOME=jdk-21.0.12.1）**192 tests, 0 failures / BUILD SUCCESS**（原 131 → 192，新增 61：`CachedProductViewServiceTest` 6、`NullValueMarkerStoreTest` 8、`ProductBloomFilterServiceTest` 7、`ProductPenetrationDefenseTest` 8、`LabCacheControllerTest` 7、`CacheExperimentDataServiceTest` 11、`LabCachePropertiesTest` 8、`CacheDefenseModeServiceTest` 6）；`verify.sh` / `smoke.sh` / console-smoke **本步未复跑**（禁止在共享实栈上执行 Docker/compose/验收命令），仅既有 IT 随 reactor 启动完整上下文并连本机 MySQL/Redis 通过
> 运行状态：共享栈保持原样，未启停任何容器；本步**没有**在实栈上调用过 `/lab/cache/seed`（不会向共享 MySQL 写入 1000 行实验商品），实栈验收留待后续统一进行

# 当前进展

- M4-1 落地：`/lab/cache/seed`、`/lab/cache/state`、`GET/POST /lab/cache/mode` 三个实验端点 + 运行时可切换的穿透防护（`off` / `null-cache` / `bloom`），挂进既有两级缓存读路径（L1 Caffeine → L2 Redis → 防护层 → DB）。
- 全部实现与测试已完成并通过 reactor 切片；本文档为交接。工作全部发生在 worktree `agent-general-purpose-6c535ef8` 的未提交改动之上（前一代理已写完主代码，本步补齐/验证测试、增补一条状态可观测性断言并提交）。
- 未做（有意）：实栈验收（seed 真打 1000 行、Grafana 面板、场景 JSON、chaos-controller 广播）不属于本步范围，见"待定事项"。

# 完成功能项

## 1. 端点（`lab/api/LabCacheController`，`/lab/**` 由 `LabTokenFilter` 以 `X-Lab-Token` 保护）

- `GET /lab/cache/mode` → `Result<{mode}>`；`POST /lab/cache/mode`，body `{"mode":"off"|"null-cache"|"bloom"}` → 同形状。未知/缺失值 → `400` + `40001`，且**不改当前模式**；key 精确匹配（不 trim 不换大小写），`null-cache` 拼错必须 400 而不是静默换成别的防护。
- `POST /lab/cache/seed` → `Result<CacheSeedResponse>`：`startId/endId/requestedProducts/inserted/alreadyPresent/rowsInRange/avalancheKeysWritten/avalancheTtlSeconds/hotKeyProductId/hotKeyWritten/bloomIdsAdded/bloomInitialised/mode`。默认 1000 个实验商品（id 90000..90999）、200 个共享同一 TTL 的雪崩 key、1 个热点 key，全部数量/范围/TTL 可配。
- `GET /lab/cache/state` → `Result<CacheStateResponse>`：`defense{mode,cacheToggleEnabled}`、`dataset{…missingRows}`（配置 vs 现实）、`keys{redisReachable,l1ExperimentKeys,l2ExperimentKeys,l2AvalancheKeysAlive,hotKeyCached}`、`bloom{key,initialised,size,capacity,falseProbability}`、`nullCache{keyPrefix,ttlSeconds,jitterSeconds,liveMarkerKeys}`。Redis 不可达时降级为 DB+L1 口径并置 `redisReachable:false`，不报错。

## 2. 防护（`catalog/cache/ProductPenetrationDefense` 等，挂在 `CachedProductViewService` 的 `@Cacheable` 体内）

- `off`：无防护，每次 miss 直达 DB（症状态，默认）。
- `null-cache`：`NullValueMarkerStore` 把 miss 写成 `null:product:<id>` = `"NULL"`，TTL = 基础 10s + `[0,5]s` 均匀抖动；命中 marker 直接返回 404 不查库；已有商品绝不会被写 marker；Redis 故障 → 记 `cache_null_cache_errors` 并照常查库。
- `bloom`：`ProductBloomFilterService`（Redisson `RBloomFilter`，复用 `seckillRedissonClient`）覆盖**全部**现存 id（rebuild = delete + tryInit + add，seed 时重建）；只有"证明不存在"才跳过 DB；**假阳性回落到 DB 并保持普通 404/40401 契约，绝不 500**；过滤器不可用/未初始化按"可能存在"回落 DB。
- `CachedProductViewService.findInDatabase` 只把 `NOT_FOUND` 翻译为 empty，其它 `BizException` 原样抛出（429 等不会被误记成 marker/404）。

## 3. 指标（沿用模块 `cache_*` 命名）

- `cache_bloom_requests{cache="product",result="hit"|"absent"|"error"}`、`cache_null_cache_requests{cache,result="hit"|"miss"}`、`cache_null_cache_writes{cache}`、`cache_null_cache_errors{cache}`、`cache_db_fallbacks{cache,reason="penetration"}`（`reason=miss` 是既有 L1+L2 双miss计数，两者区分开）。

## 4. 配置（`app.lab.cache.*`，全部 `${ENV:default}`，无 Java 侧默认值，`LabCacheProperties.validate()` 启动即校验）

- seed：`LAB_CACHE_SEED_PRODUCT_COUNT:1000`、`LAB_CACHE_SEED_START_ID:90000`、`LAB_CACHE_SEED_PROTECTED_MAX_ID:1000`（start-id 必须 > protected-max-id，保护演示基线 1/20 号商品）、`LAB_CACHE_SEED_AVALANCHE_KEY_COUNT:200`、`LAB_CACHE_SEED_AVALANCHE_TTL_SECONDS:60`、`LAB_CACHE_SEED_HOT_KEY_INDEX:999`（必须 ≥ avalanche-key-count 且 < product-count）、`LAB_CACHE_SEED_HOT_KEY_TTL_SECONDS:300`、`LAB_CACHE_SEED_WRITE_BATCH_SIZE:200`、`LAB_CACHE_SEED_NAME_PREFIX:实验商品`、`LAB_CACHE_SEED_STATUS:ON_SALE`、`LAB_CACHE_SEED_PRICE:99.00`
- state：`LAB_CACHE_STATE_SCAN_COUNT:500`
- penetration：`LAB_CACHE_DEFENSE_MODE:off`、`LAB_CACHE_NULL_KEY_PREFIX:null:product:`、`LAB_CACHE_NULL_TTL_SECONDS:10`、`LAB_CACHE_NULL_JITTER_SECONDS:5`、`LAB_CACHE_BLOOM_KEY:bloom:product-ids`、`LAB_CACHE_BLOOM_EXPECTED_INSERTIONS:10000`、`LAB_CACHE_BLOOM_FALSE_PROBABILITY:0.01`

## 5. 基线保护与幂等（有测试钉住）

- seed 只 INSERT 保留区间内缺失的 id（`aSecondSeedIsANoOp…` 第二次 `inserted=0`；`anAlreadyPresentExperimentRowIsNeverOverwritten` 不覆盖已存在行），演示商品 1/20（库存 100）所在 `id ≤ protected-max-id` 区间行数与内容前后一致（`seedFillsTheReservedRangeAndNeverTouchesTheBaseline`）。
- L2 雪崩 key 与真实读路径同布局同序列化（`cache:product:<id>`、`ProductResponse` JSON；布局经 `TwoLevelCacheManager.productL2KeyPrefix()` 单一来源），测试反序列化验证可读。

# 设计逻辑

- **防护层放在 `@Cacheable` 方法体内**而不是 Filter/Service 里：方法体只在 L1+L2 双 miss 时执行，正好是实验要操作的流量；`off` 模式行为与改动前逐字节一致（既有 `ProductCacheIT` 全绿）。
- **absent 用 `Optional.empty()` 表达、404 由调用方抛**：防护组件永远不拥有 HTTP 契约，这是"假阳性不得变成 500"的结构保证——bloom/null-cache 只决定"要不要问 DB"，问完之后的世界和 `off` 一样。
- **模式是进程内 `AtomicReference`**（`CacheDefenseModeService`），与 `LabToggleService` 同语义：配置播种、运行时 POST 切换、无重启；多实例对比留待 chaos-controller 广播（与 toggles 同路线）。
- **bloom 重建覆盖全部 id**：真实演示商品若不在过滤器里会被"证明不存在"而错答 404，比假阳性严重得多；delete+tryInit 是 Redisson 重算位图几何的唯一方式，重建窗口内未初始化 → 回落 DB（宁可少保护、不出错答）。
- **null-cache 抖动是每写一次抽一个 `[0,jitter]` 整数秒**：让 miss 风暴不会与雪崩 key 同秒集体过期，抖动源可注入（测试用固定值断言 TTL 算术，生产用 `ThreadLocalRandom`）。
- **seed 写库走裸 `JdbcTemplate`（无事务、写池）**，Redis/Redisson 全部 best-effort：Redis 挂时 seed 依然成功，但 `avalancheKeysWritten=0`、`bloomInitialised=false` 如实暴露"实验没架好"，比抛错更可诊断（`aRedisOutageLeavesTheSeedVisibleInsteadOfFailing`）。
- **启动即失败优于静默破坏演示**：validate() 把"start-id 落进保护区""热点 key 落进雪崩区间""雪崩数量>商品数"等配置错误变成启动异常（设计文档 yml 注释说明了 hot-key 与雪崩区间冲突为什么必须拒绝）。

# 存在缺陷项&TODO

- **未做实栈验收**：`/lab/cache/*` 只在单测/MockMvc/H2 层证明过；未在运行的栈上真正 seed 过 1000 行、未在 Grafana 看过新指标、未跑 verify/smoke（本任务禁止 Docker/compose/验收命令）。后续步骤需：seed 一次 → `GET /lab/cache/state` 对账 → 模式三态各打一轮穿透流量核对 `cache_db_fallbacks{reason="penetration"}`。
- **seed 非纯幂等（就 Redis 而言）**：DB 插入幂等，但重复调用会把雪崩 key 按原 TTL 重新暖一遍（TTL 重置）、并重建 bloom。对"重置实验"这是特性，对"再点一次看看"会干扰正在观察的雪崩窗口；M4-2 场景编排要约定调用时机。
- **bloom rebuild 非原子**：delete→init→add 之间（实测毫秒级，1000+ id）过滤器未初始化，此窗口内 bloom 模式退化为"全部回落 DB"。契约安全（不会错答 404），但数字上会短暂 `result=error`。
- **state 的 SCAN 是 O(L2 全量 product key)**，键多时变慢；已用 COUNT hint，可接受（演示规模）。
- **fpp=0.01 偏大**：演示"假阳性也会 404"需要足够高的假阳性率；生产级参数（0.001 以下）在注释里说明但不作为默认。
- **`hot:products` 热点 ZSet、Caffeine 预热/周期再预热（设计 8.4 后半）不在本步范围**：本步只交付 seed 数据集与穿透防护；击穿（mutex/logical-expire）与雪崩处置属 M4-2/M4-3。
- **既有硬编码（非本步引入）**：`TwoLevelCache` 的 L1 容量 10k/60s、L2 TTL 5min、clear 的 SCAN count 500 仍是常量，登记在阶段交接的清理项中，本步未顺手重构。

# 待定事项和注意事项

- 下一步（M4-2）消费本数据集：雪崩实验直接打 `cache:product:90000..90199`（同 60s TTL），击穿实验打 hot key `90999`（300s TTL）；若场景需要别的数量组合，改 `LAB_CACHE_SEED_*` 即可，但注意 validate 的三条区间约束。
- **实栈注意**：共享栈是活的，seed 会向 `catalog_product` 写 1000 行、写 Redis 与 `bloom:product-ids`；演示完需要有人清理（当前没有 `/lab/cache/reset` 端点，清理手段=SQL DELETE + `TTL/clear` 或 `TwoLevelCache.clear()`，M4-2 再评估是否补端点）。
- 模式切换是**单实例**的：三个 app 实例各自 POST 各自生效，对比实验可用，聚合广播等 chaos-controller（与 toggles 同一 TODO）。
- 场景/dashboard/scenario JSON 侧完全未接入（`code/lab/**` 对本任务禁改），`/lab/cache/**` 的 nginx 前缀路由与其它 `/lab/**` 相同，无需新配置。
- token 只走 `X-Lab-Token`，seed/state 响应均不含敏感值（有断言）；`LabTokenFilter` 的 `/lab/*` 注册天然覆盖新路径。
- `.r2c/`（前一代理的 scratch 台账）保持未跟踪，未提交。

# 附录

- 验证命令：`cd code && export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1" && mvn -pl services/seckill-app-jdk21 -am test` → `Tests run: 192, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（日志 /tmp/m41-test-run2.log）。
- 关键文件：`catalog/app/CachedProductViewService.java`、`catalog/cache/{ProductPenetrationDefense,NullValueMarkerStore,ProductBloomFilterService,PenetrationProtectionConfig}.java`、`lab/cache/{CacheExperimentDataService,CacheLabConfig}.java`、`lab/api/{LabCacheController,CacheModeResponse,CacheModeUpdateRequest,CacheSeedResponse,CacheStateResponse}.java`、`lab/toggle/{CacheDefenseMode,CacheDefenseModeService}.java`、`lab/config/LabCacheProperties.java`、`application.yml`（`app.lab.cache.*` 19 个键）。

---

## 补充（2026-10-27）：seed 的实验商品让 verify 断言失真

`/lab/cache/seed` 会向 `catalog_product` 插入 1000 条实验商品（id ≥ `LAB_CACHE_SEED_START_ID=90000`，用于保护 1/20 号演示基线）。批次闸门里 `verify.sh` 的「catalog_product 恰好 20 条种子商品」在灌过 seed 的栈上必然失败（实测 `count='1020'`）。

修复：断言只统计演示基线 `id < VERIFY_CATALOG_BASELINE_MAX_ID`（默认 1000，可环境变量覆盖），与 seed 的保护段设计对齐；实验商品存在与否不再影响 verify。

验证：灌过 seed 的栈上 `verify.sh` → **104 PASS / 1 WARN / 0 FAIL**。

补充（同日）：`inventory_stock` 同样会被 seed 灌入 1000 行，verify 的「一行一商品」检查按同一口径改为只统计 `product_id < VERIFY_CATALOG_BASELINE_MAX_ID`；变量统一定义在脚本顶部（首版补丁把它写在后面的检查里，导致前段检查 `unbound variable`）。
