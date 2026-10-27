# 图 2 · 秒杀下单全链路

> 代码基线：`504fc74`（本目录所有图统一基线）
> 源文件：[02-seckill-request-chain.mmd](02-seckill-request-chain.mmd) · 类型：`sequenceDiagram`

从浏览器/Nginx 到应用准入、Redis 锁/幂等/Lua、RabbitMQ、消费者与 MySQL 的完整时序，包含发布失败/消费失败补偿、熔断降级到同步 DB 路径，以及 `mq` 开关关闭时的同步替代路径。

读图要点：HTTP 状态只由 `mq` 开关决定——`mq=true` 返回 202（包括熔断降级到同步 DB 的请求），`mq=false` 返回 200；降级本身不改变状态码。

```mermaid
%% 代码基线：504fc74
%% 图 2 · 秒杀下单全链路（异步准入 + 补偿 + 同步降级）
%% 依据：SeckillController、SeckillAdmissionService、SeckillResilienceGuard、RedisSeckillStockStore、RedisSeckillIdempotencyStore、RabbitSeckillMessagePublisher、SeckillOrderConsumer、OrderApplicationService、OrderTransactionService
sequenceDiagram
    autonumber
    actor C as 浏览器 / k6
    participant NG as Nginx
    participant API as SeckillController
    participant SVC as SeckillAdmissionService
    participant CB as SeckillResilienceGuard
    participant R as Redis
    participant MQ as RabbitMQ
    participant CS as SeckillOrderConsumer
    participant DB as MySQL

    C->>NG: POST /api/seckill/orders<br/>Idempotency-Key: key
    NG->>API: 轮询到 app-N
    API->>SVC: admit(key, request)
    SVC->>SVC: validate（缺 key / 参数非法 → 40001）

    alt mq toggle = false（同步降级路径，不经过限流/熔断/锁）
        SVC->>DB: OrderApplicationService.create(SECKILL)<br/>事务：条件扣库存 + uk_order_idempotency
        DB-->>SVC: CREATED 或既有订单
        SVC-->>C: 200 CREATED
    else mq toggle = true（异步准入）
        SVC->>CB: admitAsync(asyncAdmission, synchronousFallback)
        CB->>CB: ratelimit.acquirePermission()<br/>被拒 → 42901（仍计入 rate_limited）
        alt circuit CLOSED 且异步路径成功
            CB->>SVC: admitAsync(key, request)
            SVC->>R: Redisson tryLock lock:seckill:{pid}<br/>wait 500ms / lease 3000ms
            alt 未拿到锁
                SVC-->>C: 409 Seckill lock timeout<br/>metrics lock_timeout
            else 已持锁
                SVC->>R: SET NX EX seckill:idem:{key} = requestId（1800s）
                alt 幂等键已存在
                    SVC->>R: GET seckill:request:{requestId}
                    alt 状态已落盘
                        SVC-->>C: 202 replay（同 requestId）
                    else 状态尚未落盘或已过期
                        SVC-->>C: 503 状态不可用
                    end
                else 首次占用幂等键
                    SVC->>R: EVAL lua/seckill_stock.lua<br/>GET 库存 + 余量判断 + DECRBY（原子）
                    alt 返回 -1 售罄
                        SVC->>R: DEL 幂等键（释放占用）
                        SVC-->>C: 409 Sold out
                    else 返回 -2 库存键不存在
                        SVC->>R: DEL 幂等键
                        SVC-->>C: 503 库存未初始化
                    else 扣减成功
                        SVC->>R: SET seckill:request:{requestId} ACCEPTED EX 1800
                        SVC->>MQ: publish seckill.order.exchange<br/>routing key seckill.order.requested
                        alt 发布失败
                            SVC->>R: INCR 库存（best-effort）<br/>DEL request + DEL 幂等键
                            SVC-->>C: 503（预留已回滚）
                        else 发布成功
                            SVC-->>C: 202 ACCEPTED + requestId
                        end
                    end
                end
                SVC->>R: unlock（lease 到期兜底）
            end
        else circuit OPEN（CallNotPermitted）或异步路径抛非 BizException
            CB->>DB: 同步降级 createSynchronously(SECKILL)<br/>不重入限流/熔断/锁
            DB-->>CB: CREATED 或既有订单
            CB-->>C: 202（mq 仍为 true）+ CREATED<br/>metrics degraded
        else circuit CLOSED 且抛出 BizException(50301)
            CB-->>C: 503（原样上抛，同时计入熔断失败窗口）
        end
        Note over CB: BusinessException（售罄、锁超时）是预期业务结果，<br/>不计入熔断失败；Redis/MQ/库存故障才计入。<br/>HTTP 状态只由 mq 开关决定：mq=true → 202（含熔断降级），<br/>mq=false → 200；降级本身不改变状态码。
    end

    MQ->>CS: 投递消息（listener 先 sleep consumer.delay-ms=20）
    CS->>DB: OrderApplicationService.create(SECKILL)<br/>同一 DB 事务：条件扣库存 + 唯一键
    alt 订单已存在（重复投递）
        CS->>R: mark request CREATED
        CS-->>MQ: ack（consumer_processed result=duplicate）
    else 新建成功
        CS->>R: mark request CREATED
        CS-->>MQ: ack（result=created）
    else DB 失败且确认无既有订单
        CS->>R: INCR 库存（best-effort）+ mark request FAILED
        CS-->>MQ: reject 且不重新入队
    end

    C->>NG: GET /api/seckill/orders/{requestId}
    NG->>API: 轮询
    API->>R: GET seckill:request:{requestId}
    API-->>C: 200 ACCEPTED / CREATED / FAILED
```
