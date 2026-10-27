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
  design/                 总体设计、方案对比、工程规约
  design/details/         细节设计 01–11（每篇带代码对照表）+ diagrams/（Mermaid 图集）
  process/                每步交接（step-*.md）、benchmark/、阶段交接
  避坑/                   真实踩过的坑（index.md + 每坑一篇）
```

---

## 3. 快速开始

```bash
# 1) 启动 dev 栈（16 个服务：MySQL/Redis/RabbitMQ/Nginx/三实例/观测栈）
bash code/lab/scripts/up.sh --profile dev

# 2) 自检（默认保留栈运行；约 2–3 分钟）
bash code/lab/scripts/verify.sh            # 期望 104 PASS / 1 WARN（cAdvisor/WSL2）

# 3) 全栈冒烟（会消耗 product 1 与 20 各 1 件）
bash code/lab/scripts/smoke.sh             # 期望 7/7

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
| `--profile ha` | **25** | Redis Sentinel（1 主 2 从 3 哨兵 + exporter） |
| `--profile db-ha` | **25** | MySQL InnoDB Cluster（3 节点 + Router + exporter + 两个 init job） |
| `--profile chaos-proxy` | **18** | 叠加 Toxiproxy，应用经代理访问 Redis/MySQL（需重建 app 层） |
| `--profile dual-jdk` | **17** | 追加 JDK 8 外壳实例，用于 D7 对比 |

> ha 与 db-ha 可以同时开着（合计 34 个 lab 容器）；`chaos-proxy` 会重建 app 层，演示前单独安排。

---

## 4. 五个可演示的实验（每条 5 分钟内）

### 4.1 缓存四类防护（D10）

1. `POST /lab-api/...`？不是——缓存实验走应用内端点，操作台尚未提供页面时用容器内 curl：
   ```bash
   docker exec jl-app-1 sh -c 'curl -s -X POST localhost:8080/lab/cache/seed -H "X-Lab-Token: $LAB_TOKEN"'
   docker exec jl-app-1 sh -c 'curl -s -X POST localhost:8080/lab/cache/mode -H "X-Lab-Token: $LAB_TOKEN" -H "Content-Type: application/json" -d "{\"mode\":\"bloom\"}"'
   ```
2. 依次切 `off → null-cache/bloom`（穿透）、`mutex/logical-expire`（击穿）、`ttl-jitter/l1-cache/db-fallback`（雪崩）。
3. 在 Grafana **D10** 对比：DB fallback 按 reason 堆叠、L1/L2 命中率、布隆拦截、预热条数。
4. `off` 是「缺陷可见」基线：同样的请求会打出 DB 回退尖峰。

### 4.2 Redis Sentinel 切换（D11 / 日志）

```bash
bash code/lab/scripts/up.sh --profile ha          # 25 服务；app 渲染进 sentinel 模式
bash code/lab/scripts/sentinel-status.sh          # 只读拓扑
# 杀主（走 chaos-controller，TTL 45s 自动回滚）
curl -s -X POST http://127.0.0.1:8080/lab-api/chaos/instances/jl-redis-1/actions \
  -H "X-Lab-Token: $LAB_TOKEN" -H 'Content-Type: application/json' -d '{"action":"kill","ttlSeconds":45}'
```
期望：约 7.6s 完成晋升、Nginx 读路径 ~9s 恢复 200（app 不重启）、TTL 到点旧主以从身份归队。
演练后把 app 还原 standalone（否则 `verify.sh` 的 Redis 内容检查会假失败）：
```bash
docker compose --project-name java-learn-lab --project-dir code/lab/docker --env-file code/lab/docker/.env \
  -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml up -d app-1 app-2 app-3
```

### 4.3 MySQL Router 切换（D11）

```bash
bash code/lab/scripts/db-ha-up.sh && bash code/lab/scripts/db-cluster-status.sh   # RESULT: ok
# 应用经 Router 运行：MYSQL_MODE=router + DB_WRITE_URL=...:6446 / DB_READ_URL=...:6447
# 杀主（白名单需含 jl-mysql-[1-3]，见 .env）
```
期望：写 6446 落到主、读 6447 轮转到从；杀主后本组重新选主、Router 重定向，读请求 70/70 保持 200；TTL 回滚后旧主以 SECONDARY 归队；`mysql_cluster_primary` 指标随之跳变。

**带压测的版本**（k6 `db-failover`：读 20 VU + 写 1 VU 每 2s 一单，同时杀主）：

```bash
bash code/lab/scripts/db-cluster-status.sh          # 先确认当前 PRIMARY 是谁
TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
LAB_TOKEN="$TOKEN" \
  CHAOS_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  DB_FAILOVER_TARGET=jl-mysql-1 DB_FAILOVER_TTL_SECONDS=45 \
  DB_FAILOVER_DURATION=3m PRODUCT_ID=2 \
  bash code/lab/k6/run.sh db-failover
```

三个前提：app 层必须 `MYSQL_MODE=router`（改完 `.env` 要重建 app 层并 `docker exec jl-nginx nginx -s reload`）；
`DB_FAILOVER_TARGET` 必须是**当前主**且不是探针容器（默认 `jl-mysql-3`）；写会真实扣库存（3m ≈ 85 单），
router 模式下 `reset-lab.sh` 默认清的是 standalone `jl-mysql`，所以换个还有库存的 `PRODUCT_ID` 最省事。

实测（2026-10-27，`run_id=k6-20261027-161756-dbfo2`）：k6 退出 0，读 **3580 次零失败**、p95 8.3ms；
写 85 次里只有 **1 次**落在选举窗口内（`status=500`，kill 后 7s），成功率 **98.8%**；
`mysql_cluster_member_count` 3→2→3（60s 内旧主以 `ONLINE SECONDARY` 回组），
`mysql_cluster_primary` 由 `mysql-1` 翻到 `mysql-2`，Router 6446 跟随；
teardown 后 `/chaos/actions` 为空。证据与阈值预算的算术见
`doc/process/step-48-k6-ha-cache-scenarios.md` 第 6 节，命令细节见 `code/lab/k6/README.md`。

### 4.4 线程实验室（D6）

```bash
# 注入七种故障之一（deadlock/stuck/cpu-burn/heap-leak/oom/thread-leak/starvation）
docker exec jl-app-1 sh -c 'curl -s -X POST localhost:8080/lab/thread/fault -H "X-Lab-Token: $LAB_TOKEN" -H "Content-Type: application/json" -d "{\"mode\":\"deadlock\",\"hold\":\"auto\",\"ttlSeconds\":120}"'
# 六态演示线程
docker exec jl-app-1 sh -c 'curl -s -X POST localhost:8080/lab/thread/lifecycle/start -H "X-Lab-Token: $LAB_TOKEN"'
```
- D6 看 `thread_lab_demo_state_code`（六态）与 `thread_lab_lifecycle_state_code`（故障 ACTIVE/PAUSED/STOPPED）是**两台不同的状态机**。
- `freeze` 会抓一份 `jcmd` 快照到 `doc/process/snapshots/<时间戳>_<instance>/`（thread dump / heap info / 类直方图 + metadata + 分析模板）。
- 演示 `true deadlock` 需显式开 `LAB_THREAD_DEADLOCK_TRUE=true`；默认模式是可排空的反转（不会永久死锁）。

### 4.5 双 JDK 对比（D7）

```bash
bash code/lab/scripts/up.sh --profile dual-jdk     # 17 服务，追加 jdk8 实例
bash code/lab/k6/run.sh d7                          # 免登录只读场景
```
期望：D7 同时看到两个 runtime 的 RPS / P95 / P99 / GC / 堆 / 线程；启动中位实测 **JDK 8 9.33s vs JDK 21 30.52s**（三实例并发、均 `-Xmx512m`）。注意 Boot 2.7/Micrometer 1.9 没有 `jvm_info`，用 `jvm_version` 标签区分。

### 4.6 网络故障（chaos-proxy + Toxiproxy）

```bash
bash code/lab/scripts/up.sh --profile chaos-proxy   # 18 服务；app 经 toxiproxy 访问 Redis/MySQL
curl -s -X POST http://127.0.0.1:8080/lab-api/chaos/toxiproxy/mysql/toxics \
  -H "X-Lab-Token: $LAB_TOKEN" -H 'Content-Type: application/json' \
  -d '{"type":"latency","name":"lat500","params":{"latencyMs":500,"jitterMs":50}}'
```
实测：下单 P95 从 ~80ms 升到 ~3.5–4s；`disable` mysql 代理后写请求 504、读请求仍 200（缓存掩盖）；`POST /lab-api/chaos/stop-all` 一键复位（`proxiesReset=2`）。

---

## 5. API 与入口

| 入口 | 地址 | 说明 |
|---|---|---|
| 业务 API | `http://127.0.0.1:8080/api/**` | login / products / orders / seckill |
| 实验操作台 | `http://127.0.0.1:8080/lab/` | Vue 页面（向导/状态栏/日志） |
| 控制面 | `http://127.0.0.1:8080/lab-api/**` | chaos-controller：`/chaos/**`、`/lab/guide/**`、`/lab/traffic/**`、`/containers`、`/logs/{name}`、`/metrics` |
| 应用内 lab API | 仅 `jl-net` 内 | `/lab/toggles`、`/lab/config`、`/lab/cache/*`、`/lab/thread/*`（Nginx 不代理） |
| Prometheus 代理 | `http://127.0.0.1:8080/prom/` | 面板与查询 |
| Grafana | `http://127.0.0.1:3000` | D1–D4、D6–D11 已置备（D5 由 Tempo UI 承担，无面板文件） |
| RabbitMQ 管理 | `http://127.0.0.1:15672` | 队列与消息 |

所有 `/lab-api/**` 与 `/lab/**` 都要求 `X-Lab-Token`（默认值见 `.env.example`，生产勿用）。

---

## 6. 观测：面板看什么

| 面板 | 内容 |
|---|---|
| D1–D2 HTTP/JVM | 请求量/延迟、JVM 堆/GC/线程、实例维度 |
| D3 资源与网络 | 容器 CPU/内存/网络（cAdvisor 在 WSL2 无 per-container 序列 → WARN）、Nginx、Redis 连接 |
| D4 业务与高可用 | 下单量/失败原因、幂等、超卖、MQ 堆积、熔断/限流、缓存命中、Hikari |
| D5 链路 | Tempo service graph（由 Tempo UI 承担） |
| D6 线程实验室 | 两套状态机时间线、blocked/waiting/runnable 指纹、堆/CPU/池 |
| D7 双 JDK | 两 runtime 的吞吐/延迟/GC/堆/线程/启动 |
| D8 JVM 调优 | 同一实例在不同 `-Xmx` / GC 预设下的堆、GC 停顿与频率、分配/晋升速率、最长停顿、线程、CPU、RPS、P95/P99、Hikari、启动耗时（配合 `tune.sh` 采样与 annotation） |
| D9 压测 | k6 VUs/RPS/P95/P99、run_id 切换、故障标注 |
| D10 缓存实验室 | L1/L2 命中、DB fallback 按 reason、布隆、空值缓存、击穿/雪崩策略、预热 |
| D11 数据库高可用 | 集群成员状态/主节点/探针健康/节点可达/Redis 主从/故障动作 |

常用 PromQL：

```promql
mysql_cluster_primary{job="chaos-controller"}                 # 谁是主（导出名无 _info）
mysql_cluster_probe_up{job="chaos-controller"}                # 0 = 探针坏了
sum by (result) (rate(cache_avalanche_l1_total[1m]))          # 雪崩 L1 优先命中结构
histogram_quantile(0.95, rate(http_server_requests_seconds_bucket{job=~"seckill-app.*"}[5m]))
```

---

## 7. 数据重置与清理

```bash
bash code/lab/scripts/reset-lab.sh --dry-run     # 预览
bash code/lab/scripts/reset-lab.sh --yes         # 停 app → 清订单/库存 → FLUSHDB → 清队列 → 启 app（约 97s）
bash code/lab/scripts/verify.sh --cleanup        # 自检后显式停栈
bash code/lab/scripts/down.sh                    # 停栈，保留命名卷
# bash code/lab/scripts/down.sh --purge           # 危险：删卷，会丢全部演示数据
```

bench：`bash code/lab/scripts/bench.sh smoke|load -- LOAD_DURATION=1m`，归档到 `code/lab/k6/reports/<run_id>/` 与 `doc/process/benchmark/`。

---

## 8. 常见问题

| 现象 | 处理 |
|---|---|
| `up.sh` 报缺 env 键 | 按提示把键补进 `code/lab/docker/.env`（模板在 `.env.example`），脚本会逐个列出键名 |
| `verify.sh` Redis 内容检查 FAIL | app 处于 `REDIS_MODE=sentinel`，先还原 standalone（见 4.2） |
| 控制台状态栏一直 warn | 有按需/一次性容器在退出态（traffic、db-ha init、jdk8 外壳）；用 `VITE_LAB_ON_DEMAND_CONTAINERS` 调整排除列表 |
| cAdvisor 面板无数据 | Docker Desktop/WSL2 限制，已在 verify 中记为 WARN |
| 中文日志/命令行乱码 | Windows 控制台为 GBK；抓输出到文件时显式 UTF-8 |
| Pull 不到 MySQL Router 8.4 | 用 `container-registry.oracle.com/mysql/community-router:8.4`（`.env.mysql.example` 已记） |
| 杀主后 app 读还是 200 | 正常：L1(60s)/L2 缓存掩盖了切换；要看错误毛刺需显式设 Lettuce 超时 |
| 演示后指标异常 | `reset-lab.sh --yes` + 重建被改过的 app 层；Toxiproxy 用 `stop-all` 复位 |

---

## 9. 资源与安全边界

- 全部服务仅绑定本机；Grafana/RabbitMQ 账号默认值在 `.env.example`，**不要暴露到局域网**。
- chaos-controller 以 root 挂载 rw Docker socket，只能操作带 `lab=java-learn` 标签的容器；datastore 目标另有闭环白名单（空 = 全拒）。
- 单点：Nginx、MySQL Router、RabbitMQ、Prometheus/Grafana —— 本地演示接受，已在设计 §8.6 声明为范围外。
- 已知限制清单见 `doc/process/阶段交接-2026-10-26.md` 的「已知缺陷 / 未验证」。

---

## 10. 文档地图

| 想去哪 | 去哪 |
|---|---|
| 总体设计 | `doc/design/2026-10-26-ha-concurrency-lab-design.md` |
| 细节设计（逐功能 + 代码对照） | `doc/design/details/`（01–11，索引在 README） |
| 图集（Mermaid，可编辑） | `doc/design/details/diagrams/` |
| 每步交接与证据 | `doc/process/step-*.md`、`doc/process/阶段交接-2026-10-26.md` |
| 踩过的坑 | `doc/避坑/index.md` |
| 工程规约（配置外置、交接、评审） | `doc/design/工程规约.md` |
