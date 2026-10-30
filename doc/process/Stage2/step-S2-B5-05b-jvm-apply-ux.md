# 当前进展

B5-05b：修复用户反馈的 JVM 参数“保存了但监控显示旧值”和向导第 3 步“读取实例配置…”导致页面上下抖动。
根因已复现：**保存只写台账草稿，不会重启 JVM**；app-2 当时台账为 `DRAFT rev2 / MaxGCPauseMillis=210`，
容器实际仍是 200。应用后实际值变为 210，说明受控重建链路本身正常，问题在“保存/生效”的交互表达。

# 完成功能项

| 项 | 落地 |
|---|---|
| 布局抖动 | 配置组加载提示改为固定高度状态槽（`min-height: 18px`），显示/消失不再改变页面高度 |
| 保存 vs 生效 | 编辑器在 `DRAFT / PENDING_RESTART` 时显示醒目提示：“已保存 revN，但尚未生效：保存不会重启 JVM”；「仅保存」改名「仅保存草稿」 |
| 一键保存并应用 | 新增「保存并应用」：保存成功后直接打开应用确认；应用仍走原有受控重建/健康门禁/确认弹窗 |
| 监控页目标 vs 实际 | `JVM 参数对照` 新增实际 `Xms/Xmx`、实际 GC、实际停顿列（来自 `jcmd VM.flags`；JDK8 用 `JAVA_TOOL_OPTIONS` 解析），并增加未生效实例横幅 |
| 时间轴图例 | JVM 参数提示在非 APPLIED 时追加 `未生效`，避免把草稿目标当成实际运行参数 |
| 已修正环境 | app-2 的 210ms 配置已应用为 `APPLIED rev3`，容器实际 `MaxGCPauseMillis=210` |

# 设计逻辑

- `PUT /lab/instances/{id}/config` 只保存草稿；`POST /apply` 才复制容器、设置 `JAVA_TOOL_OPTIONS`、重启并走 10 项健康门禁。
  不应让保存隐式重启，否则一次误点会重建实例并中断实验。
- 监控页必须区分**目标配置**与**容器实际回读**：目标列来自实例台账，实际列来自 `jcmd VM.flags`；
  `目标 vs 回读` 在 DRAFT/PENDING_RESTART 时显示“未生效（待应用）”，不再显示“与目标一致”。
- JDK8 没有 `jcmd VM.flags` 回读（LIMITED-OBSERVABILITY），实际值从容器 `JAVA_TOOL_OPTIONS` 解析，并明确标注。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| MySQL/Redis 节点数 | 已确认：MySQL 保持 3 节点；Redis 缩为 2 数据 + 2 Sentinel，见 `step-S2-B5-05c-redis-two-nodes.md` | 已定 |
| JDK8 实际值来源 | JDK8 只能从 `JAVA_TOOL_OPTIONS` 解析，非运行时 flags 回读 | 记录 |
| 自动应用 | 当前仍是“保存并应用”显式确认，不自动重启 | 设计如此 |

# 待定事项和注意事项

1. 应用参数后实例进入 60s 稳定期，正式 run 在稳定期内会被预检查 BLOCKED，这是设计行为。
2. `JvmParamsPanel` 每 5s 刷新；刚应用完最多 5s 才看到实际值更新。
3. app-2 当前：`APPLIED rev3`，G1，Xms256/Xmx512，MaxGCPauseMillis=210；如实测需要 200 可再改回并应用。
