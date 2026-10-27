> 步骤交接 · M3-1b 应用 Sentinel 客户端与双模式配置
> 日期：2026-10-27
> 分支 / HEAD：`worktree-agent-general-purpose-966ac9a0`（自 `f3cb47a`）/ `feat: wire Sentinel client discovery into the app with a REDIS_MODE switch` + `test:` + 本文档 `docs:` 三提交
> 关联任务：#48（M3-1b 本步）；上游 #47（M3-1a，见 `step-24a-sentinel-compose.md`）；下游 #49（M3-1c Sentinel 故障目标白名单与切换场景验收）
> 设计依据：`doc/design/Stage1/2026-10-26-ha-concurrency-lab-design.md` §8.2（应用通过 Sentinel 按主名发现主、Redisson `sentinelServersConfig`、dev=单机/配置 env 切换）、§3.3；`doc/design/工程规约.md` 第 1 节（配置外置）
> 测试 / verify：`mvn -pl services/seckill-app-jdk21 -am test` → **Tests run: 215, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**（含新增 `RedisModeSwitchTest` 11、`SeckillRedissonModeTest` 5、`SentinelMasterDiscoveryReporterTest` 7；`ProductCacheIT` 5/5、`RedisOutageCacheIT` 3/3 复跑通过）；`verify.sh` **103 PASS / 1 WARN / 0 FAIL**（与 24a 基线一致，WARN 仍是 cadvisor/WSL2 限制）；`smoke.sh` **7/7**；compose 不变式：默认渲染 **16** 服务且渲染中 **0** 个 `REDIS_MODE/SPRING_DATA_REDIS_SENTINEL_*` 键（dev 逐字节不变），`--profile ha` **25** 服务、三 app 均带 `REDIS_MODE: sentinel` 并双网挂载
> 运行状态：dev 16 常驻 + ha 9 全部 running；app 三实例已还原为 **standalone**（日志 `Redis access mode: standalone`，env 中无 `REDIS_MODE`/哨兵键，单网 `jl-net`）；演练后主漂移为 `jl-redis-1`（172.28.190.11），`jl-redis-2` 已 `docker start` 复位为从、两从 `master_link_status=up`；未 `--purge`、未删卷、未触碰 `jl-redis`/`jl-mysql`/`jl-rabbitmq`
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- M3-1b 完成：应用侧接入 Sentinel。`REDIS_MODE=standalone|sentinel` 一个开关**同时**切换 Spring Data/Lettuce（商品缓存 L2）与 Redisson（商品锁/幂等/Lua 库存/M4-1 布隆+空值标记）两条客户端路径；sentinel 模式**只按主名 `mymaster` 发现主节点**，缺主名或缺哨兵列表直接启动失败，绝不回落到写死地址；dev/standalone 仍是默认。
- 客户端路径端到端验证补齐（24a 契约第 4 点的遗留项）：ha 渲染下三实例启动日志均出现 `Sentinel discovered master 'mymaster' at 172.28.190.12:6379`；本步做一次干净演练（停主→读 5xx/超时→**9.5s** 恢复 200→Redisson/Lettuce 免重启重挂新主→旧主复位为从），证据见附录。
- 前两任 agent 的遗留处理：代码已写完但**未提交、无书面证据、栈留在 sentinel 模式**；本步补跑测试、重做留证演练、还原默认态并落文档。他们报告"两次演练成功"但未留痕，其结果**不可复核，不计入证据**；本附录只记录本次单次阅读量演练。
- 未做（本步边界）：操作台/场景驱动切换（#49）；哨兵全组宕机下的 sentinel 模式**活体**降级演练（仅有 mock 级测试）；`jl-redis-*-exporter` 仍未被 Prometheus 抓取（24a 遗留）。

# 完成功能项

## 1. `seckill/redis/` 新包（3 个主类）

- `RedisAccessProperties`：`app.redis.*`（env `REDIS_MODE`），只接受 `standalone|sentinel`，其它值 `@PostConstruct` 拒绝；持有哨兵发现日志的两个旋钮。
- `RedisClientConfig`：自定义 `LettuceConnectionFactory` bean 顶掉 Boot 自动装配，按 `app.redis.mode` 构建 standalone 或 sentinel 配置；sentinel 分支只读 `spring.data.redis.sentinel.master/nodes/password` 与 `spring.data.redis.password/database`，**从不读 host/port**；nodes 条目必须 `host:port`（端口不写死，缺端口即报错）。连接惰性，Redis/哨兵不可达不阻塞启动（Task 14 契约）。
- `SentinelMasterDiscoveryReporter`：仅 sentinel 模式注册的启动后证据器。守护线程上跑 `REDIS_DISCOVERY_REPORT_ATTEMPTS`（默认 12）次 `SENTINEL masters` 探测、间隔 `REDIS_DISCOVERY_REPORT_INTERVAL_MS`（默认 5000ms），**按主名过滤**返回的地址并在地址变化时打日志；全部异常吞掉记 WARN，绝不影响启动。

## 2. `SeckillRedissonConfig` 双模式改造 + `SeckillProperties` 旋钮

- 拆出纯函数 `buildConfig(...)`（测试可断言装配结果而不开 socket）：`standalone` 保持原 `useSingleServer()`；`sentinel` 用 `useSentinelServers()`，`masterName` 来自 `spring.data.redis.sentinel.master`，哨兵地址来自 `...sentinel.nodes`（补 `redis://` 前缀），口令分层：数据节点用 `REDIS_PASSWORD`、哨兵端口用 `sentinel.password`（本拓扑未鉴权故为空）。缺主名/缺列表抛错，**绝不退回单点地址**。
- `SeckillProperties.Redisson` 新增 8 个外置字段并纳入校验（正数、minIdle≤poolSize、`readMode∈{MASTER,MASTER_SLAVE,SLAVE}`）。

## 3. `application.yml`（全部 `${ENV:default}`）

- `app.redis.mode=${REDIS_MODE:standalone}` + 发现日志两旋钮；`spring.data.redis` 补 `database: ${REDIS_DATABASE:0}`、`password: ${REDIS_PASSWORD:}`（standalone 默认空/0，行为不变）。
- Redisson sentinel 旋钮：`SECKILL_REDISSON_IDLE_CONNECTION_TIMEOUT_MS:10000`、`SECKILL_REDISSON_SCAN_INTERVAL_MS:1000`、`SECKILL_REDISSON_READ_MODE:MASTER`、`SECKILL_REDISSON_MASTER_POOL_SIZE/_MIN_IDLE`、`SECKILL_REDISSON_SLAVE_POOL_SIZE/_MIN_IDLE`、`SECKILL_REDISSON_SUBSCRIPTION_POOL_SIZE`——默认全部经嵌套占位符回落既有 `SECKILL_REDISSON_POOL_SIZE:8`/`MIN_IDLE:2`，standalone 行为零变化。
- **刻意不在 yaml 声明 `spring.data.redis.sentinel.*` 空默认**：Boot 的 `RedisConnectionConfiguration` 构造期就 `determineMode()`，空主名会让每个启动（含全部 `@SpringBootTest`）以 "Sentinel Master Id must not be null or empty" 失败（实测）。这三个键只由 `compose.ha.yml` 注入，dev 渲染中完全不存在（`RedisModeSwitchTest` 回归守卫 + compose 渲染计数 0 双重钉住）。

## 4. `compose.ha.yml` app 叠加层 + `.env.example`

- `x-app-redis-ha-env` 锚点 union 进 `app-1/2/3`：`REDIS_MODE=sentinel`、`SPRING_DATA_REDIS_SENTINEL_MASTER=${REDIS_HA_MASTER_NAME:-mymaster}`、`SPRING_DATA_REDIS_SENTINEL_NODES=${REDIS_SENTINEL_NODES:-…（由 REDIS_HA_SENTINEL_PORT 组装）…}`、`SPRING_DATA_REDIS_SENTINEL_PASSWORD=${REDIS_HA_SENTINEL_PASSWORD:-}`、`REDIS_PASSWORD=${REDIS_HA_PASSWORD:-}`、`REDIS_DATABASE`。
- **网络决定（24a 契约第 4 点的落地）**：哨兵回给客户端的主地址是数据节点 pinned `jl-sentinel-net` IP（172.28.190.0/24），只挂 `jl-net` 的 app 路由不到。三方案里选了 **ha 渲染下把 app-1/2/3 附加 `jl-sentinel-net`**：(a) 改 base `jl-net` 为固定子网被 24a 禁止（会重建整个 dev 栈）；(b) announce-hostnames 会让哨兵在 `docker stop` 时因 DNS 名消失进 `#tilt`；(c) 附加网络只重建三个 app 容器（模式切换本来就要重建），默认 dev 渲染逐字节不变（服务数 16、无新键，实测）。
- `.env.example` 新增运维键 `REDIS_SENTINEL_NODES`（默认 `sentinel-1:26379,sentinel-2:26379,sentinel-3:26379`）及全旋钮文档，无密钥。

## 5. 新增测试（3 个类、23 个用例，全部无需活体 Redis/哨兵）

- `RedisModeSwitchTest`（11）：以真实 `application.yml`+模拟 systemEnvironment 源绑定两模式；模式（而非哨兵键存在与否）选择客户端类型；半配置 sentinel 直接失败且**两栈都拒绝**；portless 条目报错；池参数回落链；上下文级 reporter 仅 sentinel 存在；standalone 工厂创建不开网络。
- `SeckillRedissonModeTest`（5）：`Config` 装配层面钉 `sentinelServersConfig` 全字段与 standalone 不变。
- `SentinelMasterDiscoveryReporterTest`（7）：mock 哨兵连接断言按名过滤、地址变化逐条记录、探测预算来自配置、连接逐次关闭、死哨兵只 WARN 不抛。

# 设计逻辑

- **一个开关、两条栈**：`REDIS_MODE` 是唯一分支点，Lettuce 与 Redisson 读同一 `RedisAccessProperties`，杜绝"半切换"（一半走哨兵一半钉单点）这种教学上最混乱的状态。测试分别从"模式是选择器"与"键的存在不是选择器"两个方向钉死。
- **失败快、降级软**：配置层面（sentinel 模式缺主名/列表→启动失败）拒绝静默回写死地址；运行层面（连接惰性、发现日志走守护线程且吞异常、Redisson lazy-init）保证 Redis/哨兵全灭时应用仍能起、能降级读 MySQL——`RedisOutageCacheIT` 契约未受影响。
- **端点归属既有键空间**：不新造平行地址/端口键；standalone 继续 `spring.data.redis.host/port`（Testcontainers 动态属性照常生效），sentinel 继续 Boot 标准 `spring.data.redis.sentinel.*`，lab 友好名（`REDIS_HA_MASTER_NAME`/`REDIS_SENTINEL_NODES`）→ 标准 env（`SPRING_DATA_REDIS_SENTINEL_*`）的映射只发生在 compose 层。
- **发现证据放在哨兵连接上**：reporter 用 `getSentinelConnection().masters()`（RESP `SENTINEL masters`）而非数据连接——切换窗口内恰好是这条路径必须应答；严格主名过滤，异名地址不认，`-`/地址无效记 WARN。观测窗（12×5s）内地址变化会打印，为 #49 场景日志面板留钩子；窗口参数外置可不改码扩大。
- **`readMode` 默认 MASTER**：与单机语义强一致，演示"读写都跟主走"；`MASTER_SLAVE/SLAVE` 读扩展变体留给 #49/后续场景 env 切换。

# 存在缺陷项&TODO

- **自定义工厂绕过 Boot 的 Lettuce 客户端调参（评审已修复一半）**：`new LettuceConnectionFactory(RedisConfiguration)` 型构造器不带 `LettuceClientConfiguration`，`spring.data.redis.timeout` / `connect-timeout` / `lettuce.shutdown-timeout` 曾会**静默失效**——违反工程规约 §1（禁死旋钮）且直接挡路 #49"显式设 Lettuce 超时出错误率毛刺"的场景。评审修复后这三个键**按设置生效**（未设置时仍走 Lettuce 默认，与本步演练实测逐字节一致，`RedisModeSwitchTest.bootLettuceTimeoutKeysReachTheClientConfiguration` 钉住两模式）；`spring.data.redis.lettuce.pool.*` 仍未接管（需 commons-pool2 在 classpath，超出本步）。见文末 Fix Report。
- **Lettuce 哨兵工厂仍暴露残留 vestigial standalone 配置对象**（框架行为）："不回落写死地址"的断言因此打在激活侧（`isRedisSentinelAware()`/`getSentinelConfiguration()`），测试注释已说明。
- **演练窗口内 2 次请求超时**（本步实测）：停主后 +2.2s/+5.9s 两个 L1-miss 请求在 3s curl 上限内未返回（HTTP 000），+9.5s 恢复 200。Lettuce 命令默认超时远大于请求预算，读路径在 failover 期表现为"个别请求挂起后成功/降级"，不是快失败。#49 若要出"错误率毛刺"曲线需显式设置 Lettuce timeout（见上条），或按当前实测口径讲"用户几乎无感"。
- **L1 Caffeine 60s TTL（`TwoLevelCache` 常量，M1-3 遗留）会掩盖 L2 切换**：演练需轮换商品 id 或等 L1 过期才能观测 L2 行为；违反工程规约第 1 节的存量项，归 #49/M4-2 处理。
- **哨兵全组宕机 + `REDIS_MODE=sentinel` 的活体降级未演练**：仅有 mock 断言（`deadSentinelsNeverThrow…`）与 Redisson lazy-init；活体证据留给 #49。
- 前两任 agent 报告的两轮演练：**无日志、无时间线留档，视为未验证**；本步只认附录中这一次留证演练。
- `jl-redis-*-exporter` 仍未进 Prometheus 抓取（24a 遗留，#49 出图前处理）。

# 待定事项和注意事项

## 给 #49（Sentinel 故障目标白名单与切换场景验收）的交接契约

1. **驱动切换的合法入口**：应用与拓扑都已就位，场景可直接 `docker stop jl-redis-<当前主>`——但"当前主"会漂移（本演练后主是 `jl-redis-1`），chaos-controller/场景编排**必须先查 `get-master-addr-by-name` 或服务名映射再决定停哪个容器**，不得钉死 `redis-1`。`sentinel-status.sh` 是只读查询工具。
2. **故障目标白名单**：仅 `jl-redis-1/2/3`、`jl-sentinel-1/2/3` 允许进入 chaos 白名单；**数据/存储容器之外的红线不变**——`jl-mysql`、`jl-rabbitmq`、单机 `jl-redis`、观测栈永不可作故障目标；白名单落码时属 chaos-controller 侧改动（本步未动 `code/services/chaos-controller/**`）。
3. **场景可调的 env 面**（全部已外置）：`REDIS_MODE`（模式）、`REDIS_DISCOVERY_REPORT_ATTEMPTS/_INTERVAL_MS`（扩大观测窗以在日志面板捕获 `+switch-master` 后的地址变化——默认窗 60s，建议场景取 24×5s）、`SECKILL_REDISSON_READ_MODE`（MASTER↔MASTER_SLAVE 对比读扩展）、各池/超时键、`REDIS_HA_DOWN_AFTER_MS`（切换灵敏度）。
4. **验收口径建议**：以"应用日志出现 `master … has been changed to …` 且免重启、读恢复秒数实测（本步基线：首次失败到恢复 200 ≈ **7.2s**；Redisson 重挂 +7.8s、Lettuce 重连 +11.6s）"为切换成功判据；异步复制丢写教学点需 `docker kill` 才能稳定复现（24a 已注明，勿用 stop 包装）。
5. **进入/退出 sentinel 演示的操作面**：`bash code/lab/scripts/up.sh --profile ha` 进入、`--profile dev`（或本步用的 `docker compose … up -d app-1 app-2 app-3`，不带 ha 文件）还原；两态的 compose 渲染差异已文档化（16→25、app 双网）。

## 运维与安全注意

- **默认态已还原**：三 app 现跑 standalone（`jl-redis` 单机），与 M4-1 之后的演示口径一致；ha 拓扑仅作为 profile 叠加存在。
- 手工演练仅 `jl-redis-*`/`jl-sentinel-*` 可动；**绝不 `rm`、绝不删卷、绝不 `--purge`**；`LAB_TOKEN`/`REDIS_HA_PASSWORD` 只从 `.env` 读、不打印、不落盘（本步命令均未回显密钥）。
- 应用**在 ha 渲染下连的是 HA 组**、默认渲染下连单机 `jl-redis`——两套缓存数据互不相通，对比演示前先确认 `Redis access mode` 日志行。
- 本步改动面：`seckill-app-jdk21` 主/测试代码、`application.yml`、`compose.ha.yml`（app 叠加层）、`.env.example`；未动 `chaos-controller`、k6、dashboard、`verify.sh`、`阶段交接-2026-10-26.md`；`.env`、`dist/`、`node_modules/`、`reports/`、`.superpowers/`、`.r2c/` 未提交。

# 附录

## 关键文件

- 应用：`code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/redis/`（3 类）、`.../admission/config/SeckillRedissonConfig.java`、`.../admission/config/SeckillProperties.java`、`.../src/main/resources/application.yml`
- Compose/env：`code/lab/docker/compose.ha.yml`（`x-app-redis-ha-env` + app 三服务叠加）、`code/lab/docker/.env.example`
- 测试：`.../src/test/java/com/javalearn/lab/seckill/redis/RedisModeSwitchTest.java`、`SentinelMasterDiscoveryReporterTest.java`、`.../admission/config/SeckillRedissonModeTest.java`

## 复现命令

```bash
cd /d/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-966ac9a0

# 单测/集成测（-am 必带：-Dtest=X 与 -am 同用会因兄弟模块无匹配而失败）
export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1"
(cd code && mvn -pl services/seckill-app-jdk21 -am test)

# 进入 sentinel 演示态（app 重建为 ha 渲染：REDIS_MODE=sentinel + 双网）
bash code/lab/scripts/up.sh --profile ha
docker logs jl-app-1 2>&1 | grep -E 'Redis access mode|discovered master'

# 手工读演练（仅动 jl-redis-*）：停当前主 → curl /api/products/<轮换id> 记状态
bash code/lab/scripts/sentinel-status.sh --watch 2 -n 15
docker stop <当前主容器>; docker start <它>   # 应复位为 slave

# 还原默认演示态（standalone）
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  -f code/lab/docker/compose.dev.yml up -d app-1 app-2 app-3

bash code/lab/scripts/verify.sh   # 期望 103 PASS / 1 WARN（ha 在跑时）
bash code/lab/scripts/smoke.sh    # 期望 7/7
```

## 证据（本步实际输出）

- `mvn`：`Tests run: 215, Failures: 0, Errors: 0, Skipped: 0`、`seckill-app-jdk21 ... SUCCESS [01:29 min]`、`BUILD SUCCESS`；`ProductCacheIT` 5/5（2.089s）、`RedisOutageCacheIT` 3/3（3.400s）。
- 主发现日志（三实例同形，重建后各自首条探测）：`Sentinel discovered master 'mymaster' at 172.28.190.12:6379 (probe 1/12, sentinels [sentinel-1:26379, sentinel-2:26379, sentinel-3:26379])`（app-1/2/3，2026-10-26T16:53:24Z）。
- 演练时间线（UTC，T0=17:15:57.25 停 `jl-redis-2`，读循环 20 商品 id 轮换、~0.6s/次）：`+2.25s` HTTP **000**（超时，id=10）、`+5.87s` **000**（id=11）、`+9.48s` 起全部 **200** 直至循环结束（停→恢复 **≈9.5s**，距首个失败 **7.2s**）；`17:16:03.640 +sdown`、`17:16:04.548 +promoted-slave 172.28.190.11`、`17:16:05.027 +switch-master mymaster 172.28.190.12 → 172.28.190.11`；应用免重启证据：三实例 `17:16:05.2x master 172.28.190.12 ... has been changed to 172.28.190.11`（Redisson）与 `17:16:08.8x Reconnected to 172.28.190.11:6379`（Lettuce），期间容器未重建（`Up 24 minutes (healthy)`）。复位：`docker start jl-redis-2` → 15s 后 `role=slave master_host=172.28.190.11 master_link_status=up`。
- 还原后：三 app `Redis access mode: standalone`、env 无 `REDIS_MODE`/哨兵键、单网 `jl-net`；`verify.sh` 末尾 `VERIFY PASSED: 103 checks passed, 1 warning(s).`；`smoke.sh` `Smoke checks: 7 passed, 0 failed. / SMOKE PASSED.`。
- compose 不变式：默认 `config --services` = 16、渲染中哨兵键计数 0；`--profile ha` = 25、三 app 各带 `REDIS_MODE: sentinel` 且 `jl-sentinel-net` 生效。

## Fix Report（独立评审，2026-10-27）

评审批次：`1aceafa` + `0cd6298` + `350a2ac`。基线复跑确认：`mvn -pl services/seckill-app-jdk21 -am test`（JDK 21）→ **Tests run: 215, Failures: 0, Errors: 0, Skipped: 0**（surefire 39 个类聚合，含 `ProductCacheIT`/`RedisOutageCacheIT`）。八条审核轴中 1/2/3/5/6/7/8 通过（第 6 轴：应用配置中无任何固定 IP；failover 后新主地址同为 `jl-sentinel-net` pinned IP，app 双网后对任一主都可达，机制健壮）。评审修复两项：

1. **Important——自定义工厂吞掉 Boot 的 Lettuce 超时键**（上文缺陷项第 1 条，按工程规约"发现后同一任务内修复"处理）：`RedisClientConfig` 现经类型化双参构造器传入 `LettuceClientConfiguration`，`spring.data.redis.timeout` 与 `lettuce.shutdown-timeout` **设置即生效**（两模式、含 `#49` 需要的命令超时）；未设置时保持框架默认，与本步演练实测逐字节一致（新测试 `bootLettuceTimeoutKeysReachTheClientConfiguration` 对 builder 默认值断言，不写 Java 字面量）。`connect-timeout`（Boot 在共享 ClientResources/SocketOptions 层注入）与 `lettuce.pool.*`（需 commons-pool2）仍不接管，已在 javadoc 与本文缺陷项如实标注。
2. **Minor——compose 嵌套插值静默产端口缺失**：实测 `${REDIS_SENTINEL_NODES:-sentinel-1:${REDIS_HA_SENTINEL_PORT}...}` 在该键未设时渲染为 `sentinel-1:,sentinel-2:,sentinel-3:`（compose 对未定义变量代空串且**不报渲染错误**，靠 app 启动期 host:port 校验兜底）。内层补 `:-26379` 后三种 env 状态（两键全缺 / `REDIS_SENTINEL_NODES` 置空 / 完整 `.env.example`）渲染均为 `sentinel-1:26379,...`。

复验输出（评审者在独立 worktree 实跑）：修复后 `mvn -pl services/seckill-app-jdk21 -am test` → **Tests run: 216, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS**；compose 不变式复跑：dev 渲染 **16** 服务 / 哨兵键 **0**、`--profile ha` **25** 服务、app 双网与 `REDIS_MODE: sentinel` 不变。评审无法在本机复核的存量声明：9.5s 演练时间线与 `Sentinel discovered master` 日志（app 容器已按交接还原为 standalone，旧日志随重建消失；但 `docker logs jl-app-1` 现值 `Redis access mode: standalone` 与单网 `jl-net` 与"已还原"声明一致）、`verify.sh` 103/1 与 `smoke.sh` 7/7（评审禁跑脚本）。
