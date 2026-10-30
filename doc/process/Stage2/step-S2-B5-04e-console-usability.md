# 步骤交接：控制台可用性修复（jdk8 / 堆转储 / 全宽 / 三列对比 / 参数对照 / 线程池图）

> 任务：用户反馈 1–6（第 7、8 项后续讨论）
> 日期：2026-10-30
> 分支 / HEAD：`feature/stage2-workbench` / `7f30d15`（本步改动本地待提交）
> 测试：dashboard build 通过；console-smoke **162/0**；claims **85/85**

# 当前进展

| # | 问题 | 根因 | 修复 |
|---|---|---|---|
| 1 | 无法配置 jdk8 | `InstanceConfigGroup` 用 `v-if="jdk8Running"` 才渲染编辑器；容器未运行（dual-jdk profile）时只剩灰色提示 | 编辑器**始终渲染**；容器未运行时显示提示“仍可校验/保存，应用前需启动”，应用失败由后端返回明确原因 |
| 2 | 开启堆转储没有默认位置 | UI 只把开关置 true，路径留空；默认路径仅在服务端生成，表单看不到 | 开启且路径为空时自动填 `/app/lab-snapshots/heap-<instance>.hprof`（与服务端默认一致），输入框加占位与说明 |
| 3 | 页面没有横向占满 | `.page` 固定 `max-width:1280px` | 全局改为 `max-width:none`，所有分区使用整屏宽度 |
| 4 | 向导 JVM 配置要三列并排 | 重写后仍是“表格 + 单实例编辑器” | 第 3 步改为 **按启用节点并排的 N 列编辑器**（`wizard-config-column-<node>`），参数逐行对齐可直接比较；未启用节点仍置灰 |
| 5 | JVM 参数对照与配置不一致 | 之前的 `gcCollector` 回填缺失与竞态已修；但面板未说明“目标=已保存配置、应用后生效”，且 15s 节流导致感知滞后 | 面板明确来源与“应用后生效”；节流 15s→**5s**（跟随全局同步）；不一致时保持黄色徽标 |
| 6 | 业务线程池图空白 | 空闲时 active/queue 全为 0，曲线贴底看起来像空白 | 增加 `pool max（参考）` 曲线；所有值为 0 时图头显示“当前均为 0（空闲）” |

# 完成功能项

- `InstanceConfigGroup.vue`：jdk8 编辑器常驻 + 离线提示。
- `InstanceConfigEditor.vue`：堆转储默认路径、占位符、说明；`onHeapDumpChange`。
- `style.css`：整站全宽。
- `ConfigGroupEditor.vue`：N 列并排编辑器（移除“选择实例 + 单个编辑器”和编辑按钮）；run 快照仍由服务端采集。
- `JvmParamsPanel.vue`：5s 刷新、来源说明。
- `MonitorTimeline.vue`：线程池图加 `executor_pool_max_threads` 参考线。
- `TimelineChart.vue`：全 0 时显示“空闲”提示。
- `console-smoke.mjs`：新增三列/池上限/全宽断言；更新向导配置断言。

# 设计逻辑

- 配置编辑（台账）与容器运行解耦：**能编辑不代表能应用**，应用时由受控重建给出明确失败原因。
- 参数对照区分“已保存目标”和“容器回读”，不把草稿当生效值，也不把生效值当草稿。
- 图表在“全 0”时给出语义提示，避免把空闲误判为无数据。

## 追加：向导 JDK8 交互修正（2026-10-30）

- 现象：在向导第 3 步的 app 列中选择 JDK8，会进入“全部禁用 + 红色提示”的死胡同；
  用户误以为前端没更新或功能坏了。
- 修复：
  - app-1/2/3 的 JDK/镜像下拉**只保留 JDK21/jdk21**（服务端同样拒绝 app 实例用 jdk8）；
  - 向导第 3 步新增 **JDK8 外壳独立列**（`wizard-config-column-seckill-app-jdk8`），标注“独立实例 · 不参与本次 run 节点”，
    可直接配置/校验/应用/重启；应用要求 dual-jdk profile 容器在运行。
- 真机：shell 由 DRAFT 应用为 `APPLIED`（pool-probe 按设计 SKIPPED，其余 9 项 PASS）。

# 存在缺陷项&TODO

| 项 | 说明 |
|---|---|
| jdk8 应用 | 需 dual-jdk profile 容器运行；未运行时应用会被拒绝（预期） |
| 向导列宽 | 窄屏（< 3×380px）会自动折行，此时不再是并排 |
| #7 故障注入 404 / oom 模式拒绝 | 按用户要求后续单独讨论 |

# 待定事项和注意事项

1. 本轮仅前端与脚本变更：已重建 dashboard dist（nginx 挂载即生效），无需重建控制器。
2. 三列编辑器只读/保存/应用均走同一实例台账；任一处 `@changed` 会触发列状态重载。
3. 堆转储默认路径与后端 `InstanceConfigValidator.defaultPath` 保持一致，路径约束仍是 `/app/lab-snapshots`。

# 附录：验证

```text
npm run build -> index-Dyw1HWew.js
console-smoke -> 162 PASS, 0 FAIL
浏览器核对：.page max-width=none；wizard 三列 app-1/2/3；堆转储开启后路径 = /app/lab-snapshots/heap-app-1.hprof
```
