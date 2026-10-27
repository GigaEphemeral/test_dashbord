# 步骤交接：S2-B0-02 `stock_oversold_total` 真实递增

> 任务/步骤：Stage2 B0-02 —— 修复已注册但从未递增的 `stock_oversold` 计数器，让一次"被阻止的超卖尝试"（条件扣减 UPDATE 影响 0 行）真实 +1
> 日期：2026-10-27
> 分支 / HEAD：feature/stage2-workbench 基线 worktree 提交 `80803ae`（基线 commit `ca76318`）
> 关联任务：`doc/process/Stage2/stage2-开发测试与验收计划.md` B0-02
> 测试 / verify：聚焦新测试 11/11 绿；模块全量见附录（基线 434 tests，本步新增 11）
> 运行状态：本步为改代码+跑单测，未做真机演练；真机样本验证见 §4（unverified，主会话后补）

# 当前进展

- 收口点已接线：`JpaInventoryRepositoryAdapter.reserve`（`code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/inventory/infra/JpaInventoryRepositoryAdapter.java:52-58`）在条件扣减返回 0 行时经 Micrometer 递增 `stock_oversold`（Prometheus 导出名 `stock_oversold_total` 不变）。
- 新增 4 个测试类共 11 个用例（1 个收口点单测、2 个业务路径用例级单测、1 个真实 MySQL 回归 IT），聚焦运行全部通过。
- 最新 commit：`80803ae feat: increment stock_oversold_total on prevented oversell`。
- 模块全量测试在进行/见附录证据；本步无未完成代码项。

# 完成功能项

- 主代码（2 个文件）：
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/inventory/infra/JpaInventoryRepositoryAdapter.java`：构造器注入 `MeterRegistry`；`reserve` 检查条件 UPDATE 的受影响行数，0 行 → `stockOversold.increment()` 后返回 false；公开常量 `STOCK_OVERSOLD_METRIC`/`STOCK_OVERSOLD_DESCRIPTION` 固定注册名与描述。
  - `code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/admission/app/SeckillMetrics.java`：`stock_oversold` 的注册描述与 Javadoc 从"must stay zero（断言指标）"改为"被条件扣减挡下的超卖尝试"；保留急切注册（零值时序列就存在）与 `stockOversold()` 访问器；描述字符串与收口点完全一致，两处 builder 解析为同一个 Counter，导出名仍为 `stock_oversold_total`（Micrometer 剥 `_total` 后缀的对偶行为不变，`_created`/`_info` 陷阱无关本指标）。
- 测试（4 个新文件）：
  - `.../src/test/java/com/javalearn/lab/seckill/inventory/infra/JpaInventoryRepositoryAdapterTest.java`（4 用例，Mockito+SimpleMeterRegistry）：0 行 → +1；成功 → 不涨；多次阻止各计一次；与 `SeckillMetrics` 急切序列共享同一 Counter（单序列断言）。
  - `.../src/test/java/com/javalearn/lab/seckill/inventory/infra/StockOversoldCounterIT.java`（3 用例，真实 MySQL 8.4.4 Testcontainers）：核心回归——库存不足时条件 UPDATE 真实匹配 0 行 → 计数 +1；同步下单路径每次被挡 +1（重试再 +1）；成功下单不动计数。全部按 delta 断言（共享缓存上下文计数是累计的）。
  - `.../src/test/java/com/javalearn/lab/seckill/order/app/SyncOrderOversoldCounterTest.java`（2 用例）：业务路径一（同步 `POST /api/orders` 用例级链路 application→transaction→DefaultOrderService→收口点），售罄抛 40901 且计数恰 +1。
  - `.../src/test/java/com/javalearn/lab/seckill/admission/app/SeckillConsumerOversoldCounterTest.java`（2 用例）：业务路径二（异步秒杀消费者 `SeckillOrderConsumer.process` 走同一用例链路），数据库挡下 → `stock_oversold` +1 且 `order_consumer_processed{result="failed"}` +1 各计各的、同一次失败不双计。
- 无新增配置项、无 application.yml 改动（工程规约 §1：不引入硬编码新参数——本步确实无新参数；指标名/描述为常量字符串，非可调参数）。

# 设计逻辑

- 收口点选择：两条业务路径（同步下单与异步秒杀消费）都经 `OrderApplicationService → OrderTransactionService → DefaultOrderService(seckill-core) → InventoryRepository`，唯一落地实现就是 `JpaInventoryRepositoryAdapter`；`InventoryStockJpaRepository.reserve` 除该适配器外无其他生产调用方。把递增放在适配器（"UPDATE 影响 0 行"的第一现场）保证：① 各调用方都不会漏加；② 同一次失败只在这一处计数，天然不双计。
- 语义边界：`seckill_attempts{result="sold_out"}` 统计的是 Redis 准入层的售罄（根本没到数据库），与本指标不重叠——准入层挡掉的请求不会走条件扣减，因此不会触发本计数；本指标严格等于"数据库防线挡下的超卖尝试"。`order_consumer_processed{result="failed"}` 统计消费者的失败处理（含本场景与其他异常），与超卖计数是不同维度、互不换算。
- 计数器接线方式：收口点直接 `Counter.builder("stock_oversold")` 注册到注入的 `MeterRegistry`，与 `SeckillMetrics` 的急切注册同名同描述——Micrometer 按 (name, tags, type) 去重，两处拿到同一个 Counter 实例，序列零值在启动即存在（dashboard/告警绑序列不绑流量），真机 scrape 导出名不变。刻意不让 infra 适配器依赖 `admission.app.SeckillMetrics`（依赖方向：业务侧指标类可被 app 层用，仓储适配器只认 MeterRegistry）。
- `source`（sync/async）标签：未加。适配器层看不到请求来源，加标签需要把来源参数从用例层贯穿到仓储层，改动面与收益不符；口径允许"没有也行"。若后续面板需要分路，可在用例层传 ThreadLocal/参数再评估。
- 现有 4 处 `stockOversold()==0` 断言（SeckillConcurrency/Async/Idempotency/Toggle IT）逐一核对过场景：它们的售罄都被 Redis 准入层挡在数据库之前，条件扣减全部成功，新行为下依然为 0——语义从"恒 0 断言"变为"真实计数"但不破坏这些测试。
- 禁区遵守：未触碰 `code/lab/docker/**`、`code/lab/scripts/**`（含 verify.sh、ha-d4.json 中的 `stock_oversold_total` 引用）、claims.yml、doc/readme.md、cache 包、chaos-controller。

# 存在缺陷项&TODO

- 真机样本验证：unverified（本步未起真机栈 scrape `/actuator/prometheus` 核对 `stock_oversold_total` 在手工超卖场景下的增长样本；由主会话后补，见下表）。
- 未加 `source` 低基数标签（决定：不做，理由见设计逻辑）；面板若要分路属后续步骤。
- 对账视图、UI、面板修改：刻意不在本步范围（任务收窄）。
- 已知限制：产品行不存在时条件 UPDATE 同样 0 行、同样计数——与"库存不足被挡"共用一个口径（口径原文即"UPDATE 影响 0 行 = 被挡下的超卖尝试"），不视为缺陷。
- 本步无硬编码参数清理项。

## 真机样本验证

| 项目 | 状态 | 备注 |
|---|---|---|
| `/actuator/prometheus` 导出 `stock_oversold_total` 名称不变 | unverified | 主会话真机后补 |
| 手工制造库存不足 → 样本计数 +1 | unverified | 主会话真机后补 |
| 同步/异步各一次阻止不双计（样本层面） | unverified | 单测层已证（用例级 +1 各一次） |

# 待定事项和注意事项

- 下一步：B0-03/B0-04 等并行批次继续；本步交付合回 `feature/stage2-workbench` 时以 commit `80803ae` 为单元。
- 风险：`observability.instance=test-1` 属性相同的 IT 共享缓存 Spring 上下文与 MeterRegistry，`StockOversoldCounterIT` 用 delta 断言规避累计污染；后续如新增同上下文测试请沿用 delta 风格。
- Docker 争用提示：`StockOversoldCounterIT` 需要本地 MySQL 8.4.4 镜像（已有缓存）；若与并行演练冲突按纪律同提交重跑。
- grafana 面板 `ha-d4.json` 与 verify.sh 中对 `stock_oversold_total` 的引用现在语义上是"被挡超卖次数"而非恒 0 断言；相关文案更新留给负责面板/验收脚本的批次（禁区，本步未动）。

# 附录

- 复现命令：
  - 聚焦：`mvn -f code/pom.xml -pl services/seckill-app-jdk21 -am test -Dtest='JpaInventoryRepositoryAdapterTest,SyncOrderOversoldCounterTest,SeckillConsumerOversoldCounterTest,StockOversoldCounterIT' -Dsurefire.failIfNoSpecifiedTests=false`（11/11 绿，BUILD SUCCESS）
  - 全量：`mvn -f code/pom.xml -pl services/seckill-app-jdk21 -am test`
- 提交：`80803ae feat: increment stock_oversold_total on prevented oversell`（基于 `ca76318`）
- 关键文件：
  - 收口点：`code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/inventory/infra/JpaInventoryRepositoryAdapter.java:52-58`
  - 指标注册：`code/services/seckill-app-jdk21/src/main/java/com/javalearn/lab/seckill/admission/app/SeckillMetrics.java:54-57`
- 证据：聚焦日志 `focus1.log`、全量日志 `full1.log`（worktree 根，含 surefire `Tests run:` 汇总）
