> 步骤交接 · M3 探针目标解耦与 PRIMARY 自检告警（step 47）
> 日期：2026-10-27
> 分支 / 基线：`feature/ha-lab`，基线 `2980874`；功能提交 `a17eb67`、测试提交 `9eb392d`，实测中发现的 WARN 噪音修复 `749e125` + 测试 `a9d4b70`，文档提交见 `git log`
> 关联：`doc/process/Stage1/step-30-mysql-cluster-metrics.md`（D11 指标）、`doc/process/Stage1/step-31-mysql-router-live-drill.md`（被杀节点就是 jl-mysql-1 的那次演练）、`doc/process/Stage1/step-45-ha-acceptance-script.md`（记录过“compose 未接线”的缺口）
> 验证边界：模块测试 `mvn -pl services/chaos-controller -am test` 250 tests / 0 failures；chaos-controller 镜像重建后在真机栈上做了一次目标切换实测（jl-mysql-3 默认 → jl-mysql-2 命中 PRIMARY → 还原 jl-mysql-3）。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 1. 当前进展

- 关闭了 step-45 记录的缺口：`CHAOS_MYSQL_CLUSTER_CONTAINER` 以前只写在 `application.yml` 和 `.env.example` 的注释里，`compose.base.yml` 没有把它（以及其余 8 个已实现的 `CHAOS_MYSQL_CLUSTER_*` 键）传给 chaos-controller，所以改 `.env` 无效。
- 现在 9 个键全部接线，且 **lab 默认目标改为 `jl-mysql-3`**：`jl-mysql-1` 是 step-31 演练的惯用击杀目标，探针坐在被杀的节点上时 `mysql_cluster_probe_up=0`、成员/主库序列冻结，演练期间看起来像“探针坏了”而不是“集群切换了”。代码默认值仍是 `jl-mysql-1`（属性自洽），差异只存在于 compose 默认值、`.env.example` 注释和 `ChaosProperties` 的 javadoc。
- 新增自检 WARN：成功探针发现“自己的目标容器就是当前 PRIMARY”时，每次状态变化只告警一次（进入 PRIMARY 一次 WARN，离开时一条 INFO，再成为 PRIMARY 再 WARN 一次）。
- 容器名到 `MEMBER_HOST` 的映射显式实现为“去掉 `jl-` 前缀”，整串比较，不做 `contains`：`jl-mysql-1` ↔ `mysql-1`，`jl-mysql-10` ↔ `mysql-10` 不会误匹配 `mysql-1`。
- 真机实测顺带发现并修复了一个**既有缺陷**：`logMembershipChanges` 用 `old != now` 比较每轮新建的 `Observation` 记录（引用比较），导致每个 tick 都打一条 `member mysql-1 changed: SECONDARY/ONLINE -> SECONDARY/ONLINE` 的假 WARN，正好淹没了“每次状态变化一条”的语义。改为 `!old.equals(now)` 并补了回归测试。
- 当前栈状态：chaos-controller healthy（目标 `jl-mysql-3`），`probe_up=1`、`member_count=3`、`mysql_cluster_primary{member="mysql-2"}` 与 `db-cluster-status.sh` 的 `RESULT: ok` / `mysql-2 ONLINE PRIMARY` 一致。

# 2. 完成功能项

## 2.1 compose 接线（`code/lab/docker/compose.base.yml`，仅 chaos-controller 环境块）

`application.yml` 中已实现且可由环境变量覆盖的 9 个键，此前一个都没转发；现在全部出现在 controller 的 `environment:` 里，默认值逐项对齐 `application.yml`（唯一有意不同的是 `CHAOS_MYSQL_CLUSTER_CONTAINER`）：

| 环境变量 | 映射属性 | compose 默认 | `application.yml` 默认 | 说明 |
| --- | --- | --- | --- | --- |
| `CHAOS_MYSQL_CLUSTER_ENABLED` | `chaos.mysql-cluster.enabled` | `true` | `true` | `false` 时不建 meter、不起调度线程、不 exec |
| `CHAOS_MYSQL_CLUSTER_INTERVAL_SECONDS` | `interval-seconds` | `10` | `10` | 轮询周期 |
| `CHAOS_MYSQL_CLUSTER_CONTAINER` | `container` | **`jl-mysql-3`** | `jl-mysql-1` | 探针 exec 的目标节点；lab 默认避开惯用击杀目标 |
| `CHAOS_MYSQL_CLUSTER_ADMIN_USER` | `admin-user` | `root` | `root` | 非密钥 |
| `CHAOS_MYSQL_CLUSTER_PASSWORD_ENV_VAR` | `password-env-var` | `MYSQL_ROOT_PASSWORD` | 同 | 变量名，不是密码本身；密码只在容器内展开 |
| `CHAOS_MYSQL_CLUSTER_EXEC_TIMEOUT_SECONDS` | `exec-timeout-seconds` | `6` | `6` | 启动校验要求 `<= interval` |
| `CHAOS_MYSQL_CLUSTER_EXPECTED_MEMBER_COUNT` | `expected-member-count` | `3` | `3` | 成员数变化时每变化一条 WARN |
| `CHAOS_MYSQL_CLUSTER_THREAD_NAME` | `thread-name` | `mysql-cluster-probe` | 同 | 独立单线程守护调度器 |
| `CHAOS_MYSQL_CLUSTER_SHUTDOWN_TIMEOUT_SECONDS` | `shutdown-timeout-seconds` | `5` | `5` | 上下文关闭时等待在途 exec |

SQL 文本（`query`）和 exec argv（`command`）仍留在镜像内的 `application.yml`：它们是镜像配置而不是运行期开关，放进 compose 只会制造第二份事实来源。

## 2.2 `.env.example`

新增“Controller-side MySQL cluster probe (step-47)”小节，逐个键给注释：哪个键影响什么、为什么默认 `jl-mysql-3`、为什么密码只以变量名传递、`exec-timeout <= interval` 的启动校验。原来那段“compose.base.yml does not forward it … step-45 records the gap”的过期说明已删除，改为“改 `.env` 后只需 `compose up -d chaos-controller`，不用重建镜像”。

## 2.3 探针自检（`MysqlClusterMonitor`）

- `membershipHost(String container)`：显式去 `jl-` 前缀，其余原样返回；注释写明“整串匹配，绝不 `contains`”。
- `logSelfPrimary(ClusterMembership)`：在 `onSuccess` 的成功路径最后调用，`lastSelfPrimary` 记录上一次成功观察到的布尔值：
  - 由“非 PRIMARY / 首次”变为 PRIMARY → 一条 WARN，说明“杀掉这个节点也会杀掉探针，`mysql_cluster_*` 会冻结，请把 `CHAOS_MYSQL_CLUSTER_CONTAINER` 指向别的成员”；
  - 由 PRIMARY 变为非 PRIMARY → 一条 INFO（主库已换到谁），不重复 WARN；
  - 成员关系不变 → 不重复打日志（每 tick 一次是不允许的）；
  - split brain 场景用 `membership.primaries()` 任一命中即视为“自检命中”，与 `primary()` 只接受单主不冲突；
  - 探针失败时状态未知，保留上一个成功观察值，恢复后若仍是 PRIMARY 也不再补打（WARN 只跟随成功样本的状态变化）。

## 2.4 测试（`MysqlClusterMonitorTest`，5 → 11 个用例）

| 用例 | 断言 |
| --- | --- |
| `membershipHostMappingStripsTheLabPrefixOnly` | `jl-mysql-1→mysql-1`、`jl-mysql-10→mysql-10`、`mysql-2→mysql-2` |
| `warnsOnceWhenTheProbeTargetIsThePrimary` | 目标 `jl-mysql-1`，两个 tick 完全相同 → 恰好 1 条自检 WARN，正文含 `jl-mysql-1 (mysql-1)`、`mysql_cluster_* freezes`、`CHAOS_MYSQL_CLUSTER_CONTAINER` |
| `doesNotWarnWhenTheProbeTargetIsNotThePrimary` | 目标 `jl-mysql-3`（SECONDARY）→ 0 条 |
| `doesNotWarnWhenTheContainerNameOnlySharesAPrefixWithTheHost` | 目标 `jl-mysql-10`、成员 `mysql-1` 为 PRIMARY → 0 条（整串匹配） |
| `warnsAgainOnlyAfterThePrimaryMovesAwayAndBack` | mysql-1 PRIMARY → 1；主库移到 mysql-2 → 仍 1；再切回 → 2 |
| `anUnchangedSecondPollDoesNotLogAMembershipChange` | 第二轮相同成员关系不产生 `member mysql-1 changed` 假 WARN（引用比较回归测试） |

日志抓取用 logback `ListAppender` 挂在 `MysqlClusterMonitor` 的 logger 上，测试结束 `detachAppender`，不需要 Docker、不需要 Spring 上下文。

# 3. 设计逻辑

- **为什么 lab 默认是 `jl-mysql-3`**：`db-cluster-status.sh` 当前主库是 `jl-mysql-2`，`jl-mysql-1` 是 step-31 Router 演练和文档里出现次数最多的击杀目标。探针目标被杀时，`docker exec` 失败 → `probe_up=0`，成员/主库序列保持最后一次成功值，演练期间面板显示的是“探针坏”，不是“主库切换”。选一个既不是当前主库、也不是惯用击杀目标的节点（`jl-mysql-3`）把这种混淆降到最低。
- **为什么不改代码默认值**：`ChaosProperties.MysqlCluster.container` 的属性要在单测、独立运行和本仓库之外都自洽；lab 的部署选择属于 compose，用 `${CHAOS_MYSQL_CLUSTER_CONTAINER:-jl-mysql-3}` 表达，同时保留“显式覆盖”的能力。代价是同一个键有两个默认值——本节和 javadoc 都写明，读代码的人不会以为 compose 抄错了。
- **映射为什么显式去前缀**：成员关系来自 `performance_schema.replication_group_members.MEMBER_HOST`，compose 设置 `report_host=mysql-N`；探针容器是 `jl-mysql-N`。两者只差一个固定前缀，但用 `contains`/`startsWith` 反向匹配会引入 `jl-mysql-10` 命中 `mysql-1` 这类误报，所以只做“去前缀 + 整串相等”。
- **WARN 的语义**：告警的对象不是“集群状态变化”，而是“探针自身处在演练风险位”。它必须只在状态变化时打一次：每 tick 一次会淹没真正的成员变化；完全不打则 step-45 的缺口会以另一种形式复发（固定节点在任何一次后续 failover 之后都可能变成 PRIMARY）。离开 PRIMARY 用 INFO 而不是 WARN，因为此时探针是 drill-safe 的，不需要告警级别。
- **失败策略不变**：exec 超时/非零退出/解析失败仍然是 `probe_up=0` + 保留最后一次成功序列，自检日志只跟随成功样本，避免“探针坏了”被误报成“探针目标是主库”。
- **配置外置**：9 个键全部走环境变量，无新增硬编码路径/端口/超时；compose 默认值与 `application.yml` 逐项对齐，并有本节表格可核对。

# 4. 存在缺陷项&TODO

- **本质限制（未消除，只缓解）**：任何固定节点都可能在后续 failover 后成为 PRIMARY。若一次演练恰好杀掉探针所在节点，`mysql_cluster_*` 仍会冻结到该节点恢复；WARN 是缓解（演练前就能看到“探针站在主库上”），不是自动切换。真正的彻底方案是探针按 Router/任意存活节点轮询，属于后续任务，不在本步范围。
- **实测中发现的既有缺陷已修**：`Observation` 引用比较导致每 tick 一条假 WARN（`749e125`）。这个缺陷在 step-30 交付时就存在，只是没有真机盯着日志数 WARN；本步的“每状态变化一条”要求把它暴露了出来。
- **`query` 和 `command` 未环境变量化**：`application.yml` 里就是字面量，compose 不转发是符合现状的；若以后要支持自定义 SQL，需要同时加 `CHAOS_MYSQL_CLUSTER_QUERY` 并处理 argv 中 SQL 的注入边界，属于安全边界变更，需单独立项。
- **无额外的“目标节点是否存在”预检**：目标容器名写错时只会得到 `probe_up=0` + 一条失败 WARN（设计如此）；本步没有加启动期容器探测，避免 controller 启动依赖 db-ha 层。
- 探针容器名只校验容器名正则，不强制 `jl-mysql-N`；误配成 `jl-redis-1` 会得到一条可读的 exec 失败 WARN，而不是启动失败。保持与 step-30 一致。

# 5. 待定事项和注意事项

## 5.1 真机证据（2026-10-27，`feature/ha-lab`，镜像 `java-learn-lab/chaos-controller:jdk21` 重建后）

集群起点（`bash code/lab/scripts/db-cluster-status.sh`）：

```
jl-mysql-2       mysql-2      3306    READ_WRITE   3        mysql-3:3306 ONLINE SECONDARY | mysql-1:3306 ONLINE SECONDARY | mysql-2:3306 ONLINE PRIMARY
read/write :6446   -> mysql-2        (server_id=2, READ_WRITE)
RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY.
```

A. 默认目标（不设环境变量，compose 默认 `jl-mysql-3`）重建 controller 后：

```
target: jl-mysql-3
"message":"MySQL cluster probe scheduled every 10s against jl-mysql-3 (expected 3 members)"
"message":"MySQL cluster probe started: 3 member(s), primary mysql-2"
```

```
$ curl -s "http://127.0.0.1:8080/prom/api/v1/query?query=mysql_cluster_probe_up"
[{"metric":{"__name__":"mysql_cluster_probe_up",...,"job":"chaos-controller"},"value":[...,"1"]}]
$ curl -s "http://127.0.0.1:8080/prom/api/v1/query?query=mysql_cluster_member_count"
[{"metric":{"__name__":"mysql_cluster_member_count",...},"value":[...,"3"]}]
$ curl -s "http://127.0.0.1:8080/prom/api/v1/query?query=mysql_cluster_primary"
[{"metric":{"__name__":"mysql_cluster_primary",...,"member":"mysql-2"},"value":[...,"1"]}]
```

`probe_up=1`、`member_count=3`、`mysql_cluster_primary{member="mysql-2"}` 与状态脚本一致；controller 日志中自检 WARN 计数为 0，假 `changed:` WARN 计数也为 0。

B. 把探针放到当前主库（`CHAOS_MYSQL_CLUSTER_CONTAINER=jl-mysql-2` 重建，观察约 45s / 4 个 tick）：

```
target: jl-mysql-2
self-primary WARN count: 1
false changed WARN count: 0
"message":"MySQL cluster probe scheduled every 10s against jl-mysql-2 (expected 3 members)"
"message":"MySQL cluster probe started: 3 member(s), primary mysql-2"
"level":"WARN","message":"MySQL probe target jl-mysql-2 (mysql-2) is the current PRIMARY: a failover drill that kills this node also stops the probe, so mysql_cluster_* freezes until it returns. Point CHAOS_MYSQL_CLUSTER_CONTAINER at a different member."
```

C. 还原：再次不设环境变量重建，`target: jl-mysql-3`，`jl-chaos-controller Up (healthy)`，自检 WARN 计数 0；未做任何故障动作、未动 `.env`、未 purge。栈保持健康。

## 5.2 注意事项

- 改 `.env` 的 `CHAOS_MYSQL_CLUSTER_*` 后必须重建 controller 容器（`docker compose -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml up -d --no-deps chaos-controller`）；环境变量只在容器创建时注入，`restart` 不会重新读 `.env`。
- 若演练目标是当前主库，演练前先看一眼 controller 日志有没有自检 WARN：有就把探针换到另一个成员，否则 `probe_up=0` 会被误读成“探针故障”。
- Prometheus 导出名是 `mysql_cluster_primary`（Micrometer 会剥掉 `_info` 后缀）；不要按代码常量 `mysql_cluster_primary_info` 去查询。
- 探针没有自动切换目标：failover 后如果原目标变成新主库，WARN 会出现，但探针仍留在该节点——这是有意的显式决策点，不是遗漏。

# 附录

改动文件：

- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/cluster/MysqlClusterMonitor.java`（映射 + 自检 WARN + 引用比较修复）
- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/config/ChaosProperties.java`（`container` javadoc 说明两个默认值）
- `code/services/chaos-controller/src/test/java/com/javalearn/lab/chaos/cluster/MysqlClusterMonitorTest.java`（5 → 11 用例）
- `code/lab/docker/compose.base.yml`（仅 chaos-controller 环境块，新增 9 个键）
- `code/lab/docker/.env.example`（新键注释，替换过期缺口说明）
- 本文档

验收命令：

```bash
cd code && mvn -pl services/chaos-controller -am test        # 250 tests / 0 failures
curl -s "http://127.0.0.1:8080/prom/api/v1/query?query=mysql_cluster_probe_up"
bash code/lab/scripts/db-cluster-status.sh                  # RESULT: ok
docker logs jl-chaos-controller | grep "is the current PRIMARY"
```
