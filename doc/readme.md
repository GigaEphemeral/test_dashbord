# JAVA-LEARN · 本地高可用 / 高并发演示实验室

> 这是一个**教学演示**用的本地实验室，不是生产秒杀系统。
> 目标是固定的一条链路：**制造缺陷/故障 → 观察症状 → 执行处理 → 对比效果**，任何实验都应在 5 分钟内完成。
> 不做权限体系、审计、多租户、真实支付；不做录屏/旁白，讲解文字在操作台与本文档里。

---

## 1. 前置条件

| 项 | 要求 / 实况 |
|---|---|
| 操作系统 | Windows 10 + Docker Desktop（WSL2 后端） |
| 内存 | Docker 至少 12GB；常驻 dev 栈约 5GB，叠加 ha/db-ha 后约 9–10GB |
| JDK | PATH 上是 **JDK 21**；Maven 运行在 **JDK 25**（`D:\env\jdk\25.0.3`）；本机只有 **JRE 8**（`C:\Program Files\Java\jre1.8.0_503`），所以 JDK 8 外壳用 `--release 8` 编译、用 JRE 8 / 容器运行 |
| 构建 | Maven 3.9.x，仓库根为 `code/pom.xml` |
| 端口 | 8080（Nginx 入口）、3000（Grafana）、15672（RabbitMQ）、6446/6447（MySQL Router，仅 127.0.0.1） |

安装、镜像构建与打包的逐步明细见 [设计 · 安装与打包手册](design/安装与打包手册.md)。

---

## 2. 目录结构

```
code/                     全部可运行代码
  pom.xml                 Maven reactor
  platform/               platform-common（release 8）、platform-observability（Boot 3 starter）
  services/
    seckill-core/         领域与用例（release 8，框架无关）
    seckill-app-jdk21/    Spring Boot 3.5 外壳（全部实验能力）
    seckill-app-jdk8/     Spring Boot 2.7 外壳（仅 D7 对比用）
    chaos-controller/     故障编排、向导状态机、集群探针、Toxiproxy 动作
  lab/
    docker/               compose 分层、Nginx、Prometheus、Grafana、MySQL/Redis 拓扑
    scripts/              up/down/verify/smoke/reset/bench/console-smoke/db-ha-*
    k6/                   压测场景与运行器
    dashboard/            Vue 3 操作台（经 Nginx 托管）
    scenarios/            故障场景 JSON（schema v1）
doc/
  design/
    Stage1/               总体设计、数据库方案对比 + details/01–11（每篇带代码对照表）
                          + diagrams/（Mermaid 图集）
    Stage2/               本轮改造方案稿（开工时写入）
    工程规约.md / 安装与打包手册.md / OpenCodeReview-使用手册.md
  process/
    Stage1/               step-*.md 每步交接、benchmark/（压测结果页）、snapshots/（线程快照，
                          .gitignore 忽略）、snapshot-analysis-template.md、retrospective
    Stage2/               本轮改造的架构现状、交接与开发计划
    STAGE-01阶段交接-2026-10-26.md   Stage1 收口现状（已知缺陷与 TODO 的落点）
    交接模板.md
  avoid_common_error/     真实踩过的坑（index.md + 每坑一篇），原 doc/避坑/
```

---

## 3. 快速开始

```bash
# 1) 启动 dev 栈（16 个服务：MySQL/Redis/RabbitMQ/Nginx/三实例/观测栈）
bash code/lab/scripts/up.sh --profile dev

# 2) 自检（默认保留栈运行；约 2–3 分钟）
bash code/lab/scripts/verify.sh            # 真机基线 109 PASS / 1 WARN / 0 FAIL（2026-10-27）

# 3) 全栈冒烟（会消耗 product 1 与 20 各 1 件）
bash code/lab/scripts/smoke.sh             # 期望末行 "SMOKE PASSED."（7 项检查）

# 4) 打开操作台与观测
#    业务 API   http://127.0.0.1:8080/api/products/1
#    操作台     http://127.0.0.1:8080/lab/
#    Grafana    http://127.0.0.1:3000       （账号见 code/lab/docker/.env.example）
```

`code/lab/docker/.env` 由 `.env.example` 复制而来，**不提交**；token 与口令只放在那里。

### profile 一览

| profile | 服务数 | 用途 |
|---|---|---|
| 默认（base + dev） | **16** | 日常开发：单机 MySQL/Redis + 三实例 + 观测栈 |
| `--profile ha` | **25** | Redis Sentinel（1 主 2 从 3 哨兵 + 每个数据节点一个 exporter） |
| `--profile chaos-proxy` | **18** | 叠加 Toxiproxy，应用经代理访问 Redis/MySQL（会重建 app 层） |
| `--profile dual-jdk` | **17** | 追加 JDK 8 外壳实例，用于 D7 对比 |
| **MySQL 层不是 `up.sh` 的 profile** | **25** | `bash code/lab/scripts/db-ha-up.sh`（3 节点 + Router + 每节点 exporter + 两个一次性 init job）；`up.sh --profile db-ha` 会被显式拒绝 |

> ha 与 db-ha 可以同时开着：**34 个服务、32 个常驻容器**（`mysql-cluster-init`、`mysql-router-init` 是跑完即退出的一次性 job）。
> `chaos-proxy` 会重建 app 层，且不与 ha / db-ha 组合，演示前单独安排。

---

## 4. 每一项设计的异常如何触发

> 本章是使用文档的主干：按 `doc/design/Stage1/details/01–11` 的族排列，**一行 = 一个可以在栈上真实制造出来的异常**，
> 给出触发命令、观察点、预期症状与复位手段。命令均为可直接粘贴的完整形式。
>
> 证据标记（不做无标记的断言）：
> **✅** = 在本机真机栈上跑过，证据在 `doc/process/Stage1/step-*.md`；
> **⚪** = 按代码/配置口径（含单测与 `verify.sh` 的序列存在性断言）得到，本轮未逐条真机复现。

### 4.0 先认清走哪条通道

| 通道 | 地址 | 在哪儿发命令 | 典型端点 |
|---|---|---|---|
| 公开业务 API | `http://127.0.0.1:8080/api/**` | 宿主机 curl | `POST /api/login`、`GET /api/products/1`、`POST /api/orders`、`POST /api/seckill/orders` |
| 控制面 | `http://127.0.0.1:8080/lab-api/**` → `jl-chaos-controller:8081/`（去掉 `/lab-api` 前缀） | 宿主机 curl + `X-Lab-Token` | `/chaos/instances/{name}/actions`、`/chaos/actions`、`/chaos/stop-all`、`/chaos/toxiproxy/**`、`/lab/guide/**`、`/lab/traffic/**`、`/containers`、`/logs/{name}` |
| 应用内 lab API | **只有白名单路径**能经浏览器到达：`/lab-api/chaos/app/{instance}/lab/thread/**` 与 `.../lab/cache/**` | 宿主机 curl（走上一条的控制面） | 见 §4.2 / §4.6 |
| 应用内 lab API（直连） | 只在 `jl-net` 内可达；`/lab/toggles`、`/lab/config` 被 Nginx 硬 404 | **容器内 BusyBox wget** | `docker exec jl-app-1 sh -c 'wget …'` |
| 查询与观测 | `http://127.0.0.1:8080/prom/api/v1/**`（Prometheus 不发布宿主端口）、Grafana `:3000` | 宿主机 curl / 浏览器 | `query?query=…` |

两条铁律：

1. **应用容器里没有 `curl`、也没有 `bash`**：运行镜像 `eclipse-temurin:*-alpine` 只带 BusyBox `wget` / `nc`，
   所以容器内一律写成 `docker exec <容器> sh -c '… wget …'`（照抄 curl 会报 not found，见 `doc/avoid_common_error/16`）。
2. **token 不回显**：`$LAB_TOKEN` 由容器自身环境提供，写在 `sh -c '…'` 的单引号里由容器展开；宿主机侧从 `.env` 读：

```bash
LAB_TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
```

容器内调用模板（下文简写为 `lab-post <path> <json>` / `lab-get <path>`）：

```bash
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" \
  --header="Content-Type: application/json" --post-data="{\"mode\":\"bloom\"}" \
  http://localhost:8080/lab/cache/mode'
```

### 4.1 业务主链路（details/02）—— 六个开关与它们的失败语义

运行时开关：`POST /lab/toggles`，body `{"key":"<k>","value":<bool>}`，key 精确匹配、拼错直接 400。
六个 key 按固定顺序：**`lock` `idempotency` `mq` `ratelimit` `circuitbreaker` `cache`**（`LabToggle.java:11-16`）。
开关是**每实例内存态**——只影响收到调用的那一个实例，这正是「一个降级实例 vs 两个健康实例」对照的前提。

| 异常 | 怎么触发 | 看哪里 | 预期症状 | 复位 / 对照 | 证据 |
|---|---|---|---|---|---|
| 限流打满（429） | `bash code/lab/k6/run.sh spike`（`SPIKE_VUS=800`，`spike.js` 打秒杀，商品 20） | D4：`resilience4j_ratelimiter_available_permissions{name="seckill"}`、`sum(rate(seckill_attempts_total{result="rate_limited"}[1m]))`；D9：k6 `seckill_expected_429` | 桶 `SECKILL_RATE_LIMIT_PER_PERIOD=50` / `SECKILL_RATE_LIMIT_REFRESH=1s` / `timeout=0` → 超出即 429、不排队 | 把 `ratelimit` 关成 false 重跑：429 与 `rate_limited` 计数**同时消失**（`SeckillResilienceGuard.java:90-93`） | ⚪ |
| 熔断打开 → 同步降级 | `docker stop jl-rabbitmq`（不在 datastore 白名单里，需手工），再打秒杀 | D4：`resilience4j_circuitbreaker_state{name="seckillBackend"}` 变 1；`sum(rate(seckill_attempts_total{result="degraded"}[1m]))`；15672 队列无新消息 | 滑动窗口 `SECKILL_CB_WINDOW=10`、失败率 `SECKILL_CB_FAILURE_RATE=50`、最少 `SECKILL_CB_MIN_CALLS=5` 次后 OPEN；降级仍下单成功，只是走同步 DB | `docker start jl-rabbitmq`，`SECKILL_CB_WAIT=10s` 后半开放 3 次试探。**409/429 不会打开熔断**（`ignore-exceptions: BusinessException`） | ⚪ |
| 幂等重复提交 | 同一个 `Idempotency-Key` 连发两次 `POST /api/orders` | D4 `sum(increase(idempotent_reject_total[5m]))`；第二次响应是**同一订单** | 命中既有订单即回放，不新插一行 | 换 key 即可。注意：秒杀重放计入 `seckill_attempts_total{result="replay"}`，**不**涨 `idempotent_reject_total`（后者只统计 SYNC_API 源） | ⚪ |
| 售罄 / 锁超时 | 商品 20 库存被压测耗尽；或把 `lock` 关掉再高压 | D4 `seckill_attempts_total{result="sold_out" / "lock_timeout"}` | 售罄 409 + code 40901；抢锁超时 40901 `Seckill lock timeout` | `reset-lab.sh --yes` 恢复基线库存 | ⚪ |
| 超卖 | **造不出来，这本身就是结论**：`lock` 关成 false 也不会超卖 | D4「超卖计数（必须为 0）」= `sum(stock_oversold_total)` | 恒为 0。因为 `InventoryStockJpaRepository.java:18-23` 是单条 `UPDATE … WHERE available >= :quantity`，数据库自己拒绝把库存压到负数 | 教学点：Redis 锁只是加速路径，**最后一道防线在下一层** | ⚪ |
| MQ 堆积 | `bash code/lab/k6/run.sh spike` 期间生产快于消费；或让某个实例消费不动 | D4 `rabbitmq_queue_messages_ready{queue="seckill.order.queue"}`；RabbitMQ 管理台 15672 | 出现尖峰后回落到 0（`verify.sh:655-672` 就断言它回到 0） | 等待或 `reset-lab.sh` 清队列 | ✅ |
| 异步 → 同步切换 | `POST /lab/toggles {"key":"mq","value":false}` | 秒杀响应从 **202 ACCEPTED** 变 **200 OK**；队列不再增长 | 同步路径直接落库、无 MQ | 把值改回 true（每实例单独生效，三个实例都要发） | ⚪ |

> `seckill_attempts{result=…}` 的 8 个取值：`accepted replay sold_out lock_timeout invalid error rate_limited degraded`；
> Prometheus 侧名字带 `_total`。Micrometer/OpenMetrics 的命名坑（`_info` 被剥、`orders_created` 被改名）见 `doc/avoid_common_error`。

### 4.2 缓存体系（details/03）—— 穿透 / 击穿 / 雪崩

八种模式按 canonical 顺序：`off → null-cache → bloom → mutex → logical-expire → ttl-jitter → l1-cache → db-fallback`。
先播种，再切模式，只看 D10：

```bash
# 播种（幂等；重复跑 inserted=0）：写 catalog_product 90000..90999 + 雪崩键 + 热点键 + 重建 Bloom
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --post-data="{}" \
  http://localhost:8080/lab/cache/seed'
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" http://localhost:8080/lab/cache/state'
```

| 异常 | 怎么触发（单点、分钟级） | 看哪里 | 预期症状 | 对照 | 证据 |
|---|---|---|---|---|---|
| 缓存穿透（读不存在的键） | `curl -s http://127.0.0.1:8080/api/products/99999`（区间外的 id） | D10「DB 回落按 reason」中 `reason="penetration"`；`cache_bloom_requests_total` | `off` 模式每次都是 404 + 一次真 DB 查询；切 `bloom` 后请求被布隆过滤器挡在 DB 之前 | 依次切 `null-cache`（空值标记）与 `bloom`（布隆），对比 `cache_null_cache_requests_total` / `cache_bloom_requests_total` | ✅ |
| 缓存击穿（热点键同时失效） | 先删热点键再并发读：`docker exec jl-redis redis-cli DEL cache:product:90999`，随后集中读 90999 | `cache_breakdown_mutex_total`、`cache_breakdown_logical_total`、`cache_breakdown_refresh_total`、`cache_hotkey_publish_total`；D10 命中率曲线 | `off` 下并发请求全部落到 DB；`mutex` 只有一个线程重建、其余等待；`logical-expire` 先返回旧值再异步刷新 | 同一批流量分别在 `mutex` 与 `logical-expire` 下各跑一遍 | ✅ |
| 缓存雪崩（大批键同一 TTL 集体过期） | 播种的雪崩键共用 `LAB_CACHE_SEED_AVALANCHE_TTL_SECONDS`（默认 **60s**），seed 后约 60s 集体过期 | `cache_avalanche_ttl_jitter_total`、`cache_avalanche_limiter_total`、`cache_db_fallbacks_total{reason="avalanche"}`；`cache_preheat_*` | 过期时刻 DB 回落尖峰；`ttl-jitter` 让 TTL 打散、`l1-cache` 让 L1 先接住、`db-fallback` 触发限流返回 **429** | 三个模式各等一次集体过期 | ✅ |
| L1 预热 | `docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --post-data="{}" http://localhost:8080/lab/cache/preheat'` | `cache_preheat_runs_total` / `_loaded_total` / `_hot_list_size` / `_duration_seconds_max` | 按 `load.chunk-size` 分块读 L2 并限速装进 Caffeine | — | ✅ |
| Redis 整体不可用 | `docker stop jl-redis`（standalone 模式时） | D10 / D4：读接口仍 200，`cache_db_fallbacks_total{reason="miss"}` 抬升 | L2 失败被降级成「直接查库」，不报 5xx | `docker start jl-redis` | ⚪ |

**一次跑完八模式的带压测版本**（k6 `cache-lab`，10 VU 读 + 按时间表整层切模式，同时**主动制造三族缺陷**）：

```bash
LAB_TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
LAB_TOKEN="$LAB_TOKEN" \
  CACHE_LAB_APP_URLS='http://app-1:8080,http://app-2:8080,http://app-3:8080' \
  CACHE_LAB_MODE_DURATION=90s \
  CACHE_LAB_RESEED_MODES='off,null-cache,bloom,mutex,logical-expire,ttl-jitter,l1-cache,db-fallback' \
  bash code/lab/k6/run.sh cache-lab
```

三个必须知道的口径：

- **默认只切 `http://app-1:8080`**。经 Nginx 的读只有约 1/3 落在被切模式上，要整层一致必须显式传三个实例；`teardown()` 会把所有实例复位成 `off`（时间线中途失败也复位）。
- **穿透与击穿默认就在造**（`CACHE_LAB_PROBE_EVERY=10`、`CACHE_LAB_HOT_KEY_EVERY=25`）：每第 10 次读打一个不存在 id（`CACHE_LAB_ABSENT_IDS` 默认 99990..99999，期望 404 + code 40401），每第 25 次读打 seed 的热点键。
- **雪崩要显式 arm**：`CACHE_LAB_RESEED_MODES` 默认为空，且 dwell 必须 ≥ TTL + margin（默认 `60s + 15s = 75s`），否则重播的键会在**下一个** dwell 里集体过期、污染那个模式的对比。不满足时脚本在 **init 阶段就报错退出**，不是跑完才发现。

2026-10-27 真机对照（armed `run_id=k6-20261027175755-3961` vs 未 arm `k6-20261027-164307-cache1`）：
每个防护族都在**自己的 dwell** 里出数——`null_cache_hits=194`、`bloom_absents=243`、`mutex_rebuilds=311`、
`logical_cold_rebuilds=245` / `stale_serves=61` / `refresh_submitted=62`；DB 回落 `off=301` 基线 vs `mutex=933`；
`l1-cache` dwell 的雪崩采样窗口里 `state_l2_keys` 掉到 12（集体过期后的塌陷快照）。
未 arm 的那一轮这些计数全为 0 或序列根本不存在——**这个差值就是「缺陷被造出来了」的证据**。
完整证据矩阵与两条 init 护栏见 `doc/process/Stage1/step-56-cache-lab-defect-arming.md`。

> **5 分钟窗口**：上面这条完整时间线是 8 × 90s ≈ 12 分钟。要做短演示就把模式数收窄、不要缩短 dwell：
> `CACHE_LAB_MODES='off,bloom'`（顺序仍按 canonical 强制）+ 90s dwell ≈ 3 分钟。
> `run.sh` 会把 `cache probe:` / `cache hot key:` / `cache reseed:` / `cache dwell:` 打到横幅上，先确认这三行再按回车。

### 4.3 Redis Sentinel 主从切换（details/04）

```bash
bash code/lab/scripts/up.sh --profile ha          # 25 服务；app 渲染进 sentinel 模式
bash code/lab/scripts/sentinel-status.sh          # 只读拓扑（先确认 mymaster 落在哪个节点）
LAB_TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
curl -sS -X POST http://127.0.0.1:8080/lab-api/chaos/instances/jl-redis-1/actions \
  -H "X-Lab-Token: $LAB_TOKEN" -H 'Content-Type: application/json' \
  -d '{"action":"kill","ttlSeconds":45}'          # → HTTP 202 Accepted
```

期望（✅ 真机，step-24d）：约 7.6s 完成晋升；Nginx 读路径 ~9s 恢复 200，**app 不重启**；TTL 到点旧主以 `role=slave`、`master_link_status=up` 归队。
症状：切换窗口内商品读出现少量 5xx/超时，`jl-app-*` 的 Redis 指标同步骤降；日志出现 `master ... has been changed to ...`。
面板：**D11** 的 Redis 主从/可达面板；`sentinel-status.sh` 里主地址发生更换。
两个坑：主在多次切换后会漂移，`redis-sentinel-failover.json` 第一步写死 `jl-redis-1` 是**教学简化**，跑前先 `sentinel-status.sh` 核对；
要缩短感知时间就调 `REDIS_HA_DOWN_AFTER_MS`，要演示丢写就改用 `docker kill`（本场景用 `stop`，回归更干净）。

演练后把 app 还原 standalone（否则 `verify.sh` 的 Redis 内容检查会假失败）：

```bash
docker compose --project-name java-learn-lab --project-dir code/lab/docker --env-file code/lab/docker/.env \
  -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml up -d app-1 app-2 app-3
```

### 4.4 MySQL InnoDB Cluster / Router 切换（details/05）

```bash
bash code/lab/scripts/db-ha-up.sh                 # 3 节点 + Router + exporter（两个 init job 跑完即退出）
bash code/lab/scripts/db-cluster-status.sh        # 每节点 RGM + Router 6446/6447 后端；RESULT: ok 才继续
# app 必须先跑在 router 模式：.env 里 MYSQL_MODE=router + DB_WRITE_URL=…:6446 / DB_READ_URL=…:6447，
# 改完要重建 app 层并 docker exec jl-nginx nginx -s reload
bash code/lab/scripts/ha-acceptance.sh mysql      # 一键：预检 → 杀主 → 晋升 → Nginx 200 → TTL 回滚 → 旧主归队
```

期望（✅ 真机，step-31 / step-45）：写 6446 落主、读 6447 轮转到从；杀主后重新选主、Router 重定向，读请求 70/70 保持 200；
TTL 回滚后旧主以 `ONLINE SECONDARY` 归队；`mysql_cluster_primary`（导出名没有 `_info`）随之跳变、`mysql_cluster_member_count` 3→2→3。
面板：**D11** 成员状态时间轴 / 主节点 / 成员数 / 探针健康 / 节点可达性，末尾两块 text 面板写了 Router 观察口径。

**带压测的版本**（k6 `db-failover`：读 20 VU + 写 1 VU 每 2s 一单，同时杀主）：

```bash
bash code/lab/scripts/db-cluster-status.sh          # 先确认当前 PRIMARY 是谁
LAB_TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
LAB_TOKEN="$LAB_TOKEN" \
  CHAOS_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  DB_FAILOVER_TARGET=jl-mysql-2 DB_FAILOVER_TTL_SECONDS=45 \
  DB_FAILOVER_DURATION=3m PRODUCT_ID=2 \
  bash code/lab/k6/run.sh db-failover
```

三个前提：① app 层必须 `MYSQL_MODE=router`；② `CHAOS_TARGET_PATTERN` 与 datastore 白名单**都要放开**——前者客户端默认 `^jl-app-[0-9]+$`，后者为空表示**全拒**；
③ `DB_FAILOVER_TARGET` 必须是**当前主**，且不能是探针容器（代码默认探针进 `jl-mysql-1`，`.env.example` 建议 `jl-mysql-3`），否则演练期间 `mysql_cluster_*` 会冻结。
`DB_FAILOVER_TARGET` 的默认值是 `jl-mysql-2`（`db-failover.js:100`）。写会真实扣库存（3m ≈ 85 单），
Router 模式下 `reset-lab.sh` 默认清的是 standalone 的 `jl-mysql`，所以换个还有库存的 `PRODUCT_ID` 最省事。

实测（2026-10-27，`run_id=k6-20261027-161756-dbfo2`）：k6 退出 0，读 **3580 次零失败**、p95 8.3ms；
写 85 次里只有 **1 次**落在选举窗口内（`status=500`，kill 后 7s），成功率 **98.8%**；teardown 后 `/chaos/actions` 为空。
证据与阈值预算的算术见 `doc/process/Stage1/step-48-k6-ha-cache-scenarios.md` 第 6 节。

### 4.5 实例与网络故障（details/06）

**五种容器动作**（`POST /lab-api/chaos/instances/{name}/actions`，`{"action":…,"ttlSeconds":…}`，返回 202）：
`kill` `start` `restart` `freeze` `resume`。只有 `kill` 与 `freeze` 带 TTL 自动回滚（回滚映射 `KILL→START`、`FREEZE→RESUME`）；
`start` / `resume` 幂等（Docker 304 视为完成）。

| 异常 | 触发 | 观察点 | 症状 | 复位 | 证据 |
|---|---|---|---|---|---|
| 实例宕机 | `{"action":"kill","target":"jl-app-1","ttlSeconds":60}` | D2 实例序列断裂、D3 容器状态、Nginx access log | `upstream refused` / 502 一小段，错误率瞬时尖峰后回落；app-2/app-3 QPS 各升约一半 | TTL 自动 `start`；或 `POST /lab-api/chaos/stop-all` | ✅ |
| 实例挂死（比宕机更隐蔽） | `{"action":"freeze","target":"jl-app-2","ttlSeconds":30}` | D2/D3 + 动画视图的实例页 | 容器仍 `running`、指标仍上报，但打到它的请求全部超时；CPU 掉到 0、活跃线程数冻结 | `{"action":"resume"}` 或 TTL 保底 | ✅ |
| 网络延迟 | `bash code/lab/scripts/up.sh --profile chaos-proxy` 后 `POST /lab-api/chaos/toxiproxy/mysql/toxics -d '{"type":"latency","name":"lat500","params":{"latencyMs":500,"jitterMs":50}}'` | D1 P95/P99、D3 | 下单 P95 从 ~80ms 升到 ~3.5–4s | `DELETE /chaos/toxiproxy/{proxy}/toxics/{name}` 或 `reset-all` | ✅ |
| 网络带宽压制 | 同上，`{"type":"bandwidth","params":{"rateKbps":100}}` | D1 吞吐、D3 网络 | 大响应变慢，速率受限 | 同上 | ⚪ |
| 网络切断 | `{"type":"timeout","params":{"timeoutMs":1000}}`，或 `POST /chaos/toxiproxy/mysql/disable` | D4 错误、D11 | 写请求 504；**读请求仍 200**（被缓存掩盖）——这是「缓存把故障藏起来」的演示点 | `POST /chaos/toxiproxy/{proxy}/enable` / `reset-all` | ✅ |

toxiproxy 只有三种 toxic：`latency`（+`jitterMs`）、`bandwidth`（`rateKbps`）、`timeout`（`timeoutMs`），参数全为整数、按类型校验，
`slow_link` / `error` 之类**没有接线**。`GET /lab-api/chaos/toxiproxy/state` 看当前 toxic 列表。

**场景 JSON + 向导**（把上面这些串成一条时间线）：`code/lab/scenarios/` 下三个文件，每步都带
`explain{symptom,rootCause,fix,expectation}`，控制台向导直接渲染。

| 场景文件 | 时长 | 时间线 |
|---|---|---|
| `quick-failover.json` | 30s | t=5 `kill jl-app-3` TTL 15 |
| `ha-app-failover.json` | 240s | t=60 `kill jl-app-1` TTL 60 → t=120 `freeze jl-app-2` TTL 30 → t=150 `resume jl-app-2` |
| `redis-sentinel-failover.json` | 90s | t=10 `kill jl-redis-1` TTL 45 → t=25 `resume jl-redis-2`（只是打时间锚点） → t=60 `start jl-redis-1` |

触发：控制台「实验操作台」页选场景 → 开始/下一步/跳过/暂停/恢复/重置；或直接
`POST /lab-api/lab/guide/start {"scenarioId":"quick-failover"}` 再 `/step` 推进。
**暂停会冻结 TTL 倒计时**（不是暂停故障），`POST /lab-api/chaos/stop-all` 无视暂停强制回滚。
k6 侧用 `ha-failover` 场景驱动同一套时间线：`CHAOS_SCENARIO_FILE`（默认 `ha-app-failover.json`）、
`CHAOS_START_OFFSET`（默认 `0s`）、`CHAOS_HTTP_TIMEOUT`（默认 `20s`，**必须大于 stop-grace 10s**，见 `doc/avoid_common_error/15`）、`CHAOS_MAX_TTL`（600）。
`ha-failover` **不需要** `--profile ha`：默认目标就是三个 app 实例，控制面在 base 栈里就起来了。

### 4.6 线程实验室（details/07）

两套状态机并存，**别混**：`thread_lab_demo_state_code`（六态演示线程）与 `thread_lab_lifecycle_state_code`（故障 ACTIVE/PAUSED/STOPPED）。

**七种故障**（`POST /lab/thread/fault`，body `{mode, hold, ttlSeconds, virtual}`）：

| mode | 制造什么 | 指纹（D6） | 特别条件 |
|---|---|---|---|
| `deadlock` | 两个线程反序抢两把锁 | `thread_lab_participant_threads`、blocked 计数；`jcmd` 里 `Found one Java-level deadlock` | 默认是**可排空**的反转；要真死锁设 `LAB_THREAD_DEADLOCK_TRUE=true`，且只在可丢弃实例上用 |
| `stuck` | 线程 park 在永不打开的 latch 上 | waiting 长期不降 | 默认 4 线程（`LAB_THREAD_STUCK_THREADS`） |
| `cpu-burn` | 空转 runnable | `process_cpu_usage` 抬升、runnable 增多 | 默认 2 线程 |
| `heap-leak` | 受控速率分配、有硬顶 | `thread_lab_heap_leaked_bytes` 线性上升、GC 频率上升 | 顶为堆的 `cap-percent`（默认 80） |
| `oom` | 无顶分配，真把堆打爆 | `heap-leak` 曲线 + 最终 OOM | **默认拒绝**：要 `app.lab.thread.oom.throwaway-instance=true`（`LAB_THREAD_OOM_THROWAWAY`） |
| `thread-leak` | 持续漏线程（平台或虚拟） | `thread_lab_participant_threads` 单调上升 | `virtual:true` 走虚拟线程 |
| `starvation` | 小池 + 超额阻塞任务 | `thread_lab_pool_active` / `_pool_queue_size` | 池 2、队列 50、提交 100、任务阻塞 30s |

```bash
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" \
  --header="Content-Type: application/json" \
  --post-data="{\"mode\":\"deadlock\",\"hold\":\"auto\",\"ttlSeconds\":120}" \
  http://localhost:8080/lab/thread/fault'
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" \
  http://localhost:8080/lab/thread/state'
```

`hold` 取 `auto`（默认，到 TTL 自动排空）或 `manual`（永不自动排空，`ttlSeconds` 被忽略）；同一 mode 重复启动返回 409。
生命周期：`POST /lab/thread/fault/{mode}/stop|pause|resume`，或一键 `POST /lab/thread/stop-all`（pause 只冻结 TTL，线程仍在跑）。

**六态演示线程**：`NEW → RUNNABLE → BLOCKED → WAITING → TIMED_WAITING → TERMINATED`，是**真的把 JVM 状态造出来**
（BLOCKED 用 helper 线程持监视器 1.2s、WAITING 用无超时 `Object.wait()`、TIMED_WAITING 用 `sleep`），不是打标签。

```bash
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --post-data="" \
  http://localhost:8080/lab/thread/lifecycle/start'      # 之后 /lifecycle/step 逐态推进
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --post-data="" \
  http://localhost:8080/lab/thread/lifecycle/freeze'     # 冻结推进 + 立即抓一份快照
```

`/lifecycle/step`、`/freeze`、`/resume`、`/reset` 都是空 body；不满足前置条件一律 409（未 start、已冻结、已 TERMINATED、已到 `max-steps=6`）。
响应里 `machineStage` 可能落后于 `state`（helper 释放监视器后线程已 RUNNABLE、stage 仍显示 BLOCKED），页面按 `state` 动画。

**快照**：`POST /lab/thread/snapshot`（**空 body**，理由由服务端自己标 `freeze` / `manual`），落到
`doc/process/Stage1/snapshots/<yyyyMMdd-HHmmss-SSS>_<instance>/`：`thread-dump.txt`（`jcmd Thread.print -l`）、
`heap-info.txt`（`GC.heap_info`）、`class-histogram.txt`（`GC.class_histogram`）、`metadata.json` 与分析模板。
抓取永不抛异常，失败也返回 200 带 `captured=false` 与 `error`，不会把演示打断；目录写坏会被清掉。
`GET /lab/thread/state` 与 `/lifecycle` 在控制台白名单里，故障动作也能从「实验操作台」页跳转「线程实验室」发起。

### 4.7 双 JDK 与 JVM 调优（details/08）

```bash
bash code/lab/scripts/up.sh --profile dual-jdk     # 17 服务，追加 jl-seckill-app-jdk8
bash code/lab/k6/run.sh d7                          # JDK 21（经 Nginx）
BASE_URL=http://seckill-app-jdk8:8080 bash code/lab/k6/run.sh d7   # JDK 8 直连
```

`d7` 是**免登录只读**场景（JDK 8 外壳没有登录端点），所以对比的是商品读路径。
期望（✅）：D7 同时看到两个 runtime 的 RPS / P95 / P99 / GC / 堆 / 线程；启动中位实测 **JDK 8 9.33s vs JDK 21 30.52s**（三实例并发、均 `-Xmx512m`）。
坑：Boot 2.7 / Micrometer 1.9 **没有 `jvm_info`**，用 `jvm_version` 标签区分实例（`doc/avoid_common_error`）。

**调优台 D8**：换 JVM 参数跑同一档位压测并留下注解与报告。

```bash
bash code/lab/scripts/tune.sh --name xmx256 --flags "-Xmx256m" --scenario load --duration 60s
bash code/lab/scripts/tune.sh --restore
```

一次运行只做六件事：记录当前 flags → **只重建 app-1/2/3** 并注入 `APP_JAVA_TOOL_OPTIONS`（不改 `.env`）→ reload Nginx 并探活 → 打 Grafana 注解（tag `tuning,<profile>`）→ 可选 reset 数据 + 跑短压测 → 写 `doc/process/Stage1/benchmark/tuning-<时间>-<profile>.md`。
期望症状：`-Xmx256m` 下 GC 停顿频率与最长停顿显著上升、`768m` 时退化为 SerialGC 形态。
当前只做过 `-Xmx` 的对照，GC 选择 / `-Xms` / `-Xss` / Metaspace / 池大小未做真机扫描。

### 4.8 压测档位（details/09）

`bash code/lab/k6/run.sh <smoke|load|stress|spike|soak|d7|ha-failover|db-failover|cache-lab>`，其余名字直接 exit 2；
`traffic` **不在** run.sh 里，只能作为 compose 服务起（见 §4.9）。

| 场景 | 负载形状（默认） | 打什么 | 阈值 | 备注 |
|---|---|---|---|---|
| `smoke` | 1 VU × 1 次 | 登录→商品→下单（回放）→秒杀 | flow/server error、checks | 冒烟 |
| `load` | `ramping-vus` 50→200，10m | **只读商品**（不动库存） | P95 300ms / P99 800ms / 错误率 1% / checks 99% | 基线档位 |
| `stress` | 阶梯 50→500（1+2+2+2+2+2+1m） | 只读 | **故意不设阈值**：这个场景存在的意义是观察拐点 | 看 D1/D9 找到 knee |
| `spike` | 50 → 800（10s 上冲）→ 60s → 10s 下冲 | **打秒杀，消耗商品 20 库存** | 同 `load` | 限流/售罄的触发器 |
| `soak` | 100 VU 恒定 30m | 只读 | 同 `load` | 长稳；设计时长未真机跑满 |
| `d7` | 复用 `load` 形状 | 只读、免登录 | 同 `load` | 双 JDK |
| `ha-failover` | `CHAOS_VUS=20` × `CHAOS_DURATION=4m` + 1 VU 推场景时间线 | 只读 | flow/server <1%、P95/P99 | 见 §4.5 |
| `db-failover` | 读 20 VU + 写 1 VU / 2s | 读 + 真实下单 | 读 `http_req_failed{flow:product}` 必须为 0、写成功率 ≥0.85 | 见 §4.4 |
| `cache-lab` | 10 VU 读 + 八模式时间线 | 只读商品 | 读期望状态 + 探针 404 | 见 §4.2 |

每次运行都带 `run_id`（默认 `k6-<时间戳>-<随机>`，`RUN_ID` 可覆盖），**每条指标都打了这个 tag**，所以 D9 能按 run 切换、
D10 能按 run_id 与 dwell 时间窗对齐。归档：

```bash
bash code/lab/scripts/bench.sh load -- LOAD_DURATION=1m     # 先 reset 再跑，产物入 reports/<run_id>/ 与 doc/process/Stage1/benchmark/
bash code/lab/scripts/compare.sh <runA> <runB>              # 两个窗口的 PromQL 对照
```

`bench.sh` 的退出码就是 k6 的退出码——**阈值破线时非零是期望结果**，`run.sh` 会把这句话打在结尾。
k6 容器只挂了 `/scripts:ro` 与 `/reports`：`K6_SUMMARY_EXPORT` 指宿主路径会 `exit 107`（`doc/avoid_common_error/18`）；
`per-vu-iterations` 的 `maxDuration` 默认 10m，会把长时间线**静默截断**（`exit 0` + `checks 100%` 也一样），
预算必须由 dwell 推导（`doc/avoid_common_error/17`）。

### 4.9 操作台里能点的东西（details/10）

`http://127.0.0.1:8080/lab/` 是**单页四个标签**（没有路由、URL 不带状态），浏览器只和 chaos-controller 说话：

| 标签 | 可点 |
|---|---|
| 实验操作台 | 场景向导（开始/下一步/跳过/暂停/恢复/重置）、状态栏（向导状态、活跃故障胶囊、容器汇总、还原模式）、背景流量 开启/停止、**清除全部故障**、日志面板（tail 1–1000）、token 输入卡（存 localStorage） |
| 线程实验室 | 实例选择（`app-1/2/3`）、七种故障 + TTL 表单、六态生命周期、快照按钮 |
| 缓存实验室 | 八模式切换、播种、预热、状态查看 |
| 动画视图 | 拓扑视图 / 实例视图 / 缓存流动 三个内层页 |

背景流量是 `jl-traffic` 容器（k6 `traffic.js`，50 VU 只读、默认 30m）：`up.sh` 每次都把它**创建成停止态**，
只有 `/lab/traffic/start` 或 `docker start jl-traffic` 会让它跑起来。控制台状态栏默认 5s 轮询
（`VITE_LAB_SYNC_INTERVAL_MS`），实例列表来自 `VITE_LAB_APP_INSTANCES`。
一键复位用「清除全部故障」（`POST /lab-api/chaos/stop-all`）；`stop-all` 会连 Toxiproxy 一起 reset。
冒烟：`bash code/lab/scripts/console-smoke.sh`（Playwright，含向动画视图注入一次 10s `freeze` 来断言故障徽标）。

### 4.10 把安全边界本身当异常演示（details/11）

这四条都是**故意的**，用来讲「为什么实验台不能上生产」：

| 现象 | 触发 | 期望 | 证据 |
|---|---|---|---|
| `/lab/toggles`、`/lab/config` 从浏览器打不通 | `curl -i http://127.0.0.1:8080/lab/toggles` | **404**（Nginx 硬拒），`/api/daemon/` 同样 404 | ✅ `verify.sh:783-787` 就断言这条 |
| 内部 lab API 无 token | `docker exec jl-app-1 sh -c 'wget -S -qO- http://127.0.0.1:8080/lab/toggles'` | **401**；带 token 才返回六个 key | ✅ `verify.sh:798-817` |
| 杀数据库被拒 | `POST /lab-api/chaos/instances/jl-redis-1/actions {"action":"kill"}`，且 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` 为空 | **拒绝**（空 = 全拒），错误信息里带目标名；但 TTL 回滚**永远不被拦** | ✅ 设计见 `DatastoreTargetPolicy.java:42-101` |
| 碰非实验容器 | 目标不带 `lab=java-learn` 标签 | 拒绝；controller 只看得见项目 `java-learn-lab` 的容器 | ⚪ |

---

## 5. API 与入口

| 入口 | 地址 | 说明 |
|---|---|---|
| 业务 API | `http://127.0.0.1:8080/api/**` | `POST /login`、`GET /products/{id}`、`POST /orders`（要 `Idempotency-Key`）、`POST /seckill/orders`（要 `Idempotency-Key`，异步 202 / 同步 200）、`GET /orders/{id}`、`GET /seckill/orders/{requestId}`、`GET /hello` |
| 实验操作台 | `http://127.0.0.1:8080/lab/` | Vue 单页四标签（见 §4.9）；`/lab` 会 301 到 `/lab/` |
| 控制面 | `http://127.0.0.1:8080/lab-api/**` | chaos-controller（容器内 8081，不发布宿主端口）：`/chaos/instances/{name}/actions`、`/chaos/actions`、`/chaos/stop-all`、`/chaos/toxiproxy/**`、`/lab/guide/**`、`/lab/traffic/**`、`/containers`、`/logs/{name}`、`/metrics` |
| 应用代理 | `http://127.0.0.1:8080/lab-api/chaos/app/{instance}/lab/…` | **仅白名单**：`/lab/thread/**`（13 个子路径）、`/lab/cache/state|preheat`（GET）、`/lab/cache/mode|seed|preheat`（POST）。只有 `fault` 与 `cache/mode` 带 body |
| 应用内 lab API | 仅 `jl-net` 内 | `/lab/toggles`、`/lab/config`（**不在代理白名单**，Nginx 404）、`/lab/cache/*`、`/lab/thread/*` |
| Prometheus 代理 | `http://127.0.0.1:8080/prom/` | 面板与查询都走这里 |
| Grafana | `http://127.0.0.1:3000` | D1–D4、D6–D11 已置备（D5 由 Tempo UI 承担，无面板文件） |
| RabbitMQ 管理 | `http://127.0.0.1:15672` | 队列与消息 |

所有 `/lab-api/**` 与 `/lab/**` 都要求 `X-Lab-Token`（默认值见 `.env.example`，生产勿用）。
控制面豁免路径只有 `/health`、`/metrics`、`/info`、`/actuator*`。

---

## 6. 观测：面板看什么

| 面板 | 内容 | 触发后先看哪块 |
|---|---|---|
| D1–D2 HTTP/JVM | 请求量/延迟、JVM 堆/GC/线程、实例维度 | 实例宕机/挂死看 D2 序列断裂 + P95 台阶 |
| D3 资源与网络 | 容器 CPU/内存/网络（cAdvisor 在 WSL2 无 per-container 序列 → WARN）、Nginx、Redis 连接 | `freeze` 后 CPU 掉到 0 |
| D4 业务与高可用 | 下单量/失败原因、幂等、超卖、MQ 堆积、熔断/限流、缓存命中、Hikari | 限流看 `available_permissions`，熔断看 `circuitbreaker_state` |
| D5 链路 | Tempo service graph（由 Tempo UI 承担） | — |
| D6 线程实验室 | 两套状态机时间线、blocked/waiting/runnable 指纹、堆/CPU/池 | 故障注入后先看 `fault_active` 与参与者线程数 |
| D7 双 JDK | 两 runtime 的吞吐/延迟/GC/堆/线程/启动 | `jvm_version` 标签区分 |
| D8 JVM 调优 | 同一实例不同 `-Xmx` / GC 预设下的堆、停顿与频率、分配/晋升、最长停顿、线程、CPU、RPS、P95/P99、Hikari、启动耗时 | `tune.sh` 的 `tuning,<profile>` 注解处 |
| D9 压测 | k6 VUs/RPS/P95/P99、`run_id` 切换、故障标注 | 先看 `run_id` 变量选对运行 |
| D10 缓存实验室 | L1/L2 命中、DB fallback 按 reason、布隆、空值缓存、击穿/雪崩策略、预热；末尾一行是 **k6 cache-lab 对照（按 `run_id` / `mode`）**：读速率、模式切换事件、DB 回落对照、四族防护、state gauge、L1 命中率 | 看八模式对照前先把 `run_id` 变量选上 |
| D11 数据库高可用 | 集群成员状态/主节点/探针健康/节点可达/Redis 主从/故障动作 + 两块口径 text | `mysql_cluster_primary` 跳变点 |

常用 PromQL：

```promql
mysql_cluster_primary{job="chaos-controller"}                 # 谁是主（导出名无 _info）
mysql_cluster_probe_up{job="chaos-controller"}                # 0 = 探针坏了（拓扑保留最后已知值）
sum by (result) (rate(cache_avalanche_l1_total[1m]))          # 雪崩 L1 优先命中结构
sum by (reason) (rate(cache_db_fallbacks_total[1m]))          # miss / penetration / breakdown / avalanche
resilience4j_circuitbreaker_state{name="seckillBackend"}      # 1 = OPEN
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{job=~"seckill-app.*"}[5m]))
sum by (mode) (k6_cache_lab_db_fallbacks{run_id="k6-…"})      # k6 侧每 dwell 差值（D10 末行）
```

> 即时查询的默认回看是 5m：跑完几小时后再查 `rate(…[1m])` 会拿到空序列，用 `max_over_time(…[6h])` 复核。

---

## 7. 数据重置与清理

```bash
bash code/lab/scripts/reset-lab.sh --dry-run     # 预览
bash code/lab/scripts/reset-lab.sh --yes         # 停 app → 清订单/库存 → FLUSHDB → 清队列 → 启 app（约 97s）
bash code/lab/scripts/verify.sh --cleanup        # 自检后显式停栈
bash code/lab/scripts/down.sh                    # 停栈，保留命名卷
# bash code/lab/scripts/down.sh --purge           # 危险：删卷，会丢全部演示数据
```

`reset-lab.sh` 从不调 `compose down`、从不删卷，且固定项目 `java-learn-lab`。
**Router 模式下它清的是 standalone 的 `jl-mysql`**——db-failover 演练后要自己换商品 id 或补库存。
一键复位故障：`POST /lab-api/chaos/stop-all`（容器动作 + Toxiproxy 一起清）；线程侧另有 `POST /lab/thread/stop-all`。

bench：`bash code/lab/scripts/bench.sh smoke|load|stress|spike|soak -- LOAD_DURATION=1m`，
归档到 `code/lab/k6/reports/<run_id>/`（summary.json / k6.log / manifest.json / report.html）与 `doc/process/Stage1/benchmark/`。

---

## 8. 常见问题

| 现象 | 处理 |
|---|---|
| `up.sh` 报缺 env 键 | 按提示把键补进 `code/lab/docker/.env`（模板在 `.env.example`），脚本会逐个列出键名 |
| `up.sh --profile db-ha` 被拒 | MySQL 层由 `db-ha-up.sh` 起（它自己带 `--profile db-ha|ha`），`up.sh` 明确拒绝该值 |
| `verify.sh` Redis 内容检查 FAIL | app 处于 `REDIS_MODE=sentinel`，先还原 standalone（见 §4.3） |
| 容器里 `wget` 有、`curl`/`bash` 没有 | 正常：`eclipse-temurin:*-alpine` 只有 BusyBox。一律 `docker exec <c> sh -c 'wget …'`（`doc/avoid_common_error/16`） |
| 缓存防护计数器全程为 0 | 流量没打向缺陷：读一个区间外的 id、或删热点键、或显式 arm 雪崩重播（§4.2） |
| `cache-lab` 启动即报 `CACHE_LAB_MODE_DURATION is too short to re-arm` | 雪崩护栏：dwell 必须 ≥ TTL + margin；调大 dwell 或调小 `CACHE_LAB_AVALANCHE_TTL_SECONDS` |
| k6 `exit 0` + `checks 100%`，但少跑了一个 dwell | `per-vu-iterations` 的 `maxDuration` 默认 10m 静默截断（`doc/avoid_common_error/17`） |
| `K6_SUMMARY_EXPORT` 报 `exit 107` | 那是**容器内**路径，`/scripts` 是只读挂载，必须落在 `/reports/` 下（`doc/avoid_common_error/18`） |
| 杀主后 app 读还是 200 | 正常：L1(60s)/L2 缓存掩盖了切换；要看错误毛刺需显式设 Lettuce 超时 |
| 秒杀返回 429 / 409 | 预期：限流与售罄/重放语义，D9 把它们从错误率里单独分出来 |
| 控制台状态栏一直 warn | 有按需/一次性容器在退出态（traffic、db-ha init、jdk8 外壳）；用 `VITE_LAB_ON_DEMAND_CONTAINERS` 调整排除列表 |
| cAdvisor 面板无数据 | Docker Desktop/WSL2 限制，已在 verify 中记为唯一 WARN |
| 中文日志/命令行乱码 | Windows 控制台为 GBK；抓输出到文件时显式 UTF-8 |
| Pull 不到 MySQL Router 8.4 | 用 `container-registry.oracle.com/mysql/community-router:8.4`（`.env.mysql.example` 已记） |
| 演示后指标异常 | `reset-lab.sh --yes` + 重建被改过的 app 层；Toxiproxy 用 `stop-all` 复位 |

---

## 9. 资源与安全边界

- 全部服务仅绑定本机；Grafana/RabbitMQ 账号默认值在 `.env.example`，**不要暴露到局域网**。
- chaos-controller 以 root 挂载 rw Docker socket，只能操作带 `lab=java-learn` 标签的容器；datastore 目标另有闭环白名单（空 = 全拒）。
- 单点：Nginx、MySQL Router、RabbitMQ、Prometheus/Grafana —— 本地演示接受，已在设计 §8.6 声明为范围外。
- 已知限制与未收口项：`doc/design/Stage1/details/README.md` §3.2（缺口清单）与 §3.3（文档说反话的位置）；
  每步证据在 `doc/process/Stage1/step-*.md`，里程碑级状态在 `doc/process/STAGE-01阶段交接-2026-10-26.md`。

---

## 10. 文档地图

| 想去哪 | 去哪 |
|---|---|
| 总体设计 | `doc/design/Stage1/2026-10-26-ha-concurrency-lab-design.md` |
| 安装、镜像构建与打包 | `doc/design/安装与打包手册.md` |
| 细节设计（逐功能 + 代码对照） | `doc/design/Stage1/details/`（01–11，索引在 README，缺口清单在 §3.2） |
| 图集（Mermaid，可编辑） | `doc/design/Stage1/details/diagrams/` |
| 每步交接与证据 | `doc/process/Stage1/step-*.md`、`doc/process/STAGE-01阶段交接-2026-10-26.md` |
| 踩过的坑 | `doc/avoid_common_error/index.md`（18 条） |
| 工程规约（配置外置、交接、评审） | `doc/design/工程规约.md` |
| 压测场景与 k6 环境变量 | `code/lab/k6/README.md` |
| 文档断言的可执行校验 | `code/lab/docclaims/claims.yml` + `node code/lab/scripts/check-doc-claims.mjs` |
