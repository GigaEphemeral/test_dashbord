# 步骤交接：Stage2 B4-04 实例配置页与 JDK8/21 对照

> 任务/步骤：Stage2 **B4-04**：实例与 JVM 页配置组（校验/仅保存/应用并重启/回滚/稳定期）、目标 vs 生效、GC 日志、JDK8 LIMITED
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `edaa454`（本步改动本地待提交）
> 关联任务：计划 B4-04；方案 §9.2/§10.7/§16；B4-01/02/03 交接
> 测试：reactor **TBD**；console-smoke **126/0**（当前探针 0 → BLOCKED 分支；探针正常时应为 **145**）；verify/smoke 见收口
> 运行状态：app-1 基线 APPLIED（512m、池 4/8/50、GC 日志关闭）；jdk8 外壳 APPLIED（512m、G1、GC 日志开启，池回读 SKIPPED）；`gc-app-1.log`/`gc-app-jdk8.log.0.current` 在 snapshots
> 口径指针：2026-10-29 本文第 4/5 节是当时快照；缺口口径见 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- 实例页新增“配置组”标签：app-1/2/3 + `seckill-app-jdk8`（无容器时灰显“容器未运行（dual-jdk profile）”）；每实例展示状态徽标、目标 argv vs 容器实际 `JAVA_TOOL_OPTIONS`、镜像 tag/id、预算、warnings、稳定期、GC 日志。
- 已接后端：GET/PUT/validate/apply/rollback/effective/GC-log；apply/rollback 有二次确认与 10 项检查结果展示，按钮在重建期间锁定。
- 真机 UI：app-1 validate → 应用（APPLIED，10 检查）→ 回滚（ROLLED_BACK）；jdk8 外壳应用（APPLIED，pool SKIPPED、Prometheus job `seckill-app-jdk8`、GC 轮转日志可读）。
- 未做：jdk-matrix 自动化模板（当前靠人工配置两组）、stable 期内禁止开跑的硬门禁（B5）。

# 完成功能项

- 前端：`InstanceConfigGroup.vue`（页签与实例列表）、`InstanceConfigEditor.vue`（分组表单、状态/目标/生效/预算/警告/校验/保存/应用/回滚/确认弹窗/检查结果）、`InstanceGcLogViewer.vue`（行数输入、OK/DISABLED/NO_DATA 语义）；`InstancesPanel/View` 接入“配置组”标签。
- `api.js`：`fetch/validate/save/apply/rollback/effective/gc-log`，apply/rollback 300s AbortController 预算。
- 状态徽标：DRAFT 待应用 / PENDING_RESTART 待重启 / APPLYING 应用中 / APPLIED 已生效 / ROLLED_BACK 已回滚 / FAILED 应用失败 / DEGRADED 需人工 / NOT_CONFIGURED 未配置；`stableUntil` 显示稳定期。
- 校验/应用：事后刷新 config+effective 再发布结果，徽标与结果不再错位；Metaspace <192 的实测 OOM 警告；JDK8 独立外壳提示与 LIMITED-OBSERVABILITY/READBACK_UNAVAILABLE 展示。
- controller 小补：`InstanceConfigView` 增加 `stableUntil/lastApplyAt/appliedImage*/previousImage*`；`lab.instance.names` 增加 `seckill-app-jdk8`；Prometheus job 对 jdk8 使用 `seckill-app-jdk8`；GC 日志回读识别 JDK8 轮转文件 `<path>.<n>.current`。
- 冒烟：新增 7 条配置组断言（面板/状态/目标/生效/校验/GC 日志状态/无 apply），现有断言保持；compare 断言改为等待勾选框渲染。

# 设计逻辑

- **UI 不伪造生效**：目标 argv 来自存储配置，生效 argv 来自容器 env；`readback=UNAVAILABLE/READBACK_UNAVAILABLE` 显示“未验证”，不显示成功。
- **重建是显式危险动作**：确认弹窗写明容器重建、40–120s 抖动与门禁；运行中禁用；状态与检查结果来自后端。
- **JDK8 是独立外壳**：app-1/2/3 拒绝 jdk8 镜像；jdk8 实例走同一重建流程，但池回读 SKIPPED、指标走 `seckill-app-jdk8` job、GC 日志用轮转命名。
- **稳定期可见**：`stable_until` 在列表显示；B5 再把它变成开跑门禁。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 探针 0 | `jl-mysql-3` 掉组（errant GTID），`mysql_cluster_probe_up=0`，冒烟走 BLOCKED 分支 126；修复需重建 mysql-3 数据，超出 B4 | 环境/B5 |
| jdk-matrix 自动化 | 只提供逐实例配置与重启；未做“两组自动跑+对比报告”模板 | B5/B4 收尾可选 |
| 稳定期硬门禁 | 只展示 `stableUntil`，预检查未拒绝正式对照 | B5 |
| apply 同步 | UI 等待最长 300s，无 202/operationId；浏览器断开后结果仍可从 config 读取 | 记录 |
| 全量回归 | 本步改 UI + controller 视图/job，批次收口重跑 reactor/verify/smoke | B4 收口 |

# 待定事项和注意事项

1. 下一步：B4 收口（全量闸门、独立审核、closure），然后 B5。
2. 现场：jdk8 外壳在跑（dual-jdk）；不需要时 `docker stop jl-seckill-app-jdk8`。
3. 冒烟计数：探针恢复后应为 145；当前 126 是 BLOCKED 分支，不是覆盖减少。

# 附录

## 真机证据

- UI app-1：status ROLLED_BACK/APPLIED；target/effective 均 `-Xms256m -Xmx512m …`；validate“预算 744/768 MB”；GC 日志 `DISABLED 未启用`；apply 弹窗 → APPLIED，10 项检查；rollback → ROLLED_BACK。
- jdk8：PUT 配置（jdk8/512m/G1/GC 日志）→ APPLIED，10 项检查（pool SKIPPED、Prometheus job seckill-app-jdk8 up=1、image jdk8）；`GET .../logs/gc` → `OK`，路径 `gc-app-jdk8.log.0.current`，内容为 `GC pause (G1 Evacuation Pause)`。
- app-2/3 未变。

## 关键文件

- 前端：`InstancesPanel/InstancesView.vue`、`InstanceConfigGroup/InstanceConfigEditor/InstanceGcLogViewer.vue`、`api.js`
- 冒烟：`console-smoke.{mjs,sh}`
- controller：`InstanceViews/InstanceConfigService`（view 字段）、`InstanceConfigProperties`/`application.yml`（jdk8 job）、`InstanceLogsService`（轮转回退）
- 配置：`compose.base.yml`、`.env.example`（jdk8 instance/job）
