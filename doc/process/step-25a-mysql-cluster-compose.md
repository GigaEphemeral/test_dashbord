> 步骤交接 · M3-2a MySQL InnoDB Cluster + Router compose 与初始化
> 日期：2026-10-27
> 分支 / HEAD：worktree `worktree-agent-general-purpose-f56dc285`（基线 `ab4ca38`）/ 本次提交 `feat: add MySQL InnoDB Cluster + Router db-ha compose tier (step 25a)`（`dbe4dbf`），文档随后 `docs:` 提交
> 关联任务：#50（M3-2a 本步）；下游 #51（M3-2b 应用 Router 双数据源与集群状态指标）
> 设计依据：`doc/design/2026-10-26-ha-concurrency-lab-design.md` §8.3（InnoDB Cluster：3 节点组复制单主 + Router 6446/6447 + 每节点 exporter）、§3.1（≈1.8GB 预算）、§3.3（dev=单机 MySQL、ha=集群，env 切换）、§8.6（Router 为可接受单点）；`doc/design/数据库高可用方案对比.md` §2（方案 A 采用）/§3.5（回退判据）；`doc/design/工程规约.md` 第 1 节（配置外置）
> ⚠️ 合并顺序说明：本 worktree 基线 `ab4ca38` **早于** M3-1a（`compose.ha.yml` / `up.sh` / `down.sh` / `verify.sh` 的 Sentinel 改动）。`compose.mysql.yml` 声明 `profiles: ["db-ha", "ha"]`，其中 `ha` 伞形 profile 在本基线下只含 MySQL 层；主会话把 `feature/ha-lab`（已含 M3-1a）与本步合并后，`ha` 才同时覆盖 Redis + MySQL 两层。
> 静态验证：compose 不变式实测——默认（base+dev）= **16** 服务；base+dev+mysql 不带 profile = **16**（零变化）；`--profile db-ha`（及 `--profile ha`）= **25**，追加集恰为 9 个 DB-HA 服务。7 个 shell 脚本 `bash -n` 全过；compose 模型 `config` 整树渲染通过（含嵌套 `${A:-${B}}` 插值）；模板渲染器离线干跑通过（含 `|`/`&`/`\` 对抗值）。**未启动任何容器**（见"当前进展"）。
> 运行状态：**群复制尚未真实初始化**。dev 16 常驻栈照常运行，本步只交付"编排 + 初始化脚本 + 只读状态工具 + 实机 Runbook"，`jl-mysql-1/2/3`、`jl-mysql-router` 等容器**不存在也未创建**，`jl-mysql-data`、`jl-redis` 等 dev 资产零接触。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

# 当前进展

- M3-2a 完成（静态层）：设计 §8.3 的 MySQL InnoDB Cluster 拓扑（3 × MySQL 8.4.4 组复制单主 + 1 × MySQL Router 6446/6447 + 每节点 1 个 mysqld-exporter + 两个一次性引导 job）落为 profile 门控的 `compose.mysql.yml` overlay，风格逐条对齐 `compose.ha.yml`（M3-1a）；交付 `db-ha-up.sh` / `db-ha-down.sh` / `db-cluster-status.sh` 三个脚本与本文档的实机 Runbook。
- **实机初始化未验证，这是主会话的下一步验收动作**：按本约束（另有 Redis 切换演练在同机进行、25 容器栈在跑），本轮禁止任何容器生命周期命令（up/create/start/stop/exec 写操作/run），因此"3 节点成组、Router 引导、failover 演示"只完成了**离线可证的部分**（compose 渲染、脚本语法、模板干跑、`docker history` 证实 `mysql:8.4.4` 自带 mysql-shell 层），执行证据为零。Runbook（附录 A）按"每步命令 + 期望输出 + 已知 Docker Desktop GR 坑的逐步定位"编写，供主会话直接照跑。
- 默认 dev 栈零影响：DB-HA 服务全部 `profiles: ["db-ha","ha"]`，不覆盖 base/dev 任何服务；默认 `config --services` 实测仍 16。
- 上一位 agent 遗留的三项审查修正已完成：渲染器补 `|` 转义（密码类 token 合法含 `|`，未转义会截断 sed 表达式）；`.env.mysql.example` 对 `MYSQL_HA_GR_LOCAL_ADDRESS` 的说明改为与布线上真实一致（该键**不经 .env 注入容器**，注入会把同一地址发给三个节点造成冲突；每节点地址由 entrypoint 派生）；`db-ha-up.sh` 一处错误提示原本会 inspect 错误镜像名，已改为指向真实渲染镜像。`.gitignore` 的 `!.env.mysql.example` 例外保留：`.env.*` 全局忽略会吞掉这份"文档化默认集模板"，与既有 `!.env.example` 同构，是必要的而非游离规则。
- 未做（本步边界）：应用接 Router 与集群状态指标（#51）；`prometheus.yml` 抓取接线、`.env.example` 并键、`up.sh/down.sh/verify.sh` 对 mysql overlay 的纳入（本步**无权改动**这些文件，见"待定事项"接线清单）；方案 B（主从 + ProxySQL）仅在 §3.5 判据触发时才启动。

# 完成功能项

## 1. `code/lab/docker/compose.mysql.yml`（388 行，profiles `db-ha` + `ha`）

- 三个 YAML 锚点：`x-mysql-ha-node`（GR 节点）、`x-mysql-ha-exporter`（导出器）、`x-mysql-ha-init`（一次性 job）；与 base `mysql:` 同风格——`image: ${MYSQL_IMAGE}`（=mysql:8.4.4）、`container_name: jl-*`、`restart: unless-stopped`、`mem_limit`、命名卷、`labels: lab/java-learn + lab.role`。
- 服务（9 个，全部 profile 门控）：`mysql-1/2/3`（各自仅 `MYSQL_HA_SERVER_ID` / `MYSQL_HA_REPORT_HOST` / `MYSQL_HA_MEMBER_WEIGHT` 三个身份项不同 + 独立卷 `jl-mysql-N-data`）、`mysql-cluster-init`（mysql-shell 引导 job）、`mysql-router-init`（Router 引导 job）、`mysql-router`（长驻，`127.0.0.1:6446/6447` 发布，**节点端口不发布**）、`mysql-1/2/3-exporter`（每节点一个，`expose 9104`）。
- 依赖链即引导顺序：`cluster-init` 等三节点 `service_healthy` → `router-init` 等 `cluster-init: service_completed_successfully` → `mysql-router` 等 `router-init` 完成 → 导出器各自等对应节点 healthy。
- GR 配置不进 `command:`：每容器 `--defaults-extra-file=${MYSQL_HA_NODE_CONF_FILE}`（MySQL 要求该参数在首位，恰好只出现一次），文件由容器启动时从 `conf/node.cnf.tpl` 渲染。
- 健康检查：节点用 `mysqladmin ping`（`MYSQL_PWD="$$MYSQL_ROOT_PASSWORD"`，口令不进 argv）——**故意只测 mysqld 存活，不测"是否在组内 ONLINE"**，否则引导 job 的 depends_on 会死锁；Router 用 bash `/dev/tcp` 环回双端口探测（整条命令可 env 覆盖，兼容无 bash 镜像）。全部 interval/timeout/retries/start_period 外置。
- 命名卷独立：`jl-mysql-1/2/3-data` + `jl-mysql-router-data`，**绝不复用 `jl-mysql-data`**（dev 卷初始化时没有 gtid_mode=ON）。网络复用 base 的 `jl-net`（同 compose.ha.yml 的声明方式），GR 对等发现走 Compose DNS。
- 嵌套插值实测渲染正确：`${MYSQL_HA_INIT_IMAGE:-${MYSQL_IMAGE:-mysql:8.4.4}}` → `mysql:8.4.4`；`${MYSQL_HA_EXPORTER_PASSWORD:-${MYSQLD_EXPORTER_PASSWORD}}` → `exporter`；Router healthcheck 默认串内的端口正确展开。

## 2. `code/lab/docker/mysql-cluster/`（2 个长驻 entrypoint + 2 个 job 入口 + 渲染库 + 3 份模板/脚本）

- `conf/node.cnf.tpl`：**8.4 适配的完整 GR 选项文件**——唯一 `server-id`/`report_host`/`group_replication_local_address`；`gtid_mode=ON`、`enforce_gtid_consistency=ON`、`log_replica_updates=ON`、`binlog_format=ROW`、`disabled_storage_engines`、`replica_parallel_workers`/`replica_preserve_commit_order`（8.4 的 replica_* 拼写）；`single_primary_mode=ON`、`member_weight`、`auto_rejoin_tries`、`member_expel_timeout`、`exit_state_action=READ_ONLY`、`ip_allowlist`、`start_on_boot=OFF`（成组只由 AdminAPI 触发）。**头部写明 8.4 红线：`transaction_write_set_extraction`（8.3.0 移除）与 `binlog_transaction_dependency_tracking`（8.4.0 移除）绝不可回填，否则 mysqld 以 unknown variable 拒启**；并写明"注释里不得出现字面 @@TOKEN@@"的模板书写规则（扫描器连注释一起扫）。
- `lib/render-tpl.sh`：`@@NAME@@` 占位符渲染器，缺变量/空值即失败退出（宁可不启动也不让空 server_id 悄悄组错）；值做 `\`、`&`、`|` 三重 sed 转义；只打印 token 名，从不打印值。
- `node-entrypoint.sh`：渲染 → `mysqld --validate-config` 非阻塞自检（8.0.16+，把 8.4 选项打错变成一条 WARN 而不是砖机）→ `exec docker-entrypoint.sh "$@"` 完整保留镜像原生 initdb / `MYSQL_DATABASE` / `MYSQL_USER` / init 目录行为；`MYSQL_HA_GROUP_SEEDS` 为空时由三主机键派生（单一事实源）。
- `create-cluster.js`（mysql-shell AdminAPI，由 job 用 `MYSQL_IMAGE` 自带 shell 执行）：预检 `checkInstanceConfiguration`（蛇形/驼峰键名双兼容）→ 可选 `configureInstance`（容忍失败，静态项在 cnf 里）→ **先 `dba.getCluster()` 复用已有集群，只有不存在才 `dba.createCluster(name,{groupName: 与节点 cnf 同一 UUID, multiPrimary:false, localAddress, accountHostPattern})`——组存在后永不二次 bootstrap（两次播种=两个不相交组的经典事故）** → `addInstance`（`recoveryMethod=clone` 默认，专治"每节点各自 initdb 写出不同 server_uuid 的 errant transactions"；字典/URI 双形态重试）→ 轮询 `status()` 直至 3 个 ONLINE（超时/间隔外置，`ON_NO_QUORUM`/`ERROR` 立即失败并打印成员表）→ `rescan`。凭据只从环境读，argv 上无口令。
- `init/00-cluster-accounts.sql.tpl`：**镜像 `mysql/init/00-exporter.sql` 的 grant 形状**（exporter：`PROCESS, REPLICATION CLIENT, SELECT ON *.*`），另加 `lab_monitor` 只读监控账号（USAGE + PROCESS/REPLICATION CLIENT + SELECT on performance_schema/information_schema/mysql_innodb_cluster_metadata，无 DML/DDL——#51 的 poller 用它）与 app 账号兜底。**刻意不走 docker-entrypoint-initdb.d**（那会在每个节点各自的 initdb 期写进互不相容的 GTID 流=errant transactions），而是在 `dba.createCluster` 成功后定位 ONLINE PRIMARY、只在主上执行一次、随组复制分发。
- `router-bootstrap-entrypoint.sh`：**等 `mysql_innodb_cluster_metadata` 出现才引导**（Router 早于元数据=第二个经典事故：要么失败，要么"健康但零路由"）；`mysqlrouter --bootstrap root@mysql-1:3306 --directory=${MYSQL_HA_ROUTER_DATA_DIR} --conf-scope --ssl-mode=DISABLED ...`；口令投喂三模式 env 切换（`stdin` 默认 / `option-file` 0600 临时文件 / `argv` 显式 WARN）——**非 TTY 提示行为本机离线不可证，留了切换键**；`--user` 仅 uid=0 时追加；可写性预检带 uid/ls -ld 诊断；已有 config 即跳过（幂等），`MYSQL_HA_ROUTER_FORCE_BOOTSTRAP=true` 才重做。

## 3. `code/lab/scripts/db-ha-up.sh`（385 行，分步引导 + 有界等待）

- 5 个阶段各自独立 `compose up -d`：[1/5] 三节点 `--wait --wait-timeout`；[2/5] 引导 job 起后按容器退出码判定（`wait_for_exit`，超时+轮询间隔外置）；[3/5] Router（可 `--skip-router`，绕开 Router 镜像拉取风险）；[4/5] 三导出器；[5/5] `db-cluster-status.sh` 断言"恰好一个 PRIMARY + 全员 ONLINE"后才报成功。
- 前置校验：docker 可用、`.env` 存在（缺失回退 `.env.example` 并 WARN）、`.env.mysql.example` 键名差集提示、**`MYSQL_HA_CLUSTER_GROUP_NAME` 必填且必须是 UUID**（只报长度不报值）。全程不 `source .env`（`env_default` 按键读取、只读 needed），不打印任何口令。
- 失败即打诊断：每阶段 die 附带该坑的定位路径（validate-config WARN 来源、旧卷 gtid OFF、OOM 降 limit、两种子、RECOVERING=clone 还在搬、Router 元数据顺序、数据目录 uid）。

## 4. `code/lab/scripts/db-ha-down.sh`（297 行，仅拆本层）

- **绝不 `docker compose down`**：服务清单取"带/不带 profile 的渲染模型之差"，永不陈旧漂移；双重护栏——与常驻 16 服务重名即中止、超出 `DB_HA_ALLOWED_SERVICES_REGEX` 白名单即中止。
- `stop -t DB_HA_STOP_TIMEOUT_SECONDS`（InnoDB 干净关闭，避免下次启动先跑崩溃恢复）再 `rm -f`；**无 `--purge` 不动任何卷**；`--purge` 只删 `DB_HA_ALLOWED_VOLUMES_REGEX`（`jl-mysql-[123]-data`、`jl-mysql-router-data`），**`jl-mysql-data` 在结构上不可能匹配**（卷清单还额外只从"单独渲染 compose.mysql.yml"得到，dev 层的卷根本不进检查面）。提供 `--dry-run`。收尾对比前后 `label=lab=java-learn` 容器全集，出现计划外消失即报错。

## 5. `code/lab/scripts/db-cluster-status.sh`（277 行，只读，仿 sentinel-status.sh）

- 三段输出：容器 rollup（inspect）、每节点 `performance_schema.replication_group_members` 成员视图（含本节点视角的 read_only/成员数）、Router 6446/6447 当前解析到的后端与 `@@read_only`（用节点容器里的 mysql 客户端探测，可 `DB_CLUSTER_STATUS_PROBE_CONTAINER` 改）。
- 硬约束：**只有 SELECT/inspect**，绝无 DML/DDL/compose 生命周期命令；口令只在容器内由 `MYSQL_PWD="$MYSQL_ROOT_PASSWORD"` 展开（变量**名**从 env 配置，宿主机 argv 上永不出现值）；超时/重试/间隔/端口/容器清单全 env 化；退出码=健康判定（全员应答且恰好一个 PRIMARY 才 0）。`MEMBERSHIP_SQL` 与 #51 的轮询查询逐字一致（见"待定事项"契约）。

## 6. `code/lab/docker/prometheus/mysql-ha-scrape.yml` + `code/lab/docker/.env.mysql.example`

- 抓取文件**刻意惰性**：本步不改 `prometheus.yml`（无权），头部写明接线两行（`scrape_config_files:`，Prometheus 3.x 原生支持）与"bare list、勿包 scrape_configs"的坑；单 job 三 target、`member` 标签供 D11 聚合。端口 9104 与容器名有"与 env 同步改"的耦合声明（Prometheus 无插值，属规约允许的记录在案例外）。
- `.env.mysql.example`（227 行）：**108 个键全部有注释与默认值**，与 compose/脚本消费键双向机械核对通过（compose/脚本消费的每个 MYSQL_HA_*/DB_HA_*/DB_CLUSTER_STATUS_* 键都能在本文件找到；本文件每个键都能找到消费方）。`MYSQL_HA_CLUSTER_GROUP_NAME` 是唯一无默认必填键，并解释为什么（两台机器绝不互认对组）。

# 设计逻辑

- **profile 门控保 16 服务口径**：与 base 的 `traffic`、compose.ha.yml 的 `ha` 同构。实测：不带 profile 渲染含 mysql overlay 仍 16；verify.sh/bench.sh/reset-lab.sh（都按不带 profile 枚举）零感知。`db-ha` 与 `ha` 双 profile 挂同一批服务：`db-ha` 供"dev 栈在跑、只补数据层高可用"的内存友好形态（脚本默认），`ha` 是设计 §3.3 伞形，合并 M3-1a 后一条命令起两层。
- **模式切换走 env、dev 是默认**（§3.3/§8.3）：常驻 `mysql` 服务定义未动；应用侧 `MYSQL_MODE=standalone|router`（#51 落地，命名对齐 Redis 层 `REDIS_MODE`）；缺省即 dev 单机。
- **一次性 job + 声明式依赖，而不是把编排塞进 up.sh**：`createCluster→addInstance→wait→Router bootstrap` 的因果顺序用 `service_completed_successfully` 链进 compose 模型，脚本只做"有界等待 + 失败诊断"；每个 job 幂等可重跑，正是设计风险表"InnoDB Cluster 在 Docker Desktop 初始化不稳"的对策（反复 up 不会组出两个群）。
- **节点身份三件套外置到 .env**（`MYSQL_HA_MYSQL_N_HOST/SERVER_ID/MEMBER_WEIGHT`）：server_id 兼 GTID source id，同 id 组直接拒收；report_host/local_address 用 Compose 服务名而非 IP——**Docker Desktop 每次 recreate 换 IP，IP 身份会让 rejoin 悄悄失败**（与 Sentinel 层用固定 IP 相反，因为 GR 自带对等发现、每次重建都要经 DNS 找到队友）。
- **口令面**：所有 mysql/mysqlrouter/mysqlsh 调用口令走 `MYSQL_PWD`、stdin、0600 option-file 或 shell 的进程内字典，argv/日志/宿主机命令行零出现；Router 元数据缓存放 keyring 文件（卷内），非明文 config。
- **账号在引导后单点落主**（而非 initdb.d）：见"完成功能项 2"；同时把 dev 单机 exporter SQL 的 grant 形状原样镜像进集群，两条栈口径一致。
- **工程规约 §1 对齐**：compose `${VAR:-default}`（比 ha.yml 的 `${VAR}` 更进一步，overlay 可独立于 .env 渲染）；脚本"环境 > .env > 默认"；模板占位符无默认即快速失败。路径/端口/超时/重试/TTL/池/期望数/容器清单/镜像 tag 无一硬编码（唯一在案例外：scrape yml 的 9104，Prometheus 无插值，已写成显式同步注释）。

# 存在缺陷项&TODO

## 实机未验证（本步最大项，也是主会话下一步验收动作）

- **群复制从未真实启动过**：受并发约束（dev 25 容器在跑 + 同机 Redis failover 演练进行中），本轮只允许 `config`/`bash -n`/干跑级验证。下列断言**全部待实机确认**：三节点成组与 clone 恢复；`mysqld --initialize` 在 option file 已含 gtid_mode=ON 时的 initdb 行为（8.4 预期支持，未在本镜像上跑过）；Router 在 `--bootstrap` 非 TTY 下的提示行为（三模式切换键已备好）；`mysql/mysql-router:8.4.4` tag 本机不存在（实测 `docker images` 无）、registry mirror 能否拉取未知——拉不到时 `--skip-router` 先演组内行为，或改指本机已有 router tag。
- 已离线核实的仅有：`docker history mysql:8.4.4` 含 `microdnf install mysql-shell-8.4.4` 层（引导 job 不需要新镜像）；`mysql:8.4.4`、`prom/mysqld-exporter:v0.16.0` 本机在库。

## 内存预算：声明 limit 合计 ≈2.1GB，**高于设计 ≈1.8GB**（在案偏差）

- 稳态声明合计 = 3×640m(节点) + 64m(Router) + 3×48m(导出器) = **2128MiB**（引导 job 128m 跑完即退不计稳态）。8.4 带 performance_schema 的 mysqld 空闲 RSS 实测一般 350–500MB，640m 是"不 OOM 优先"的选择；总盘 16GB 限额下（dev 常驻 ~5.4GB + Redis HA 0.72GB + 本层）仍在预算内，但**违背 §3.1 字面值**。
- 处置选项（主会话实机后二选一）：(a) `docker stats` 实测后把 `MYSQL_HA_NODE_MEM_LIMIT` 与 `MYSQL_HA_INNODB_BUFFER_POOL_SIZE` 成对下调至声明合计 ≤1.8GB（如 512m×3，buffer pool 96M，演示负载足够）；(b) 保留现值并在设计文档把预算改记为 ~2.1GB。`db-ha-up.sh` 的开跑提示（`DB_HA_MEMORY_BUDGET_WARN`）已内置"先停 app 层"的口径。
- 与 M3-1a 同口径的说明：§3.1"每节点 1 个 exporter"按每 MySQL 节点解读（3 个），Router 无 exporter（Router 指标属 #51/后续，需要时另加 mysqlrouter-router 镜像与预算）。

## 其它已知限制

- `jl-mysql-{1,2,3}-exporter` 尚未被 Prometheus 抓取（接线是主会话动作，见下）；D11/面板与 `mysql_cluster_member_state`/`mysql_cluster_primary_info` 指标属 #51。
- 引导 job 的 `restart: "no"` + `depends_on: completed_successfully` 意味着改 env 重新 up 时 job 容器会重建重跑（幂等，设计使然）；但若**上次引导失败留下半成品组**，重跑 create-cluster.js 走 getCluster 分支只会补齐成员，不会解散错组——极端场景需 `db-ha-down.sh --purge` 重来。
- `MYSQL_HA_ROUTER_BOOTSTRAP_EXTRA_OPTS` 是空格分词的裸追加串（刻意简单）；不要往里放带空格的参数值。
- 本层与 M3-1a 的合并冲突面：`compose.mysql.yml` 头注释/脚本对 `compose.ha.yml` 的引用在合并后才完全成立；`ha` profile 语义以合并版为准。

# 待定事项和注意事项

## 主会话必须完成的接线（本步无权改动这些文件）

1. **`code/lab/docker/.env.example`**：并读 `.env.mysql.example` 的 108 键（或至少 `MYSQL_HA_CLUSTER_GROUP_NAME` 生成指引与 Router 两键），保持"一份 .env 全栈生效"。
2. **`code/lab/scripts/up.sh` / `down.sh`**：`ha` 伞形 profile 的文件列表追加 `compose.mysql.yml`（`db-ha` 单层入口保留 `db-ha-up.sh`）；down 路径要保证 DB-HA 容器不残留（参考 M3-1a 对 ha 的处理），且 `down -v` 的卷清单**不得**被本层扩大成能删 `jl-mysql-data`。
3. **`code/lab/docker/prometheus/prometheus.yml`**：加两行 `scrape_config_files: [prometheus/mysql-ha-scrape.yml]`（同文件内相对路径，卷挂载 `:ro` 已覆盖整个目录）并重载 Prometheus；随后 `promtool check config` 或 UI targets 页确认三个 member 抓取 UP。
4. **`code/lab/scripts/verify.sh`**（可选，仿 `REDIS_HA_VERIFY=auto`）：加 `DB_HA_VERIFY` 门控段，仅在 DB-HA 容器存在时跑 `db-cluster-status.sh` 断言，**只增 PASS 不动 dev 计数**。
5. **`code/lab/docker/grafana/dashboards/`**：D11 集群面板等 #51 指标落地后再画，本层 exporter 指标（`mysql_global_status_*`、per-member 复制延迟）已可先接 scrape。
6. `smoke.sh`/`reset-lab.sh`/`bench.sh` 无需改动（profile 门控使其不可见），但主会话应复跑确认 16 服务口径未变。

## 给 #51（应用 Router 双数据源与集群状态指标）的契约

1. **接入点**：jl-net 内主机名 `mysql-router`，端口 `6446` 读写 / `6447` 只读（env 键 `MYSQL_HA_ROUTER_RW_PORT/RO_PORT`，可改）；宿主机侧 `127.0.0.1:6446/6447`（`MYSQL_HA_ROUTER_*_HOST_PORT`）。**应用绝不直连 `mysql-1/2/3`**（节点端口未发布，且直连会让切换演示失效）；Router 是设计 §8.6 认可单点。X 协议 6448/6449 存在但本 lab 不用。
2. **凭据模型**：业务账号=现有 `MYSQL_USER/MYSQL_PASSWORD`（`seckill` 库，账号 SQL 已在主上落组）；集群探针账号 `MYSQL_HA_MONITOR_USER/PASSWORD`（`lab_monitor`，只读：PROCESS、REPLICATION CLIENT、SELECT on performance_schema/information_schema/mysql_innodb_cluster_metadata）；管理/引导账号 `MYSQL_HA_ADMIN_USER`(=root)+`MYSQL_ROOT_PASSWORD`，应用侧不需要。口令一律 env，不进日志。
3. **切换开关（dev 默认）**：`MYSQL_MODE=standalone|router` 放 `.env`；standalone 沿用 `x-app-env` 的 `MYSQL_HOST=mysql`/3306；router 模式读写源 `jdbc:mysql://mysql-router:6446/seckill`、只读源 `jdbc:mysql://mysql-router:6447/seckill`。读写路由沿用 `ReadWriteRoutingDataSource` + **必须 `LazyConnectionDataSourceProxy`**（设计 §8.3 点名的坑）。
4. **集群状态指标（10s 轮询）**：**不需要 mysql-shell**，直接 SQL（`db-cluster-status.sh` 里 `MEMBERSHIP_SQL` 的裁剪版，逐字一致）：
   `SELECT MEMBER_HOST, MEMBER_PORT, MEMBER_STATE, MEMBER_ROLE FROM performance_schema.replication_group_members;`
   用 `lab_monitor` 经 6446 或任一节点执行；`member_state` 按角色导出 `mysql_cluster_member_state{member=...}`，`MEMBER_ROLE='PRIMARY'` 行导出 `mysql_cluster_primary_info`。若坚持 mysql-shell 形态：`mysqlsh --js --uri=lab_monitor@mysql-router:6446 --no-log -e "print(dba.getCluster('labCluster',{connectOnly:true}).status())"`（monitor 账号的元数据 SELECT 已覆盖）。
5. **chaos-controller 红线**：数据层容器（`jl-mysql`、`jl-mysql-1/2/3`、`jl-mysql-router` 与其 exporters）**永不作为故障动作目标**——切换演示走手动 `docker stop jl-mysql-<primary>`（Runbook A6）或 #51 里显式白名单的新动作；现有 `CHAOS_GUIDE_TARGET_PATTERN=^jl-app-[0-9]+$` 的收口模式保持不变，任何放宽都要审查不含 mysql 前缀。
6. **切换语义预期**：杀主 → 组选举（member_weight 高者胜，平票看 uuid）→ Router 元数据刷新后重指 6446（应用侧表现为短报错+重试恢复，这是教学点不是缺陷）→ 旧主回来以 READ_ONLY 从身份 rejoin（exit_state_action=READ_ONLY 保证它不自杀）。

## 运维与安全注意

- **诚实口径**：本步交付=编排与工具，**不是"集群已跑通"**；对外表述请用"Runbook 就绪、待主会话实机验收"。
- 只读观察永远安全：`bash code/lab/scripts/db-cluster-status.sh`（含 `--router`/`--no-router`/`--service` 变体）。拆除只经 `db-ha-down.sh`（先 `--dry-run`）；`--purge` 会强制下次全量重引导（clone），执行前确认没有并发实验依赖这三个卷。
- `.env`、`dist/`、`node_modules/`、`reports/`、`.superpowers/`、`.r2c/`、`code/services/**` 一律不提交；本步未改 `compose.base/dev/ha.yml`、`up/down/verify/smoke/reset-lab/bench.sh`、k6、dashboard、`阶段交接-2026-10-26.md`。
- 若宿主机 6446/6447 被占：改 `MYSQL_HA_ROUTER_RW/RO_HOST_PORT`（容器内端口可保持 6446/6447，jl-net 内主机名+端口即契约全部）。

# 附录

## 关键文件

- Compose：`code/lab/docker/compose.mysql.yml`、`code/lab/docker/.env.mysql.example`、（未改）`compose.base.yml`/`compose.dev.yml`
- 镜像内资产：`code/lab/docker/mysql-cluster/{node-entrypoint.sh,cluster-init-entrypoint.sh,router-bootstrap-entrypoint.sh,create-cluster.js,lib/render-tpl.sh,conf/node.cnf.tpl,init/00-cluster-accounts.sql.tpl}`
- 脚本：`code/lab/scripts/db-ha-up.sh`、`db-ha-down.sh`、`db-cluster-status.sh`
- 观测：`code/lab/docker/prometheus/mysql-ha-scrape.yml`（待接线）
- 设计：`doc/design/2026-10-26-ha-concurrency-lab-design.md` §8.3/§3.1/§3.3/§8.6、`doc/design/数据库高可用方案对比.md` §2/§3.5、`doc/design/工程规约.md` §1

## 回退判据（方案 A → 方案 B：主从 + ProxySQL，`数据库高可用方案对比.md` §3.5）

触发条件（任一长期成立即评估回退，记录进交接文档）：

1. **InnoDB Cluster 在当前 Docker Desktop 环境反复初始化失败且短期内无法稳定**——操作化口径：Runbook A2–A4 累计 3 次完整重跑（含 `--purge` 重来）仍到不了"3 ONLINE + 单 PRIMARY + Router 双路由可查"，或成组后日常 rejoin/clone 故障率使一次演示准备超过 ~30 分钟；
2. 演示重心转向"切换过程逐步可讲解"而非自动选主（方案 B 的手动提从+改代理更符合）；
3. 宿主机内存需压到 12GB 以下（方案 B 1主2从1proxy 可省 ~0.6–1GB）。

回退影响面（届时变化什么，本步预留了什么）：

| 影响点 | 变化 | 本步已留的接口 |
|---|---|---|
| compose | 用主从+ProxySQL overlay 替换 `compose.mysql.yml`（profile 语义不变） | 服务名/端口/profile 只在 overlay 内，dev 层零牵连 |
| 应用数据源 | 连接串指向 ProxySQL 地址（写端口/读端口）；读写路由保留 | #51 契约第 3 条：应用只认 env 里的 host:port 对，不认"Router"本身 |
| chaos-controller | 提从、更新 ProxySQL 后端、旧主接回改为脚本动作 | 只读探针 SQL（performance_schema 视图）换成 ProxySQL `stats_mysql_*`；"永不自动打数据层"红线不变 |
| 面板 D11 | 指标改为 ProxySQL 后端状态与复制延迟 | exporter 抓取按 member 标签的形状可平移 |
| 验收场景 | `db-failover` 判定不变，切换由脚本完成 | `db-cluster-status.sh` 的 RESULT 语义（单写点+全员在线）保持可实现 |

## 附录 A：实机 Runbook（主会话验收动作，逐步命令 + 期望输出 + 坑位诊断）

> 全程在仓库根、Git Bash 内执行；命令均出自本步交付物。**顺序不可乱**：A1→A2（成组）→A3（Router）→A4（断言）→A5（接线）→A6（演练）。演练期间停 app 层（`docker compose ... stop app-1 app-2 app-3`）以满足内存预算；本 Runbook 只在确认无其他实验并发时执行。

**A0 前置**

```bash
docker images --format '{{.Repository}}:{{.Tag}}' | grep -E 'mysql|router'
# 期望：mysql:8.4.4、prom/mysqld-exporter:v0.16.0 在列（本步实测均存在）；
#       mysql/mysql-router:8.4.4 本步实测【不在列】→ 先手动拉：
docker pull mysql/mysql-router:8.4.4
```

拉取失败（registry mirror）→ 用本机已有 router tag 覆盖 `MYSQL_HA_ROUTER_IMAGE`，或 `db-ha-up.sh --skip-router` 先验 A2/A4。

```bash
uuidgen | tr 'A-Z' 'a-z'   # 记下输出
grep -E '^[A-Z]' code/lab/docker/.env.mysql.example >> code/lab/docker/.env
# 编辑 .env：MYSQL_HA_CLUSTER_GROUP_NAME=<上一步的 UUID>
```

诊断：该键缺失/非 UUID → db-ha-up.sh 立刻 die 并给出生成命令；mysqld/Shell 对非 UUID 组名的报错难读，前置校验就是为拦截它。

**A1 不变式复核（纯解析，允许随时跑）**

```bash
cd code/lab/docker
docker compose --project-name java-learn-lab --project-directory . --env-file .env \
  -f compose.base.yml -f compose.dev.yml -f compose.mysql.yml config --services | wc -l   # 16
docker compose ... --profile db-ha config --services | sort                                # 25，追加 9 个
```

诊断：默认 >16 → 某个新服务漏了 `profiles:`（红线，回去补）；`--profile db-ha` 仍 16 → profile 名不匹配（`DB_HA_PROFILE`/锚点声明）。`MYSQL_HA_CLUSTER_GROUP_NAME` 未设时 config 会打 4 条 "Defaulting to a blank string" WARN，属预期（唯一无默认键）。

**A2 起节点 + 成组**

```bash
bash code/lab/scripts/db-ha-up.sh
```

期望逐段输出：`[1/5] ... Nodes are healthy (mysqladmin ping).` → 节点日志里 `node config ready: server_id=<n> report_host=mysql-<n> local_address=mysql-<n>:33061` → `[2/5]` bootstrap 日志序列 `preflight/configured → creating cluster "labCluster" on root@mysql-1:3306 → adding root@mysql-2/3 (recoveryMethod=clone) → cluster ONLINE: ONLINE 3/3, PRIMARY count=1 → BOOTSTRAP OK` → `Bootstrap job finished cleanly (exit 0).`

坑位诊断（每阶段 die 文本已内置）：
- **节点不起/unknown variable**：看 `docker logs jl-mysql-1` 是否有 node-entrypoint 打的 `--validate-config` WARN；8.4 删过的 write-set 选项回填是最典型笔误（模板头已写明禁令）。
- **initdb 阶段失败**：`gtid_mode=ON` 随 option file 进入首启 temp server；若本镜像组合出现 initialize 拒绝，把 `MYSQL_HA_PERFORMANCE_SCHEMA` 保持 ON 的同时先手查 `docker logs`，**不要**改成"先起裸实例再改配置"——卷会带着 no-gtid 历史，后患同"旧卷复用"。
- **server_id 相同 → 组拒不成**：三节点的 `MYSQL_HA_MYSQL_N_SERVER_ID` 必须 1/2/3 互异（compose 默认已保证；改 env 时别复制）。
- **report_host 不可解析 → 成员互认失败**：`docker exec jl-mysql-1 bash -c 'getent hosts mysql-2'` 必须有解；report_host/local_address 只能是 Compose 服务名（IP 在 recreate 后必失效）。
- **已有组时又 createCluster**：不允许——重跑 A2 时 create-cluster.js 走 `dba.getCluster()` 复用分支，日志应出现 `cluster "labCluster" already exists - re-running membership only`；若出现两个 disjoint 组的症状（每边各看到 1-2 成员），`db-ha-down.sh --purge` 后重来。
- **addInstance 报 errant transactions**：正常预期路径，`MYSQL_HA_RECOVERY_METHOD=clone` 自动克隆治愈；成员长期 RECOVERING → 看被克隆节点日志，慢盘时上调 `MYSQL_HA_CLUSTER_READY_TIMEOUT_SECONDS` 与 `DB_HA_INIT_WAIT_TIMEOUT_SECONDS`。
- **首次 3 节点成形要重试**：`MYSQL_HA_INIT_RETRY_COUNT/INTERVAL`（12×10s）与 `waitSyncTimeout` 是有界重试预算，日志中 `waiting for mysqld (ready=2/3 ...)` 几轮属正常冷启动，超出预算才失败。

**A3 Router 起 + 引导确认**

期望：`[3/5]` 段先由 `jl-mysql-router-init` 打印 `InnoDB Cluster metadata schema is present (attempt N)` → `bootstrapping Router against root@mysql-1:3306 (mode=stdin, scope=ROUTING)` → `generated /var/lib/mysqlrouter/mysqlrouter.conf` + 路由摘要（destinations 含三成员）→ `jl-mysql-router` healthy，`docker logs jl-mysql-router` 有 `Routing ... 6446/6447`。

坑位诊断：
- **Router 早于元数据引导**：不可能经 A2 顺序发生，但若手工起过 Router，删 `jl-mysql-router-data` 卷重来（Router 引导必须等 `mysql_innodb_cluster_metadata` 存在——job 里有 SHOW SCHEMAS 门）。
- **stdin 模式挂住/提示超时**（本机离线不可证的那条）：失败文本会点名当前 mode；改 `.env` `MYSQL_HA_ROUTER_PASSWORD_MODE=option-file` 重跑本脚本，最后手段 `argv`（会 WARN 口令可见）。
- **数据目录不可写**：preflight 打印 uid 与 remedy；或换 `MYSQL_HA_ROUTER_DATA_DIR`。
- **健康检查失败但进程在**：镜像无 bash 时整条命令 env 覆盖 `MYSQL_HA_ROUTER_HEALTHCHECK_CMD`。

**A4 断言与手工烟测**

```bash
bash code/lab/scripts/db-cluster-status.sh
# 期望三行 membership：全员 ONLINE、恰一个 PRIMARY，6446 -> (server_id=<primary>, READ_WRITE)，
# 6447 -> 某个 secondary（READ_ONLY）；末行 RESULT: healthy ... exit 0
docker exec jl-mysql-1 bash -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -h mysql-router -P 6446 -uroot \
  -e "SELECT @@hostname, @@read_only, @@server_id;"'    # 期望：PRIMARY 主机名, read_only=0
```

6446 返回 read_only=1 或报 "all routes with matching destinations unavailable" → Router 元数据还没刷新（等 1-2 个 TTL）或账号 host 面被 `MYSQL_HA_ACCOUNT_HOST_PATTERN` 改窄。

**A5 观测接线**（"待定事项"主会话清单第 3 条）：prometheus.yml 加 `scrape_config_files` → reload → UI targets 里 `mysqld-exporter-ha` 三 member UP → 可选 verify.sh 的 `DB_HA_VERIFY` 段。

**A6 切换演示（验收动作，非本步必做）**

```bash
docker stop jl-mysql-<当前PRIMARY>       # 手动，只经本 Runbook，不交给 chaos-controller
bash code/lab/scripts/db-cluster-status.sh --router   # 观察新主晋升 + 6446 改指 + 6447 池收缩
docker start jl-mysql-<旧主>              # 期望其以 SECONDARY/READ_ONLY rejoin（exit action=READ_ONLY）
```

演示期间 `docker logs -f jl-mysql-router` 会打出目的地翻转（这就是 Router 控制台的用途）。应用侧持续写入 + 重试的表现属 #51。

**A7 拆除**

```bash
bash code/lab/scripts/db-ha-down.sh --dry-run && bash code/lab/scripts/db-ha-down.sh
# 期望末行："DB-HA tier is down. Always-on dev stack untouched..."；jl-mysql/jl-redis 等 16 容器仍在
```

## 附录 B：本步实际验证输出（证据）

- profile 不变式（base+dev+mysql，worktree 实测）：默认 `config --services` = **16**；`--profile db-ha` 与 `--profile ha` 均 = **25**，追加集恰为 `{mysql-1, mysql-1-exporter, mysql-2, mysql-2-exporter, mysql-3, mysql-3-exporter, mysql-cluster-init, mysql-router, mysql-router-init}`。
- `bash -n`：`db-ha-up.sh`、`db-ha-down.sh`、`db-cluster-status.sh`、`node-entrypoint.sh`、`cluster-init-entrypoint.sh`、`router-bootstrap-entrypoint.sh`、`lib/render-tpl.sh` **全 OK**；无 shellcheck 可用（未安装，如实记录）。
- 渲染器离线干跑：`node.cnf.tpl` 15 token、`00-cluster-accounts.sql.tpl` 8 token 全部渲染成功，含 `|`/`&`/`\` 对抗值正确落入输出（修复了 `|` 截断后）。
- 键面双向核对：compose/脚本/模板消费的 111 个 `MYSQL_HA_*`/`DB_HA_*`/`DB_CLUSTER_STATUS_*` 变量 vs `.env.mysql.example` 的 108 个键，除 4 个"compose 内部按节点派生名"（`MYSQL_HA_SERVER_ID/REPORT_HOST/MEMBER_WEIGHT` 由 per-node 键映射、`MYSQL_HA_GR_LOCAL_ADDRESS` 容器内派生，均在文档中说明）外全覆盖；无死键。
- 镜像事实：`docker history mysql:8.4.4` 含 `microdnf install -y mysql-shell-$MYSQL_SHELL...` 层；本机有 `mysql:8.4.4`、`prom/mysqld-exporter:v0.16.0`；**无** `mysql/mysql-router:8.4.4`（A0 先拉）。
- 未执行（明确声明）：任何 `docker compose up/create/start/stop/kill/rm/down`、`docker run`、卷/网络变更、`mvn`。**集群实机初始化未验证，是主会话的下一步验收动作。**
