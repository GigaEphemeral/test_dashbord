> 步骤交接 · M1-4 读写路由数据源
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / `ddbc485`（代码）；文档提交见 git log
> 关联任务：#15（M1-4）
> 测试 / verify：app/reactor 52 tests 绿；`verify.sh` 73 PASS / 1 WARN
> 运行状态：16 个 lab 容器运行中
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- Task 15 完成：`afa4969` 实现读写路由，`ddbc485` 修复 Qwen 评审发现的两个 Important 问题。
- 独立范围复审已通过：两个 finding 均 ADDRESSED，无新的 Critical/Important。
- 依赖 Task 14（两层缓存、Redis 健康组）和 Task 12/13（catalog、订单一致性读）。
- 下一步：Task 16（秒杀主链路：Redisson 锁 + RabbitMQ），并开始执行配置外置规约。

# 完成功能项

- `DataSourceType`、`ReadWriteRoutingDataSource`、`DataSourceConfig`：两个 Hikari 池 `write-pool`/`read-pool`，主 DataSource 为 `LazyConnectionDataSourceProxy` 包住 routing。
- `@Transactional(readOnly=true)` 路由到读池，否则写池；Flyway、JPA、Actuator DB 健康均走 primary（写池）。
- `ProductReadService`（readOnly）承接缓存 miss 的产品读；缓存命中不开启事务。控制器契约不变。
- `OrderQueryService` 改为写池，保证幂等重试和 `GET /api/orders/{id}` 的读后写一致性；产品读仍走读池。
- 自定义 `dbHealthContributor` 绑定 primary，阻止 Boot 默认多 DataSource 健康聚合器提前触碰 read 池。
- 测试：H2 双库 `ReadWriteRoutingTest`（readOnly→read、普通→write、非事务→write、Lazy 证明）；`OrderQueryRoutingTest`（真实 OrderQueryService、red→green）；`DataSourceHealthIT`（单个 health indicator、read 池保持冷）；`ProductCacheIT` 读池初始化断言。app/reactor 52 tests 绿。
- `verify.sh` 新增写池/冷读/读池检查；现有检查全部保留，73/1。
- 线上三实例重建健康；Prometheus 可见 write-pool/read-pool 指标；读池由真实只读事务初始化。

# 设计逻辑

- **为什么 Lazy**：路由键在物理连接获取时解析；Spring 事务 begin 早于 readOnly 标记发布，若不延迟会全部命中写池。H2 测试用“读池启动前后”证明。
- **一致性读 vs 延迟读**：订单幂等/查询固定写库；产品详情允许读副本（M3 Router 6447），缓存进一步吸收流量。
- **健康门**：应用健康组 `app` 排除 redis；DB 健康只走 primary；根健康仍诚实反映 Redis DOWN。
- **配置接入点**：`DB_WRITE_URL`/`DB_READ_URL` 为 M3 Router 6446/6447 的钩子；当前 dev 两池都指向 `mysql`。
- **新规约**：从 Task 16 起，所有运行路径/超时/TTL/池大小/端口必须外置；本任务未做一次性历史重构。

# 存在缺陷项&TODO

- Minor（不阻塞）：
  - 两个 H2 测试可共享 context 以缩短启动时间。
  - 未来若引入按 DataSource 的健康/探针，需继续防止 Boot 多 DataSource 聚合器触碰 read 池；`dbHealthContributor` 是模板。
  - `OrderTransactionService` 内部再次按幂等键查询（指标精确性代价），已在注释说明。
- 历史配置外置审计：见 `阶段交接-2026-10-26.md`；Task 16 起新代码不得新增硬编码。
- M3 待办：Router URL 接入 compose、replica lag 监控、MySQL InnoDB Cluster 初始化风险。

# 待定事项和注意事项

- Task 16 起每个任务/修复都写 `doc/process/Stage1/step-<任务号>-<slug>.md` 并随代码提交。
- 运行栈保持运行；不要 `--purge` 或删除命名卷。
- 演示数据会累积（product 1 库存约 94、订单 6），后续需 reset 脚本。
- 安全边界：chaos-controller 挂载 rw Docker socket，token 默认值仅本机实验；不要暴露 8080 到局域网。
- 提交：`afa4969`（routing）、`ddbc485`（Qwen 修复）；Qwen 范围复审无新增提交。

# 附录

- 关键文件：
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/datasource/`
  - `.../catalog/app/ProductReadService.java`
  - `.../order/app/OrderQueryService.java`
  - `.../src/test/java/com/javalearn/lab/routing/`
  - `code/lab/scripts/verify.sh`
- 复核入口：`doc/process/STAGE-01阶段交接-2026-10-26.md`、`.superpowers/sdd/ha-lab/task-15-report.md`（gitignored）。
