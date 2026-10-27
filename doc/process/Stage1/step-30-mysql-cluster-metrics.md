> 步骤交接（无头部块，2026-10-27 文档整理时补）
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

M3-2 的指标半边（任务 #51b / 步骤 30）落地：chaos-controller 以固定间隔对 InnoDB Cluster 执行**只读成员查询**，把成员状态与主节点导出为 Prometheus 指标，供 D11 面板使用。

- 代码基线：本步在 `worktree-agent-general-purpose-1023f206` 完成，合并前基线为 `859ea7e`。
- 测试：`mvn -pl services/chaos-controller -am test` → **138 tests / 0 failures / BUILD SUCCESS**（125 基线 + 13 本步新增）。
- 说明：本步由两段组成——第一段代理在写入实现后因基础设施错误中断（未提交），第二段补测试与文档并修正三处编译问题（网关接口新增 `execInContainer` 导致两个既有测试替身不完整；`Search.multiGauge()` 不存在；测试替身方法插到了内部类外面）。

# 完成功能项

- **网关 exec 能力**：`ContainerGateway#execInContainer(container, command, env, stdin, timeoutSeconds)` + `ExecResult(stdout, stderr, exitCode, timedOut)`。口令不进入 argv：通过 exec 环境变量传入容器，在容器内由 bash 展开。
- **成员查询与解析**：默认对 `jl-mysql-1` 执行
  `SELECT MEMBER_HOST, MEMBER_PORT, MEMBER_STATE, MEMBER_ROLE FROM performance_schema.replication_group_members ORDER BY MEMBER_ROLE DESC, MEMBER_HOST`，
  解析器同时接受 `mysql --batch --raw --skip-column-names` 的四列制表符行，以及 `db-cluster-status.sh` 的 `host:port STATE ROLE | …` 汇总格式。
- **指标**（代码中的 meter 名 → Prometheus 导出名）：
  - `mysql_cluster_member_state{member,role,state}` → 同名，每个成员一行，值为 1；
  - `mysql_cluster_primary_info{member}` → **导出为 `mysql_cluster_primary`**（Prometheus 命名约定会剥掉 `_info` 后缀，见「缺陷」）；
  - `mysql_cluster_member_count`：上次成功探测的成员行数；
  - `mysql_cluster_probe_up`：上次探测成功为 1，失败为 0；
  - `mysql_cluster_probe_success_total` / `mysql_cluster_probe_failure_total`：探测成功/失败计数。
- **失败语义**：exec 失败、超时、非零退出、输出不可解析都只产生一次失败样本并置 `probe_up=0`，**保留上一次成功的成员序列**（不会把「探针坏了」显示成「集群消失」）；恢复时打一条 INFO。
- **成员消失**：成功探测后按整组重新注册 `MultiGauge`，缺席成员的行会被删除，不会残留 `=1` 幽灵序列。
- **禁用态**：`enabled=false` 时不建任何 `mysql_cluster_*` 表、不建调度线程、不 exec（dev 栈没有 db-ha 层时避免每 10s 刷失败日志）。

# 设计逻辑

- **为什么探针打在 `jl-mysql-1` 而不是 Router**：`performance_schema.replication_group_members` 在每个 GR 节点上都能看到完整成员视图；Router 只是入口，不持有该视图。探测容器默认选 `jl-mysql-1`，与演示中的「杀主」目标区分——若探测容器本身被停掉，那是「探针坏了」而不是「集群降级」，两种症状必须能区分。
- **为什么口令走容器环境而不是 JVM 参数**：`command` 是固定 argv，SQL 从 stdin 进入，`MYSQL_PWD` 在容器内由 bash 从容器自己的环境展开；JVM 侧只持有「变量名」，不持有口令值。
- **为什么解析失败要抛异常而不是返回空快照**：空快照会清空所有成员序列，看起来像集群整体消失；抛异常走失败路径，序列保留、`probe_up` 归零。
- **为什么重新注册整组**：Micrometer 的 `MultiGauge.register(rows, true)` 会以新行集替换旧行集，缺席的标签组合自然消失；这是本项目里「有界基数 + 无幽灵序列」的统一做法。
- **状态/角色词表有界**：`MemberState`（ONLINE/RECOVERING/OFFLINE/ERROR/UNREACHABLE/UNKNOWN）与 `MemberRole`（PRIMARY/SECONDARY/UNKNOWN）把任何未知值折进 UNKNOWN，标签基数不随服务端输出增长；解析器硬上限 16 行。

## 配置（`chaos.mysql-cluster.*`，默认值来自 `application.yml`）

| 键 | 环境变量 | 默认 | 说明 |
|---|---|---|---|
| `enabled` | `CHAOS_MYSQL_CLUSTER_ENABLED` | `true` | 关闭时不建表、不调度、不 exec |
| `interval-seconds` | `CHAOS_MYSQL_CLUSTER_INTERVAL_SECONDS` | `10` | 轮询周期 |
| `container` | `CHAOS_MYSQL_CLUSTER_CONTAINER` | `jl-mysql-1` | 被 exec 的 lab 容器（受 `lab=java-learn` 白名单约束） |
| `admin-user` | `CHAOS_MYSQL_CLUSTER_ADMIN_USER` | `root` | 只读账号名 |
| `password-env-var` | `CHAOS_MYSQL_CLUSTER_PASSWORD_ENV_VAR` | `MYSQL_ROOT_PASSWORD` | 容器内保存口令的环境变量**名** |
| `query` | —（YAML） | 见上 | 只读成员查询 |
| `command` | —（YAML） | `bash -c 'MYSQL_PWD=… exec mysql --batch --raw --skip-column-names'` | exec argv，SQL 走 stdin |
| `exec-timeout-seconds` | `CHAOS_MYSQL_CLUSTER_EXEC_TIMEOUT_SECONDS` | `6` | 单次 exec 上界，必须小于轮询周期 |
| `expected-member-count` | `CHAOS_MYSQL_CLUSTER_EXPECTED_MEMBER_COUNT` | `3` | 与观测值不一致时按「变化」记 WARN，不刷屏 |
| `thread-name` | `CHAOS_MYSQL_CLUSTER_THREAD_NAME` | `mysql-cluster-probe` | 专用单线程调度器 |
| `shutdown-timeout-seconds` | `CHAOS_MYSQL_CLUSTER_SHUTDOWN_TIMEOUT_SECONDS` | `5` | 停机等待在飞 exec 的宽限期，0 = 立即 |

## D11 面板 PromQL（按**导出名**写）

```promql
# 谁是主
mysql_cluster_primary{job="chaos-controller"}
# 成员数与在线数
mysql_cluster_member_count{job="chaos-controller"}
count(mysql_cluster_member_state{job="chaos-controller",state="ONLINE"})
# 探针健康（0 是「探针坏了」，不是集群结论）
mysql_cluster_probe_up{job="chaos-controller"}
# 近 5 分钟探测失败次数
increase(mysql_cluster_probe_failure_total{job="chaos-controller"}[5m])
```

5 分钟演示脚本：`up.sh --profile db-ha`（或已有 db-ha 栈）→ Grafana D11 看到 3 成员、`mysql-1` 为主、`probe_up=1` → `docker stop jl-mysql-1` → 成员行在下一个轮询周期内变化（`RECOVERING/OFFLINE`、`mysql_cluster_primary` 转移到新主）→ `docker start jl-mysql-1` → 成员回到 ONLINE；期间停掉探测容器本身则应是 `probe_up=0` 且成员序列保留，两者症状不同。

# 存在缺陷项&TODO

- **`_info` 后缀被剥**：设计稿写 `mysql_cluster_primary_info`，但 Micrometer 的 Prometheus 命名约定会把 `_info` 剥掉，实际导出名是 `mysql_cluster_primary`。代码常量仍叫 `METRIC_PRIMARY_INFO`，面板与告警必须用导出名；若要严格对齐设计，需改常量名并同步设计文档。
- **未做真机验证**：本步没跑 Docker，查询语句借用了 `db-cluster-status.sh` 的已验证形式，但「在 chaos-controller 容器内经 docker-java exec、用 `MYSQL_ROOT_PASSWORD` 展开口令」这条完整链路尚未在真栈上跑过；真机验收归 #51c。
- **D11 面板未建**：本步只交付指标与 PromQL 示例。

> 后续：D11 面板已由 step-38 落地（`ha-d11.json`），见 `doc/process/Stage1/step-38-ha-acceptance.md`。
- **探测目标选择**：默认 `jl-mysql-1` 恰是演示里常被杀的节点；演示前应确认「杀主演练」的目标与探测容器不同，或接受短暂 `probe_up=0` 并把它当作教学点（探针视角 vs 集群视角）。
- **基数**：成员上限 16；`member` 标签取自 `MEMBER_HOST`（compose 用服务名，如 `mysql-1`），不含容器前缀。

# 待定事项和注意事项

- 若后续把 GR 扩到 4+ 节点，需同步 `expected-member-count`；否则只是多记一条 WARN，不影响指标。
- 失败路径保留旧序列是刻意设计，不要改成「失败即清空」。
- 该探针与 D11 面板的切换标注（Grafana annotation）尚未联动；切换演示时先看 `mysql_cluster_primary` 的跳变时刻。
