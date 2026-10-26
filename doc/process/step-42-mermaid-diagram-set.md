# 当前进展

M6-2「全量 Mermaid 图表与导出」完成：在 `doc/design/details/diagrams/` 交付 9 张跨主题图，每张都有可编辑 `.mmd` 源与 `.md` 包装（内嵌 ` ```mermaid ` 围栏，GitHub/GitLab 可直接渲染）。

- 合并：`git log` 中的 `6546e6e` / `c0f207b`（M6-2 两个 docs 提交），合并进 `feature/ha-lab` 后共 19 文件 / +1440 行。
- 代码基线：各图头部记录 `代码基线：<commit>`；`README.md` 含索引与「每张图需回访的代码路径」清单。

# 完成功能项

| 文件 | 类型 | 内容 |
|---|---|---|
| `01-deployment-topology` | flowchart | 宿主端口、`jl-net`/`jl-sentinel-net`、按 profile 分组的容器、三实例 + Nginx |
| `02-seckill-request-chain` | sequenceDiagram | 锁/幂等/Lua → MQ → 消费者 → DB，含补偿与同步降级分支 |
| `03-read-write-routing` | flowchart | `LazyConnectionDataSourceProxy` → 路由数据源 → 读写池，`MYSQL_MODE` 与 Router 6446/6447 |
| `04-cache-read-path` | flowchart | L1/L2、穿透/击穿/雪崩三段防护、运行时切换与指标落点 |
| `05-redis-sentinel-failover` | sequenceDiagram | 发现 → 主 → 停主 → sdown/odown → switch-master → 重连，标注 7.6s 晋升 / 9.1s 首个 200 |
| `06-mysql-cluster-failover` | sequenceDiagram | Router RW/RO、杀主、选主、重定向、TTL 归队 |
| `07-chaos-guide-state-machines` | stateDiagram-v2 ×2 | 向导 IDLE/RUNNING/PAUSED/COMPLETED/FAILED 与动作 APPLYING/ACTIVE/REVERTING/FAILED |
| `08-thread-lab-lifecycle` | stateDiagram-v2 ×2 | 演示线程六态 与 故障动作 ACTIVE/PAUSED/STOPPED |
| `09-load-and-observability-flow` | flowchart | k6 remote write、Actuator 抓取、exporter、OTLP→Tempo、D1–D11 |

# 设计逻辑

- **只画已合并的代码**：图内容以 `feature/ha-lab` 的类/方法/脚本为准，不按设计稿臆造；作者在写图前核对了 `ChaosActionService`/`GuideStateMachine`、`TwoLevelCache`/`Product*Defense`、`DataSourceConfig`、`ThreadLabMetrics` 等实现。
- **双文件形式**：`.mmd` 便于复制到 Mermaid Live 单独渲染与 diff；`.md` 便于仓库内直接预览。
- **一致性清单**：README 为每张图列出 2–4 个「改动时必须回访」的代码路径，避免图与代码长期漂移。
- **实测数字带出处**：Sentinel 7.6s / 9.1s 来自 `step-24d` 时间戳换算（`step-38` 记录换算方式），不写无出处的数字。

# 存在缺陷项&TODO

- **未做视觉渲染验证**：本机无 Mermaid 渲染器，只做了图类型关键字、围栏配对、无制表符、`alt/end` 与括号平衡、`.mmd` 与 `.md` 字节一致检查；图形效果未经验证。
- **待补图**：D5/D7/D8 三个面板没有 JSON（D5 由 Tempo UI 承担），对应图在 README 中标记待补；MySQL Router 重定向与旧主归队的精确耗时只有 5s 采样，未写入图。
- **文档漂移（本次未改，归 #61 批次文档刷新）**：`details/01`、`details/09` 仍写「Toxiproxy / D6 / D10 / D11 未实现」，D11 相关描述仍称 `mysql-ha-scrape.yml` 为惰性文件，而实际已改为 `prometheus.yml` 内联 `mysqld-exporter-ha` job。
- 图未接入操作台动画（M6-1 的职责）。

# 待定事项和注意事项

- 后续新增/修改能力时，先改代码再改 `.mmd` 与 `.md` 两处，并按 README 的一致性清单回访对应代码路径。
- `07` 与 `08` 各含两台状态机，图内用两个独立 `stateDiagram` 块表达，避免把「向导状态」和「动作状态」混成一个状态机（本仓库常见误读点）。
- 若后续为图表增加导出图片（PNG/SVG），建议在 CI 或本地脚本中跑 Mermaid CLI，而不是手工截图。
