> 步骤交接 · D10 缓存实验室 Grafana 面板（设计 §7.4）
> 日期：2026-10-28
> 分支 / HEAD：worktree `agent-general-purpose-472cfdf2`，基线 `a37abfff`（`fix: stagger the deadlock workers ...`）
> 关联任务：M4 缓存实验室观测层（消费 M4-1 两级缓存/穿透、M4-2a 击穿、M4-3 预热已落地的指标；不改任何 Java/compose/脚本/Vue）
> 验证：`python -c "json.load(...)"` 与 `node -e "require(...)"` 均通过；未执行 Docker/compose，未启动 Grafana，**无法做视觉验证**，PromQL 也未对活栈执行。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

# 当前进展

- 新增 `code/lab/docker/grafana/dashboards/ha-d10.json`：15 个数据面板 + 1 个说明文本 + 5 个 row 分组，`uid=ha-d10`，`schemaVersion=39`，datasource 占位 `{"type":"prometheus","uid":"prometheus"}`，风格对齐 `ha-d1`–`ha-d4`。
- 未改 provisioning：`code/lab/docker/grafana/provisioning/dashboards/dashboards.yml` 已经把 `/var/lib/grafana/dashboards` 整目录纳入 file provider（`updateIntervalSeconds: 10`），新增文件无需改配置即可加载。
- 面板覆盖设计 §7.4 的 L1/L2 命中率、DB QPS、布隆拦截、锁等待、预热；"当前策略与切换标注"因无指标/无 annotation 供给而明确不画（原因见"存在缺陷项&约束"）。
- 基线没有雪崩实现：`catalog/cache/Avalanche*`、`ProductAvalancheDefense` 在工作树中不存在，`064cea4`（雪崩提交）不是 HEAD 祖先。因此没有写任何雪崩专属指标名；DB 回落面板用 `sum by (reason)`，雪崩合并后 `reason="avalanche"` 会自动出现。

# 完成功能项

模板变量只有 `instance`，与 D1/D2/D4 完全一致（`label_values(up{job=~"seckill-app.*"}, instance)`，多选 + All，`allValue=.*`）。所有应用指标查询都带 `job=~"seckill-app.*",instance=~"$instance"`。

| # | 面板 | 类型 | PromQL（核心） | 教学用途 |
|---|---|---|---|---|
| 1 | L1 / L2 命中率（按实例） | timeseries | `sum by (instance, cache) (rate(cache_l1_requests_total{result="hit"}[1m])) / clamp_min(sum by (instance, cache) (rate(cache_l1_requests_total[1m])), 1e-9)`；L2 同构 | 两级缓存各自的命中率；L2 分母只算 L2 查询 |
| 2 | 缓存请求速率（按实例） | timeseries | `sum by (instance) (rate(cache_l1_requests_total[1m]))`、`cache_l2_requests_total` 同构 | 观察 L1 流量与 L1 miss 后下探 L2 的流量 |
| 3 | DB 回落占比（占 L1 查询） | stat | `sum(rate(cache_db_fallbacks_total[1m])) / clamp_min(sum(rate(cache_l1_requests_total[1m])), 1e-9)` | 保护态目标 <5%（场景 5） |
| 4 | DB 回落按原因（堆叠） | timeseries, stacked | `sum by (reason) (rate(cache_db_fallbacks_total[1m]))` | 当前 `miss`/`penetration`/`breakdown`，未来自动纳入 `avalanche` |
| 5 | 布隆过滤器结果 | timeseries | `sum(rate(cache_bloom_requests_total{result="absent"}[1m]))`，另有 `hit`、`error` | 拦截量 vs 放行量 vs 过滤器故障 |
| 6 | 空值缓存：命中 / 写入 / 错误 | timeseries | `cache_null_cache_requests_total{result="hit"\|"miss"}`、`cache_null_cache_writes_total`、`cache_null_cache_errors_total` 的 rate | 空值标记的收益与 Redis 故障降级 |
| 7 | mutex：重建 / 等待后命中 / 超时 / 旧值 | timeseries | `sum(rate(cache_breakdown_mutex_total{result="rebuild"\|"double-check-hit"\|"timeout"\|"stale-serve"\|"error"}[1m]))` | 击穿时"只重建一次"的互斥证据；锁等待只有超时计数，无等待耗时 |
| 8 | logical-expire：冷重建 / 新值 / 旧值 | timeseries | `cache_breakdown_logical_total{result="cold-rebuild"\|"fresh-serve"\|"stale-serve"}` 的 rate | 逻辑过期的旧值兜底路径 |
| 15 | 互斥事件中的重建份额 | stat | `sum(rate(cache_breakdown_mutex_total{result="rebuild"}[1m])) / clamp_min(sum(rate(cache_breakdown_mutex_total[1m])), 1e-9)` | 重建在全部锁事件中的占比；分母防 0 |
| 9 | logical-expire：异步刷新结果 | timeseries | `cache_breakdown_refresh_total{result="submitted"\|"deduped"\|"success"\|"failure"\|"rejected"}` 的 rate | 刷新去重与失败 |
| 10 | 预热运行与每轮加载量 | timeseries | `sum by (trigger) (rate(cache_preheat_runs_total[1m])) * 60`；`rate(cache_preheat_loaded_total[1m]) / clamp_min(sum by (trigger) (rate(cache_preheat_runs_total[1m])), 1e-9)` | 运行/分、加载条数/轮，分母防 0 |
| 11 | 预热耗时 P95 | timeseries | `histogram_quantile(0.95, sum by (le, trigger) (rate(cache_preheat_duration_seconds_bucket[1m])))` | startup/periodic/manual 的耗时 |
| 12 | 热榜大小与发布结果 | timeseries | `cache_preheat_hot_list_size`、`cache_hotkey_publish_total{result="success"\|"failure"}` 的 rate | 共享 ZSet 规模与发布健康度 |
| 13 | HikariCP 连接 | timeseries | `hikaricp_connections_active/pending/idle/max`（`sum`/`max by (instance)`） | DB 压力与池等待 |
| 14 | DB QPS 与缓存回源速率 | timeseries | `rate(mysql_global_status_questions{job="mysqld-exporter"}[1m])`、`sum(rate(cache_db_fallbacks_total[1m]))` | MySQL 实际 QPS 对照缓存回源 |

所有除法分母都套 `clamp_min(..., 1e-9)`；固定 `[1m]` 窗口与 D1–D4 的写法一致。计数器在 Prometheus 侧统一带 `_total` 后缀（Micrometer 命名 + Prometheus 客户端追加），gauge 保持 `cache_l1_hit_ratio`、`cache_preheat_hot_list_size` 原样。

# 设计逻辑

- **先 grep 再写 PromQL**：命中/回落来自 `TwoLevelCache`（`cache_l1_requests`、`cache_l2_requests`、`cache_db_fallbacks{reason="miss"}`、`cache_l2_errors`），穿透来自 `ProductPenetrationDefense`/`ProductBloomFilterService`/`NullValueMarkerStore`，击穿来自 `ProductBreakdownDefense`（`cache_breakdown_mutex/logical/refresh`），预热来自 `lab/cache/preheat/CachePreheatService` 与 `HotKeyTracker`（`cache_preheat_runs/loaded/duration/failures`、`cache_hotkey_publish`、`cache_preheat_hot_list_size`）。标签值也逐个核对：`trigger=startup|periodic|manual`、`reason=miss|penetration|breakdown`、`result` 的取值集合。
- **DB 回落按 reason 而不是按固定三条查询**：`reason` 是既有有界标签族，`ProductBreakdownDefense` 的注释明确说明它是该族的第三个值；用 `sum by (reason)` 既覆盖现状，也不会为未合并的雪崩实现编造序列名。
- **命中率按实例分组**：`sum by (instance, cache)` 保留实例维度，配合 `$instance` 变量可以只看某台或全部；`cache` 当前只有 `product`，但保留标签便于未来多缓存。
- **预热"每轮加载量"用比值**：`loaded/runs` 直接回答"一轮预热暖了多少条"，`clamp_min` 处理刚启动 runs=0 的瞬间；运行频率用 `×60` 显示为次/分，避免 0.0167 次/秒难以读数。
- **锁等待的表达边界**：代码只有 `timeout`/`double-check-hit`/`stale-serve` 计数，没有等待时长或队列长度，面板描述里如实写明，不用 `histogram_quantile` 伪造 P95。
- **DB QPS 取 MySQL 侧**：应用没有 SQL 语句计数指标，Hikari 也只暴露连接池；用 `mysqld-exporter` 的 `mysql_global_status_questions` 作为 QPS，旁边叠缓存回源速率做因果对照。
- **不画"当前策略"**：全仓 `Gauge.builder` 只有 `cache_l1_hit_ratio`、`cache_preheat_hot_list_size` 与线程实验室指标；没有 `cache_defense_mode` 一类 gauge。`/lab/cache/state` 是带 token 的 HTTP JSON，不是 Prometheus 数据，硬塞进面板只能造假。
- **不画 annotation row**：`ha-d1`–`ha-d4`、`ha-d9` 的 `annotations.list` 全为空，仓库内也没有 provision 的 annotation 查询；按任务约束不发明 annotation 数据源，切换时间点留给实验记录。

# 存在缺陷项&约束

1. **无 `mode` 模板变量**：D1–D4 只有 `instance`（D3 无变量），缓存指标也没有 `mode` 标签；任务要求的 "instance/mode 与 D1–D4 一致" 落到实际就是只保留 `instance`，加一个空的 `mode` 变量会误导使用者。若后续加入 `cache_defense_mode{mode=...}` gauge，再补变量。
2. **无当前策略 / 切换标注**：见"设计逻辑"，这是指标与 provisioning 的缺口，不是面板遗漏。切换操作只能通过 `POST /lab/cache/mode` 完成，状态只能查 `/lab/cache/state`。
3. **雪崩面板未单列**：基线无雪崩类；`064cea4` 里虽已有 `cache_db_fallbacks{reason="avalanche"}`、`cache_avalanche_l1`、`cache_avalanche_l1_refresh`、`cache_avalanche_limiter`，但它们不在本基线，按"不发明指标名"的要求没有写入查询。合并后第 4 块面板会自动出现 avalanche 堆叠，专属面板见待定。
4. **`mysql_global_status_questions` 未在本仓库被引用**：仓库里只有 D3 使用 `mysql_global_status_threads_connected`；`questions` 属于 mysqld-exporter v0.16 默认 global_status collector 的标准输出，但没有实栈证据。若该序列为空，第 14 块面板只剩缓存回源曲线。
5. **无活栈验证**：没有执行任何 Docker 命令，Grafana 未启动；面板布局、单位、图例、`$instance` 过滤和 PromQL 结果均未经视觉/运行时确认。JSON 只证明结构合法。
6. **无数据前提**：缓存实验开关关闭时 `TwoLevelCacheManager` 返回 `NoOpCache`，`cache_*` 序列不存在；预热关闭时 `cache_preheat_*` 也不会注册。面板显示 No data 是预期，不是故障。
7. **`logical-expire` 计数为 0 的歧义**：代码在构造时注册 mutex 与 logical 两套计数器，只有当前策略会自增；因此 0 既可能表示"未启用"也可能表示"启用了但没触发"，面板描述已注明，但无法从指标本身区分。

# 待定事项

- **实栈验收**：起栈后打开 D10，先 `GET /lab/cache/state` 确认缓存/预热开关，再压 `GET /api/products/*`，逐块确认序列出现；重点是 `cache_l1_requests_total`、`cache_preheat_runs_total`、`hikaricp_connections_*` 与 `mysql_global_status_questions` 是否真的被 scrape 到。
- **雪崩合并后**：确认 `reason="avalanche"` 出现在第 4 块面板；再补 `cache_avalanche_l1{result}`、`cache_avalanche_l1_refresh{result}`、`cache_avalanche_limiter{result}` 的独立面板（名称来自未合并提交 `064cea4`，合并时需以合并后的代码为准复核）。
- **策略切换时间轴**：若确实需要 annotation，需在 `provisioning/dashboards` 之外新增 Grafana annotation 数据源/查询（涉及 provisioning 改动，超出本任务边界，未做）。
- **策略 gauge**：若教学上需要"当前策略"常驻面板，应在应用侧新增 `cache_defense_mode{mode=off|mutex|logical-expire}` 之类的 gauge（Java 改动，超出本任务边界），而不是在面板里查询非指标端点。
- **视觉复核**：图表宽度、legend 数量（mutex 5 条、刷新 5 条、空值 4 条）与阈值颜色需要在真实 Grafana 里确认；本环境无法渲染。

---

# 补充（D10 真机取数）

> 日期：2026-10-27 · 分支 `feature/ha-lab`（主 worktree `D:\000CODE\JAVA-LEARN`）· Prometheus `http://127.0.0.1:8080/prom` · 只改本仪表盘与本文件，未改 Java / compose / 脚本 / `.env*`。

## 前置更正：运行镜像比 HEAD 旧

真机取数前先核对容器内 jar：`docker exec jl-app-1 sh -c 'unzip -l /app/app.jar | grep -i avalanche'` 返回 0 行，`/actuator/prometheus` 也没有任何 `cache_avalanche_*` 序列。也就是说 `java-learn-lab/seckill-app:jdk21`（2026-10-27 05:21 构建）虽然晚于 `064cea4`，但构建缓存里的源码并不含 M4-2b。为保证四个雪崩面板能对活栈取数，本次做了一次镜像刷新：

1. `docker build --target build -t d10-app-build:tmp -f services/seckill-app-jdk21/Dockerfile .`（复用 Maven 层，产出含 `Avalanche*` 类的 jar）；
2. 旧镜像备份为 `java-learn-lab/seckill-app:jdk21-pre-d10`，用临时 Dockerfile `FROM ...:jdk21-pre-d10` + `COPY d10-app.jar /app/app.jar` 覆盖同名 `:jdk21`（不改仓库 Dockerfile）；
3. `docker compose ... up -d --no-build --no-deps --wait app-1 app-2 app-3` 重建三实例；容器 IP 变化导致 Nginx 仍指向旧 IP，执行 `docker exec jl-nginx nginx -s reload` 后三实例恢复轮询。

刷新后 `cache_avalanche_ttl_jitter_total` / `cache_avalanche_l1_total` / `cache_avalanche_l1_refresh_total` / `cache_avalanche_limiter_total` 与 `cache_db_fallbacks_total{reason="avalanche"}` 均出现，指标名与源码一致（Prometheus 对 counter 追加 `_total`）。

## 修正清单

1. **面板 11「预热耗时 P95」原 PromQL 永远为空**：`Timer.builder("cache_preheat_duration")` 未开 `publishPercentileHistogram()`，导出只有 `cache_preheat_duration_seconds_{count,sum,max}`，没有 `_bucket`。改为 `max by (trigger) (cache_preheat_duration_seconds_max{...})`，标题改为「预热耗时（最大值）」，图例 `{{trigger}} 最大`，单位仍为 `s`。
2. **四个雪崩面板 legend 是 `__auto`**：Grafana 不会把 `__auto` 当模板，会原样显示。面板 105–107 改为 `{{result}}`；面板 108 第一条 `{{result}}`、第二条（`cache_db_fallbacks_total{reason="avalanche"}`）改为 `avalanche 落库/s`。单位 `reqps` 正确，无需改。
3. **文案过时**：顶部说明与面板 4 描述仍写「雪崩未合并、合并后才会出现」，改为 M4-2b 已合并、`reason="avalanche"` 已出现。
4. **三个存疑指标名全部确认存在，未改名**：
   - `hikaricp_connections_idle`：存在，`sum by (instance)` 观测 5–7；
   - `hikaricp_connections_max`：存在，`max by (instance)` 观测 10；
   - `mysql_global_status_questions{job="mysqld-exporter"}`：存在，`instance="mysqld-exporter:9104"`，观测 149961。
   - 注意 Hikari 指标带 `pool=read-pool|write-pool`，现有 `sum/max by (instance)` 会把两个池合并，这是面板口径而非指标名错误。

## 逐面板取数（`$instance` 取 All，即 `instance=~".*"`）

| # | 面板 | PromQL（节选） | 观测：序列数 / 样例值 | 结论 |
|---|---|---|---|---|
| 1 | L1 / L2 命中率 | `sum by (instance,cache)(rate(cache_l1_requests_total{result="hit"}[1m])) / clamp_min(sum by (...)(rate(...[1m])),1e-9)`；L2 同构 | 各 3 序列；L1：app-3 0.0286、app-1/2 0；L2：app-2 0.371、app-3 0.333、app-1 0.0714 | 有数据 |
| 2 | 缓存请求速率 | `sum by (instance)(rate(cache_l1_requests_total[1m]))`；L2 同构 | 各 3 序列；L1：app-1 3.56/s、app-2/3 0.636/s；L2：3.56 / 0.636 / 0.655 | 有数据 |
| 3 | DB 回落占比 | `sum(rate(cache_db_fallbacks_total[1m])) / clamp_min(sum(rate(cache_l1_requests_total[1m])),1e-9)` | 1 序列，观测 **1.22（>100%）** | 有数据；口径坑见下 |
| 4 | DB 回落按原因 | `sum by (reason)(rate(cache_db_fallbacks_total[1m]))` | 4 序列：miss 4.11/s、penetration 0.0536/s、breakdown 0.782/s、avalanche 0.964/s | 有数据，`avalanche` 已并入 |
| 5 | 布隆结果 | `sum(rate(cache_bloom_requests_total{result="absent"}[1m]))` 等 | absent 0.358/s；hit 计数器 1（5 分钟 rate 0.0033/s）；error 0 | 有数据 |
| 6 | 空值缓存 | `cache_null_cache_requests_total{result=hit/miss}`、`writes`、`errors` 的 rate | hit 0.107/s、miss 0.0536/s、writes 0.0536/s、errors 0 | 有数据 |
| 7 | mutex | `cache_breakdown_mutex_total{result=...}` 的 rate | rebuild 0.687/s；双检命中计数器 46、timeout 6、stale 0、error 0 | 有数据；双检/超时已实测 |
| 8 | logical-expire | `cache_breakdown_logical_total{result=...}` 的 rate | cold 计数器 18、fresh 0、stale 0.0364/s | 有数据 |
| 15 | 互斥重建份额 | `rebuild / clamp_min(全部 mutex 事件)` | 1 序列 0.926 | 有数据 |
| 9 | logical 异步刷新 | `cache_breakdown_refresh_total{result=...}` 的 rate | submitted 20、success 20；deduped/failure/rejected 0 | 有数据 |
| 10 | 预热运行与每轮加载 | `sum by (trigger)(rate(cache_preheat_runs_total[1m]))*60`；`loaded/runs` | 3 序列；manual 1.02 次/分、loaded/run 1；periodic/startup 该窗口为 0 | 有数据 |
| 11 | 预热耗时（最大值） | **修正后** `max by (trigger)(cache_preheat_duration_seconds_max{...})` | 3 序列；manual 0.00646s、periodic 0.00533s、startup 0 | **原 P95 表达式为空，已修** |
| 12 | 热榜大小与发布 | `sum by (instance)(cache_preheat_hot_list_size)`；`rate(cache_hotkey_publish_total{result=...}[1m])` | 3 序列：8/8/110（窗口内本地 Top-N）；发布成功 0.218/s、失败 0 | 有数据 |
| 13 | HikariCP | `hikaricp_connections_active/pending/idle/max{...}` | 各 3 序列；active 0、pending 0、idle 5–7、max 10 | 有数据 |
| 14 | DB QPS 与回源 | `rate(mysql_global_status_questions{job="mysqld-exporter"}[1m])`；`sum(rate(cache_db_fallbacks_total[1m]))` | 各 1 序列；MySQL 15.9/s、缓存回源 5.9/s | 有数据 |
| 105 | TTL 抖动（雪崩） | `sum by (result)(rate(cache_avalanche_ttl_jitter_total[1m]))` | 3 序列；applied 0.0172/s（累计 40）、skipped/error 0 | 有数据 |
| 106 | L1 优先（雪崩） | `sum by (result)(rate(cache_avalanche_l1_total[1m]))` | 3 序列；cold-rebuild 0.0364/s（累计 20）、fresh/stale 0 | 有数据；fresh/stale 未触发见下 |
| 107 | L1 异步刷新（雪崩） | `sum by (result)(rate(cache_avalanche_l1_refresh_total[1m]))` | 5 序列，全部 0（计数器 0） | 序列存在但无事件，原因见下 |
| 108 | DB 兜底限流（雪崩） | `sum by (result)(rate(cache_avalanche_limiter_total[1m]))`；`sum(rate(cache_db_fallbacks_total{reason="avalanche"}[1m]))` | acquired 0.909/s、rejected 0.841/s；avalanche 回源 0.964/s | 有数据；限流已实测 429 快失败 |

## 仍然为 0 / 无法生成的数据

1. **面板 106 `fresh-serve` / `stale-serve`、面板 107 全部结果为 0**：`l1-cache` 的计数器只在 `@Cacheable` miss、进入 `ProductAvalancheDefense.withL1Cache` 时自增；而 L1 Caffeine `expire-after-write=60s` 与 L1 stale window `60s` 相同，L1 未过期时读请求直接命中缓存，不会进入 loader。用「删 L2 + 等 L1 过期」实测时两者同时到期，只能得到 `cold-rebuild`（累计 20），拿不到 fresh/stale；`cache_avalanche_l1_refresh_*` 因此也没有 submitted/success。要演示需把 `LAB_CACHE_L1_EXPIRE_AFTER_WRITE_SECONDS` 调到小于 refresh interval（10s）或做 L1 淘汰，属于配置/实现改动，本次未做。
2. **面板 8 `fresh-serve` 为 0**：logical TTL 30s 小于 L1 60s，命中缓存时同样不进 loader；`stale-serve` 已实测（删 L2 后直接读源实例，累计 20 并产生 20 次 refresh success）。
3. **面板 9 `deduped` / `failure` / `rejected` 为 0**：没有对同一 key 的并发刷新，也没有把 2 线程刷新池打满，属预期。
4. **面板 5 `error`、面板 7 `stale-serve/error`、面板 12 `failure`、面板 13 `active/pending` 为 0**：Redis 正常、锁正常、发布正常、无并发 DB 占用，属健康基线。
5. **`seckill-app-jdk8` 目标 down**（`Get http://seckill-app-jdk8:8080/actuator/prometheus: dial tcp ...`），`up=0`；不影响三实例面板，但 `job=~"seckill-app.*"` 会把它算进 `up` 类查询，D10 的缓存查询由于它无序列而不受影响。
6. **口径坑（不是指标名错误）**：一次「两级都 miss 且保护放行查库」会先由 `TwoLevelCache` 记 `reason="miss"`，再由 `ProductPenetrationDefense`/`ProductBreakdownDefense`/`ProductAvalancheDefense` 记 `penetration|breakdown|avalanche`。因此面板 3 对全部 reason 求和会重复计数（实测 1.22 > 100%），面板 4 堆叠也不是互斥分解。按 reason 逐条看、或把 `miss` 当「两级缓存未命中」而非「最终落库」更准确；本次只做真机核对，未改这两个面板的口径。

## 生成信号用的命令（token 只在容器内读取）

```bash
# 基线读 + seed（seed 只写 90000..90999，保留）
for r in 1 2 3; do for p in $(seq 1 20); do curl -s -o /dev/null http://127.0.0.1:8080/api/products/$p; done; done
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --post-data="" localhost:8080/lab/cache/seed'

# 切模式（app-1/2/3 各执行一次；mode=off|null-cache|bloom|mutex|logical-expire|ttl-jitter|l1-cache|db-fallback）
docker exec jl-app-N sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --header="Content-Type: application/json" --post-data="{\"mode\":\"ttl-jitter\"}" localhost:8080/lab/cache/mode'

# 手动预热
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --post-data="" localhost:8080/lab/cache/preheat'

# 并发打点（mutex 双检 / ttl-jitter 写 / db-fallback 限流）
seq 90400 90430 | xargs -P 20 -I{} curl -s -o /dev/null http://127.0.0.1:8080/api/products/{}
docker exec jl-app-1 sh -c 'for i in $(seq 1 30); do wget -qO- localhost:8080/api/products/91340 >/dev/null 2>&1 & done; wait'
docker exec jl-app-3 sh -c 'for i in $(seq 1 100); do wget -qO- localhost:8080/api/products/$((91000+i)) >/dev/null 2>&1 & done; wait'

# 删 L2 模拟过期（当前 master=jl-redis-2，键 cache:product:<id>；密码只在容器内展开）
docker exec jl-redis-2 sh -c 'redis-cli -a "$REDIS_HA_PASSWORD" --no-auth-warning DEL cache:product:90300'

# 逐条取数
curl -s --get http://127.0.0.1:8080/prom/api/v1/query --data-urlencode 'query=cache_l1_requests_total'
docker exec jl-app-1 sh -c 'wget -qO- localhost:8080/actuator/prometheus | grep -E "^cache_avalanche|^hikaricp_|^mysql_global_status_questions"'
```

## 恢复状态与遗留

- 三个实例 `GET /lab/cache/mode` 均为 `off`（已核对）；容器全部 running/healthy，Prometheus 三个 `seckill-app` 目标 `up=1`。
- 实验数据保留：MySQL 90000..90999 共 1000 行、Bloom（1022 个 id）、热榜 ZSet 与 L2 缓存键（部分键被本次删除/重写，5 分钟 TTL 会自然收敛，未手工清理）。
- 镜像：`java-learn-lab/seckill-app:jdk21` 已换成含 M4-2b 的 app.jar（旧镜像保留为 `:jdk21-pre-d10`）；未提交任何镜像/构建产物。
- 未做：Grafana 视觉截图（仍无浏览器渲染证据）、mode 指标/annotation（原结论不变）。
