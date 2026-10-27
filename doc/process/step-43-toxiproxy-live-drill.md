> 步骤交接（无头部块，2026-10-27 文档整理时补）
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

# 当前进展

M3-3 真机演练完成（`chaos-proxy` 叠加层 + Toxiproxy 动作），并在演练中发现并修复了两个**只有真机才会暴露**的问题：镜像无 shell、init 依赖健康检查。演练结束后栈已恢复默认 dev 状态。

- 相关提交：叠加层 `92e03e1`/`7471d7f`（M3-3a）、动作 API `2bb617b`/`0222791`/`7ca32d9`（M3-3b）；本步修复与交接见下一条提交。
- 真机环境：Windows 10 + Docker Desktop；dev 16 + ha 9 + db-ha 常驻，演练期间临时叠加 `chaos-proxy`（18 服务）。

# 完成功能项

## 真机修复

1. **`shopify/toxiproxy:2.9.0` 是 distroless 镜像**：容器内没有 `sh`、没有 `wget`。原 overlay 的 `healthcheck: wget … /version` 永远失败 → toxiproxy 被判定 unhealthy → `toxiproxy-init` 的 `service_healthy` 依赖直接阻断 app 层启动（实测报 `dependency failed to start: container jl-toxiproxy is unhealthy`）。
   - 修复：删除 toxiproxy 容器的 in-container healthcheck（distroless 无法运行任何 shell 探针）；`toxiproxy-init` 改用独立 `alpine:3.19`（`TOXIPROXY_INIT_IMAGE`，可配），依赖降为 `service_started`，并在脚本开头用 `/version` 轮询最多 60s 等待 API 就绪。
   - 新增 `.env.example` / `.env` 键：`TOXIPROXY_INIT_IMAGE=alpine:3.19`。
2. **应用经代理的真实落点**：演练确认 app 的 `REDIS_HOST=toxiproxy`、`APP_DATASOURCE_WRITE_JDBC_URL=jdbc:mysql://toxiproxy:13306/seckill…`，即两个数据面都真的走代理（`MYSQL_MODE=standalone` 时 `DB_*_URL` 不生效，必须靠 `APP_DATASOURCE_*` 覆盖——这一点在 M3-3a 已预判，本次真机证实）。

## 演练证据（每一步都实跑）

| 步骤 | 命令 | 结果 |
|---|---|---|
| 代理建立 | `docker logs jl-toxiproxy-init` | `API ready` → 创建 `redis → jl-redis:6379`（16379）、`mysql → jl-mysql:3306`（13306） |
| 基线写延迟 | 5× `POST /api/orders`（唯一幂等键） | `200`，75–90ms（前两条含连接池预热 0.65–1.42s） |
| 注入 500ms 延迟 | `POST /chaos/toxiproxy/mysql/toxics {"type":"latency","params":{"latencyMs":500,"jitterMs":50}}` | `200`，返回 toxic `lat500` |
| 延迟影响 | 5× 同一下单 | `200`，**3.48–4.07s**（一次下单要多次往返 DB，约 7×500ms） |
| 断网 | `POST /chaos/toxiproxy/mysql/disable` | 下单 **504**（Nginx 10s 读超时），产品读仍 **200**（L1/L2 命中掩盖 DB 故障），lab-api 容器列表 200（应用未挂） |
| 恢复 | `POST …/mysql/enable` | 下单恢复 |
| 一键复位 | `POST /chaos/stop-all` | `data.toxiproxy = {status:"reset", proxiesReset:2, proxiesTotal:2, errors:[]}`；随后 state 显示两个代理 `enabled=true`、toxics 数均为 0 |
| 还原 | `up -d app-1..3`（base+dev）、停并删 toxiproxy 容器 | 三实例 healthy、`REDIS_HOST=redis`、`MySQL/Redis access mode: standalone`、Nginx 产品读 200、lab 容器 32 个 |

# 设计逻辑

- **把网络故障放在代理层而不是容器层**：`tc`/iptables 需要特权与宿主差异，Toxiproxy 提供可编程 HTTP API，chaos-controller 能用同一套 token/错误码体系驱动，且副作用可枚举（toxics 可列出、可复位）。
- **代理开关属「演示准备阶段」**：app 的端点在建连时解析，切换必须重建应用容器；因此 `chaos-proxy` 是独立 profile，不与 ha/db-ha 混跑。
- **`stop-all` 必须能恢复网络**：与容器动作一致，演示结束要能一键回到干净状态；本次实测 `proxiesReset=2`。
- **读缓存掩盖 DB 故障是教学点**：断网时读 200、写 504 的对比正是「缓存降低数据库依赖」的直观证据，交接里明确记录，避免被误判为「演练失败」。

# 存在缺陷项&TODO

- **未用 k6 采集 P95/P99**：本步用 5 次 curl 采样，足以证明数量级（75ms → 3.5s），但没有形成压测曲线；后续可在 `bench.sh` 里加一个 `toxiproxy` 变体。
- **带宽 toxic 未演练**：API 支持 `bandwidth`，真机只验证了 `latency` 与断网。
- **toxics 无 TTL**：与容器动作不同，网络故障需要手工复位或 `stop-all`；是否加 TTL 待定（演示中手动复位更直观）。
- **控制台未接入**：`/chaos/toxiproxy/**` 目前只能经 `/lab-api` 用 curl/脚本驱动，操作台页面未做。
- **redis 代理的延迟对业务读路径影响有限**：L1 60s + L2 缓存会掩盖大部分 Redis 延迟，教学上应改用「新产品 id + 关闭缓存 toggle」或直接展示 mysql 代理的写路径（本步采用后者）。

# 待定事项和注意事项

- 演练前先确认 `ha`/`db-ha` 叠加层是否在跑：`chaos-proxy` 会重建 app 层，若同时需要 Sentinel/Router 演示，应先决定顺序。
- 事故预案：若演练中断，复位顺序是「`POST /chaos/toxiproxy/reset-all` → `enable` 两个代理 → 用 base+dev 重建 app-1..3 → 停删 toxiproxy 容器」；本次中断即按此恢复。
- `TOXIPROXY_INIT_IMAGE` 必须是带 shell 与 wget 的镜像（默认 alpine:3.19），不要改回 toxiproxy 自身镜像。
