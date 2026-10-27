> 步骤交接 · M3-2 应用 MySQL Router 客户端与 `MYSQL_MODE` 开关
> 日期：2026-10-27
> 分支 / HEAD：`worktree-agent-general-purpose-9a778a65`（自 `859ea7e`）/ `f47e60a`（feat）+ `f15951d`（test）+ 本文档 `docs:` 提交
> 关联任务：M3-2 本步（应用侧接入 MySQL Router）；上游 M1-4 读写路由数据源（`doc/process/Stage1/step-15-read-write-routing.md`）、M3 MySQL InnoDB Cluster/ Router 拓扑（`step-25a-mysql-cluster-compose.md`、`step-25b-mysql-live-bootstrap.md`）；下游为独立任务：集群状态指标轮询（`mysql_cluster_member_state` / `mysql_cluster_primary_info`）与 Router 活体切换验收
> 设计依据：`doc/design/Stage1/2026-10-26-ha-concurrency-lab-design.md` §8.3（Router 6446 读写 / 6447 只读、读写分离必须 Lazy 代理、dev 用单机 MySQL）、§3.3（环境变量切换、镜像不变）、§8.6（Router 单点风险）；`doc/design/工程规约.md` §1（配置外置）
> 测试 / compose：`mvn -pl services/seckill-app-jdk21 -am test` → **Tests run: 309, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**（基线 298 + 本步 11）；compose 不变式：默认 **16**、`--profile ha` **25**、`--profile db-ha` **25**
> 运行状态：本步**未触碰 Docker / 未连活体 Router / 未跑 MySQL 查询**（任务约束，且 HA 栈可能被其他 agent 使用）；所有断言均在构建出的配置层完成
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- 应用侧完成 `MYSQL_MODE=standalone|router` 单开关接入（默认 `standalone`）。命名与 `REDIS_MODE` 对齐：`app.datasource.mode` ← `MYSQL_MODE`，未知值启动失败；镜像是同一个，只换环境变量。
- `router` 模式下写池指向 MySQL Router **6446**、读池指向 **6447**；`standalone` 模式下两池保持原单机 `mysql` 端点，默认渲染行为不变。读写分离仍由 `ReadWriteRoutingDataSource` 按 `@Transactional(readOnly=true)` 决定，主 `DataSource` 仍是 `LazyConnectionDataSourceProxy`（**必须保留**：连接在事务标记发布后才取，否则只读事务会先拿到写连接）。
- 失败快：router 模式缺 `DB_WRITE_URL`/`DB_READ_URL` 直接启动失败，消息点名缺失的 env key，绝不静默回落到 dev MySQL（与 `REDIS_MODE=sentinel` 同哲学）；校验集中在 typed properties 的 `@PostConstruct`，没有散落的 if。
- compose 的 app env 已接线（仅 `compose.base.yml` 的 `x-app-env` 锚点，app-1/2/3 共用），`.env.example` 补键与注释；默认渲染服务数/行为不变。
- 本步边界内**未做**：集群状态指标轮询（`mysql_cluster_member_state` / `mysql_cluster_primary_info`）与 Router 活体切换验收——它们是独立的后续任务，不在本步伪装成完成。

# 完成功能项

## 1. `seckill/datasource/DataSourceAccessProperties`（新）

- `@ConfigurationProperties(prefix = "app.datasource")`：`mode`（`standalone|router`）+ `router.write-url` / `router.read-url`。镜像 `RedisAccessProperties` 的结构。
- `@PostConstruct validate()`：
  - 未知/空 mode → `IllegalStateException`，消息含 `app.datasource.mode`、两个合法值与 `MYSQL_MODE`；
  - `router` 模式缺任一端点 → 消息分别点名 `app.datasource.router.write-url` / `read-url`（env `DB_WRITE_URL` / `DB_READ_URL`、Router 端口 6446 / 6447），并明确 "refuses to fall back to the standalone dev MySQL"。
- `effectiveWriteUrl(...)` / `effectiveReadUrl(...)` 是唯一的端点选择点：standalone 返回已绑定的 dev URL，router 返回 Router URL。

## 2. `DataSourceConfig` 扩展

- `@EnableConfigurationProperties(DataSourceAccessProperties.class)`；`routingDataSource(...)` 增加该属性参数，在路由数据源装配前把生效 URL 写回两个 `HikariDataSource`（此时 Hikari 尚未建立任何连接，`setJdbcUrl` 安全）。standalone 下这一步是恒等变换。
- 启动日志固定一行（每实例一次）：`MySQL access mode: router (write endpoint jdbc:mysql://mysql-router:6446/seckill, read endpoint jdbc:mysql://mysql-router:6447/seckill)`。`endpoint()` 去掉查询串与 user-info，**不打印口令**；standalone 同样打印实际生效的 dev 端点。
- `LazyConnectionDataSourceProxy` / 路由键逻辑 / `dbHealthContributor` 全部保持原样，没有重设计。

## 3. `application.yml`（只动 datasource 段）

- 新增 `app.datasource.mode: ${MYSQL_MODE:standalone}`、`app.datasource.router.write-url: ${DB_WRITE_URL:}`、`read-url: ${DB_READ_URL:}`。
- **语义变化（有意）**：`write.jdbc-url` / `read.jdbc-url` 退回为纯 standalone dev 端点（`MYSQL_HOST`/`MYSQL_DATABASE` 占位），不再内嵌 `${DB_WRITE_URL:…}`。即 `DB_*_URL` **只在 router 模式被读取**；standalone 下即使 env 里有它们也不半切换（模式是开关，不是键是否存在）。默认 `.env` 不设置这些键，dev 端点与改动前逐字节一致。
- 池大小/超时此次未改（`10/2` 为 M1-4 存量硬编码，属工程规约存量债，未纳入本步）。

## 4. Compose 与 `.env.example`

- `compose.base.yml` 的 `x-app-env` 新增 `MYSQL_MODE: ${MYSQL_MODE:-standalone}`、`DB_WRITE_URL: ${DB_WRITE_URL:-}`、`DB_READ_URL: ${DB_READ_URL:-}`；仅 app-1/2/3 使用该锚点。
- `.env.example` 追加 "App MySQL CLIENT" 段：模式语义、两端点必填、如何先 `db-ha-up.sh` 再改 `.env`（`MYSQL_MODE=router`）并只重建三个 app；凭据仍走 `MYSQL_USER`/`MYSQL_PASSWORD`，不进 JDBC URL。
- 默认渲染仍 **16** 服务，三个 app 的有效端点不变；`--profile ha`（Redis Sentinel 叠加）不自动切 MySQL（那是 `.env` 的 `MYSQL_MODE` 决定的独立开关）；`--profile db-ha`/`--profile ha` 的 MySQL 层由 `db-ha-up.sh`/`compose.mysql.yml` 负责。

## 5. 测试（11 个新用例，Docker-free）

`MySqlModeSwitchTest`：真实 `application.yml` + 模拟 `systemEnvironment` 绑定两模式；默认 dev 端点；router 模式绑定 6446/6447；**只有模式能切换**（仅设 DB_* 键时仍 standalone）；未知 mode 拒绝；缺写/读 URL 分别拒绝；`ApplicationContextRunner` 构建真实 `DataSourceConfig` 后断言两个 Hikari 池的 `jdbcUrl` 就是 Router 端点（不建连接、`isRunning()==false`），主 bean 仍是 Lazy 代理；standalone 上下文忽略 Router 键；router 缺 URL 时上下文启动失败；路由键 readOnly→READ / 其余→WRITE；启动日志脱敏函数（去查询串与 user-info）。
既有 `ReadWriteRoutingTest` / `OrderQueryRoutingTest` 仅补 `app.datasource.mode=standalone`，H2 事务级路由断言原样通过。

# 设计逻辑

- **模式是唯一分叉点**：`MYSQL_MODE` 决定端点解析，键存在与否不决定；这与 Redis Sentinel 的教训一致（半配置状态最难讲清）。因此 standalone 显式忽略 Router 键，避免"一池走 dev、一池走 Router"。
- **失败快、连接懒**：配置层缺 URL 立即失败；运行层 Hikari 仍懒连接，Router/MySQL 未就绪不会阻塞启动，只影响首个需要连接的请求。端点写回发生在池启动之前，所以切模式不需要额外重启技巧。
- **Lazy 代理是契约不是装饰**：路由键在物理连接获取时解析；Spring 事务 begin 早于 readOnly 标记可见。去掉 Lazy 会让所有只读事务落写池——本步没有改这条链，测试继续钉住它。
- **日志可验收**：一条启动行同时给出模式与两端点，演练可直接 grep；脱敏逻辑只保留 scheme/authority/path，查询串（可能含 `password=`）与 `user:pass@` 都不会出现。
- **compose 仅换 env**：镜像不变（§3.3）。默认值 standalone 让 dev 渲染安全；router 端点默认为空，配合启动校验把"忘记设 URL"变成显式失败而不是悄悄连 dev。

# 存在缺陷项&TODO

- **未做（独立后续任务）**：chaos-controller 每 10s 用 mysql-shell 轮询集群状态并暴露 `mysql_cluster_member_state`、`mysql_cluster_primary_info`（§8.3 / §12）——本步未动 `chaos-controller`。
- **未做（独立后续任务）**：Router 活体故障切换验收（杀主 → 组复制选主 → Router 重定向 → 应用恢复）。本步按约束没有 Docker/活体 Router/MySQL 查询，**切换只在配置层与单测层验证**；不得把单测结果当作活体验收证据。
- **既有池参数硬编码**：`app.datasource.write/read` 的 `maximum-pool-size: 10`、`minimum-idle: 2` 仍是 M1-4 遗留，未按工程规约 §1 外置（本步不扩面修改，避免行为漂移）。
- **HA 渲染与 MySQL 模式解耦**：`up.sh --profile ha` 只叠加 Redis Sentinel，不会自动把 `MYSQL_MODE` 置为 router；切换 MySQL 需显式改 `.env`。这是 compose 文件归属（`compose.mysql.yml` 不在本步范围）与内存预算的现状，不是缺陷，但演示脚本需按交接语义操作。
- **standalone 不再读取 `DB_*_URL`**：此前 yml 的占位符会让这两个键在任何模式生效，现在仅 router 模式读取。当前仓库/默认 `.env` 均未设置它们，故 dev 行为不变；若有人手工在 standalone 下设置过，需要改用 `MYSQL_HOST`/`MYSQL_DATABASE` 或显式 `app.datasource.write.jdbc-url`。

# 待定事项和注意事项

## 精确切换语义

| env | yml 键 | 默认 | 何时生效 | 缺失/非法时 |
|---|---|---|---|---|
| `MYSQL_MODE` | `app.datasource.mode` | `standalone` | 总是；唯一决定端点选择 | 非 `standalone`/`router` → 启动失败，消息含 `MYSQL_MODE` |
| `DB_WRITE_URL` | `app.datasource.router.write-url` | 空 | 仅 `router`；写池 = Router 6446（读写、跟随当前主） | router 下为空 → 启动失败，点名 `DB_WRITE_URL`，拒绝回落 dev |
| `DB_READ_URL` | `app.datasource.router.read-url` | 空 | 仅 `router`；读池 = Router 6447（只读、分摊到从节点） | router 下为空 → 启动失败，点名 `DB_READ_URL`，拒绝回落 dev |

- `standalone`：两池均使用 `jdbc:mysql://${MYSQL_HOST:mysql}:3306/${MYSQL_DATABASE:seckill}?...`，与改动前的 dev 渲染一致；`DB_*` 被忽略。
- `router`：写池 6446、读池 6447；`@Transactional(readOnly=true)` 仍走读池，其余（含无事务、Flyway、健康检查）走写池。
- 进入演示：先 `bash code/lab/scripts/db-ha-up.sh`（`jl-mysql-router` healthy、6446/6447 可连），再把 `.env` 的 `MYSQL_MODE`/`DB_WRITE_URL`/`DB_READ_URL` 设好，只重建 `app-1/2/3`；启动日志 grep `MySQL access mode` 确认。
- 退出演示：`.env` 改回 `MYSQL_MODE=standalone`（或删掉三键）并重建 app；不需要重建镜像或 MySQL 层。

## 风险与演示口径

- **Router 是单点**（设计 §8.6 已接受）：Router 挂掉时 6446/6447 都不可用，应用表现为连接失败/请求错误；租户级高可用不在本实验范围。
- **6447 在从节点间分摊**：组复制是异步复制，读路由可能读到滞后数据；**read-your-writes 在只读路由上不保证**。M1-4 已把订单幂等重试与订单查询固定在写池；商品详情允许短暂陈旧，教学时按"读副本延迟"口径讲解。
- **Router 依赖已 bootstrap 的集群元数据**：`mysql-router-init` 未完成或集群无 ONLINE 从节点时，6447 的行为取决于 Router 的 destination 策略；本步未做活体验证，演示前先看 `db-cluster-status.sh` 与 Router healthcheck。
- **日志安全**：启动行只输出 scheme/authority/path，不带查询串/user-info；`.env` 的数据库口令只从 `MYSQL_PASSWORD` 读取，未打印、未提交。
- **回滚面**：本步只改 app datasource 段 + 三个 env 键；回滚 feat 提交即可回到"两池都读 `DB_*` 占位符"的旧语义（不推荐，旧语义没有模式校验）。

# 附录

## 关键文件

- 主代码：`code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/datasource/`（`DataSourceAccessProperties` 新增、`DataSourceConfig` 扩展）
- 配置：`code/services/seckill-app-jdk21/src/main/resources/application.yml`（datasource 段）、`code/lab/docker/compose.base.yml`（`x-app-env`）、`code/lab/docker/.env.example`
- 测试：`.../src/test/java/com/javalearn/lab/seckill/datasource/MySqlModeSwitchTest.java`、`.../src/test/java/com/javalearn/lab/routing/{ReadWriteRoutingTest,OrderQueryRoutingTest}.java`
- 上游证据：`doc/process/Stage1/step-15-read-write-routing.md`、`step-25a-mysql-cluster-compose.md`、`step-25b-mysql-live-bootstrap.md`

## 复现命令与实测结果

```bash
cd /d/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-9a778a65
export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1"
(cd code && mvn -pl services/seckill-app-jdk21 -am test)
# → Tests run: 309, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS

cd code/lab/docker
# 本 worktree 无 .env（gitignored），按 up.sh 的做法从 .env.example 复制一份后渲染
cp .env.example .env
docker compose -f compose.base.yml -f compose.dev.yml config --services | wc -l                 # 16
docker compose -f compose.base.yml -f compose.dev.yml -f compose.ha.yml --profile ha config --services | wc -l    # 25
docker compose -f compose.base.yml -f compose.dev.yml -f compose.mysql.yml --profile db-ha config --services | wc -l  # 25
```

- 默认渲染下 app-1/2/3 的 `MYSQL_MODE: standalone`、`DB_WRITE_URL: ""`、`DB_READ_URL: ""`（`docker compose config` 实测，有效端点仍是 `mysql:3306/seckill`）。
- `MySqlModeSwitchTest` 实测日志：`MySQL access mode: router (write endpoint jdbc:mysql://mysql-router:6446/seckill, read endpoint jdbc:mysql://mysql-router:6447/seckill)`。
- **未执行**：活体 Router 连接、`mysql_cluster_*` 指标、故障切换演练、`verify.sh`/`smoke.sh`（它们需要运行中的栈；本步按约束不碰 Docker）。
