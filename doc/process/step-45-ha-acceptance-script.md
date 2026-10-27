> 步骤交接 · M3 高可用验收脚本（step 45）
> 日期：2026-10-27
> 分支 / 基线：worktree `agent-general-purpose-40b52b49`，基线 `4d6d5e4`；本文档对应的功能提交 `826a45e`（`feat: add the repeatable HA acceptance script`）
> 关联：`doc/process/step-24d-sentinel-live-drill.md`（Sentinel 实测）、`doc/process/step-31-mysql-router-live-drill.md`（Router 实测）、`doc/process/step-38-ha-acceptance.md`（M3 验收清单）
> 验证边界：本会话**没有运行任何容器**，只执行了 `bash -n`、`docker compose config` 和两个 `--dry-run`；另外用本地 mock 的 `docker`/`curl` 跑通了 sentinel/mysql 的完整断言链（不接触 Docker）。真机实跑仍未执行。

# 1. 用法（Usage）

```bash
# 两个演练，二选一
bash code/lab/scripts/ha-acceptance.sh sentinel
bash code/lab/scripts/ha-acceptance.sh mysql

# 只打印计划、解析后的配置和 compose 渲染检查，不改任何状态
bash code/lab/scripts/ha-acceptance.sh sentinel --dry-run
bash code/lab/scripts/ha-acceptance.sh mysql    --dry-run

# 帮助（含完整安全说明与常用覆盖项）
bash code/lab/scripts/ha-acceptance.sh --help
```

行为：

- `sentinel`：从三个 `jl-redis-*` 中找出当前 `role=master` 的节点（不写死 `jl-redis-1`），通过混沌 API 执行 `kill`（TTL 默认 45s）；轮询 Sentinel 的 `get-master-addr-by-name` 与数据节点角色，要求出现另一个 master；同时轮询 Nginx `GET /api/products/1`，首个 200 必须 ≤15s；TTL 到点后要求旧主 `role=slave` 且 `master_link_status=up`。
- `mysql`：先跑 `db-cluster-status.sh`，要求 `RESULT: ok`，再从输出中取当前 `ONLINE PRIMARY`；通过混沌 API 执行 `kill`（TTL 默认 45s）；轮询 Router 6446，要求 `server_id` 变化且 `read_only=0`；同时轮询 Nginx 商品读；TTL 到点后要求目标节点为 `ONLINE SECONDARY` 且 `db-cluster-status.sh` 回到 `RESULT: ok`。
- 两种模式结束前都会 `POST /chaos/stop-all`（失败也执行），断言 `GET /chaos/actions` 为空、目标容器 running；mysql 还要回到 `RESULT: ok`，sentinel 还要回到 `role=slave/master_link_status=up`。
- 退出码：`0` 全 PASS；`1` 至少一个 FAIL（cleanup 仍已尝试）；`2` 参数/配置错误（未触碰任何东西）。
- 输出：每步 `PASS/FAIL/SKIP/WARN`，末尾 `N PASS / M FAIL` 与实测的 promotion / Router switch / 首个 200 / TTL 重启 / 重加入恢复时间。脚本输出为纯 ASCII，避免 Windows GBK 控制台乱码。

前置：

1. dev 栈已起，`code/lab/docker/.env` 存在（`up.sh` 会创建）。
2. sentinel：`up.sh --profile ha`（或等价的 base+dev+ha 渲染）已把 `jl-app-1/2/3` 重建为 `REDIS_MODE=sentinel`，`jl-redis-1/2/3` 与 `jl-sentinel-1/2/3` healthy。
3. mysql：`db-ha-up.sh` 已起层，集群 `RESULT: ok`；`MYSQL_MODE=router`、`DB_WRITE_URL`/`DB_READ_URL` 已设置且 app 三实例已重建（脚本会读容器 env 校验，不是只读 `.env`）。
4. 当前没有别的 chaos action；脚本会在 preflight 直接拒绝，避免 `stop-all` 回滚别人的故障。

安全边界：

- 目标必须同时满足“在当前 HA 节点列表里”和“匹配 controller 容器内生效的 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`”。不匹配时脚本先做一次幂等 `start` 探针，把 controller 的真实 403 响应原样打印出来再退出。
- 显式 `HA_ACCEPT_SENTINEL_TARGET` / `HA_ACCEPT_MYSQL_TARGET` 只有在它就是当前 master/primary 时才被接受；杀副本不算演练。
- mysql 的 Router 探针默认按 `HA_ACCEPT_MYSQL_NODES` 顺序选第一个**非目标**的 running 节点（例如目标为 `jl-mysql-1` 时选 `jl-mysql-2`），因为探针节点不能和目标一起被杀。
- 只对 `jl-redis-*` / `jl-mysql-*` 目标发动作；`stop-all` 只回滚 controller 自己跟踪的动作；cleanup 的 `docker start` 只作为 controller 不可用/丢失注册表时的最后兜底，且只针对本次目标。

# 2. 环境变量表（Env table）

优先级统一为：**进程环境 > `code/lab/docker/.env` > 脚本默认**。`.env` 只按 key 读取，不 `source`，token/password 不打印。

## 2.1 通用

| 变量 | 默认 | 说明 |
|---|---|---|
| `HA_ACCEPT_ENV_FILE` | `code/lab/docker/.env` | 读取的 lab env 文件；dry-run 在文件不存在时回退 `.env.example` 做渲染检查。 |
| `HA_ACCEPT_COMPOSE_PROJECT` | `java-learn-lab` | `docker compose --project-name`。 |
| `HA_ACCEPT_CONTROLLER_URL` | `http://127.0.0.1:8080/lab-api` | Nginx 后面的 controller API 前缀。 |
| `HA_ACCEPT_NGINX_URL` | `http://127.0.0.1:8080` | Nginx 入口。 |
| `HA_ACCEPT_PRODUCT_PATH` | `/api/products/1` | 商品读探针路径，必须以 `/` 开头。 |
| `HA_ACCEPT_LAB_TOKEN` | 取 `LAB_TOKEN`（env/.env，最后 `lab-token`） | 一次性覆盖 token；永不打印。 |
| `HA_ACCEPT_CURL_TIMEOUT_SECONDS` | `5` | 单次 curl 预算。 |
| `HA_ACCEPT_EXEC_TIMEOUT_SECONDS` | `5` | 容器内 `redis-cli` / `mysql` 的单次预算。 |
| `HA_ACCEPT_POLL_INTERVAL_SECONDS` | `2` | 拓扑/HTTP/TTL 轮询间隔。 |
| `HA_ACCEPT_HTTP_RECOVERY_THRESHOLD_SECONDS` | `15` | 首个 200 的验收阈值（step-24d/31 的 ≤15s）。 |
| `HA_ACCEPT_HTTP_RECOVERY_BUDGET_SECONDS` | `30` | 等待首个 200 的总预算；超过阈值但预算内仍记录并 FAIL。 |
| `HA_ACCEPT_STOP_BUDGET_SECONDS` | `20` | 等待 kill 把容器停掉/cleanup 把容器拉起的小预算。 |
| `HA_ACCEPT_STOP_ALL_BUDGET_SECONDS` | `30` | 等待 `GET /chaos/actions` 清空的预算。 |
| `HA_ACCEPT_CLEANUP_CLUSTER_BUDGET_SECONDS` | `60` | cleanup 恢复拓扑（mysql `RESULT: ok` / sentinel replica link）的预算。 |
| `HA_ACCEPT_APP_CONTAINERS` | `jl-app-1 jl-app-2 jl-app-3` | app 实例名单。 |
| `HA_ACCEPT_NGINX_CONTAINER` | `jl-nginx` | Nginx 容器名。 |
| `HA_ACCEPT_CONTROLLER_CONTAINER` | `jl-chaos-controller` | controller 容器名（读 allow-list/最大 TTL/探针配置）。 |

## 2.2 Sentinel

| 变量 | 默认 | 说明 |
|---|---|---|
| `HA_ACCEPT_SENTINEL_DATA_CONTAINERS` | `jl-redis-1 jl-redis-2 jl-redis-3` | 数据节点名单。 |
| `HA_ACCEPT_SENTINEL_CONTAINERS` | `jl-sentinel-1 jl-sentinel-2 jl-sentinel-3` | Sentinel 名单。 |
| `HA_ACCEPT_SENTINEL_MASTER_NAME` | `REDIS_HA_MASTER_NAME`，最后 `mymaster` | `get-master-addr-by-name` 的主名。 |
| `HA_ACCEPT_SENTINEL_NODE_PORT` | `REDIS_HA_NODE_PORT`，最后 `6379` | 数据节点端口。 |
| `HA_ACCEPT_SENTINEL_PORT` | `REDIS_HA_SENTINEL_PORT`，最后 `26379` | Sentinel 端口。 |
| `HA_ACCEPT_SENTINEL_PASSWORD` | `REDIS_HA_PASSWORD`，最后空 | 容器内 `REDISCLI_AUTH`，不打印。 |
| `HA_ACCEPT_SENTINEL_TARGET` | `auto` | `auto` = 当前 master；显式值必须等于当前 master。 |
| `HA_ACCEPT_SENTINEL_TTL_SECONDS` | `45` | kill 的 TTL（step-24d 实测 45s）。 |
| `HA_ACCEPT_SENTINEL_PROMOTION_BUDGET_SECONDS` | `60` | 等待 `+switch-master` 的预算（实测 ~7.6s）。 |
| `HA_ACCEPT_SENTINEL_REJOIN_BUDGET_SECONDS` | `120` | 等待 TTL 重启 + `role=slave` + link up 的预算。 |
| `HA_ACCEPT_SENTINEL_REQUIRE_AGREEMENT` | `true` | 是否要求三个 Sentinel 的 master 地址一致。 |
| `HA_ACCEPT_SENTINEL_REQUIRE_SENTINEL_MODE` | `true` | 要求 app 容器 `REDIS_MODE=sentinel`；`false` 时降级为 WARN（仅拓扑演练）。 |

## 2.3 MySQL

| 变量 | 默认 | 说明 |
|---|---|---|
| `HA_ACCEPT_MYSQL_NODES` | `jl-mysql-1 jl-mysql-2 jl-mysql-3` | 集群节点名单。 |
| `HA_ACCEPT_MYSQL_ROUTER_CONTAINER` | `jl-mysql-router` | Router 容器名。 |
| `HA_ACCEPT_MYSQL_ROUTER_HOST` | `mysql-router` | 容器内访问 Router 的 host。 |
| `HA_ACCEPT_MYSQL_ROUTER_RW_PORT` | `MYSQL_HA_ROUTER_RW_PORT`，最后 `6446` | 读写路由端口。 |
| `HA_ACCEPT_MYSQL_ADMIN_USER` | `MYSQL_HA_ADMIN_USER`，最后 `root` | 探针账号。 |
| `HA_ACCEPT_MYSQL_PASSWORD_ENV` | `MYSQL_ROOT_PASSWORD` | 容器内密码变量**名**；值在容器内展开，不进宿主 argv。 |
| `HA_ACCEPT_MYSQL_BIN` | `MYSQL_HA_MYSQL_BIN`，最后 `mysql` | 容器内客户端。 |
| `HA_ACCEPT_MYSQL_CONNECT_TIMEOUT_SECONDS` | `5` | mysql 客户端连接超时。 |
| `HA_ACCEPT_MYSQL_PROBE_CONTAINER` | `auto` | `auto` = 选一个非目标的 running 节点；显式值等于目标时 preflight 拒绝。 |
| `HA_ACCEPT_MYSQL_TARGET` | `auto` | `auto` = 当前 PRIMARY；显式值必须等于 PRIMARY。 |
| `HA_ACCEPT_MYSQL_TTL_SECONDS` | `45` | kill 的 TTL（step-31 实测 45s）。 |
| `HA_ACCEPT_MYSQL_PROMOTION_BUDGET_SECONDS` | `90` | 等待 6446 换 `server_id` 的预算。 |
| `HA_ACCEPT_MYSQL_REJOIN_BUDGET_SECONDS` | `180` | 等待 TTL 重启 + `ONLINE SECONDARY` + `RESULT: ok` 的预算。 |
| `HA_ACCEPT_MYSQL_CLUSTER_STATUS_INTERVAL_SECONDS` | `10` | 重加入阶段两次 `db-cluster-status.sh` 之间的间隔（脚本本身约 20s）。 |
| `HA_ACCEPT_MYSQL_REQUIRE_ROUTER_MODE` | `true` | 要求 app 容器 `MYSQL_MODE=router`；`false` 时降级为 WARN（只验 Router 拓扑）。 |

其他由脚本读取、但不在控制块里的键：

- controller 容器内的 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`、`CHAOS_MAX_TTL_SECONDS`（TTL 上限校验）。
- `db-cluster-status.sh` 的 `DB_CLUSTER_STATUS_PROBE_CONTAINER`：脚本在每次调用时显式覆盖为选定的非目标探针。
- `CHAOS_MYSQL_CLUSTER_CONTAINER`：**仅用于 WARN**。controller 默认 `jl-mysql-1`；若它恰好是本次被杀节点，`mysql_cluster_*` 指标在窗口内冻结。建议指向 `jl-mysql-3` 等非目标节点。注意：`compose.base.yml` 目前**没有**把这个键从 `.env` 转发给 controller，所以只写 `.env` 无效；`.env.example` 只加了说明性注释，没有把它伪装成可用键。真正生效需要把该键加入 controller 的 environment（属 compose 改动，超出本任务）。

# 3. 断言清单（Assertions）

## 3.1 通用 preflight

1. controller `GET /lab-api/health` → 200 且 `"status":"UP"`。
2. `docker compose config` 在对应 profile 下渲染出必需服务（sentinel：`redis-1/2/3`、`sentinel-1/2/3`；mysql：`mysql-1/2/3`、`mysql-router`）。
3. 目标容器 running + healthy（sentinel：3 数据节点、3 Sentinel、3 app、Nginx、controller；mysql：3 节点、Router、3 app、Nginx、controller）。
4. app 容器 env 符合模式（sentinel `REDIS_MODE=sentinel`；mysql `MYSQL_MODE=router`）。
5. `GET /chaos/actions` 为空（拒绝与别人的故障叠加）。
6. 目标匹配 controller 容器内的 datastore allow-list；不匹配时打印 controller 的 403 body。
7. 请求 TTL ≤ controller 的 `CHAOS_MAX_TTL_SECONDS`。
8. 演练前 Nginx 商品读 200。

## 3.2 Sentinel 专项

9. 数据节点中恰好一个 `role=master`。
10. 其余节点 `role=slave` 且 `master_link_status=up`。
11. 目标解析：`auto` 取当前 master；显式目标必须等于它。
12. 至少一个 Sentinel 返回 `get-master-addr-by-name`，且地址属于目标容器的某个 IP。
13. `POST /chaos/instances/<target>/actions` `{"action":"kill","ttlSeconds":45}` → 202，回显 target/TTL 正确。
14. 目标容器在预算内变为非 running。
15. `get-master-addr-by-name` 变为新地址，且另一数据节点 `role=master`。
16. 新地址属于新 master 容器。
17. 三个 Sentinel 地址一致（可关）。
18. Nginx 商品读首个 200 ≤15s。
19. TTL 到点后目标容器 running。
20. 目标 `role=slave` + `master_link_status=up`。
21. TTL 回滚后 `GET /chaos/actions` 为空。

## 3.3 MySQL 专项

22. `db-cluster-status.sh` 退出 0 且末行 `RESULT: ok`（在 kill 之前）。
23. 从 membership 行解析出唯一 `ONLINE PRIMARY`，且属于节点名单。
24. 目标解析：`auto` 取 PRIMARY；显式目标必须等于它。
25. Router 探针容器不是目标且 running。
26. controller 探针不在目标上则 PASS；在目标上则 WARN 并给出 `CHAOS_MYSQL_CLUSTER_CONTAINER=jl-mysql-3` 建议。
27. 6446 基线 `server_id` + `read_only=0`。
28. `POST ... {"action":"kill","ttlSeconds":45}` → 202，回显 target/TTL 正确。
29. 目标容器在预算内变为非 running。
30. 6446 返回不同 `server_id` 且 `read_only=0`。
31. Nginx 商品读首个 200 ≤15s。
32. TTL 到点后目标容器 running。
33. membership 中目标行为 `ONLINE SECONDARY`。
34. `db-cluster-status.sh` 回到 `RESULT: ok`。
35. 重加入后 6446 仍为新 `server_id` + `READ_WRITE`。
36. TTL 回滚后 `GET /chaos/actions` 为空。

## 3.4 Cleanup（两种模式）

37. `POST /chaos/stop-all` → 200 且 `failed=0 pending=0`。
38. 轮询 `GET /chaos/actions` 直到 `data:[]`。
39. 目标容器 running（controller start 优先，`docker start` 兜底）。
40. mysql：`db-cluster-status.sh` 恢复 `RESULT: ok`；sentinel：目标恢复 `role=slave/master_link_status=up`。

任何 FAIL 都会让最终退出码为 1；cleanup 的 FAIL 也算。

# 4. 实测预期（Measured expectations）

数据来自既有真机演练，脚本的默认阈值按这些结果设定：

## 4.1 Sentinel（step-24d，2026-10-26）

- 19:05:04 kill `jl-redis-1`；19:05:09.993 `+sdown`；19:05:11.117 `+odown`；19:05:11.630 `+switch-master` → **停主到晋升约 7.6s**。
- Nginx 商品读 2s 轮询 52 次：**50×200 / 2×000**（两次 3s 客户端超时）；**停主后首个 200 在 +9.1s**，满足 ≤15s。
- TTL 45s 在 19:05:49 自动 `start`；19:05:53 `-sdown slave`，`jl-redis-1` 以 `role=slave`、`master_link_status=up` 回归。
- 应用无重启（`StartedAt` 不变），三实例 `Reconnected to 172.28.190.12`。
- 脚本默认：TTL 45s、promotion 预算 60s、rejoin 预算 120s、阈值 15s；预期 PASS 且测量值落在 ~5–10s / ~45–60s 区间。

## 4.2 MySQL Router（step-31，2026-10-26）

- 杀主 `jl-mysql-1`（TTL 45s）：`FinishedAt=21:28:26Z`，TTL `StartedAt=21:29:01Z`。
- Router 6446 从 `server_id=1 READ_WRITE` 切到 `server_id=2 READ_WRITE`；6447 落到 `server_id=1 READ_ONLY`。
- Nginx 商品读 70 次**全部 200**，没有 5xx。
- 旧节点回到 `ONLINE SECONDARY`；`db-cluster-status.sh` 末行 `RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY.`。
- 脚本默认：TTL 45s、promotion 预算 90s、rejoin 预算 180s、阈值 15s。
- **指标口径**：step-31 当时 controller 的 InnoDB 探针有 stdin 挂死缺陷（P1），`mysql_cluster_*` 没随杀主变化；该缺陷后来已修复（step-31 补充节）。本脚本只断言 Router 6446 与 `db-cluster-status.sh`，**不**断言 Prometheus 指标；需要指标证据时另跑 D11/PromQL。

## 4.3 与 step-38 验收清单的关系

- 场景 8/9 的“错误窗口 <15s”由本脚本的“首个 200 ≤15s”覆盖。
- “错误率 <1%”**没有**被本脚本覆盖：脚本是单请求探针，不是负载；该口径仍以 k6 `ha-failover` / 后续 mysql 场景的行为准。
- step-38 要求 mysql 切换前 `db-cluster-status.sh RESULT: ok`；脚本把它变成硬 preflight。

# 5. 剩余工作（What remains）

1. **真机实跑**：本会话只做了 `bash -n`、两个 dry-run 和 mock 断言链；需要在同时具备 ha 与 db-ha 层的机器上跑 `sentinel` / `mysql` 各一次，记录真实 PASS 数和恢复时间。Sentinel 需要 app 已按 `--profile ha` 重建；MySQL 需要 `MYSQL_MODE=router` 且 app 已重建。
2. **CI 接线**：CI 目前没有 Docker 栈。可先接一个只跑 `bash -n` + 两个 `--dry-run`（加 `docker compose config`）的静态 job；真机 job 需要 Docker-in-Docker 或 self-hosted runner、镜像预热，以及 `.env`/`MYSQL_HA_CLUSTER_GROUP_NAME` 的注入。不要把 token 写进 CI 日志。
3. **`CHAOS_MYSQL_CLUSTER_CONTAINER` 接线**：`compose.base.yml` 的 `chaos-controller.environment` 没有转发该键，`.env` 设置无效。若要让“探针避开被杀节点”成为一条命令可配的推荐项，需要补 compose 一行（本任务禁止改 compose）。当前脚本只在 preflight WARN，并给出 `jl-mysql-3` 建议。
4. **Prometheus 断言（可选）**：探针修好后，可在 mysql 演练中加一条 `mysql_cluster_primary{member="mysql-2"}` / `member_state` 变化断言；需要 `HA_ACCEPT_PROMETHEUS_URL` 和稳定的抓取周期，当前未做。
5. **错误率口径**：step-38 的 <1% 需要 k6 负载。可在 `code/lab/scenarios/` 增加 `mysql-failover` 场景（目前只有 `redis-sentinel-failover.json`），并复用 `ha-failover` 驱动；本脚本不替代 k6。
6. **失败注入测试**：mock 已覆盖成功链；还可用 mock 覆盖“kill 被 403”“promotion 不出现”“stop-all 失败”三条负路径，确认 cleanup/退出码。当前只做了成功链的 mock。
7. **dry-run 的运行时层检查**：dry-run 只用 `docker compose config` 判断 profile 定义是否存在，不探测容器是否 running（按“dry-run 不碰 Docker 生命周期”的约束）；真正的层存在性检查在无 `--dry-run` 的 preflight。
