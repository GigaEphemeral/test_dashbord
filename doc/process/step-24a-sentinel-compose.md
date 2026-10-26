> 步骤交接 · M3-1a Redis Sentinel compose 与 HA profile
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / 本次提交 `feat: add Redis Sentinel HA compose profile and failover tooling`（父提交 `ab4ca38`）
> 关联任务：#47（M3-1a 本步）；下游 #48（M3-1b 应用 Sentinel 客户端与双模式配置）、#49（M3-1c Sentinel 故障目标白名单与切换场景验收）
> 设计依据：`doc/design/2026-10-26-ha-concurrency-lab-design.md` §8.2（Redis Sentinel）、§3.1（≈800MB 预算）、§3.3（dev=单机 Redis、ha=Sentinel，通过 env 切换）；`doc/design/工程规约.md` 第 1 节（配置外置）
> 测试 / verify：`verify.sh` 复跑 **103 PASS / 1 WARN / 0 FAIL**（dev 基线 97 + 6 项 Sentinel 检查；唯一 WARN 是既有 cadvisor/WSL2 限制，非本步引入）；compose 不变式：默认渲染 **16** 服务、`--profile ha` 追加 **9** 服务（合计 25）；`bash -n` / `sh -n` 全过，四个 lab 脚本均 `set -euo pipefail`，bind 挂载脚本/模板均为 LF 无 CRLF
> 运行状态：dev 16 常驻 + ha 9（`jl-redis-1/2/3`、`jl-sentinel-1/2/3`、`jl-redis-{1,2,3}-exporter`）全部 running healthy；切换演练后主为 `jl-redis-2`、两从在线复制、`jl-redis-1` 已复位为从；未 `--purge`、未删卷、未触碰 `jl-redis`/`jl-app-*`/`jl-mysql`/`jl-rabbitmq`

# 当前进展

- M3-1a 完成：把设计 §8.2 的 Redis Sentinel 拓扑（1 主 + 2 从 + 3 哨兵，quorum=2，主名 `mymaster`，每数据节点独立 `redis_exporter`）落到一个 profile 门控的 `ha` overlay，并交付只读状态工具与演练证据。
- 切换演练成功闭环：`docker stop` 主 → 哨兵 SDOWN + 选举 + `+switch-master` 晋升从节点 → 幸存从重挂新主 → 新主的 exporter 正常抓取 → `docker start` 旧主、由哨兵重定向为从并在线复制。实测 **5.78s** 从停主到新主地址发布、**~15s** 旧主复挂为从。
- 默认 dev 栈零影响：`ha` 服务只叠加、不覆盖 base/dev 任何服务；profile 门控使默认 `docker compose config --services` 仍是 16。
- 未做（本步边界）：应用尚未接入 Sentinel（属 #48）；`jl-redis-*-exporter` 尚未被 Prometheus 抓取（见缺陷项）；MySQL InnoDB Cluster（§8.3）是后续任务。

# 完成功能项

## 1. `code/lab/docker/compose.ha.yml`（287 行，profile `ha`）

- 三个 YAML 锚点复用：`x-redis-ha-node`（数据节点）、`x-redis-ha-sentinel`（哨兵）、`x-redis-ha-exporter`（导出器），所有值从 `.env` 插值，无运行期硬编码。
- 服务：`redis-1`（初始主，无 `REDIS_HA_REPLICAOF_HOST`）、`redis-2`/`redis-3`（`--replicaof` 指向 `REDIS_HA_REDIS_1_IP`）、`sentinel-1/2/3`、`redis-1/2/3-exporter`（`expose: 9121`，`REDIS_ADDR=redis://redis-N:${REDIS_HA_NODE_PORT}`）。
- 每个数据节点/哨兵双联网：`jl-net`（dev 栈可达路径 + exporter 抓取）+ `jl-sentinel-net`（固定 IP，见下）；导出器与哨兵在 `jl-net`。
- 依赖门控：从节点 `depends_on: redis-1: service_healthy`；哨兵依赖三数据节点 healthy；导出器依赖对应数据节点 healthy。
- `jl-sentinel-net`：本文件内声明（不进 base，避免改动 `jl-net` 触发 dev 容器重建），`ipam` 固定子网 `${REDIS_HA_SUBNET}`，节点 IP 固定。
- 资源：`mem_limit` 取 `REDIS_HA_NODE_MEM_LIMIT/…SENTINEL…/…EXPORTER…`；`/data` 用 tmpfs（`REDIS_HA_TMPFS_SIZE`），**无命名卷**——`down --purge` 的卷清单因此完全不变。
- 标签统一 `lab: java-learn` + `lab.role: redis|redis-sentinel|exporter`；`restart: unless-stopped`（导出器同）。

## 2. `code/lab/docker/redis/`（两个 `sh` 入口 + 一个模板）

- `redis-node-entrypoint.sh`：从环境拼装 `redis-server … [--replicaof h p] [--requirepass/--masterauth]`，只在这里出现 Redis 的**选项名**，值全部来自 env。`REDIS_HA_REPLICAOF_HOST` 为空即当主；`REDIS_HA_ANNOUNCE_IP` 非空才追加 `--replica-announce-ip/port`；`REDIS_HA_REQUIRE_AUTH=true` 且密码为空时**快速失败**。`masterauth` 是被降级旧主能重挂新主的关键。
- `sentinel-entrypoint.sh`：把只读 `sentinel.conf.tpl` 渲染成 tmpfs 上可写副本再 `redis-server <conf> --sentinel`；占位符 `@NAME@` 从模板自身发现（注释行剔除），缺失/空值即报错退出，替换值做 sed 转义（密码可能含 `\&|`）。
- `sentinel.conf.tpl`：`monitor/down-after/parallel-syncs/failover-timeout/auth-pass/resolve-hostnames/announce-hostnames` 全为占位符；文档说明为何 `monitor` 必须是 **IP 而非主机名**（否则 `docker stop` 触发 `#tilt`）。

## 3. `code/lab/scripts/sentinel-status.sh`（257 行，只读）

- 并列打印哨兵视角（`get-master-addr-by-name`、`master` flags、quorum/parallel-syncs、failover-state、known slaves/sentinels、`INFO sentinel` 的 `master0` 行、`REPLICAS`/`SENTINELS` 端点）与数据节点视角（`INFO` 的 role/connected_slaves/master_host:port/master_link_status/clients/ops）。
- 严格只读：只跑 `INFO` 与只读 `SENTINEL` 子命令，绝不 `failover`/`REPLICAOF`/写命令；用 `REDISCLI_AUTH` 使密码不进命令行、不打印；容器缺失时打印 `missing/（not running）` 而不报错。
- `--watch [秒] -n 次数` 便于演练时刷屏观察；env 覆盖优先于 `.env` 优先于内置默认。

## 4. `code/lab/scripts/up.sh`（`--profile dev|ha`）

- `COMPOSE_FILES`/`ACTIVE_PROFILES` 数组化：dev 恒为 base+dev；ha 叠加 `compose.ha.yml` 并 `--profile ha`，dev 服务定义逐字节不变。
- 主 `compose … up -d --wait` 仍只等 16 常驻服务；ha profile 下**另行枚举** `--profile ha config --services` 减去默认集，对每个 `jl-<service>` 做同样的 running/healthy 复核。
- 末尾仅 ha profile 追加拓扑摘要（主名/IP/quorum/down-after/failover-timeout/exporter 提示），并指向 `sentinel-status.sh` 与 `REDIS_HA_VERIFY=auto`。

## 5. `code/lab/scripts/down.sh`

- 检测到 `compose.ha.yml` 存在时，两条分支（`down` 与 `down -v`）都带 `--profile ha`，保证 `jl-redis-1..3`/`jl-sentinel-1..3` 不残留；文件缺失时行为与旧版一致。
- 文档说明 Sentinel 拓扑 `/data` 是 tmpfs、无命名卷，`--purge` 不新增卷删除项。

## 6. `code/lab/scripts/verify.sh`（5b 段，+186 行）

- source `.env` 前对若干 HA 键（`REDIS_HA_VERIFY`/poll/ports/expected/status 容器列表等，**不含密钥**）做快照、source 后回写，使运维单次 `REDIS_HA_VERIFY=off` 能复现纯 dev 基线。
- `REDIS_HA_VERIFY=auto`（默认）仅在首个哨兵容器存在时执行；`on` 强制要求；`off` 跳过。只读、整轮重试（`get-master-addr-by-name` 全哨兵一致、每哨兵 `slaves=2,sentinels=3`、组内**恰好一个 master + N 个在线从**——按 role 聚合，天然容忍演练后主是别的容器）。**只会新增 PASS，不改 dev 计数**。

## 7. `code/lab/docker/.env.example`（+110 行）

- 逐键注释 `REDIS_HA_*`、`SENTINEL_STATUS_*`、`REDIS_HA_VERIFY*`、`REDIS_HA_EXPECTED_*`，并说明预算算式、pinned-IP/tilt 坑、masterauth、无卷 tmpfs、opt-in 宿主机端口（注释内 `6380-6382/26380-26382`）。与 `compose.ha.yml` 的引用一一对应（交叉核对通过）。

# 设计逻辑

- **profile 门控而非新常驻服务**：`ha` 服务全部 `profiles: ["ha"]`，与 base 里 `traffic` 同构，因此 `verify.sh`/`bench.sh`/`reset-lab.sh`/`up.sh` 的 16 服务口径与 dev 输出零变化；默认栈不为演示拓扑付内存。
- **身份用固定 IP，不用主机名**：`jl-sentinel-net` 上给三主从、三哨兵钉死 IP，`sentinel monitor` 与 `--replicaof` 都指 IP，`resolve/announce-hostnames=no`。原因（本步实测）：`docker stop` 会让容器 DNS 名从 Docker 内嵌 DNS 消失，开了 `resolve-hostnames` 的哨兵会 `Failed to resolve hostname` 进 `#tilt` 而拒绝晋升。固定 IP 跨 stop/start/recreate 稳定，旧主复位与新主发现才都能成立。
- **可写哨兵配置来自渲染副本**：哨兵运行期会重写自身配置（myid/known-replica/known-sentinel/epoch/晋升后的主地址），只读 bind 挂载会让重写失败，故模板只读、运行副本落 tmpfs、每次启动重建。
- **无命名卷 + tmpfs**：拓扑是一次性演示数据（`appendonly=no`），tmpfs 同时给哨兵可写目录，`down --purge` 清单因此不变。`maxmemory(${REDIS_HA_MAXMEMORY}=96mb)` 必须小于 `mem_limit(${REDIS_HA_NODE_MEM_LIMIT}=128m)`。
- **一份 lab 口令贯穿全组**：`REDIS_HA_PASSWORD` 同时作每节点的 `requirepass`+`masterauth` 与每哨兵的 `auth-pass`；`masterauth` 与被降级旧主重挂直接相关。哨兵自身端口不鉴权（只哨兵→数据节点方向带口令），故状态工具与 `verify.sh` 可匿名读哨兵。
- **切换语义即验收对象**：`verify.sh` 的角色断言按"组内恰好一个 master + 其余在线从"聚合而非钉死具体容器，正是为容忍演练后主漂移到 `redis-2`/`redis-3`。
- **对齐工程规约第 1 节**：路径、端口、超时候选（down-after/failover-timeout）、TTL 类（idle timeout、tcp-keepalive）、池/容量（maxmemory、mem_limit、tmpfs size）、quorum、parallel-syncs、期望数（EXPECTED_SENTINELS/REPLICAS）、poll 次数/间隔、容器列表全部有 env 覆盖路径；compose 用 `${VAR}`（值来自 `.env`，与 base 同风格，up 前从 `.example` 复制），脚本用"环境 > `.env` > 默认"，无任何密钥打印或写盘。

# 存在缺陷项&TODO

## 内存预算核对（本步实测）

- `docker stats --no-stream`：`jl-redis-1` 4.9MiB、`jl-redis-2` 6.4MiB、`jl-redis-3` 5.5MiB、`jl-sentinel-1/2/3` ≈4.5/5.0/4.6MiB、三个 exporter ≈6.4/6.0/5.6MiB，**实际合计 ≈49MiB**。
- 声明 `mem_limit` 合计 = 3×128 + 3×64 + 3×48 = **720MiB**（inspect 得 134217728/67108864/50331648 字节，与 `.env.example` 注释算式一致）。720MiB < 设计 ≈800MB，预算成立且余量充足。
- **口径说明**：设计 §3.1 写"每节点 1 个 exporter"，本实现按 §8.2 上下文解读为**每数据节点**（1 主 + 2 从共 3 个），刻意不给哨兵加 exporter——若再加 3 个哨兵 exporter，limit 合计会到 384+192+288 = **864MiB，反超 ≈800MB 预算**。故 3 exporter 是"符合预算"的选择，非遗漏；若后续确需哨兵指标，应在更大预算或调低单容器 limit 后引入。

## 已知限制 / 后续待办（供 #48/#49）

- **`jl-redis-*-exporter` 未被 Prometheus 抓取**：`prometheus.yml` 仅有 `redis-exporter:9121`（单机 dev 导出器）。本步只证明"新主的 exporter 自身抓得到该节点"（`redis_up 1`、`redis_connected_slaves 1`），**不代表 Prometheus 已采集这三个 HA 导出器**。#48/#49 若要出图需新增 scrape job 或在 `ha` profile 下条件化抓取，并把 job/targets 外置。
- **演练后主漂移**：切换后主是 `jl-redis-2`，与 compose 声明的初始主 `redis-1` 不同。这是切换的**正确结果**，不是缺陷；若演示要把主复位回 `redis-1`，需人工 `SENTINEL failover`/二次切换或重启哨兵（属 #49 场景编排，不在本步）。
- **应用连接主用的地址落在 `jl-sentinel-net`（关键，见"待定事项"契约第 4 点）**：本步未做应用侧真实连通验证（属 #48），只验证了拓扑与切换机制本身。
- **异步复制可能丢最近写入**：这是 §8.2 明确的教学点。但 `docker stop`（SIGTERM）会让 Redis 在退出前跑完复制积压，本演练**无法稳定复现"丢最后写入"**；真要演示丢写需 `docker kill`（SIGKILL），与"平滑停主"相悖，留给 #49 场景设计权衡（并在文档/面板如实标注，勿把 stop 演练包装成"已复现丢写"）。
- `REDIS_HA_REDIS_1_HOST_PORT` 仅出现在注释的 opt-in `ports:` 块（带内联默认 `6380`），有意未进 `.env.example` 生效键；解除注释才用到。

# 待定事项和注意事项

## 给 #48（应用 Sentinel 客户端与双模式配置）的契约

1. **主名**：`REDIS_HA_MASTER_NAME=mymaster`。客户端用主名发现主，**绝不在应用配置里写死 `redis-1`**——切换后主可能是 `redis-1/2/3` 任一。
2. **哨兵端点与注入方式（双模式）**：
   - 开关：`REDIS_MODE=standalone|sentinel`（放 `.env`；dev profile 保持 `standalone`，ha profile 用 `sentinel`），满足设计 §3.3"配置通过环境变量切换"。
   - `standalone` 时沿用现有 `x-app-env` 的 `REDIS_HOST=redis` / `REDIS_PORT`（本步未动）。
   - `sentinel` 时新增：哨兵列表（如 `REDIS_SENTINEL_NODES=sentinel-1:26379,sentinel-2:26379,sentinel-3:26379`，逗号分隔、外置）+ 主名（复用 `REDIS_HA_MASTER_NAME`）+ 口令源（`REDIS_HA_PASSWORD`，勿硬编码）。哨兵端口容器内 26379，**未发布到宿主机**，应用经 `jl-net` 用服务名 `sentinel-N` 访问。
3. **Redisson `sentinelServersConfig` 需外置的字段**：`masterName`、`sentinelAddresses`、`database`（与 standalone 对齐，默认 0）、`password`（来自 `REDIS_HA_PASSWORD`）、以及超时/池：`connectTimeout`、`commandTimeout`(timeout)、`idleConnectionTimeout`、`masterConnectionMinimumIdleSize`、`masterConnectionPoolSize`、`slaveConnectionMinimumIdleSize`、`slaveConnectionPoolSize`、`readMode`/`subscriptionConnectionPoolSize` 等——**这些池大小与超时都要按工程规约第 1 节做成可覆盖配置**，不要落进 Java 常量。切换相关超时（重试、`retryDelay`）同样外置。
4. **⚠️ 主地址落在 `jl-sentinel-net`（实测，务必处理）**：哨兵向客户端返回的**主/从地址是数据节点的 announced IP = pinned `jl-sentinel-net` IP（如 `172.28.190.12`）**，而 `jl-app-1` 目前只挂在 `jl-net`（`172.19.0.12`）。因此纯 `standalone` 的 app 网络**够不到**哨兵给出的主地址。#48 需二选一：(a) 在 `ha` profile 下把 app 容器额外接入 `jl-sentinel-net`（与数据节点同网，能直达 pinned IP）；(b) 重新设计 announced IP 使其落在 app 可达且稳定的网络上。**不建议**通过改 base 的 `jl-net` 为固定子网来实现，因为那会重建 dev 容器、违背"profile 叠加不改 dev"的约束。本条在 #48 需用**真实客户端连接**端到端验证，本步未验证。
5. **compose env**：#48 预计要给 `x-app-env`（或 app 的 ha overlay 覆盖）新增 `REDIS_MODE`、哨兵列表、`REDIS_HA_MASTER_NAME`、`REDIS_HA_PASSWORD`；当 `REDIS_MODE=sentinel` 时把 app 加入 `jl-sentinel-net`。切换开关默认走 `.env`，密钥仍只从 `.env` 读、不打印、不落盘。

## 运维与安全注意

- **诚实口径**：应用**当前不接 Sentinel**（仍连单机 `jl-redis`），接入是 #48；切换演练的教学点是**异步复制可能丢最近写入**，勿表述成"应用已自动故障转移"。
- 只读看拓扑：`bash code/lab/scripts/sentinel-status.sh`（演练时 `--watch`）。手工演练（仅 ha 容器安全，app 仍连单机 redis）：`docker stop jl-redis-1` → 观察晋升 → `docker start jl-redis-1` 复挂为从。**绝不 `rm`、绝不删卷、绝不碰 `jl-redis`/`jl-app-*`/`jl-mysql`/`jl-rabbitmq`**。
- `down.sh` 在 `compose.ha.yml` 存在时会连带停 ha 容器；只想停 dev 且保留拓扑请用带 `--profile` 的显式 compose 或保持栈运行。
- 若宿主机占用 `172.28.190.0/24`：改 `REDIS_HA_SUBNET` 且同步改三 `REDIS_HA_REDIS_*_IP` 与三 `REDIS_HA_SENTINEL_*_IP`（须同子网、/24 内、互不冲突），base 的 `jl-net`（172.19/16）、bridge 172.17/16、multica_default 172.18/16 已让开。
- `.env`、`dist/`、`node_modules/`、`code/lab/k6/reports/`、`.superpowers/`、`.r2c/`、`code/services/**` 一律不提交；`LAB_TOKEN`/`REDIS_HA_PASSWORD` 只读不打印。本步未改任何应用 Java、`code/services/**`、k6、dashboard、场景 JSON 与 `阶段交接-2026-10-26.md`。

# 附录

## 关键文件

- Compose：`code/lab/docker/compose.ha.yml`、`code/lab/docker/.env.example`
- Redis 启动与模板：`code/lab/docker/redis/redis-node-entrypoint.sh`、`sentinel-entrypoint.sh`、`sentinel.conf.tpl`
- 脚本：`code/lab/scripts/sentinel-status.sh`、`up.sh`、`down.sh`、`verify.sh`
- 设计/规约：`doc/design/2026-10-26-ha-concurrency-lab-design.md` §8.2/§3.1/§3.3、`doc/design/工程规约.md` 第 1 节

## 复现命令

```bash
cd /d/000CODE/JAVA-LEARN

# 不变式：默认仍是 16 服务；ha 追加 9 个（=25）
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  -f code/lab/docker/compose.dev.yml config --services | wc -l           # -> 16
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  -f code/lab/docker/compose.dev.yml -f code/lab/docker/compose.ha.yml \
  --profile ha config --services | sort                                  # -> 25（含 redis-1..3/sentinel-1..3/三导出器）

# 启动/停止 HA（保持 dev 服务定义不变地叠加）
bash code/lab/scripts/up.sh --profile ha
bash code/lab/scripts/down.sh          # 会一并清理 ha 容器

# 只读看拓扑 / 演练时观察
bash code/lab/scripts/sentinel-status.sh
bash code/lab/scripts/sentinel-status.sh --watch 2 -n 15

# 手工切换演练（仅 ha 容器）
docker stop jl-redis-1        # 观察哨兵 +switch-master 晋升
docker start jl-redis-1       # 应复挂为 slave（master_link_status: up）
docker logs --since 60s jl-sentinel-1 | grep -E 'sdown|switch-master|new-epoch|vote-for-leader'

# 复跑验收（栈需运行）
bash code/lab/scripts/verify.sh
```

## 证据（本步实际输出）

- compose 不变式：默认 `config --services` = **16**（`redis` 在列、无 `redis-1..3`/`sentinel-*`/`traffic`）；`--profile ha` = **25**，追加集恰为 `{redis-1,redis-2,redis-3,redis-1-exporter,redis-2-exporter,redis-3-exporter,sentinel-1,sentinel-2,sentinel-3}`。
- 演练前：哨兵一致 `get-master-addr=172.28.190.11:6379`；三导出器 `redis_up 1`。
- 切换时间线（UTC）：`15:31:43` 停 `jl-redis-1` → `15:31:48.881 +sdown master mymaster 172.28.190.11` → `+new-epoch 1` / `+vote-for-leader …` → `15:31:49.719 +switch-master mymaster 172.28.190.11 6379 → 172.28.190.12 6379` → **实测晋升 5.78s**；停后幸存：`jl-redis-2 role=master`、`jl-redis-3 role=slave master_link=up`、`jl-redis-1 exited`。
- 复位：`docker start jl-redis-1` → 本地初起 `role:master` → 被哨兵重定向，`15:32:43` 达 `role=slave master_host=172.28.190.12 master_link_status=up`。复位后 `sentinel-status.sh`：`jl-redis-2 master connected_slaves=2`、`jl-redis-1`/`jl-redis-3` slave link up。
- 新主 exporter：`redis-2-exporter` → `redis_up 1`、`redis_connected_slaves 1`。
- `docker stats --no-stream`（HA）：见"存在缺陷项"内存表，实际合计 ≈49MiB、limit 合计 720MiB。
- `verify.sh` 末尾：`VERIFY PASSED: 103 checks passed, 1 warning(s).`（新增 6 项：`sentinels agree on the current master of mymaster (172.28.190.12:6379)`、三哨兵 `discovers 3 sentinels and 2 replicas`、`one writable master in the ha group ( jl-redis-1=slave jl-redis-2=master jl-redis-3=slave)`、`2 replicas report master_link_status up`）；唯一 WARN=`cadvisor container series absent（Docker Desktop/WSL2 限制）`，与本步无关。
