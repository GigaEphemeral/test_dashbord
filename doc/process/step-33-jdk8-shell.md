> 步骤交接 · Step 33 JDK 8 外壳（D7 双 JDK 对比的 8 侧）
> 日期：2026-10-27
> 分支 / HEAD：`worktree-agent-general-purpose-be0d16bc`（自 `91eb112`）/ `feat: add Boot 2.7 / Java 8 seckill shell for the dual-JDK lab` + `test: pin the JDK 8 shell read-path contract and externalized config` + 本文档 `docs:` 提交
> 关联任务：M5 双 JDK（设计 §10）；上游：JDK 21 外壳（Task 13–17、M3-1b）、共享 MySQL/Flyway 种子；下游：D7 面板、compose 第二运行时服务、k6 双目标
> 设计依据：`doc/design/2026-10-26-ha-concurrency-lab-design.md` §10.1（`seckill-app-jdk8` = Temurin 8 + Spring Boot 2.7.18，仅实验用途、标注 EOL）、§10.2（相同限额/同一套 k6 场景，记录吞吐/P99/GC/堆/线程/启动耗时）、D7（§6 面板表：JDK 8 vs 21）；`doc/design/工程规约.md` 第 1 节（配置外置）、第 2 节（五段式交接）
> 测试 / verify：`mvn -q -pl services/seckill-app-jdk8 -am test` → **exit 0，Tests run: 11, Failures: 0, Errors: 0, Skipped: 0**（`ProductApiTest` 5、`ActuatorEndpointsTest` 3、`ExternalizedConfigTest` 3；兄弟模块 `platform-common` 10、`seckill-core` 13 同批通过）；`mvn -q -pl services/seckill-app-jdk8 -am -DskipTests package` → **exit 0**，`target/seckill-app-jdk8-0.1.0-SNAPSHOT.jar`（44,030,521 B，repackage 后的可执行 jar，class 文件版本 52）
> 运行状态：**本机 JRE 8 实跑通过并已退出**（`java -version` = 1.8.0_503；`SERVER_PORT=18081 APP_PROFILE=demo` 启动 → `Started SeckillJdk8Application in 6.302 seconds (JVM running for 6.837)`；curl：`/actuator/health` 200 UP、`/actuator/health/app` 200 UP、`/api/products/1` 200、`/api/products/20` 200、`/api/products/9999` 404、`/actuator/prometheus` 200 / 66,972 B / 452 行）；`taskkill` 后 18081 无监听、`git status --short` 只剩本步文件；**未启动任何容器、未碰 Docker 栈、未改 compose/k6/dashboard**

# 当前进展

- `services/seckill-app-jdk8` 从"只有 parent + artifactId 的空骨架"变成**可启动、可观测、与 JDK 21 外壳读路径同构的 Spring Boot 2.7.18 / Java 8 应用**。D7 面板的 8 侧数据源已经就位：吞吐/P99/GC/堆/线程/启动耗时全部由 `/actuator/prometheus` 暴露，指标名与 JDK 21 侧一致。
- 范围按任务边界**刻意收窄**：只有热读路径（商品 + hello）与指标。缓存 L1/L2、穿透防御、线程实验室、toggles、异步秒杀、登录、订单、Flyway、Redis/RabbitMQ/Resilience4j 全部不在本步。
- 两种运行 profile 已实测：`demo`（H2 内存，零外部依赖，启动 6.3s，是启动耗时对比可复现的前提）与 `mysql`（共享库，env 驱动）；**`mysql` profile 只做了配置装配级验证，未连过活体 MySQL**（本步不允许起容器）。
- 提交面：`pom.xml` + 10 个主类 + 3 个资源文件（application.yml/schema.sql/data.sql）+ 3 个测试类 + 本文档。未触碰 `seckill-app-jdk21/**`、`seckill-core/**`、`platform/**`、`chaos-controller/**`、`code/lab/**`。

# 完成功能项

## 1. POM（`code/services/seckill-app-jdk8/pom.xml`）

- `maven.compiler.release=8` 覆盖 reactor 根的 21；Spring Boot 版本用根 POM 既有属性 `spring-boot-jdk8.version=2.7.18`（本地 `spring-boot.version` 只做别名，保持单一版本源）。
- `dependencyManagement` 导入 `spring-boot-dependencies:2.7.18` BOM；依赖：`seckill-core`、`platform-common`、`spring-boot-starter-web`、`spring-boot-starter-actuator`、`micrometer-registry-prometheus`（BOM → 1.9.17）、`spring-boot-starter-data-jpa`、`mysql:mysql-connector-java`、`com.h2database:h2`（runtime）、`spring-boot-starter-test` + `junit-jupiter`（test）。
- **必须显式钉版本的一处**：2.7.18 的 BOM 只管新坐标 `com.mysql:mysql-connector-j`，不管 `mysql:mysql-connector-java`，故本模块 `<mysql-connector-java.version>8.0.33</mysql-connector-java.version>`（与 BOM 选的驱动版本同号；旧坐标是 relocation POM，落地的 jar 实为 `mysql-connector-j-8.0.33.jar`，已在 `BOOT-INF/lib` 核实）。
- `spring-boot-maven-plugin:2.7.18` `repackage` → `java -jar` 可用。**未加** redis/redisson/rabbitmq/flyway/resilience4j/caffeine。

## 2. 应用代码（`com.javalearn.lab.seckill.jdk8`，7 个主类）

- `SeckillJdk8Application`：`@SpringBootApplication`；类注释第 1 行标 EOL/教学专用（设计 §10.1）。
- `catalog/ProductController` → `GET /api/products/{id}`，返回 `Result<ProductResponse>`；`catalog/ProductResponse` 是与 JDK 21 `ProductResponse`（record）**字段名逐一对齐**的 Java 8 POJO：`id,name,price,status,description`；`ProductReadService` = `@Transactional(readOnly = true)` 直连 `seckill-core` 的 `DefaultProductQueryService`；`catalog/infra/` = `javax.persistence` 实体 + `JpaRepository` + `ProductRepository` 适配器；`catalog/config/CatalogConfig` 装配核心用例（与 JDK 21 同名同责）。
- `web/HelloController` → `GET /api/hello`，`{message,instance}` 与 JDK 21 同形；`web/GlobalExceptionHandler` 与 JDK 21 同规则（`BizException`→状态码 + `Result` 包体，NOT_FOUND→404/`40401`，参数类型错→400/`40001`）。
- Java 8 语法约束：无 `var`/record/文本块/`List.of`/`Map.of`/无参 `orElseThrow()`；`release 8` 由编译器强制。

## 3. 指标与标签（`application.yml`，53 个 `${ENV:default}` 占位符）

- `spring.application.name=${APP_NAME:seckill-app-jdk8}` + `management.endpoints.web.exposure.include=${MANAGEMENT_ENDPOINTS_INCLUDE:health,info,metrics,prometheus}` + `management.info.env.enabled=${INFO_ENV_CONTRIBUTOR_ENABLED:true}`（Boot 2.7 的 env info contributor **默认关闭**，条件报告实测 `management.info.env.enabled is not true`，不打开 `/actuator/info` 返回 `{}`）。
- **Prometheus 标签机制的结论**：JDK 21 侧的 `app/env/instance/jvm_version` 是**应用内公共标签**，由 `platform-observability` 的 `MeterRegistryCustomizer#commonTags` 注入（`app` 取 `spring.application.name`），`prometheus.yml` 的 `job_name: seckill-app` 只是抓取作业名、D 系列面板实际按 `app=~".*"` 过滤。本模块**不依赖** `platform-observability`（Boot 3/jakarta），改用 Boot 2.7 官方的 `management.metrics.tags.{app,env,instance,[jvm.version]}`，**标签名与取值来源完全相同**（`jvm_version` 来自 `${java.version}` 系统属性），因此 D7 查询两侧可通用；机制差异只是"yml 声明" vs "Java 定制器"。JRE 8 实跑证据：`jvm_memory_used_bytes{app="seckill-app-jdk8",...,jvm_version="1.8.0_503",}`。
- P99 前置条件同样在 yml 里复刻了 `platform-observability` 的 `EnvironmentPostProcessor` 设置：`percentiles-histogram.http.server.requests` + `slo`/`min`/`max expected`（全部 env 可覆盖）→ 抓取文本含 `http_server_requests_seconds_bucket`。
- Tomcat/D2 侧旋钮：`server.tomcat.threads.max/min-spare`、`accept-count`、`max-connections`、`connection-timeout`、`mbeanregistry.enabled`。Hikari 侧：`DB_POOL_MAX_SIZE/MIN_IDLE/NAME`、`DB_CONNECTION_TIMEOUT_MS`、`DB_IDLE_TIMEOUT_MS`、`DB_MAX_LIFETIME_MS`、`DB_VALIDATION_TIMEOUT_MS`。

## 4. 两个 profile 与种子数据

- `demo`（`application.yml` 默认，`APP_PROFILE` 可切）：H2 内存 + `schema.sql`/`data.sql`（Boot 2.7 的 `spring.sql.init`，比 `CommandLineRunner` 更贴框架惯例）。**种子与共享 MySQL 种子逐行同值**：20 个商品 id 1–20、名称/价格/状态/描述照抄 Flyway `V2__seed_catalog_products.sql`，库存照抄 `V3`（`INSERT INTO inventory_stock SELECT id, 100 FROM catalog_product`）→ k6 默认 `PRODUCT_ID=1`、`SECKILL_PRODUCT_ID=20`、"stock 100" 断言在两壳成立。id 之所以显式写死：MySQL 靠 AUTO_INCREMENT 从 1 起，H2 无自动序列时必须有确定值才能保证"同一行"。
- `mysql`：URL/用户/口令/驱动全部 `${DB_URL:jdbc:mysql://${MYSQL_HOST:mysql}:${MYSQL_PORT:3306}/${MYSQL_DATABASE:seckill}...}` 形式（默认值与 JDK 21 外壳既有约定一致，不新增密钥），`ddl-auto=${JPA_DDL_AUTO:validate}`、`spring.sql.init.mode=${SQL_INIT_MODE:never}`——**建表仍归 JDK 21 侧的 Flyway 所有**，本壳不重复建表。

## 5. 测试（3 类 11 例，零外部依赖）

- `ProductApiTest`（5，`@SpringBootTest(RANDOM_PORT)` + `TestRestTemplate`）：包体字段集合恰为 `{code,message,data,success}`、`data` 恰为 `{id,name,price,status,description}`、`"price":299.00` 两位小数刻度、id 1/20 的名称、未知 id → 404+`40401`、非数字 id → 400+`40001`、`/api/hello`。
- `ActuatorEndpointsTest`（3）：`/actuator/health`、`/actuator/health/app`、`/actuator/health/readiness` 均 UP；`/actuator/info` 含 `app.name=seckill-app-jdk8`；`/actuator/prometheus` 含 `jvm_memory_used_bytes`、`app="seckill-app-jdk8"`、`jvm_version="`、`http_server_requests_seconds_count` 与 `_bucket`。
- `ExternalizedConfigTest`（3）：第二个上下文用 env 风格键（`APP_NAME`/`SERVER_PORT`/`DB_POOL_MAX_SIZE`/`DB_POOL_NAME`/`DB_CONNECTION_TIMEOUT_MS`/`INSTANCE_ID`/`DEMO_DB_URL`）覆盖，断言它们既进 `Environment`，也真的落到**活 Hikari 池**（`getMaximumPoolSize()==7`、`getPoolName()`、`getConnectionTimeout()==4321`）和**指标公共标签**（scrape 含 `app="jdk8-override-app"`、`instance="override-instance"`）。

# 设计逻辑

- **复用边界**：只吃 `seckill-core` + `platform-common`（两者 `release 8`，class 版本 52，JVM 8 可加载）；`platform-observability` 是 Boot 3/jakarta，硬拼会炸，因此它承担的两件事（公共标签、HTTP 指标直方图/SLO 默认值）改由 `application.yml` 声明式复刻——**读起来更薄，但标签与指标名与 JDK 21 侧可对齐**，这是 D7 对比的前提。
- **读路径为什么不含缓存**：JDK 21 的 `GET /api/products/{id}` 背后是 Caffeine L1 + Redis L2 + 穿透防御（Boot 3 专属）。本壳保持**同一路径、同一 JSON**，但直接走 domain/JPA——这一点必须让看面板的人知道：D7 上 JDK 8 侧的吞吐/P99 是"无缓存直读 DB"的形状，不是同一套缓存语义的对比。要对比缓存效果得等 M4 的 L2 侧另建 Boot 2 实现。
- **"stock/readiness 端点"的裁决**：JDK 21 外壳**没有任何库存 REST 端点**（库存只在 Redis Lua 与 `inventory_stock` 表里，`/api/**` 全集为 products/hello/orders/seckill orders/login + `/lab/**`）。为了不制造一个 JDK 21 侧没有的端点破坏"同一 k6 脚本打两壳"，本壳把"readiness"落在 `/actuator/health` 家族：沿用 JDK 21 的容器门 group 名 `app`（`/actuator/health/app`），并开 `probes` 得到 `/actuator/health/readiness`；`demo` 种子仍写 `inventory_stock`，为后续真要出库存端点留数据。**结论：本壳的 HTTP 面 = products + hello + actuator，不多一个。**
- **profile 的取舍**：`demo` 用 `schema.sql`/`data.sql` 而不是引入 Flyway——Flyway 会需要 MySQL 方言脚本，而 H2 版脚本本就该分开维护；两份 DDL 的差异（无 ENGINE/CHARSET、无 AUTO_INCREMENT、`ON UPDATE` 省略）已在文件注释写明。两个 `@SpringBootTest` 上下文共享同一个 `seckill-jdk8` 内存库，所以 `ProductApiTest`/`ActuatorEndpointsTest` 的注解完全一致以复用同一缓存上下文（种子只跑一次），配置测试则自带 `DEMO_DB_URL` 指向独立库。
- **工程规约第 1 节**：Java 里没有任何运行时常量，唯一数字是 `@Column` 的 `length/precision/scale`——那是**库表结构契约**（与 JDK 21 实体逐值相同），不是可调参数。53 个占位符覆盖端口/线程池/连接池/超时/TTL/URL/驱动/方言/脚本位置/暴露端点/SLO 桶。

# 存在缺陷项&TODO

- **本步明确没做（下游必须接手的四项）**：① compose 里**没有** `seckill-app-jdk8` 服务与镜像（`code/lab/docker/**` 未被本步触碰，Docker 归另一 agent）；② **没有** D7 Grafana 面板（`lab/docker/grafana/dashboards/` 仍是 d1/d2/d3/d4/d9）；③ **没有** k6 目标接线（k6 `BASE_URL` 现指向 nginx，需给第二运行时的入口与 `app` 变量）；④ 缓存/线程/toggles/异步秒杀/登录/订单在 JDK 8 侧**无对等实现**，D7 只能比"裸读路径 + JVM/GC/堆/线程/启动"。
- **`jvm_info` 指标不存在**：Boot 2.7 + micrometer 1.9.17 不导出 `jvm_info{version=...}`（抓取文本里 `# TYPE jvm_*` 全清单见附录，无此项）。运行时代际由**每条序列上的 `jvm_version` 公共标签**表达（JRE 8 实测 `jvm_version="1.8.0_503"`）。D7 若按 `jvm_info` 写查询会拿不到数据，**必须改用 `jvm_version` 标签或 `count(...) by (jvm_version)`**。
- `mysql` profile 未对活体 MySQL 跑过：只验证了占位符装配与 `demo` 启动。首个 compose 接线者需实跑一次 `APP_PROFILE=mysql` 并确认 `ddl-auto=validate` 对 Flyway 表通过（H2 与 MySQL 的 `TIMESTAMP ON UPDATE` 差异不影响映射列）。
- 本机 Java 8 **只有 JRE**（`C:\Program Files\Java\jre1.8.0_503`，无 javac），编译靠 `--release 8` + Maven（跑在 JDK 25 上）。设计 §10.1 写的是 Temurin 8 运行时；真要容器化仍需基础镜像提供 JDK/JRE 8，本步未定镜像。
- 测试在 **JDK 25 上跑 Boot 2.7/Spring 5.3**（surefire fork 跟随 Maven）：通过，但伴随 Tomcat native-access 与 `Java 25.0.3` 运行时告警；Spring 5.3 官方支持上限远低于 25，这条路径**能用但非受支持**。若要"测试也在 8/21 上跑"，需 Maven toolchains 或把 `JAVA_HOME` 指到 `C:\Program Files\Eclipse Adoptium\jdk-21.0.12.1`（step-24b 文档的既有做法）。
- 启动耗时 6.302s 是**未加 `-Xmx512m` 的裸默认堆**读数，且只有单次样本；设计 §10.2 要求"相同 CPU/内存限额、相同 `-Xmx512m`"才进 D7 正式对比表。当前数字仅用于"能跑起来"的证据，**不得直接抄进对比表**。
- 健康 group `app` 的 `include` 默认 `db,ping`（本壳无 Redis 贡献者），与 JDK 21 侧的 `exclude:[redis]` 语义等价但**写法不同**；将来给本壳加缓存 L2 时，这条 include 需要同步调整，否则新贡献者会被静默排除在容器门外。

# 待定事项和注意事项

1. **给 compose/D7 接线者（下一棒）**：新增服务时把 `SERVER_PORT`、`APP_PROFILE`、`DB_URL`/`MYSQL_*`、`TOMCAT_THREADS_MAX`、`DB_POOL_MAX_SIZE`、`INSTANCE_ID`、`APP_NAME` 当 env 暴露即可，**不要改 jar**；Prometheus 需要一个新 job（例如 `job_name: seckill-app-jdk8`，`metrics_path: /actuator/prometheus`）指向 8080——但 `app` 标签已经能区分两壳，按 `app` 过滤即可，作业名只是可读性。
2. **给 D7 面板作者**：两侧通用查询 `http_server_requests_seconds_bucket{app=~"$app"}`（P99 用 `histogram_quantile`）、`jvm_memory_used_bytes{app=~"$app"}`、`jvm_gc_pause_seconds_*`、`jvm_threads_live_threads`、`tomcat_threads_busy_threads`、启动耗时**不能**从指标取，只能取启动日志 `Started SeckillJdk8Application in ...` 或 k6/脚本侧墙钟。
3. **k6 复用要点**：`lab/k6/lib/flow.js` 的 `product()` 只校验 `payload.data.id === id`，`Result` 多出的 `success` 字段不影响；换壳只需 `BASE_URL` + 可选 `PRODUCTS_PATH`。
4. **安全/边界**：本壳无鉴权、无 `/lab/**`，`GlobalExceptionHandler` 的兜底 500 不回显堆栈；`MYSQL_USER/MYSQL_PASSWORD` 默认值 `seckill/seckill` 沿用 JDK 21 外壳既有约定（compose dev 口令，非新密钥），生产红线不变——**本模块 EOL，仅本机对比用途**。
5. **数据状态**：`demo` 每次都新建内存库、进程退出即消失；未读写 `jl-mysql`、未写 `code/lab/docker/.env*`、未跑 `verify.sh`/`smoke.sh`（它们断言的是活体 dev 栈，本步不允许动容器）。
6. 下一步建议顺序：本壳进 compose（含 `--release 8` 的镜像构建） → Prometheus 新 job + D7 面板 → 同一 k6 场景跑两壳并把"吞吐/P99/GC/堆/线程/启动耗时/镜像大小"写入 `doc/process/benchmark/jvm-comparison/`。

# 附录

## 关键文件

- `code/services/seckill-app-jdk8/pom.xml`
- `.../src/main/java/com/javalearn/lab/seckill/jdk8/`：`SeckillJdk8Application`、`catalog/{ProductController,ProductReadService,ProductResponse}`、`catalog/config/CatalogConfig`、`catalog/infra/{ProductJpaEntity,ProductJpaRepository,JpaProductRepositoryAdapter}`、`web/{HelloController,GlobalExceptionHandler}`
- `.../src/main/resources/`：`application.yml`、`schema.sql`、`data.sql`
- `.../src/test/java/com/javalearn/lab/seckill/jdk8/`：`ProductApiTest`、`ActuatorEndpointsTest`、`ExternalizedConfigTest`

## 启动与验证命令

```bash
cd /d/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-be0d16bc/code

mvn -q -pl services/seckill-app-jdk8 -am test            # 11 tests, exit 0
mvn -q -pl services/seckill-app-jdk8 -am -DskipTests package

"C:/Program Files/Java/jre1.8.0_503/bin/java" -version   # 1.8.0_503
cd services/seckill-app-jdk8
SERVER_PORT=18081 APP_PROFILE=demo \
  "C:/Program Files/Java/jre1.8.0_503/bin/java" -jar target/seckill-app-jdk8-0.1.0-SNAPSHOT.jar
curl -i localhost:18081/actuator/health localhost:18081/api/products/1 localhost:18081/actuator/prometheus
# 连共享 MySQL 跑同一 jar（需活体库，本步未执行）：
#   APP_PROFILE=mysql DB_URL=jdbc:mysql://... MYSQL_USER=... MYSQL_PASSWORD=... java -jar ...
```

## 证据（本步实际输出）

- Maven：`TEST_EXIT=0` / `PKG_EXIT=0`；`Tests run: 5 ... ProductApiTest`、`Tests run: 3 ... ActuatorEndpointsTest`、`Tests run: 3 ... ExternalizedConfigTest`（模块合计 11/0/0/0），`Started ActuatorEndpointsTest in 6.827 seconds`。
- JRE 8：`java version "1.8.0_503"`、`Java(TM) SE Runtime Environment (build 1.8.0_503-b01)`、`HotSpot(TM) 64-Bit Server VM (build 25.503-b01)`；jar 内主类 `od` 读 class 版本 = `52`。
- 启动行：`Tomcat started on port(s): 18081 (http)` + `Started SeckillJdk8Application in 6.302 seconds (JVM running for 6.837)`。
- curl：`/actuator/health` → `200 {"status":"UP","groups":["app","liveness","readiness"]}`；`/actuator/health/app` → `200 {"status":"UP"}`；`/api/products/1` → `200 {"code":"0","message":"OK","data":{"id":1,"name":"极客机械键盘","price":299.00,"status":"ON_SALE","description":"87 键热插拔轴体，PBT 键帽"},"success":true}`；`/api/products/20` → 200；`/api/products/9999` → 404；`/actuator/info` → `{"app":{"name":"seckill-app-jdk8","runtime":"jre8","shell":"seckill-app-jdk8","purpose":"D7 dual-JDK comparison, EOL teaching-only"}}`；`/actuator/prometheus` → 200 / 66,972 B / 452 行，含 `jvm_memory_used_bytes{app="seckill-app-jdk8",area="nonheap",env="dev",id="Code Cache",instance="app-jdk8-local",jvm_version="1.8.0_503",}`；`# TYPE jvm_*` 清单为 buffer/classes/gc/memory/threads 五族，**无 `jvm_info`**。
- 收尾：`taskkill //PID <jre8 java>` 成功，18081 无 LISTENING；`git status --short` 仅 `code/services/seckill-app-jdk8/pom.xml` 与 `src/`（无 `target/`、无日志、无 `.env`）。
