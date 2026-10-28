# 步骤交接：S2-B0-06 `reset-lab.sh` 复位目标按拓扑解析

> 任务/步骤：Stage2 B0-06 —— reset-lab.sh 的复位目标由"当前栈实际拓扑"决定（router/db-ha 指向集群，不再默认单机 `jl-mysql`），执行前打印目标清单，`--dry-run` 口径不变
> 日期：2026-10-28
> 分支 / HEAD：feature/stage2-workbench 基线 worktree 提交 `d07590c`（本步 commit `e1d2a8c`）
> 关联任务：`doc/process/Stage2/stage2-开发测试与验收计划.md` B0-06；验收对应 A25
> 测试 / verify：新增 mock 测试 `test-reset-topology.sh` 28/28 绿；既有 `test-lab-safety.sh` 全量仍绿；`bash -n` 两脚本均过
> 运行状态：本步为 shell 层 mock 自测，未起真机栈；真机 db-ha `--dry-run` 验证见 §存在缺陷项&TODO（unverified，主会话后补）

# 当前进展

- `code/lab/scripts/reset-lab.sh` 已改造：复位目标不再硬默认 `jl-mysql`/`jl-redis`，改由「声明开关（MYSQL_MODE / REDIS_MODE）× 活体容器探测」双信号解析（B0-06 主缺陷修复点）。
- 新增 mock-docker/curl 测试 `code/lab/scripts/test-reset-topology.sh`（仓库此前无 docker mock 先例，test-lab-safety 是纯静态 grep、test-smoke 是 HTTP mock；本步新建了 shell 级 mock 模式，可复用）。
- `.env.example` 补了 RESET_* 拓扑键说明块（保持 CRLF，未动其它设计文档）。
- 最新 commit：`e1d2a8c feat(B0-06): resolve reset-lab.sh targets from the live stack topology`。
- 本步无未完成代码项。

# 完成功能项

- `code/lab/scripts/reset-lab.sh`：
  - 拓扑解析（在任何破坏性动作之前）：
    - MySQL：`MYSQL_MODE=router` → 集群：Router 在跑则 exec 进第一个运行中的 `jl-mysql-<n>`、经 `mysql-router:6446` 连接（跟随 PRIMARY）；Router 没跑则逐节点探测 `@@read_only=0` 定位当前 PRIMARY 直连（GR 把写扩散到从节点）。`MYSQL_MODE=standalone` → `jl-mysql`（容器内 socket，行为与旧版一致）；仅当 `jl-mysql` 没在跑而集群在跑时自动落到集群并打 WARN（口径漂移提示）。两栈同时在跑时以声明开关为准——这正是 router 模式下复位到集群、不再误击单机的修复点。
    - Redis 同理：`REDIS_MODE=sentinel` → 对每个运行中的 `jl-redis-<n>` 逐个 FLUSHDB，基线验证经 `info replication` role 探针跟随当前 master；standalone → `jl-redis`；未知/矛盾拓扑同样报错退出。
  - 解析入口用 compose 注入 app 层的同款开关键 MYSQL_MODE/REDIS_MODE（reset 本来就 source 该 .env，口径已在用），再用 `docker inspect` 状态 + compose project 标签做活体校验（`jl-` 前缀 + 节点名模式，与 db-ha-up.sh/db-cluster-status.sh 一致）。
  - 执行前先打印 `Reset targets` 清单（声明模式 / 拓扑 / exec 容器 / 路由 / 库 / 端口 / 各节点观察态），`--dry-run` 同样打印；`RESET SUMMARY` 也回显实际目标。
  - 未知拓扑（声明 standalone 但单机和集群全没跑、声明 router 但集群全没跑、模式取值非法、集群无主）→ 退出码 2，列出所有候选容器观察态与修复提示，先于任何变更、不触碰任何容器。
  - `--yes` 守卫与 `--dry-run` 语义原样保留：不带 `--yes` 仍打印计划后拒绝执行；preflight 收集问题、EXIT trap 等既有安全机制未弱化。
  - 工程规约 §1：新增可变参数全部置顶 env 化（`RESET_MYSQL_MODE`/`RESET_REDIS_MODE` 覆盖键、`RESET_CONTAINER_PREFIX`、`RESET_MYSQL_HA_NODES`、`RESET_MYSQL_ROUTER_CONTAINER/HOST/RW_PORT`、`RESET_MYSQL_NODE_PORT`、`RESET_REDIS_HA_NODES`、`RESET_REDIS_NODE_PORT`、`RESET_TOPOLOGY_PROBE_RETRIES/INTERVAL_SECONDS`、`RESET_MYSQL_CONNECT_TIMEOUT_SECONDS`），并纳入既有正整数校验循环；usage() 与 `.env.example` 同步文档化。
- `code/lab/scripts/test-reset-topology.sh`（新增，mock docker/curl 双 shim + env 夹具，全程零真机）：9 组场景 28 条断言——standalone 解析到 `jl-mysql`/`jl-redis`；db-ha mock 解析到集群且 mysql argv 带 `-h mysql-router -P 6446`、`jl-mysql` 零 exec；Router 缺席逐节点落 PRIMARY；未知拓扑 exit 2 且报错可读；双栈同跑以声明为准；sentinel 三节点逐个 FLUSHDB + master 验证；dry-run 日志无 stop/start/TRUNCATE；无 `--yes` 仍拒绝；mock 全链路 `--yes` 复位经集群路由跑通 `RESET PASSED`。
- 未动：chaos-controller/app Java 代码、`claims.yml`、compose 文件（`compose.mysql.yml` 只读参考）、`.env.example` 之外的设计文档。

# 设计逻辑

- 「声明开关 × 活体探测」双信号：MYSQL_MODE/REDIS_MODE 是 compose 注入 app 层的唯一拓扑开关（compose.base.yml:67、compose.ha.yml、.env.example 注释均以此为准），复位目标与 app 实际读写目标同源，避免"复位了 app 没在用的那套"；探测负责证明声明可执行（容器 running 且 project 标签命中），两者矛盾且声明目标已死时才回退并 WARN。
- 集群写路径选择：优先 Router 6446（跟随 PRIMARY、语义与 app 写池一致）；Router 未运行是 db-ha-up.sh 明示的合法状态（--skip-router），故逐节点探测 `@@read_only=0` 直写 PRIMARY，由 Group Replication 扩散——与任务背景给的"经 Router 6446 或逐节点"口径一致。mysql 客户端刻意跑在节点容器里（Router 镜像不带 mysql 客户端，db-cluster-status.sh 已论证同点）。
- Redis 集群逐节点 FLUSHDB 而非只打 master：复位场景下从节点可能滞留在旧主/断链状态，逐节点清理才是确定性基线；验证仍跟随当前 master（应用 seeder 写 master）。Redis 集群路径为前瞻支持（M3-1b 后 app 才切 sentinel），standalone 行为零变化。
- 报错退出用 2（配置/口径类错误，与本脚本既有约定一致：1=运行时失败，2=拒执行为），且发生在 preflight 之前——此刻尚未创建任何可失败变更。
- 凭证纪律不变：MYSQL_PWD 仍走 0600 `--env-file`，探针同样经该通道，密码不上 argv、不打日志；打印的目标清单只有容器名/主机名/端口/库名。
- 兼容性：standalone 默认路径的命令、参数（socket 连接、无 -h/-P）与旧版逐字节等价；bench.sh 调用面（`--yes`）无需改动。test-lab-safety.sh 的静态断言（label 相等检查两处、trap、探针顺序等）全部保持。

# 存在缺陷项&TODO

- 真机 db-ha 栈 `--dry-run` 指向核对：unverified（本步纪律禁跑 docker/compose 生命周期，由主会话真机验证，见下表）。
- 集群逐节点模式的写放大假设（GR 扩散 TRUNCATE/UPDATE 到从节点）未在真机核对——TRUNCATE 走 Router/PRIMARY 后从节点可见性是 GR 既有行为，风险低但列入真机核对项。
- Redis sentinel FLUSHDB 路径目前只有 mock 证据；真 app 尚未切 sentinel（M3-1b 才切），届时补真机。
- `RESET_MYSQL_CONTAINER`/`RESET_REDIS_CONTAINER` 保留为单机目标名覆盖键（bench/老用法兼容），集群目标覆盖用新增节点键，不做"单键双语义"。

## 真机样本验证

| 项目 | 状态 | 备注 |
|---|---|---|
| db-ha 栈上 `reset-lab.sh --dry-run` 打印 cluster 目标与 Router 路由 | unverified | 主会话真机后补 |
| 真机 `--yes` 经 Router 6446 完成复位且验证通过 | unverified | 依赖 db-ha 栈 + app 起栈 |
| standalone 真机行为与旧版一致 | unverified | mock 已证参数级等价 |

# 待定事项和注意事项

- 合回时以 commit `e1d2a8c` 为单元；verify.sh 若要把 `test-reset-topology.sh` 纳入例行闸门属主会话决定（本步未动 verify.sh）。
- 并行步骤注意：本步与 bench.sh / reset-lab.sh 交叠仅在使用面（bench 传 `--yes`，接口未变）；若 B3/B0-x 后续步骤改 app 的 MYSQL_MODE 注入路径，需回头核对解析键名。
- mock 测试在 `RESET_TOPOLOGY_PROBE_RETRIES=1` 下运行；真机默认 3 次×2s 是有界探测，不会挂住会话。
- 报错信息里刻意列出全部候选容器（含未参与决策的），让"将要复位谁"在失败路径同样可审计。

## 补充

- **真机 `--dry-run` 已验（R2，2026-10-28，见 `b0-live-verification.md` §R2-1）**：
  - 现栈声明口径 `MYSQL_MODE=standalone` → 解析到 `jl-mysql`（本地 socket），与 app 实际读写目标同源，符合设计。
  - `RESET_MYSQL_MODE=router RESET_REDIS_MODE=sentinel --dry-run` → `MySQL topology: cluster`、
    `route=[MySQL Router mysql-router:6446 (follows the PRIMARY), client exec in jl-mysql-1]`、
    三节点 + router 活体全命中 ⇒ 本步登记的「db-ha 栈上 --dry-run 打印 cluster 目标与 Router 路由」= **真机 PASS**。
  - 未执行真 `--yes`（会清演示数据，留到 B0 之后按批次需要）。新发现：sentinel 口径下 dry-run preflight 报
    `redis-cli ping ... NOAUTH Authentication required.`（jl-redis-1/2/3），即集群 Redis 复位路径的鉴权口径
    在真机尚未打通 —— 与本步「Redis 集群路径为前瞻支持」的登记一致，真 `--yes` 前需先补该口径。
  - 旁证「跟随 PRIMARY」有效：`Current state` 行读到的集群侧数字（rows=20/orders=167）与 dev 单机面（rows=1020/orders=4）不同。

# 附录

- 复现命令：
  - mock 拓扑测试：`bash code/lab/scripts/test-reset-topology.sh`（28 PASS / 0 FAIL，尾行 `RESET-TOPOLOGY MOCK TESTS PASSED.`）
  - 既有安全测试回归：`bash code/lab/scripts/test-lab-safety.sh`（尾行 `LAB SAFETY TESTS PASSED.`）
  - 语法闸门：`bash -n code/lab/scripts/reset-lab.sh && bash -n code/lab/scripts/test-reset-topology.sh`
- 提交：`e1d2a8c feat(B0-06): resolve reset-lab.sh targets from the live stack topology`（基于 `d07590c`）
- 关键文件：
  - 复位脚本：`code/lab/scripts/reset-lab.sh`（拓扑解析块位于 read-only helpers 与 preflight 之间，先于一切变更打印 `Reset targets`）
  - mock 测试：`code/lab/scripts/test-reset-topology.sh`
  - 键文档：`code/lab/docker/.env.example`（"reset-lab.sh target resolution" 块）
- 只读参考：`code/lab/docker/compose.mysql.yml`（容器命名 `jl-mysql-1..3`、`jl-mysql-router`）、`db-ha-up.sh`（Router 6446/6447、--skip-router 合法态）、`db-cluster-status.sh`（节点内客户端探测先例）、`compose.base.yml:67`（MYSQL_MODE 注入口径）
