> 步骤交接 · M2-2b compare 与 D9
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / 本次提交 `feat: add bench compare and D9 dashboard`（父提交 `7b43e29`）
> 关联任务：#42（M2-2b）
> 测试 / verify：无 Java 代码变更；`verify.sh` **97 PASS / 1 WARN**（新增 ha-d9，WARN 仍是 cAdvisor/WSL2）；compare 实跑成功；D9 Grafana API 200
> 运行状态：16 个 lab 容器运行中，三实例健康；默认 product 20 已由 Task 20a reset 恢复

# 当前进展

- Task 20b 完成：新增 `bench-compare.js` / `compare.sh` 与 `ha-d9.json`，`verify.sh` 纳入 ha-d9。
- compare 已对已有 `20261026-145555-smoke` 与 `neg-threshold` 两个归档实跑，输出 Markdown 对比表并写入 `doc/process/benchmark/compare-smoke-vs-neg.md`。
- D9 由 Grafana provisioning 自动加载，API 200；面板包含 VUs、RPS、P95/P99、错误率、409/429、应用 RPS/P95 与运行信息。
- 验证：`verify.sh` 97 PASS / 1 WARN；栈保持运行。
- 下一步：Task 21（M2-3 chaos-controller 容器动作与 TTL 看门狗）。

# 完成功能项

- `code/lab/scripts/bench-compare.js`：
  - 支持 run id（在 `BENCH_REPORTS_DIR` 下解析）或包含 `manifest.json` 的目录；
  - 对比 scenario/runId/verdict/git/reset/duration/RPS/requests/iterations/P95/P99/max/failed/flow/server/checks/409/429/VUs；
  - 通过 `PROM_URL` 在各自运行窗口查询峰值：heap、threads、GC pause 次数、Hikari pending；Prometheus 不可用显示 n/a 并提示；
  - 输出 Markdown，支持 `--out`。
- `code/lab/scripts/compare.sh`：薄包装，环境变量可覆盖 reports dir/PROM_URL/超时/输出；`exec node` 传播退出码。
- `code/lab/docker/grafana/dashboards/ha-d9.json`：UID `ha-d9`，`run_id` 多选变量（来自 `k6_http_reqs_total`），应用指标对照；k6 业务拒绝（expected 409/429）独立展示。
- `code/lab/scripts/verify.sh`：dashboard 检查循环加入 ha-d9。
- `doc/process/benchmark/compare-smoke-vs-neg.md`：真实对比结果（PASS vs 负向 FAIL，含 heap/GC/thread/Hikari 峰值）。
- 无应用 Java 代码、无 compose、无 k6 场景变更；不重启共享栈（Grafana provisioning 自动加载 D9）。

# 设计逻辑

- **compare 数据源**：Task 20a 的 `manifest.json` 是运行元数据与 keyMetrics 的唯一来源；不重新解析 k6 日志。JVM/资源峰值按 manifest 的 `startEpoch/endEpoch` 用 PromQL `max_over_time`/`increase` 查询，跨 reset 的计数器用 `increase` 并接受重置影响。
- **场景不同提示**：现有归档只有 smoke 与负向 smoke；compare 仍可比较流程/性能趋势，但显式提示场景不同，不等价于同场景回归。
- **D9 变量作用域**：`run_id` 来自 k6 remote-write 序列；k6 面板全部按 `$run_id` 过滤；应用 RPS/P95 作为对照，不带 `run_id`（应用指标没有该标签）。
- **错误语义**：k6 的 expected 409/429 不计入 failed；D9 单独展示业务拒绝；应用层 5xx 仍由 k6 failed/server error 面板反映。
- **配置外置**：compare 的 reports dir、PROM_URL、超时、输出路径全部 env 可覆盖；D9 JSON 不含运行时路径/超时。
- **无侵入**：D9 仅新增 provisioning JSON；Grafana 文件 provider 自动刷新，不重启整个栈。

# 存在缺陷项&TODO

- compare 样例的两个 run 场景相同（smoke），但用途不同（正常 vs 负向阈值）；真正的同场景 A/B 需要后续用 `bench.sh smoke` 生成两个不同配置的 run。
- Prometheus 峰值查询依赖运行时间窗和 7d 保留期；归档过期或 Prometheus 重启后可能 n/a。compare 已提示但不失败。
- D9 运行信息是静态 Markdown；manifest/verdict 未在面板内动态展示（可后续通过 Grafana annotation/表格插件或外部链接增强）。
- D9 只做 Grafana API 200 校验，没有自动化视觉回归；查询已在真实 k6 序列上核对。
- 无应用层代码改动；不需要重建镜像。

# 待定事项和注意事项

- 下一步 Task 21（M2-3）：chaos-controller 容器动作 + TTL 看门狗；应复用 Task 20a reset/bench 与现有 token/whitelist，所有超时/路径外置。
- 审核：独立 Qoder 会话 + Qwen + 隔离 worktree（OCR 暂不启用）。Task 18/19/20a 审核会话 `14710c72-8d3c-58d5-afec-680ebca111b8` 正在运行；Task 20b 审核待启动，不阻塞主开发。
- 运行 `compare.sh` 前确认两个 run 的 manifest 仍在本机 reports 目录；不要删除 reports/benchmark 证据。
- 演示数据：Task 20a 最终 reset 后 product 20=100；verify 97/1。

# 附录

- 本次提交：`feat: add bench compare and D9 dashboard`（父 `7b43e29`）。
- 关键文件：
  - `code/lab/scripts/bench-compare.js`
  - `code/lab/scripts/compare.sh`
  - `code/lab/docker/grafana/dashboards/ha-d9.json`
  - `code/lab/scripts/verify.sh`
  - `doc/process/benchmark/compare-smoke-vs-neg.md`
- 验证命令：

```bash
bash code/lab/scripts/compare.sh 20261026-145555-smoke neg-threshold --out doc/process/benchmark/compare-smoke-vs-neg.md
bash code/lab/scripts/verify.sh
```

# 审核修复（Task 20b 独立审核，追加）

> 依据设计 §11.4（compare）与 §7.4（D9）对提交 `8744854` 的独立审核，只修
> High/Medium，零新依赖、不改应用代码/compose。记录如下（保留上方原始五段式，不改写历史）。

- **D9 延迟单位（High）**：k6 remote-write 把 `k6_http_req_duration_p95/_p99` 以
  秒存储（load 实测 product 0.00835s=8.35ms、login 0.01837s），面板 3 却按 `ms`
  标注且未换算，显示值小 1000 倍。修复：表达式 `* 1000`，单位保持 `ms`。
- **D9 多 run/flow 压平（High）**：`run_id` 变量默认 All/多选，但 k6 面板用无
  `by` 的 `max(...)`/`sum(...)`，会把多个 run 与多个 flow 合成一条“最差端点”线，
  无法区分运行（load 的 login 18.4ms vs product 8.35ms 被压平）。修复：面板 2/5
  按 `run_id` 聚合，面板 3 按 `run_id, flow` 聚合，面板 4 按 `run_id` 聚合，legend
  带上 `{{run_id}}`（延迟再加 `{{flow}}`）。单 run 选择时仍返回一条，All 时逐 run 区分。
- **compare 缺涨跌幅（Medium，§11.4）**：原表只有绝对 Δ（B-A）。新增 `Δ% (B vs A)`
  列，按原始值计算涨跌幅（A=0 记 n/a），样例同步再生成。
- **compare 缺 429 率（Medium，§11.4）**：原只有 409/429 计数。新增
  `Expected 429 rate = expected429 / requests` 行（保留计数行），与 n/a 语义一致。
- **compare 峰值窗口（Medium）**：旧窗口 `max(60, duration+30)` 从 `end` 回看，
  对 6-7s smoke 会多含约 50s 运行前（reset 后启动）历史，实测把 load 的 GC 次数从
  20.0 抬高到 23.6（虚增约 40%）。改为 `duration + grace`，`grace` 由
  `BENCH_COMPARE_WINDOW_GRACE_SECONDS`（默认 15，>一个 5s scrape）控制并设下限，
  去掉 60s 固定地板，兼顾覆盖一次抓取与不吞运行前活动。
- **健壮性/口径（Minor，一并处理）**：`BENCH_COMPARE_TIMEOUT_MS` 非法值不再变成
  NaN 即时 abort（回退 5000）；`reset.baselineStock` 缺失时显示 n/a 而非
  “undefined”；`Peak heap (MB)` 改为准确的 `(MiB)`；删除 `compare.sh` 未用的
  `ROOT_DIR`、`promMax` 未用的 `durationSeconds` 死参；`BENCH_COMPARE_SCRIPT`/新增
  env 写入 `compare.sh` 与 `k6/README.md`；`k6/README.md` 去掉“Task 20b 尚无 compare”
  过期说明并补 compare 用法/环境变量/退出码与 D9 指向。

- 验证（只读，不触碰共享栈与 reports）：`node --check`、`bash -n`、`JSON.parse` 均通过；
  对 `20261026-145555-smoke` vs `neg-threshold` 实跑 compare 退出 0，`PROM_URL` 指向
  死端口时峰值行显示 n/a 且退出 0，缺参数/缺失 run 退出 2；D9 修复后的每条 k6 PromQL
  用已知窗口 GET 复核（load t=1792998232 → login 18.37ms/product 8.35ms；spike
  t=1792992851 → 429 sum by run_id=13391；neg t=1792998367 → 分 flow P95 ms）。
- 仍 deferred（本次不实现）：D9 故障标注（Task 21/22 chaos）、运行 manifest 动态面板、
  Grafana 视觉自动化回归。`Δ` 绝对列沿用原始浮点四舍五入，故 A(4)→B(6) 与 Δ(1) 的
  表观不一致属已知 Minor（涨跌幅列以原始值给出更可读的 +27.3%）。
