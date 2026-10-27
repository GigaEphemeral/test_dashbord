# Stage2 B0 收口 · 真机验证记录（R1）

> 执行代理：R1（Stage2 B0 收口真机验证）
> 日期：2026-10-28
> 分支 / 开工 HEAD：`feature/stage2-workbench` @ `4dd4165`（`git status --short` 干净，34 容器在跑）
> 范围：按 `doc/design/安装与打包手册.md` §5.2/§8 正式口径重建应用层镜像到 B0 后代码并显式 `--force-recreate` 替换应用层；逐条做掉 B0-02/03/04/05/08 登记的 unverified 真机项 + 全量 verify.sh 闸门。
> 纪律：数据面指向不变（`MYSQL_MODE`/`REDIS_MODE` 与原容器一致）；不动 `compose*.yml`/`prometheus.yml`/Java 代码；不 down 栈；不杀数据层容器；改 env 走 `.env`。

## 0. 开工自检

| 检查 | 命令 | 结果 |
|---|---|---|
| 分支/HEAD | `git rev-parse --abbrev-ref HEAD` / `--short HEAD` | `feature/stage2-workbench` / `4dd4165` |
| 工作树 | `git status --short` | 空（干净） |
| 栈规模 | `docker ps -q \| wc -l` | 34 |

## 1. 应用层镜像重建 + `--force-recreate`（B0-08 真机项）

### 1.1 构建前实况（证据 `/tmp/r1/pre-build.log`）

```
java-learn-lab/seckill-app:jdk21          cfb7a164e9b3  7 hours ago
java-learn-lab/chaos-controller:jdk21     409a4e944856  7 hours ago
jl-app-1/2/3      -> sha256:cfb7a164e9b3...   （旧镜像）
jl-chaos-controller -> sha256:9ac92f049bc7... （= :jdk21-pre-docs，Stage1 期镜像）
```

最后一个 Java 代码变更提交 `6a41c7c`（2026-10-28 01:12，B0-04 合并）晚于镜像构建时间 ⇒ 运行镜像确实落后于 B0 代码；`jl-chaos-controller` 更是跑在 `:jdk21-pre-docs`（连 7 小时前的 `:jdk21` 都不是）。结论：**确需重建 + 显式 recreate**，与任务前置事实一致。

回滚位（构建前打的备份 tag）：
`java-learn-lab/seckill-app:jdk21-pre-b0r1` → `cfb7a164e9b3`、
`java-learn-lab/chaos-controller:jdk21-pre-b0r1` → `409a4e944856`；
另仓库内已有 `:jdk21-pre-docs`（`694d4586c398`）/`chaos-controller:jdk21-pre-docs`（`9ac92f049bc7`）。

### 1.2 构建命令（手册 §5.2 原文口径，实跑）

```bash
time docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env \
  -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml \
  build app-1 chaos-controller
```

关键输出（`/tmp/r1/build.log`，EXIT=0）：

```
#19 [app-1 build 6/7] RUN mvn -q -pl services/seckill-app-jdk21 -am -DskipTests package   DONE
#24 [app-1 build 7/7] RUN curl -fsSL ... opentelemetry-javaagent-2.10.0.jar && test -s      DONE 3.5s
 Image java-learn-lab/seckill-app:jdk21 Built
 Image java-learn-lab/chaos-controller:jdk21 Built
real    3m7.464s
```

产物（与手册记录的 3m37s 冷构建同量级；本轮 `COPY pom.xml/platform` 命中层缓存，只有 `COPY services` 之后重跑）：

| 镜像 | 新 image ID |
|---|---|
| `java-learn-lab/seckill-app:jdk21` | `5b18a49d504c` |
| `java-learn-lab/chaos-controller:jdk21` | `f83b32f914a6` |

### 1.3 recreate 命令（手册 §8「重建单个服务（不动数据容器）」口径 + B0-08 `--force-recreate` 真机项）

```bash
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env \
  -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml \
  up -d --no-deps --force-recreate --wait --wait-timeout 240 \
  app-1 app-2 app-3 chaos-controller
```

输出要点（`/tmp/r1/recreate.log`）：`Container jl-app-1/2/3 Recreated` → `Started` → `Healthy`；`jl-chaos-controller Started/Healthy`。**首跑 EXIT=1**：`--wait` 在 `container jl-app-3 is unhealthy` 时返回——回查 `docker inspect jl-app-3 .State.Health.Log` 末三条为 `503 → exit 0 → exit 0`，即等待窗口正好落在 start_period 边界的一次 503 上，数秒后转 healthy；属**等待时序抖动，非实现缺陷**（健康门是 `/actuator/health/app`，`--wait` 无额外裕量）。

复核实况（`/tmp/r1/post-recreate.log`）：

```
jl-app-1/2/3        -> sha256:5b18a49d504c...  Up (healthy)   ← 与 :jdk21 新指向一致
jl-chaos-controller -> sha256:f83b32f914a6...  Up (healthy)
docker ps -q | wc -l = 34                     ← 未增删容器
```

数据面指向逐项比对（与 1.1 重建前 `docker inspect jl-app-1` 一致，无回退）：

```
MYSQL_MODE=standalone          REDIS_HOST=redis  REDIS_PORT=6379   MYSQL_HOST=mysql
DB_WRITE_URL=jdbc:mysql://jl-mysql-router:6446/seckill?...
DB_READ_URL=jdbc:mysql://jl-mysql-router:6447/seckill?...
LAB_TOGGLE_{LOCK,IDEMPOTENCY,MQ,RATELIMIT,CIRCUITBREAKER,CACHE}=true
```

> 注：`REDIS_MODE` 在重建前后的 app 容器 env 中均**不存在**（当前栈是 base+dev 组装，Sentinel 的 `x-app-redis-ha-env` 未叠加）；`container_name`/`config_files` 标签仍为 `compose.base.yml,compose.dev.yml`，与重建前一致。`.env` 全程未改。

新代码确实生效的旁证（重建后立即取 `GET jl-app-1:8080/lab/config`，经一次性 curl 容器）：响应 `data` 键为
`[app, env, instance, jvmVersion, toggles, cacheConfig]`，其中 **`cacheConfig` 有 59 条**、每条带
`configKey/env/value/defaultValue/source`，`source` 全为 `systemEnvironment`——`/lab/config` 的 `cacheConfig`
区块正是 B0-04（`de9e2fc`）才有的接口，旧镜像不会有该字段。

**结论：PASS**（镜像重建 + 显式 `--force-recreate` 真机跑通；`--wait` 的 503 抖动已记录并复核为健康）。

## 2. B0-02 `stock_oversold_total` 真机样本

（进行中）

## 3. B0-03 Caffeine L1 统计序列

（进行中）

## 4. B0-04 有效值回读

（进行中）

## 5. B0-05 query string 经 Nginx

（进行中）

## 6. B0-08a 镜像身份脚本与 bench 归档

（进行中）

## 7. 全量闸门 verify.sh

（进行中）

## 8. 现场复位与收尾

（进行中）
