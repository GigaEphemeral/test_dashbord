> 步骤交接 · M1-6 限流熔断、mock login 与运行时 toggles
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / 提交 `feat: add resilience and runtime lab toggles`（父提交 `21b5374`）
> 关联任务：#17（M1-6）
> 测试 / verify：reactor 163 tests 绿（app 122）；重建镜像后 `verify.sh` 90 PASS / 1 WARN
> 运行状态：16 个 lab 容器运行中，三实例健康；product 1 DB 84 / Redis 93（历史 idempotency-off 演示漂移），product 20 DB/Redis 均 90；订单 26

# 当前进展

- Task 17 完成：mock login、`/lab/**` token 网关、六项运行时 toggle、Resilience4j 限流/熔断、同步 DB 降级、toggle-aware 两级缓存全部落地。
- 既有 Task 12–16 的 catalog、订单幂等、缓存、读写路由、异步秒杀链路保持不变；`/api/**` 契约未变。
- 配置继续强制外置：登录前缀/TTL、lab token、六个 toggle 种子、限流阈值/刷新窗口、熔断窗口/阈值/等待/半开/最小调用数全部在 `application.yml`，环境变量可覆盖；Java 无新运行值硬编码。
- 测试：reactor **163 tests, 0 failures**（app 122、core 13、chaos 14、common 10、observability 4）；app 新增 37 个测试。
- `verify.sh` 新增 10 项检查（login、Redis TTL、lab 401/200、POST 200、config 无密钥、Nginx 不代理、两个 resilience4j 指标、新 result 标签），**90 PASS / 1 WARN**；WARN 仍是既有 cAdvisor/WSL2 限制。
- 应用镜像已重建，三实例 force-recreate 后健康，Nginx 已加载新的 `/lab/**` 拒绝规则；栈保持运行。
- 下一步：Task 18（M1-7 业务指标、D4 与全栈 smoke）。

# 完成功能项

## Mock login

- `POST /api/login`，body `{"userId":"u1"}`；userId 非空校验，空/缺失 → 400 `40001`。
- `LoginService` 生成 UUID token，Redis `SET login:token:<token> = userId EX app.login.token-ttl-seconds`，返回 `Result<LoginResponse(token, expiresInSeconds)>`。
- 配置：`app.login.token-key-prefix`（`${LOGIN_TOKEN_KEY_PREFIX:login:token:}`）、`app.login.token-ttl-seconds`（`${LOGIN_TOKEN_TTL_SECONDS:1800}`）。
- 无用户表/鉴权拦截；该 token 是 k6 与后续鉴权的演示登录。

## 内部 lab API 与 token

- `LabTokenFilter` 仅注册在 `/lab/*`（`FilterRegistrationBean`，非 `@Component`），要求 `X-Lab-Token`，与 `app.lab.token`（`${LAB_TOKEN:lab-token}`）做 `MessageDigest.isEqual` 常量时间比较；`OPTIONS` 放行；否则 401 `Result`；token 从不写日志。
- `GET /lab/toggles` → `Result<Map<String,Boolean>>`，固定顺序 `lock,idempotency,mq,ratelimit,circuitbreaker,cache`。
- `POST /lab/toggles` body `{"key":"cache","value":false}` → 更新本实例内存状态并返回快照；未知 key / 缺失 value / 空 body → 400 `40001`。
- `GET /lab/config` → app/env/instance/JVM 版本/当前 toggles；不包含 token、数据库/Redis 凭据或端点。
- Toggles 为**每实例**内存状态；chaos-controller 后续广播。Nginx 对 `/lab/toggles`、`/lab/config` 显式 404，应用不发布宿主端口。
- Compose app env 增加 `LAB_TOKEN=${LAB_TOKEN:-lab-token}` 与六个 `LAB_TOGGLE_*` 默认值。

## Resilience4j

- 依赖：`io.github.resilience4j:resilience4j-spring-boot3:2.4.0` + `resilience4j-micrometer:2.4.0`（Maven Central，2.x/Boot 3/Java 21），版本在 POM 固定。
- `resilience4j.ratelimiter.instances.seckill`：50/1s，timeout 0（可配）；`resilience4j.circuitbreaker.instances.seckillBackend`：窗口 10、失败率 50%、open 10s、半开 3、最小调用数 5（补充，见设计逻辑）、`ignore-exceptions: BusinessException`（初版为 `BizException`，独立复审后改为仅忽略预期业务异常子类，见下方复审修复记录）。
- `SeckillResilienceConfig` 从 Boot 自动配置的 `RateLimiterRegistry`/`CircuitBreakerRegistry` 手动解析实例并启动即注册，Micrometer 暴露 `resilience4j_ratelimiter_available_permissions`、`resilience4j_circuitbreaker_state`。
- 接入顺序：校验 → （mq on 时）限流检查一次 → （circuitbreaker on 时）`CircuitBreaker.executeSupplier(admitAsync)` → `CallNotPermittedException` 或非 `BizException` 失败 → `createSynchronously` 降级 + `degraded` 指标；BizException 原样抛出，不打开熔断。
- `ratelimit` 关闭跳过限流；`circuitbreaker` 关闭直接执行异步路径；`mq` 关闭走同步 DB 路径（200 CREATED）；`lock` 关闭跳过 Redisson；`idempotency` 关闭跳过 Redis claim/release；`cache` 关闭返回 `NoOpCache`。
- 指标：`seckill_attempts{result=...}` 新增 `rate_limited`、`degraded`，启动即注册零值；既有指标保留。
- Actuator 关闭 resilience4j health indicator（`management.health.circuitbreakers/ratelimiters.enabled=false`），避免实验性 OPEN 熔断让容器健康门把实例摘掉；根健康仍诚实。

## 测试

- `LabToggleServiceTest`（6）：默认顺序、单项更新、未知 key/空 key/空 value、8 线程并发更新。
- `LabControllerTest`（9）：无 token/错 token 401、OPTIONS 放行、GET 六键、POST false/true、未知 key/缺失 value/空 body 400、config 无密钥。
- `LoginControllerTest`（3）：token/expiresInSeconds、空 userId 400、缺失 userId 400。
- `LoginServiceIT`（3，真实 Redis）：值/前缀/TTL、每次登录不同 token、空 userId 不写 Redis。
- `SeckillResilienceTest`（6，真实/受控注册表）：限流第二次 429 + 指标；熔断打开后降级 CREATED + degraded；BizException 不打开；CB toggle off 透传；限流 toggle off 全放行；降级路径只检查一次限流且不调用异步。
- `SeckillAdmissionServiceTest`：新增 mq 关、lock 关、idempotency 关、mq 可重新打开 4 个用例。
- `SeckillToggleIT`（2，真实 MySQL/Redis/RabbitMQ）：idempotency 关时同 key 两次只落 1 条订单（Redis 库存 -2，DB -1，记录 app 层缺口）；mq 运行时关闭走同步 DB（Redis 库存不变）。
- `ProductCacheIT` 新增 1 个：cache 关时两级都不读写、重新打开恢复 L2/L1 命中。
- `ToggleAwareCacheManagerTest`（3）：关闭返回 no-op、开启返回 TwoLevelCache、运行时切换无需重启。

# 设计逻辑

- **每实例 toggle + 内存 `AtomicBoolean`**：读取无锁，映射构造后不可变；非常适合“一个实例降级、两个实例正常”的对比实验。后续广播由 chaos-controller 负责，当前 API 不假装全局。
- **手动注册表而非注解**：注解在代理创建时固定，运行时无法干净跳过；从注册表拿实例后按 toggle 条件执行，才能做到“关闭即真正绕过”。
- **限流只检查一次**：`SeckillResilienceGuard` 是唯一调用 `acquirePermission()` 的地方，降级 supplier 直接指向 `createSynchronously`，不会重新进入 `admit`，因此无递归/重复计数。
- **熔断忽略 BusinessException（复审修正后）**：售罄、锁超时是预期业务语义（`BusinessException`，被熔断忽略）；参数错误/429 在进入熔断前抛出；Redis/MQ 的 503（`BizException(50301)`）计入失败率，真实故障累计后打开熔断。非业务异常或 OPEN 状态降级到同步 DB。
- **最小调用数 5**：Resilience4j 默认 100 大于 10 的滑动窗口；不补充 `minimum-number-of-calls` 时窗口永远到不了 100，熔断无法打开。该值外置为 `SECKILL_CB_MIN_CALLS`。
- **同步降级**：复用既有 `OrderApplicationService`（DB 条件扣库存 + 唯一幂等键），不复制事务逻辑；降级响应 `requestId=null`、`status=CREATED`、`orderId` 有值。
- **idempotency 关**：只跳过应用层 Redis claim；DB `uk_order_idempotency` 仍是最终背板。演示结果是“Redis 库存多扣一次、DB 只有一单”，这是要展示的缺口，不是 DB 保证被移除。
- **cache 关**：`CacheManager.getCache()` 每次按 toggle 返回 `NoOpCache`；Spring Cache 的 get/invoke/put 循环照常，但读写都不触达 Caffeine/Redis，`@Cacheable` 契约不变。重新打开返回原 `TwoLevelCache`，已有条目仍有效。
- **健康门隔离**：熔断 OPEN/限流耗尽属于实验现象，不应触发 Docker 健康检查失败；关闭对应 health indicator，根 `/actuator/health` 仍保留其他真实组件状态。
- **配置外置**：`LoginProperties`、`LabProperties`、`LabToggleProperties` 启动校验必填；`resilience4j.*` 全部 `${ENV:default}`；compose 只注入值，不复制默认。
- **Nginx 边界**：`/lab/**` 不属于业务入口，Nginx 对 toggles/config 显式 404；token 网关在应用内，应用无宿主端口，符合“internal only”。

# 存在缺陷项&TODO

- **熔断在真实基础设施故障下难触发**（已由复审修复，见文末复审修复记录）：Task 16 已把 Redis/MQ 故障包装为 `BizException(50301)`，而熔断配置按 brief 忽略所有 `BizException`；因此真实 Redis/MQ 故障不会打开熔断，降级路径目前由单元测试注入非 Biz 异常验证。若后续要在真实故障场景演示熔断，需要拆分“基础设施异常”和“业务异常”类型（或调整 ignore 规则），并同步更新 Task 16 的 503 契约。
- **限流仅覆盖异步路径**：`mq` 关闭时请求直接走同步 DB 路径，不经过限流；这与 brief “before the async path” 一致，但演示时需注意开关组合。
- **降级响应仍是 202**：控制器按 `mq` toggle 返回 202；降级响应没有 requestId、但 `status=CREATED`/`orderId` 有值。客户端应读 body 状态而非只看 HTTP；k6 场景需在 M2 对齐。
- **idempotency 关闭导致 Redis/DB 库存漂移**：Redis 库存多扣一次，DB 正确；没有对账任务，人工重置前需知悉。
- **cache 关闭不清理旧条目**：重新打开后仍可能命中关闭前的 L1/L2 条目；用于演示“绕过缓存”时建议先清 `cache:product:*` 或等 TTL。
- **toggle 变化无日志/审计**：当前只返回快照；操作台接入时可加审计事件。
- **登录无鉴权**：任何人可拿任意 userId 换 token；仅演示用途。
- **配置外置遗留**：`TwoLevelCache` 的 L1 容量/过期、L2 TTL、`cache:` 前缀仍是 Task 14 的 Java 常量；本步未扩大范围，后续触摸缓存时可一并外置。
- **verify 的 flaky 风险**：`docker exec ... | grep -q` 在 `pipefail` 下可能因 SIGPIPE 误报；本次已把 `cache_l2_errors_total` 检查改为先捕获再 grep，其他旧检查未动。

# 待定事项和注意事项

- 下一任务 M1-7：D4 业务指标面板与全栈 smoke；不要把 `/lab/**` 暴露到 Nginx 或宿主端口。
- chaos-controller 广播 toggles 时需新增内部调用（当前只提供 app 内 API）；广播应逐实例调用并汇总失败。
- 演示熔断/429 时优先在单元/IT 或隔离实例上做，避免 verify 和产品库存被污染；verify 只做无副作用的 cache false→true。
- 演示 idempotency 关闭后如需恢复库存一致性，需要手工对账或重置 Redis 库存键（谨慎）。
- 演示数据继续累积：product 1 DB 库存 84（Redis 93，早期 idempotency-off 演示的已知漂移）、product 20 DB/Redis 均 90、订单 26；仍无 reset 脚本。
- 栈保持运行，不要 `--purge` 或删除命名卷；`.env` 不提交。
- 提交：本次 `feat: add resilience and runtime lab toggles`；报告在 `.superpowers/sdd/ha-lab/task-17-report.md`（gitignored）。

# 附录

- 关键文件：
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/login/`
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/lab/`
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/admission/app/SeckillResilienceGuard.java`
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/admission/config/SeckillResilienceConfig.java`
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/catalog/cache/NoOpCache.java`
  - `code/services/seckill-app-jdk21/src/main/resources/application.yml`
  - `code/lab/docker/compose.base.yml`
  - `code/lab/docker/nginx/conf.d/default.conf`
  - `code/lab/scripts/verify.sh`
- 关键测试：
  - `.../lab/toggle/LabToggleServiceTest.java`
  - `.../lab/api/LabControllerTest.java`
  - `.../login/api/LoginControllerTest.java`、`.../login/app/LoginServiceIT.java`
  - `.../admission/app/SeckillResilienceTest.java`
  - `.../admission/SeckillToggleIT.java`
  - `.../catalog/cache/ToggleAwareCacheManagerTest.java`、`ProductCacheIT.java`
- 复核入口：本文件、`doc/process/阶段交接-2026-10-26.md`、`.superpowers/sdd/ha-lab/task-17-report.md`。

---

## 复审修复记录（Task 17 独立审核，2026-10-26）

**Critical/Important 修复**

1. **熔断对真实基础设施故障不可触发（Important，已修复）**：Task 16 把 Redis/MQ/库存/发布/状态读取故障全部包装为 `BizException(50301)`，而 `seckillBackend.ignore-exceptions` 配置为父类 `BizException`；Resilience4j 的 ignore 匹配是 `configuredClass.isAssignableFrom(thrown.getClass())`（父类配置覆盖全部子类），真实故障永远打不开熔断，D4 的熔断演示不成立。
   - 修复：引入 `com.javalearn.lab.common.BusinessException extends BizException`（platform-common，Java 8 框架中立），仅售罄/锁超时等预期业务结果使用；`ignore-exceptions` 改为 `BusinessException`。父类 `BizException(50301)` 不再被忽略：CLOSED 时原样抛出（HTTP 503 / 50301 契约不变），同时计入失败率，累计达到 `minimum-number-of-calls` 后 OPEN，随后请求走同步 DB 降级。非 Biz 的意外异常行为不变。
   - 与 brief 字面要求（`ignore-exceptions: BizException`）的偏差理由：保持字面配置会使本任务的熔断演示在 §11.3 可用故障（容器 kill、Toxiproxy 断 Redis/MySQL）下完全不触发；拆分"预期业务异常 vs 基础设施异常"是设计规范 §15 第 2 行的前提。`SeckillResilienceGuard` 的 BizException 传播逻辑未变（BusinessException 是其子类）。
2. **verify.sh 新增检查的硬编码（Important，已修复）**：Task 17 新增的 Nginx lab 探测 `--max-time 5`、resilience4j 指标轮询 `seq 1 30`/`sleep 2` 外置为 `LAB_NGINX_CURL_MAX_TIME`、`RESILIENCE_POLL_RETRIES`、`RESILIENCE_POLL_INTERVAL_SECONDS`（工程规约 §1：超时/重试/间隔必须可覆盖；容器内 wget 探测无 URL/超时新增硬编码，沿用既有 `LAB_INTERNAL_BASE_URL`）。

**Minor 记录（未改动）**

- login/lab 走 Nginx 的 curl URL `http://127.0.0.1:8080` 与既有 verify 检查风格一致（历史清单已登记）；docker exec 内 `wget` 无 `--timeout`，沿用文件既有写法。
- Redis 故障时 `POST /api/login` 由 catch-all 返回 500（演示可接受，未处理）。
- `cache` 关闭时 `TwoLevelCacheConfig` 启动预建返回 no-op，重开后首次访问才注册缓存指标（有意，注释已说明）。

**测试证据（JDK 21）**

- 修复前回归：`SeckillResilienceConfigIT#infrastructureWrappedFailuresOpenTheCircuitWithTheProductionConfig` 在旧配置下失败（`expected: OPEN but was: CLOSED`），真实容器 + 生产 YAML + 真实 registry，无 mock 状态机。
- 修复后：`mvn -pl services/seckill-app-jdk21 -am test` 全 reactor 167 tests / 0 失败（platform-common 10、observability 4、seckill-core 13、app 126、chaos 14；app 较修复前 +4：新 IT 3 例 + `SeckillResilienceTest` 新增"50301 计入并 OPEN"1 例）。
- 覆盖（真实 CircuitBreaker）：50301×5 → OPEN + 第 6 次 CREATED/degraded；`BusinessException`×8 → CLOSED、0 失败、0 降级；非 Biz ×5 → OPEN + 降级；OPEN 后降级不重入限流/熔断（`SeckillResilienceTest` 既有断言保留）。Task 16 的 503 单测与 `GlobalExceptionHandler` 契约未回退。

**遗留**

- 熔断 OPEN 期间的容器健康与 D4 面板联调属 Task 18（M1-7）。
- 本次未运行 `verify.sh`（隔离 worktree，不触碰共享栈）；主会话合并后需重建镜像并统一跑一次全量 verify。
