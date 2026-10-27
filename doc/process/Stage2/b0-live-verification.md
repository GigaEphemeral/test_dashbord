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

现场：一次性 `curlimages/curl:8.10.1` 容器挂 `jl-net`（app 镜像无 curl，见手册 §4.2），脚本
`/tmp/r1/b002.sh` 只读挂载进 `/work`；`--entrypoint sh /work/b002.sh jl-app-1 10`。
证据：`/tmp/r1/b002-baseline.log`、`/tmp/r1/b002-burst.log`、`/tmp/r1/b002-after.log`、`/tmp/r1/b002-revert.log`。

1. **关 `lock` 开关（POST /lab/toggles，app 侧）** — `{"key":"lock","value":false}` 返回
   `{"lock":false,...,"cache":true}`，随后 GET 复核同为 false。
2. **制造库存拒绝**（product 10 当时 `available=100`）：
   - 单次超量：`quantity=100000` → `{"code":"40901","message":"Sold out"}` / `HTTP=409`；
   - 12 并发 `quantity=50` → HTTP 码直方图 `2 × 200`、`10 × 409`。
     合计「数据库防线挡下」应为 1 + 10 = **11**。
3. **Prometheus 即时查询**（经 Nginx `/prom/`）：

   ```
   stock_oversold_total                       app-1 = 11   app-2 = 0  app-3 = 0
   increase(stock_oversold_total[5m])         app-1 = 11.141345032412053  app-2/3 = 0
   sum(increase(stock_oversold_total[5m]))    = 11.14522362960753          (> 0 ✓)
   ```

   `increase()` 的 11.14 是 Prometheus 对采样边界的外推（计数真实值 11），不是多计。
   基线（打流量前）三条序列已在、值为 0，验证了 B0-02「急切注册 ⇒ 零值序列先于流量存在」。
4. **订单表未超卖**（`docker exec jl-mysql mysql -N ... seckill`，`MYSQL_MODE=standalone` 的数据面即 dev 单节点）：

   ```
   select product_id,available from inventory_stock where product_id=10;   -> 10  0
   select count(*),sum(quantity) from order_order where product_id=10 and user_id='r1-oversold';  -> 2  100
   select min(available) from inventory_stock;                              -> 0
   select count(*) from inventory_stock where available<0;                  -> 0
   ```

   售出 100 = 初始 `available` 100，`available` 落在 0 且全表无负值 ⇒ 条件 UPDATE 确实把 11 次超卖挡在数据库之前。

### 现场复位

- `POST /lab/toggles {"key":"lock","value":true}` 对 app-1/2/3 各一次，三台 GET 复核均为
  `{lock:true, idempotency:true, mq:true, ratelimit:true, circuitbreaker:true, cache:true}`（默认态）。
- 测试数据回收：`delete from order_order where user_id='r1-oversold'`（残留 0 行）、
  `update inventory_stock set available=100 where product_id=10`（回到测试前值）。

### 逐项结论

| 步骤文档登记的项 | 结果 |
|---|---|
| `/actuator/prometheus` 导出 `stock_oversold_total` 名称不变 | PASS（Prometheus 端序列名即 `stock_oversold_total`，标签 `exported_instance`=app-1/2/3、`instance`=app-N:8080） |
| 手工制造库存不足 → 样本计数 +1 | PASS（挡下 11 次 → 计数 11，`increase[5m]` = 11.14 > 0） |
| 同步/异步各一次阻止不双计（样本层面） | 部分——本轮只造了同步 `POST /api/orders` 样本（清单口径即此）；异步消费路径未单独造样本，单测层已证（用例级各 +1） |

**结论：PASS**


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
