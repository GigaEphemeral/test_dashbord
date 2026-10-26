> 步骤交接 · M4-3 Caffeine 热点预热（设计 8.4）
> 日期：2026-10-27
> 分支 / HEAD：worktree 分支 `worktree-agent-Qwen-3.8-ed2bd9fc`（基线 `feature/ha-lab` 合并点 `6e5d776`）/ 实现提交 `4fb2099 feat: add Caffeine preheat tracking, warming and L1 refresh (M4-3)`、`50538f8 fix: reload the Caffeine L1 synchronously from Redis L2 (M4-3)`、`473adb9 test: cover hot-key decay, preheat triggers and L1 tuning (M4-3)`、`edaa5e2 test: prove preheat-enabled and preheat-disabled startup (M4-3)`、合并 `6e5d776`；评审修复提交 `b7914c9 fix: publish the shared hot list atomically and spare it from cold flushes (M4-3 review)`（含回归测试）与本文件 `docs:` 提交
> 关联任务：M4-3（设计 8.4 "Caffeine 预热"行；消费 M4-1 两级缓存与 M4-2a 击穿防护，不改动其契约）
> 测试 / verify：`mvn -pl services/seckill-app-jdk21 -am test`（reactor 5 模块，JAVA_HOME=21）**BUILD SUCCESS**；改动前基线 **Tests run: 349**（0 failures / 0 errors / 0 skipped），修复后 **Tests run: 350**（`HotKeyTrackerTest` 由 6 增至 7）；首轮修复后全量运行中 `ThreadStateFingerprintTest`（线程实验室、负载敏感，与本步无关）偶发 1 失败，单独重跑 3/3 绿、随后全量重跑 350 全绿；M4-1 缓存套件（`TwoLevelCacheTest` 5、`LabCacheControllerTest` 7、`ProductPenetrationDefenseTest`、`NullValueMarkerStoreTest`、`ProductBloomFilterServiceTest`、`CacheExperimentDataServiceTest`、`CacheDefenseModeServiceTest` 6）、M4-2a 击穿套件（`ProductBreakdownDefenseTest` 20、`BreakdownProtectionConfigTest`、`LabCachePropertiesTest`）与本步 M4-3 套件（`HotKeyTrackerTest` 7、`HotKeyTrackingInterceptorTest` 4、`CachePreheatServiceTest` 8、`CachePreheatLifecycleTest` 5、`TwoLevelCacheTuningTest` 5、`LabCachePreheatPropertiesTest` 6、`LabCachePreheatControllerTest` 4、`CachePreheatEnabledContextIT` 1、`CachePreheatDisabledContextIT` 1）确认全绿未回归；未执行任何 Docker/compose 命令、未连实栈（模块 IT 按既有约定经 Testcontainers 随 reactor 运行）
> 抢救说明：实现会话（agent `ba2068b9`）在写完测试后、生成交接文档前因基础设施错误中止，本文档由独立评审会话依据代码与测试重建；所有断言均以 diff、测试运行输出或本评审的修复验证为准，未验证事项只出现在"待定"里。

# 当前进展

- M4-3 五个机制全部落地并有测试：本地热点探测（Caffeine 限容 + epoch 衰减）、Top-N 发布到共享 Redis ZSet `hot:products`、启动异步预热、每 5 分钟增量再预热、运行时 `refreshAfterWrite(10s)` 异步从 L2 刷新；外加手动触发端点 `GET/POST /lab/cache/preheat`。
- L1 参数（10k / 10s / 60s）从 `TwoLevelCache` 硬编码迁移为外置配置（`app.lab.cache.preheat.l1.*`），默认值与历史行为一致，`recordStats` 保留。
- 评审发现并修复 1 个 Important 缺陷（热点榜发布非原子且会被冷实例清空，见"缺陷"）；其余为有意披露的边界（见"缺陷/限制"与"待定"）。
- 有意未做：D10 面板、console UI、实栈演示与"重启 10s 内 L1 命中率 ≥80%"验收、跨实例一致性度量，全部列入待定。

# 完成功能项

## 1. 热点探测（每实例本地）

- `lab/cache/preheat/HotKeyTrackingInterceptor`：挂在 MVC `GET /api/products/*`（`preHandle`，`Ordered.LOWEST_PRECEDENCE`），只解析纯数字 id；挂点选在拦截器而不是 `@Cacheable` 方法体内，因为方法体只在 L1+L2 双 miss 时执行，而热点探测必须看到每一次读（包括命中）。跟踪失败只记日志绝不 500。
- `HotKeyTracker.record(long)`：写入 Caffeine `Cache<Long, Sample>`（`maximumSize = tracker.capacity`，默认 10 万）。计数器不是裸 AtomicLong：每个样本带写入时的衰减 epoch，有效分数 = `count >>> (currentEpoch - sampleEpoch)`，即"每 30s 减半"，无需后台扫描 10 万条，且在可注入 `Clock` 下测试完全确定。衰减到 0 的 key 由容量上限兜底淘汰，单一热点 key 不可能永久霸榜（持续衰减 + eviction）。

## 2. Top-N 发布（跨实例共享）

- 每 `tracker.flush-interval-seconds`（默认 10s）由调度器调用 `HotKeyTracker.publishTopN()`：本地 Top-N（分数=衰减后计数，同分按 id 升序）先写入旁路 key `hot:products:staging`，再 `RENAME` 原子替换共享 ZSet；读者永远看不到空榜或半榜，发布中途 Redis 挂掉时**旧榜保持原位**（评审修复点，见缺陷 1）。
- 本地 Top-N 为空（刚启动、无流量）时该次发布是 no-op，只刷新大小 gauge，不删除共享榜。
- ZSet 成员不带 TTL：这是启动预热的前提（重启实例靠它继承热点）。榜的"新鲜度"由存活实例每 10s 重发兜底。

## 3. 启动预热（异步、限速、可关）

- `CachePreheatLifecycle`（`ApplicationListener<ApplicationReadyEvent>`）：ready 后向守护调度器（`cache-preheat-scheduler`，默认 1 线程，`destroyMethod=shutdownNow`）提交启动预热（延迟 `load.startup-delay-ms`，默认 1000ms）；**不阻塞、不拖慢启动**。任务全部包 `guarded()`，异常不可能杀死 fixed-rate 调度。
- `CachePreheatService.run(STARTUP)`：`ZREVRANGE hot:products 0 topN-1` 读榜 → 逐个 `GET cache:product:<id>`（**只读 L2，永不查库**）→ 反序列化 `ProductResponse` → 直接 `l1.put`。**绝不写 L2**，否则会重置 M4-2 雪崩实验的同 TTL key 集（有测试断言 `values.set` 从未调用）。每 `load.chunk-size`（50）次读取 sleep `load.chunk-pause-ms`（50ms）限速；中断时恢复标志并以 `failed` 结束。
- 容错：Redis 挂 → 结果 `FAILED` + `cache_preheat_failures{operation="hot-list"|"load"}`，不抛到请求线程；空榜/无榜 → `SUCCESS`、requested=0（新栈场景零加载）。缓存 lab 开关关闭 → `CACHE_DISABLED`（没有 L1 可暖）。`enabled=false` 时启动/周期任务完全不排程，手动端点仍可用。
- 单飞：`AtomicBoolean` 保证启动/周期/手动不会重叠，撞车方立即得到 `BUSY` 结果且不触碰指标。

## 4. 周期再预热（增量）

- 每 `load.periodic-interval-seconds`（默认 300s）以 `PERIODIC` 触发同一服务；增量语义：L1 中年龄小于 `refresh-after-write-seconds` 的 entry 用 `Caffeine.policy().expireAfterWrite().ageOf()` 判定为"仍新鲜"，跳过且**不发 Redis 读**（测试用 `verify(values, times(1)).get(...)` 钉住）。

## 5. 运行时刷新（M4-2 系列的前置修复 `50538f8`）

- `TwoLevelCache` L1 命中路径：`getIfPresent` 命中后再 `l1.get(key, this::readL2)` 以触发 Caffeine 的 `refreshAfterWrite`（`getIfPresent` 本身永不触发刷新），调用方立即拿旧值，后台单飞 reload。
- CacheLoader 语义（评审确认正确）：`reload` 同步返回——L2 真空缺失返回 null → Caffeine 移除 L1 条目，下一请求走正常 L1→L2→防护链重建（该重建按 M4-1 既有语义计入 `cache_db_fallbacks{reason="miss"}`，预热本身不产生任何 DB 回源）；Redis 报错则抛异常——这是 Caffeine 文档化的"保留旧值"方式，旧值最多存活到 `expireAfterWrite(60s)`。`50538f8` 之前的版本让同步 CacheLoader 返回 `CompletableFuture`，会把 future 对象本身存进 L1，属真缺陷，已修。
- stampede/deadlock：refresh 天然每 key 单飞、跑在 Caffeine 默认执行器上；命中路径的同步 `readL2` 只在"两次查找之间恰好过期"的罕见交错发生。无新增锁序，无死锁路径。

## 6. API 与配置

- `GET /lab/cache/preheat`：状态快照（enabled、cacheEnabled、hotListKey、topN、hotListSize、trackedKeys、running、lastRun{trigger,at,outcome,durationMs,requested,loaded,skippedFresh,missing,failed,error}）；`POST` 同步执行一次 MANUAL 并返回 post-run 状态。两端口都过 `LabTokenFilter`（测试断言无 token → 401/40101，响应不含 token/密码字样）。
- 配置全部 `${ENV:default}` 外置（`application.yml` L213-242），`@PostConstruct validate()` 启动即校验：topN≤capacity、expire>refresh、全部数值正/非负、必填文本非空。`LabCachePreheatProperties` 与 `LabCacheProperties` 无重叠（后者继续持有 L2 TTL/抖动/防护参数；L1 调优作为预热故事的一部分归入 `preheat.l1.*`，经 `TwoLevelCacheSettings` bean 注入 `TwoLevelCacheManager`，单一来源）。

| 配置键（`app.lab.cache.preheat.`） | 环境变量 | 默认值 |
|---|---|---|
| enabled | LAB_CACHE_PREHEAT_ENABLED | true |
| top-n | LAB_CACHE_PREHEAT_TOP_N | 200 |
| hot-list-key | LAB_CACHE_PREHEAT_HOT_LIST_KEY | hot:products |
| tracker.capacity | LAB_CACHE_PREHEAT_TRACKER_CAPACITY | 100000 |
| tracker.decay-interval-seconds | LAB_CACHE_PREHEAT_TRACKER_DECAY_INTERVAL_SECONDS | 30 |
| tracker.flush-interval-seconds | LAB_CACHE_PREHEAT_TRACKER_FLUSH_INTERVAL_SECONDS | 10 |
| load.chunk-size | LAB_CACHE_PREHEAT_LOAD_CHUNK_SIZE | 50 |
| load.chunk-pause-ms | LAB_CACHE_PREHEAT_LOAD_CHUNK_PAUSE_MS | 50 |
| load.startup-delay-ms | LAB_CACHE_PREHEAT_STARTUP_DELAY_MS | 1000 |
| load.periodic-interval-seconds | LAB_CACHE_PREHEAT_PERIODIC_INTERVAL_SECONDS | 300 |
| load.scheduler-pool-size | LAB_CACHE_PREHEAT_SCHEDULER_POOL_SIZE | 1 |
| load.thread-name-prefix | LAB_CACHE_PREHEAT_THREAD_PREFIX | cache-preheat |
| l1.maximum-size | LAB_CACHE_L1_MAXIMUM_SIZE | 10000 |
| l1.refresh-after-write-seconds | LAB_CACHE_L1_REFRESH_AFTER_WRITE_SECONDS | 10 |
| l1.expire-after-write-seconds | LAB_CACHE_L1_EXPIRE_AFTER_WRITE_SECONDS | 60 |

## 7. 指标（命名延续 `cache_*` 家族，tag 基数全部有界）

| 指标 | 类型 | tags | 含义 |
|---|---|---|---|
| cache_hotkey_publish | Counter | cache=product, result=success\|failure | 热点榜发布次数 |
| cache_preheat_hot_list_size | Gauge | cache=product | 共享榜最近观测成员数 |
| cache_preheat_runs | Counter | cache, trigger=startup\|periodic\|manual | 预热轮数（BUSY 不计） |
| cache_preheat_loaded | Counter | cache, trigger | 实际写入 L1 的条数 |
| cache_preheat_duration | Timer | cache, trigger | 单轮墙钟耗时 |
| cache_preheat_failures | Counter | cache, operation=hot-list\|load | 失败分类 |
| （复用 M4-1）cache_l1_requests / cache_l1_hit_ratio / cache_l2_requests / cache_db_fallbacks | — | — | 命中率与回源 |

除注册断言外，测试断言了真实计数值（decay 精确到右移位、runs/loaded/failures 的具体数字、`cache_l1_hit_ratio=0.5` 等）。

# 设计与实现要点

- 预热数据源恒为 L2：重启后 Redis 还留着热点值，预热不碰 MySQL——这是"重启 10s 内命中率"故事的物理基础，也是"不做预测模型"教学边界的落实（数据驱动 + 自适应淘汰 + 自动刷新）。
- 衰减用 epoch 右移而非桶轮转/定时扫描：O(1) 记录、读取时惰性折算、测试可用 MutableClock 精确推演；63 个 epoch 后必然归零（`elapsed >= Long.SIZE` 防移位溢出）。
- 跨实例语义 = last-writer-wins 的整榜替换（每个实例发布自己的 Top-N 视图），分数为本地衰减计数；这是演示语义（共享流量下收敛），不是精确的全局计数——刻意不做 ZSet 累加合并，避免"重启继承旧分数永远偏高"的更差行为。
- 与 M4-2a 防护链的关系（评审逐项核实）：预热只写 L1，绕过 `@Cacheable` 但不绕过防护（防护本来就只在双 miss 触发）；不重写任何 L2 key 的 TTL；穿透空值 marker 与 bloom 存独立 key 空间，预热读取的 `cache:product:<id>` 恒为裸 `ProductResponse` JSON，不会把 marker/包装对象灌进 L1；逻辑过期的旧值最多以 L2 物理 TTL（5 分钟）的新鲜度被复制进 L1，与普通 L2 读路径一致，未削弱 M4-2a 的 retention>hot-key-ttl 校验成果。

# 发现的缺陷与修复情况

1. **Important（评审已修复，含回归测试）**：`HotKeyTracker.publishTopN` 原版先 `DEL hot:products` 再逐批 `ZADD`——(a) 读者可能在窗口内看到空榜/半榜（恰好打在"压测中重启实例、新实例读榜预热"的演示路径）；(b) delete 成功后 add 因 Redis 故障失败，共享榜被**整体销毁**，与其 javadoc 自诺的"previous Redis list is left in place"直接矛盾（原测试用 mock 从未验证过这一点）；(c) 本地 Top-N 为空的实例（刚重启、无流量副本）每 10s 一次 delete-不-add，把其他实例赖以预热的共享榜清空。修复：staging key + `RENAME` 原子替换、空 Top-N 视为 no-op、失败路径 best-effort 清理 staging（下次发布也会预清理，泄漏有界）。测试改为断言 staging-add + rename、断言空视图发布零写入、断言中途故障后共享 key 从未被删（`HotKeyTrackerTest` 6→7 用例）。
2. Minor（有意接受，已披露）：共享 ZSet 无 TTL。全部实例消失后榜单会长期滞留；影响有界——过期 id 的 L2 值早已到期，预热只会计 `missing`，不会查库、不会给出错误数据。
3. Minor（有意接受，已披露）：重启实例首次发布前继承的是历史衰减计数（分数不跨实例合并）；演示口径足够，教学上不声称精确。
4. Minor（有意权衡，已披露）：L1 命中路径为触发刷新做两次查表（`getIfPresent` + `get(key,fn)`）；Caffeine 的 `getIfPresent` 不触发 `refreshAfterWrite`，这是该行为的唯一正规触发方式，代价换正确性。
5. 无遗留 Critical。评审同时确认了 `50538f8`（同步 reload）修复本身正确、配置校验完整、预热与击穿/穿透防护无绕过交互（见"设计"节）。

# 待定事项

- **D10 面板未接**：`cache_preheat_*`/`cache_hotkey_publish` 指标与 `/lab/cache/preheat` 快照尚无 Grafana D10 面板与 console UI 展示（属面板任务，涉及 `code/lab/**` 与观测配置，本步按边界不动）。
- **实栈演示与验收未做**：`caffeine-warmup.js` k6 场景与"压测中重启实例、10s 内 L1 命中率 ≥80%"的验收（设计 §场景 10）从未在实栈执行；本步全部结论来自单测与 Testcontainers 级 IT，未验证真机时序。
- **跨实例一致性未度量**：多实例下 last-writer-wins 榜与"全局真实 Top-200"的偏差没有测量工具，若演示需要应加双实例对比脚本（属后续实验步骤）。
- 预热写入 L1 不计 `cache_l1_requests`（只有真实读计），命中率口径是"请求读 L1"，面板文案如引用需说明。
- staging key 命名固定在 `hot:products:staging`；若 hot-list-key 被改为多实例不同值，各写各榜（这已是现状语义），演示双实例时需统一 env。
