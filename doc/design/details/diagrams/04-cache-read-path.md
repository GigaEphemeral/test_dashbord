# 图 4 · 缓存读路径与四类防护

> 代码基线：`504fc74`（本目录所有图统一基线）
> 源文件：[04-cache-read-path.mmd](04-cache-read-path.mmd) · 类型：`flowchart TB`

L1 Caffeine → L2 Redis → 穿透/击穿/雪崩防护 → DB 回源，展示 `POST /lab/cache/mode` 的运行时模式分派、各策略参数，以及指标落点。

读图要点：一个进程内模式开关逐实例生效；`off` 保持历史行为；`db-fallback` 超出 50 permits/1000ms 直接 42901；异步刷新每键最多一个 in-flight。

```mermaid
%% 代码基线：504fc74
%% 图 4 · 缓存读路径与四类防护（L1 / L2 / 穿透 / 击穿 / 雪崩）
%% 依据：CachedProductViewService、TwoLevelCache、AvalancheCacheResolver、ProductAvalancheDefense、ProductBreakdownDefense、ProductPenetrationDefense、CacheDefenseModeService、LabCacheController、application.yml（app.lab.cache.*）
flowchart TB
  REQ["GET /api/products/{id}"] --> PC["ProductController"]
  PC --> CP["CachedProductViewService.get<br/>@Cacheable(product)<br/>cacheResolver=avalancheCacheResolver"]

  CP --> L1{"Caffeine L1<br/>10k / refreshAfterWrite 10s / expire 60s"}
  L1 -->|"命中"| HIT["直接返回<br/>cache_l1_requests{result=hit}"]
  L1 -->|"未命中"| L2{"Redis L2<br/>cache:product:{id}<br/>物理 TTL 5min"}
  L2 -->|"命中"| WARM["回填 L1 后返回<br/>cache_l2_requests{result=hit}"]
  L2 -->|"未命中 / Redis 异常"| MISS["cache_l2_requests{result=miss}<br/>Redis 异常另计 cache_l2_errors<br/>cache_db_fallbacks{reason=miss}"]

  MISS --> AV["ProductAvalancheDefense.load<br/>只在 L1+L2 都未命中时进入"]
  PEN["ProductPenetrationDefense.load<br/>穿透防护"]
  BRK["ProductBreakdownDefense.load<br/>击穿防护"]
  JIT["L2 写入侧 TTL 抖动 ±20%<br/>cache_avalanche_ttl_jitter{result}<br/>本次回源"]
  L1M["L1 镜像：10s 内新鲜<br/>60s 内旧值 + 单飞异步刷新"]
  LIM{"AvalancheLimiter<br/>50 permits / 1000ms 固定窗口"}
  DBREAD["ProductReadService.get<br/>@Transactional(readOnly=true)<br/>→ read-pool / 6447"]
  REF["异步刷新执行器<br/>每键最多 1 个 in-flight"]
  AV --> MODE{"CacheDefenseModeService.current()<br/>POST /lab/cache/mode 运行时切换<br/>进程内、逐实例，seed=off"}
  MODE -->|"off"| DBREAD
  MODE -->|"null-cache"| PEN
  MODE -->|"bloom"| PEN
  MODE -->|"mutex / logical-expire"| BRK
  MODE -->|"ttl-jitter"| JIT
  MODE -->|"l1-cache"| L1M
  MODE -->|"db-fallback"| LIM

  PEN --> P1{"null-cache？"}
  P1 -->|"是"| MARK{"null:product:{id} 标记存在？<br/>TTL 10s + jitter 5s"}
  MARK -->|"是"| EMPTY["Optional.empty<br/>→ 40401"]
  MARK -->|"否"| DBREAD
  P1 -->|"否（bloom）"| BLOOM{"Redisson Bloom<br/>mightContain(id)？"}
  BLOOM -->|"否"| EMPTY
  BLOOM -->|"是（含 1% 误判）"| DBREAD

  BRK --> B1{"mutex？"}
  B1 -->|"是"| LOCK["Redisson lock:product:{id}<br/>tryLock 200ms / lease 2000ms"]
  LOCK -->|"未拿到且 on-lock-timeout=stale<br/>且有旧值"| STALE1["返回旧值<br/>cache_breakdown_mutex{result=stale-serve}"]
  LOCK -->|"未拿到且无旧值 / on-lock-timeout=db"| DBREAD
  LOCK -->|"拿到锁"| DBL["双重检查：L2 / 未过期 stale<br/>命中则返回，不查 DB"]
  DBL -->|"仍无"| DBREAD
  B1 -->|"否（logical-expire）"| LOG{"stale 条目存在且<br/>logical TTL 30s 内？"}
  LOG -->|"是"| FRESH1["直接返回旧值<br/>logical fresh-serve +1"]
  LOG -->|"否（已过期，retention 600s 内）"| STALE2["立即返回旧值<br/>单飞异步刷新<br/>cache_breakdown_logical{result=stale-serve}"] --> REF
  LOG -->|"无条目 / 超过 retention"| DBREAD

  L1M --> LM1{"镜像条目存在且<br/>10s 内？"}
  LM1 -->|"是"| FRESH2["直接返回<br/>l1 fresh-serve +1"]
  LM1 -->|"否，60s 窗口内"| STALE3["返回旧值 + 单飞异步刷新<br/>cache_avalanche_l1{result=stale-serve}"] --> REF
  LM1 -->|"冷启动 / 超过 60s"| DBREAD

  LIM -->|"拒绝"| R429["42901<br/>cache_avalanche_limiter{result=rejected}"]
  LIM -->|"允许"| DBREAD

  JIT --> DBREAD
  DBREAD --> DB[("MySQL")]
  DBREAD --> MET["cache_db_fallbacks<br/>reason=penetration / breakdown / avalanche"]
  DB --> STORE["回填 L2（ttl-jitter 时 ±20%）<br/>更新 stale / L1 镜像"]
  STORE --> CP
  REF --> DBREAD
  REF --> PUB["刷新结果 publish 回共享缓存<br/>cache_breakdown_refresh{result}<br/>cache_avalanche_l1_refresh{result}<br/>submitted / deduped / success / failure / rejected"]
```
