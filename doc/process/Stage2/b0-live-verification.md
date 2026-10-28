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

## 6. B0-08a 镜像身份记录 + bench 归档

### 6.1 `image-identity.sh` 对重建后的栈跑一次

```bash
bash code/lab/scripts/image-identity.sh --out /tmp/r1/images.json \
     --containers "app-1 app-2 app-3 chaos-controller" --project java-learn-lab
# EXIT=0，逐台 (ok)：
#   Image identity: app-1 -> jl-app-1 tag=java-learn-lab/seckill-app:jdk21 id=sha256:5b18a49d504c…(ok)
#   ... app-2 / app-3 同一 id；chaos-controller -> jl-chaos-controller tag=…chaos-controller:jdk21 id=sha256:f83b32f914a6…(ok)
```

与 `docker inspect --format '{{.Image}}'` 逐台比对（证据 `/tmp/r1/imageidentity-run.log`）：

| 容器 | 脚本记录 imageId | `docker inspect` | 一致 |
|---|---|---|---|
| jl-app-1 / 2 / 3 | `sha256:5b18a49d504c…4ab2e` | 同 | ✓ |
| jl-chaos-controller | `sha256:f83b32f914a6…fcc3` | 同 | ✓ |

`resolvedBy` 全部为 `labAlias`（本栈每个服务都钉了 `container_name: jl-<service>`，按脚本的解析顺序
「真名 → `jl-` 别名 → compose service 标签」永远先命中别名）。⇒ **`com.docker.compose.service`
标签解析分支在本栈上真机不可达**，这条 unverified 只能由 `test-image-identity.sh` 覆盖，本轮如实记为未覆盖
（不是实现问题，是现栈命名方式使然）。

### 6.2 `bench.sh` 最小场景（smoke）

```bash
BENCH_DOC_DIR=/tmp/r1/bench-doc bash code/lab/scripts/bench.sh smoke --run-id r1-b0-smoke
# 关键输出：
#   Preflight: 16 compose service(s) running/healthy
#   Image identity recorded: code/lab/k6/reports/r1-b0-smoke/image-identity.json
#   Reset targets (resolved from the current stack topology):
#     declared modes : MYSQL_MODE=standalone REDIS_MODE=standalone
#     MySQL target : container=jl-mysql …   Redis targets : jl-redis (FLUSHDB)
#   BENCH_VERDICT=PASS   EXIT=0   duration 16s   checks rate=100% flow_errors 0% server_errors 0%
```

归档 `code/lab/k6/reports/r1-b0-smoke/`：`manifest.json`、`image-identity.json`、`summary.json`、
`k6.log`、`report.html`、`reset.log`（`code/lab/k6/reports/` 已 gitignore，不入库）。

- `manifest.json.images.containers[]` 含 4 台 `imageTag` + `imageId`，且与 6.1 之后、reset 重启应用层之后的
  `docker inspect --format '{{.Image}}'` 再次逐台比对一致（`jl-app-1` 创建时间 17:39:01Z、`jl-app-2`
  18:02:02Z、`jl-app-3` 17:58:16Z 各不相同，但镜像 digest 同为 `5b18a49d504c…`——归档记的是镜像身份，
  与「哪一次 recreate 起的容器」正交）。
- `manifest.json.git` = `9f0a36e` / `feature/stage2-workbench`，即代码身份也在档内。
- bench 自带的 reset-lab 顺带把数据面复位（订单清空、库存回 100、Redis FLUSHDB），
  并核对了 `MYSQL_MODE=standalone` / `REDIS_MODE=standalone` 与 §1 的重建前后一致（拓扑感知解析生效）。
- reset 停启应用层后 Nginx 上游健康：连续 6 次 `GET /api/products/3` 全 200，
  `docker logs jl-nginx` 近 3 分钟 `connect() failed` 计数 0（本轮无需再 reload）。

### 6.3 B0-08 其余 unverified 项（本轮范围外，留给 R2）

| 项 | 状态 |
|---|---|
| `--force-recreate` 实际重建行为 | **PASS**（§1，app 层三台 + 控制器真机重建） |
| 真机 bench/tune 端到端归档（tag/id 与运行容器一致） | **PASS**（§6.2；tune 未跑，只跑 bench） |
| 真机 `composeService` 标签解析路径 | 未覆盖（现栈命名使然，见 6.1） |
| db-failover 期间 `mysql_cluster_probe_up` 恒 1 | **未做**——需杀 MySQL 节点，属 R2 范围（本代理不杀数据层容器）。现场留给 R2。 |
| 探针自检开关 `failure-container-check-enabled` 真机表现 | 未做（同样需要杀/冻结探针目标节点，R2 范围） |

**结论：PASS**（镜像身份最小闭环在真机成立；两项依赖杀节点的留在 R2）

## 7. 全量闸门 verify.sh

```bash
bash code/lab/scripts/verify.sh > /tmp/verify-r1.log 2>&1; echo EXIT=$?
```

```
VERIFY PASSED: 109 checks passed, 1 warning(s).
Stack left running for debugging (default).
EXIT=0
WARN  cadvisor container series absent (Docker Desktop/WSL2 limitation; D3 CPU/memory/network panels will show No data)
FAIL  计数：0
```

与 B0 基线预期 **109 PASS / 1 WARN / 0 FAIL** 完全一致，唯一 WARN 仍是 cadvisor/WSL2 那条已知限制。
本轮跑在 §6.2 bench smoke（含 reset-lab）与 §1~§5 的多次应用层 recreate 之后，即验的是
「新镜像 + 新代码 + 复位后的现场」。

**结论：PASS**

## 8. 现场复位与收尾

终态复核（证据 `/tmp/r1/final-state.log`）：

| 检查 | 结果 |
|---|---|
| `docker ps -q \| wc -l` | **34**（与开工一致，未增删容器；数据层容器全程未动） |
| 应用层镜像 | jl-app-1/2/3 = `seckill-app:jdk21` → `sha256:5b18a49d504c…`；jl-chaos-controller = `chaos-controller:jdk21` → `sha256:f83b32f914a6…`（本轮新构建，未回滚） |
| 数据面指向 | `MYSQL_MODE=standalone`、`MYSQL_HOST=mysql`、`REDIS_HOST=redis`、`DB_{WRITE,READ}_URL` 与开工前逐字一致（未退回、未改指） |
| 改过的 env（全部复位） | `LAB_CACHE_L2_TTL_SECONDS=300`、`LAB_CACHE_L1_MICROMETER_STATS_ENABLED=true`（`docker inspect` + `/lab/config` 双重读回）；`.env` 文件全程未编辑，覆盖只走 shell env + compose 既有占位 |
| toggles | 三台 `GET /lab/toggles` 全默认 `{lock,idempotency,mq,ratelimit,circuitbreaker,cache}=true` |
| 一次性容器 | `docker ps -a --filter ancestor=curlimages/curl:8.10.1` = **0**（全部 `--rm`，用完即走；镜像留在本地供 R2 复用） |
| Nginx | 已 reload；`/api/products/*` 连续 200，`connect() failed` 计数 0 |
| 业务数据 | B0-02 造的 2 单已删、product 10 库存回写 100；随后 §6.2 bench smoke 的 reset-lab 把订单/库存/Redis 复位到 bench 基线（终态 `order_order` 4 行来自 smoke 自身流量，库存 100/98/98） |
| `jl-traffic` | `Exited (2) 4 hours ago`——开工前就存在的按需容器状态，本轮未启动、未改动 |
| 工作树 | `git status --short` 空 |

留给 R2 / 主会话的现场说明：

1. **本代理未做任何杀/冻结节点演练**，故这些 B0-08 真机项仍在册：db-failover 期间
   `mysql_cluster_probe_up` 恒 1、探针自检开关 `failure-container-check-enabled` 的真机表现。
   现栈 MySQL 集群 3 节点 ONLINE、Router 6446/6447 在跑（控制器启动日志：
   `MySQL cluster probe scheduled every 10s against jl-mysql-3`、`primary mysql-2`），R2 可直接接手。
2. **B0-03 的「lab `cache` 总开关 off ⇒ 5 条序列连同旧 `cache_l1_*` 全不注册」路径未单独真机做**
   （本轮按清单做的是 `LAB_CACHE_L1_MICROMETER_STATS_ENABLED=false` 重启一台）。真机补法：
   `POST /lab/toggles {"key":"cache","value":false}`（只影响收到调用的那一台），等过 5m lookback 再看
   `count by(instance)`，用完把开关复位回 true。总开关路径单测层已证（`TwoLevelCacheManager` → NoOpCache）。
3. **B0-04 的 `LAB_CACHE_L2_KEY_PREFIX` 改值真机项未做**（需再一轮 recreate，本轮清单未列）。
4. **B0-05 的 `/lab/cache/keys?cursor=` 端到端要等 B3**：本轮用 `/lab/cache/state` 与
   `/lab/thread/state` 两条白名单路径证了透传与「query 不参与白名单」。
5. 操作口径提醒（本轮踩过）：**单独 recreate 应用层后必须 `docker exec jl-nginx nginx -s reload`**，
   否则 Nginx 抱着旧容器 IP 静默 failover（§5.0），会污染任何「按实例统计」的真机断言。
6. 镜像回滚位仍在：`seckill-app:jdk21-pre-b0r1`（`cfb7a164e9b3`）、
   `chaos-controller:jdk21-pre-b0r1`（`409a4e944856`）、`:jdk21-pre-docs` 等旧位未删。

## 9. 清单项总表

| 清单项 | 结论 | 关键数字 |
|---|---|---|
| 应用层镜像重建 + `--force-recreate`（B0-08 真机项） | PASS | 构建 3m7.5s；新 `5b18a49d504c` / `f83b32f914a6`；34 容器不变 |
| 1. B0-02 超卖样本 | PASS | `stock_oversold_total` 0→11；`increase[5m]`=11.14>0；DB 无负库存、售出=初始 100 |
| 2. B0-03 Caffeine 序列 | PASS | `count by(instance)` 3→2（stats off）→3（复位）；`absent()`=1；hit 120/miss 20 |
| 3. B0-04 有效值回读 | PASS | `l2.ttl-seconds` value=45/source=systemEnvironment；Redis TTL 41；state 201=scan 201；热键 280≠45；59 变量下发 |
| 4. B0-05 query string 经 Nginx | PASS | app span `url.query` 逐字节相同；413/41301（8192）；400/40001 且不出网；404/40401、405/40501 |
| 5. B0-08a 镜像身份 + bench 归档 | PASS | 脚本与 `docker inspect` 4/4 一致；`manifest.images` 含 tag+id；smoke BENCH_VERDICT=PASS |
| 6. 全量闸门 verify.sh | PASS | 109 PASS / 1 WARN（cadvisor WSL2）/ 0 FAIL，EXIT=0 |

---

# R2 增补 · 杀节点演练类真机验证

> 执行代理：R2（Stage2 B0 收口真机验证 R2，负责 R1 让路的杀节点演练项）
> 日期：2026-10-28
> 分支 / 开工 HEAD：`feature/stage2-workbench` @ `f9902eb`（`git status --short` 干净，34 容器在跑）
> 范围：B0-06 真机 `--dry-run`、B0-08 探针隔离真机（403/40302 + 自检开关口径）、db-failover 演练（含
> `mysql_cluster_probe_up` 全程恒 1、Router 6446 切主耗时、旧主 TTL 归队、k6 归档）、R1 deferred 快项（B0-03
> 总开关 / B0-04 前缀）、收尾闸门。
> 证据目录：`/tmp/r2/`。

## R2-0. 开工自检

| 检查 | 结果 |
|---|---|
| 分支 / HEAD | `feature/stage2-workbench` / `f9902eb` |
| 工作树 | `git status --short` 空 |
| 容器数 | 34（与 R1 收尾一致） |
| `db-cluster-status.sh`（`/tmp/r2/db-cluster-status-initial.log`，EXIT=0） | 3/3 ONLINE，**PRIMARY = `mysql-2`（容器 `jl-mysql-2`）**；6446→mysql-2、6447→mysql-1；`RESULT: ok` |

## R2-1. B0-06 真机：`reset-lab.sh --dry-run`（未执行任何真实复位）

两次 dry-run，均 EXIT=0，尾行 `DRY RUN: no data was changed.`，日志内无任何 stop/start/TRUNCATE 执行行。

**（a）现栈声明口径**（`/tmp/r2/b006-dryrun.log`）——app 层当前起在 base+dev，`MYSQL_MODE=standalone`：

```
declared modes : MYSQL_MODE=standalone REDIS_MODE=standalone (source: .env + RESET_* overrides)
MySQL target   : container=jl-mysql db=seckill route=[container-local socket in jl-mysql (standalone dev MySQL)]
```

⇒ 解析结果与 app 实际读写目标**同源**（standalone 栈就该复位单机），这正是 B0-06「声明开关 × 活体探测」的
设计口径，不是缺陷。

**（b）集群口径**（`RESET_MYSQL_MODE=router RESET_REDIS_MODE=sentinel bash code/lab/scripts/reset-lab.sh --dry-run`，
`/tmp/r2/b006-dryrun-router.log`，EXIT=0）——**这才是步骤文档登记的那条 unverified**：

```
declared modes : MYSQL_MODE=router REDIS_MODE=sentinel (source: .env + RESET_* overrides)
MySQL topology : cluster
MySQL target   : container=jl-mysql-1 db=seckill
                 route=[MySQL Router mysql-router:6446 (follows the PRIMARY), client exec in jl-mysql-1]
MySQL nodes seen : jl-mysql-1 jl-mysql-2 jl-mysql-3 router=running
Redis targets    : jl-redis-1 jl-redis-2 jl-redis-3 (FLUSHDB each; verify on jl-redis-1)
```

- 目标解析打印指向集群：经 **Router 6446（跟随 PRIMARY）**、客户端 exec 在 `jl-mysql-1`、三节点 + router 活体全命中。
- 旁证「跟随 PRIMARY」真实有效：dry-run 的 `Current state` 行读到的集群侧数字
  （`inventory_rows=20 available_min=16 orders=167`）与 dev 单机面（rows=1020、orders=4）明显不同 ⇒ 探针确实连在集群上。
- preflight WARN（不阻断 dry-run）：`redis-cli ping on jl-redis-{1,2,3} returned "NOAUTH Authentication required."`
  ⇒ 新发现：sentinel 拓扑下 reset 的 Redis 探针没带鉴权（app 侧 `REDIS_MODE` 本就未切 sentinel，
  属 M3-1b 前瞻路径）。真 `--yes` 走 router 口径时 MySQL 侧可用、Redis 侧需先补鉴权口径。

**未做（有意）**：真 `--yes` 的集群复位。原因如任务口径——会清演示数据；且（b）已暴露 Redis NOAUTH 缺口，
`--yes` 现在跑必失败在 Redis 步骤。留待 B0 之后按批次需要再做。

**结论：PASS**（口径 (b) 即步骤文档「db-ha 栈上 `--dry-run` 打印 cluster 目标与 Router 路由」的真机闭环）

## R2-2. B0-08 探针隔离真机：403 / 40302

### R2-2.0 先纠一条前置事实（否则整个 403 验证会做错）

任务口径给的是「`CHAOS_MYSQL_CLUSTER_CONTAINER` 默认 `jl-mysql-1` ⇒ 探针 = 受保护目标」。
`application.yml:144` 的**代码默认**确实是 `jl-mysql-1`，但**现栈的运行态值不是它**：

```
docker exec jl-chaos-controller env | grep CHAOS_MYSQL_CLUSTER_CONTAINER
  -> CHAOS_MYSQL_CLUSTER_CONTAINER=jl-mysql-3
compose.base.yml:482  CHAOS_MYSQL_CLUSTER_CONTAINER: ${CHAOS_MYSQL_CLUSTER_CONTAINER:-jl-mysql-3}
控制器启动日志：MySQL cluster probe scheduled every 10s against jl-mysql-3
```

即 compose 层**有意**把 lab 默认改成 `jl-mysql-3`（注释与 `doc/process/Stage1/step-47-probe-target.md:27`
写明：`jl-mysql-1` 是惯用击杀目标，故 lab 默认让探针避开），护栏保护的是 **`jl-mysql-3`**。
⇒ 本步结论必须按运行态值验；同时「代码默认 jl-mysql-1 vs lab compose 默认 jl-mysql-3」是口径落差，
与 R1 记录的 `ProbeTargetGuard` javadoc 注释不一致同族，留主会话回写文档（本代理不改代码）。

### R2-2.1 事故实录（不掩饰）：按任务口径对 `jl-mysql-1` POST KILL 没有被拒，真把容器杀了

```
POST /lab-api/chaos/instances/jl-mysql-1/actions  {"action":"kill","ttlSeconds":30}
  -> curl 10s 超时（HTTP=000），控制器实际已受理
控制器日志：Chaos kill applied to jl-mysql-1 (id=896c5650…, ttl=30s, expiresAt=00:52:47.855Z)
          Reverted kill on jl-mysql-1 (trigger=ttl, attempt=1)   @00:52:48
          start on jl-mysql-1 is ready (state=running, health=healthy) @00:53:03
          MySQL cluster member disappeared: mysql-1 (last SECONDARY/ONLINE) @00:52:27
          MySQL cluster membership has 2 member(s), expected 3
          MySQL cluster member appeared: mysql-1 (SECONDARY/ONLINE) @00:53:07
```

- 护栏**行为正确**：`jl-mysql-1` 不在保护集（保护的是 jl-mysql-3），所以放行——不是实现缺陷，是前置事实不符。
- 影响面：被杀的是**非主 SECONDARY**，PRIMARY 仍是 mysql-2，Router 6446 未切换、写路径未中断；
  TTL 30s 自动归队，`docker ps` 复核 `jl-mysql-1 Up (healthy)`。
- 归队后 `db-cluster-status.sh` 复跑（`/tmp/r2/db-status-after-accidental.log`，EXIT=0）：
  `RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY`，6446→mysql-2、6447→mysql-1（与开工态逐字一致）。
- `mysql_cluster_probe_up` 在事故窗口（1500s 区间、5s 步进）`samples=301 min=1 max=1 non1=(none)`
  （`/tmp/r2/probe-up-incident.json`）⇒ 反向证明「探针不在被杀节点上时，观测不因节点故障而冻结」，
  正是 B0-08 分离规则要的效果。
- 附带发现：**受理型 KILL 的 HTTP 响应同步阻塞**（容器停止期间不返回，客户端 10s 超时；拒绝路径 0.05s 即返回）。
  演练时间线因此不能以 POST 返回时刻为准，须以控制器日志 / Prometheus 采样为准（本步即如此）。

### R2-2.2 正做：按运行态保护集验 403/40302（目标 `jl-mysql-3`）

```
POST /lab-api/chaos/instances/jl-mysql-3/actions {"action":"kill","ttlSeconds":30}
HTTP=403 TIME=0.045
{"code":"40302","message":"target \"jl-mysql-3\" is the MySQL cluster probe container
 (chaos.mysql-cluster.container / env CHAOS_MYSQL_CLUSTER_CONTAINER): kill/freeze/restart on it
 stops the probe as well … Move the probe to another member first … start/resume on it stay
 allowed, because they are what brings it back.","data":null,"success":false}
```

控制器同刻 WARN 两条（`ChaosActionService` + `LabExceptionHandler`）；`jl-mysql-3` 状态
`Up 9 hours (healthy)` 未变（拒绝发生在任何 Docker 调用之前）；
`sum(chaos_actions_total{result="rejected"})` = **1**（仅这一笔，计数纪律对得上）。

### R2-2.3 把探针移到任务口径的 `jl-mysql-1` 后复验（shell 注入 compose 既有占位，未编辑任何文件）

```
CHAOS_MYSQL_CLUSTER_CONTAINER=jl-mysql-1 docker compose … up -d --no-deps --force-recreate --wait chaos-controller
  -> Container jl-chaos-controller Healthy；容器 env 读回 CHAOS_MYSQL_CLUSTER_CONTAINER=jl-mysql-1
  -> 启动日志：MySQL cluster probe scheduled every 10s against jl-mysql-1
  -> docker exec jl-nginx nginx -t && nginx -s reload（R1 §5.0 操作口径，本步复现其必要性）
```

| 动作（目标 `jl-mysql-1`，经 Nginx `/lab-api`） | HTTP | code | 容器实况 |
|---|---|---|---|
| `kill` | **403** | **40302** | 未动（`Up 9 minutes (healthy)`） |
| `freeze` | **403** | **40302** | 未动 |
| `restart` | **403** | **40302** | 未动 |
| `start`（恢复动作，必须永不受限） | **202** | 0（`status":"completed"`） | 仍 `Up (healthy)` |

响应体：`/tmp/r2/guard-kill.json`、`guard-freeze.json`、`guard-restart.json`、`guard-start.json`。

### R2-2.4 `failure-container-check-enabled` 真机口径

代码口径（`MysqlClusterMonitor.classifyFailure()`）：仅当**探针 tick 失败**时才 inspect 探针容器，
分类为 `RUNNING` / `NOT_RUNNING` / `UNKNOWN` 三态并各出一条 WARN；开关键
`chaos.mysql-cluster.failure-container-check-enabled`（env `CHAOS_MYSQL_CLUSTER_FAILURE_CONTAINER_CHECK_ENABLED`，默认 true）。

真机观测：控制器本轮起栈至今 `grep -ac "cluster probe failed" = 0` ⇒ **分类分支从未在真机触发**；
而 B0-08 规则下触发它的唯一路径（对探针容器施加 kill/freeze）恰被 403 拒掉，所以「三态文案长什么样」
在受保护拓扑下不可自然复现；绕开 API 直接 `docker stop` 探针容器属本任务禁区（不杀/冻结除演练 PRIMARY
以外的数据层容器）。已在真机核到的相邻事实：开关在 `application.yml:170` 存在且默认 true、
控制器容器 env 未覆盖它（env 清单里无 `FAILURE_CONTAINER_CHECK` 键 ⇒ 走 compose 占位默认值）。
故本项如实记 **unverified**（分类日志文案），开关存在性与默认值 = 已核。

**结论：R2-2 PASS**（403/40302 三动作真机复现 + `start` 放行 + 容器未被触碰 + 拒绝计数）；
`failure-container-check` 三态文案 = unverified（见 R2-2.4）。

## R2-3. db-failover 演练（核心，兼 Stage1 遗留 P0①）

### R2-3.0 前置：把 app 数据面临时切到 Router

`code/lab/k6/README.md:188-205` 写明：**`MYSQL_MODE=standalone` 时两个池都指 dev `jl-mysql`，
杀集群节点对请求路径毫无影响**。开工态正是 standalone（R1 §1 记录），所以本项必须先临时切 router、
演练结束再切回。

```bash
# 走 compose 既有占位（compose.base.yml:67 `${MYSQL_MODE:-standalone}`），未编辑 .env / compose 文件
MYSQL_MODE=router docker compose … up -d --no-deps --force-recreate --wait app-1 app-2 app-3
docker exec jl-nginx sh -c 'nginx -t -q && nginx -s reload'      # R1 §5.0 口径
# 三台日志：MySQL access mode: router (write endpoint jdbc:mysql://jl-mysql-router:6446/seckill,
#                                          read endpoint jdbc:mysql://jl-mysql-router:6447/seckill)
```

`.env` 里 `DB_WRITE_URL/DB_READ_URL` 本就指 6446/6447（`.env:221-222`），只缺 `MYSQL_MODE` 一个开关
（`/tmp/r2/recreate-app-router.log`，EXIT=0，三台 Healthy）。

**选定击杀目标的三重确认**（绝不选探针节点）：
1. `db-cluster-status.sh`（`/tmp/r2/db-status-pre-drill.log`，EXIT=0）：PRIMARY = **mysql-2**（容器 `jl-mysql-2`）、
   6446→mysql-2、RESULT: ok；
2. 探针容器 = `jl-mysql-1`（R2-2.3 移过来的，当前非主）⇒ 杀主不伤观测，正是 B0-08 要验的组合；
3. `docker exec jl-mysql-1 mysql -h mysql-router -P 6446 -e "select @@hostname"` → `mysql-2`（`read_only=0`）。

**k6 侧接线预检**（零副作用：故意打不存在的目标）返回 `403 / 40301`
`target "jl-mysql-9" is a datastore and is refused by chaos.datastore.allowed-target-pattern="^jl-(redis|mysql)-[1-3]$"`
⇒ 控制器侧白名单独立生效且已含 `jl-mysql-[1-3]`（k6 侧同名 env 只是客户端 schema 校验），无需重启控制器。
预检同时**证伪一处任务口径**：bench.sh 的 scenario 白名单只收 `smoke|load|stress|spike|soak`（`bench.sh:174`），
db-failover 不在其中 ⇒ 归档走 `code/lab/k6/run.sh` 的 `K6_REPORT_DIR/K6_SUMMARY_EXPORT`，
manifest 由现成 `bench-report.js` 手工生成（未改任何脚本）。

选 `PRODUCT_ID=3`：集群侧 product 1/2 只剩 17/16 库存，3m 演练约 83 次写会把商品卖光，
业务 409 会伪装成选举窗失败（README 明确警告过该坑）；product 3 有 100。

### R2-3.1 时间线（时刻全部取自控制器日志 / k6 日志 / Router 轮询，不以 POST 返回时刻为准）

```
01:21:29Z  k6 起跑（read 20 VU、write 1 VU、chaos 1 VU，duration 3m）
01:21:49Z  chaos VU POST kill jl-mysql-2 ttl=45s
01:21:52Z  探针 WARN：mysql-2 从成员视图消失（last PRIMARY/ONLINE）；成员 3 -> 2
01:21:52Z  探针 WARN：mysql-1 SECONDARY/ONLINE -> PRIMARY/ONLINE
01:21:52Z  探针自检 WARN：MySQL probe target jl-mysql-1 (mysql-1) is the current PRIMARY …
01:21:58Z  Router 轮询最后一次仍见 6446 -> mysql-2
01:21:59Z  控制器：Chaos kill applied to jl-mysql-2 (ttl=45s, expiresAt=01:22:34.428Z)
01:22:00Z  k6：accepted action=kill status=202（POST→受理 11s = 同步 docker stop 耗时）
01:22:01Z  Router 轮询首次见 6446 -> mysql-1（read_only=0）      ← 切主完成
01:22:34Z  Reverted kill on jl-mysql-2（trigger=ttl, attempt=1）  ← TTL 自动归队
01:22:39Z  start on jl-mysql-2 is ready (state=running, health=healthy)
01:22:52Z  探针 WARN：MySQL cluster member appeared: mysql-2 (SECONDARY/ONLINE)
01:22:51Z  Router 6447 首次见 mysql-2 参与读路由
01:24:33Z  k6 teardown stop-all reverted=0 failed=0（无残留动作）
```

对照 Stage1 真机基线：

| 指标 | 本次（R2） | Stage1 基线 | 判定 |
|---|---|---|---|
| Router 6446 切新主 | kill POST 01:21:49 → 6446 首次新主 01:22:01 = **12s** | 12.0s | 一致 |
| 旧主 TTL 后归队（ONLINE SECONDARY） | kill POST 01:21:49 → 01:22:52 = **63.6s** | 62.5s | 一致 |
| └ docker stop + 受理确认 | ≈11s（POST→202） | — | 同 R2-2.1 阻塞发现 |
| └ GR 追赶（容器就绪→ONLINE） | 13s | — | — |

### R2-3.2 探针全程恒 1（B0-08 关键真机项）

`query_range` 窗口 01:20:30–01:25:30、step=5s（覆盖 kill 前 80s 至恢复后 180s）：

```
mysql_cluster_probe_up            samples=61  min=1  max=1  distinct=1  non1=(none)
mysql_cluster_probe_failure_total samples=61  min=0  max=0                     ← 全程零次失败
mysql_cluster_member_count        3 →(01:22:00 起) 2 →(01:23:00 起) 3          ← 报的是集群，不是探针
mysql_cluster_primary{member}     mysql-2 序列 01:20:30..01:21:55（18 点）
                                  mysql-1 序列 01:22:00..01:25:30（43 点）      ← MultiGauge 无幻影 =1
mysql_cluster_member_state        mysql-2/PRIMARY/ONLINE   …01:21:55
                                  mysql-1/PRIMARY/ONLINE   01:22:00…
                                  mysql-2/SECONDARY/ONLINE 01:23:00…           ← 旧主以从节点归队
                                  mysql-3/SECONDARY/ONLINE 全程 61 点未断
```

⇒ **探针恒 1 且零失败，同时成员/主信息如实报出 3→2→3 与 mysql-2→mysql-1**：
「探针与被杀节点分离 ⇒ 观测不因演练而冻结」这条 B0-08 设计意图在真机成立
（对照 R2-2.1 反面：被杀节点若恰是探针，series 会整体冻结）。
探针的 self-primary 自检 WARN 也真机触发了一次（新主恰是探针容器），文案与
`MysqlClusterMonitor.logSelfPrimary()` 一致，且按状态变化只出一条，未刷屏。

### R2-3.3 业务面：读零失败、写错误只在选举窗、错误率 <1%

k6 退出码 **0**，`checks_total 3684 / checks_succeeded 100.00% / checks_failed 0`：

```
✓ 'rate==0'   http_req_failed{flow:product}  rate=0.00%     （db_failover_reads = 3580，读全程 200）
✓ p(95)<300   http_req_duration{flow:product} p(95)=6.49ms  p(99)=10.25ms  max=689.68ms
✓ 'rate<0.01' flow_errors 0.00%（0/3684）；server_errors 0.02%（1/3684）← 唯一一次即选举窗 5xx
✓ 'rate>0.85' db_failover_write_success rate=98.79%（82/83）
  db_failover_writes = 83（created 82 / expected-election-error 1 / 真实失败 0：
  db_failover_write_failures 序列根本未出现在 summary ⇒ 真失败计数 0）
```

app 侧硬证（唯一一次写失败落在选举窗内，01:21:59.68，写池 commit）：

```
jl-app-2 WARN Hikari ProxyConnection … marked as broken because of SQLSTATE(08S01)
          CommunicationsException: Communications link failure … ConnectionImpl.commit
          at OrderTransactionService.create <- OrderApplicationService.create <- OrderController.create
jl-app-1 / jl-app-3 同窗口无任何写失败
```

⇒ Stage1 遗留 P0① 三条口径（读零失败、写错误限选举窗、错误率<1%）在 B0 后新镜像上真机成立。

### R2-3.4 归档（顺带补 B0-08a 在故障场景下的一次归档）

`code/lab/k6/reports/r2-dbfo1/`（gitignore 内，不入库）：`summary.json`、`k6.log`、
`image-identity.json`、`manifest.json`、`report.html`。`bench-report.js` 输出 `BENCH_VERDICT=PASS`，
manifest 关键字段：

```
runId = r2-dbfo1 | scenario = db-failover | verdict = PASS | duration = 184s
git = { commit 4e039c2…, branch feature/stage2-workbench }
images.containers[] = jl-app-1/2/3 java-learn-lab/seckill-app:jdk21            sha256:5b18a49d504c…4ab2e running
                      jl-chaos-controller java-learn-lab/chaos-controller:jdk21 sha256:f83b32f914a6…ffcc3 running
thresholds.results  = 5 条全 ok（write_success / flow_errors / p95 / p99 / http_req_failed{flow:product}）
```

与 `docker inspect jl-app-1 jl-chaos-controller --format '{{.Image}}'` 逐台一致
（`5b18a49d504c…` / `f83b32f914a6…`）⇒ 归档记的镜像身份 = 演练实际所跑的 B0 后新镜像。
`image-identity.json` 的 `resolvedBy` 仍全为 `labAlias`（与 R1 §6.1 结论一致，本栈命名使然）。

一处**已知限制**（非缺陷）：k6 `--summary-export` 产出的 `summary.json` 不含 sub metric（按 tag 拆分值），
故 manifest 的 keyMetrics 只有聚合值；按 tag 的判定证据在 `k6.log` 与本报告。

### R2-3.5 现场复位（本步）

- app 层：不带 `MYSQL_MODE` 覆盖 recreate 回 standalone（`/tmp/r2/recreate-app-standalone.log`，
  三台 Healthy、日志 `MySQL access mode: standalone` ×3、容器 env `MYSQL_MODE=standalone`）+ `nginx -s reload`。
- 演练写入（router 模式下约 83 单进集群 `seckill` 库、product 3 库存相应减少）**未回滚**：
  真 `--yes` 会清演示数据（任务口径留到 B0 之后），此处如实记账。dev 单机面（app 实际读写的 `jl-mysql`）
  未被演练触碰。
- 集群成员态：演练后 PRIMARY = mysql-1、旧主 mysql-2 已以 SECONDARY 归队（3/3 ONLINE、RESULT: ok）。
  开工态是 mysql-2 为主；是否/如何回正见 R2-5 收尾。

**结论：R2-3 PASS**

## R2-4. R1 让路的两个快项：B0-03 总开关 / B0-04 前缀改值

两项都只动 shell 注入的 compose 既有占位（`.env`、`compose*.yml` 一字未改），各只 recreate 一台，
做完即无覆盖 recreate 复位。

### R2-4.1 B0-03：全局 `cache` 开关关闭 ⇒ caffeine 家族序列**消失**（缺失 ≠ 值为 0）

做法（占位见 `compose.base.yml:90  LAB_TOGGLE_CACHE: ${LAB_TOGGLE_CACHE:-true}`）：

```bash
LAB_TOGGLE_CACHE=false docker compose … up -d --no-deps --force-recreate --wait app-1   # 01:40:17Z
docker inspect jl-app-1 → env LAB_TOGGLE_CACHE=false
docker exec jl-nginx sh -c 'nginx -t -q && nginx -s reload'                             # R1 §5.0 口径
```

读回与驱动（`/tmp/r2/b003-off-live.log`，EXIT=0）：

| 观测点 | app-1（开关关） | app-2 / app-3（对照） |
|---|---|---|
| `GET /lab/toggles` | `"cache":false` | `"cache":true` |
| 60 次读（3 轮 ×20 商品，直连 `:8080`） | 60×**200**（关缓存不影响可读性） | 60×200 |
| 原始抓取 `^cache_l1_caffeine` 行数 | **0** | 4 / 4 |
| 原始抓取 `^cache_l1_estimated_size` 行数 | **0** | 1 / 1 |
| 原始抓取 `^cache_l1_requests_total` 行数 | **0** | 2 / 2 |

同一份 app-1 抓取里，`cache_avalanche_l1_total`、`cache_bloom_requests_total`、
`cache_breakdown_mutex_total` 等家族**照旧以 0.0 值出现**（前 20 行已入日志）⇒
「0 值序列」与「序列整体缺失」在同一台 JVM 的同一次抓取里同时可见，这正是缺失≠0 最强的证据形态。

Prometheus 侧（`/tmp/r2/b003-off-prom.log`，查询时刻 01:52:54Z，距 recreate 11m，远超 5m lookback）：

```
count by (instance) (cache_l1_caffeine_requests_total)  ->  仅 app-2:8080=2、app-3:8080=2
count by (instance) (cache_l1_estimated_size)           ->  仅 app-2:8080=1、app-3:8080=1
absent(cache_l1_estimated_size{instance="app-1"})                  = 1
absent(cache_l1_caffeine_requests_total{instance="app-1"})         = 1
cache_l1_caffeine_requests_total{instance="app-1:8080"}            = EMPTY RESULT
query_range 01:42:00Z..01:52:00Z step=30s（app-1）                  = EMPTY RESULT   ← 不是 0 值采样，是无采样
```

**与 R1 §3.2 的口径差别（新事实，需分清两个开关）**：
`LAB_CACHE_L1_MICROMETER_STATS_ENABLED=false` 只让 5 条 caffeine 家族序列消失，
应用口径 `cache_l1_requests_total` 仍在（R1 实测 6 条）；而**总开关** `LAB_TOGGLE_CACHE=false`
让两族一起消失（原始抓取 `cache_l1_requests_total` = 0 行）⇒ 总开关关的是缓存本身（不装配/不走），
stats 开关只关 Micrometer 绑定。B0-03 的「缺失≠0」判定在两条路径上都成立。

复位（`/tmp/r2/reset-app12.log`、`/tmp/r2/reset-check.log`、`/tmp/r2/b003-restore-prom.log`，均 EXIT=0）：
不带覆盖 recreate app-1 + app-2（01:55:50Z，Healthy）+ `nginx -s reload`；
读回 `LAB_TOGGLE_CACHE=true`、`/lab/toggles` `cache:true`×3；三个 `count by (instance)` 查询全部回到 **3**，
且 app-1 新 JVM 是活值（刚打一轮读后 `result=hit 0 / result=miss 20`），不是常量。

### R2-4.2 B0-04：`LAB_CACHE_L2_KEY_PREFIX` 改值后 state 视图**无「0 键」错位**

做法（占位 `compose.base.yml:113  LAB_CACHE_L2_KEY_PREFIX: ${LAB_CACHE_L2_KEY_PREFIX:-cache:}`）：
01:32:40Z 只 recreate app-2，注入 `LAB_CACHE_L2_KEY_PREFIX=cache:r2pfx:`；app-3 保持默认前缀作对照。
R1 已登记该条为「未做」，其判据是「state 视图不出现 0 键错位」——本步把它做成**两个时刻 × 三个视图**的对齐表。

有效值回读（`/tmp/r2/b004-prefix-live.log`，EXIT=0）：

```
configKey=app.lab.cache.l2.key-prefix  env=LAB_CACHE_L2_KEY_PREFIX
value=cache:r2pfx:   defaultValue=cache:   source=systemEnvironment
（同一台同一次回读：ttl-seconds=300/defaultValue 300、clear-scan-count=500/500 ⇒ 只动了前缀一个键）
```

对齐（`/tmp/r2/b004-prefix-live.log` + `/tmp/r2/b004-prefix-live2.log` +
`/tmp/r2/b004-state-newprefix.json`、`b004-state-after-seed.json`，EXIT=0）：

| 时刻 | app-2 `state.keys` | 同刻 raw `redis-cli --scan` | 判定 |
|---|---|---|---|
| 01:34:15Z 改前缀后、未播种（`b004-state-newprefix.json`） | `l2ExperimentKeys=0`、`l2AvalancheKeysAlive=0`、`hotKeyCached=false`、`redisReachable=true` | 该时刻未留 raw 计数（如实记，判据取下一周期） | state 侧 0；见 01:53:53Z 行的 raw/raw 对齐 |
| 01:35:05Z 播种后（`b004-state-after-seed.json`） | `l2ExperimentKeys=201`、`l2AvalancheKeysAlive=200`、`hotKeyCached=true` | — | 与 R1 §4.3 默认前缀下同一组数字（201/200/true）一致 |
| 01:53:53Z 播种键过期后 | 回到 `0 / 0 / false` | `cache:r2pfx:*`=**0**、`cache:product:*`=20 | **state 0 == raw 0**，逐字对齐 |
| 01:54:18Z 再播种后 | `201 / 200 / true` | `cache:r2pfx:product:*`=**201**、`cache:r2pfx:*`=201、`cache:product:*`=20（未变）、`cache:*`=221 | **201 = 201**，跨前缀零污染 |
| 01:54:18Z 对照 app-3（默认前缀） | `l2ExperimentKeys=0` | `cache:product:*`=20 | 这 20 键是 id 1..20 常规读缓存，落在实验窗口 90000–90999 之外 ⇒ 各自前缀各自口径的正确读数 |

写路径同样走新前缀（不是只改读侧）：

```
cache:r2pfx:product:90123  TTL=53   ← app-2 常规 L2 写键（GET /api/products/90123 后）
cache:product:90123        TTL=-2   ← 默认前缀下根本没有这个键
cache:r2pfx:product:90999  TTL=294  ← 播种热键，仍按 seed 口径 300s（R1 §4.4 结论在前缀改值后依旧成立）
cache:r2pfx:product:90001  TTL=53   ← 播种雪崩键，仍按 60s 口径
```

⇒ 前缀是「五方共用同一解析点」在真机上成立：`/lab/config` 回读、state 扫描、常规写、播种写、
雪崩写全部随同一个值移动，且各实例按自己生效的前缀计数 ⇒ R1 登记的那条「未做」现记 **PASS**。

复位：不带覆盖 recreate app-2（同 R2-4.1 那一次），读回 `LAB_CACHE_L2_KEY_PREFIX=cache:`；
`/lab/cache/state` 回到默认前缀视图。遗留：`cache:r2pfx:*` 下 201 个实验键会按各自 TTL（≤300s）自然过期，
未手工 DEL（演练期间不碰 `jl-redis` 数据，避免与 verify.sh 的 Redis 断言抢时间窗）。

### R2-4 逐项结论

| 清单项 | 结果 |
|---|---|
| B0-03 真机：全局 `cache` 开关关闭 ⇒ caffeine 家族序列消失（缺失≠0） | **PASS**（原始抓取 0 行 + `absent()`=1 + range EMPTY + 同抓取内 0.0 值家族对照） |
| B0-03 复位后序列回归 3 实例且为活值 | PASS |
| B0-04 真机：`LAB_CACHE_L2_KEY_PREFIX` 改值 ⇒ state 视图无「0 键」错位 | **PASS**（state 计数与同前缀 raw 扫描逐时刻相等） |
| B0-04 写路径随前缀移动 / 播种与雪崩 TTL 口径不受影响 | PASS |
| 两项复位（env、toggles、序列） | PASS（见上） |

**结论：R2-4 PASS**（R1 §3.2/§4 的两条留白在真机补齐；新记录一条口径差别：总开关 vs stats 开关影响面不同）

---

## 收口终验（主会话，2026-10-28）

R2 在配额内已完成全部实质提交（`6204e0e..21b6177`），终验由主会话执行：

| 闸门 | 当场输出 | 结论 |
|---|---|---|
| `verify.sh` | `109 PASS / 1 WARN / 0 FAIL`，EXIT=0（唯一 WARN=cAdvisor/WSL2） | PASS |
| `db-cluster` 哨兵 | `RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY`；PRIMARY=mysql-1、探针=jl-mysql-3（compose 默认），互不重合 | PASS |
| k6 单测 | `# pass 79 / # fail 0` | PASS |
| `smoke.sh` | 末行 `SMOKE PASSED.`（7/7） | PASS |
| `test-lab-safety.sh` | `LAB SAFETY TESTS PASSED.` | PASS |
| `mvn test`（reactor，含探针 javadoc 澄清后复跑） | BUILD SUCCESS，EXIT=0；app-jdk21 **469** / jdk8 **59** / chaos **296** / core 13 / platform 14 = **851** | PASS |
| claims | 32/32（B0 未新增断言，指标字典抽样断言归 B1 起补） | PASS |
| console-smoke | **未重跑**：B0 未动 dashboard 文件；基线 42 沿用，B1 重构时随重写验收 | 豁免（已登记） |

收口伴随的文档/注释修正（本次提交）：

1. 计划 §0 闸门表新增「B0 收口基线（现行）」列，§1 顶部登记收口状态（v1.3）。
2. `ProbeTargetGuard` javadoc 澄清双默认：代码回退值 `jl-mysql-1` 与 compose 现场默认 `jl-mysql-3`
   （实验室栈内 compose 恒注入 env var，生效探针是 -3；`ProbeTargetIsolationTest` 已按此断言）。

**有意顺延（不阻塞收口，已在计划/交接登记）**：
`/lab/cache/keys?cursor=` E2E（等 B3 分页 UI）；db-ha 真 `reset-lab.sh --yes`（会清演示数据，按批次需要再做）；
`failure-container-check` 真机细节行为（单元已覆盖）。

**结论：B0 收口成立，B1 门禁解除。**
