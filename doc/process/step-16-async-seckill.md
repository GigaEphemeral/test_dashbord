> 步骤交接 · M1-5 秒杀主链路：锁、库存、MQ
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / `d6b3d21`（Task 16 配置修复，基于 `9044ed9`；父提交 `a1b690b`）
> 关联任务：#16（M1-5）
> 测试 / verify：reactor 126 tests 绿；app 85 tests 绿；`verify.sh` 80 PASS / 1 WARN
> 运行状态：16 个 lab 容器运行中，三实例健康；product 20 库存 DB/Redis 均 97

# 当前进展

- Task 16 完成：异步秒杀准入、Redisson 商品锁、Redis 幂等 + Lua 原子库存、RabbitMQ 持久化队列、限速消费者、轮询接口、启动库存播种全部落地。
- 既有同步 `/api/orders` 仍是持久订单唯一路径；消费者通过 `OrderApplicationService` 复用它，没有复制事务/库存逻辑。
- 配置外置规约首次强制执行：所有路径、超时、TTL、前缀、队列/交换机、消费延迟、Redisson 池大小均在 `application.yml` / compose env，Java 无运行值硬编码。
- 全量测试：reactor 126（app 85、core 13、chaos 14、common 10、observability 4）；`verify.sh` 80 PASS / 1 WARN（cAdvisor/WSL2 既有 WARN）。
- Qwen 审核 Approved；按配置外置规约补修两处 Minor（verify curl 超时、compose Redis/Rabbit 端口），提交 `d6b3d21`，范围复审通过。
- 下一步：Task 17（M1-6 限流熔断、mock login、运行时 toggles）。

# 完成功能项

- `POST /api/seckill/orders`：`Idempotency-Key` 必填；异步返回 202 + `SeckillResponse(requestId,status,orderId)`；缺键/非法参数 40001；售罄/锁超时 40901；Redis/RabbitMQ 不可用 50301。
- `GET /api/seckill/orders/{requestId}`：200 `SeckillRequestView`（ACCEPTED/CREATED/FAILED、orderId、message）；未知/过期 40401。
- 异步流程：校验 → `RLock` 商品锁（wait/lease 可配）→ Redis `SET NX EX` 幂等 → classpath Lua 原子扣减 → 存请求状态 → 发布 JSON → 202；`finally` 释放锁。
- 发布失败：Lua 回补库存、删除幂等/请求键、返回 503；售罄/缺库存时释放幂等占位。
- 幂等重放：同 key 返回同一 requestId 与当前状态，不再扣库存/发消息；DB `uk_order_idempotency` 仍是最终背板。
- 消费者：`@RabbitListener(queues="${app.seckill.mq.queue}")`，持久化 DirectExchange/Queue/Binding，JSON converter；`consumer.delay-ms` 限速；调用 `OrderApplicationService.create`；成功置 CREATED，失败置 FAILED 并 best-effort 回补 Redis 库存；`default-requeue-rejected=false`，失败后抛出 `AmqpRejectAndDontRequeueException` 不重入队。
- 重复投递：DB 幂等键收敛为同一订单；创建前预查 + 失败后再查，已存在订单时绝不回补 Redis 库存。
- 指标：`seckill_attempts{result=accepted|replay|sold_out|lock_timeout|invalid|error}`、`order_accept_seconds`、`order_consumer_processed{result=created|duplicate|failed}`、`stock_oversold`（恒 0）；所有系列启动即注册。
- 启动播种：`ApplicationRunner` 读取 `inventory_stock`，`SET NX` 每个 `seckill:stock:{productId}`，记录 seeded/skipped；Redis 故障仅 WARN，不阻塞启动。
- 同步 toggle：`app.seckill.async-enabled=false` 时直接调用 `OrderApplicationService`，返回 200 + `status=CREATED` + `orderId`，不经过 Redis 库存/MQ。
- 测试：新增 10 个 `SeckillAdmissionServiceTest`、5 个 `SeckillOrderConsumerTest`、10 个 `SeckillControllerTest`、5 个 `SeckillStockStoreIT`、`SeckillAsyncIT`、`SeckillConcurrencyIT`、`SeckillIdempotencyIT`；`MySqlRabbitIntegrationTestSupport` + `RabbitMqTestContainer` 为所有 full-context 测试提供 RabbitMQ 4.1。
- `verify.sh` 新增 7 项：product 20 初始库存、202+requestId、轮询 CREATED、同 key 重放、DB/Redis 各 -1 且仅 1 条订单、RabbitMQ 队列存在且 `messages_ready=0`、秒杀指标暴露。
- 应用镜像重建、三实例 force-recreate，当前全部 healthy。
- 配置补丁：`verify.sh` seckill 段 curl 超时改为 `SECKILL_CURL_MAX_TIME` / `SECKILL_CURL_POLL_MAX_TIME`；compose `REDIS_PORT`/`RABBITMQ_PORT` 改为 `${VAR:-default}`；默认行为不变（`d6b3d21`）。

# 设计逻辑

- **锁与幂等顺序**：先拿商品锁，再做幂等 claim。锁保证同商品准入串行，减少“幂等键已写、状态未写”的窗口；幂等值存 requestId，重放直接读请求状态，不重算。
- **两层库存**：Redis Lua 是准入闸门（原子 check+DECRBY，超卖不可能）；DB 条件更新 + 唯一键是持久真相。消费者只调用既有订单服务，不再次扣 Redis；DB 售罄/异常时回补 Redis。
- **状态与回滚**：请求状态先落 Redis 再发消息；发布失败删除幂等/状态并回补库存，客户端可用同 key 重试。消费者失败置 FAILED、回补库存、拒绝且不重入队。
- **Redisson 懒连接**：`lazy-initialization=true` 保证 Redis 故障时应用仍能启动/健康（Task 14 契约）；真实锁调用失败映射 503。
- **配置外置**：`SeckillProperties` 无 Java 默认值，YAML 是唯一默认来源，`@PostConstruct` 校验必填/正值；Rabbit 连接、监听并发、prefetch 来自 `spring.rabbitmq.*`，compose 用 `RABBITMQ_DEFAULT_USER/PASS` 插值，不复制凭据。
- **播种读走写池**：`JdbcTemplate` 非事务查询走 primary/write-pool，避免 Spring Data 只读事务在启动时初始化 read-pool，满足 Task 15 的健康/池语义；`SET NX` 使三实例只有一个 seeded，其余 skipped。
- **测试隔离**：每个 seckill IT 使用独立 exchange/queue/routing key，避免 Spring context cache 中多个 listener 竞争；额外 audit queue（无消费者）绑定同一 exchange/routing key，可精确证明发布消息数（重放不增、40 并发=20、同 key=1）。
- **线程池/锁等待**：并发测试锁等待 2s/5s，consumer delay 0；生产默认 500ms/20ms，全部可配置。

# 存在缺陷项&TODO

- 发布仅 `convertAndSend`，未启用 publisher confirm/return；连接失败能捕获，交换机存在但路由丢失无法感知。后续可加 confirms + DLQ。
- 失败消息无 DLQ/重试；已补偿后丢弃，只能通过请求状态 FAILED 查询。
- 请求状态是 JSON 覆盖写，不是 CAS；并发重复投递的 CREATED/FAILED 可能互相覆盖（订单唯一键仍保证不会重复）。
- `duplicate` 指标依赖创建前查询提示，极端并发下可能多计 created。
- Redis `restore` 为 `EXISTS` + `INCRBY` 两步，非严格原子；人工改 DB/删 Redis 键后可能漂移，暂无对账任务。
- 状态保存后、发布前崩溃会留下 ACCEPTED 到 TTL；无补偿扫描。
- 同步 toggle 不计 sold_out 指标；目前仅是启动开关，运行时切换留给 M1-6。
- 所有 full-context 测试新增 RabbitMQ 容器依赖，测试启动时间增加。
- `order_accept_seconds` 同时覆盖同步/异步路径（同步路径也记录耗时）。

# 待定事项和注意事项

- `app.seckill.async-enabled` 运行时刷新、限流熔断、mock login 在 Task 17；届时评估是否把开关接入运行时 toggles。
- 后续增加 publisher confirms 时需要处理“已发布但未确认”的幂等/回滚边界，避免重复回补。
- 消息 DLQ 与请求状态补偿扫描可在 M2/M3 故障场景中补齐。
- 演示数据累积：product 1 库存 91、product 20 库存 97、订单 12；尚无 reset 脚本。
- 栈保持运行，不要 `--purge` 或删命名卷；Product 20 已被 verify 使用，后续秒杀验证继续用它，product 1 留给同步订单检查。
- 提交：`9044ed9`（异步秒杀）、`d6b3d21`（配置外置修复）；报告在 `.superpowers/sdd/ha-lab/task-16-report.md`（gitignored）。

# 附录

- 关键文件：
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/admission/`
  - `code/services/seckill-app-jdk21/src/main/resources/lua/seckill_stock.lua`
  - `code/services/seckill-app-jdk21/src/main/resources/application.yml`
  - `code/lab/docker/compose.base.yml`
  - `code/lab/scripts/verify.sh`
  - `code/services/seckill-app-jdk21/src/test/java/com/javalearn/lab/seckill/MySqlRabbitIntegrationTestSupport.java`
  - `code/services/seckill-app-jdk21/src/test/java/com/javalearn/lab/seckill/RabbitMqTestContainer.java`
- 复核入口：本文件、`doc/process/阶段交接-2026-10-26.md`、`.superpowers/sdd/ha-lab/task-16-report.md`。
