> 步骤交接 · M1-7 业务指标、D4 与全栈 smoke
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / `feat: add business metrics and D4 smoke`（父提交 `c33bcb4`），随后 `docs: record Task 18 metrics and smoke`
> 关联任务：#18（M1-7）
> 测试 / verify：reactor **163 tests, 0 failures**（app 122）；`smoke.sh` **7/7 PASS**；`verify.sh` **96 PASS / 1 WARN**
> 运行状态：16 个 `lab=java-learn` 容器运行中，三实例健康；product 1 DB 81、product 20 DB/Redis 均 87、订单 32；`idempotent_reject_total` 累计 3

# 当前进展

- Task 18 完成：补齐最后一个业务指标 `idempotent_reject_total`，为 `order_accept_seconds` 开启 percentile histogram 与 SLO 桶，新增 D4「业务与高可用」面板，提供可配置的全栈 `smoke.sh`，并扩展 `verify.sh`。
- 未改动 Task 17 的 admission/lab/resilience 行为；未接触独立 review worktree/branch，未合并。
- 测试：reactor **163 tests, 0 failures**；app 122（与 Task 17 持平，新增断言不加测试方法）、core 13、chaos 14、common 10、observability 4。`OrderApplicationServiceIT` 现在断言顺序重放 +1、并发同 key 9 次重放各计数一次且仍只落一单。
- `smoke.sh` 端到端 **7/7 PASS**：login → product → 订单创建/重放/查询 → 秒杀 202/轮询 CREATED。
- 应用镜像已重建（`java-learn-lab/seckill-app:jdk21`，`5b8b280b74d9`），三实例 force-recreate 后健康；Prometheus 已重启加载 RabbitMQ detailed queue 抓取任务；Grafana 已热加载 D4。
- `verify.sh` **96 PASS / 1 WARN**（原 90 PASS + 6 项新增；WARN 仍是 cAdvisor/WSL2 限制）。栈保持运行。
- 下一步：M2-1（k6 基础场景与 remote write）。

# 完成功能项

## 1. 业务指标 `idempotent_reject_total`

- 新增 `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/order/app/OrderMetrics.java`：集中注册 `orders_created_total` 与 `idempotent_reject`，两个 counter 启动即存在 0 值序列；无 userId/orderId/requestId 等高基数标签。
- `OrderTransactionService`：事务内预检命中既有订单（顺序重放）时，在 **afterCommit** 记录一次 `idempotent_reject`；真正插入仍只记 `orders_created_total`。
- `OrderApplicationService`：并发同 key 输家抛 `DuplicateOrderKeyException` 后，重读到胜者订单时记录一次 `idempotent_reject`；与预检路径互斥，不会双计。
- `OrderConfig` 新增 `OrderMetrics` bean 并注入两个服务；`Orders_created_total` 的 meter 名与 Prometheus relabel 规则保持不变。
- 未改变秒杀准入重放路径：D4 的秒杀重放仍读 `seckill_attempts{result="replay"}`；共享的持久订单层若遇到 MQ 重复投递，也会记 `idempotent_reject`（见缺陷项）。
- 证据：`GET /actuator/prometheus` 暴露 `idempotent_reject_total{app="seckill-app",...} 0.0`；smoke 重放后 Prometheus `sum(idempotent_reject_total{job="seckill-app"})` = 1，两次 verify 后 = 3。

## 2. `order_accept_seconds` 直方图与 SLO

- `application.yml` 新增：
  - `management.metrics.distribution.percentiles-histogram.order_accept_seconds=true`
  - `management.metrics.distribution.slo.order_accept_seconds=${ORDER_ACCEPT_SLO:50ms,100ms,300ms,500ms,1s,2s}`
- `compose.base.yml` 的 app 环境注入 `ORDER_ACCEPT_SLO`；`.env.example` / 本地 `.env` 提供默认值。
- 未改 Timer 代码；`order_accept_seconds_bucket` 现按实例导出，含默认 Micrometer 桶与 6 个 SLO 边界。首次重启后样本 P95≈0.98s（Redisson 懒初始化），随后恢复正常；首样本 `order_accept_seconds_sum=0.947996811s`，`count=1`。
- Prometheus 查询证据：`count(order_accept_seconds_bucket{job="seckill-app"})` = 225（3 实例 × 75 桶）。

## 3. D4 面板 `ha-d4.json`

- 文件：`code/lab/docker/grafana/dashboards/ha-d4.json`；UID `ha-d4`，标题「D4 业务与高可用」，tags `ha-lab,d4,business,resilience`，refresh `5s`，时间 `now-15m`，datasource `prometheus`。
- 模板变量 `instance`：`label_values(up{job="seckill-app"}, instance)`，includeAll + multi + allValue `.*`；所有应用面板按 `instance=~"$instance"` 过滤；RabbitMQ 面板不按应用实例过滤。
- 11 个面板：
  1. 订单创建速率 `sum(rate(orders_created_total[1m]))`
  2. 秒杀尝试（按 result）`sum by (result)(rate(seckill_attempts_total[1m]))`
  3. 幂等拒绝 `sum(rate(idempotent_reject_total[1m]))`
  4. 超卖计数 `stock_oversold_total`（stat，0=未超卖，≥1 红）
  5. 下单受理延迟 P95/P99 `histogram_quantile(0.95/0.99, sum by (le)(rate(order_accept_seconds_bucket[1m])))`
  6. 消费者结果 `sum by (result)(rate(order_consumer_processed_total[1m]))`
  7. MQ backlog：`rabbitmq_queue_messages_ready{queue="seckill.order.queue"}`、`rabbitmq_queue_messages_unacked{queue=...}`、聚合 `rate(rabbitmq_queue_messages_delivered_total[1m])`
  8. 熔断器状态 `resilience4j_circuitbreaker_state{name="seckillBackend"} == 1`（stat，显示实例 + state 标签）
  9. 限流剩余许可 `resilience4j_ratelimiter_available_permissions{name="seckill"}`
  10. 缓存命中率 L1/L2：`hit / clamp_min(all, 1e-9)`
  11. Hikari pending/active（按 read/write pool）
- RabbitMQ 默认 `/metrics` 是聚合指标、没有 `queue` 标签。`prometheus.yml` 新增 `rabbitmq-queue` job：`/metrics/detailed?family=queue_coarse_metrics`，并用 metric relabel 把 `rabbitmq_detailed_queue_*` 改名为 `rabbitmq_queue_*`；原 `rabbitmq` job 保留聚合 `delivered_total`。
- 验证：`GET /api/dashboards/uid/ha-d4` = **200**；11 panels、变量、tags、刷新/时间窗均与预期一致；16 条 PromQL（含 P95/P99 与实例变量替换）对 Prometheus 全部 `status=success`。P95/P99 在最近 1m 无样本时返回 `NaN`，属“面板可显示 No data”的允许范围；有 smoke 流量时观察到有效值。

## 4. 全栈 smoke `code/lab/scripts/smoke.sh`

- 严格模式 `set -euo pipefail`、LF、基于 `BASH_SOURCE` 的路径解析；`dotenv_value` 只读取 `.env` 的键值，不 source，避免凭据进入进程环境或输出。
- 配置（环境变量 > `.env` > 默认）：
  - `SMOKE_BASE_URL`（默认 `http://127.0.0.1:8080`）
  - `SMOKE_LAB_TOKEN` / `LAB_TOKEN`（默认 lab token；当前业务步骤不需要，保留给 lab API 扩展）
  - `SMOKE_PRODUCT_ID`（1）、`SMOKE_SECKILL_PRODUCT_ID`（20）
  - `SMOKE_USER`、`SMOKE_TIMEOUT_SECONDS`、`SMOKE_POLL_RETRIES`、`SMOKE_POLL_INTERVAL_SECONDS`
  - 额外可选 `SMOKE_ENV_FILE`
- 7 个检查：`POST /api/login` 拿 token → `GET /api/products/{id}` → `POST /api/orders` 唯一 key → 同 key 重放同 orderId → `GET /api/orders/{id}` → `POST /api/seckill/orders` 202/requestId → 轮询 `GET /api/seckill/orders/{requestId}` 至 CREATED 且有 orderId。
- 每个步骤 PASS/FAIL；失败时打印 HTTP 状态与最后响应（截断 500 字符），最终非零退出；不直接访问 MySQL/Redis/RabbitMQ，无 jq 依赖。
- 实跑输出：7 passed, 0 failed；订单 27 → 重放同 id；秒杀 requestId `aa9795f7-...` → orderId 28。
- smoke 每次消耗 product 1 与 product 20 各 1 件；未加入 `verify.sh`，按 brief 单独执行并记录。

## 5. `verify.sh` 扩展

- 新增/增强检查（旧检查全部保留）：
  - `ha-d4` dashboard API 200；
  - app-1 Actuator 暴露 `order_accept_seconds_bucket` 与 `idempotent_reject_total`；
  - Prometheus `idempotent_reject_total{job="seckill-app"} > 0`（顺序重放后）；
  - Prometheus 存在 `order_accept_seconds_bucket`、`order_consumer_processed_total`、`rabbitmq_queue_messages_ready`；
  - Prometheus 存在带 `queue="seckill.order.queue"` 标签的 `rabbitmq_queue_messages_ready`；
  - 原有 `resilience4j_circuitbreaker_state` / `resilience4j_ratelimiter_available_permissions` 检查保留。
- 新增等待窗口外置：`METRIC_WAIT_RETRIES`（30）、`METRIC_WAIT_INTERVAL_SECONDS`（2），并让 `check_prometheus_positive` 复用。
- 最终结果 **96 PASS / 1 WARN**；WARN 仍为 cAdvisor 在 Docker Desktop/WSL2 无 per-container 序列。
- 未把 smoke 放进 verify，避免每轮重复消耗库存/订单。

## 6. 文档与配置

- 本文件；更新 `doc/process/阶段交接-2026-10-26.md`。
- `.env.example` 新增 `ORDER_ACCEPT_SLO` 与 7 个 `SMOKE_*` 键；本地 `.env` 同步（不提交）。
- 未新增依赖；未改 admission/lab/resilience 业务代码。

# 设计逻辑

- **单一计数所有者**：`OrderMetrics` 统一注册两个 counter，避免在两个类里重复 `Counter.builder` 造成描述/注册顺序分叉；`OrderTransactionService` 与 `OrderApplicationService` 共享同一实例。
- **两种重放形态、各计一次**：顺序重放在事务内预检命中，走 afterCommit 记录；并发同 key 由数据库唯一键裁决，输家事务回滚后在非事务包装层重读胜者并记录。预检与核心读取在同一 REPEATABLE READ 快照内，因此不会出现“预检未命中但内部命中”的漏计/双计窗口。
- **afterCommit 语义**：只有真正提交的创建/重放才计数；事务回滚不会留下虚假业务指标。`orders_created_total` 与 `idempotent_reject` 互补，重放不再被误记为新建。
- **不改秒杀重放语义**：`seckill_attempts{result="replay"}` 仍是准入层（Redis 幂等键）重放的权威指标；`idempotent_reject` 是持久订单层的幂等信号，MQ 重复投递也会记录，便于观察“消息层重复但 DB 只落一单”。
- **直方图用配置而非代码**：`order_accept_seconds` 的 Timer 已存在，只用 `management.metrics.distribution.*` 打开 percentile histogram 与 SLO 桶，避免在生产代码里硬编码桶边界；`ORDER_ACCEPT_SLO` 可在 `.env` 调整。
- **RabbitMQ 队列标签**：插件默认端点聚合所有队列，D4 的 backlog 必须绑定 detailed endpoint。单独新增 job 而不是替换原 job：保留聚合 `delivered_total` 等计数，同时用 relabel 得到文档化的 `rabbitmq_queue_*{queue=...}` 名称。两套序列标签集不同，不冲突。
- **D4 变量作用域**：`instance` 变量只过滤应用面板；RabbitMQ/exporter 指标没有应用实例标签，强行过滤会全空，因此在 MQ 面板不引用变量。
- **状态映射**：Resilience4j 每个状态导出 0/1 序列；面板用 `== 1` 只留当前状态，stat 的 displayName 用 `${__field.labels.instance} ${__field.labels.state}` 显示实例与状态文本，避免 6 条 0 值干扰。
- **命中率除零**：`clamp_min(denominator, 1e-9)` 保证无流量时比值为 0 而非 NaN。
- **smoke 配置优先级**：显式环境变量 > `.env` > 默认；不 source `.env`，避免把数据库/RabbitMQ 凭据带入 smoke 进程；所有 URL、超时、重试、产品 id、用户都可覆盖。
- **smoke 只走 HTTP**：不依赖 Redis/MySQL 客户端，验证的是 Nginx → 应用 → DB/MQ 的完整用户路径；登录 token 作为 `Authorization` 头携带，为后续鉴权预留。
- **smoke 断言异步契约**：秒杀必须返回 202 + ACCEPTED；若 `mq` toggle 已关闭返回 200 CREATED，smoke 会明确失败并提示，而不是静默通过，避免全栈验证失去意义。
- **verify 的证据选择**：重放后断言 `idempotent_reject_total > 0`（不仅存在），证明计数真的递增；再单独断言带 queue 标签的 ready 序列，证明 D4 的 MQ 面板不是空壳。
- **配置外置**：`ORDER_ACCEPT_SLO`、`SMOKE_*`、`METRIC_WAIT_*` 均在脚本/环境/配置中；Java 没有新增运行时常量。

# 存在缺陷项&TODO

- **`idempotent_reject` 也会统计 MQ 重复投递**：计数器位于共享持久订单层，秒杀消费者重复投递同样会 +1；这不是秒杀准入重放（后者仍只记 `seckill_attempts{result="replay"}`）。若需要严格区分来源，可后续加低基数标签（如 `source=sync|consumer`），但要先评估 D4/告警契约与标签基数。
- **P95/P99 低流量精度有限**：直方图为默认 Micrometer 桶 + 6 个 SLO 边界，样本很少时 `histogram_quantile` 只能线性插值；首请求含 Redisson 懒初始化，会出现 ~1s 的 P95 尖峰，不代表稳态。
- **直方图序列开销**：每个实例约 75 个 bucket 序列（默认桶 + SLO），3 实例 225 条；实验室可接受，生产应收窄桶或关闭默认桶。
- **MQ delivered 速率无 queue 标签**：RabbitMQ 4.1 的 detailed queue 家族不提供 delivered 计数，面板的消费速率是聚合 `rabbitmq_queue_messages_delivered_total`；ready/unacked 才是单队列。
- **smoke 会消耗库存**：每跑一次 product 1 与 product 20 各 -1；目前无 reset 脚本，演示/验收前需知悉。两次 verify + 一次 smoke 后 product 1 DB 81、product 20 DB/Redis 87、订单 32。
- **smoke 依赖默认 `mq` 开启**：关闭 mq toggle 时秒杀返回 200 CREATED，smoke 会失败并提示；这是有意的契约断言，不是脚本缺陷。
- **smoke 未直接断言 Prometheus 计数**：保持 HTTP-only；`idempotent_reject` 的 Prometheus 证据由 verify 覆盖。`SMOKE_LAB_TOKEN` 当前仅配置、未用于业务步骤。
- **D4 无自动化渲染/查询回归**：已手工用 Prometheus 验证 16 条 PromQL 与 Grafana API 200；后续可加 dashboard JSON lint / `/api/ds/query` 冒烟，但不在本任务范围。
- **verify 新等待窗口**：`METRIC_WAIT_RETRIES`/`METRIC_WAIT_INTERVAL_SECONDS` 已外置，但 `check_metric`、`check_http_ok` 等旧 helper 仍保留原有 15/20 次、5s 超时常量；后续统一清理。
- **订单重试常量仍在 Java**：`OrderApplicationService` 的 `MAX_RETRIES=10`、`RETRY_SLEEP_MILLIS=50` 是 Task 13 遗留，本任务未扩大范围外置。
- **Task 17 既有缺陷未变**：真实 Redis/MQ 故障被包装为 `BizException`，熔断忽略；`idempotency` 关闭会造成 Redis/DB 库存漂移；`cache` 关闭不清理旧条目；mock login 无鉴权。详见 Task 17 交接。

# 待定事项和注意事项

- **下一任务 M2-1**：k6 基础场景与 remote write。可直接复用 smoke 的登录/商品/订单/秒杀路径与 `ORDER_ACCEPT_SLO`；D4 作为业务/HPA 视图基线。不要把 smoke 放进高频压测循环（会消耗库存）。
- **运行状态**：16 个 lab 容器运行中；应用镜像 `java-learn-lab/seckill-app:jdk21`（`5b8b280b74d9`）；Prometheus 已重启加载 `rabbitmq-queue` job，后续修改 `prometheus.yml` 需要再次重启或开启 lifecycle reload。
- **Grafana**：D4 通过文件 provisioning 热加载（updateInterval 10s），无需重启；若面板显示 No data，先在 Prometheus 直接执行对应 PromQL，确认窗口内是否有流量。
- **数据状态**：product 1 DB 81（Redis 侧仍有历史漂移，早期 idempotency-off 演示所致）、product 20 DB/Redis 均 87、订单 32、`idempotent_reject_total` 3；仍无一键 reset。
- **toggle 注意**：跑 smoke/verify 前确保三实例 `mq` toggle 为 true（默认）。若做过关闭实验，需通过 `/lab/toggles` 逐实例恢复，或重启 app 容器回到 env 种子。
- **安全边界**：`.env` 不提交；smoke 不打印 token/密码；新增 `.env.example` 键均为本地默认值。`/lab/**` 仍不对 Nginx 暴露。
- **隔离约束**：Task 17 review worktree/branch 未被触碰；本任务未合并任何 review 分支。
- **提交**：功能提交 `feat: add business metrics and D4 smoke`；文档提交 `docs: record Task 18 metrics and smoke`。报告在 `.superpowers/sdd/ha-lab/task-18-report.md`（gitignored）。

# 附录

## 关键文件

- `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/order/app/OrderMetrics.java`（新增）
- `.../order/app/OrderTransactionService.java`、`.../order/app/OrderApplicationService.java`、`.../order/config/OrderConfig.java`
- `code/services/seckill-app-jdk21/src/main/resources/application.yml`
- `code/services/seckill-app-jdk21/src/test/java/com/javalearn/lab/seckill/order/app/OrderApplicationServiceIT.java`
- `code/lab/docker/grafana/dashboards/ha-d4.json`（新增）
- `code/lab/docker/prometheus/prometheus.yml`
- `code/lab/scripts/smoke.sh`（新增）、`code/lab/scripts/verify.sh`
- `code/lab/docker/compose.base.yml`、`code/lab/docker/.env.example`

## 复现命令

```bash
export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1"
cd code && mvn test

bash code/lab/scripts/smoke.sh
bash code/lab/scripts/verify.sh

# D4 API
curl -sS -u admin:labadmin -o /dev/null -w '%{http_code}\n' \
  http://127.0.0.1:3000/api/dashboards/uid/ha-d4
```

## 证据摘要

- smoke：`Smoke checks: 7 passed, 0 failed.`
- verify：`VERIFY PASSED: 96 checks passed, 1 warning(s).`
- Prometheus：`idempotent_reject_total{job="seckill-app"}` 累计 3；`count(order_accept_seconds_bucket{job="seckill-app"})` = 225；`rabbitmq_queue_messages_ready{queue="seckill.order.queue"}` 有序列。
- Grafana：`ha-d4` HTTP 200，11 panels，变量 `instance`。
