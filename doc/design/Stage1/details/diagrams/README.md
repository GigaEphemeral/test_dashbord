# 跨专题图表集（Mermaid 源 + 渲染包装）

> 代码基线：`504fc74`（`git rev-parse --short HEAD`，本目录所有图统一基线）
> 范围：只画 `doc/design/Stage1/details/01–11` 没有覆盖的**跨专题**视角；每个专题的细节分支仍以对应文档为准。
> 形态：每个图一份纯 Mermaid `.mmd` 源文件（无围栏），加一份 `.md` 包装（内含 mermaid 围栏，GitHub/GitLab 可直接渲染）。
> 维护约定：先改 `.mmd`，再同步 `.md` 内的同一段源码；两者必须逐字一致，避免“源文件改了、渲染页没改”。

## 1. 图的边界与去重

- 这九张图是**一致性锚点**：它们把多篇专题文档共同依赖的事实画在一处，不重复专题图里的全部分支。
- 专题文档已有同主题图时，本目录只保留跨文档必需的那一层；两处冲突时以**已合并代码**为准，并回头修正专题文档。
- 本环境**没有 Mermaid 渲染器**：只做了图类型关键字、围栏配对、Tab 检查和保守语法审阅，**没有做视觉渲染验证**。

| 本目录图 | 专题文档中的同主题图 | 去重口径 |
|---|---|---|
| 01 部署拓扑 | `01-总体架构与运行拓扑.md` §2 | 本图补上 `chaos-proxy` 与 `traffic` 的 profile 分组、当前宿主端口和精确渲染计数；01 §2 仍是逐容器讲解版本 |
| 02 秒杀全链路 | `02-业务主链路.md` §5.1 | 本图把准入、MQ、消费者、补偿和同步降级放在一条时序里；02 §5 仍按下述分支逐段讲解 |
| 03 读写路由 | `05-MySQL高可用与读写路由.md` §3 | 本图补上 `MYSQL_MODE` 端点分支和 Router 6446/6447；05 §3 已同步为 `MYSQL_MODE`/默认 standalone 现状 |
| 04 缓存读路径 | `03-缓存体系.md` §3–§6 | 本图把 L1/L2、四类防护、运行时模式开关和指标落点合成一张；03 仍逐策略讲决策树 |
| 05 Sentinel 切换 | `04-Redis高可用.md` §3.1 | 本图只保留跨文档需要的时间线与客户端重连；04 仍保留完整演练叙述 |
| 06 MySQL 切换 | `05-MySQL高可用与读写路由.md` §4 | 本图补上活体切换、Router 重路由与 TTL 重加入；05 §4 只讲验收脚本 |
| 07 向导/动作状态机 | `06-故障注入与实验向导.md` §2–§3 | 本图把两个状态机放在同一张图并明确隔离；06 仍逐状态讲 API 语义 |
| 08 线程生命周期 | `07-线程实验室.md` §3.2、§4.1 | 本图把六态与故障生命周期并列，强调指标名不同；07 仍讲每个阶段的线程构造 |
| 09 压测与观测 | `09-压测与观测.md` §7 | 本图补上 D6/D7/D8/D10/D11 已合并、D5 由 Tempo 承担的面板现实；09 §7 的旧口径需要按本图核对 |

## 2. 索引

| # | 图文件 | 类型 | 展示内容 | 更新时必须对照的源码/配置 |
|---|---|---|---|---|
| 1 | [`01-deployment-topology.mmd`](01-deployment-topology.mmd) / [`.md`](01-deployment-topology.md) | `flowchart TB` | 宿主回环端口、`jl-net`/`jl-sentinel-net`、按 profile 分组的容器、Nginx 后三实例 | `code/lab/docker/compose.base.yml`、`compose.ha.yml`、`compose.mysql.yml`、`compose.toxiproxy.yml`、`code/lab/docker/nginx/conf.d/default.conf` |
| 2 | [`02-seckill-request-chain.mmd`](02-seckill-request-chain.mmd) / [`.md`](02-seckill-request-chain.md) | `sequenceDiagram` | 浏览器→Nginx→应用→Redis 锁/幂等/Lua→MQ→消费者→DB，含补偿与同步降级 | `SeckillAdmissionService.java`、`SeckillResilienceGuard.java`、`SeckillOrderConsumer.java`、`SeckillController.java` |
| 3 | [`03-read-write-routing.mmd`](03-read-write-routing.mmd) / [`.md`](03-read-write-routing.md) | `flowchart TB` | `LazyConnectionDataSourceProxy`→`AbstractRoutingDataSource`→读写池，`MYSQL_MODE` 与 6446/6447 | `DataSourceConfig.java`、`ReadWriteRoutingDataSource.java`、`DataSourceAccessProperties.java`、`application.yml`（`app.datasource.*`） |
| 4 | [`04-cache-read-path.mmd`](04-cache-read-path.mmd) / [`.md`](04-cache-read-path.md) | `flowchart TB` | L1→L2→穿透→击穿→雪崩→DB/限流，含运行时模式开关与指标 | `CachedProductViewService.java`、`TwoLevelCache.java`、`ProductAvalancheDefense.java`、`ProductBreakdownDefense.java`/`ProductPenetrationDefense.java` |
| 5 | [`05-redis-sentinel-failover.mmd`](05-redis-sentinel-failover.mmd) / [`.md`](05-redis-sentinel-failover.md) | `sequenceDiagram` | 应用→Sentinel 发现→主→kill→sdown/odown→switch-master→重连 | `RedisClientConfig.java`、`SentinelMasterDiscoveryReporter.java`、`code/lab/docker/compose.ha.yml`、`doc/process/Stage1/step-24d-sentinel-live-drill.md` |
| 6 | [`06-mysql-cluster-failover.mmd`](06-mysql-cluster-failover.mmd) / [`.md`](06-mysql-cluster-failover.md) | `sequenceDiagram` | Router 6446/6447、kill 主、组选举、Router 重路由、TTL 重加入 | `DataSourceAccessProperties.java`、`code/lab/docker/compose.mysql.yml`、`code/lab/docker/prometheus/prometheus.yml`、`doc/process/Stage1/step-31-mysql-router-live-drill.md` |
| 7 | [`07-chaos-guide-state-machines.mmd`](07-chaos-guide-state-machines.mmd) / [`.md`](07-chaos-guide-state-machines.md) | `stateDiagram-v2` | 向导 IDLE/RUNNING/PAUSED/COMPLETED/FAILED 与动作 APPLYING/ACTIVE/REVERTING/REVERTED/FAILED/COMPLETED 两台状态机 | `GuideService.java`、`GuideStatus.java`、`ChaosActionService.java`、`ChaosActionStatus.java` |
| 8 | [`08-thread-lab-lifecycle.mmd`](08-thread-lab-lifecycle.mmd) / [`.md`](08-thread-lab-lifecycle.md) | `stateDiagram-v2` | 单线程六态 NEW→…→TERMINATED 与故障 ACTIVE/PAUSED/STOPPED | `LifecycleDemoService.java`、`DemoState.java`、`ThreadFaultService.java`、`LifecycleState.java` |
| 9 | [`09-load-and-observability-flow.mmd`](09-load-and-observability-flow.mmd) / [`.md`](09-load-and-observability-flow.md) | `flowchart LR` | k6 remote write、Actuator 抓取、exporter、OTLP→Tempo、Grafana D1–D11 | `code/lab/k6/run.sh`、`code/lab/docker/prometheus/prometheus.yml`、`otel-collector/otel-collector.yaml`、`tempo/tempo.yaml` |

## 3. 一致性检查清单

以后改动下列任一代码路径，必须回来核对对应图；改图后再跑一次 §4 的结构检查。

### 图 1 · 部署拓扑

- [ ] `code/lab/docker/compose.base.yml`：服务清单、宿主端口、`networks: jl-net`、profile 列表；新增/删除服务会改变 16/18/25 计数。
- [ ] `code/lab/docker/compose.ha.yml`：9 个 `ha` 服务、`jl-sentinel-net` 固定 IP、三个 app 的 `REDIS_MODE`/网络合并。
- [ ] `code/lab/docker/compose.mysql.yml`：9 个 `db-ha`/`ha` 服务、Router 6446/6447、两个一次性 init job。
- [ ] `code/lab/docker/compose.toxiproxy.yml` + `code/lab/docker/nginx/conf.d/default.conf`：`chaos-proxy` 两个服务、三类代理端口、`upstream seckill_backend` 轮询参数。

### 图 2 · 秒杀全链路

- [ ] `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/admission/app/SeckillAdmissionService.java`：锁→幂等→Lua→状态→发布，以及发布失败回滚。
- [ ] `.../admission/app/SeckillResilienceGuard.java`：限流、熔断、同步降级与 `BusinessException` 忽略规则。
- [ ] `.../admission/app/SeckillOrderConsumer.java`：重复投递、失败补偿、`AmqpRejectAndDontRequeueException`。
- [ ] `.../admission/api/SeckillController.java` + `.../order/app/OrderApplicationService.java`：202/200 返回码与 DB 唯一键兜底。

### 图 3 · 读写路由与 MYSQL_MODE

- [ ] `.../datasource/DataSourceConfig.java`：Lazy 代理、两个 Hikari 池、启动期端点日志、`dbHealthContributor`。
- [ ] `.../datasource/ReadWriteRoutingDataSource.java`：`determineCurrentLookupKey()` 的 READ/WRITE 判定。
- [ ] `.../datasource/DataSourceAccessProperties.java`：`MYSQL_MODE` 校验、`DB_WRITE_URL`/`DB_READ_URL` 的 required 语义。
- [ ] `code/services/seckill-app-jdk21/src/main/resources/application.yml`（`app.datasource.*`）：默认 URL、池名、router 端点键。

### 图 4 · 缓存读路径与四类防护

- [ ] `.../catalog/app/CachedProductViewService.java` + `.../catalog/cache/TwoLevelCache.java`：`@Cacheable` 接缝、L1/L2 顺序、`cache_db_fallbacks{reason=miss}`。
- [ ] `.../catalog/cache/ProductAvalancheDefense.java`：`ttl-jitter`/`l1-cache`/`db-fallback` 与 limiter 预算。
- [ ] `.../catalog/cache/ProductBreakdownDefense.java`：`mutex` 双重检查、`logical-expire` 单飞刷新与 publish-through。
- [ ] `.../catalog/cache/ProductPenetrationDefense.java` + `.../lab/toggle/CacheDefenseModeService.java` + `.../lab/api/LabCacheController.java`：空值/布隆、模式枚举与 `POST /lab/cache/mode`。

### 图 5 · Redis Sentinel 切换

- [ ] `.../redis/RedisClientConfig.java`：Lettuce/Redisson 的 sentinel 配置同开关、`REDIS_MODE` 校验。
- [ ] `.../redis/SentinelMasterDiscoveryReporter.java`：按 master 名发现、日志证据字段。
- [ ] `code/lab/docker/compose.ha.yml`：`mymaster`、3 Sentinel、`down-after`/quorum、固定 IP。
- [ ] `doc/process/Stage1/step-24d-sentinel-live-drill.md`（及 `step-38-ha-acceptance.md` 的折算）：7.6s/9.1s 的时间戳来源。

### 图 6 · MySQL InnoDB Cluster 切换

- [ ] `.../datasource/DataSourceAccessProperties.java`：router 模式写 6446/读 6447 的绑定。
- [ ] `code/lab/docker/compose.mysql.yml`：单主 Group Replication、Router、两个 init job、exporter 标签。
- [ ] `code/lab/docker/prometheus/prometheus.yml`：`mysqld-exporter-ha` 已内联（不再是惰性文件），D11 的 `mysql_up` 依赖它。
- [ ] `doc/process/Stage1/step-31-mysql-router-live-drill.md`：kill、重路由、TTL 回滚与重加入的实测记录。

### 图 7 · 故障注入与向导状态机

- [ ] `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/guide/GuideService.java` + `GuideStatus.java`：允许的 start/pause/resume/reset 状态。
- [ ] `.../action/ChaosActionService.java`：`APPLYING` 闸门、TTL、watchdog、stop-all、FAILED 重试。
- [ ] `.../action/ChaosActionStatus.java`：动作枚举（没有 PAUSED）。
- [ ] `code/services/chaos-controller/src/main/resources/application.yml`（`chaos.*`）：TTL 上限、watchdog 周期、`reset-mode`。

### 图 8 · 线程实验室生命周期

- [ ] `.../lab/thread/LifecycleDemoService.java`：六阶段推进、freeze/resume/reset、真实 `Thread.getState()`。
- [ ] `.../lab/thread/DemoState.java`：0–5 编码与 `thread_lab_demo_state_code`。
- [ ] `.../lab/thread/ThreadFaultService.java` + `FaultAction.java`：注入、TTL、pause/resume、teardown。
- [ ] `.../lab/thread/LifecycleState.java`：0/1/2 编码与 `thread_lab_lifecycle_state_code`。

### 图 9 · 压测与观测数据流

- [ ] `code/lab/k6/run.sh`：镜像、`RUN_ID`、`K6_PROMETHEUS_RW_*`、`-o experimental-prometheus-rw`。
- [ ] `code/lab/docker/prometheus/prometheus.yml`：scrape job、relabel、remote-write receiver 依赖的 compose 参数。
- [ ] `code/lab/docker/otel-collector/otel-collector.yaml` + `tempo/tempo.yaml`：OTLP 管线与 metrics_generator 回写。
- [ ] `code/services/seckill-app-jdk21/src/main/resources/application.yml`（`management.*`）+ `code/lab/docker/grafana/dashboards/`：指标导出名与 D1–D11 实际文件。

## 4. 结构检查（本环境可执行的部分）

```text
图类型关键字：flowchart / sequenceDiagram / stateDiagram-v2，均为标准类型
围栏：每个 .md 恰好 1 个 mermaid 围栏开头 + 1 个空围栏结尾
Tab：.mmd / .md 内无 Tab
编码：UTF-8，无 BOM
未验证：Mermaid 视觉渲染、GitHub/GitLab 实际渲染、跨浏览器主题
```

## 5. 已知待补 / 文档偏差

| 项 | 状态 | 说明 |
|---|---|---|
| D5 链路 | 待补 | 没有 `ha-d5.json`；Tempo service graph / trace 查询可替代，但不算独立面板 |
| D7 JDK 对比 | 已落地 | `ha-d7.json` 已入库（step-41）；08 文档的 D7 流程也已同步为已接线 |
| D8 调优实验 | 已落地 | `ha-d8.json` 已入库（step-51）；08 文档 §10.3 的控制台入口仍未做 |
| MySQL Router 重路由精确时刻 | 待补 | step-31 只有 5s 采样；图上标“待补”，不编造秒数 |
| MySQL 旧主重加入完成时刻 | 待补 | step-31 只记录 TTL start 与之后 21:30:30 的写成功 |
| D11 帮助文案 | 已对齐 | 面板文案已改为 `prometheus.yml` 内联 `mysqld-exporter-ha`（本批修订） |
| 01 §8 / 09 §14 旧口径 | 已对齐 | 本批已把 Toxiproxy、D6/D7/D10/D11 等旧口径改为已实现；本表其余项仍按代码维护 |
