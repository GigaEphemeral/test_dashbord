# 步骤交接：Stage2 B5-00 前置门禁（集群修复 + 稳定期 BLOCK）

> 任务/步骤：B5 前置：`jl-mysql-3` 复组、稳定期硬门禁、B5 口径确认
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `d973c1f`（本步改动本地待提交）
> 关联：B5 计划（`stage2-开发测试与验收计划.md` §6）、B4 收口
> 测试：`RunPrecheckServiceTest` 定向通过；console-smoke **145/0**；集群 3/3 ONLINE、probe_up=1
> 运行状态：mysql-1/2/3 ONLINE（mysql-3 已 clone 重建）；Router 6446→mysql-1、6447→mysql-3；app-1 基线 APPLIED

# 当前进展

- B5-00a：`jl-mysql-3` 因 errant GTID OFFLINE；按授权走完整停机恢复：
  停三节点 → `dba.rebootClusterFromCompleteOutage('labCluster',{force:true})`（mysql-1 PRIMARY + mysql-2 ONLINE）→
  `removeInstance('mysql-3:3306',{force:true})` → `addInstance(..., {localAddress:'mysql-3:3306', recoveryMethod:'clone'})`
  （MYSQL 通信栈要求 localAddress 端口 = 3306）→ 3/3 ONLINE；`mysql_cluster_probe_up=1`，Router ok。
- B5-00b：预检查新增 `instances.stable`：`lab_instance_config.stable_until > now` 的实例 **BLOCK** 正式 run；
  真机 apply 后立即预检查 BLOCKED，65s 后转 WARN。
- 口径确认：mysql-3 重建允许；稳定期 **BLOCK**；jdk-matrix 采用 **A**（d7 两侧各 1 次、仅模板内可选）；B5-04 按提议范围。
- 冒烟阈值恢复 **145**（探针正常分支）。

# 完成功能项

- `InstanceConfigRepository.findStableAfter(now)`；`RunPrecheckService.KEY_STABLE` + `stableCheck`（BLOCK，含实例/状态/stableUntil 明细，查询失败 fail-closed）。
- `PrecheckConfig` 注入 `InstanceConfigRepository`；`RunPrecheckServiceTest` 增加 BLOCK/放行两条用例。
- `console-smoke.sh` 的 `SMOKE_MIN_PASS_COUNT` 145。
- 集群修复为运维操作，未改 compose/脚本。

# 设计逻辑

- 稳定期属于“配置刚生效、L1/连接池/计数器刚重建”的窗口，正式对照必须等它结束；探索性/草稿不受影响（只拦 start）。
- 稳定期门禁放在预检查而不是 start 服务里，控制台能直接展示阻断原因与剩余时间。
- 集群修复选择 InnoDB Cluster 官方完整停机恢复 + clone，而不是手工拼 GTID；只重建 mysql-3 数据。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 全量 reactor | 本步只跑 precheck 定向；B5 收口重跑 | B5-05 |
| 集群修复脚本化 | 本次是手工 mysqlsh 操作；未写 `db-ha-rejoin` 脚本（需评估是否值得） | 记录 |
| 稳定期剩余时间 | 预检查只给 stableUntil，未给剩余秒数 | 记录 |
| mysql-3 历史 | clone 后 mysql-3 数据来自 mysql-1，集群 demo 数据一致 | 记录 |

# 待定事项和注意事项

1. 下一步 Wave 1：B5-04a 字典分级 ∥ B5-01a 拓扑契约；随后 B5-01b/c 与 B5-04b。
2. 重建 upstream 后仍必须 `nginx -s reload`；mysql 集群重启不影响 standalone 业务库（app 当前不指向 Router）。
3. 任何 apply 后的 60s 内预检查会 BLOCK，属预期；演示前留出稳定期。

# 附录

## 真机证据

- `db-cluster-status.sh`：3/3 answering，exactly one PRIMARY；Router 6446→mysql-1、6447→mysql-3。
- `mysql_cluster_probe_up=1`；console-smoke **145 PASS / 0 FAIL**。
- apply app-1 → `stableUntil=2026-10-29T10:03:23Z`；预检查立即 `verdict=BLOCKED`、`instances.stable` 列出 app-1；65s 后 `verdict=WARN`（仅 toxiproxy）。
