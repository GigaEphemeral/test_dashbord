# 步骤交接：B4/B5 配置下发、手动重启与单一事实源

> 任务：用户反馈——JVM 配置无法下发生效、无法手动重启、向导与实例页参数不一致、监控页缺少参数对照
> 日期：2026-10-30
> 分支 / HEAD：`feature/stage2-workbench` / `922f05e`（本步改动本地待提交）
> 测试：`InstanceRebuildServiceTest` 16/0、`InstanceRebuildControllerTest` 9/0、`InstanceConfigServiceTest` 9/0；
> console-smoke **155/0**（新增重启按钮 / JVM 参数对照断言）；claims 76/0；真机 app-1 手动重启 10 项检查全 PASS

# 当前进展

- **确认：后端下发链路本来就是好的。** app-2 保存 → 应用实测 `APPLIED`，10 项检查（snapshot/park/create/start/
  docker-health/app-probe/pool-probe/nginx-reload/prometheus-up/image-identity）全 PASS，耗时 110s。
- **直接原因（已修）**：`InstanceConfigEditor.toForm()` 漏拷 `gcCollector`（它既不在 TEXT/NUMBER/BOOLEAN 列表里，
  `buildPayload` 又单独处理），页面加载后 GC 选择器显示“未设置”，**任何一次页面保存都会把 G1 丢掉**——这正是
  “配置下发生效不了 / 两个页面参数不一致”的根因。修复后页面显示 G1，校验 argv 含 `-XX:+UseG1GC`。
- **并发写丢失（已修）**：向导与监控参数卡原来写的是
  `views.value = { ...views.value, [node]: await fetchInstanceConfig(...) }`——对象展开在 `await` **之前**求值，
  三个实例并发时后完成的赋值会用旧快照覆盖，随机丢掉 app-1 的配置（表现为“实例状态时好时坏”、向导参数偶发缺 app-1）。
  改为先 `await` 再合并；监控参数卡现在稳定显示 app-1/app-2 `APPLIED · G1 · 与目标一致`，app-3 `NOT_CONFIGURED`。
- **真正的缺口在 UI**：
  1. 场景向导第 3 步的「变量与配置组」是第二套草稿模型（字段名/默认值不同），其“应用/回滚”按钮是占位；
  2. 「实例与 JVM」页可以应用，但没有显式“重启 JVM”，且操作栏随页面滚动；
  3. 监控页没有各实例参数对照，无法直观看出参数差异。
- 本步全部修复，并补上了 B5-04b 遗留的 jvm 回读超时与 env 转发问题。

# 完成功能项

| 项 | 落点 | 说明 |
|---|---|---|
| 手动重启 | `POST /lab/instances/{instance}/restart`（`InstanceRebuildService.restart`） | 仅当配置状态为 `APPLIED` 时允许；用当前已应用配置走同一受控重建（10 项检查），revision 不变；未配置 → 409 |
| 重启按钮 | `InstanceConfigEditor.vue` | 「重启 JVM」+ 确认弹窗；仅在 APPLIED 时可用；与 apply/rollback 共用进度与结果面板 |
| 操作栏悬浮 | `InstanceConfigEditor.vue` | 校验/仅保存/应用/回滚/重启固定在卡片底部，长表单滚动时仍可操作 |
| 单一事实源 | `ConfigGroupEditor.vue` 重写 | 向导第 3 步不再维护第二套字段模型：表格显示每节点台账状态/revision/目标参数/回读，编辑区直接复用 `InstanceConfigEditor`（保存/应用/重启/回滚全在台账上） |
| run 配置快照 | `RunLedgerService.create` | 创建 run 时若未显式带 config，则按 `nodes` 从 `lab_instance_config` **服务端快照**写入 config 组；向导不再发送浏览器侧快照，消除整页竞态 |
| 向导快照 | `ExperimentWizard.vue` | `update:config` 改为发射 **服务端实例配置快照**，run 草稿仍记录“创建时实际配置”，但不再有第二套默认值 |
| 监控参数对照 | `JvmParamsPanel.vue` | 实时监控新增「JVM 参数对照」：实例/状态/rev/Xms-Xmx/GC/目标停顿/Young 区间/IHOP/Region/额外参数/镜像/目标-回读一致性；每 15s 最多刷新一次 |
| 回读稳定性 | `application.yml`、compose、`.env.example` | `jvm-flags-timeout-seconds` 5→10；补转发 `LAB_INSTANCE_JVM_FLAGS_*`、`LAB_INSTANCE_EVENTS_*` |

# 设计逻辑

- **一套配置、一个写入口**：`lab_instance_config` 是唯一事实源；向导只做“选择实例 + 嵌入同一编辑器 + 汇总状态”，
  因此两个页面天然一致。run 草稿里的 config 组退化为“创建时快照”，不再参与生成启动参数。
- **重启 ≠ 应用**：应用使用已保存的草稿（可提升 DRAFT→APPLIED）；重启只接受 APPLIED，避免把草稿静默生效。
  两者都走同一条受控重建与 10 项健康门禁，失败自动恢复。
- **监控参数卡只读**：数据来自同一台账 + 容器回读，按 15s 节流，避免 5s 轮询把 6 个请求放大。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 向导第 3 步嵌入编辑器 | 每个节点渲染一个完整编辑器，页面较长；如需更紧凑可后续做“摘要 + 抽屉” | Future |
| `lab_run_env` 采集 | 报告环境段仍回退配置台账（B5-03 遗留） | Future |
| app-2 状态 | 验证过程中 app-2 已从 NOT_CONFIGURED 变为 APPLIED rev1（真实下发证据），属预期副作用 | 记录 |

# 待定事项和注意事项

1. 重启/应用都会重建容器（约 40–120s），期间禁止发起 run（服务端已用 run 锁拒绝）。
2. 「重启 JVM」只对 `APPLIED` 可用；`DRAFT/PENDING_RESTART` 请用「应用并重启」。
3. `jcmd` 回读在负载下可能变慢，10s 为默认；仍可被 `LAB_INSTANCE_JVM_FLAGS_TIMEOUT_SECONDS` 覆盖。

# 附录

## 真机证据（2026-10-30）

```text
POST /lab-api/lab/instances/app-2/apply（保存后）
  -> status=APPLIED rollback=NOT_NEEDED，10 checks PASS，110s

POST /lab-api/lab/instances/app-3/restart
  -> 409/40907 no stored configuration for app-3

POST /lab-api/lab/instances/app-1/restart
  -> action=RESTART status=APPLIED rollback=NOT_NEEDED revision=27
     snapshot/park/create/start/docker-health/app-probe/pool-probe/nginx-reload/prometheus-up/image-identity = PASS
     115s

GET /lab-api/lab/instances/app-1/effective
  -> readback=OK jvmReadback=OK notes=[]
```

## 关键文件

- 后端：`instance/InstanceRebuildService.java`（RESTART）、`InstanceRebuildController.java`
- 前端：`ConfigGroupEditor.vue`（重写）、`InstanceConfigEditor.vue`（重启/悬浮栏）、`JvmParamsPanel.vue`、`MonitorPage.vue`、`ExperimentWizard.vue`、`api.js`
- 配置：`application.yml`、`compose.base.yml`、`.env.example`
