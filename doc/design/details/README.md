# 本地高可用 / 高并发教学实验室 · 详细设计文档索引

> 目的：把 `doc/design/2026-10-26-ha-concurrency-lab-design.md` 的整体设计，拆成可按主题阅读、可对照代码、可在 5 分钟内演示的详细设计文档。
> 代码基线：`294798a`（01–03 编写时的基线）。04–11 由其他代理在相同/邻近基线上编写，合入时未改写正文基线，行号漂移请按符号名重新定位。
> 文档范围：只描述本仓库已合并的代码；设计稿里有、代码里没有的能力一律标注为「未实现 / 待补」，不按已交付来写。

## 1. 阅读顺序

建议按下面的顺序读。01–03 是底座与主链路；04–11 是按主题拆开的专题文档。

| # | 文档 | 主题 | 状态 |
|---|---|---|---|
| 01 | [01-总体架构与运行拓扑](01-总体架构与运行拓扑.md) | Compose 分层、16/25/25 服务不变式、网络与宿主端口、启动顺序与健康门、内存预算 | 已完成 |
| 02 | [02-业务主链路](02-业务主链路.md) | `catalog` / `inventory` / `order` 三域、Flyway、同步下单与幂等、异步秒杀与补偿、读写路由 | 已完成 |
| 03 | [03-缓存体系](03-缓存体系.md) | L1 Caffeine → L2 Redis → DB、Redis 故障降级、M4-1 穿透、M4-2a 击穿、实验播种与 `/lab/cache/*` | 已完成 |
| 04 | [04-Redis高可用](04-Redis高可用.md) | Redis 高可用：Sentinel 拓扑、主从切换、客户端发现与演练 | 已完成 |
| 05 | [05-MySQL高可用与读写路由](05-MySQL高可用与读写路由.md) | MySQL 高可用与读写路由：InnoDB Cluster、Router 6446/6447、双数据源接线与切换 | 已完成 |
| 06 | [06-故障注入与实验向导](06-故障注入与实验向导.md) | chaos-controller 容器动作、TTL 看门狗、场景 JSON、向导状态机、操作台 | 已完成 |
| 07 | [07-线程实验室](07-线程实验室.md) | 七种故障线程、单线程生命周期、指标指纹与排查 | 已完成 |
| 08 | [08-双JDK与JVM调优](08-双JDK与JVM调优.md) | JDK 8/21 外壳、镜像与启动参数、对比方法与调优台 | 已完成 |
| 09 | [09-压测与观测](09-压测与观测.md) | k6 场景、bench/compare、归档、Prometheus/Grafana/Tempo 面板 | 已完成 |
| 10 | [10-操作台与API](10-操作台与API.md) | Vue 操作台、`/lab-api/**`、`/lab/**`、token 与容器白名单 | 已完成 |
| 11 | [11-配置与安全边界](11-配置与安全边界.md) | 配置外置、环境变量、密钥与端口暴露、实验安全边界 | 已完成 |

> 各专题文档正文的「路径:行号」以它们各自头部的基线提交为准；本索引状态只表示文档已合入，不表示文档中的行号在当前 HEAD 仍然精确。不要把「未实现」理解成代码不存在：以第 3.1 节清单为准。

## 2.「代码对照」约定

每篇文档的核心章节是一张 **代码对照表**，固定四列：

| 列 | 含义 |
|---|---|
| 概念/行为 | 读者要理解的设计概念或运行行为 |
| 类或方法 | 承载它的 Java 类/方法、脚本变量/函数或配置键；用反引号包住可在源码中检索的符号 |
| `path/to/file:line` | 仓库根目录相对路径 + **该基线提交**下的行号 |
| 说明 | 该行为的关键语义、边界或已知偏差 |

约定：

- 行号是 **写文档时** 用 `grep -n` / 读文件取得的，不是估算；每篇文档头部记录自己的 `代码基线`。
- 代码一旦继续合并，行号会漂移；请先 `git rev-parse --short HEAD` 对照文档头部的基线，再按符号名重新定位，不要盲信行号。
- 只引用真实存在的文件与真实行；不把设计稿的类名、端点或面板当成已实现代码。
- 表格中的 `代码基线` 与 `path:line` 只对本仓库有效；脚本路径统一从仓库根写起（如 `code/lab/scripts/up.sh`）。

## 3. 当前基线的三个硬事实

1. **服务数不变式**：`compose.base.yml` + `compose.dev.yml` 默认渲染 **16** 个服务；`compose.ha.yml` 的 `ha` profile 追加 **9** 个（合计 25）；`compose.mysql.yml` 的 `db-ha` profile 追加 **9** 个（合计 25）；两份 overlay 同时选中才是 34，但没有脚本把两者接在一起。
2. **应用默认仍连单机 MySQL**：`MYSQL_MODE=standalone|router` 已实现（51a），但默认与所有 compose 渲染都是 `standalone`，即 `jl-mysql`；`router` 模式写 6446/读 6447，尚未做真机切换验收。
3. **缓存能力已合并三批**：M4-1 穿透、M4-2a 击穿（含 publish-through 与 `retention-seconds > hot-key-ttl-seconds` 审核修复）、M4-3 Caffeine 预热（ZSet 热点榜、启动/周期预热、L1 `refreshAfterWrite` 外部化）均已合入；雪崩策略仍在进行中。

### 3.1 未实现 / 未合并清单（统一口径）

| 能力 | 状态 | 证据 / 说明 |
|---|---|---|
| 雪崩策略（`ttl-jitter` / `l1-cache` / 熔断降级 + 限流） | **进行中** | 只有 200 个同 TTL 的 seed 键；截断的代理留下未提交的 Avalanche* 类，收尾任务正在补测试与配置。见 03 §12、`doc/process/step-26-breakdown-defense.md` |
| Caffeine 热点预热（ZSet、启动/周期预热、`refreshAfterWrite`） | **已合并** | M4-3（`edaa5e2` 等，merge `6e5d776`）；文档 03 的正文章节仍以旧基线描述，正文未改写。 |
| 线程快照（`jcmd Thread.print -l` / 堆直方图落盘） | **未实现** | 只有 `LoggingLifecycleSnapshotSink` 占位；没有 jcmd 采集与快照目录。见 `doc/process/step-34-thread-lifecycle.md` |
| 应用侧 MySQL Router 双数据源（`MYSQL_MODE`） | **已合并（默认 standalone）** | 51a：`DataSourceAccessProperties` + `MySqlModeSwitchTest`；真机 Router 切换验收仍未做。见 `doc/process/step-29-mysql-router-client.md` |
| 集群状态指标（`mysql_cluster_member_state` / `mysql_cluster_primary_info`） | **未实现** | 截断的代理只在 `ContainerGateway` 留了 exec 能力，探针本体未提交。 |
| Toxiproxy / `chaos-proxy` | **未实现** | 没有 compose 服务，也没有 chaos-controller 动作；`ChaosActionType` 只有容器动作。见 01 §10 |
| D6 / D7 / D10 / D11 面板 | **未实现** | 现有 dashboard 只有 `ha-d1`–`ha-d4`、`ha-d9`；D5 由 Tempo UI 承担，D8 也没有独立文件 |
| M4-2 评审修复（publish-through / retention 校验） | **已合并** | `34c0dfc` / `426e9c9` / `34c5df0`（2026-10-27） |

## 4. 文档状态与维护

- 01–11：全部已交付，包含代码对照表、配置项表、5 分钟演示步骤、已知缺陷/边界与关联文档。
- 专题文档正文按各自基线编写；合并顺序晚于文档基线的能力（M4-3 预热、M4-2 审核修复、51a `MYSQL_MODE`）以第 3.1 节状态为准，正文如未提及不等于代码不存在。
- Mermaid 图使用标准 `flowchart` / `sequenceDiagram` / `stateDiagram-v2` 语法；本环境没有渲染器，**图的视觉效果未经验证**，只做了围栏配对与图类型检查。
- 文档只写 `doc/design/details/`；不修改源码、测试、compose、脚本、`.env*` 或其他文档。

## 5. 关联入口

- 总体设计：[`../2026-10-26-ha-concurrency-lab-design.md`](../2026-10-26-ha-concurrency-lab-design.md)
- 数据库方案对比：[`../数据库高可用方案对比.md`](../数据库高可用方案对比.md)
- 工程规约：[`../工程规约.md`](../工程规约.md)
- 阶段交接（含缺陷/边界与未验证项）：[`../../process/阶段交接-2026-10-26.md`](../../process/阶段交接-2026-10-26.md)
- 运行与演示脚本：`code/lab/scripts/`；Compose：`code/lab/docker/`；场景：`code/lab/scenarios/`；压测：`code/lab/k6/`；操作台：`code/lab/dashboard/`。
