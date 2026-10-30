# JAVA-LEARN · 本地高可用 / 高并发演示实验室

一套可以在本机 Docker 上完整跑起来的 **制造故障 → 观察症状 → 执行处理 → 对比效果** 教学实验室。

- 三实例秒杀应用（JDK 21 ×3，另有 JDK 8 对照外壳），Nginx 轮询分流
- MySQL 单机 + InnoDB Cluster（3 节点 + Router）、Redis 单机 + Sentinel 主从
- Prometheus / Grafana / Tempo / OTel / cAdvisor 观测栈
- Vue 控制台：场景向导、k6 压测、故障注入、实时监控、实验台账、单次报告导出
- 全部实验可在 5 分钟内完成，任何动作可暂停/可还原

> 详细设计与实现情况见 [doc/实现情况-2026-10-30.md](doc/实现情况-2026-10-30.md)；
> 安装细节见 [doc/design/安装与打包手册.md](doc/design/安装与打包手册.md)；
> 交付流程见 [doc/process/Stage2/stage2-开发测试与验收计划.md](doc/process/Stage2/stage2-开发测试与验收计划.md)。

## 1. 前置条件

| 项 | 要求（本机实况） |
|---|---|
| 操作系统 | Windows 10 + Docker Desktop（WSL2 后端） |
| 内存 | Docker ≥ 12GB；常驻 dev 栈约 5GB，叠加 ha / db-ha 约 9–10GB |
| JDK | PATH 上 JDK 21；Maven 实际运行在 JDK 25；JDK 8 只有 JRE（容器内运行） |
| Maven | 3.9.x，仓库根 `code/pom.xml` |
| Node | 20+（控制台构建；本机 v24） |
| 端口 | 8080（控制台/Nginx）、3000（Grafana，仅 127.0.0.1）、15672（RabbitMQ，仅 127.0.0.1）、6446/6447（MySQL Router，仅 127.0.0.1） |

## 2. 打包与启动

```bash
# 1) 准备环境变量（首次）
cp code/lab/docker/.env.example code/lab/docker/.env

# 2) 构建镜像（首次或应用/控制器代码变更后）
cd code/lab/docker
docker compose -f compose.base.yml -f compose.dev.yml --env-file .env build

# 3) 启动（dev = 16 个常驻服务）
bash code/lab/scripts/up.sh

# 4) 访问
#    控制台   http://127.0.0.1:8080/lab/      （令牌默认 lab-token，请求头 X-Lab-Token）
#    Grafana  http://127.0.0.1:3000           （admin / admin）
#    Prometheus 经 Nginx：http://127.0.0.1:8080/prom/
```

可选 profile：

```bash
bash code/lab/scripts/up.sh --profile ha          # + Redis Sentinel 拓扑
bash code/lab/scripts/up.sh --profile dual-jdk    # + JDK 8 外壳
bash code/lab/scripts/up.sh --profile chaos-proxy # + Toxiproxy（与 ha 互斥）
bash code/lab/scripts/db-ha-up.sh                 # MySQL InnoDB Cluster + Router
bash code/lab/scripts/down.sh                     # 停止；数据卷保留
```

### 控制器离线重建（Maven Central 不可达时）

`compose build` 会在容器内跑 Maven；若拉不到依赖，用宿主机构建 jar 再打运行时镜像：

```bash
cd code && mvn -pl services/chaos-controller -am -DskipTests package
cd services/chaos-controller
docker build -f Dockerfile.runtime -t java-learn-lab/chaos-controller:jdk21 .
cd ../../lab/docker
docker compose -f compose.base.yml -f compose.dev.yml --env-file .env \
  up -d --force-recreate chaos-controller
```

控制台改动只需在主树 `cd code/lab/dashboard && npm run build`（Nginx 直接挂载 `dist/`）。

## 3. 使用流程

1. **实例与 JVM**：为 app-1/2/3 选择 JDK 镜像、堆/GC/业务池/Caffeine，`校验 → 仅保存 → 应用并重启`；
   已应用后可点「重启 JVM」（配置不变）；回滚使用上一版配置。向导第 3 步与这里共用同一份实例台账。
2. **场景向导**：选择节点与目标 → JVM 配置（三列并排对比）→ k6 参数 → 故障时间线 → 预检查 → 启动。
3. **实时监控与操作**：时间轴（请求/k6/JVM 堆/GC/线程池/连接池）、k6 进度、JVM 参数对照、拓扑热度、
   容器故障注入与缓存实验。
4. **实验记录**：run 台账、预检查、操作审计、本次结果（k6 阈值/环境/分流/KPI）、run 时间线
   （k6 压力、JVM 堆、GC 次数与停顿、缓存命中、数据库 QPS/线程）、导出 JSON、复制为新草稿。
5. **对比与复盘**：选两个 run 对比；把多次导出的 JSON 并排人工比较。

示例报告：[`doc/demo.html`](doc/demo.html)（`run-20261030-001` 的真实数据，自包含单文件，可直接浏览器打开或发给别人）。

常用维护命令：

```bash
bash code/lab/scripts/reset-lab.sh --yes     # 复位业务数据（订单/库存）到确定性基线
bash code/lab/scripts/bench.sh load -- LOAD_DURATION=1m   # CLI 压测并归档报告
bash code/lab/scripts/verify.sh              # 全栈只读验收（109 项）
bash code/lab/scripts/smoke.sh               # 业务冒烟
bash code/lab/scripts/console-smoke.sh       # 控制台端到端冒烟
```

## 4. 验证与质量门禁

| 门禁 | 当前基线（2026-10-30） |
|---|---|
| 控制器单测 | 887+（`mvn -pl services/chaos-controller -am test`） |
| console-smoke | 161 |
| verify.sh | 109 PASS / 1 WARN / 0 FAIL（WARN=cAdvisor 在 containerd 存储下无法枚举容器） |
| smoke.sh | 7/7 |
| 文档断言 | 84/84（`bash code/lab/scripts/check-doc-claims.sh`） |
| env 接线 | `bash code/lab/scripts/test-chaos-env-wiring.sh` |

## 5. 常见问题

- **正式 run 被 BLOCKED**：实例刚重建后 60s 稳定期内会阻断；等稳定期结束。
- **Prometheus 内存/序列增长**：k6 远程写已限制 `systemTags`，仍可在 `.env` 调 `PROMETHEUS_MEM_LIMIT` / `PROMETHEUS_RETENTION`。
- **jcmd 回读超时**：负载下会变慢，默认 10s，可调 `LAB_INSTANCE_JVM_FLAGS_TIMEOUT_SECONDS`。
- **商品 ID 越界**：库内只有 1–10000；越界请求按缓存穿透处理，404 计入 `product_missing`，不计入 `flow_errors`（`PRODUCT_DB_MAX` 可覆盖）。
- **Grafana 登录**：默认 `admin/admin`；改密码需 `docker exec jl-grafana grafana cli admin reset-admin-password <new>`。
- **更多坑位**：见 [doc/avoid_common_error/index.md](doc/avoid_common_error/index.md)。

## 6. 目录

```
code/
  pom.xml                 Maven reactor
  platform/               common（release 8）+ observability（Boot 3 starter）
  services/               seckill-core / app-jdk21 / app-jdk8 / chaos-controller
  lab/
    docker/               compose 分层、Prometheus、Grafana、Tempo、MySQL/Redis 拓扑
    scripts/              up/down/verify/smoke/bench/reset/console-smoke/db-ha-*
    k6/                   压测场景、运行器与报告归档
    dashboard/            Vue 3 控制台
    scenarios/            故障场景 JSON
doc/
  design/                 总体设计、Stage1/Stage2 设计与工程规约
  process/                Stage1/Stage2 交接、验收计划、指标字典
  avoid_common_error/     真实踩过的坑
```
