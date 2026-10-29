# 批次收口：Stage2 B3 缓存实验与交互对齐

> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `424c948`（已推送）
> 范围：B3-00 交互对齐（`91a07f5`）+ B3-01..04 缓存实验（`f4e25de`）+ 审核修复（`4e925ae`）+ 收尾（`424c948`）
> 闸门：reactor **1337 tests / 0 fail**（chaos 756、app 495、jdk8 59、core 13、platform 14）；console-smoke **138/0**；
> k6 node **113**；claims **52/52**；verify **109 PASS / 1 WARN / 0 FAIL**；smoke **7/7**
> 审核：独立 Qwen（隔离 worktree）**Approved with fixes**，0 Critical / 0 High / 1 Medium（已修）/ 7 Minor 记录

# 当前进展

B3 批次全部收口：6 分区控制台、7 步实验向导、节点门禁、故障时间线、实验记录名称/归档/删除、监控时间轴；
独立 Caffeine L1 开关；10,000 条确定性商品与 SCAN 键浏览/单键详情/重置；A/B 公平对照面板；run 级业务对账。
真机验证覆盖 L1 三实例独立、10k seed、键分页、A/B 一对、对账 7 项（0 流量与 4 单增量两种样本）。
下一步为 B4（实例 JVM/GC/线程池配置闭环）。

# 完成功能项

- 详见 `step-S2-B3-00a-interaction-alignment-implementation.md`（B3-00）与
  `step-S2-B3-01-cache-experiments.md`（B3-01..04，含 Fix Report）。
- 计划 §4 已写入各步骤实现状态；§0 记录批次基线；README §10 更新为 B3 已实现、B4/B5 未实现。

# 设计逻辑

- 草稿 ≠ 运行态；未启用节点只缩小实验范围（via-nginx 仍打全部 upstream，精确隔离用 target-instance）。
- 手动保持复用 `ttlSeconds=-1`：无 expiry、无自动回滚、stop-all 可清理。
- L1 关闭 = 绕过 + 清空 + 不计 L1 计数；L2 仍是共享事实源，保证 A/B 的 B 组是“无 L1 的公平读”。
- 10,000 商品库存公式 `MOD(id*37,501)` 在 Flyway、reset-lab、app reset 三处一致；reset 的业务清空必须显式勾选。
- 对账只看业务不变量，未接线一律 DEGRADED；性能阈值仍由 k6/阈值单独判定。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 跨 JVM seed 竞争 | 同 JVM 已 `synchronized`；三实例同时 seed 同一范围仍可能撞唯一键 | 记录 |
| A/B 无 N 次重复 | 单对对照；中位数/离散度 | B5-03 |
| 7 条审核 Minor | 见 `step-S2-B3-01-cache-experiments.md` 第 4 节与 Fix Report | 记录 |
| 对账样本截断 | `lab_run_check` 无 sample 列，落库按 1024 字符截断 | 记录 |
| B4/B5 | 实例配置应用/回滚/回读、拓扑热度、分流统计、全量验收 | B4/B5 |

# 待定事项和注意事项

1. B4 开工前先读 `step-S2-B3-01-cache-experiments.md` 的“重建与重载”：重建任一 upstream 容器后必须 `nginx -s reload`。
2. 数据现状：基线 10,000 + 实验 seed 1,000；A/B 证据 run-20261029-003/004、对账证据 run-20261029-001 保留。
3. 发布纪律：本批已按用户授权推送；后续 tag/发布仍需单独授权。

# 附录

## 提交

```
424c948 test(B3): stabilize the console smoke and fold in the batch review
4e925ae fix(B3): clamp key-browser SCAN hint to remaining page capacity
f4e25de feat(Stage2): implement the B3 cache experiments batch
91a07f5 feat(Stage2): implement the B3-00 interaction alignment
```

## 交接

- B3-00 实现：`step-S2-B3-00a-interaction-alignment-implementation.md`
- B3-01..04 与审核：`step-S2-B3-01-cache-experiments.md`
- 设计阶段：`step-S2-B3-00-interaction-alignment.md`
