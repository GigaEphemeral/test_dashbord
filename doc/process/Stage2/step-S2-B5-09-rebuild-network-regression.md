# 当前进展

重装后用户第一次在控制台 apply JVM 配置，受控重建把 app-1/2/3 从 `jl-sentinel-net` 移出，只剩下
`jl-net`；三个实例虽然还是 `REDIS_MODE=sentinel`，但已无法到达 Sentinel 主地址，Redis 调用全部阻塞，
连带 `/actuator/prometheus` 超时、Prometheus app target 全部 DOWN、核心 KPI/分流/Redis 面板无数据。
已修复控制面重建逻辑并恢复现场；同时修正了两处把 UTC 时间直接展示给用户的 UI 文本。

验证（2026-10-30）：

| 项 | 结果 |
|---|---|
| 控制器全量测试 | **895 tests / 0 failures / 0 errors**（新增 2 条网络保留测试） |
| app 网络 | app-1/2/3 均为 `jl-net + jl-sentinel-net` |
| `/actuator/prometheus` | 三个实例均 ~100–111 KB，不再超时 |
| Prometheus app target | `app-1/app-2/app-3` 全部 UP |
| KPI 窗口 API | `GET /lab/metrics/window` → `quality=OK`，`http.rps` 等有逐实例序列 |
| 缓存键 API | `GET /lab/cache/keys?limit=100` 返回 `cache:product:*` 与 `ttlSeconds` |
| dashboard build | 通过；UTC 文本改为本地时间 |

# 完成功能项

| 项 | 落地 |
|---|---|
| 重建保留全部网络 | `DockerInstanceRebuildGateway.createFrom` 在创建后调用 `connectToNetworkCmd`，把快照里除主网络外的网络（及其 alias）重新接上；新增 `extraNetworks(snapshot, primary)` 纯函数与单测 |
| 现场恢复 | 对 app-1/2/3 执行 `docker network connect jl-sentinel-net` 并重启，Lettuce 共享连接锁释放，指标/Redis 恢复 |
| 控制面重建后的验证 | 用新控制器对 app-1 执行 `POST /lab/instances/app-1/restart`：状态 APPLIED，重建后仍是双网络，metrics 111 KB，Prometheus target UP |
| UI 时间显示 | `RunTimelines` 的 run 窗口与 `ObservePanel` 的 KPI 窗口不再直接显示 UTC ISO，改用浏览器本地时区（`toLocaleString('zh-CN')`） |
| 防回退断言 | claims 新增 `rebuild-preserves-secondary-networks` |

# 设计逻辑

- **为什么只有主网络**：docker-java 的 `CreateContainerCmd` 只接受一个 `NetworkMode`；Docker 在 create 时只挂
  主网络，其余网络必须创建后 `connect`。快照其实已经采集了 `networkAliases`（所有网络），但旧实现只用了
  `primaryAliases()`，因此 ha overlay 的 `jl-sentinel-net` 被丢掉。
- **为什么症状这么重**：Sentinel 模式所有 Redis 操作共用一条 Lettuce shared connection；连接不可达时请求在
  `LettuceConnectionFactory.doInLock` 上排队，Tomcat 线程被占满，连 `/actuator/prometheus` 都无法在 5s 内响应，
  于是 Prometheus 判 target down，所有依赖应用指标的 KPI/分流/缓存面板同时变空。
- **为什么不是 Grafana/Prometheus 配置坏了**：10 张面板仍由 `grafana/provisioning/dashboards` 声明式加载，
  Prometheus 规则也正常；关键指标族（`container_*`、`mysql_global_status_*`、`redis_commands_processed_total`、
  `rabbitmq_queue_*`、`cache_l1_*`、`jvm_gc_pause_*`、`thread_lab_*`）裁剪后仍存在。
- **时间不是时钟漂移**：host / app / Prometheus 的 `date -u` 与 `time()` 一致；问题只是 UI 把 ledger 的 UTC ISO
  原样展示，而其他位置用本地时间。已统一为本地显示。
- **Redis 信息不是丢失**：指标 `redis_db_keys`、`redis_memory_used_bytes`、`redis_commands_processed_total`
  都在；缓存键抽屉用 `/lab/cache/keys` 返回 key 与 `ttlSeconds`。全新安装下“实验键 0”是因为还没 seed
  90000–90999 数据集，商品缓存键（预热写入的 `cache:product:*`）是存在的。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 失败 run 无法补数据 | `run-20261030-001`（15:10–15:20）期间 app target 已 down，KPI/分流无样本；只能重新跑一轮 | 记录 |
| 多网络只覆盖 sentinel-net | 当前实验室只有 ha overlay 增加第二网络；如未来再叠加网络，修复同样适用，但需要为 alias/link 增加测试覆盖 | Future |
| 重建后的网络自检 | 目前靠 apply 后的 app-probe/metrics；可增加“快照网络数 == 重建网络数”的显式检查 | 可选 |
| 健康门不含 Redis | `health/app` 故意排除 Redis；Redis 不可达时实例仍 healthy，这次靠 Prometheus target 才发现。是否加“sentinel 连接性”软告警需另议 | 决策 |

# 待定事项和注意事项

1. **以后 apply/restart 前**先确认 app 双网络：`docker inspect jl-app-1 --format '{{range $k,$v := .NetworkSettings.Networks}}{{$k}} {{end}}'`。
2. **新 run 才能有 KPI**：失败 run 的窗口没有应用指标，重新启动一轮 load/smoke 即可看到 KPI 与分流。
3. **Redis 键/TTL 在哪看**：控制台“实时监控与操作 → 缓存键”抽屉；指标侧看 D11/D10 或 `redis_db_keys`。
4. **实验数据集**：全新安装后 `cache:product:90000..90999` 不存在，先跑 `POST /lab/cache/seed` 或 cache-lab 场景。

---

## 附录：现场证据

```text
# 故障时
jl-app-1 networks = jl-net            （缺 jl-sentinel-net）
Prometheus seckill-app targets: app-1/2/3 DOWN (context deadline exceeded)

# 修复后
jl-app-1 networks = jl-net jl-sentinel-net
GET /actuator/prometheus -> 111264 bytes (fast)
up{job="seckill-app"} -> app-1/app-2/app-3 = 1

# 控制面 restart 验证（新镜像）
POST /lab/instances/app-1/restart -> APPLIED
networks = jl-net jl-sentinel-net
```
