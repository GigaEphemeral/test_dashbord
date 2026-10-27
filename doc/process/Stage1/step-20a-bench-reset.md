> 步骤交接 · M2-2a 数据重置与 bench 归档
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / `feat: add bench runner and lab reset`（父提交 `0c00e09`）
> 关联任务：#41（M2-2a）
> 测试 / verify：无 Java 代码变更、未编译；默认 `verify.sh` **96 PASS / 1 WARN**；`smoke.sh` **7/7**；`bench.sh smoke` exit 0；`bench.sh load -- LOAD_DURATION=1m` exit 0；负向 `ERROR_RATE=0` exit **99** 且归档完整
> 运行状态：16 个 `lab=java-learn` 容器运行中、三实例健康；product 1/20 DB/Redis 均为 **100**、订单 **0**、队列空（最终 reset 后）
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- Task 20a 完成：新增 `code/lab/scripts/reset-lab.sh`、`bench.sh`、`bench-report.js`，并给 `code/lab/k6/run.sh` 增加最小的 `--summary-export` / `reports` 挂载 / 额外 env 转发能力。
- 数据重置可重复：停止 app 层 → 清订单/恢复库存 → FLUSHDB → 清队列 → 启动 app 层并等待健康；product 20 从 Task 19 的 0 恢复到 100，默认 `verify.sh` 不再需要 `SECKILL_PRODUCT_ID` 覆盖。
- 每次 bench 生成 `code/lab/k6/reports/<run_id>/`（`summary.json`、`k6.log`、`manifest.json`、`report.html`，重置时另有 `reset.log`）和 `doc/process/Stage1/benchmark/<date>-<scenario>-<run_id>.md`；阈值失败时仍完整归档并以 k6 退出码退出。
- 验收实跑：
  - 重置：`RESET PASSED`，库存 min/max=100、reserved=0、订单 0、Redis 20/20 个 `seckill:stock:*`=100、队列 ready/unacked=0，耗时 97s；
  - 默认 `verify.sh`：**96 PASS / 1 WARN**（WARN 仍是 WSL2 cAdvisor per-container 序列缺失）；
  - 默认 `smoke.sh`：**7/7 PASS**；
  - `bench.sh smoke`：run_id `20261026-145555-smoke`，7 requests / 5 checks，exit 0，归档 + 结果文档；
  - `bench.sh load -- LOAD_DURATION=1m`：run_id `20261026-150056-load`，9,244 requests / 9,044 iterations、RPS 151.55、product P95 **8.35ms** / P99 **15.26ms**、错误 0、VU max 200，exit 0；
  - 负向 `bench.sh smoke --run-id neg-threshold -- ERROR_RATE=0`：exit **99**，`flow_errors`/`server_errors` 的 `rate<0` 判 FAIL，`manifest.json` verdict=FAIL，`report.html` 与结果文档均生成。
- 未改应用代码、未改 compose、未改 `verify.sh`/`smoke.sh`、未删除任何卷；未做 compare/D9（Task 20b）与 chaos 时间线（Task 22）。
- 下一步：M2-2b compare 与 D9 面板。

# 完成功能项

## 1. `code/lab/scripts/reset-lab.sh`

- `set -euo pipefail`、LF、路径基于脚本自身定位；compose 始终 pin `java-learn-lab`，同时校验容器 `com.docker.compose.project` 标签，避免误伤同名非本项目的 `redis` 容器。
- 用法 `reset-lab.sh [--yes] [--dry-run]`：破坏性操作必须 `--yes`（bench 传入）；`--dry-run` 只做只读预检并打印计划/当前状态。
- 预检先收集全部问题（compose 服务存在、MySQL/Redis/RabbitMQ/app 容器 running、项目标签（空标签也拒绝）、RabbitMQ management API 200、目标队列存在（GET /api/queues 要求 200）、MySQL 只读查询与 catalog 非空、Redis PING、`.env` 凭据齐全），有任一问题则不执行任何破坏性动作。
- 重置步骤：
  1. `compose stop app-1 app-2 app-3`（先静默消费者，避免 truncate 与 purge 之间产生新订单）；
  2. MySQL：`TRUNCATE order_order`；`UPDATE inventory_stock SET available=<baseline>, reserved=0`；对缺失的 catalog 商品补插 baseline 行；Flyway 历史与 catalog 种子不动；
  3. Redis：`redis-cli flushdb`（实验专用实例；清掉 L2 缓存、login token、秒杀幂等 claim 与 `seckill:stock:*`）；
  4. RabbitMQ：management API `DELETE /api/queues/%2F/<queue>/contents`（队列与绑定保留，404 视为无消息可清）；
  5. `compose start app-1 app-2 app-3`，轮询容器 health 至三实例 healthy（默认最长 180s），启动 seeder 用 `SET NX` 从 MySQL 回填 Redis 库存；
  6. 校验：库存行数=catalog 数、min/max=baseline、reserved=0、订单=0、每个 catalog 商品都有 `seckill:stock:<id>`=baseline（轮询等待 seeder）、队列 ready/unacked=0、三实例 healthy、Nginx `/health`=ok，打印紧凑 summary。
- 所有值可覆盖：`RESET_BASELINE_STOCK`、`RESET_APP_SERVICES`、`RESET_MYSQL_SERVICE`/`RESET_MYSQL_CONTAINER`、`RESET_REDIS_SERVICE`/`RESET_REDIS_CONTAINER`、`RESET_RABBITMQ_SERVICE`/`RESET_RABBITMQ_CONTAINER`、`RABBITMQ_QUEUE`、`RABBITMQ_MANAGEMENT_URL`、`RESET_APP_HEALTH_TIMEOUT_SECONDS`、`RESET_APP_HEALTH_INTERVAL_SECONDS`、`RESET_VERIFY_RETRIES`、`RESET_VERIFY_INTERVAL_SECONDS`、`RESET_CURL_MAX_TIME`、`RESET_ENV_FILE`、`RESET_COMPOSE_PROJECT`、`RESET_HEALTH_URL`、`RESET_SECKILL_STOCK_KEY_PREFIX`。
- 凭据来自 `RESET_ENV_FILE`（默认 `code/lab/docker/.env`）：MySQL 密码经 0600 `docker exec --env-file`（MYSQL_PWD）传递、RabbitMQ 认证经 `curl --config -`（stdin）传递，两者都不出现在 argv/ps 或任何输出中；临时文件由 EXIT trap 删除；脚本不打印 `.env` 内容。（Fix Report 前的实现用 `curl -u` 与 `compose exec -e MYSQL_PWD=`，密码在 argv 可见，已在 fix 提交中改正。）

## 2. `code/lab/scripts/bench.sh`

- 用法 `bench.sh <smoke|load|stress|spike|soak> [--run-id <id>] [--no-reset] [--] [KEY=VALUE...]`；默认 run_id 为 `<YYYYmmdd-HHMMSS>-<scenario>`。
- 预检：Docker 可用；`compose config --services` 的 16 个服务全部 running 且带 health 的 healthy（默认全部，可用 `BENCH_REQUIRED_SERVICES` 收窄）；Nginx `/health=ok`；Prometheus `/-/ready` 含 Ready（经 Nginx 容器内网访问）；k6 镜像本地存在（缺失只提示 pull）；场景文件存在；node 可用；不自动启动栈。
- 默认调用 `reset-lab.sh --yes` 并把输出写入 `reset.log`；`--no-reset` 时 manifest 记录 `reset.executed=false`。
- 通过既有 `code/lab/k6/run.sh` 运行：传入 `RUN_ID`、`SCENARIO`、`K6_REPORT_DIR=<run dir>`、`K6_SUMMARY_EXPORT=/reports/summary.json`、`K6_FORWARD_ENV=<-- 后的变量名>`；k6 stdout/stderr 写入 `k6.log`，退出码原样传播。
- 归档：`summary.json`、`k6.log`、`manifest.json`、`report.html`（+ `reset.log`）；manifest 记录 scenario/run_id/start/end/duration、k6 镜像/退出码/转发 env、git commit/branch、reset baseline、阈值结果与 verdict。
- 结果文档：`doc/process/Stage1/benchmark/<date>-<scenario>-<run_id>.md`，含关键指标、阈值判定、Grafana 时间窗链接、Prometheus 查询链接、manifest 摘要与 reset 信息。
- 退出码：k6 非 0 时以 k6 退出码退出（阈值失败为 99），否则报告/判定失败退出 1；所有路径都保留归档。

## 3. `code/lab/scripts/bench-report.js`

- 零依赖 Node 脚本，读取 k6 `--summary-export` 与 k6.log，生成 `manifest.json`、自包含 `report.html` 和 Markdown 结果文档。
- 适配 k6 1.3 的扁平 metrics 结构（`{count,rate,p(95),value,...}`）；关键指标：iterations、RPS、P95/P99/max、product 读 P95/P99、`http_req_failed`、`flow_errors`、`server_errors`、checks、409/429、terminal failed/poll timeouts、VU max。
- 阈值判定优先解析 k6.log 的 `✓/✗ 'rule' observed` 块（含实测值）；summary 中 k6 1.3 的布尔阈值仅作兜底（实测 true=越界、false=通过）；整体 verdict 以 k6 退出码为准，summary 缺失判 FAIL 但仍出报告。
- `report.html` 为内联 CSS 的单文件页面：verdict 徽标、关键指标卡片、指标表、阈值表（含 observed）、运行记录、Grafana/Prometheus 链接、env 覆盖与归档文件说明。

## 4. `code/lab/k6/run.sh` 最小扩展

- 新增 `K6_REPORT_DIR`（host 目录挂到 `/reports`，rw）、`K6_SUMMARY_EXPORT`（容器内路径，调用 `--summary-export`）；`K6_SUMMARY_EXPORT` 必须配合 `K6_REPORT_DIR`，否则 fail-fast。
- 新增 `K6_FORWARD_ENV`（逗号分隔变量名，校验 `[A-Za-z_][A-Za-z0-9_]*`）把 bench 的 `--` 覆盖显式转发进容器，避免在 run.sh 里重复维护变量清单。
- Windows/Git Bash 的挂载路径仍用 `cygpath -m`，docker 调用保留 `MSYS_NO_PATHCONV=1`；未设置新变量时行为与 Task 19 完全一致。

# 设计逻辑

- **先静默再重置**：先 `compose stop` app 层，再 truncate/purge，最后 `compose start`。若按“先 purge 再 restart”，消费者已 prefetch 的未确认消息会在重启后回队并再次落单，造成 truncate 后又出现订单；先停消费者使重置结果确定。`stop`+`start` 与 `restart` 一样是新 JVM：运行时 toggle、熔断器、Caffeine L1 全部回到种子状态。
- **DB 为唯一库存真相**：重置只改 MySQL 库存，Redis 由应用启动 seeder 用 `SET NX` 回填。这样 Redis 与 DB 不会因脚本手工写两处而漂移，也复用了生产启动路径。FLUSHDB 后所有 key 不存在，`SET NX` 必然写入。
- **只动实验数据**：order 表 truncate、inventory 覆盖、Redis FLUSHDB、队列 contents 删除；不 drop 库、不删卷、不动 Flyway/catalog，保证 `verify.sh` 的 20 商品/迁移版本检查继续成立。
- **项目边界**：compose 命令 pin `java-learn-lab`；直接 `docker exec` 的三个 datastore 容器额外校验 compose project 标签，防止环境里同名 `redis`（本机确实存在一个非实验 `redis` 容器）被误清。
- **失败不静默**：预检聚合所有问题后一次性报告；重置中途失败时 EXIT trap 会把已停的 app 层重新拉起（不掩盖原始退出码，重启失败则打印手工恢复命令）并非零退出打印 compose ps；bench 的 reset 失败会在 k6 前中止且不伪造归档。阈值失败不弱化断言，归档后以 99 退出。
- **summary 导出的版本差异**：k6 1.3 的 `--summary-export` 不是旧版 `values` 嵌套结构，且阈值布尔语义未文档化（实测 true=越界）。报告生成器因此以日志中的 ✓/✗ 为权威阈值结果，summary 布尔只做兜底，verdict 锚定 k6 退出码，避免把失败 run 误标 PASS。
- **配置外置**：脚本内只有集中默认值；路径、URL、容器/服务名、baseline、队列、超时、重试、报告目录、Grafana 面板均可环境覆盖；无密码入参、无 `.env` 入库。
- **归档 gitignore**：`code/lab/k6/reports/` 已在 Task 19 加入 `.gitignore`；可复现的是脚本与结果 Markdown，原始 summary/log 留在本机。

# 存在缺陷项&TODO

- **重置耗时约 97s**：其中 `compose start` 等待依赖健康 + Spring Boot 启动 + 健康轮询占主要时间；短 smoke 的 bench 总耗时中重置占 90% 以上。若后续需要高频短跑，可评估只重启必要实例或复用 `restart`，但会牺牲“所有实例 toggle/熔断/L1 归零”的确定性。
- **重置会清空登录 token 与缓存**：Redis FLUSHDB 是设计选择（实验数据整体归零）；任何依赖旧 token/缓存的外部脚本需重新登录。
- **订单自增 ID 归零**：`TRUNCATE` 重置 AUTO_INCREMENT，验证/压测看到的订单 id 会从 1 重新开始；依赖历史 id 的日志对照需注意。
- **指标计数器随 app 重启归零**：Prometheus 会处理 counter reset，但跨 reset 的 `increase()` 会把重启点算作下降；报告只使用本次 run 的 k6 指标，未依赖应用累计值。
- **队列 in-flight 语义**：purge 只删除 ready 消息；脚本通过先停消费者规避未确认消息回队。若未来有外部消费者不在 app-1/2/3 中，需要扩展静默范围。
- **k6 summary 阈值布尔语义未文档化**：已实测 true=越界；若升级 k6 需重新验证 `bench-report.js` 的兜底分支（日志解析不受影响）。
- **`--summary-export` 是 k6 旧式导出**：k6 未来版本可能移除；当前镜像固定 `grafana/k6:1.3.0`，升级时需同步验证。
- **预检默认要求全部 16 服务健康**：Task 22 chaos 期间若故意停某服务，bench 会拒绝启动；届时用 `BENCH_REQUIRED_SERVICES` 收窄或先恢复。
- **报告 HTML 未做浏览器/移动端像素验收**：仅验证生成成功、字段与阈值正确；Task 20b 的 compare 页会复用同一数据。
- **duration 是墙钟时间**：k6 1.3 summary 不含 `state.testRunDurationMs`，manifest 的 duration 取 bench 起止秒差（含容器启动约 1–3s），不是 k6 内部测试时长。
- **未实现 compare/D9**：按边界留给 Task 20b；当前报告没有多 run 对比、没有 D9 面板。
- **结果文档会随每次 bench 累积**：`doc/process/Stage1/benchmark/` 目前提交了 smoke、1m load 与负向样例；日常调试可用 `BENCH_DOC_DIR` 指到临时目录避免污染仓库。
- **负向样例保留 FAIL 文档**：`2026-10-26-smoke-neg-threshold.md` 故意为 FAIL，作为“阈值失败仍归档”的证据；不要误读为功能回归。

# 待定事项和注意事项

- **下一步 M2-2b**：compare 与 D9。可直接读取 `code/lab/k6/reports/<run_id>/manifest.json`（schemaVersion 1、keyMetrics、thresholds、verdict）做两次 run 对比；不要改动本步的归档字段名，如需扩展保持向后兼容。
- **Task 22 chaos**：bench 的 `--no-reset` 与 `BENCH_REQUIRED_SERVICES` 是给故障场景预留的入口；chaos 时间线尚未实现。
- **运行前**：栈必须已启动；`bench.sh` 不会 `up`。默认 reset 会重启三实例，演示前若不想丢当前数据用 `--no-reset`。
- **数据状态**：最终 reset 后 product 1/20 DB/Redis=100、订单 0、队列空；随后 smoke/negative 产生的订单已再次清零。
- **安全边界**：重置只作用于 `java-learn-lab` 项目；不要修改 `RESET_*_CONTAINER` 指向非实验容器，预检的项目标签会拦截但不要绕过。`.env` 永不提交。
- **Windows/Git Bash**：两个新脚本均为 LF；如遇 CRLF 导致 `bash` 报错，需保持仓库 LF（`.gitattributes` 未改）。`bench-report.js` 依赖宿主 node（本机 v22.23.2），无 npm 依赖。
- **提交**：功能提交 `feat: add bench runner and lab reset`；本文件与阶段交接随后提交。

# 附录

## 关键文件

- `code/lab/scripts/reset-lab.sh`（新增）
- `code/lab/scripts/bench.sh`（新增）
- `code/lab/scripts/bench-report.js`（新增）
- `code/lab/k6/run.sh`（扩展 `K6_REPORT_DIR`/`K6_SUMMARY_EXPORT`/`K6_FORWARD_ENV`）
- `code/lab/k6/README.md`（bench/reset 章节）
- `doc/process/Stage1/benchmark/2026-10-26-smoke-20261026-145555-smoke.md`
- `doc/process/Stage1/benchmark/2026-10-26-load-20261026-150056-load.md`
- `doc/process/Stage1/benchmark/2026-10-26-smoke-neg-threshold.md`

## 复现命令

```bash
bash code/lab/scripts/reset-lab.sh --dry-run
bash code/lab/scripts/reset-lab.sh --yes
bash code/lab/scripts/verify.sh                 # 96/1
bash code/lab/scripts/smoke.sh                  # 7/7
bash code/lab/scripts/bench.sh smoke
bash code/lab/scripts/bench.sh load -- LOAD_DURATION=1m
set +e; bash code/lab/scripts/bench.sh smoke --run-id neg-2 -- ERROR_RATE=0; echo $?; set -e  # 99
```

## 证据摘要

- reset：`RESET PASSED`，`inventory rows=20 min=100 max=100 reserved_sum=0`、`orders=0`、`redis 20/20 keys at 100`、`queue ready=0 unacked=0`、三实例 healthy、elapsed 97s。
- 默认 verify：`VERIFY PASSED: 96 checks passed, 1 warning(s)`。
- 默认 smoke：`SMOKE PASSED`（7/7）。
- bench smoke：run_id `20261026-145555-smoke`，7 requests、5 checks、exit 0，manifest verdict PASS。
- bench load 1m：run_id `20261026-150056-load`，9,244 requests、9,044 iterations、RPS 151.55、P95 8.95ms（product 8.35ms）、P99 16.63ms（product 15.26ms）、error 0、VU max 200、exit 0。
- 负向：run_id `neg-threshold`，exit 99，阈值 `flow_errors rate<0` / `server_errors rate<0` FAIL，归档 5 个文件 + 结果文档。

# Fix Report（独立审核修复，2026-10-26）

审核结论：Spec 功能面全部符合；修复 4 个 High/Medium 真实缺口（1 个提交，`fix: ...`）。本 worktree 仅做静态与只读验证，未跑 bench/k6/reset --yes，未动共享容器。

1. **High｜凭据出现在 argv（与脚本注释矛盾）**：`reset-lab.sh` 原 3 处 `curl -u "$USER:$PASS"`（overview/queue/purge）与 2 处 `compose exec -T -e MYSQL_PWD="$MYSQL_PASSWORD"`（读探针/写重置）均可被本机 `ps` 读到。改为：MySQL 密码写入 `mktemp` + `chmod 600` 的 `MYSQL_PWD=` 环境文件、经 `docker exec --env-file` 传递（argv 仅含非敏感的用户名）；RabbitMQ 认证经 `printf 'user = "…"' | curl --config -` 从 stdin 传入。两个临时文件由统一 EXIT trap 删除；不 `set -x`、错误输出不含密码（mysql/docker 错误只回显用户名）。`mysql` 由 `compose exec` 改为对已校验项目标签的容器 `docker exec`，项目边界不变。
2. **High｜stop 后失败留下 app 停机**：在 `compose stop` 前设 `APP_RECOVERY_NEEDED=true`，EXIT trap 在窗口内任何非零退出时自动 `compose start` 拉起 app 层并明示"数据重置未完成"，自动拉起失败则打印可复制的手工恢复命令；原始退出码不被掩盖（trap 内 `local rc=$?` 且不显式 exit）。
3. **Medium｜队列配错时先破坏后失败**：预检新增 `GET /api/queues/%2F/<queue>` 要求 200，另补 MySQL `SELECT 1`、`catalog_product` 非空、`redis-cli ping = PONG` 只读探针，全部在停 app 层之前完成；`--dry-run` 下这些仍是 WARN。purge 阶段 404 语义改为"队列中途消失"（不再可能掩盖配错的队列名）。
4. **Medium｜空 compose 项目标签被放行**：`[[ -n "$project" && "$project" != "$COMPOSE_PROJECT" ]]` 改为严格 `[[ "$project" != "$COMPOSE_PROJECT" ]]`（datastore 与 app 两个循环），同名非 compose 容器不再被放行。
5. **Medium｜`bench.sh --` 可覆盖内部变量**：`--` 后 `KEY=VALUE` 直接 export，`K6_IMAGE/K6_NETWORK/K6_DIR/PATH/RESET_*/BENCH_*` 等会造成"实际运行与 manifest 记录漂移"（如 `-- RESET_BASELINE_STOCK` 改了重置值但归档仍记 100）。新增保留名正则拒绝：`RUN_ID/SCENARIO/PATH/HOME/IFS/ENV/BASH_ENV/K6_(DIR|RUNNER|IMAGE|NETWORK|REPORT_DIR|REPORT_MOUNT|SUMMARY_EXPORT|FORWARD_ENV)/BENCH_*/RESET_*`；`ERROR_RATE/LOAD_DURATION/K6_PROMETHEUS_RW_*` 等场景变量不受影响。Prometheus 就绪检查由裸 `docker exec jl-nginx` 改为 `compose exec -T nginx`（服务名 `BENCH_NGINX_SERVICE` 可覆盖），不再按容器名直连。
6. 新增零依赖测试：`code/lab/scripts/test-lab-safety.sh`（bash 静态断言上述 1-5 的不变量，34 项全 PASS）与 `code/lab/scripts/bench-report.test.js`（node:test，5 项：summary 缺失仍出 FAIL 归档、日志 ✓/✗ 权威且压过 summary 布尔、HTML 转义、非法参数非零退出）。

验证（本 worktree 实际执行）：`bash -n` 三个脚本 + `node --check` 通过；`node --test bench-report.test.js` 5/5 pass；`test-lab-safety.sh` PASSED；`RESET_ENV_FILE=<主树 .env> reset-lab.sh --dry-run` exit 0，catalog=20/orders=2/queue ready=0 全部经新凭据通道读出（证明 `--env-file` 与 `--config -` 在真实栈上可用、队列预检 200 通过），临时凭据文件已清理。文档同步修正了本文第 29/38 行与"失败不静默"的设计描述及 `code/lab/k6/README.md` 凭据/覆盖说明。未修（Low）：reset 失败时 RUN_DIR 仅含 reset.log（调试保留，无伪造）；k6 summary 阈值布尔语义仍依赖日志解析兜底（已在报告注明）。
