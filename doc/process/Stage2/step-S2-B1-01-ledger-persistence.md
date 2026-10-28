# 步骤交接：S2-B1-01 台账持久化（`lab_ledger` + Flyway + 状态机/恢复/暂存/保留）

> 任务/步骤：Stage2 B1-01 —— run/operation/配置快照/故障事件/审计落到栈内 MySQL 的独立台账库 `lab_ledger`；Flyway 版本化 10 张表；状态机与重启恢复；写入可暂存/补写；保留最近 50 个 run
> 日期：2026-10-28
> 分支 / HEAD：`feature/stage2-workbench` 基线 `103f835`（B0 收口）；本步提交 `83c1b07`（实现/配置）、`9b859ad`（测试），交接文档一笔见 `git log` 最新
> 关联任务：`doc/process/Stage2/stage2-开发测试与验收计划.md` §2 B1-01；`doc/design/Stage2/stage2改造方案.md` §10.5/§10.3/§10.1；验收 A21
> 测试：`mvn -pl services/chaos-controller -am test` → chaos-controller **333 tests / 0 fail / 0 error / 0 skip**（基线 296，本步 +37）；platform-common 10、platform-observability 4
> 全量：`mvn test`（reactor）→ **888 tests**（10 + 4 + 13 + 469 + 59 + 333）/ BUILD SUCCESS，EXIT=0
> 静态安全：`bash code/lab/scripts/test-lab-safety.sh` → `LAB SAFETY TESTS PASSED.`
> 运行状态：只跑单测/Testcontainers（本机 Docker 起临时 `mysql:8.4.4`），**未起共享 lab 栈、未执行任何 compose/up/down/verify/reset 命令**；真机重启演练与 db-ha 演练留主会话（见 §存在缺陷项&TODO）

# 当前进展

- `lab_ledger` 已作为独立数据库落地：库名、10 张表、Flyway `V1` 与方案 §10.5 定稿清单逐项一致；与业务库 `seckill` 同实例、物理分库，保留策略只动 `lab_run`（子表 `ON DELETE CASCADE`）。
- 控制器启动不再依赖 MySQL：两个 Hikari 池 `initialization-fail-timeout=-1`，建库/Flyway 在自有单线程 daemon 调度器上重试；失败只把台账状态置 `DEGRADED` 并 WARN，Spring 上下文照常启动（全上下文测试已用不可达的默认 `mysql:3306` 验证）。
- 重启恢复：`RUNNING/STOPPING/VERIFYING → UNKNOWN` 并写 `lab_run_step`；`REQUESTED/PENDING_CONFIRMATION` 操作 → `UNKNOWN`；compare-and-set，重复执行零副作用。
- 写入失败不拖垮动作：`LedgerWriter` 把失败写入转为本地 JSON 文件（原子写），按序补写；补写成功先写 `lab_ledger_stash` 审计行（`flushed_at` 非空）再删文件；超过文件上限丢最旧并 WARN；目录挂命名卷 `jl-ledger-stash-data`，控制器重启不丢。
- 对外 API：`POST /lab/runs`、`GET /lab/runs`、`GET /lab/runs/{runId}`、`GET /lab/ledger/status`、`GET /lab/ledger/recovery`；沿用 `Result`/`LabExceptionHandler`/`TokenFilter`，新增 50302 台账不可用码。
- 本步未实现：B1-02 幂等键/环境锁执行流程、B1-03 Prometheus 聚合、B2 k6；`lab_env_lock` 只建表 + 基础仓储。

# 完成功能项

## 依赖与测试闸门

- `chaos-controller/pom.xml`：新增 `spring-boot-starter-jdbc`、`com.mysql:mysql-connector-j`(runtime)、`flyway-core`、`flyway-mysql`；test 增加 `testcontainers:mysql`、`testcontainers:junit-jupiter`（版本由 Boot BOM 管理）。
- surefire 增加 `**/*Test.java` + `**/*IT.java`（与 `seckill-app-jdk21` 同口径），`LedgerSchemaIT` 真进 `mvn test`。

## 配置（27 个新键，三处默认值一致）

`application.yml` 的 `lab.ledger.*` 全部用 `${ENV:default}` 声明；`compose.base.yml` 的 chaos-controller 服务 environment 与 `.env.example` 同步。完整表见附录 C。

- 账号口径（已决策）：`LAB_LEDGER_USER=root`，密码默认跟随 `MYSQL_ROOT_PASSWORD`。理由：业务账号 `seckill` 在 standalone/集群里只有 `seckill.*` 权限，建 `lab_ledger` 需要 `CREATE`；新增数据库账号意味着改 init 脚本并扩大凭据面。chaos-controller 本来就挂载 `docker.sock`（root 等价），因此 root 不扩大实际边界。密码只进环境变量，不写 JDBC URL、不写日志。
- 拓扑：`LAB_LEDGER_MYSQL_MODE` 默认 `${MYSQL_MODE:standalone}`，与 app 层和 `reset-lab.sh` 同一开关；standalone 解析 `mysql:3306`，db-ha 解析 `mysql-router:6446`（读写口、跟随 PRIMARY）。`.env.example` 刻意把 `LAB_LEDGER_MYSQL_MODE` 与 `LAB_LEDGER_PASSWORD` 注释掉，保证 `MYSQL_MODE=router` / 改 root 密码时台账自动跟随。
- 池/超时：`max-pool-size`、`min-idle`、`connection-timeout-ms`、`validation-timeout-ms`；建库重试 `bootstrap-retry-seconds`；暂存目录/文件数/单文件字节/补写间隔；保留 run 数与执行间隔；列表分页默认/上限；单 JSON 载荷上限；run-id 冲突重试次数。
- 新增 `jl-ledger-stash-data` 命名卷，挂到 `/app/ledger-stash`；chaos-controller **未加** `depends_on: mysql`。

## Schema（`src/main/resources/db/ledger/V1__lab_ledger.sql`）

10 张表全部 `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci`：

| 表 | 关键点 |
|---|---|
| `lab_run` | `run_id` VARCHAR PK；`(status, created_at)`、`(scenario, created_at)` 索引；`operator`/`source_commit`/UTC `DATETIME(3)` |
| `lab_run_step` | 状态机时间线，FK 级联；`detail` JSON |
| `lab_run_env` | 镜像/Java/JVM/GC 快照，JSON 列 |
| `lab_run_param` | 参数与配置快照，`payload` 原生 JSON |
| `lab_operation` | 操作契约，状态字符串枚举；`run_id` 可空，FK 级联 |
| `lab_fault_event` | 故障事件流，`event` 为保留字已反引号 |
| `lab_metric_window` | 观测窗 + `data_quality`，JSON stats |
| `lab_run_check` | 对账判定 `PASS/FAIL/INFO` |
| `lab_ledger_stash` | 补写审计；`flushed_at` 可空 |
| `lab_env_lock` | 写型 run 互斥锁（本步只建表 + 基础仓储） |

- run id：`run-yyyyMMdd-NNN`，由注入的 `Clock`（`Clock.systemUTC()`）取 UTC 日期，唯一约束冲突时用下一个序号重试（`LAB_LEDGER_RUN_ID_MAX_ATTEMPTS`）。
- JSON 载荷在服务侧递归拦截 password/passwd/secret/token/credential/apiKey 类键，直接 400，不写入、不做摘要；不存完整缓存值。

## 代码（新包 `com.javalearn.lab.chaos.ledger`）

- `LedgerProperties`：27 键绑定 + 启动校验，错误信息同时点名属性与 env。
- `LedgerTargetResolver`：mode→URL；校验 JDBC 前缀、host、库名与 `lab.ledger.schema` 一致；派生不带库名的服务器级 URL。
- `LedgerConfig`：服务器级/库级两个 Hikari DataSource + 两个 JdbcTemplate + 显式 Flyway Bean（`classpath:db/ledger`、`defaultSchema=lab_ledger`、`createSchemas=false`）+ 单线程 daemon 调度器 + 仓储/写入/恢复/保留/服务 Bean。`ChaosControllerApplication` 排除 Flyway 自动配置与通用 DB health contributor（后者会让 MySQL 被杀时 `/health` 变 503，进而让控制器容器 unhealthy）。
- `LedgerBootstrap`：`CREATE DATABASE IF NOT EXISTS` → Flyway → 仅首次成功时跑一次恢复 → 保留 → `OK`；失败 `DEGRADED` + 定时重试。状态 `OK/DEGRADED/NO_DATA`；`ApplicationRunner` 只投递任务，不阻塞启动。
- `RunStatus` / `OperationStatus`：完整合法转移表；`UNKNOWN` 不在任何转移目标集，只由恢复写入；`UNKNOWN→FAILED/ABORTED` 为运维收口。
- `RunLedgerRepository`：纯 `JdbcTemplate`（无 JPA），覆盖 10 张表的基础读写、compare-and-set 状态更新、保留删除、stash 审计、env lock 基础方法。
- `RunLedgerService`：创建/列表/详情/状态/恢复视图、run-id 生成、分页 clamp（默认 50 / 上限 200 外置）、载荷校验。
- `LedgerWriter`：`DataAccessException` → 暂存（调用方不失败）；`DuplicateKeyException` 上抛给 run-id 重试；记录 `lastWriteAt/lastError`。
- `LedgerStash`：原子写、按文件名（时间+序号）排序补写、幂等 apply（已存在 run 跳过、已到目标状态跳过）、审计行 + 删文件、超上限淘汰最旧、补写失败保留当前及后续文件、损坏文件隔离为 `.corrupt`。
- `LedgerRecovery`：重启扫描两类未决状态；只更新源状态，重复执行幂等。
- `LedgerRetention`：`DELETE FROM lab_run WHERE run_id NOT IN (newest N)`，子表级联。
- `LedgerController` + `LedgerViews`：5 个只读/创建端点。
- `LabErrorCode.LEDGER_UNAVAILABLE=50302`；`LabExceptionHandler` 增加 503/404/400/409 映射。

## REST 契约

| 方法 | 路径 | 行为 |
|---|---|---|
| `POST` | `/lab/runs` | body `{scenario, params?}` → 201 `{runId,status:"DRAFT",createdAt}`；写 `lab_run` + `lab_run_param`（group=`request`） |
| `GET` | `/lab/runs?scenario=&status=&limit=&offset=` | `items,total,limit,offset`；limit 上限外置 |
| `GET` | `/lab/runs/{runId}` | run + steps + env + params + checks；未知 id → 404/40401 |
| `GET` | `/lab/ledger/status` | `state,schemaVersion,pendingStash,lastWriteAt,lastError,mode`；DEGRADED 仍 200 |
| `GET` | `/lab/ledger/recovery` | 当前 UNKNOWN run、未确认操作、清理建议；只读 |

`TokenFilter` 已覆盖全部非公开路径，未改白名单。

# 设计逻辑

- **为什么 root**：见上。`seckill` 账号没有跨库 `CREATE`；另建账号需要修改 `mysql/init` 并再分发一份凭据。台账库与业务库同实例，root 只被控制器进程使用，不暴露宿主机。
- **为什么 MySQL 不可用仍要启动**：杀主演练会命中台账写入窗口，控制器若因 MySQL 启动失败，恢复页和故障操作也会一起消失。Hikari 的 `initialization-fail-timeout=-1` 让“建池”不等于“连库”；`LedgerBootstrap` 的后台重试把失败收敛成 `DEGRADED`。`/health` 只代表控制器进程，台账健康看 `/lab/ledger/status`。
- **恢复只做一次**：首次成功迁移后跑一次 `LedgerRecovery`；之后因 MySQL 短暂不可用触发的重试**不会**再跑恢复，否则会把仍在运行的 run 误标 UNKNOWN。恢复本身幂等，主会话手工重跑也安全。
- **暂存/补写顺序**：一个事件一个文件，文件名 `stash-<epochMillis>-<seq>.json`；补写按文件名排序，遇到第一个失败就停，避免后一个事件越过前一个（例如 transition 越过 create）。补写幂等：run 已存在则只补缺失的 params；目标状态已达成则跳过。审计行在 apply 成功后写，写成功才删文件；极端情况下（审计成功但删文件失败）下次会重复一条审计，不重复业务副作用。
- **保留与级联**：只删 `lab_run`，所有子表 FK `ON DELETE CASCADE`；`lab_ledger_stash` 是审计流水，不随 run 删除（它没有 run FK），保留策略不清理它。启动成功后立即执行一次，之后按 `retention-interval-minutes`。
- **时间与 id**：所有时间 `Instant` → UTC `DATETIME(3)`；run id 日期只用注入 Clock 的 UTC，不读系统默认时区。
- **为什么不给 run+params 包事务**：MySQL 不可用时写入本就允许半成功；暂存事件按“run 存在但 params 缺失”补写，比强事务更符合“动作不能失败”的目标。状态转移用 compare-and-set，避免并发覆盖。

# 存在缺陷项&TODO

- **真机重启演练未做**（本 worktree 纪律禁起共享栈）：需要主会话重建镜像后创建一笔 DRAFT/RUNNING run、重启 chaos-controller、确认 `UNKNOWN` + `lab_run_step` 与 `/lab/ledger/recovery` 清理项（A21）。
- **db-ha 经 Router 6446 的真机写入未做**：单测用 Testcontainers 直连，URL 解析与 compose 渲染已验证，但未在 `MYSQL_MODE=router` 的共享栈上落库。
- **杀主写入窗口未真机演练**：暂存/补写路径由单测用“拒绝连接的 DataSource”验证；真实 `docker stop` 主库期间的时序未验。
- `lab_operation` / `lab_fault_event` / `lab_metric_window` / `lab_run_check` 目前只有表 + 仓储方法，业务写入归 B1-02/B1-03/B2；本步不生成这些行。
- `lab_env_lock` 只有建表和基础 CRUD，抢锁流程归 B1-02。
- 台账 `DEGRADED/NO_DATA` 时 `POST/GET /lab/runs*` 统一 503（按任务要求），因此“完全断库时新建 run 走暂存”不会经 REST 发生；暂存主要保护已在执行中的写入（B1-02/B2 接入后）。mid-call 失败仍会暂存并返回 201，补写前详情 404。
- `sourceCommit` 默认 `unknown`；主会话可在 `.env` 设 `LAB_SOURCE_COMMIT`。
- **行尾说明**：`compose.base.yml`、`.env.example`、`application.yml` 在基线的 git index 中就是 CRLF。本步按“修改既有文件先确认原行尾”的要求沿用 CRLF，未整体翻转（否则会产生整文件 diff）；所有新增 Java/SQL/测试/文档均为 LF。若主会话要求 compose 全 LF，应单独做一笔纯行尾提交。

# 待定事项和注意事项

- **主会话真机前需要**：
  1. 重建/重启 `chaos-controller`（新依赖 + 新镜像），compose 会自动创建 `jl-ledger-stash-data` 卷；**不需要**手工建库，首次启动会 `CREATE DATABASE lab_ledger` 并跑 Flyway。
  2. 确认容器 env 中 `LAB_LEDGER_MYSQL_MODE` 与当前 `MYSQL_MODE` 一致（默认跟随）；db-ha 演练前把 `MYSQL_MODE=router`，台账自动走 `mysql-router:6446`。
  3. 若 `.env` 改过 `MYSQL_ROOT_PASSWORD`，不要单独写死 `LAB_LEDGER_PASSWORD`；保持注释即自动跟随。
  4. 真机重启演练：先 `POST /lab/runs` 造一条 DRAFT（当前无 start 端点，UNKNOWN 需要 B1-02 或手工 SQL 把 status 置 RUNNING），重启控制器后看 `/lab/ledger/status` 与 `/lab/ledger/recovery`。
- 不建议把 `lab_ledger` 加进 `reset-lab.sh`：它不是业务演示数据；清空会丢失审计。若需要，另加显式开关。
- 合并后请复跑全量闸门；本步未改 `claims.yml`、未改计划 §0 基线数字/状态列（按约定由主会话回写）。
- 下一步：B1-02 操作契约/幂等/环境锁会直接复用 `LedgerWriter.transition`、`lab_operation` 与 `lab_env_lock` 基础方法；B1-03 往 `lab_metric_window` 写窗口聚合。

# 附录

## A. 命令与结果（本 worktree）

```bash
# 模块闸门（含 Testcontainers *IT）
cd code
mvn -pl services/chaos-controller -am test
# chaos-controller: Tests run: 333, Failures: 0, Errors: 0, Skipped: 0
# platform-common: 10 / platform-observability: 4；BUILD SUCCESS，EXIT=0

# 全量 reactor
mvn test
# 10 + 4 + 13 + 469 + 59 + 333 = 888，BUILD SUCCESS，EXIT=0

# 静态安全（不碰栈）
bash code/lab/scripts/test-lab-safety.sh
# LAB SAFETY TESTS PASSED.

# compose 渲染校验（只读，不起容器）
docker compose --env-file code/lab/docker/.env.example \
  -f code/lab/docker/compose.base.yml config
# 27 个 LAB_LEDGER_* 全部展开，默认值见附录 C；卷 jl-ledger-stash-data 已挂载
```

## B. 提交

| sha | 内容 |
|---|---|
| `83c1b07` | `feat(B1-01): persist runs to the lab_ledger schema with Flyway`（实现/配置/DDL/volume） |
| `9b859ad` | `test(B1-01): cover stash replay and restart recovery`（7 个测试类，37 用例） |
| 本笔 | `docs(B1-01): hand off the ledger persistence step` |

## C. 新增配置键（27）

| 键 | env | 默认 |
|---|---|---|
| `lab.ledger.enabled` | `LAB_LEDGER_ENABLED` | true |
| `lab.ledger.mysql-mode` | `LAB_LEDGER_MYSQL_MODE` | `${MYSQL_MODE:standalone}` |
| `lab.ledger.standalone-url` | `LAB_LEDGER_STANDALONE_URL` | `jdbc:mysql://${MYSQL_HOST:mysql}:3306/lab_ledger?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC` |
| `lab.ledger.router-url` | `LAB_LEDGER_ROUTER_URL` | `jdbc:mysql://${MYSQL_ROUTER_HOST:mysql-router}:6446/lab_ledger?...` |
| `lab.ledger.schema` | `LAB_LEDGER_SCHEMA` | lab_ledger |
| `lab.ledger.user` | `LAB_LEDGER_USER` | root |
| `lab.ledger.password` | `LAB_LEDGER_PASSWORD` | `${MYSQL_ROOT_PASSWORD:labroot}` |
| `lab.ledger.source-commit` | `LAB_SOURCE_COMMIT` | unknown |
| `lab.ledger.operator` | `LAB_LEDGER_OPERATOR` | local |
| `lab.ledger.max-pool-size` | `LAB_LEDGER_MAX_POOL_SIZE` | 4 |
| `lab.ledger.min-idle` | `LAB_LEDGER_MIN_IDLE` | 1 |
| `lab.ledger.connection-timeout-ms` | `LAB_LEDGER_CONNECTION_TIMEOUT_MS` | 3000 |
| `lab.ledger.validation-timeout-ms` | `LAB_LEDGER_VALIDATION_TIMEOUT_MS` | 1000 |
| `lab.ledger.bootstrap-retry-seconds` | `LAB_LEDGER_BOOTSTRAP_RETRY_SECONDS` | 15 |
| `lab.ledger.bootstrap-thread-name` | `LAB_LEDGER_BOOTSTRAP_THREAD_NAME` | ledger-bootstrap |
| `lab.ledger.flyway-locations` | `LAB_LEDGER_FLYWAY_LOCATIONS` | classpath:db/ledger |
| `lab.ledger.stash-dir` | `LAB_LEDGER_STASH_DIR` | /app/ledger-stash |
| `lab.ledger.stash-max-files` | `LAB_LEDGER_STASH_MAX_FILES` | 500 |
| `lab.ledger.stash-max-bytes` | `LAB_LEDGER_STASH_MAX_BYTES` | 262144 |
| `lab.ledger.stash-replay-interval-seconds` | `LAB_LEDGER_STASH_REPLAY_INTERVAL_SECONDS` | 20 |
| `lab.ledger.retention-runs` | `LAB_LEDGER_RETENTION_RUNS` | 50 |
| `lab.ledger.retention-interval-minutes` | `LAB_LEDGER_RETENTION_INTERVAL_MINUTES` | 30 |
| `lab.ledger.recovery-enabled` | `LAB_LEDGER_RECOVERY_ENABLED` | true |
| `lab.ledger.query-default-limit` | `LAB_LEDGER_QUERY_DEFAULT_LIMIT` | 50 |
| `lab.ledger.query-max-limit` | `LAB_LEDGER_QUERY_MAX_LIMIT` | 200 |
| `lab.ledger.max-payload-bytes` | `LAB_LEDGER_MAX_PAYLOAD_BYTES` | 65536 |
| `lab.ledger.run-id-max-attempts` | `LAB_LEDGER_RUN_ID_MAX_ATTEMPTS` | 10 |

## D. 测试分布（新增 37）

| 类 | 用例 | 覆盖 |
|---|---|---|
| `RunStatusTransitionTest` | 7 | 全转移表、终态、UNKNOWN 不可由转移产生、UNKNOWN→FAILED/ABORTED、未结束集合 |
| `LedgerPropertiesTest` | 9 | 真实 yml 默认值、27 个 env 键存在、env 覆盖、非法 mode/空 URL/库名不符/池与超时越界、server URL 派生 |
| `LedgerSchemaIT` | 4 | 10 表 InnoDB/utf8mb4、JSON 读写、run 往返（毫秒 UTC）、run_id 唯一冲突重试 |
| `LedgerStashTest` | 4 | 仓储异常→落盘且调用方不失败、补写成功+审计 `flushed_at`+删文件、超上限淘汰最旧、补写失败保留 |
| `LedgerRecoveryTest` | 3 | 三种未结束状态→UNKNOWN+step、终态/DRAFT 不动、操作 REQUESTED/PENDING→UNKNOWN、幂等 |
| `LedgerRetentionTest` | 2 | 超 N 旧 run 级联删除、低于上限零变更 |
| `LedgerControllerTest` | 8 | create 201/list/detail/404/DEGRADED 503/状态 200/恢复视图 |

## E. 关键文件

- 实现：`code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/ledger/`
- DDL：`code/services/chaos-controller/src/main/resources/db/ledger/V1__lab_ledger.sql`
- 配置：`.../src/main/resources/application.yml`、`code/lab/docker/compose.base.yml`、`code/lab/docker/.env.example`
- 测试：`.../src/test/java/com/javalearn/lab/chaos/ledger/`

## F. 补充：主会话真机演练（2026-10-28，合并 `8d93d6e` 后）

本节的实测结果关闭了上文「存在缺陷项&TODO」中的真机三项（A21、Router 6446 写入、镜像重建），仅"杀主写入窗口时序"仍只有单测证据。

### F.1 镜像与控制器重建

- 现场栈：`compose.base.yml + compose.dev.yml`（`MYSQL_MODE=standalone`），34 容器健康。
- **构建口径偏差（环境所致，非代码缺陷）**：`docker compose build chaos-controller` 在 builder 容器内连 Maven Central 失败
  （`SSL peer shut down incorrectly`，host 网络模式同样失败；本机 Maven 正常）。改用同一 Maven reactor 在
  `maven:3.9-eclipse-temurin-21` 内离线构建（挂载本机 `~/.m2`）：
  `mvn -o -B -pl services/chaos-controller -am -DskipTests package` → BUILD SUCCESS（JDK 21 编译通过）。
  随后用仅含运行时阶段的临时 Dockerfile（`tmp/ledger-image/`，未入库）把同一个 jar 打成
  `java-learn-lab/chaos-controller:jdk21`，imageId **`5a6ae18d9aa4`**。仓库内 Dockerfile 未改动；网络恢复后
  应按原 Dockerfile 重建一次以恢复构建溯源（不影响 B0 的 tag+imageId 最小闭环口径）。
- `up -d --no-deps --force-recreate chaos-controller`：自动创建卷 `jl-ledger-stash-data`；容器 healthy 后
  `LAB_LEDGER_MYSQL_MODE=standalone`、`LAB_LEDGER_STANDALONE_URL=jdbc:mysql://mysql:3306/lab_ledger?...`。

### F.2 台账落地（standalone）

```
GET /lab/ledger/status → {"state":"OK","schemaVersion":"1","pendingStash":0,"mode":"standalone"}
jl-mysql: information_schema 统计 lab_ledger 共 11 张表（10 张台账表 + flyway_schema_history）
flyway_schema_history: V1 "lab ledger" success=1
```

### F.3 A21 重启恢复（真机 PASS）

| 步骤 | 结果 |
|---|---|
| `POST /lab/runs {"scenario":"b1-a21-drill","params":{"vus":10,...}}` | 201 `run-20261028-001` / `DRAFT` |
| SQL 置 `RUNNING`（本步尚无 start 端点） | `started_at=03:43:51.623` |
| `docker restart jl-chaos-controller` → healthy | — |
| `GET /lab/runs/run-20261028-001` | `status=UNKNOWN`，step `recovery RUNNING→UNKNOWN`，`detail.reason=controller restart`，params 快照仍在 |
| `GET /lab/ledger/recovery` | `unknownRuns` 含该 run；`cleanupSuggestions` 给出"检查残留 k6/traffic 与 toxic 后按 FAILED/ABORTED 收口" |

### F.4 db-ha 口径（Router 6446，真机 PASS）

以 `LAB_LEDGER_MYSQL_MODE=router` 覆盖重建控制器（shell env 优先于 `.env`）：

```
GET /lab/ledger/status → {"state":"OK","mode":"router"}          # 经 mysql-router:6446 建库+迁移成功
POST /lab/runs {"scenario":"b1-router-drill"} → run-20261028-001  # 新库独立序号
jl-mysql (standalone) lab_ledger.lab_run:  只有 b1-a21-drill / UNKNOWN   ← 未被写入
jl-mysql-2 (PRIMARY)  lab_ledger.lab_run:  b1-router-drill / DRAFT       ← 经 Router 落到集群
```

随后不带覆盖重建回 `standalone`，`GET /lab/runs` 仍能读到原 UNKNOWN run。两套拓扑各自物理隔离，符合"台账库与业务库物理分库"。

### F.5 回归闸门（重建后）

| 闸门 | 结果 |
|---|---|
| `mvn -pl services/chaos-controller -am test`（主树复跑） | 333 / 0 fail / 0 error / 0 skip |
| `smoke.sh` | 7/7，`SMOKE PASSED.` |
| `verify.sh` | **109 PASS / 1 WARN / 0 FAIL**（唯一 WARN 仍是 cAdvisor/WSL2） |
| `test-chaos-env-wiring.sh` | 扩展为"8 个 CHAOS 键 + 27 个台账键"，全 PASS |

### F.6 遗留（有意保留）

- 演练数据未清理：standalone `run-20261028-001`（UNKNOWN，A21 样本）与集群 `b1-router-drill`（DRAFT，Router 样本），
  保留作 B1-05 台账页的实样；保留策略 50 run 会自动轮换。
- 杀主写入窗口的**真实时序**仍未演练（暂存/补写只有单测）：需要 `docker stop` 掉业务主库，影响演示链路，留 B1 收口或专项演练。
- 保留策略（50 run 级联删除）只有单测，未在真机造 51 笔 run。
