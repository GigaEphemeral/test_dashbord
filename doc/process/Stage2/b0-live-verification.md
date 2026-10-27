# Stage2 B0 收口 · 真机验证记录（R1）

> 执行代理：R1（Stage2 B0 收口真机验证）
> 日期：2026-10-28
> 分支 / 开工 HEAD：`feature/stage2-workbench` @ `4dd4165`（`git status --short` 干净，34 容器在跑）
> 范围：按 `doc/design/安装与打包手册.md` §5.2/§8 正式口径重建应用层镜像到 B0 后代码并显式 `--force-recreate` 替换应用层；逐条做掉 B0-02/03/04/05/08 登记的 unverified 真机项 + 全量 verify.sh 闸门。
> 纪律：数据面指向不变（`MYSQL_MODE`/`REDIS_MODE` 与原容器一致）；不动 `compose*.yml`/`prometheus.yml`/Java 代码；不 down 栈；不杀数据层容器；改 env 走 `.env`。

## 0. 开工自检

| 检查 | 命令 | 结果 |
|---|---|---|
| 分支/HEAD | `git rev-parse --abbrev-ref HEAD` / `--short HEAD` | `feature/stage2-workbench` / `4dd4165` |
| 工作树 | `git status --short` | 空（干净） |
| 栈规模 | `docker ps -q \| wc -l` | 34 |

## 1. 应用层镜像重建 + `--force-recreate`（B0-08 真机项）

### 1.1 构建前实况（证据 `/tmp/r1/pre-build.log`）

```
java-learn-lab/seckill-app:jdk21          cfb7a164e9b3  7 hours ago
java-learn-lab/chaos-controller:jdk21     409a4e944856  7 hours ago
jl-app-1/2/3      -> sha256:cfb7a164e9b3...   （旧镜像）
jl-chaos-controller -> sha256:9ac92f049bc7... （= :jdk21-pre-docs，Stage1 期镜像）
```

最后一个 Java 代码变更提交 `6a41c7c`（2026-10-28 01:12，B0-04 合并）晚于镜像构建时间 ⇒ 运行镜像确实落后于 B0 代码；`jl-chaos-controller` 更是跑在 `:jdk21-pre-docs`（连 7 小时前的 `:jdk21` 都不是）。结论：**确需重建 + 显式 recreate**，与任务前置事实一致。

回滚位（构建前打的备份 tag）：
`java-learn-lab/seckill-app:jdk21-pre-b0r1` → `cfb7a164e9b3`、
`java-learn-lab/chaos-controller:jdk21-pre-b0r1` → `409a4e944856`；
另仓库内已有 `:jdk21-pre-docs`（`694d4586c398`）/`chaos-controller:jdk21-pre-docs`（`9ac92f049bc7`）。

### 1.2 构建命令（手册 §5.2 原文口径，实跑）

```bash
time docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env \
  -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml \
  build app-1 chaos-controller
```

关键输出（`/tmp/r1/build.log`，EXIT=0）：

```
#19 [app-1 build 6/7] RUN mvn -q -pl services/seckill-app-jdk21 -am -DskipTests package   DONE
#24 [app-1 build 7/7] RUN curl -fsSL ... opentelemetry-javaagent-2.10.0.jar && test -s      DONE 3.5s
 Image java-learn-lab/seckill-app:jdk21 Built
 Image java-learn-lab/chaos-controller:jdk21 Built
real    3m7.464s
```

产物（与手册记录的 3m37s 冷构建同量级；本轮 `COPY pom.xml/platform` 命中层缓存，只有 `COPY services` 之后重跑）：

| 镜像 | 新 image ID |
|---|---|
| `java-learn-lab/seckill-app:jdk21` | `5b18a49d504c` |
| `java-learn-lab/chaos-controller:jdk21` | `f83b32f914a6` |

### 1.3 recreate 命令（手册 §8「重建单个服务（不动数据容器）」口径 + B0-08 `--force-recreate` 真机项）

```bash
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env \
  -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml \
  up -d --no-deps --force-recreate --wait --wait-timeout 240 \
  app-1 app-2 app-3 chaos-controller
```

输出要点（`/tmp/r1/recreate.log`）：`Container jl-app-1/2/3 Recreated` → `Started` → `Healthy`；`jl-chaos-controller Started/Healthy`。**首跑 EXIT=1**：`--wait` 在 `container jl-app-3 is unhealthy` 时返回——回查 `docker inspect jl-app-3 .State.Health.Log` 末三条为 `503 → exit 0 → exit 0`，即等待窗口正好落在 start_period 边界的一次 503 上，数秒后转 healthy；属**等待时序抖动，非实现缺陷**（健康门是 `/actuator/health/app`，`--wait` 无额外裕量）。

复核实况（`/tmp/r1/post-recreate.log`）：

```
jl-app-1/2/3        -> sha256:5b18a49d504c...  Up (healthy)   ← 与 :jdk21 新指向一致
jl-chaos-controller -> sha256:f83b32f914a6...  Up (healthy)
docker ps -q | wc -l = 34                     ← 未增删容器
```

数据面指向逐项比对（与 1.1 重建前 `docker inspect jl-app-1` 一致，无回退）：

```
MYSQL_MODE=standalone          REDIS_HOST=redis  REDIS_PORT=6379   MYSQL_HOST=mysql
DB_WRITE_URL=jdbc:mysql://jl-mysql-router:6446/seckill?...
DB_READ_URL=jdbc:mysql://jl-mysql-router:6447/seckill?...
LAB_TOGGLE_{LOCK,IDEMPOTENCY,MQ,RATELIMIT,CIRCUITBREAKER,CACHE}=true
```

> 注：`REDIS_MODE` 在重建前后的 app 容器 env 中均**不存在**（当前栈是 base+dev 组装，Sentinel 的 `x-app-redis-ha-env` 未叠加）；`container_name`/`config_files` 标签仍为 `compose.base.yml,compose.dev.yml`，与重建前一致。`.env` 全程未改。

新代码确实生效的旁证（重建后立即取 `GET jl-app-1:8080/lab/config`，经一次性 curl 容器）：响应 `data` 键为
`[app, env, instance, jvmVersion, toggles, cacheConfig]`，其中 **`cacheConfig` 有 59 条**、每条带
`configKey/env/value/defaultValue/source`，`source` 全为 `systemEnvironment`——`/lab/config` 的 `cacheConfig`
区块正是 B0-04（`de9e2fc`）才有的接口，旧镜像不会有该字段。

**结论：PASS**（镜像重建 + 显式 `--force-recreate` 真机跑通；`--wait` 的 503 抖动已记录并复核为健康）。

## 2. B0-02 `stock_oversold_total` 真机样本

现场：一次性 `curlimages/curl:8.10.1` 容器挂 `jl-net`（app 镜像无 curl，见手册 §4.2），脚本
`/tmp/r1/b002.sh` 只读挂载进 `/work`；`--entrypoint sh /work/b002.sh jl-app-1 10`。
证据：`/tmp/r1/b002-baseline.log`、`/tmp/r1/b002-burst.log`、`/tmp/r1/b002-after.log`、`/tmp/r1/b002-revert.log`。

1. **关 `lock` 开关（POST /lab/toggles，app 侧）** — `{"key":"lock","value":false}` 返回
   `{"lock":false,...,"cache":true}`，随后 GET 复核同为 false。
2. **制造库存拒绝**（product 10 当时 `available=100`）：
   - 单次超量：`quantity=100000` → `{"code":"40901","message":"Sold out"}` / `HTTP=409`；
   - 12 并发 `quantity=50` → HTTP 码直方图 `2 × 200`、`10 × 409`。
     合计「数据库防线挡下」应为 1 + 10 = **11**。
3. **Prometheus 即时查询**（经 Nginx `/prom/`）：

   ```
   stock_oversold_total                       app-1 = 11   app-2 = 0  app-3 = 0
   increase(stock_oversold_total[5m])         app-1 = 11.141345032412053  app-2/3 = 0
   sum(increase(stock_oversold_total[5m]))    = 11.14522362960753          (> 0 ✓)
   ```

   `increase()` 的 11.14 是 Prometheus 对采样边界的外推（计数真实值 11），不是多计。
   基线（打流量前）三条序列已在、值为 0，验证了 B0-02「急切注册 ⇒ 零值序列先于流量存在」。
4. **订单表未超卖**（`docker exec jl-mysql mysql -N ... seckill`，`MYSQL_MODE=standalone` 的数据面即 dev 单节点）：

   ```
   select product_id,available from inventory_stock where product_id=10;   -> 10  0
   select count(*),sum(quantity) from order_order where product_id=10 and user_id='r1-oversold';  -> 2  100
   select min(available) from inventory_stock;                              -> 0
   select count(*) from inventory_stock where available<0;                  -> 0
   ```

   售出 100 = 初始 `available` 100，`available` 落在 0 且全表无负值 ⇒ 条件 UPDATE 确实把 11 次超卖挡在数据库之前。

### 现场复位

- `POST /lab/toggles {"key":"lock","value":true}` 对 app-1/2/3 各一次，三台 GET 复核均为
  `{lock:true, idempotency:true, mq:true, ratelimit:true, circuitbreaker:true, cache:true}`（默认态）。
- 测试数据回收：`delete from order_order where user_id='r1-oversold'`（残留 0 行）、
  `update inventory_stock set available=100 where product_id=10`（回到测试前值）。

### 逐项结论

| 步骤文档登记的项 | 结果 |
|---|---|
| `/actuator/prometheus` 导出 `stock_oversold_total` 名称不变 | PASS（Prometheus 端序列名即 `stock_oversold_total`，标签 `exported_instance`=app-1/2/3、`instance`=app-N:8080） |
| 手工制造库存不足 → 样本计数 +1 | PASS（挡下 11 次 → 计数 11，`increase[5m]` = 11.14 > 0） |
| 同步/异步各一次阻止不双计（样本层面） | 部分——本轮只造了同步 `POST /api/orders` 样本（清单口径即此）；异步消费路径未单独造样本，单测层已证（用例级各 +1） |

**结论：PASS**


## 3. B0-03 Caffeine L1 统计序列

驱动：一次性 curl 容器脚本 `/tmp/r1/reads.sh`（`GET /api/products/1..20`，每台 3 轮 = 60 次读，直连
`jl-app-N:8080` 以免受 Nginx 上游缓存影响）。证据：`/tmp/r1/b003-baseline.log`、
`b003-round1.log`、`b003-round3.log`、`b003-off.log`、`b003-restore-check.log`。

### 3.1 三实例真序列 + 数值随读写变化

打流量前（基线）：`count by (instance) (cache_l1_caffeine_requests_total)` = **3**（app-1/2/3 各 2 条
`result` 序列），`cache_l1_estimated_size` = 3 条、值 0，`_hit_ratio` = 1（无读时的 Caffeine 约定值）。

读写 3 轮 ×20 商品后：

| 序列 | app-1 | app-2 | app-3 |
|---|---|---|---|
| `cache_l1_caffeine_requests_total{result="hit"}` | 120 | 100 | 100 |
| `cache_l1_caffeine_requests_total{result="miss"}` | 20 | 40 | 40 |
| `cache_l1_estimated_size{cache="product"}` | 20 → 0 | 20 | 20 |
| `cache_l1_caffeine_hit_ratio` | 0.857 | 0.714 | 0.714 |
| `cache_l1_caffeine_evictions_total` | 0 → 20 | 0 → 20 | 0 |
| `increase(cache_l1_caffeine_evictions_total[5m])` | **20.011** | 0 | — |

- 标签核对：Java 侧只有 `cache="product"`（requests 另带 `result`）；Prometheus 端为
  `instance="app-N:8080"`（抓取目标）+ `exported_instance="app-N"`（relabel），与 B0-03「标签纪律」一致。
- 双视图口径核对：同窗口 `cache_l1_requests_total`（应用口径）app-1 为 hit 60/miss 20，
  Caffeine 口径为 hit 120/miss 20 —— 一次 Spring-cache 命中触发两次 Caffeine 读，正是设计逻辑预期。
- `estimated_size` 随 `expireAfterWrite=60s` 归零、`evictions_total` 同步 +20，说明是活值而非常量。

### 3.2 `LAB_CACHE_L1_MICROMETER_STATS_ENABLED=false` 重启一台 ⇒ 序列消失（缺失≠0）

做法（不动 `.env`，走 compose 既有占位 `${LAB_CACHE_L1_MICROMETER_STATS_ENABLED:-true}` 由 shell 注入，
只 recreate app-3）：

```bash
LAB_CACHE_L1_MICROMETER_STATS_ENABLED=false docker compose ... up -d --no-deps --force-recreate app-3
docker inspect jl-app-3 ... | grep MICROMETER_STATS   # -> =false
# 等过 Prometheus 5m lookback 再判缺失（重启 17:51:56Z，查询 17:57:24Z，间隔 5m28s）
```

| 查询 / 观测 | 结果 |
|---|---|
| `count by (instance) (cache_l1_caffeine_requests_total)` | **2**（仅 app-1、app-2）——app-3 序列消失 |
| `count by (instance) (cache_l1_estimated_size)` | **2** |
| `absent(cache_l1_estimated_size{exported_instance="app-3"})` | **1**（确证「无序列」而非「值为 0」） |
| `cache_l1_requests_total`（旧应用口径） | 6 条，app-3 仍在（hit 0/miss 0）⇒ 只关本步 5 条 caffeine 序列，旧序列不受影响 |
| app-3 原始抓取 `/actuator/prometheus` | `cache_l1_caffeine*` / `cache_l1_estimated_size` 匹配行数 **0**；app-1 同一抓取 **13** 行 |

复位：不带覆盖变量再 `--force-recreate app-3`，`docker inspect` 读回 `=true`；两个
`count by (instance)` 查询均回到 **3**（见 §8 现场复位）。

**结论：PASS**

## 4. B0-04 有效值回读（value + source 三处一致）

现场：把 `LAB_CACHE_L2_TTL_SECONDS` 从默认 300 改为 **45** 后只 recreate app-2；
驱动脚本 `/tmp/r1/b004.sh`（一次性 curl 容器）；Redis 侧 `docker exec jl-redis redis-cli`。
证据：`/tmp/r1/b004-readback.log`、`b004-redis.log`、`b004-envcount.log`、`b004-restore.log`、`b003-restore-check.log`。

### 4.1 `GET /lab/config` 的 value + source

```
configKey=app.lab.cache.l2.ttl-seconds  env=LAB_CACHE_L2_TTL_SECONDS
value=45   defaultValue=300   source=systemEnvironment          ← B0-04 验收原句
configKey=app.lab.cache.l2.key-prefix      value=cache:  defaultValue=cache:  source=systemEnvironment
configKey=app.lab.cache.l2.clear-scan-count value=500   defaultValue=500     source=systemEnvironment
```

### 4.2 compose 59 个变量确实下发到 app 容器（本步登记的最后一项 unverified）

```
docker exec jl-app-{1,2,3} sh -c 'env | grep -c "^LAB_CACHE_"'   ->  59 / 59 / 59
```

对照：§1 重建**前**的 app-1 env 里 `LAB_CACHE_*` 出现 **0** 次（旧容器起在 compose 增键之前）——
「改了 compose/env 但容器没跟上」正是手册避坑 19；本轮 recreate 消除了它。

### 4.3 三处一致（config 回读 = Redis 单键 TTL = state 视图）

| 观测点 | 值 |
|---|---|
| `GET app-2:/lab/config` → `l2.ttl-seconds` | 45 |
| 常规读写路径新写键 `redis-cli TTL cache:product:90123` | **41**（≤45，写入后已过约 4s，同一 TTL） |
| `GET app-2:/lab/cache/state` → `keys.l2ExperimentKeys` | **201** |
| `redis-cli --scan --pattern 'cache:product:9*' \| wc -l` | **201** |
| `state.keys.redisReachable` / `hotKeyCached` | true / true |

### 4.4 播种热键 TTL 不随 L2 TTL 变（口径隔离反证）

同一台 app-2（L2 TTL=45）执行 `POST /lab/cache/seed` 后：

```
seed 响应: hotKeyProductId=90999, hotKeyWritten=true, avalancheKeysWritten=200, avalancheTtlSeconds=60
redis-cli TTL cache:product:90999  ->  280   ← 播种口径 LAB_CACHE_SEED_HOT_KEY_TTL_SECONDS=300，未被 45 带走
redis-cli TTL cache:product:90001  ->  39    ← 播种雪崩键 TTL≈60（seed 后约 21s 查询），同样独立于 L2 TTL
```

代码侧对得上：`CacheExperimentDataService.warmHotKey(hotKeyId, Duration.ofSeconds(seed.getHotKeyTtlSeconds()))`
用的是 seed 自己的 TTL，与 `TwoLevelCache` 的 L2 写 TTL 不同源。

### 4.5 复位

不带覆盖变量 recreate app-2 → `docker inspect` 读回 `LAB_CACHE_L2_TTL_SECONDS=300`，
`GET /lab/config` 回读 `value=300 defaultValue=300 source=systemEnvironment`；随后 `nginx -s reload`。

### 逐项结论

| 步骤文档登记的项 | 结果 |
|---|---|
| `LAB_CACHE_L2_TTL_SECONDS=45` 后 `/lab/config` value=45 且 source=systemEnvironment | PASS |
| `/lab/cache/state` 键数 + 单键 `TTL` 与回读值三者一致 | PASS（201=201；45↔41） |
| 播种热键 TTL 不随 L2 TTL 变化 | PASS（280≈300） |
| `LAB_CACHE_L2_KEY_PREFIX` 改值后 state 视图不出现「0 键」错位 | **未做**（本轮清单未列该项，且需再一整轮 recreate；单测已证前缀五方共用同一解析点） |
| compose 起栈后 59 个变量确实下发到 app 容器 | PASS（59/59/59） |

**结论：PASS**（前缀改值一条不在本轮真机范围，留给 B3/R2 顺手覆盖）

## 5. B0-05 query string 经 Nginx 透传

链路：`curl(宿主机) → 127.0.0.1:8080 jl-nginx /lab-api/ → jl-chaos-controller:8081 /chaos/app/<实例>/**
→ jl-app-N:8080 <白名单路径>?<query>`。控制器自带 `X-Lab-Token`（网关注入），所以宿主机侧要带
`X-Lab-Token: lab-token`。证据：`/tmp/r1/b005-positive.log`、`b005-positive2.log`、`b005-longquery.log`、
`b005-413.log`、`b005-400.log`、`b005-pipe.log`、`b005-tempo*.log`。

### 5.0 先踩到的环境坑（与 B0-05 无关，但必须记）

首轮全部 `HTTP=502`，nginx 错误日志是
`connect() failed (111: Connection refused) ... upstream: "http://172.19.0.22:8081/..."`：
`nginx` 的 `upstream`/`proxy_pass` 主机名在配置加载时解析一次并缓存，**recreate 应用层后 IP 变了而
Nginx 不知道**。`docker exec jl-nginx nginx -t && nginx -s reload` 立刻恢复（`up.sh` 末段本来就做这件事，
手册 §8 也写了「重建 app 层 + nginx -s reload」）。同因还解释了 §3 首轮 Nginx 流量集中打到 app-2/3、
app-1 计数为 0 的怪象（Nginx 静默 failover 到可用节点）。⇒ 本轮之后每次单独 recreate 应用层，
都紧跟一次 `nginx -s reload`。

### 5.1 正路：白名单路径 + query 透传成功（以 app 侧证据为证）

`GET /lab-api/chaos/app/app-1/lab/cache/state?tag=r1qmark7731&b=1%2B1&c=Zm9vYmFy%3D%3D` → `HTTP=200`
（app 的 `Result` JSON 信封，`defense/breakdown/avalanche/dataset/keys/bloom/nullCache` 全量）。

app 侧硬证据走 OpenTelemetry：app 容器由 javaagent 生成自己的 server span，其 `url.query` 属性即
app 实际收到的原始 query。Tempo（`jl-tempo:3200/api/search`，TraceQL）查得：

```
rootServiceName = seckill-app   rootTraceName = "GET /lab/cache/state"
span attributes: url.query = "tag=r1qmark7731&b=1%2B1&c=Zm9vYmFy%3D%3D"
```

与客户端发出的 query **逐字节相同**（`%2B`、`%3D%3D` 未被解码、未被重新编码、`&` 分段不变）——
这正是 B0-05「只透传、不解释」的口径，且证明旧缺陷（query 全丢）已消失：若仍丢弃，
`url.query` 会是空值、`rootTraceName` 也不会带这些参数。另附 Nginx 错误日志里的上游 URL
（`.../chaos/app/app-1/lab/cache/state?a=b&mode=view&tag=r1-query-test`）证明 client→nginx→controller
一跳同样带 query。`GET app-2 /lab/thread/state?...` 亦为 `HTTP=200`（`{"code":"0",...,"data":[]}`）。

### 5.2 负路 1：请求体超 `CHAOS_APP_PROXY_MAX_REQUEST_BYTES` → 413

9028 字节 body POST 到白名单带体路径 `/lab-api/chaos/app/app-1/lab/cache/mode`：

```
{"code":"41301","message":"Request body exceeds 8192 bytes"}   HTTP=413
```

同路径小 body 对照：`{"code":"0","data":{"mode":"off"}}` HTTP=200。
上限 8192 未在任何 compose/.env 里出现（`grep CHAOS_APP_PROXY code/lab/docker/.env*` 无匹配），
即 `application.yml` + `@ConfigurationProperties(chaos.app-proxy)` 的默认值生效，外置口径可验。
（对照的 `mode=off` 就是 `LAB_CACHE_DEFENSE_MODE` 默认值，无需复位。）

### 5.3 负路 2：非法 query → 400 且不出网

```
GET .../lab/cache/state?tag=r1bad8842&x=%zz
{"code":"40001","message":"Query string is not a valid URI query and cannot be forwarded verbatim"} HTTP=400
```

「不出网」用 app 侧遥测反证：`{span.url.query=~".*r1bad8842%zz.*"}` 在 Tempo 查得 `{"traces":[]}`
（同窗口同语法对 5.1 的 marker 能命中，说明查询本身有效）⇒ 该请求确实被网关挡在转发之前。

### 5.4 白名单/方法回归（顺带复核 B0-05 修正在真机成立）

```
GET /lab-api/chaos/app/app-1/lab/toggles?cursor=1&limit=20
  -> {"code":"40401","message":"Path is not in the app proxy allow-list: GET /lab/toggles"}  HTTP=404
DELETE /lab-api/chaos/app/app-1/lab/cache/state?x=1
  -> {"code":"40501","message":"Method 'DELETE' is not allowed; the proxy forwards GET and POST only"} HTTP=405
```

404 而非 405：与步骤文档「白名单外路径是 404/40401」的口径修正一致；且带 query 的白名单外路径
仍按 path 判定（query 不参与白名单）。

### 5.5 发现（不掩饰）：原始非法字符 `|` 的转义在真机入站路径不可达

```
GET /lab-api/chaos/app/app-1/lab/cache/state?tag=...&v=a|b          -> HTTP=400，Tomcat HTML 错误页
GET http://jl-chaos-controller:8081/chaos/app/...?tag=...&v=a|b      -> HTTP=400，同样 Tomcat HTML
GET .../lab/cache/state?tag=...&v=a%7Cb                              -> HTTP=200（app 正常应答）
```

现象：HTTP 400 由**控制器自身内嵌 Tomcat 在解析请求行时**发出（绕过 Nginx 直连 8081 同样 400，
Tempo 中亦无对应 app span），响应体是 Tomcat 的 HTML 而非 `Result` 信封。
初判：**不是 B0-05 的实现错**——单测 `HttpAppProxyGatewayTest` 断言的是「gateway 拿到含 `|` 的 query
时只做字节级转义再拼 URI」，这条内部逻辑没被违反；真实入站链路上 `|` 在上游（Tomcat）就被拒了，
所以转义分支只对「已合法编码的 query」或程序内调用生效。属**文档/测试口径与真机入站层次的落差**：
若控制台要给用户自由输入的 query，需知道未编码的 RFC3986 非法字符会拿到 400 HTML（非 `Result` 信封）。
建议由主会话决定是否在改造方案 §5.4 补一句口径说明；本轮未改代码、未改文档结论。

### 逐项结论

| 清单 / 步骤文档项 | 结果 |
|---|---|
| query string 经 Nginx 透传成功（app 响应/日志为证） | PASS（app span `url.query` 逐字节等于入参） |
| 超限路径 413（体 > `CHAOS_APP_PROXY_MAX_REQUEST_BYTES`） | PASS（413/41301，上限 8192） |
| 非法 query 400 | PASS（400/40001 且 Tempo 无 app span，证不出网） |
| 白名单只看 path（带 query 的白名单外路径 404） | PASS（404/40401） |

**结论：PASS**（另记 5.0 Nginx 需 reload 的操作坑与 5.5 `|` 入站 400 的口径落差）

## 6. B0-08a 镜像身份脚本与 bench 归档

（进行中）

## 7. 全量闸门 verify.sh

（进行中）

## 8. 现场复位与收尾

（进行中）
