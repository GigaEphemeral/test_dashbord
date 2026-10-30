# JAVA-LEARN · 本地高可用 / 高并发演示实验室

一套可以在本机 Docker 上完整跑起来的 **制造故障 → 观察症状 → 执行处理 → 对比效果** 教学实验室。

- 三实例秒杀应用（app-1/app-2 为 JDK 21，app-3 为 JDK 8；另有可选 JDK 8 对照外壳），Nginx 轮询分流
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
| JDK | PATH 上 JDK 21；Maven 实际运行在 JDK 25；app-3/JDK 8 以 `--release 8` 编译、在容器内 `eclipse-temurin:8-jdk-alpine` 运行 |
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
bash code/lab/scripts/up.sh --profile ha          # + Redis 2 数据 + 2 Sentinel
bash code/lab/scripts/up.sh --profile dual-jdk    # + JDK 8 外壳
bash code/lab/scripts/up.sh --profile chaos-proxy # + Toxiproxy（与 ha 互斥）
bash code/lab/scripts/db-ha-up.sh                 # MySQL InnoDB Cluster + Router
bash code/lab/scripts/down.sh                     # 停止；数据卷保留
```

> **JDK 8 口径（2026-10-30，P1 完成）**：默认就是 **2×JDK21 + 1×JDK8**——app-1/app-2 使用
> JDK 21 外壳，app-3 使用 JDK 8 业务外壳；三者同 Nginx 上游、同 MySQL/Redis/RabbitMQ，都承接
> 登录、商品读（两级缓存）、下单与异步秒杀。`dual-jdk` 额外加的是 **D7 对照外壳**
> `jl-seckill-app-jdk8`，不参与分流，仅用于同机对比。app-3 的业务/缓存/回读一致性见
> `doc/process/Stage2/step-S2-B5-05e-app3-jdk8-parity.md`。

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

三个 Java 业务镜像（jdk21 / jdk8 / controller）都提供了离线运行时 Dockerfile：

```bash
cd code && mvn -pl services/seckill-app-jdk21,services/seckill-app-jdk8,services/chaos-controller \
  -am -DskipTests package

# JDK 21 壳：先从“已带 OTel agent 的旧镜像”复制 agent，再覆盖同名 tag
docker tag java-learn-lab/seckill-app:jdk21 java-learn-lab/seckill-app:otel-src
docker build -f code/services/seckill-app-jdk21/Dockerfile.runtime \
  -t java-learn-lab/seckill-app:jdk21 code/services/seckill-app-jdk21
docker rmi java-learn-lab/seckill-app:otel-src

# JDK 8 壳（app-3）与控制器
docker build -f code/services/seckill-app-jdk8/Dockerfile.runtime \
  -t java-learn-lab/seckill-app:jdk8 code/services/seckill-app-jdk8
docker build -f code/services/chaos-controller/Dockerfile.runtime \
  -t java-learn-lab/chaos-controller:jdk21 code/services/chaos-controller
```

`Dockerfile.runtime` 从 `java-learn-lab/seckill-app:jdk21` 复制同一版本的 OTel agent
（`OTEL_SOURCE_IMAGE` 可覆盖），因此三个运行时的 agent 版本不会漂移。首次没有旧 jdk21
镜像时，先用官方 `Dockerfile`（容器内 Maven 可联网）构建一次。

控制台改动只需在主树 `cd code/lab/dashboard && npm run build`（Nginx 直接挂载 `dist/`）。

## 3. 使用流程

1. **场景向导 · 变量与配置组**：app-1/app-2（JDK 21）与 app-3（JDK 8）的堆/GC/业务池/Caffeine，
   `校验 → 仅保存 → 应用并重启`；已应用后可点「重启 JVM」（配置不变）；回滚使用上一版配置。
   app-3 与 app-1/2 同列并排、同样参与 run 分流；仅其 JDK 版本与镜像固定为 8/jdk8。
   `dual-jdk` 的对照外壳 `seckill-app-jdk8` 不在实例台账内，只用于 D7 同机对比。
   缓存防御模式、L1 开关与预热也在这里按实例设置。
2. **场景向导**：选择节点与目标 → JVM 配置（三列并排对比）→ k6 参数 → 故障时间线 → 预检查 → 启动。
3. **实时监控与操作**：四排时间轴（请求/k6、JVM 堆/GC、线程池/连接池、Redis/MySQL 数据层，图例带 JVM 参数）、k6 进度、
   实例卡片、按实例隔离的缓存流动、分层连线拓扑；故障注入/线程工具/缓存键/容器日志为右下角悬浮抽屉。
4. **实验记录**：run 台账、预检查、操作审计、本次结果（k6 阈值/环境/分流/KPI）、run 时间线
   （k6 压力、JVM 堆、GC 次数与停顿、缓存命中、数据库 QPS/线程）、导出 JSON、复制为新草稿。
5. **对比与复盘**：选两个 run 对比；把多次导出的 JSON 并排人工比较；向导第 7 步可跑缓存 A/B。

故障注入与线程故障的完整行为、可注入线程和 OOM 限制见 [`doc/process/Stage2/故障注入使用说明.md`](doc/process/Stage2/故障注入使用说明.md)。



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
| 控制器单测 | 893（`mvn -pl services/chaos-controller -am test`） |
| console-smoke | 159 |
| verify.sh | 110 PASS / 1 WARN / 0 FAIL（WARN=cAdvisor 在 containerd 存储下无法枚举容器） |
| smoke.sh | 7/7 |
| 文档断言 | 92/92（`bash code/lab/scripts/check-doc-claims.sh`） |
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
