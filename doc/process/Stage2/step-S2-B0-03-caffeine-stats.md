# 步骤交接：S2-B0-03 Caffeine stats 绑定 Micrometer + `estimatedSize` 导出

> 任务/步骤：Stage2 B0-03 —— 把 Caffeine L1 的 `recordStats()` 统计与 `estimatedSize()` 绑定为真实 Micrometer 序列，补上 B0-01 字典确认的 `cache_size`/L1 命中率"无序列"缺口
> 日期：2026-10-28
> 分支 / HEAD：feature/stage2-workbench 基线 worktree 提交 `d07590c`
> 关联任务：`doc/process/Stage2/stage2-开发测试与验收计划.md` B0-03
> 测试 / verify：聚焦新测试 12/12 绿；模块全量见附录（基线 445 tests，本步新增 12）
> 运行状态：本步为改代码+跑单测，未做真机演练；真机 curl `/actuator/prometheus` 见 §真机样本验证（unverified，主会话后补）

# 当前进展

- Caffeine L1 原生统计已导出为 5 条真实序列（每个 L1 缓存实例一组，标签仅低基数 `cache`）：
  - `cache_l1_caffeine_requests_total{cache,result=hit|miss}`（FunctionCounter，读 `stats().hitCount()/missCount()`，导出名自动带 `_total`）
  - `cache_l1_caffeine_hit_ratio{cache}`（Gauge，`stats().hitRate()`，0..1）
  - `cache_l1_caffeine_evictions_total{cache}`（FunctionCounter，`stats().evictionCount()`，含容量驱逐与过期驱逐）
  - `cache_l1_estimated_size{cache}`（Gauge，`estimatedSize()`；"估算"语义同时写进指标名与 HELP 文本）
- 注册开关外置：`app.lab.cache.preheat.l1.micrometer-stats-enabled`（env `LAB_CACHE_L1_MICROMETER_STATS_ENABLED`，yml 默认 true），无任何硬编码新参数。
- `recordStats()` 原本已开启（`TwoLevelCache` L1 构建链），本步不动它。
- 最新 commit：`feat: bind Caffeine L1 stats to Micrometer with estimated size export`。

# 完成功能项

- 主代码（5 个文件）：
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/catalog/cache/TwoLevelCache.java`：新增 `registerCaffeineStats(MeterRegistry)`（构造器内按开关调用）。FunctionCounter/Gauge 的 state 对象是 Caffeine 缓存本身、每次读取重新 `stats()` 求值（刻意不持有 CacheStats 快照，快照会冻结为注册时刻的值）。类 Javadoc 同步说明两套视图。
  - `.../catalog/cache/TwoLevelCacheSettings.java`：record 增加第 4 个组件 `micrometerStatsEnabled`；保留 3 参便捷构造器（默认 true），旧调用点（测试/工具/`defaults()`）零改动。
  - `.../lab/cache/preheat/LabCachePreheatProperties.java`：`L1` 增加 `micrometerStatsEnabled` 绑定字段（yml 单一默认值来源口径不变）。
  - `.../lab/cache/preheat/CachePreheatConfig.java`：`twoLevelCacheSettings` bean 把绑定值传入 4 参 record。
  - `code/services/seckill-app-jdk21/src/main/resources/application.yml`：`app.lab.cache.preheat.l1` 下新增 `micrometer-stats-enabled: ${LAB_CACHE_L1_MICROMETER_STATS_ENABLED:true}`。
- 测试（1 个新文件，12 用例）：
  - `.../src/test/java/com/javalearn/lab/seckill/catalog/cache/TwoLevelCacheMicrometerStatsTest.java`：
    读写/驱逐（容量驱逐 + 过期驱逐）后 stats 序列值真实变化；`estimatedSize` gauge 随 put/evict 变化；命中率 gauge 等于 `stats().hitRate()` 实时值；caffeine 序列与应用口径序列（`cache_l1_requests`）共存且互不覆盖；标签只有 `cache`（+`result`），无 Java 侧 `instance`；注册开关 off 时 5 条新序列一条都不注册（旧序列不受影响）；cache 总开关 off 时经 `TwoLevelCacheManager` 断言"无序列、无假 0"；开关 on 时 5 条序列齐；3 参构造器保持注册；`PrometheusMeterRegistry.scrape()` 断言导出名含 `_total` 后缀、HELP 含 "Estimated"、无 `_info`/`_created` 陷阱。Redis 全 mock，无容器依赖。
- 未动：`/lab/cache/state` 响应结构（B3）、chaos-controller、claims.yml、compose 文件。

# 设计逻辑

- 双视图命名：应用路径计数 `cache_l1_requests_total{result}` 早已存在，与 Caffeine 内部计数**数值天然不同**（一次 Spring-cache 命中会触发两次 Caffeine 读：`getIfPresent` + refresh 触发读）。因此新序列统一带 `caffeine` 源标记（`cache_l1_caffeine_*`），两套序列各自成立、面板可对照，不做别名复用，避免同一序列两种口径。
- estimatedSize 语义：指标名 `cache_l1_estimated_size` 直接把"估算"写进名字，HELP 文本再标 "(not exact!)"。B0-01 字典里预留的 `cache_size`/`cache_l1_size` 词表名未被采用——采用"名字自证估算"口径；字典回填时按新名登记。
- FunctionCounter 而非 Counter：Caffeine stats 是累计单调值，`FunctionCounter(name, l1, c -> c.stats().xxx())` 每次 scrape 重新求值，Prometheus 端导出为 `_total` 计数，`increase()` 窗口语义正确；不引入 `CaffeineCacheMetrics` binder（其命名 `cache_gets_total`/`cache_size` 与本仓 `cache_l1_*` 前缀风格不一致且会再叠一套口径）。
- 标签纪律：只有 `cache`（`result` 用于 requests）。`instance` 由 Prometheus 抓取目标提供（生产面板经 relabel 成 `exported_instance`，与 B0-01 字典 `avg by(exported_instance)` 口径一致），Java 侧刻意不加，避免双 instance。
- **L1 关闭口径（本步决定）：不注册该序列（absence = 不适用），绝不伪造 0。** 两种"关闭"路径都成立：① lab `cache` 总开关 off → `TwoLevelCacheManager` 返回 `NoOpCache`，`TwoLevelCache` 实例根本不存在，5 条序列（连同旧 `cache_l1_*`）全不注册；② 新注册开关 off → 只关本步的 5 条 caffeine 序列，旧序列不受影响。PromQL 侧"无数据"由面板/D10 与 0 区分（B0-01 字典已有"无序列=未接线/不适用"列）。
- 指标名陷阱规避：无 `_info` 后缀（会被 Micrometer/Prometheus 剥或附加）、无 `_created`（Prometheus 保留时序）；`_total` 只由 registry 对 counter 类型自动追加，注册名不带。
- 断言测试与共享上下文：新测试全部 standalone（mock Redis + SimpleMeterRegistry/PrometheusMeterRegistry），不进 Spring 共享上下文，无累计污染问题。

# 存在缺陷项&TODO

- 真机样本验证：unverified（本步未起真机栈 curl `/actuator/prometheus`）。
- B0-01 指标字典回填：`cache_size`/`cache_l1_size` 两行的"未接线"结论需按本步新名（`cache_l1_estimated_size` 等 5 条）更新验收证据列——留给字典维护批次（本步未动字典，防止与并行批次改冲）。
- Grafana/D10 面板增加"每实例 L1 估算条目数/命中率"图：不在本步范围。
- 已知限制：Caffeine stats 不区分"refresh 读"与"业务读"，`cache_l1_caffeine_requests` 计数大于应用请求数属预期（双视图口径，见设计逻辑）。

## 真机样本验证

| 项目 | 状态 | 备注 |
|---|---|---|
| 三实例 `/actuator/prometheus` 存在 `cache_l1_estimated_size{cache="product"}` 且数值随负载变化 | unverified | 主会话真机后补 |
| `cache_l1_caffeine_requests_total`/`_evictions_total`/`_hit_ratio` 序列存在、标签仅 cache(+result) | unverified | 单测层已证 scrape 文本 |
| cache 开关 off 后上述序列消失（无数据≠0） | unverified | 单测层已证 manager 行为 |
| Prometheus 端 `increase(cache_l1_caffeine_evictions_total[5m])` 出数 | unverified | 依赖真机抓取 |

# 待定事项和注意事项

- 下一步：B0-04（L2 TTL/键前缀等硬编码外置）与本步共用 `app.lab.cache.preheat.l1.*` 配置块，B0-04 作者请勿重排本步新增的 `micrometer-stats-enabled` 键。
- 合并注意：本步交付以 feat commit 为单元进 `feature/stage2-workbench`；worktree 内 Maven 本地仓库缺兄弟模块快照，验证命令需带 `-am`（基线任务给的裸 `-pl` 命令在本 worktree 会因 platform-common 等未 install 而失败）。
- 面板接线（A04 前置）：命中率两条线（`cache_l1_hit_ratio` 应用口径 / `cache_l1_caffeine_hit_ratio` Caffeine 口径）语义不同，接线时按字典选一口径为主。

# 补充

（留白：待主会话真机验证后回填证据与字典行号。）

# 附录

- 复现命令：
  - 聚焦：`cd code && mvn -f pom.xml -pl services/seckill-app-jdk21 -am test -Dtest='TwoLevelCacheMicrometerStatsTest,TwoLevelCacheTuningTest,ToggleAwareCacheManagerTest' -Dsurefire.failIfNoSpecifiedTests=false`（20/20 绿）
  - 全量：`cd code && mvn -f pom.xml -pl services/seckill-app-jdk21 -am test > /tmp/b003.log 2>&1; echo "EXIT=$?"; grep -a 'Tests run:' /tmp/b003.log | tail -3`
- 证据：日志 `/tmp/b003-focus.log`、`/tmp/b003.log`（含 surefire `Tests run:` 汇总与退出码）
- 关键文件：
  - 绑定实现：`code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/catalog/cache/TwoLevelCache.java`（`registerCaffeineStats`）
  - 配置链：`application.yml` `app.lab.cache.preheat.l1.micrometer-stats-enabled` → `LabCachePreheatProperties.L1` → `CachePreheatConfig` → `TwoLevelCacheSettings` → `TwoLevelCache`
