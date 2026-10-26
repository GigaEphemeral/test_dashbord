> 步骤交接 · D10 缓存实验室 Grafana 面板（设计 §7.4）
> 日期：2026-10-28
> 分支 / HEAD：worktree `agent-general-purpose-472cfdf2`，基线 `a37abfff`（`fix: stagger the deadlock workers ...`）
> 关联任务：M4 缓存实验室观测层（消费 M4-1 两级缓存/穿透、M4-2a 击穿、M4-3 预热已落地的指标；不改任何 Java/compose/脚本/Vue）
> 验证：`python -c "json.load(...)"` 与 `node -e "require(...)"` 均通过；未执行 Docker/compose，未启动 Grafana，**无法做视觉验证**，PromQL 也未对活栈执行。

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
