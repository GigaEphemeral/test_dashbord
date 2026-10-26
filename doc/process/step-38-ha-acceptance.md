> 步骤交接 · M3 高可用验收 runbook（step 38）
> 日期：2026-10-29
> 分支 / HEAD：worktree `agent-general-purpose-b6751c9e`，基线 `699942c`（M4-2b 雪崩防护合并点）
> 关联任务：设计 §15 场景 1/8/9 的 M3 验收；D11 数据库高可用面板（设计 §7.4）
> 验证：`python -c "json.load(...)"` 与 `node -e "require(...)"` 均通过；**Grafana 未启动、面板未渲染**，PromQL 未对活栈执行；未运行任何 Docker 命令，未改 Java/compose/脚本/provisioning。

# 当前进展

本步交付两件东西：`code/lab/docker/grafana/dashboards/ha-d11.json`（D11）与本验收 runbook。D11 已按设计 §7.4 覆盖成员状态、主节点、成员数与探针健康、节点可达性、读写分流（间接）、Redis 主从、切换/回滚动作与 Router 说明。

M3 的三条验收项里，**场景 8（Redis Sentinel 切换）已有真机证据**，**场景 1（应用故障转移）有场景演练证据但未跑统一 `verify.sh`**，**场景 9（MySQL 切换）没有任何活体切换记录**：仓库中不存在 `doc/process/step-31-mysql-router-live-drill.md`，step-25b 明确把"故障转移与重加入"列为未验证。因此 M3 尚未满足"场景 1、8、9 通过"的完成标志，第 9 项按下节"待执行"命令补做。

## (a) Redis Sentinel 切换的既有证据

来源：`doc/process/step-24d-sentinel-live-drill.md`（2026-10-26 实跑）。时间线（UTC）：

| 时刻 | 事件 | 证据 |
|---|---|---|
| 19:05:04 | chaos API 对 `jl-redis-1` 执行 kill | `docker events`：`kill → stop → die` |
| 19:05:09.993 | Sentinel `+sdown master mymaster 172.28.190.11 6379` | sentinel 日志 |
| 19:05:11.117 | `+odown ... #quorum 3/2` | sentinel 日志 |
| 19:05:11.630 | `+switch-master ... 172.28.190.11 6379 172.28.190.12 6379` | **停主到晋升约 7.6s** |
| 19:05:15.8 | 三实例 Lettuce `Reconnected to 172.28.190.12`，app 无重启 | app 日志 / `StartedAt` 逐字节相同 |
| 19:05:49 | TTL 45s 到点，`jl-redis-1` 自动 `start` | `docker events`，未手工执行 |
| 19:05:53 | `-sdown slave 172.28.190.11:6379 ...`，旧主以副本重加入 | sentinel 日志；`master_link_status=up` |

- Nginx 商品读 2s 轮询 52 次：**50×200 / 2×000**（两次 3s 客户端超时，均跨越切换窗口），**停主后首个 200 于 +9.1s**（≤15s）。
- k6 `ha-failover`：3/3 动作 accepted、`http_req_failed 0.00%`、checks 720/720、product p95=29.14ms / p99=41.15ms，teardown `stop-all reverted=0 failed=0`。
- 最终拓扑：master 漂移到 `jl-redis-2`，`jl-redis-1/3` 为副本且 link up；三 Sentinel 的 `master0` 一致（`slaves=2,sentinels=3`）。

口径提醒：52 次探针里的 2 次 000 是客户端 3s 超时，不是 5xx；验收矩阵的"错误率 <1%"以 k6 负载口径（0.00%）判定，两者不要混用。

# 完成功能项

## (b) MySQL 集群 + Router 的既有证据与缺口

已有证据（`doc/process/step-25b-mysql-live-bootstrap.md`，2026-10-27 实机）：

- 3 节点 Group Replication 单主 + `jl-mysql-router`（Oracle 8.4.10，6446 read/write、6447 read-only）+ 3 个 exporter；`db-cluster-status.sh` 末行 `RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY.`。
- 6446 跟随主节点，6447 在 SECONDARY 间轮询；8.0 Router 混配 8.4 服务器会把全员判 RO 并静默关闭 6446，已换 8.4 镜像修复。
- 实测内存 1911.0MiB（≈1.87GB），声明稳态上限 2128MiB（≈2.1GB）。
- 探针（step 30，已合并）：chaos-controller 每 10s 在 `jl-mysql-1` 内只读查询 `replication_group_members`，导出 `mysql_cluster_member_state{member,role,state}`、`mysql_cluster_primary{member}`（`_info` 被 Prometheus 剥掉）、`mysql_cluster_member_count`、`mysql_cluster_probe_up`、`mysql_cluster_probe_success_total`、`mysql_cluster_probe_failure_total`。

缺口：

1. **无 `step-31`**：Router 切主、被踢节点 clone 重加入、应用经 Router 的错误窗口都没有活体验证；step-25b 第 54 行明确标注未验证。
2. **Prometheus 抓取未接线**：`code/lab/docker/prometheus/mysql-ha-scrape.yml` 是惰性文件，`prometheus.yml` 没有 `scrape_config_files`，`mysqld-exporter-ha` 这个 job 目前不存在。D11 的 `mysql_up` 面板在接线前恒为空。
3. **应用 `MYSQL_MODE=router` 只有单测**（step-29），未在活栈上确认写池→6446、读池→6447。
4. **datastore 白名单默认拒绝 MySQL**：`CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` 默认 `^jl-redis-[1-3]$`，对 `jl-mysql-*` 的 kill 会 403。放宽是**安全边界变更**，需显式决定。

### 待执行：MySQL 主节点切换演练（补齐场景 9）

前置：db-ha 层已起、集群 `RESULT: ok`。以下命令**本步未执行**。

```bash
# 1) 起层并确认拓扑与路由
bash code/lab/scripts/db-ha-up.sh
bash code/lab/scripts/db-cluster-status.sh          # 期望 RESULT: ok；记录当前主节点

# 2) 接线 Prometheus（改 prometheus.yml 属后续任务，本步不改）
#    在 scrape_configs 同级加：
#      scrape_config_files:
#        - prometheus/mysql-ha-scrape.yml
docker exec jl-prometheus promtool check config /etc/prometheus/prometheus.yml
docker exec jl-prometheus wget -qO- --post-data='' http://127.0.0.1:9090/-/reload

# 3) 应用切 Router（.env，随后只重建 app-1/2/3）
#      MYSQL_MODE=router
#      DB_WRITE_URL=jdbc:mysql://mysql-router:6446/seckill
#      DB_READ_URL=jdbc:mysql://mysql-router:6447/seckill
docker logs jl-app-1 2>&1 | grep "MySQL access mode"   # 期望 router + 6446/6447

# 4) 放宽 chaos 数据面白名单（安全边界变更，演示后改回）
#      CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^(jl-redis-[1-3]|jl-mysql-[1-3])$'
#    只重建 chaos-controller，使 env 生效

# 5) 把探针挪到存活节点（默认探的是 jl-mysql-1；若它就是要杀的主节点，
#    杀它等于杀探针，D11 只会看到 probe_up=0 + 旧拓扑）
#      CHAOS_MYSQL_CLUSTER_CONTAINER=jl-mysql-2      # 选一个不杀的节点
#    重建 chaos-controller 后确认 mysql_cluster_probe_up=1

# 6) 杀主（把 jl-mysql-1 换成第 1 步报告的主节点容器名）
curl -sS -X POST \
  -H "X-Lab-Token: ${LAB_TOKEN:-lab-token}" -H 'Content-Type: application/json' \
  -d '{"action":"kill","ttlSeconds":120}' \
  http://127.0.0.1:8080/lab-api/chaos/instances/jl-mysql-1/actions

# 7) 并行取证：HTTP 错误窗口 + 集群/路由状态
for i in $(seq 1 60); do
  curl -s -o /dev/null -w '%{http_code} %{time_total}\n' --max-time 3 \
    "http://127.0.0.1:8080/api/products/$((i % 20 + 1))"
  sleep 1
done
bash code/lab/scripts/db-cluster-status.sh
```

判定：D11 上 `mysql_cluster_primary` 在 1–2 个探测周期（10–20s）内跳到新主；被踢成员出现 `RECOVERING`/`OFFLINE`；`probe_up` 保持 1；6446 跟随新主、6447 仍为只读；HTTP 首个 200 距 kill ≤15s 且 5xx 错误率 <1%。TTL 到点后 `jl-mysql-1` 自动 start，成员经 `RECOVERING`（clone）回到 `ONLINE`。演示后把白名单、`MYSQL_MODE`、探针容器改回默认值。

# 设计逻辑

## (c) D11 面板与 PromQL

D11 共 20 个面板项：15 个数据/说明面板 + 5 个 row。`uid=ha-d11`、`schemaVersion=39`、数据源 `{"type":"prometheus","uid":"prometheus"}`、`refresh=10s`、默认 30m 窗口，风格对齐 D4/D10。模板变量 `member`（来自 `mysql_cluster_member_state`）、`instance`（来自 HA exporter）、`action`（来自 `chaos_actions_total`），均为多选 + All（`.*`）。

| # | 面板 | 类型 | PromQL / 查询（`job` 省略处见行内） | 预期 |
|---|---|---|---|---|
| 1 | 成员状态时间轴 | state-timeline | `max by (member)(mysql_cluster_member_state{job="chaos-controller",member=~"$member",state="ONLINE"}*1) or max by (member)(mysql_cluster_member_state{job="chaos-controller",member=~"$member",state="RECOVERING"}*2) or max by (member)(mysql_cluster_member_state{job="chaos-controller",member=~"$member",state="OFFLINE"}*3) or max by (member)(mysql_cluster_member_state{job="chaos-controller",member=~"$member",state="ERROR"}*4) or max by (member)(mysql_cluster_member_state{job="chaos-controller",member=~"$member",state="UNREACHABLE"}*5) or max by (member)(mysql_cluster_member_state{job="chaos-controller",member=~"$member",state="UNKNOWN"}*6)` | 每成员一行，1–6 映射 ONLINE/RECOVERING/OFFLINE/ERROR/UNREACHABLE/UNKNOWN；空白=不在最近成功探测的视图 |
| 2 | 成员明细 | table | `mysql_cluster_member_state{job="chaos-controller",member=~"$member"}`（instant, format=table） | 直接展示 member/role/state 标签原值 |
| 3 | 主节点（每成员一行） | stat | `max by (member)(mysql_cluster_primary{job="chaos-controller",member=~"$member"}) or max by (member)(mysql_cluster_member_state{job="chaos-controller",member=~"$member"}*0)` | 1=PRIMARY 绿，0=SECONDARY 蓝；选举窗口可能全 0 |
| 4 | 成员数 | stat | `mysql_cluster_member_count{job="chaos-controller"}` | 3=绿（期望值来自 `expected-member-count`） |
| 5 | 探针健康 | stat | `mysql_cluster_probe_up{job="chaos-controller"}` | 1=正常，0=探测失败 |
| 6 | 探针失败/成功 | timeseries | `increase(mysql_cluster_probe_failure_total{job="chaos-controller"}[5m])`、`increase(mysql_cluster_probe_success_total{job="chaos-controller"}[5m])` | 正常时成功≈30/5m、失败 0 |
| 7 | 节点可达性 | stat | `mysql_up{job="mysqld-exporter-ha",instance=~"$instance"}` | 每 member 一个 UP/DOWN；**接线前为空** |
| 8 | 读写池连接 | timeseries | `sum by (pool)(hikaricp_connections_active{job=~"seckill-app.*",pool=~"write-pool\|read-pool"})`、同构 `hikaricp_connections_pending` | 间接观察路由；standalone 下两池都连 dev mysql |
| 9 | Redis 可达 | stat | `redis_up{job="redis-exporter"}` | 仅代表 dev 单机 `redis:6379` |
| 10 | Redis 连接与从节点 | timeseries | `redis_connected_clients{job="redis-exporter"}`、`redis_connected_slaves{job="redis-exporter"}` | 单机从节点通常 0 |
| 11 | 故障动作速率 | timeseries | `sum by (action,target,result)(rate(chaos_actions_total{job="chaos-controller",action=~"$action"}[5m]))` | 只含经 chaos API 的动作 |
| 12 | 自动回滚速率 | timeseries | `sum by (action,result)(rate(chaos_reverts_total{job="chaos-controller",action=~"$action"}[5m]))` | TTL/watchdog/stop-all 结果 |
| 13 | 活跃故障动作 | stat | `chaos_active_actions{job="chaos-controller"}` | 未回滚的 kill/freeze 数；PAUSED 不减少 |
| 14 | 使用说明 | text | — | 口径与限制 |
| 15 | Router 观察手册 | text | — | Router 无指标，改用 `db-cluster-status.sh` + 探针 |

关键取舍：

- **state 是标签不是数值**：`mysql_cluster_member_state` 恒为 1，`state` 在标签里。状态时间轴用 `or` 把六种 state 编码成 1–6，既保留真实词表，又让同一 member 的行能随状态变色；表格面板同时保留原始字符串，便于核对。
- **主节点补齐 0 值**：单主组里 `mysql_cluster_primary` 只对主库产生序列，直接画只有一行。用 `member_state * 0` 补出其余成员，实现"每个成员一行"，不发明任何新指标。
- **`_info` 命名陷阱**：代码常量是 `mysql_cluster_primary_info`，Prometheus 导出名是 `mysql_cluster_primary`；面板按导出名写（step-30 已记录）。
- **Router 不画假曲线**：`compose.mysql.yml` 没有 Router metrics 端点，仓库也不存在 `mysql_router_*` 指标。D11 明确用文本面板 + `db-cluster-status.sh` 观察，而不是编造时间序列。
- **Redis 只画抓得到的**：`prometheus.yml` 只有 dev 单机 `redis-exporter`；`jl-redis-*-exporter` 与 Sentinel 都没被抓取，所以面板标注"dev 单机"，不暗示 Sentinel 拓扑。
- **切换标注**：chaos-controller 默认向 Grafana `/api/annotations` 写全局注解，强制标签 `chaos`、`java-learn`、target、action；D11 用内置 Grafana annotation 查询按 `chaos` 标签显示。手工 `docker stop/start` 不会产生标注，因为不经过 chaos API。

# 存在缺陷项&TODO

## (d) 验收清单（设计 §15 → 证据）

| §15 # | 场景 | 通过标准 | 现有证据 | 结论 |
|---|---|---|---|---|
| 1 | 负载均衡 + 故障转移 | 错误率 <1%，恢复 <15s | step-22a `quick-failover`：app-3 kill + TTL，Nginx 产品读全程 200，08:08:22 起 starting、08:09:13 healthy；step-24d 52 次轮询 50×200 | 通过（流量秒级切换；容器 healthy 约 66s，语义是"服务恢复"不是"容器健康"）；未跑 `verify.sh` |
| 8 | Redis 切换 | 错误窗口 <15s，错误率 <1% | step-24d：晋升 7.6s、首个 200 +9.1s、k6 0.00% failed、720/720 checks | 通过（按 k6 负载口径；探针口径 2/52 客户端超时） |
| 9 | MySQL 切换 | 错误窗口 <15s，错误率 <1% | **无**：step-31 不存在，step-25b 明确未验证切主/重加入 | **待执行**（命令见 (b)） |
| — | 基础设施自检 | 容器健康、集群成员、数据源可用 | `db-cluster-status.sh` 曾 `RESULT: ok`；`verify.sh` 的 HA 段未跑 | 部分通过 |

M3 完成标志是"场景 1、8、9 通过"，当前 1/8 通过、9 待执行，**M3 不能标记完成**。

## 缺陷与限制

1. **`mysql-ha-scrape.yml` 未接线**：文件头写明是惰性文件，需在主 `prometheus.yml` 加 `scrape_config_files` 两行；本任务边界禁止改 provisioning/配置，故 D11 的 `mysql_up` 面板与 `instance` 变量在接线前为空。
2. **MySQL 切主未演练**：没有 step-31，Router 8.4 切主、6446 跟随、被踢节点 clone 重加入均无实测证据。
3. **应用 Router 模式未活体验证**：step-29 只有上下文/端点单测；写池 6446、读池 6447 的行为未在真栈确认。
4. **探针容器与 kill 目标冲突**：探针默认在 `jl-mysql-1`，若杀主恰好是它，`probe_up=0` 且拓扑冻结在最后一次成功值。演练前必须把 `CHAOS_MYSQL_CLUSTER_CONTAINER` 指到存活节点，否则 D11 无法区分"探针坏了"与"集群降级"。
5. **默认白名单拒绝 MySQL**：`^jl-redis-[1-3]$` 不含 `jl-mysql-*`，不显式放宽就无法通过 chaos API 演练切主；放宽属于安全边界变更，需在验收记录里单独声明。
6. **Sentinel 与 HA Redis exporter 未抓取**：D11 的 Redis 面板只反映 dev 单机；Sentinel 的 `+switch-master` 无法在 Prometheus 时间轴上显示，只能看 sentinel 日志与 `sentinel-status.sh`。
7. **Router 单点**：只有一个 `jl-mysql-router`（6446/6447），无第二个 Router 实例；Router 挂掉时探针仍可能 3/3 ONLINE，两类故障必须分开判断。
8. **标注只覆盖 chaos API**：annotation 是全局的、无 dashboardId，所有查询了 `chaos` 标签的面板都会显示；手工 Docker 操作不会标注。
9. **未渲染**：Grafana 未启动，面板布局、value mapping 颜色、state-timeline 行高、table 列宽、变量下拉均未经视觉确认；PromQL 未对活栈执行。

# 待定事项和注意事项

## (e) 待定

- **Prometheus 接线**（主会话）：`prometheus.yml` 加两行 → `promtool check config` → `/-/reload` → targets 页确认 `mysqld-exporter-ha` 三 member UP。接线后再复核 D11 的 `mysql_up` 与 `instance` 变量。
- **MySQL 切主演练脚本化**：目前只能手工 curl + 轮询。若要把场景 9 固化成 `code/lab/scenarios/*.json`，需要新增 `mysql-failover` 场景（Java/脚本改动，超出本任务）。
- **Router 指标**：若后续需要真正的 Router 面板，应在 Router 侧启用 metrics 端点（若镜像支持）或由 exporter 采集，而不是在 Grafana 里伪造；同时需考虑第二个 Router 实例消除单点。
- **Sentinel 抓取**：若要画 Sentinel 拓扑，需要为 `jl-redis-*-exporter` 与 sentinel 新增 scrape job，并解决 master 漂移后 target 与角色标签的对应关系（端口固定、角色在变）。
- **`verify.sh` 的 HA 断言**：step-24d 明确 `verify.sh` / `smoke.sh` / `console-smoke.sh` 未跑（#61 所有），M3 收口前需统一补跑。
- **探针容器选择**：默认 `jl-mysql-1` 与演示 kill 目标重叠。建议把"探针节点 ≠ 演示杀主节点"写进场景前置检查，或在演示时动态改 `CHAOS_MYSQL_CLUSTER_CONTAINER`。
- **标注清理**：chaos annotation 会长期留在 Grafana 库中；反复演练后时间轴可能拥挤，必要时按 `chaos` 标签清理。

## 注意事项

- `member` 标签是 `mysql-1/2/3`（`MEMBER_HOST`），容器名是 `jl-mysql-1/2/3`；D11 的成员变量与 chaos 的 target 不是同一套命名，排查时先对齐。
- `mysql_cluster_probe_up=0` 时成员/主节点面板仍显示上次成功拓扑，这是刻意设计（区分探针故障与集群故障），不要当成实时状态。
- 成员从 `replication_group_members` 消失时 MultiGauge 会清除该行，状态时间轴出现空白；空白不等于 UNKNOWN。
- 所有 Redis 结论只适用于 dev 单机；Redis Sentinel 的验收证据仍以 step-24d 的日志/脚本输出为准。
- D11 的 annotation 依赖 chaos-controller 的 `CHAOS_ANNOTATION_ENABLED=true` 与 Grafana 管理员凭据；两者缺失时动作仍执行，只是没有标注。
