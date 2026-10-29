# 步骤交接：Stage2 B3 缓存实验批次（B3-01 ~ B3-04）

> 任务/步骤：Stage2 **B3-01 独立 Caffeine L1 开关**、**B3-02 10,000 商品基线 + SCAN 键查询/单键/重置**、**B3-03 A/B 公平对照**、**B3-04 业务对账**
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `91a07f5`（B3-00 已推送；本批改动**尚未提交**）
> 关联任务：B3-00 交互对齐（`step-S2-B3-00a-interaction-alignment-implementation.md`）、计划 v1.6、需求 §十一（#3/#9/#10）、方案 §5/§10.9
> 测试 / verify：reactor **1337 tests / 0 fail**（chaos 756、app 495、jdk8 59、core 13、platform 14）；console-smoke **138 PASS / 0 FAIL**；k6 node **113 pass**；doc claims **52/52 PASS**；verify **109 PASS / 1 WARN / 0 FAIL**；smoke **7/7 SMOKE PASSED**
> 运行状态：dev 栈运行中，app/controller 均已用新 jar 重建（app 镜像 `0d47f7d4…`、controller `1274291d…`）；Flyway 4 已应用；A/B 证据 run-20261029-003/004、对账证据 run-20261029-001 保留
> 口径指针：2026-10-29 本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次可能已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- B3-01/02/03/04 全部实现并通过真机验证：L1 每实例独立开关与清空、10,000 条确定性商品、SCAN 键分页/单键详情/实验重置、A/B 对照面板、run 级业务对账。
- 闸门：console-smoke **138/0**（B3-00 132 +6）；claims **52**（+5）；verify **109/1**（与基线一致）；smoke **7/7**；k6 node **113**；reactor 全量见附录。
- 真机证据：L1 关闭后 app-1 `l1Present=false / l2Present=true` 且 app-2 仍启用；seed 10,000 经代理约 7s；SCAN 首屏 3 条、游标可续；reset 删除 22 个缓存键；A/B 一对完成（9 行差值，清空 21/23/21 条）；对账 0 流量 PASS、smoke 4 单后 `ordersDelta=4 / stockDecrement=4` 仍 PASS。
- 独立 Qwen 审核（隔离 worktree）：**Approved with fixes**，0 Critical / 0 High / 1 Medium（已修）/ 7 Minor；
  Medium 是 `GET /lab/cache/keys` 在页容量小于 SCAN 批次时返回批后游标会永久跳过超限键，修复提交
  `ef06b9a`（已 cherry-pick 为 `4e925ae`，SCAN hint 随剩余容量收缩 + 新增序列测试）。
- 独立 Qwen 审核（隔离 worktree）：**Approved with fixes**，0 Critical / 0 High / 1 Medium（已修）/ 7 Minor；
  Medium 的修复与证据见文末 Fix Report（原提交 `ef06b9a`，主分支 cherry-pick 为 `4e925ae`）。
- 未完成：B4/B5。

# 完成功能项

## B3-01 独立 Caffeine L1 开关

- `TwoLevelCache`：每实例 `AtomicBoolean`；关闭即清空本地条目并记录条数，get/put/evict 绕过 L1，L1 计数器停止增长，L2/Redis 语义不变；新增 `cache_l1_enabled{cache}` gauge（0/1）。
- `TwoLevelCacheManager`：把开关扇出到所有托管缓存；关闭期间新建的缓存也以关闭状态启动。
- 新 `LabCacheL1Controller`：`GET /lab/cache/l1`、`POST /lab/cache/l1 {"enabled":bool}`，返回 `enabled/cacheCount/caches/clearedEntries/instance`；同值设置幂等；`/lab/cache/state` 增加 `l1Enabled`。
- `CacheLab.vue`：开关 `cache-l1-toggle`、状态 `cache-l1-state`，关闭时命中率显示“不适用”，中文说明“每实例独立、L2 不受影响”。
- 测试：`TwoLevelCacheL1SwitchTest`、`TwoLevelCacheManagerL1SwitchTest`、`LabCacheL1ControllerTest`。

## B3-02 10,000 商品基线 + 键查询 + 重置

- Flyway **V4**：ids 21..10000 用递归 CTE 确定性生成（`实验商品-<id>`，99.00，ON_SALE）；全部 10,000 行库存统一为 `MOD(id*37,501)`（0–500；商品 1=37、20=239、10000=262）、`reserved=0`；`cte_max_recursion_depth` 会话内提升。
- `reset-lab.sh` 与 `POST /lab/cache/reset` 共用同一库存公式；`RESET_BASELINE_STOCK` 保留为显式平铺覆盖；`test-reset-topology.sh` mock 同步。
- `POST /lab/cache/seed` 支持 `{"count":N}`（1..`app.lab.cache.seed.max-request-count` 默认 10000）；10,000 行用分块多行 INSERT（约 7s，代理 30s 预算内）；`synchronized` 防同 JVM 并发重入；阈值/雪崩/热键按请求数裁剪。
- `GET /lab/cache/keys?prefix=&cursor=&limit=`：SCAN 游标分页、前缀白名单、`limit` 上限 1000、有界批量补齐（默认 20 批，`scan-batches`）。
- `POST /lab/cache/key`：key 或 productId 二选一，返回 L1/L2 presence、TTL、按字节截断的值与 `truncated`；L1 探测不计数。
- `POST /lab/cache/reset`：默认只删实验缓存键（SCAN 前缀、上限 50000）；`includeBusiness=true` 才 TRUNCATE `order_order` 并恢复库存公式。
- 配置外置：`LAB_CACHE_SEED_MAX_REQUEST_COUNT`、`LAB_CACHE_KEYS_SCAN_BATCHES` 进 yml/compose/.env.example；代理超时默认 10s→30s（`CHAOS_APP_PROXY_REQUEST_TIMEOUT_SECONDS`），else 10k seed 会超时。
- 控制器代理白名单：`GET /lab/cache/l1|keys`、`POST /lab/cache/l1|key|reset|seed`，`seed/l1/key` 转发 body。
- `CacheKeysPanel.vue` 挂在“实时监控与操作 → 实验工具”：实例选择、seed 数量、前缀/游标/limit、键表、单键详情、重置（含业务勾选与二次确认）。
- verify 断言基线 10000 条与 Flyway `1,2,3,4`；k6 README/采样注释同步。

## B3-03 A/B 公平对照

- `CacheABPanel.vue`（Benchmark 页）：A 打开 L1 并逐实例预热 → 创建/预检查/启动/等待/收盘；B 关闭 L1（显示每实例清空条数）→ 同一流程；结束后自动恢复 L1。
- 同数据集、同 `LOAD_DURATION/VUS/START_VUS`、同商品范围与实例集合、同 JVM 参数（不传 ramp 分片，使用 manifest 默认）；差值表 9 项取自 B1 聚合层，缺数据显式“无数据”。
- 真机一对：run-20261029-003（A）与 004（B）均 COMPLETED，清空 21/23/21 条，差值表 9 行。

## B3-04 业务对账

- 控制器 `reconcile` 包：只读业务库 Hikari（`seckill`，`initialization-fail-timeout=-1`，语句超时外置）、RabbitMQ 管理 API 队列检查、`ReconciliationEvaluator`、`ReconciliationStore`（`lab_run_check`）。
- 7 项检查：`orders.delta`、`stock.delta`、`stock.nonnegative`、`orders.duplicates`、`idempotency.duplicates`、`oversold.counter`（Prometheus）、`mq.drained`；全部 PASS 才 PASS，任一未接线/不可达为 DEGRADED，业务不变量破坏为 FAIL。
- run 启动后（READY→RUNNING）抓基线，best-effort（失败只降级对账，不影响 run）；`POST /lab/runs/{id}/reconcile` 现算并持久化，`GET /lab/runs/{id}/reconciliation` 返回最近结果（未跑过为 404，UI 显示无数据）。
- `RunReconciliation.vue` 挂在实验记录详情（对账按钮、verdict 徽标、7 行证据、DEGRADED 说明）。
- 真机：run-20261029-001 零流量 PASS；smoke 新增 4 单后 `ordersDelta=4, quantityDelta=4, stockDecrement=4` 仍 PASS。

# 设计逻辑

- **L1 关闭 = 绕过 + 清空 + 不计 L1 计数**：把“关闭”与“0% 命中”区分开；L2 仍是共享事实源，因此 B3-03 的 B 组是“无 L1 的公平读”。
- **商品基线确定性**：库存公式同时出现在 Flyway、reset-lab、app reset 三处，任何一次重置都回到同一数值；10,000 条使商品采样/穿透/正态热度有真实数据。
- **SCAN 分批有界**：Redis SCAN 可能空批返回非零游标；页面在有限批次内补齐，既不让首屏空，也不做无限扫描；前缀白名单防止变成 keyspace dump。
- **seed 幂等与预算**：同 JVM `synchronized` + 缺失集合；多行 INSERT 把 10k 插入压到约 7s；代理超时上调到 30s 只影响单次转发的预算。
- **A/B 固定模板**：避免“实验类型/流量模型”混用；A 预热、B 清空、其余变量不变，差值直接来自聚合层，缺数据不按 0。
- **对账与性能分离**：对账只看业务不变量，k6 阈值单独判定；未接线一律 DEGRADED，避免“全 0 假 PASS”。
- **安全边界**：对账只读业务库、不执行写 SQL；reset 的业务清空是显式 `includeBusiness=true` + UI 二次确认；键浏览器只允许白名单前缀。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| seed 跨实例并发 | `synchronized` 只在单个 JVM 内生效；三实例同时 seed 同一范围仍可能撞唯一键（同一会话内顺序 seed 正常） | 记录 |
| A/B 无批量编排 | 面板一次一对，未做 N 次重复；中位数/离散度在 B5-03 | B5-03 |
| 键浏览默认只开放 `cache:product:` | 其他前缀需改 `allowed-prefixes`；防止 keyspace dump 的有意限制 | 设计口径 |
| 对账 sample 落库截断 | `lab_run_check` 无 sample 列，持久化时按 1024 字符截断；实时响应保留完整有界样本 | 记录 |
| 对账覆盖 7 项 | 未包含业务金额/用户维度对账（本实验室无支付） | 设计口径 |
| B3 审核 Minor | 7 条记录：reset 删除全部 `cache:product:*`（可再生成）、Java 默认值与“无默认值”注释不一致、`armed` map 自然完成后不回收（任务自跳过）、跨 JVM seed 竞争、30s 代理预算、via-nginx 与 includeBusiness 为已确认设计、前端 PromQL 直连 /prom | 记录 |
| console-smoke 收尾 404 | 套件自己删除的 run 仍可能有最后一次详情轮询 404；已在套件内按 deletedRunIds 过滤，并清理上次中断留下的 localStorage 选中项 | 记录 |
| 控制器代理超时上调 | 10s→30s 是全局单次转发预算，慢调用会占住一个代理线程 | 记录 |

# 待定事项和注意事项

1. **下一步**：B3 收口文档与提交/推送已完成（独立审核见 Fix Report），然后 B4 实例 JVM/GC/线程池配置闭环。
2. **重建与重载**：宿主 `mvn -o -pl services/seckill-app-jdk21,services/chaos-controller -am -DskipTests package`，用 `tmp/b3-image/` 的运行时临时 Dockerfile 打同名 tag，`up -d --no-deps --force-recreate` 后**必须 `docker exec jl-nginx nginx -s reload`**（Nginx 在启动时解析 upstream，不重载会 502）。
3. **数据状态**：baseline 10,000 + 实验 seed 1,000（90000–90999）；A/B 证据 run 003/004、对账证据 run 001 保留；无 RUNNING run、无 k6 容器、traffic 停止。
4. **Windows 编码**：Git Bash 直接 `-d '{"name":"中文"}'` 会按 GBK 发送导致 400；用 UTF-8 文件 + `--data-binary`。
5. **测试耗时**：app 模块 IT 含 Testcontainers，全量 reactor 约 10–15 分钟；单独 app 测试建议带 `-am`。

# 附录

## 闸门与命令

```bash
cd code && mvn -o test                                   # 1337 tests / 0 fail
node --test "code/lab/k6/test/*.test.mjs"                # 113 pass
bash code/lab/scripts/verify.sh                          # 109 PASS / 1 WARN / 0 FAIL
bash code/lab/scripts/smoke.sh                           # 7/7 SMOKE PASSED
bash code/lab/scripts/console-smoke.sh --no-build        # 138 PASS / 0 FAIL
bash code/lab/scripts/check-doc-claims.sh                # 52/52 PASS
```

## 真机证据（2026-10-29）

- V4：`SELECT COUNT(*) FROM catalog_product` = 11,000（10,000 基线 + 1,000 实验 seed）；`inventory_stock` 10,000+ 行；商品 1/20/10000 库存 37/239/262；公式不匹配行 0。
- L1：GET app-1/app-2 均 `enabled=true`；POST app-1 `{"enabled":false}` → `clearedEntries=1`；app-2 仍 true；键详情 `l1Present=false, l2Present=true, ttl=261`。
- 键：`seed {"count":20}` → range 90000–90019、avalancheKeys 20、`hotKeyProductId=-1`；`keys?limit=3` → 3 条、cursor 可续；`key {productId:1}` → 值/ttl=299/L1+L2 均 true；非法前缀 400。
- 重置：`reset {"includeBusiness":false}` → deletedKeys 22，Redis 前缀计数 0；随后默认 seed 恢复 1,000。
- A/B：run-20261029-003/004 COMPLETED，差值表 9 行；L1 清空 21/23/21。
- 对账：run-20261029-001 7 项 PASS；smoke 后 `ordersDelta=4, stockDecrement=4` 仍 PASS。

## 关键文件

- app：`catalog/cache/{TwoLevelCache,TwoLevelCacheManager}.java`、`lab/cache/{L1Switch,CacheExperimentDataService}.java`、`lab/api/{LabCacheL1Controller,LabCacheKeysController,LabCacheController}.java`、`db/migration/V4__catalog_baseline_10000.sql`、`lab/config/LabCacheProperties.java`
- controller：`reconcile/*`、`ledger/{RunLifecycleService,RunStartedHook}.java`、`appproxy/AppProxyService.java`、`application.yml`
- 前端：`CacheLab.vue`、`CacheKeysPanel.vue`、`CacheABPanel.vue`、`RunReconciliation.vue`、`BenchmarkPanel.vue`、`MonitorPage.vue`、`RunLedger.vue`、`api.js`
- 脚本：`reset-lab.sh`、`verify.sh`、`smoke.sh`、`console-smoke.{sh,mjs}`、`test-reset-topology.sh`
- 配置：`compose.base.yml`、`.env.example`（cache seed/keys、reconcile、proxy timeout）

# Fix Report（B3 独立审核修复，2026-10-29）

- 修复 finding（Medium）：`GET /lab/cache/keys` 游标分页在 SCAN 单批返回键数超过页内剩余容量时，
  该批未展示的键会被游标永久跳过（生产默认 scan-count 500 > limit 100，翻页可整段漏键）。
  `CacheExperimentDataService.scanKeys` 现将每批 Redis COUNT 提示钳制为
  `min(keys.scan-count, limit - 已取条数)`，并如实记录残余口径：COUNT 只是提示，Redis 仍可能超发，
  超发键计入 `scanned` 但不列出；需要完整枚举时加大 `limit`（或 reset+重播种）。
- 顺带清理（Minor）：`batchInsert` javadoc 移除与实现不符的 "INSERT IGNORE" 描述（实际是缺失集合预过滤 +
  同 JVM `synchronized`，跨实例并发仍为已记录限制）；删除无调用方的 `sumCounts` 死代码。
- 测试：新增 `CacheExperimentKeysTest.scanKeysClampsTheScanHintToTheRemainingPageCapacity`
  （断言 hint 序列 4/3/1，页满即停）。
- 命令与输出（worktree，JDK 21）：
  - `mvn -o -pl services/seckill-app-jdk21 -am test -Dtest='CacheExperimentKeysTest,TwoLevelCacheL1SwitchTest,TwoLevelCacheManagerL1SwitchTest,LabCacheL1ControllerTest,LabCacheControllerTest,LabCachePropertiesTest'`
    → 43 tests / 0 fail（Keys 12、L1 缓存 4、Manager 2、L1 控制器 4、LabCache 10、Properties 11）
  - `mvn -o -pl services/seckill-app-jdk21 -am test -Dtest='ProductRepositoryIT,InventoryRepositoryIT'`（Testcontainers）
    → 14 tests / 0 fail（V4 幂等重放、10,000 基线与公式断言通过）
  - `mvn -o -pl services/chaos-controller -am test -Dtest='FaultSchedule*,RunLifecycleFaultScheduleTest,RunLedgerServiceFaultScheduleTest,ChaosActionServiceTest,ChaosActionStateMachineTest,EnvironmentStopAllServiceTest,Reconciliation*,ReconcilePropertiesTest,RunPrecheckServiceTest,BenchLaunchPlannerTest,AppProxyServiceTest,LedgerControllerTest,LedgerRetentionTest'`
    → 259 tests / 0 fail
  - `node --test "code/lab/k6/test/*.test.mjs"` → 113 pass / 0 fail
- 自检：修复仅触及 `scanKeys` 的 hint 计算与两处注释/死代码；分页响应契约（游标透传、limit 硬上限、
  前缀白名单、有界批次）不变；未运行 verify/console-smoke（worktree 规则禁止），真机闸门由控制器合并后复跑。
