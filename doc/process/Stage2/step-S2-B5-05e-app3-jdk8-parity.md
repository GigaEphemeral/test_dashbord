# 当前进展

B5-05 P1：**app-3 已真正成为三个业务实例之一**——同一 Nginx upstream、同一 MySQL/Redis/RabbitMQ、同一
run 节点集合，承接 login / 商品两级缓存读 / `POST|GET /api/orders` / 异步 `/api/seckill/orders`；
不再是“只有商品直读的对照外壳”。外壳 `jl-seckill-app-jdk8` 仍保留在 `dual-jdk` profile，只用于 D7 同机对比，
不参与分流。

提交：`e2ba057`（jdk8 业务移植）、`7c81556`（jdk8 缓存/缓存实验/回读）、本步 feat/fix 提交（见附录）。

验证（2026-10-30 真机）：

| 闸门 | 结果 |
|---|---|
| jdk8 模块 `mvn test` | **350 tests / 0 failures / 0 errors** |
| 控制器 `mvn test` | **892 tests / 0 failures / 0 errors** |
| `smoke.sh` | **7 passed / 0 failed**（`SMOKE PASSED.`） |
| `verify.sh` | **108 PASS / 1 WARN / 0 FAIL**（WARN 仍为 cAdvisor/WSL2；Sentinel 2+2 后比 109 少一条 per-sentinel 断言） |
| `console-smoke.sh` | **158 PASS / 0 FAIL** |
| `check-doc-claims.sh` | **91 PASS / 0 FAIL** |
| 分流探测 | 经 Nginx 各 9 次 `POST /api/orders`、`POST /api/seckill/orders`，**全部 200/202，无 404** |

# 完成功能项

| 项 | 落地 |
|---|---|
| 业务移植 | `seckill-app-jdk8` 增加 order（JPA 下单/幂等重放/库存条件扣减）、admission（Redisson 锁 → Redis 幂等 → Lua 原子扣库存 → Rabbit 发布 → 202 → 轮询）、业务池、Resilience4j 限流/熔断、Lab toggle、Redis 客户端（standalone/sentinel） |
| 跨运行时队列 | 三个实例共享同一 exchange/queue；app-3 发布时把 `__TypeId__` 改写为 JDK21 的 `SeckillOrderMessage` FQCN（`app.seckill.mq.type-id`，可配），消费端按监听签名反序列化，双向兼容 |
| 两级缓存 | 移植 TwoLevelCache（Caffeine L1 + Redis L2）、穿透/击穿/雪崩防御、L1 开关、预热与热键追踪；商品读改走 `CachedProductViewService`，指标名与 JDK21 一致（`cache_l1_*`/`cache_l2_*`/`cache_*_total`） |
| 回读端点 | `/lab/pool`、`/lab/config`、`/lab/toggles`、`/lab/cache/{mode,seed,state,preheat,keys,l1,reset}` 与 JDK21 同契约 |
| 接线 | compose `app-3` 增加 `APP_PROFILE=mysql`、`APP_NAME=seckill-app`、`INFO_RUNTIME=jdk8`、`LAB_SNAPSHOT_DIR` 与模板挂载；`Dockerfile.runtime` 从 JDK21 镜像复制同版本 OTel agent |
| 控制面 | 默认 `jdk8-mode=app-slot`、`names=app-1,app-2,app-3`、`jdk8-instance=app-3`、`jdk8-prometheus-job=seckill-app`；app-slot 的 JDK8 槽位为 **FULL-OBSERVABILITY**，`/lab/pool` 参与 apply 回读；全局 stop-all 对无线程实验室的实例返回 `not-applicable` 而不是 `failed` |
| 控制台 | 移除旧“外壳独立列”和 app-3 的 LIMITED-OBSERVABILITY；实例徽标同时展示 FULL/LIMITED；app-3 参与向导节点与三列并排；线程工具选中 app-3 时直接提示不可用并停止发请求 |
| 文档/断言 | README、`.env.example`（app-slot 默认值/Sentinel 2+2 期望值）、架构现状、故障注入说明、claims（新增 app-3 业务对等断言）、验收计划基线 |

# 设计逻辑

- **为什么移植而不是反向代理**：app-3 要作为第三份真实业务流量与 JVM/GC 对比对象，任何“转给 app-1”的代理都会
  污染吞吐/P99 与线程/堆观测；只有同契约的本地实现才能让 Nginx 轮询结果可比。
- **为什么共享队列/键**：秒杀库存、幂等键、请求状态属于商品而不是实例；三个实例必须看到同一份 Redis 状态，
  队列也必须竞争消费，才能保持“同一业务、三个 JVM”的口径。
- **`__TypeId__` 适配**：Spring AMQP 默认按类名路由，两个运行时的类名不同会让共享队列互不可读；
  app-3 发布时写入 JDK21 的规范类名，消费端按监听参数类型转换，避免再引入第三套消息格式。
- **mysql profile 是硬要求**：Boot 2.7 外壳默认 `demo`（H2），如果沿用默认值，app-3 会返回 H2 种子数据，
  与 app-1/2 的 MySQL 数据分成两套；compose 显式 `APP_PROFILE=mysql` 是本次“看起来没分流/数据不对”的根因之一。
- **FULL-OBSERVABILITY 的条件**：只有独立外壳（`jdk8-mode=shell`）仍是无 `/lab/pool` 的 LIMITED；
  app-slot 的 app-3 有池/配置/缓存回读，因此按 FULL 处理，`INSTANCE_NAMES` 也不再包含外壳。
- **线程实验室暂不移植**：Boot 2.7 侧线程故障端点仍需 `ThreadFaultService`/演示快照的一整套改造，
  且虚拟线程在 JDK8 不成立；按 P0 约定放在 P3，当前 UI 明确标注 N/A，容器级 kill/freeze 不受影响。
- **缓存版本差异**：Boot 2.7.18 BOM 管理 Caffeine **2.9.3**（3.x 需要 Java 11），API 使用面一致；
  极限淘汰时序可能与 3.x 略有差异，但指标名与容量语义相同。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 线程实验室 | app-3 无 `/lab/thread/**`；UI 已提示 N/A，容器级故障可用 | P3（虚拟线程模式除外） |
| Sentinel 基线数字 | 3→2 哨兵后 verify 从 109 PASS 变为 108 PASS（少一条 per-sentinel 断言）；已在验收计划登记 | 记录 |
| 缓存单测的 Redis 替身 | jdk8 的 `TwoLevelCacheTest` 用内存 FakeRedis（无 Testcontainers）；真实 Redis 覆盖仍在 jdk21 的 IT | 记录 |
| 外壳并存 | `jl-seckill-app-jdk8` 仍由 `dual-jdk` 提供 D7 对比；确认不再需要时可连同 `seckill-app-jdk8` job、D7 面板一并下线 | 待定 |
| JDK8 flags 回读 | `jcmd VM.flags` 仍未对 JDK8 执行（`jvmReadback=READBACK_UNAVAILABLE`），堆/GC 以配置台账 + GC 日志为准 | 记录 |
| 旧拓扑孤儿容器 | `jl-sentinel-3`、`jl-redis-3(-exporter)` 是 2+2 改造前的残留，已 `docker stop`（未删除）；不要再启动，否则 Sentinel 会重新发现第三条记录 | 记录 |

# 待定事项和注意事项

1. **不要再把 app-3 当外壳用**：`LAB_INSTANCE_JDK8_MODE=shell` 只在需要恢复旧 D7 布局时使用；
   此时 `names` 需包含 `seckill-app-jdk8`，UI 会重新出现外壳语义。
2. **app-3 镜像必须走 mysql profile**：手工 `docker run` 时若忘记 `APP_PROFILE=mysql`，数据会退回 H2。
3. **重建顺序**：先 `mvn -pl services/seckill-app-jdk8 -am -DskipTests package`，再构建 `Dockerfile.runtime`，
   最后 `up -d --no-deps --force-recreate app-3`；控制面改动同样需要重建 `jl-chaos-controller`。
4. **共享队列的双向兼容**由 `app.seckill.mq.type-id` 决定，改动 JDK21 消息类名时必须同步该默认值。
5. **app-3 的 `INFO_SHELL=seckill-app-jdk8`** 只用于 info 面板标识；Prometheus `app` 标签仍是 `seckill-app`，
   保证三个实例落在同一 job/面板查询里。

---

## 附录 A：真机证据

```text
# 分流（Nginx 轮询，修复前 202 202 404 202 202 404 ...）
$ for i in $(seq 1 9); do curl -o /dev/null -w '%{http_code} ' -X POST \
    http://127.0.0.1:8080/api/seckill/orders -H 'Content-Type: application/json' \
    -H "Idempotency-Key: probe2-$i" -d '{"userId":"u","productId":1,"quantity":1}'; done
202 202 202 202 202 202 202 202 202

# smoke.sh
PASS  POST /api/orders creates order id=77 ...
PASS  repeat Idempotency-Key returns the same order id=77
PASS  POST /api/seckill/orders accepted asynchronously ...
PASS  GET /api/seckill/orders/... reaches CREATED (orderId=78)
SMOKE PASSED.

# app-3 身份与回读
/actuator/info -> {"shell":"seckill-app-jdk8","runtime":"jdk8","app":{"name":"seckill-app",...}}
/lab/pool      -> corePoolSize 4 / maxPoolSize 8 / queueCapacity 50 ...
/lab/config    -> jvmVersion 1.8.0_504, toggles 6 项
/lab/cache/state -> defense.mode off, avalanche/breakdown 全量回读

# Prometheus（同一 job，app-3 的 jvm_version=1.8.0_504）
orders_created_total{instance="app-3:8080",app="seckill-app",job="seckill-app"} = 4
up{job="seckill-app"} -> app-1/app-2/app-3 全 1
```

## 附录 B：关键文件

- `code/services/seckill-app-jdk8/**`（order/admission/cache/lab/redis + application.yml + pom）
- `code/services/seckill-app-jdk8/Dockerfile.runtime`（OTel agent 复用）
- `code/lab/docker/compose.base.yml`（app-3 身份与 profile、mixed-image 默认值）
- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/instance/{InstanceConfigProperties,InstanceConfigValidator,InstanceConfigService,InstanceRebuildService}.java`
- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/topology/TopologyService.java`
- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/action/EnvironmentStopAllService.java`
- `code/lab/dashboard/src/components/{InstanceConfigEditor,ConfigGroupEditor,ExperimentWizard,ThreadLab}.vue`
- `code/lab/scripts/{up.sh,console-smoke.mjs}`、`code/lab/docclaims/claims.yml`
