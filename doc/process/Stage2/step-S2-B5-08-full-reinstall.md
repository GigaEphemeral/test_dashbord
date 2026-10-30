# 当前进展

按用户要求做了一次**完整重装**：宿主编译三个服务、重建全部镜像与 dashboard、删除全部命名卷后，
以“当前完整形态”重新起栈（dev + Redis 2+2 HA + MySQL 3 节点/Router + D7 对照壳）。
按用户选择只做健康检查，未跑 verify/smoke、未消耗业务库存。

重装后实测：

| 项 | 结果 |
|---|---|
| `jl-*` 运行容器 | **30** 个全部 running；有 healthcheck 的全部 healthy |
| Prometheus targets | **16/16 up**（含 app-1/2/3 与 D7 壳） |
| app 健康门 | app-1/2/3 `/actuator/health/app` 均 `UP` |
| 业务入口 | `/health`、`/lab/`、`/api/products/1`、`POST /api/login` 均 200（token TTL 1800） |
| 身份 | app-3 `runtime=jdk8`、`FULL-OBSERVABILITY`；D7 壳 `runtime=jre8` |
| MySQL HA | 3/3 ONLINE，Router 6446→PRIMARY、6447→READ_ONLY |
| Redis HA | 2 数据 + 2 Sentinel；app-1/2/3 均为 `REDIS_MODE=sentinel` 并发现 `mymaster` |

# 完成功能项

| 项 | 落地 |
|---|---|
| 宿主构建 | `mvn -pl services/seckill-app-jdk21,services/seckill-app-jdk8,services/chaos-controller -am -DskipTests package`；dashboard `npm run build` |
| 镜像重建 | `seckill-app:jdk21`（java 21.0.12.1、uid 100、OTel agent）、`seckill-app:jdk8`（1.8.0_504）、`chaos-controller:jdk21`；均用离线 `Dockerfile.runtime` |
| 新增 jdk21 运行时 Dockerfile | `code/services/seckill-app-jdk21/Dockerfile.runtime`：从旧镜像复制同版本 OTel agent，官方入口参数（`-XX:MaxRAMPercentage=70` 等）保持一致 |
| 数据清空 | `db-ha-down.sh --purge` + `down.sh --purge` 删除全部命名卷（业务库、`lab_ledger`、Redis、MQ、Prometheus、Grafana、Tempo、k6 reports、ledger stash），并清掉旧前缀卷 |
| 重建顺序 | `up.sh --profile ha` → `db-ha-up.sh`（全新 bootstrap）→ `compose --profile dual-jdk up -d seckill-app-jdk8` |
| `.env` 对齐 | 旧 `.env` 缺少 `.env.example` 新增的 **144** 个键；按示例默认值补齐（不覆盖已有值），`up.sh` 的键完整性检查通过 |
| 集成缺陷修复 | ① `compose.ha.yml` 只下发 Boot 3 的 `SPRING_DATA_REDIS_SENTINEL_*`，app-3（Boot 2.7）启动报 “requires a master NAME”；已补 `SPRING_REDIS_SENTINEL_*`。② D7 壳原本无 MQ 配置，消费者启动报 `ACCESS_REFUSED`（guest/guest）；已补 `RABBITMQ_*` 与 `REDIS_*` |
| 输出口径 | `up.sh` 的 ha 摘要不再说 “app 仍连单机 Redis”，改为“overlay 已切 app 到 Sentinel” |

# 设计逻辑

- **清卷重装**：用户明确选择“彻底清空”，因此业务库与 `lab_ledger` 都是空的——实例配置台账为
  `NOT_CONFIGURED`，不会出现旧 `APPLIED` 与实际参数不符的历史包袱。
- **为什么用离线 Dockerfile**：本机容器内 Maven Central TLS 失败；宿主编译 + 运行时镜像能复现
  与官方镜像相同的 JRE/JDK、用户（uid 100）、入口参数与 OTel agent 版本。
- **为什么 app 走 Sentinel**：“当前完整形态”按 ha overlay 的设计口径启动；单机 `jl-redis` 保留给
  纯 dev 工具，不再作为 app 的 L2。
- **两个集成缺陷都是“完整形态才暴露”的**：P1 只验证过 app-3 在 dev（standalone）下运行；
  切到 ha 后 Boot 2.7 的 Sentinel 键名不同。D7 壳此前只做只读对比，加入异步秒杀后才需要 broker 环境。
- **健康检查而非验收**：用户要亲自测试，verify/smoke 会建订单、耗库存；按选择不执行。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| verify 与 Sentinel 的口径差异 | app 现在把登录令牌/缓存/库存写到 HA 主，`verify.sh` 的 Redis 内容检查仍硬编码 `jl-redis`，直接跑会有 4 项 FAIL（历史已知）；验收前需按 step-24b 还原 app 到 standalone | 已知 |
| 启动期短暂 unhealthy | app-1/2 冷启动约 2–3 分钟，期间 Docker healthcheck 会显示 unhealthy，`/actuator/health` 随后转 UP；`up.sh` 首次等待可能超时 | 记录 |
| `.env` 补键值 | 144 个缺失键用 `.env.example` 默认值补齐；若你之前对这些键有自定义（端口/IP/开关），需再核对 | 待确认 |
| D7 壳的 MQ | 壳现在也会消费 `seckill.order.queue`，与 app-1/2/3 竞争消费；D7 对比时应停止壳或接受消息被壳处理 | 记录 |
| 台账为空 | 全新安装后实例配置未配置；需要在控制台按需创建/应用 JVM 配置 | 正常 |

# 待定事项和注意事项

1. **默认连接形态**：app-1/2/3 连 Sentinel；MySQL 仍是 `MYSQL_MODE=standalone`（单机 `jl-mysql`）。
   如果要测 Router 读写分流，改 `.env` 的 `MYSQL_MODE=router` 后重建 app 层。
2. **控制台令牌**：`X-Lab-Token` 默认 `lab-token`（见 `.env` 的 `LAB_TOKEN`）。
3. **D7 壳**：`jl-seckill-app-jdk8` 是独立容器，不参与 Nginx 分流；Prometheus job `seckill-app-jdk8`。
4. **不要再跑 `down.sh --purge` 除非确实要清数据**；普通重启用 `down.sh`（保留卷）。
5. 重新打包命令见 README §2 的“三个 Java 业务镜像都提供了离线运行时 Dockerfile”。

---

## 附录：本次关键输出

```text
Images:
  java-learn-lab/seckill-app:jdk21        java 21.0.12.1, uid=100, otel 2.10.0
  java-learn-lab/seckill-app:jdk8         java 1.8.0_504,  uid=100, otel 2.10.0
  java-learn-lab/chaos-controller:jdk21   java 21.0.12.1

Stack:
  jl-* running = 30, Prometheus targets 16/16 up
  app-1/2/3 health/app = UP; app-3 info runtime=jdk8; shell info runtime=jre8
  MySQL cluster 3/3 ONLINE; Router 6446 -> mysql-1, 6447 -> mysql-2
  Redis: jl-redis-1 master + jl-redis-2 replica + sentinel-1/2; app REDIS_MODE=sentinel

Business:
  GET  /health               200
  GET  /lab/                 200
  GET  /api/products/1       200
  POST /api/login            200, expiresInSeconds=1800
```
