# 当前进展

B5-05c：按用户口径缩减 Redis 多节点规模为 **2 数据节点 + 2 Sentinel**，MySQL 保持 **3 节点**
（InnoDB Cluster 需要多数派，缩到 2 会失去自动切主）。本次只改 compose/观测/脚本/拓扑口径，
不删除 MySQL 节点；当前正在运行的 HA 栈仍是旧 3+3，需要下次 `--profile ha` 重建后生效。

# 完成功能项

| 项 | 落地 |
|---|---|
| Redis 数据节点 | `redis-1`（初始主）+ `redis-2`（副本）；删除 `redis-3` 与 `redis-3-exporter` |
| Sentinel | `sentinel-1` + `sentinel-2`，`REDIS_HA_QUORUM=2`；两个都要在线才能选主 |
| App Sentinel 地址 | `REDIS_SENTINEL_NODES` 默认两项；compose 默认同步 |
| Prometheus | `redis-exporter-ha` 只抓 redis-1/2 两个 target |
| 拓扑 | `lab.topology.redis-nodes=2`（Redis+Sentinel），`mysql-nodes=3`（MySQL 不变）；镜像/导出配置同步 |
| 脚本 | `verify.sh` 期望 1 副本/2 Sentinel；`sentinel-status`、`ha-acceptance`、`reset-lab`、`up.sh` 默认名单与提示改为 2+2 |
| 资源 | HA 层少 1 Redis（128m）+1 Sentinel（64m）+1 exporter（48m），约省 240MB 内存上限 |

# 设计逻辑

- 2 个 Sentinel 的 quorum=2：**两个都健康时**可自动把副本提升为主；任一 Sentinel 故障时无法选主。
  这是“省资源”换来的明确降级，验收/演示时必须两台 Sentinel 都在线。
- MySQL 3 节点保留：InnoDB Group Replication 的多数派是 2/3；2 节点虽可读写，但任一节点故障后
  无法自动选举新主，A10/HA 验收会失效，因此不缩。
- 应用仍按 `REDIS_MODE` 选择 standalone 或 sentinel；本次只改 HA 拓扑，dev 单机 Redis 不变。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 运行中栈未重建 | 当前 `jl-redis-3`/`jl-sentinel-3` 仍在跑；下次 HA profile up 才会移除 | 部署动作 |
| Sentinel 容错 | 2 Sentinel 时任一哨兵故障不能选主 | 已接受 |
| 仪表盘 | 个别历史面板仍按 redis-1/2/3 写死变量或图例，缺 redis-3 只显示无数据，不影响 2 节点 | UI 清理 |
| demo | 需要重新录制拓扑快照（Redis/Sentinel 从 3+3 变 2+2） | 收尾 |

# 待定事项和注意事项

1. 应用 2+2 需要在有 HA 栈的机器上执行 HA profile 重建；重建前确认 `REDIS_HA_QUORUM=2` 且两台 Sentinel 都能启动。
2. MySQL 节点仍为 `mysql-1/2/3`，Router 6446/6447 不变；`db-ha` profile 不受本步影响。
3. 当前 dev 应用仍 `MYSQL_MODE=standalone`、`REDIS_HOST=redis`，HA 节点只做对照，不影响日常压测。
