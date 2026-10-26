> 步骤交接 · Step 36 D6 线程实验室面板（七种故障指纹 + 双生命周期时间轴）
> 日期：2026-10-27
> 分支 / HEAD：worktree `worktree-agent-general-purpose-51c147e9`（基线 `a37abff`）/ 本次提交 `feat: add D6 thread-lab dashboard` + 本文档 `docs: record the D6 thread-lab handover (step-36)`
> 关联任务：M5 线程实验室（设计 §7.4 D6、§9.1 故障指纹、§9.2 单线程六态）；上游 M5-1（`ThreadLabMetrics`，step-32）、M5-2a（`LifecycleDemoMetrics`，step-34）；下游：线程操作页（#34）与快照标注（#33）
> 验证：`python -c "import json;json.load(open('code/lab/docker/grafana/dashboards/ha-d6.json',encoding='utf-8'))"` → **OK**（12 panels、id 1–12 无重复、gridPos 无重叠、2 个模板变量）；**未启动任何容器、未调用 Grafana/Prometheus、未执行 Docker 命令**，面板渲染与 PromQL 实跑均未验收

# 当前进展

- 新增 `code/lab/docker/grafana/dashboards/ha-d6.json`（uid `ha-d6`，标题「D6 线程实验室」，tags `ha-lab,d6,thread-lab`，refresh `5s`，默认 `now-15m`）：12 个面板覆盖设计 §7.4 的「七种故障指标指纹、生命周期状态、快照事件标注」中的前两项。
- **纯指标面板**：`/lab/thread/**` 不经 Nginx（`code/lab/docker/nginx/conf.d/default.conf` 只把 `/lab-api/` 转给 chaos-controller，`/lab/` 是静态控制台，注释明确 "Nginx must never proxy these paths"），因此 D6 不新增数据源、不假设任何 API 访问，全部信息来自既有 `seckill-app` scrape。
- 两条**互不相同**的状态机各占一条 state timeline：`thread_lab_demo_state_code`（0–5 真实 JVM 态）与 `thread_lab_lifecycle_state_code`（0/1/2 故障 ACTIVE/PAUSED/STOPPED），颜色、刻度、图例均分开。
- 未改动 provisioning（`providers[0].type: file` 扫描 `/var/lib/grafana/dashboards`，新文件自动加载）、未改其它 dashboard、compose、脚本、Java 代码或 Vue 控制台。

# 完成功能项

## 1. 布局（24 列栅格）

| id | 类型 | 位置 | 标题 |
|---|---|---|---|
| 1 | state-timeline | x0 w10 y0 h7 | 演示线程状态（0–5 真实 JVM 态） |
| 2 | state-timeline | x10 w14 y0 h7 | 故障生命周期（ACTIVE / PAUSED / STOPPED，按 mode） |
| 3 | timeseries | x0 w12 y7 h8 | JVM 线程态指纹（blocked / waiting / timed-waiting / runnable） |
| 4 | timeseries | x12 w12 y7 h8 | 故障参与线程数（按 mode） |
| 5 | timeseries | x0 w8 y15 h8 | 进程 CPU（按实例） |
| 6 | timeseries | x8 w8 y15 h8 | 堆内存已用（area=heap） |
| 7 | timeseries | x16 w8 y15 h8 | 泄漏线程持有字节（heap-leak / oom） |
| 8 | timeseries | x0 w8 y23 h8 | 饥饿池活跃线程（starvation） |
| 9 | timeseries | x8 w8 y23 h8 | 饥饿池队列长度（starvation） |
| 10 | timeseries（bars, stacked） | x16 w8 y23 h8 | 注入事件（每分钟，按 mode） |
| 11 | timeseries（bars, stacked） | x0 w12 y31 h8 | 回退事件（每分钟，按 reason） |
| 12 | timeseries（bars, stacked） | x12 w12 y31 h8 | 演示线程状态推进（每分钟，按目标态） |

## 2. PromQL 清单（全部按 `job="seckill-app"`，应用面板再按 `instance=~"$instance"`）

1. `thread_lab_demo_state_code{job="seckill-app",instance=~"$instance"}` — 值映射 0 NEW / 1 RUNNABLE / 2 BLOCKED / 3 WAITING / 4 TIMED_WAITING / 5 TERMINATED，`min 0 / max 5`，逐态 hex 颜色 + 同色 thresholds。
2. `max by (mode) (thread_lab_lifecycle_state_code{job="seckill-app",instance=~"$instance",mode=~"$mode"})` — 映射 0 STOPPED（灰）/ 1 ACTIVE（红）/ 2 PAUSED（橙）。取 `max by (mode)` 是为了把 7 mode × 3 实例压成 7 行，任一实例处于 ACTIVE 即显示 ACTIVE。
3. `sum by (state) (jvm_threads_states_threads{job="seckill-app",instance=~"$instance",state=~"blocked|waiting|timed-waiting|runnable"})` — 标签值已核对：Micrometer `JvmThreadMetrics` 用 `toLowerCase + replace('_','-')`，所以是 `timed-waiting` 而不是 `timed_waiting`（已反编译 1.15.12 的 `JvmThreadMetrics#getStateTagValue` 确认）。
4. `thread_lab_participant_threads{job="seckill-app",instance=~"$instance",mode=~"$mode"}`
5. `process_cpu_usage{job="seckill-app",instance=~"$instance"}`（unit `percentunit`）
6. `sum by (instance) (jvm_memory_used_bytes{job="seckill-app",area="heap",instance=~"$instance"})` — 对 heap 各 pool 求和得到整堆已用，避免 Eden/Old/Survivor 叠加成三倍。
7. `thread_lab_heap_leaked_bytes{job="seckill-app",instance=~"$instance",mode=~"$mode"}`
8. `thread_lab_pool_active{job="seckill-app",instance=~"$instance",mode=~"$mode"}`
9. `thread_lab_pool_queue_size{job="seckill-app",instance=~"$instance",mode=~"$mode"}`
10. `sum by (mode) (increase(thread_lab_injected_total{job="seckill-app",instance=~"$instance",mode=~"$mode"}[1m]))` — bars + `stacking.mode=normal`
11. `sum by (reason) (increase(thread_lab_reverted_total{job="seckill-app",instance=~"$instance",mode=~"$mode"}[1m]))`
12. `sum by (to) (increase(thread_lab_demo_transitions_total{job="seckill-app",instance=~"$instance"}[1m]))`

## 3. 模板变量与数据源

- `instance`：`label_values(up{job="seckill-app"}, instance)`，includeAll + multi，allValue `.*`，label「实例」——与 D4 逐字段一致。
- `mode`：`label_values(thread_lab_fault_active{job="seckill-app"}, mode)`，includeAll + multi，allValue `.*`，label「故障模式」。`thread_lab_fault_active` 七种 mode 在启动时 eager 注册为 0，因此下拉在首次注入前就有 7 个选项。
- 数据源统一 `{"type":"prometheus","uid":"prometheus"}`：本仓库没有 `${DS_PROMETHEUS}` 用法（已 grep 全部 dashboard），provisioning 的 datasource uid 固定为 `prometheus`。
- `annotations.list` 保持 `[]`：D1–D4、D9 均未定义任何 annotation query，不存在可复制的 `chaos` tags 模式（见缺陷项）。

## 4. 五分钟演示脚本（不依赖 API 可达性以外的假设）

前置：栈已起、Grafana 打开 D6、`instance` 选全部或某一实例、时间窗 `now-15m`、refresh 5s。注入命令需**从 `jl-net` 内的容器**发起（宿主机没有 app 端口），例如一次性 curl 容器：

```bash
TOKEN=${LAB_TOKEN:-lab-token}
NET=java-learn-lab_jl-net
lab() { docker run --rm --network "$NET" curlimages/curl:8.10.1 \
  -s -H "X-Lab-Token: $TOKEN" -H 'Content-Type: application/json' "$@"; }
```

| 时间 | 操作 | 面板应看到 |
|---|---|---|
| 0:00–0:50 | `lab -X POST http://app-1:8080/lab/thread/lifecycle/start`，随后每 2s 一次 `.../lifecycle/step` | P1 由 NEW→RUNNABLE→BLOCKED→WAITING→TIMED_WAITING→TERMINATED 逐格推进；P12 出现对应目标态的柱子 |
| 0:50–1:20 | `.../lifecycle/reset`；再 start + step，然后 `.../lifecycle/freeze` | P1 停在当前真实态（TIMED_WAITING 会自然走完 sleep，属预期）；freeze 后 P12 不再出现新柱 |
| 1:20–2:20 | `lab -X POST http://app-1:8080/lab/thread/fault -d '{"mode":"deadlock","hold":"manual"}'` | P3 `blocked` 陡增；P4 `app-1 deadlock=2`；P2 `deadlock=ACTIVE`；P10 出现一根 deadlock 柱。`.../fault/deadlock/stop` 后三者回落，P11 `manual` 柱 +1 |
| 2:20–3:20 | 同上注入 `cpu-burn` | P5 该实例 CPU 拉满；P3 `runnable` 上升；stop 后立即回落 |
| 3:20–4:10 | 注入 `starvation` | P8 active 贴住池上限、P9 queue 持续上涨；stop 后同时归零（P7 保持 0） |
| 4:10–5:00 | 注入 `heap-leak`（默认 120s TTL，`auto`） | P6 阶梯上升、P7 阶梯上升后封顶；TTL 到期 P11 `ttl` 柱 +1，P7 drain 回零 |

演示要点：先看基线 10s，再注入；`blocked`/`waiting` 是绝对线程数，三实例求和后要看**跃迁**而不是数值本身。

## 5. 验证证据

- JSON 解析 OK；`panels` 12 个、id 1–12 唯一、类型分布 2 state-timeline + 10 timeseries、gridPos 两两不重叠、所有 `x+w ≤ 24`。
- 未执行：Grafana 渲染、PromQL 对 Prometheus 的 `status=success` 校验、变量选项实际枚举（本任务禁 Docker，且共享栈未由本会话操作）。

# 设计逻辑

- **为什么 metrics-only**：`/lab/thread/**` 只在 `jl-net` 内可达；把注入按钮/状态 JSON 塞进 D6 会引入反向代理与鉴权耦合。指标族 `thread_lab_*` 已 eager 注册（step-32/34 的测试钉住），足以表达全部指纹；控制动作留给线程操作页。
- **两套状态机不能复用**：`thread_lab_lifecycle_state_code` 在 M5-1 已占用 0/1/2（STOPPED/ACTIVE/PAUSED），M5-2a 另立 `thread_lab_demo_state_code` 0–5。D6 用两张 timeline、两套映射与配色，避免观众把「TERMINATED」误读成「故障已停止」。
- **指纹到面板的映射**（设计 §9.1）：

  | mode | 指纹 | D6 面板 |
  |---|---|---|
  | deadlock | `blocked` 陡增 + 参与线程 2 | P3 + P4 |
  | stuck | `waiting` 陡增 | P3 + P4 |
  | cpu-burn | `runnable` 上升 + CPU 拉满 | P3 + P5 |
  | heap-leak | 堆阶梯 + 泄漏字节阶梯封顶 | P6 + P7 |
  | oom | 堆先爬后断崖（进程重启则序列归零） | P6 + P7 |
  | thread-leak | `participant_threads` 持续上升（虚拟线程看堆） | P4 + P6 |
  | starvation | pool active=max + queue 上涨 | P8 + P9 |

- **指标名核对（Micrometer 1.15.12 / prometheus-metrics 1.x）**：`PrometheusNamingConvention` 末尾调用 `PrometheusNaming.sanitizeMetricName`，会剥掉 reserved 后缀。`thread_lab_injected_total` / `thread_lab_reverted_total` / `thread_lab_demo_transitions_total` 只含 `_total`，客户端会再补 `_total`，因此抓取名与代码名一致；这同时解释了 `prometheus.yml` 里 `orders_created_total → orders_total` 的 relabel（`_total` 与 `_created` 连续被剥离）。
- **counter 面板用 `increase(...[1m])` 而非 `rate`**：教学场景关心「刚刚注入了几次」，整数柱比每秒小数直观；`[1m]` 与仓库既有 `rate(...[1m])` 的窗口习惯一致。
- **`max by (mode)`**：生命周期是 mode 级属性；按实例展开会让 21 行 timeline 无法在一屏内读完，且同一 mode 不会在两个实例同时被人为注入以外的方式出现。

# 存在缺陷项&TODO

- **未渲染、未实跑 PromQL**：本任务禁止 Docker，无法在 Grafana 11.3.0 里打开 D6，也无法对 Prometheus 执行 12 条查询确认 `status=success`。state-timeline 的 `insertNulls/mergeValues/showValue` 字段按 11.3 schema 书写，但未由 Grafana 解析过；首次起栈需按「待定事项」逐项核对。
- **`thread_lab_injected_total` 没有 `hold` 标签**：任务简报写的是 `{mode,hold}`，实际 `ThreadLabMetrics` 只注册 `mode`（`hold` 只存在于 `FaultAction` 对象，未上报）。D6 因此无法按 auto/manual 区分注入，只能从 P11 的 `reason`（ttl/manual/stop-all）间接看出回退方式。要按 hold 拆分需改 Java 代码，超出本任务范围。
- **chaos annotation 未加**：任务要求「若既有 dashboard 已有 annotation 就照抄」，实际 D1–D4/D9 全部是 `"annotations": {"list": []}`，没有 tags `chaos` 的 Prometheus annotation 先例；强行新造会与 #33 的快照标注任务冲突。D6 保持空列表，等 #33 统一确定 annotation 数据源（Prometheus 查询 vs Grafana HTTP API）。
- **`thread_lab_reverted_total` 是懒注册**：首次回退前 Prometheus 里没有该序列，P11 初始为空（不是 0）。面板描述已写明；不接受该行为再把 counter 改成 eager 需动 Java。
- **D2 的旧面板有失效图例**：`ha-d2.json` 第 393 行 `thread_lab_lifecycle_state_code` 的 `legendFormat` 是 `"{{instance}} {{thread}}"`，但该指标没有 `thread` 标签（只有 `mode`），图例会显示字面量。属既有缺陷、不在本任务改动面内，登记待后续清理。
- **只覆盖 7 种故障 + 演示机，不含快照事件**：§7.4 的「快照事件标注」依赖 #33 的 `thread_lab_snapshot_total`/annotation，当前不存在该指标，故未画。

# 待定事项和注意事项

- **加载方式**：`provisioning/dashboards/dashboards.yml` 是 `type: file` + 目录扫描 + `updateIntervalSeconds: 10`，把本文件合并进主工作树后 10s 内自动出现，无需重启 Grafana、无需改 provisioning。若 Grafana 已在 UI 打开过同 uid，需硬刷新（`allowUiUpdates: true` 时 UI 副本可能遮蔽文件版本）。
- **起栈后的最小核对清单**：① `GET /api/dashboards/uid/ha-d6` 返回 12 panels；② `mode` 下拉 7 项（deadlock/stuck/cpu-burn/heap-leak/oom/thread-leak/starvation）；③ 对 P1–P12 的表达式逐个 `/api/v1/query` 确认无 parse error；④ 注入一次 deadlock，确认 P3 `blocked` 与 P4 `deadlock=2` 同步出现，停止后回落且 P11 出现 `manual`。
- **共享栈影响**：本步只新增一个 JSON 与本文档；主工作树的 `compose`/脚本/Java/Vue 未改，合并不影响正在运行的容器。
- **命名遗留**：D6 标题与 tags 使用 `thread-lab`，与 D1–D4 的英文 tag 风格一致；若后续 D7/D8 沿用 `d7`/`d8` tag，请保持小写短横线。
