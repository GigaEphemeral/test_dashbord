# 步骤 55 · D10 补 k6 cache-lab 对照（run_id 变量 + 每 mode 面板行）

> 步骤交接 · 收口 step-48 §7.1 第 4 条「D10 按 run_id 对齐」未达成项
> 日期：2026-10-27
> 代码基线：`e96ed16`（`feature/ha-lab`，step-48 真机运行交接之后；改动在隔离 worktree 提交，待主会话合并）
> 交付：`code/lab/docker/grafana/dashboards/ha-d10.json`（新增 `run_id` 模板变量 + 1 行 7 面板，原 25 面板 / `instance` 变量 / uid 逐对象未动）、`code/lab/docclaims/claims.yml`（+2 断言）、`code/lab/scripts/verify.sh`（+1 断言：ha-d10 暴露 run_id 变量）、本文档。
> 验证：`json.load` → OK；`node code/lab/scripts/check-doc-claims.mjs` → **24 total / 24 PASS（基线 22 + 新增 2），Result: OK**；`bash -n verify.sh` → OK；12 条面板表达式全部在真机 Prometheus 上用 `run_id="k6-20261027-164307-cache1"` 跑通（§3.1）；Grafana 真机 provisioning 已验证并还原（§3.3）。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

## 1. 契约

### 1.1 `run_id` 模板变量

D10 `templating.list` 在 `instance` 之前新增一个变量，约定与 D9 一致（D9 的 run_id 取自 `k6_http_reqs_total`，D10 按任务口径取自 `k6_cache_lab_reads_total`）：

```json
{
  "allValue": ".*", "current": { "selected": true, "text": ["All"], "value": ["$__all"] },
  "datasource": { "type": "prometheus", "uid": "prometheus" },
  "definition": "label_values(k6_cache_lab_reads_total, run_id)",
  "hide": 0, "includeAll": true, "label": "run_id", "multi": true, "name": "run_id",
  "options": [],
  "query": { "query": "label_values(k6_cache_lab_reads_total, run_id)", "refId": "StandardVariableQuery" },
  "refresh": 2, "regex": "", "skipUrlSync": false, "sort": 1, "type": "query"
}
```

`query` 用对象形式，与本文件既有 `instance` 变量一致（D9 用裸字符串，两种 Grafana 都接受）。
所有 k6 侧 target 用 `run_id=~"$run_id"`（正则匹配，配合 `allValue: ".*"`）；app 侧 target 继续用 `instance=~"$instance"`，`instance` 变量逐字节未动。

### 1.2 新面板行（id 109–116，行尾追加，`gridPos` 无重叠）

行标题：**k6 cache-lab 对照（按 run_id / mode）**（row id 109，y=81）。
所有分组用通用的 `sum by (mode, result)` / `sum by (mode)`，**不枚举 result 取值**——后续给场景补 `absent`、`limited` 等新 result 时面板无需改动。

| id | 类型 | 标题 | 表达式 |
|---|---|---|---|
| 110 | text | k6 对照行的两个口径陷阱 | （markdown，见 §1.3） |
| 111 | timeseries | 每模式读速率与结果（k6） | `sum by (mode, result) (rate(k6_cache_lab_reads_total{run_id=~"$run_id"}[1m]))` |
| 112 | timeseries | 模式切换事件（时间轴权威切换点） | `sum by (mode, result) (k6_cache_lab_mode_switches_total{run_id=~"$run_id"})` |
| 113 | timeseries | DB 回落对照：k6 每 dwell 差值 vs app 速率 | A：`sum by (mode) (k6_cache_lab_db_fallbacks{run_id=~"$run_id"})`；B：`sum by (reason) (rate(cache_db_fallbacks_total{job=~"seckill-app.*",instance=~"$instance"}[1m]))` |
| 114 | timeseries | 四族防护计数（按 mode） | `sum by (mode) (...)` × 4：`k6_cache_lab_bloom_absents` / `k6_cache_lab_mutex_rebuilds` / `k6_cache_lab_limiter_rejections` / `k6_cache_lab_state_live_markers`，均带 `{run_id=~"$run_id"}` |
| 115 | timeseries | state gauge（按 mode）：L1 / L2 / bloom | `sum by (mode) (...)` × 3：`k6_cache_lab_state_l1_keys` / `k6_cache_lab_state_l2_keys` / `k6_cache_lab_state_bloom_size`，均带 `{run_id=~"$run_id"}` |
| 116 | timeseries | L1 命中率（按 mode，k6 差值口径） | `sum by (mode) (k6_cache_lab_l1_hits{run_id=~"$run_id"}) / clamp_min(sum by (mode) (k6_cache_lab_l1_requests{run_id=~"$run_id"}), 1e-9)` |

面板结构（datasource 块、`fieldConfig.defaults.custom`、`options.legend/tooltip`）逐字段镜像同文件既有 timeseries 面板；单位：111 为 `reqps`，112–115 为 `short`（113 混单位是任务要求的设计：k6 差值 次/dwell 与 app 速率 次/s 共享时间轴），116 为 `percentunit`（min 0 / max 1）。

### 1.3 text 面板（id 110）写死的两个解读口径

1. k6 的 `mode` 标签由**场景时间表推导**，app 侧 `cache_*` 序列没有 `mode` 标签，两侧只能按时间窗对齐；权威切换点以 k6 切换日志（每条 `apps=N/N`）为准，`k6_cache_lab_mode_switches_total` 是其计数。
2. `POST /lab/cache/seed` 用同一个 TTL（`LAB_CACHE_SEED_AVALANCHE_TTL_SECONDS`，默认 60s）写 200 个键，seed 后第 60 秒集体过期，落在当时激活的 dwell 里污染该模式的对比（真机 bloom dwell 的 398 次 DB 回落即此，见 step-48 §7.2）。

### 1.4 verify.sh 与 docclaims

- `verify.sh`：既有 uid 循环（`ha-d1..d4 d7 d9`）**未动**；循环后新增一个块——拉取 `GET /api/dashboards/uid/ha-d10`（复用 `GRAFANA_ADMIN_USER/PASSWORD`，凭据不回显），断言 body 含 `"name":"run_id"`，重试 10×1s，pass/fail 措辞与邻近检查一致。该检查同时隐含「ha-d10 已 provision 且是当前版本」。
- `claims.yml`：新增 `d10-k6-runid-variable`（file_contains `label_values\(k6_cache_lab_reads_total, run_id\)`）与 `d10-k6-cache-lab-panels`（grep_count `k6_cache_lab_(reads_total|mode_switches_total)` ≥2），CRLF 行尾保持。

## 2. 必需环境

- lab 栈正常运行（本步验证时 32 容器在线，`jl-grafana`、`jl-nginx`、Prometheus 均健康）。
- Prometheus **不发布宿主端口**，一律走 Nginx 代理：`http://127.0.0.1:8080/prom/api/v1/query`。
- 至少一次 `cache-lab` k6 运行已 remote-write（本步用 `run_id=k6-20261027-164307-cache1`，其序列仍在 Prometheus 中；`count by (run_id) (k6_cache_lab_reads_total)` → 该 run 8 条序列，也是当前该指标唯一的 run_id）。
- Grafana 从**主仓路径** `D:\000CODE\JAVA-LEARN\code\lab\docker\grafana\dashboards` file-provision，`updateIntervalSeconds: 10`；worktree 里的改动合并进主树后 ~10s 内自动生效，无需重启任何容器。

## 3. 验证

### 3.1 每条表达式的真机查询证据

`GET http://127.0.0.1:8080/prom/api/v1/query`，`run_id="k6-20261027-164307-cache1"`，
`time=2026-10-27T08:47:30Z`（run 结束时刻；表达式与写进 JSON 的逐字一致，仅把 `$run_id`/`$instance` 代入实值）：

| 面板 | 表达式（代入后） | series | 样本值 |
|---|---|---|---|
| 111 | `sum by (mode, result) (rate(k6_cache_lab_reads_total{run_id="k6-20261027-164307-cache1"}[1m]))` | 2 | `{mode="l1-cache",result="ok"}=10.1966`；`{mode="db-fallback",result="ok"}=24.25` |
| 111（`time=08:46:00Z` 复查） | 同上 | 3 | `mutex/ok=10.17`、`logical-expire/ok=24.23`、`ttl-jitter/ok=14.05` |
| 112 | `sum by (mode, result) (k6_cache_lab_mode_switches_total{run_id="..."})` | 8 | `{mode="off",result="accepted"}=2`（初始 + teardown 复位），其余 7 个 mode 各 =1 |
| 113-A | `sum by (mode) (k6_cache_lab_db_fallbacks{run_id="..."})` | 8 | `off=1, null-cache=0, bloom=398, mutex=0, logical-expire=0, ttl-jitter=0, l1-cache=0, db-fallback=0` |
| 113-B | `sum by (reason) (rate(cache_db_fallbacks_total{job=~"seckill-app.*"}[1m]))` | 4 | `miss/penetration/breakdown/avalanche` 四个 reason；run 结束时全 0 |
| 113-B（`time=08:44:40Z`，bloom dwell 中） | 同上 | 4 | `miss=3.60/s`、`penetration=3.51/s` —— 与 k6 侧 bloom dwell 的 398 次差值同一时间窗（≈13.3/s×30s），两侧对齐成立 |
| 114 | `sum by (mode) (k6_cache_lab_bloom_absents / _mutex_rebuilds / _limiter_rejections / _state_live_markers {run_id="..."})` | 各 8 | 本 run 全 0（step-48 §7.2：场景没造出穿透/击穿/限流缺陷；面板留待场景补造缺陷能力后直接出数） |
| 115 | `sum by (mode) (k6_cache_lab_state_l1_keys / _l2_keys / _bloom_size {run_id="..."})` | 各 8 | `l1: off=570`；`l2: off=606, null-cache=6`；`bloom size=3066` |
| 116 | `sum by (mode) (k6_cache_lab_l1_hits{...}) / clamp_min(sum by (mode) (k6_cache_lab_l1_requests{...}), 1e-9)` | 8 | `off=0.6116, null-cache=0.9796, bloom=0.7590` |
| 变量源 | `count by (run_id) (k6_cache_lab_reads_total)` | 1 | `k6-20261027-164307-cache1=8`（`label_values(...)` 是 Grafana 语法，Prometheus API 不识别，用等价 series 查询验证） |

注：111 在任一时刻只覆盖 rate 窗口内的 1–2 个 dwell（1m 窗口 vs 30s dwell），这是 rate 面板的固有形态，不是空数据；切换边界看 112。

### 3.2 静态校验

- `python json.load(ha-d10.json)` → OK；面板 25 → 33，id 无重复，`gridPos` 两两无重叠（程序化检查）。
- 与 `git show HEAD:...ha-d10.json` 对比：原 25 个面板对象、`instance` 变量、uid/version/其余顶层键**逐对象相等**，diff 纯增量（678 行插入、0 删除）；文件保持全 CRLF、无裸 LF。
- `node code/lab/scripts/check-doc-claims.mjs` → `Summary: 24 total, 24 PASS, 0 FAIL, 0 STALE, 0 ERROR`、`Result: OK`（基线 22 + 新增 2）。
- `bash -n code/lab/scripts/verify.sh` → OK；文件保持 LF。

### 3.3 Grafana 真机 provisioning 检查（已执行，主树已还原）

1. 前置：`git -C /d/000CODE/JAVA-LEARN status --porcelain code/lab/docker/grafana/dashboards/ha-d10.json` → 空（主树该文件干净）。
2. 把 worktree 版本复制到主树该路径，等 15s（provisioner 每 10s 扫描）。
3. `GET http://127.0.0.1:3000/api/dashboards/uid/ha-d10`（凭据从主树 `code/lab/docker/.env` source 进变量，未回显）→ **HTTP 200**，`version: 4`，`templating` 为 `run_id <- label_values(k6_cache_lab_reads_total, run_id)` + 原 `instance`，33 面板，id 109–116 的标题与类型全部正确。
4. 还原：`git show HEAD:code/lab/docker/grafana/dashboards/ha-d10.json > <主树路径>`（未用 `git checkout --`/`git restore`）；`git -C /d/000CODE/JAVA-LEARN status --porcelain <该路径>` → 空，`diff --stat` → 0 行。
5. 再等 12s 复查 Grafana：`version: 5, panels: 25, vars: instance` —— 线上已回到原版。全程未动任何容器。

合并进主树后 provisioner 会在 ~10s 内自动加载新版，无需人工操作；`verify.sh` 的新断言届时应 PASS（本次只在 worktree 做了 `bash -n`，**合并后跑一次完整 `verify.sh` 是主会话的事**）。

## 4. 结论

- step-48 §7.1 第 4 条的缺口已关闭：D10 现在能按 `run_id` 过滤 k6 cache-lab 序列，8 个 mode 的读速率、切换点、DB 回落、四族防护、state gauge、L1 命中率与 app 侧 `cache_*` 计数器在同一时间轴上可读，「对比效果」一步可以在一块仪表盘上完成。
- 113 面板的两侧对齐已用真机数据验证：bloom dwell 内 app 侧 `miss+penetration ≈ 7.1/s`（1m rate 摊薄后）与 k6 侧 398 次/dwell 指向同一事件。
- 所有面板对 result/mode 用通用分组，场景后续新增 `absent`、`limited` 等 result 或新 series 时无需改 JSON（新 series 需要新 target 的除外）。

## 5. 仍未完成

1. **场景本身还没造出缺陷**：114 面板在本次 run 全 0，因为 cache-lab 只读存在且非热点的键（step-48 §7.2 已证伪 README 的「对比 off 与保护态」表述）。补「读不存在 id / 热点键变冷 / 按 dwell 重 arm 同 TTL 键」是另一个任务（`code/lab/k6/**` 由并发 agent 负责，本步未触碰）。
2. **seed 同 TTL 污染未修**：text 面板只是把陷阱写死在仪表盘上；根治要么场景按 dwell 重新 arm，要么 seed 支持 TTL 打散，均不在本步范围。
3. **app 侧序列永远没有 `mode` 标签**：对齐只能靠时间窗 + 112 的切换计数；若要在 app 序列上带 mode，需要 app 侧导出当前模式（设计文档明确当前策略不进 Prometheus），本步不改 app。
4. **verify.sh 的 provision 循环仍不覆盖 ha-d5/d6/d8/d10/d11**：任务描述称「verify.sh 断言每个 dashboard uid 已 provision」，实际循环只有 `ha-d1 ha-d2 ha-d3 ha-d4 ha-d7 ha-d9`（既有状态，未削弱）。本步的新断言让 ha-d10 有了内容级覆盖；其余 uid 是否入列由主会话定夺。
5. **D10 顶部使用说明（id 20 text 面板）未更新**：其中「策略切换不会出现在时间轴上；切换时间点需在实验记录里手工标注」对 k6 cache-lab 运行已不完全成立（112 面板给出切换时间轴）。该面板属于「既有面板逐字节不动」约束范围，留给主会话合并后决定是否补一句。
6. **合并后动作（主会话）**：合并 → 等 ~10s provisioning → 跑完整 `verify.sh`（预期新增 1 PASS）→ 如需，把 step-48 §7.1 第 4 条的「未达成」标注指向本文档（step-48 由并发 agent 负责，本步未改）。
