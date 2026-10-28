# 步骤交接：Stage2 B3-00 交互对齐（交互稿 v4 + 文档对齐阶段）

> 任务/步骤：Stage2 **B3-00 交互对齐**（当前仅完成交互稿 v4 与设计/计划文档对齐，代码实现未开始）
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `0d0281b`
> 关联任务：B2 收口与独立审核；B3-00（计划 v1.4）；交互稿 v4；方案 v1.5
> 测试 / verify：文档断言 **41/41 PASS**；HTML 设计稿用 Playwright（Edge headless）验证导航/门禁/GC 参数/重启/删除弹窗无 JS 错误；本步未改代码，**未重跑 Maven / verify / smoke**
> 运行状态：本步未修改运行环境；B2 收口时控制器 healthy，当前无 k6 运行容器
> 口径指针：2026-10-29 本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次可能已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- B2 批次已收口（`3012120`），独立 Qwen 审核 **0 Critical / 0 High / 0 Medium / 5 Minor，Approved**（`5836ec6` 回写）；B2 收口闸门：reactor **1231/0/0/0**、console-smoke **74/0**、claims 41、k6 105、verify 109/1、smoke 7/7。
- 按用户反馈完成交互稿 v1→v4 迭代：A–F（6 分区、基础配置 + 7 步向导、节点多选、故障时间线、拓扑完整性、测试名称/清理）全部采纳；再按 6 条配置组意见完成 v4.1（未启用节点门禁、JVM/GC 参数、线程池 tooltip、Caffeine 参数、重启、GC 日志）。
- 三份目标文档已对齐并推送：方案 **v1.5 §16**、最终交互稿 **§11**、开发测试与验收计划 **v1.4（新增 B3-00）**。
- 最新 commit `0d0281b`；已推送 `origin/feature/stage2-workbench`（`2644b93..0d0281b`）。
- 未开始：B3-00 的代码实现（结构化表单、节点门禁、商品 ID 分布参数、测试名称/清理、时间轴等）；B3 缓存实验、B4、B5。

# 完成功能项

## 交互稿 v4（设计演示，不是真实控制台）

- 文件：`doc/design/Stage2/stage2-交互稿演示.html`（单文件、内联 CSS/JS，可直接浏览器打开；提交 `470e1d7` / `587dae5` / `c2039b5`）。
- 6 个一级分区：总览 / 场景向导 / 实例与 JVM / Benchmark / 实时监控与操作 / 实验记录；取消独立「故障与缓存实验」，计划故障进向导、运行时操作进监控页、探索工具进标签页。
- 前置基础配置 + 7 步向导：实验目标（多选）→ 目标与流量 → 变量与配置组 → 流量与执行配置（k6）→ 故障时间线 → 预检查与执行 → 报告与对比；启动后自动跳转监控。
- 节点门禁：Java app-1/2/3 多选；<2 个时实例分流/故障禁用；MySQL 单机/集群；Redis 高可用；chaos-proxy；未启用节点在配置行/编辑器下拉/拓扑/实例表/实例卡显示“未启用”。
- 配置组编辑器：
  - 堆/运行时：`-Xms`、`-Xmx`、`MaxMetaspaceSize`、`MaxDirectMemorySize`、`extraJvmArgs`（白名单）。
  - GC：G1/Parallel/Serial；G1 参数含最大停顿、新代初始/最大占比、Region、IHOP；Parallel 线程数；通用 NewRatio/SurvivorRatio；**不含** `G1ReservePercent`/`G1ConcRefinementThreads`/ZGC。
  - 业务池/Tomcat：core/max/queue/keepAlive/拒绝策略；`server.tomcat.threads.max`/`acceptCount`/`minSpareThreads`；均带中文 tooltip。
  - Caffeine：`maximumSize`、写入/访问过期、刷新、`recordStats`；页面提示“按秒配置，0=不启用；关闭 L1 清空并显示不适用”。
  - GC 日志：开关/路径/单文件上限/保留数/OOM dump/路径；JDK8 `-Xloggc`、JDK21 `-Xlog:gc*`。
  - 重启：应用并重启 / 仅保存 / 回滚；状态 `待重启/应用中/已生效/已回滚`；预检查前完成、run 中禁改启动参数、失败自动回滚并进入稳定期。
- 商品 ID：采样范围预设（含越界/库内热点/自定义）、`PRODUCT_ID_MIN/MAX/DISTRIBUTION/CONCENTRATION`、平均/正态分布、库内/越界占比预览。
- 实时监控与操作：KPI、时间轴（RPS/P95/错误率、JVM/GC、线程池/连接池）、k6 进度/速度（VU、iterations/s、http_reqs/s）、完整拓扑（Nginx/app/Redis/Sentinel/MySQL/Router/RabbitMQ/Toxiproxy）、Grafana 入口/深链、实时操作与实验工具标签。
- 实验记录：测试名称、归档/彻底删除（二次确认）、报告对比指标表、UNKNOWN 关闭。

## 文档

- `doc/design/Stage2/stage2改造方案.md` → **v1.5**，新增 §16（§16.1–§16.12），并在 §4.3/§4.4/§4.5/§9/§11 加口径指针。
- `doc/process/Stage2/stage2-最终交互稿.md` → 顶部链接 v4，新增 §11（门禁、JVM/GC、线程池、Caffeine、GC 日志、重启、已确认决策）。
- `doc/process/Stage2/stage2-开发测试与验收计划.md` → **v1.4**：新增 **B3-00 交互对齐**；B4-01/02/04 补字段与重启语义；B5-01/02 补时间轴/拓扑/分流口径；§9 追加 4 项确认；变更记录更新。
- B2 收口/审核文档：`step-S2-B2-00-closure.md`（`3012120` + `5836ec6`），含 A01/A17/A20/A09/A12/A29 真机证据与 5 个 Minor。

# 设计逻辑

- **k6 是统一流量触发层**：smoke/load/spike 是流量模型；吞吐基线/缓存穿透/缓存 A/B/JVM/故障是实验目标。目标可多选/组合，但都挂在同一份 k6 配置上；因此 k6 配置固定在预检查前一步，避免“实验类型”和“流量模型”混为一谈。
- **基础配置是门禁**：节点/MySQL/Redis/网络形态决定哪些实验可选；未启用节点不删拓扑、只置灰并显示“未启用”，既保留完整结构，又避免误配。
- **配置组与实例页分工**：向导第 3 步是“实验组 vs 基线组”的编辑入口；实例页是当前状态 + 配置组库。JVM/GC/线程池/Caffeine/GC 日志字段全部白名单化，重启/回滚/稳定期由 B4 落实，run 中禁止改启动参数。
- **Caffeine 语义**：过期/刷新按秒，`0` 不启用；关闭 L1 时清空并显示“不适用”，防止把“关闭”误读成 0 命中率。
- **可观测性**：时间轴用 Prometheus HTTP API（Grafana 同源），k6 进度/速度用 remote write 的 `run_id`；无数据不显示 0；Grafana iframe 受登录/Cookie 限制时降级深链。
- **文档与实现分离**：本步只改交互稿与三份基线文档，明确标注 B3-00 代码未开始，避免把设计稿当已交付能力。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| B3-00 代码未开始 | 结构化 k6 表单、节点门禁、商品 ID 分布参数、故障时间线、测试名称/归档删除、时间轴/k6 进度、拓扑/实例表“未启用”均只到设计稿 | B3-00 |
| `PRODUCT_ID_*` 未进代码 | manifest/planner/k6 尚未加入采样范围/分布/集中度参数；当前 k6 仍按固定商品/范围 | B3-00/B3-02 |
| JVM/GC/线程池/Caffeine/GC 日志字段未进 schema | 方案已补字段，但 B4 的配置 schema、白名单、预算校验、重启/回滚未实现；Caffeine 属 B3 | B4-01/02/04、B3-01 |
| 测试名称/归档/删除未进台账 | 需要 Flyway 迁移、列表/详情字段、级联删除 API 与二次确认；运行中删除需先 stop | B3-00/B1 台账扩展 |
| 时间轴/k6 进度未接真实数据 | 设计稿是静态示意；需要 Prometheus 查询与 k6 run 指标窗口/前端图表 | B5-01/B5-02 |
| 线程 UI 仍走旧代理 | 新入口 `/chaos/instances/{name}/thread/**` 已有 operationId/事件，UI 尚未切换 | B5/专项 |
| `traffic_mixed` 恒 false | 预检查停背景流量，不做自动检测；报告中明确标注 | 设计口径 |
| EXITED k6 容器保留 | 保留到 stop-all/控制器重启清扫 | 设计口径 |
| A10 HA 切换 | B2 未重跑完整切换，引用 Stage1/B0 证据 | B5/专项 |
| B2 审核 Minor-1 | `LOG_WAIT_SECONDS=15`、默认 tail=200、4MiB 防御上限未外置 | 待裁决 |
| B2 审核 Minor-2 | 报告目录 0777 无 Windows 单测，只有真机 A01 证据 | 记录 |
| B2 审核 Minor-3 | stop-all 停 k6 后 run 行仍 RUNNING、锁仍持有；页面已提示需再 stop，未自动收盘 | 专项/B5 |
| B2 审核 Minor-4 | 文档小漂移已修 `架构现状` 指针；其余记录 | 记录 |
| B2 审核 Minor-5 | 根 `.gitattributes` 声明 shell `eol=lf`，但存量 `run.sh/bench.sh` 是 CRLF；需单独 renormalize | 专项 |
| 设计稿行尾 | HTML 是 CRLF（与仓库既有 HTML/脚本一致）；三份文档是 LF；后续编辑用字节级处理 | 纪律 |

# 待定事项和注意事项

1. **下一步**：你审阅三份文档与 v4 交互稿；确认后按计划进入 **B3-00 交互对齐** 的代码实现，再 B3-01 缓存实验。本步未推进开发。
2. **依赖**：B3-00 的商品 ID 分布参数要改 manifest/planner/k6 与 UI；测试名称/删除要扩台账 API/Flyway；时间轴要接 Prometheus；JVM/Caffeine/GC 日志要等 B4/B3 的 schema。
3. **运行/调试**：`stage2-交互稿演示.html` 是纯前端 mock，不请求后端；不要把它当作真实控制台验收。真实控制台仍是 B2 收口状态。
4. **安全边界**：新增字段必须走白名单/类型/范围校验；`extraJvmArgs` 拒绝危险诊断/命令/任意路径；不带任意 URL、挂载或 shell。
5. **发布纪律**：本次按用户明确要求推送 `feature/stage2-workbench`；后续 push/tag 仍需单独授权。未打 tag。
6. **数据状态**：本步未改业务数据/台账；B2 收口后无 k6 运行容器，`jl-traffic` 为按需停止态。

# 附录

## 提交

```
0d0281b docs(Stage2): align design, interaction spec and plan with interaction v4
c2039b5 feat(Stage2): refine the config-group editor in the interaction mockup
587dae5 feat(Stage2): revise the interaction mockup for the A-F decisions
470e1d7 docs(Stage2): add the interactive HTML mockup for the final interaction spec
1b01d98 docs(Stage2): add the final interaction spec aligned with the requirements and plan
5836ec6 docs(B2): fold the independent review into the closure
3012120 docs(B2): close the batch with the gate ledger, live evidence and claims
```

## 关键文件

- 交互稿：`doc/design/Stage2/stage2-交互稿演示.html`
- 方案：`doc/design/Stage2/stage2改造方案.md`（§16）
- 交互稿文档：`doc/process/Stage2/stage2-最终交互稿.md`（§11）
- 计划：`doc/process/Stage2/stage2-开发测试与验收计划.md`（B3-00、B4、B5、§9）
- B2 收口：`doc/process/Stage2/step-S2-B2-00-closure.md`

## 验证命令

```bash
bash code/lab/scripts/check-doc-claims.sh
# 结果：41 total, 41 PASS, 0 FAIL, 0 STALE, 0 ERROR

# HTML 设计稿（Playwright / Edge headless）验证项：
# - 6 个导航分区、无 JS 错误
# - 取消 app-3 后：配置行置灰、编辑器下拉排除、拓扑/实例表/实例卡显示“未启用”
# - G1/Parallel/Serial 切换显示对应字段
# - “应用并重启”状态变为“已生效”
# - 向导启动后自动跳到实时监控与操作
# - 实验记录删除弹窗可开/可取消
```
