# 步骤 49 · 文档断言证伪器（doc claims checker）

> 步骤交接 · 文档漂移治理
> 日期：2026-10-27
> 代码基线：`2980874`（`docs: close out the 2026-10-27 batch with final gates and retrospective`）
> 交付：`code/lab/docclaims/claims.yml`、`code/lab/scripts/check-doc-claims.mjs`、`code/lab/scripts/check-doc-claims.sh`、本文档。
> 边界：只读脚本 + 文档；不启动 Docker / Maven / npm，不改 `verify.sh`。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

`doc/avoid_common_error/14-docs-must-be-falsified.md` 和 `doc/process/Stage1/retrospective-2026-10-27.md` §3.1 记录过同一类事故：代码已经合并，文档还写着「未实现 / 未接线 / 不存在」。本步把这些断言变成可执行的清单：每条断言绑定一个仓库内事实，事实变了脚本就红。

---

# 1. 注册表契约

注册表：`code/lab/docclaims/claims.yml`，顶层是一个 claim 列表。解析器是脚本内置的 YAML 子集（两空格缩进；支持列表、嵌套 map、行内 `[a, b]`、引号字符串、布尔、整数、行尾注释），**不引入任何 npm 依赖**。不支持 anchor、block scalar、flow map；出现未知字段或坏缩进直接报错，不猜。

每条 claim 的字段：

| 字段 | 必填 | 含义 |
|---|---|---|
| `id` | 是 | 稳定标识，小写字母/数字/连字符；`--only` 按它匹配 |
| `doc` | 否 | 断言所在文档，字符串或字符串列表；只作定位与 STALE 提示，不解析正文 |
| `statement` | 是 | 人读的断言原文（可含中文） |
| `polarity` | 否 | `positive`（默认）或 `negative` |
| `verify` | 是 | 仓库事实检查，见下表 |
| `expect` | 是 | 与断言一致的观测值：布尔，或 `grep_count` 的整数 |
| `last_verified` | 是 | 最近一次人工确认日期 `YYYY-MM-DD` |
| `note` | 否 | 给维护者的补充说明 |

`verify.kind`：

| kind | 字段 | 观测值 | 说明 |
|---|---|---|---|
| `file_exists` | `path` | 布尔 | 路径必须存在且是普通文件 |
| `file_contains` | `path`、`pattern` | 布尔 | 读取 UTF-8 文本，按 JavaScript 正则搜索 |
| `grep_count` | `path`、`pattern` | 整数 | 目录递归或单文件，统计**匹配行数**；跳过 `.git`、`node_modules`、`target`、`dist` 等 |
| `shell` | `command`、可选 `timeout_ms`（≤60000） | 布尔 | 在仓库根目录用 `bash -c`（回退 `sh`）执行；只允许快速、只读命令 |

`path` 一律相对仓库根，解析后必须仍在根内（拒绝 `..` 逃逸）。`pattern` 是 JavaScript 正则，不是 glob；需要字面点号时用 `[.]` 或转义。`shell` 的输出不打印，避免把环境变量/凭据带进日志。

判定语义：

- **positive**：断言说能力存在。`verify` 的观测值符合 `expect` → `PASS`；不符合 → `FAIL`。
- **negative**：断言说能力缺失。观测值符合 `expect`（通常是 `false` 或 `0`）→ `PASS`；不符合 → `STALE`，表示能力已经落地而文档还在说缺失。
- `grep_count` 的 `expect: 0` 表示「一行都不许匹配」；`expect: N > 0` 表示「至少 N 行匹配」。
- 状态只有四种：`PASS` / `FAIL` / `STALE` / `ERROR`（正则错误、路径逃逸、shell 不存在等）。任何 `FAIL`、`STALE`、`ERROR` 都以退出码 1 结束；注册表/参数错误退出码 2；全绿退出码 0。

运行接口：

```bash
node code/lab/scripts/check-doc-claims.mjs
node code/lab/scripts/check-doc-claims.mjs --json
node code/lab/scripts/check-doc-claims.mjs --only 'toxiproxy-*'
node code/lab/scripts/check-doc-claims.mjs --registry code/lab/docclaims/claims.yml --root .
bash code/lab/scripts/check-doc-claims.sh --only 'd?-*'
```

`--json` 只向 stdout 输出一个 JSON 对象（含 `summary` 与逐条 `claims`），适合 CI 或上层脚本。`--only` 支持 `*` 与 `?`，匹配不到任何 id 时退出码 2。

---

# 2. 功能落地时怎么加断言

## 2.1 新功能已合并，文档说它存在

1. 在 `claims.yml` 末尾（positive 区）加一条，`polarity` 可省略。
2. 选最小、最稳定的事实：优先 `file_exists` / `file_contains`；需要跨文件计数时用 `grep_count`；只有前三种都不够时才用 `shell`，且命令必须快速、只读。
3. `doc` 写断言所在的文档，可以写多个；`statement` 写文档里的原话或等价句。
4. `expect` 用 `true`（或 `grep_count` 的最小行数）。
5. 运行 `node code/lab/scripts/check-doc-claims.mjs --only <新 id>`，确认 `PASS`。
6. 把 `last_verified` 改成当天，和代码改动一起提交。

## 2.2 一个 negative 断言突然 STALE（功能刚落地）

这是本工具最主要的告警路径。处理顺序：

1. 看 `STALE` 行的 `docs say this is missing...`，把列出的文档逐一改成「已实现」并写证据（文件、提交号）。
2. 把该 claim 从 negative 翻成 positive：删掉 `polarity: negative`，把 `expect` 从 `false/0` 改成 `true/最小行数`，并把 `statement` 改成肯定句。不要只是删掉 claim，否则下次功能被移除时没人报警。
3. 如果新功能有多个可证伪点，拆成多条 positive claim（例如「文件存在」+「关键类/路由存在」）。
4. 重跑全量，确认 `STALE` 消失、没有新的 `FAIL`。

## 2.3 一个 positive 断言 FAIL（代码被移除或重构）

先判断是哪一边错：

- 代码确实还在，只是路径/符号改名 → 更新 `verify.path` / `pattern`，保留断言。
- 功能确实被移除 → 文档必须降级为「未实现」，同时把 claim 翻成 negative（`polarity: negative`，`expect: false/0`，`statement` 改成否定句）。
- 只是测试/构建产物缺失（例如 dashboard JSON 尚未生成）→ 不要把 claim 改成 negative；先修构建，claim 保持 positive。

## 2.4 写 claim 的三条硬规则

- **一个 claim 只证伪一件事**。不要用一条 `grep_count` 同时证明 Toxiproxy 覆盖层和 Java 动作。
- **断言必须能在当前工作树复现**。`last_verified` 不是「我记得」，而是「我跑过这条命令」。
- **不要把会变的数字写进 `verify`**（测试数、服务数、毫秒数）。这类数字不属于本工具，见 §3。

---

# 3. 它不做什么

- **不做语义审核**。它不读文档正文来判断「这句话是否仍然成立」；`doc` 只是定位信息。一个 positive claim 通过，只能证明代码事实存在，不能证明文档里没有另一段旧话还在说「未实现」。反向的 negative claim 才是过期探测器。
- **不校验正文里的数字**。Maven 测试数、`verify.sh` 的 PASS/WARN、compose 服务数、启动毫秒、k6 VU/阈值都可能随环境变化；这些应留在各自的验收脚本或 step 文档的「验证记录」里，不要塞进 `claims.yml`。
- **不校验行号与提交号**。文档里的 `路径:行号`、`代码基线: <commit>` 仍按 `doc/design/Stage1/details/README.md` 的基线规约人工维护。
- **不做运行时验证**。不启动容器、不连 Prometheus/Grafana、不跑 Maven/npm/k6；`shell` 只用于 `bash -n` 这类静态检查。功能「存在但坏了」不在本工具范围内。
- **不替代 `verify.sh`**。`verify.sh` 仍然是环境与行为的验收入口；本工具是文档断言的静态闸门，默认不接线（见 §5）。
- **不处理秘密**。注册表不应包含 token、密码、连接串；`shell` 输出不回显。
- **不是完整 YAML 解析器**。只支持两空格缩进的子集；复杂 YAML 会报错而不是静默误读。

---

# 4. 首次运行结果（2026-10-27，基线 `2980874`）

命令与退出码：

```text
$ node code/lab/scripts/check-doc-claims.mjs
...
Summary: 16 total, 16 PASS, 0 FAIL, 0 STALE, 0 ERROR
Result: OK
EXIT=0
```

逐条结果：

| # | claim | 方向 | 观测 |
|---|---|---|---|
| 1 | `toxiproxy-overlay-exists` | positive | `compose.toxiproxy.yml` 含 `toxiproxy-init:` |
| 2 | `toxiproxy-actions-exist` | positive | `ToxiproxyController.java` 含 `chaos/toxiproxy` |
| 3 | `d6-thread-dashboard-exists` | positive | `ha-d6.json` 含 `thread_lab_demo_state_code` |
| 4 | `d7-dual-jdk-dashboard-exists` | positive | `ha-d7.json` 含 `jvm_version` |
| 5 | `d10-cache-dashboard-exists` | positive | `ha-d10.json` 含 `cache_l1_requests_total` |
| 6 | `d11-db-ha-dashboard-exists` | positive | `ha-d11.json` 含 `mysql_cluster_member_state` |
| 7 | `mysqld-exporter-ha-job-exists` | positive | `prometheus.yml` 含 `job_name: mysqld-exporter-ha` |
| 8 | `mysql-cluster-probe-exists` | positive | chaos-controller 主代码 2 行匹配 `mysql_cluster_(member_state|primary_info)` |
| 9 | `mysql-mode-switch-exists` | positive | app 主代码 11 行匹配 `MYSQL_MODE` |
| 10 | `thread-snapshots-exist` | positive | thread 包 9 行匹配 `Thread.print|GC.heap_info` |
| 11 | `ha-acceptance-script-exists` | positive | `bash -n ha-acceptance.sh` 退出 0 |
| 12 | `thread-console-proxy-exists` | positive | `AppProxyService.java` 含 `/lab/thread` |
| 13 | `chaos-counters-exist` | positive | chaos-controller 主代码 4 行匹配 `chaos.(actions|reverts)` |
| 14 | `d8-tuning-panel-missing` | negative | `ha-d8.json` 不存在 |
| 15 | `cache-console-page-missing` | negative | dashboard 源码 0 行匹配 `/lab/cache/(seed|mode|state)` |
| 16 | `lab-cache-proxy-missing` | negative | `AppProxyService.java` 0 行匹配 `/lab/cache` |

故意破坏验证（已还原）：

```text
$ # 把 d6-thread-dashboard-exists 的 path 改成 ha-d6-missing.json
$ node code/lab/scripts/check-doc-claims.mjs --only d6-thread-dashboard-exists
FAIL  d6-thread-dashboard-exists
      file_contains code/lab/docker/grafana/dashboards/ha-d6-missing.json pattern="thread_lab_demo_state_code" -> false (ENOENT) | expected true but observed false
Summary: 1 total, 0 PASS, 1 FAIL, 0 STALE, 0 ERROR
Result: FAILED
EXIT=1
```

STALE 路径也做了本地验证：临时 `touch` 一个 `ha-d8.json`，`d8-tuning-panel-missing` 立刻报 `STALE` 并退出 1；文件已删除。

## 4.1 首次运行发现的过期文档（本步不修，交由文档 owner）

以下断言在当前 HEAD 已不成立；本次只登记，**没有修改任何 `doc/design` 正文**，避免把「证伪」和「改稿」混在一个提交里。

| 文档:行 | 过期断言 | 当前事实 |
|---|---|---|
| `doc/design/Stage1/details/README.md:57` | 线程快照未实现，只有 `LoggingLifecycleSnapshotSink` 占位 | `FileLifecycleSnapshotSink` + `ThreadSnapshotService` 已合并（step-35） |
| `doc/design/Stage1/details/README.md:59` | `mysql_cluster_*` 指标未实现 | `MysqlClusterMonitor` 已导出（step-30） |
| `doc/design/Stage1/details/README.md:60` | Toxiproxy / `chaos-proxy` 未实现 | 覆盖层 + `ToxiproxyController` 已合并（step-39/40） |
| `doc/design/Stage1/details/README.md:61` | D6 / D7 / D10 / D11 面板未实现 | 四个 JSON 均已存在（step-36/37/38/41） |
| `doc/design/Stage1/details/01-总体架构与运行拓扑.md:439` | `--profile dev|ha|full` 与 `chaos-proxy` 尚未落地 | `chaos-proxy` 已落地；`full` 仍未做（只这一半过期） |
| `doc/design/Stage1/details/02-业务主链路.md:380` | `MYSQL_MODE` 与 Router 双数据源未实现 | `DataSourceAccessProperties` + `MySqlModeSwitchTest` 已合并（step-29） |
| `doc/design/Stage1/details/03-缓存体系.md:439` | D10 面板未实现 | `ha-d10.json` 已存在（step-37） |
| `doc/design/Stage1/details/05-MySQL高可用与读写路由.md:7` | 应用侧 Router 模式与集群指标尚未实现 | 两者均已合并（step-29/30） |
| `doc/design/Stage1/details/05-MySQL高可用与读写路由.md:19` | exporter 抓取配置尚未接进 Prometheus | `mysqld-exporter-ha` 已内联进 `prometheus.yml`（step-38 补充） |
| `doc/design/Stage1/details/05-MySQL高可用与读写路由.md:102` | 没有 `MYSQL_MODE`、没有 `mysql_cluster_*` | 两者均已合并 |
| `doc/design/Stage1/details/05-MySQL高可用与读写路由.md:302` | `MYSQL_MODE` / `mysql_cluster_*` 未实现 | 同上 |
| `doc/design/Stage1/details/06-故障注入与实验向导.md:425` | Router 双数据源、Toxiproxy、集群指标都不存在 | 三者均已合并 |
| `doc/design/Stage1/details/07-线程实验室.md:23` | `LifecycleSnapshotSink` 当前实现只打日志，不含 jcmd | `FileLifecycleSnapshotSink` 已执行 jcmd（step-35） |
| `doc/design/Stage1/details/07-线程实验室.md:27` | chaos-controller 没有代理 `/lab/thread/**` | `AppProxyService` 已实现窄代理（step-44） |
| `doc/design/Stage1/details/07-线程实验室.md:203-204` | jcmd 快照 / 落盘标注「待补（未实现）」 | 已实现（step-35） |
| `doc/design/Stage1/details/07-线程实验室.md:456` | 线程快照 jcmd 未实现 | 同上 |
| `doc/design/Stage1/details/07-线程实验室.md:457` | D6 面板未实现 | `ha-d6.json` 已存在（step-36） |
| `doc/design/Stage1/details/08-双JDK与JVM调优.md:6` | D7 面板不存在 | D7 已存在；同句的调优台/D8 仍未实现（部分过期） |
| `doc/design/Stage1/details/08-双JDK与JVM调优.md:44` | 图中「D7 对比（未实现）」 | D7 已落地（step-41） |
| `doc/design/Stage1/details/08-双JDK与JVM调优.md:47` | 仓库没有 JDK 8 的 compose 服务与 Dockerfile | `--profile dual-jdk` 与 JDK 8 服务已合并（step-41） |
| `doc/design/Stage1/details/08-双JDK与JVM调优.md:157` | 「计划中的 D7 对比流程（未实现）」 | D7 流程已落地 |
| `doc/design/Stage1/details/08-双JDK与JVM调优.md:170` | 图中「D7 面板不存在」 | D7 已存在 |
| `doc/design/Stage1/details/08-双JDK与JVM调优.md:346` | D7 面板尚不存在 | D7 已存在 |
| `doc/design/Stage1/details/08-双JDK与JVM调优.md:352` | D7 面板未实现 | D7 已存在 |
| `doc/design/Stage1/details/08-双JDK与JVM调优.md:353` | compose 无 JDK 8 服务 | 已有 `dual-jdk` profile（step-41） |
| `doc/design/Stage1/details/09-压测与观测.md:6` | Grafana 只有 D1/D2/D3/D4/D9 | D6/D7/D10/D11 已落地 |
| `doc/design/Stage1/details/09-压测与观测.md:447` | D5/D6/D7/D8/D10/D11 都未实现，抓取未接线 | 只有 D8 仍未实现；其余已落地。同文 `:236` 已写对，前后矛盾 |
| `doc/design/Stage1/details/09-压测与观测.md:454` | `chaos_actions_total` / `chaos_reverts_total` 未实现 | 已实现（step-21） |
| `doc/design/Stage1/details/10-操作台与API.md:42` | 控制台不可达 `/lab/thread` | thread 控制台经窄代理可达（step-44） |
| `doc/design/Stage1/details/10-操作台与API.md:389` | 控制器没有 `/lab/thread/**` 代理 | 已实现（step-44） |
| `doc/design/Stage1/details/11-配置与安全边界.md:385` | 应用 Router 双数据源未实现 | 已实现（step-29） |
| `doc/design/Stage1/details/11-配置与安全边界.md:386` | Toxiproxy 未实现 | 已实现（step-39/40） |
| `doc/design/Stage1/details/11-配置与安全边界.md:387` | 集群状态指标未实现 | 已实现（step-30） |
| `doc/design/Stage1/details/diagrams/README.md:122` | D7 对比「待补」 | D7 已落地 |
| `doc/design/Stage1/details/diagrams/09-load-and-observability-flow.md:66` | D7「未实现（待补）」 | D7 已落地；同图 `:67` 的 D8 仍然正确 |
| `doc/process/Stage1/step-30-mysql-cluster-metrics.md:69` | D11 面板未建 | D11 已建（step-38） |
| `doc/process/Stage1/step-38-ha-acceptance.md:47`、`:147`、`:161`、`:181` | `mysql-ha-scrape.yml` 惰性、job 不存在、D11 无数据 | 同文 `:185` 已记录最终改为 `prometheus.yml` 内联 job；前半段未回改 |
| `doc/process/Stage1/step-42-mermaid-diagram-set.md:33` | 自述 `details/01`、`details/09` 仍写 Toxiproxy/D6/D10/D11 未实现 | 该自述到本基线仍成立，属于「知道但未修」 |
| `code/lab/docker/grafana/dashboards/ha-d11.json:47`、`:624` | 面板说明文字仍称 `mysql-ha-scrape.yml` 未被引用 | job 已内联；面板数据面可用 |
| `doc/readme.md:160` | 写「D1–D11」 | 实际没有 D8（`ha-d8.json` 不存在），是过度声明 |

仍然正确、**不要**当成过期修掉的「未实现」：D8 调优面板、`/lab/cache/reset`、缓存实验控制台页、`/lab/cache/**` 窄代理、`SIGSTOP` 主库切换演练。它们已经分别由 negative claim 或本次人工核对确认。

---

# 5. 可选闸门（不改 `verify.sh`）

本步**没有修改** `code/lab/scripts/verify.sh`。需要时在提交前手工加一行：

```bash
node code/lab/scripts/check-doc-claims.mjs
```

或使用包装脚本（`REPO_ROOT` / `DOC_CLAIMS_REGISTRY` / `NODE_BIN` 可外置覆盖）：

```bash
bash code/lab/scripts/check-doc-claims.sh
```

建议的接入方式（任选，不要在本步直接改）：

- 本地 pre-push：把上面一行加到个人 hook；失败只警告不阻塞。
- CI 文档任务：先跑 `--json` 存档，再按退出码决定是否阻塞；`--only` 可用于按目录拆分。
- 发布/合并前：全量跑一次；`STALE` 必须在合并前把对应文档改掉，或在 PR 里明确登记「本批不修」。

参数外置约定：

| 变量/参数 | 默认 | 用途 |
|---|---|---|
| `REPO_ROOT` / `--root` | 脚本上三级目录 | 仓库根；所有 `path` 相对它解析 |
| `DOC_CLAIMS_REGISTRY` / `--registry` | `code/lab/docclaims/claims.yml` | 注册表路径（可绝对或相对 root） |
| `NODE_BIN` | `node` | 包装脚本使用的 Node 可执行文件 |
| `--only` | 不限制 | 按 claim id 的 `*`/`?` glob 过滤 |
| `--json` | 关 | 机器可读输出 |

维护节奏：功能合并时按 §2 加/翻 claim；每次发版前全量跑；`last_verified` 与代码基线一起更新。这样「未实现」不再依赖某个人记得 grep。
