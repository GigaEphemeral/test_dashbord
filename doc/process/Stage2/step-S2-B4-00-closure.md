# 批次收口：Stage2 B4 实例 JVM/GC/线程池配置闭环

> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `edaa454`（B4-04 待提交）
> 范围：B4-01 schema/预算/草稿（`d4a5c82`）、B4-02 受控重建/回滚/稳定期（`8a45c4a`）、B4-03 GC 日志（`edaa454`）、
> B4-04 实例页与 JDK8 LIMITED（待提交）
> 闸门：reactor **1414 tests / 0 fail**（chaos 819、app 509、jdk8 59、core 13、platform 14）；console-smoke **126/0**（探针 0 的 BLOCKED 分支；探针正常应为 145）；verify **109/1/0**；
> smoke **7/7**；claims **58/58**；chaos 全量 **812/0**（B4-02）
> 审核：待独立 Qwen 审核

# 当前进展

B4 四步全部实现并真机验证：
- 每实例 JVM/GC/业务池/Caffeine/GC 日志 schema、白名单/预算/路径校验、草稿与 revision；
- 受控重建：snapshot→park→复制创建→Docker health→应用探针→池回读→Nginx reload→Prometheus up→镜像核对；
  成功 APPLIED/ROLLED_BACK + `stable_until`，失败自动恢复旧容器（FAILED；恢复失败 DEGRADED）；
- GC 日志：JDK21 `-Xlog:gc*`、JDK8 `-Xloggc`+轮转，写在 app 可写快照目录，控制器经 `docker exec tail` 回读；
- 实例页“配置组”：状态/目标 vs 生效/预算/警告/稳定期、校验/仅保存/应用并重启/回滚、GC 日志查看；JDK8 外壳 LIMITED。

# 完成功能项

- 后端：`instance/*`（InstanceConfig/Validator/Properties/Repository/Service/Controller/Rebuild*/Logs*/Probe/Gateway）、
  ledger **V4** `lab_instance_config`、**V5** apply/stable 字段、Nginx `/lab-api` 300s、`LAB_INSTANCE_*` 全套配置。
- app：有界业务线程池 + `/lab/pool` 回读 + `executor_rejected_total`；Caffeine 启动开关/访问过期。
- 前端：`InstanceConfigGroup/Editor/GcLogViewer`、`InstancesPanel/View` 配置组标签、`api.js` 七类实例 API。
- 测试：B4-01 48、B4-02 32、B4-03 20（定向）＋ ledger IT；console-smoke +7 条配置组断言。

# 设计逻辑

- 启动参数只允许 run 外应用；草稿保存与真实重建分离，状态 `DRAFT/PENDING_RESTART/APPLYING/APPLIED/ROLLED_BACK/FAILED/DEGRADED`。
- 预算按 `mem_limit` 而非只看 `-Xmx`；默认 Metaspace 96/native 64 是估算值，显式 128m 实测 Metaspace OOM。
- G1 实验参数必须 `-XX:+UnlockExperimentalVMOptions`；健康门禁等待冷启动（20–100s），不把瞬时 unhealthy 当失败。
- 失败自动恢复旧容器；成功才删 parked；`stable_until` 记录稳定期（B5 再做开跑门禁）。
- GC 日志路径限白名单根；JDK8 轮转文件 `<path>.<n>.current` 自动回退；无数据不伪造。
- JDK8 外壳是独立实例，app-1/2/3 不允许切 jdk8 镜像。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| mysql-3 掉组 | errant GTID，探针 0，冒烟走 BLOCKED 分支 126（正常 145）；修复需重建 mysql-3 数据 | 环境/B5 |
| 稳定期硬门禁 | 只展示 `stableUntil`，预检查未拒绝稳定期内正式对照 | B5 |
| jdk-matrix 自动化 | 提供逐实例配置/重启，未做两组自动跑+对比模板 | B5 |
| apply 同步 | 无 operationId/202，最长 240s；浏览器断开后从 config 读取结果 | 记录 |
| Metaspace 建议 | 校验允许 128，UI 已警示；是否强制 ≥192 待定 | B4 审核/B5 |
| 失败容器诊断 | 失败容器移除后恢复，日志摘录进检查证据（约 1200 字符） | 记录 |
| 审核 Minor | 待 Qwen 审核记录 | B4 收口 |

# 待定事项和注意事项

1. 下一步 B5：拓扑热度、分流统计、N 次重复、A01–A32 全量验收。
2. 现场：app-1 基线 APPLIED（512m/池 4-8-50/GC 日志关）；jdk8 外壳 APPLIED（512m/G1/GC 日志开）；mysql-3 OFFLINE。
3. 重建后必须 `nginx -s reload`；apply 内部已做。
4. 发布纪律：本批已按“一路执行”授权推送；tag 仍需单独授权。

# 附录

## 真机证据

- app-1：apply（384m/池 2-4-10/30）9 项检查 PASS；rollback（512m/池 4-8-50/60）PASS；失败自动 RESTORED 两次。
- GC 日志：app-1 apply 后 `logs/gc` OK（55KB，GC pause 行）；rollback 保留文件。
- JDK8：apply APPLIED（pool SKIPPED、`seckill-app-jdk8` up=1、image jdk8），`logs/gc` OK（`.0.current`）。
- UI：app-1 validate/apply/rollback、jdk8 卡片与 LIMITED 展示。

## 提交

```
d4a5c82 feat(B4): implement the instance config schema and runtime knobs
8a45c4a feat(B4): implement controlled instance rebuild with rollback and stable period
edaa454 feat(B4): add version-aware GC logging and container log readback
（待提交）feat(B4): add the instance configuration page and JDK8 limited matrix
```
