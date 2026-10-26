# 步骤 31：MySQL Router 真机切换演练（写 6446 / 读 6447 / 杀主重路由）

> 任务/步骤：step-31（M3-2c 真机验收，Router 模式 + 主库故障切换）
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab`（演练前 `623d8f1`；提交时基线 `f0422cb`，其间另一会话合入 D11 面板）
> 关联任务：M3-2b（db-ha）、M3-2c（集群指标）、MYSQL_MODE 路由开关
> 测试 / verify：**未运行** `verify.sh` / `smoke.sh` / `console-smoke.sh`（批次门禁负责）；本步只做真机演练与 compose 渲染计数
> 运行状态：交付时 dev 栈 16 服务全部 healthy；db-ha 层 UP；app 回到 `MYSQL_MODE=standalone`；主库为 `jl-mysql-2`（演练后的正确结果）

# 当前进展

- 重建并替换了两个变更镜像：`java-learn-lab/seckill-app:jdk21`、`java-learn-lab/chaos-controller:jdk21`（只 build 这两个服务，未动其它镜像）。
- 三个 app 实例以 `MYSQL_MODE=router` 起来并通过 Nginx 完成真实读写：商品读 200、下单 200，写行落在当时主库 `jl-mysql-1`；路由器 6446→server_id=1(READ_WRITE)、6447→server_id=2(READ_ONLY)。
- 通过 chaos API 对 `jl-mysql-1` 执行 `kill`（TTL 45s）：Router 把 6446 切到 `jl-mysql-2`；Nginx 商品读全程 200；TTL 到点 `jl-mysql-1` 自动 `start` 并以 SECONDARY 重新入组。
- datastore 白名单按本步需要扩到 `^jl-(redis|mysql)-[1-3]$`；`jl-mysql` 与其容器 ID 前缀仍被 `40301` 拒绝。
- **发现一个真实缺陷**：`chaos-controller` 的 InnoDB Cluster 探针在同一环境下每次都 6s 超时（`mysql_cluster_probe_up=0`，成员/主库序列为空）。用同镜像内的 docker-java 3.7.1 复现：`exec` 带 stdin 时 `awaitCompletion` 超时且 stdout/stderr 全空，不带 stdin 时 201ms 正常返回。按任务硬规则未改 Java 代码，只记录（见「存在缺陷项」）。
- 演练数据已清理：`order_order` 中 `drill31-%` 行删除，`inventory_stock.product_id=1` 恢复 100。

# 完成功能项

- **镜像重建**：`docker compose ... build app-1 chaos-controller` → 两个镜像 Built；`up -d --no-deps --force-recreate app-1 app-2 app-3 chaos-controller`。
- **Router 模式启动证据**（app-1，app-2/3 同时刻同文案）：
  ```
  {"@timestamp":"2026-10-26T21:22:55.568548433Z",...,"instance":"app-1","message":"MySQL access mode: router (write endpoint jdbc:mysql://jl-mysql-router:6446/seckill, read endpoint jdbc:mysql://jl-mysql-router:6447/seckill)","logger_name":"com.javalearn.lab.seckill.datasource.DataSourceConfig"}
  ```
  三个实例 `docker inspect .State.Health.Status` 均为 `healthy`。
- **真实流量（经 Nginx 127.0.0.1:8080）**：
  ```
  GET /api/products/1 -> 200
  {"code":"0","message":"OK","data":{"id":1,"name":"极客机械键盘",...},"success":true}
  POST /api/orders -> 200
  {"code":"0","message":"OK","data":{"id":1,"orderNo":"f914d5fa-8562-4936-9224-0d150c11cff0","userId":"drill31-1793049845","productId":1,"quantity":1,"status":"CREATED",...},"success":true}
  ```
- **写入落点与读路由**（在节点容器里直连 Router，凭据只在容器内展开）：
  ```
  mysql-1/2/3 直查 seckill.order_order:
  1  drill31-1793049845  CREATED      （三节点均可见，主库 jl-mysql-1 确认）
  6446: 1  0   （server_id=1，READ_WRITE）
  6447: 2  1   （server_id=2，READ_ONLY，即 secondary）
  ```
- **白名单放开**：`.env` 与 `.env.example` 均改为 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN=^jl-(redis|mysql)-[1-3]$`，compose 默认值同步；代码空默认仍是全拒。
  ```
  jl-mysql      -> HTTP 403 {"code":"40301","message":"target \"jl-mysql\" is a datastore and is refused by chaos.datastore.allowed-target-pattern=\"^jl-(redis|mysql)-[1-3]$\": ..."}
  79dbbf218e65  -> HTTP 403 {"code":"40301","message":"container reference \"79dbbf218e65\" resolves to target \"jl-mysql\" is a datastore and is refused by ..."}
  ```
- **杀主与 TTL 回滚**：
  ```
  POST /lab-api/chaos/instances/jl-mysql-1/actions {"action":"kill","ttlSeconds":45}
  HTTP 202 {"code":"0",...,"data":{"target":"jl-mysql-1","action":"kill","ttlSeconds":45,"expiresAt":"2026-10-26T21:29:00.214892353Z","revertAction":"start","status":"active",...}}
  ```
  - `jl-mysql-1` FinishedAt = `2026-10-26T21:28:26Z`，StartedAt（TTL 回滚）= `2026-10-26T21:29:01Z`。
  - 采样时间线（UTC，每秒一次 Nginx 读，每 5s 一次 6446 查询）：
    ```
    21:28:32 poll_start
    21:28:33..21:31:24 read=200（共 70 次，无一次非 200）
    21:28:44 6446=[2  0]
    21:28:56 6446=[2  0]  ... 21:31:25 6446=[2  0]
    21:30:30 write=200 body={"code":"0","message":"OK","data":{"id":2,"orderNo":"d0588e06-...","userId":"drill31-kill-179304..."}}
    ```
  - 切换前状态脚本（节选）：`mysql-1 ... READ_WRITE ... mysql-1 ONLINE PRIMARY`；`read/write :6446 -> mysql-1 (server_id=1, READ_WRITE)`、`read-only :6447 -> mysql-2 (server_id=2, READ_ONLY)`、`RESULT: ok`。
  - 切换后状态脚本（节选）：
    ```
    jl-mysql-2       mysql-2  3306  READ_WRITE  3  ... mysql-2:3306 ONLINE PRIMARY
    jl-mysql-1       mysql-1  3306  READ_ONLY   3  ... mysql-1:3306 ONLINE SECONDARY
    read/write :6446 -> mysql-2 (server_id=2, READ_WRITE)
    read-only  :6447 -> mysql-1 (server_id=1, READ_ONLY)
    RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY.
    ```
- **Prometheus 查询（原样）**：
  ```
  GET /prom/api/v1/query?query=mysql_cluster_probe_up
  {"status":"success","data":{"resultType":"vector","result":[{"metric":{"__name__":"mysql_cluster_probe_up",...,"job":"chaos-controller"},"value":[1793050323.293,"0"]}]}}
  GET /prom/api/v1/query?query=mysql_cluster_primary          -> {"status":"success","data":{"resultType":"vector","result":[]}}
  GET /prom/api/v1/query?query=mysql_cluster_member_state     -> {"status":"success","data":{"resultType":"vector","result":[]}}
  mysql_cluster_probe_failure_total = 85（截至恢复前），success_total = 0
  ```
  即：指标**没有**随杀主变化——原因是探针本身从未成功（见缺陷），不是集群视图问题。
- **恢复**：三个 app 以 standalone 重新创建，启动行
  `MySQL access mode: standalone (write endpoint jdbc:mysql://mysql:3306/seckill, read endpoint jdbc:mysql://mysql:3306/seckill)`，均 healthy；
  `Redis access mode: standalone` 未变；compose 渲染计数 `dev=16 / ha=25 / db-ha=25`；Nginx `GET /api/products/1` 连续 200；`db-cluster-status.sh` → `RESULT: ok`。

# 设计逻辑

- 路由模式仍是“模式决定端点、端点不回退”：`MYSQL_MODE=router` 时写池/读池分别使用 `DB_WRITE_URL`/`DB_READ_URL`，两个键缺失即启动失败；standalone 时这两个键被忽略。本次只在 gitignored `.env` 里改值，不重建镜像。
- 6446 是读写端口，Router 跟随 PRIMARY；6447 只读，轮询 secondaries。因此“写在主库、读在从库”可以用 `SELECT @@server_id, @@read_only` 直接取证，无需抓应用 SQL。
- 白名单是“整名匹配 + 关键字先判”的双门：`^jl-(redis|mysql)-[1-3]$` 只放行两组 HA 数据节点，dev 单节点 `jl-mysql`、`*-exporter`、`jl-mysql-10` 都不匹配；裸 ID 先解析成容器名再判，解析后仍按 `jl-mysql` 拒绝。
- TTL 回滚走 `revertAction=start`，有意绕开白名单（已放行的故障必须能撤销）；45s TTL 让“新主选举 → 旧主回组”在一个可观察窗口内完成。
- 演练数据用 `drill31-` 前缀标识；订单会扣库存，所以清理时同时恢复 `inventory_stock=100`。

# 存在缺陷项&TODO

- **P1：InnoDB Cluster 探针在本机永远超时，`mysql_cluster_*` 指标不可用。**
  - 现象：controller 每 10s 打 `Timed out after 6s while executing a command in jl-mysql-1 (aborting the stream)`，`probe_up=0`、`member_state`/`primary` 无序列。
  - 复现（同镜像、同 docker-java 3.7.1、同 httpclient5 transport，代码与 `DockerContainerGateway#execInContainer` 相同）：带 stdin 调用 `timedOut=true ms=6030`，stdout/stderr 为空；同一命令改成不带 stdin：`timedOut=false ms=201`。
  - 结论：`execStartCmd(...).withStdIn(ByteArrayInputStream)` + `awaitCompletion` 在本机 Docker 上收不到完成信号；探针把 SQL 走 stdin 的设计因此不可用。候选修复：SQL 改走 argv（`-e`）或显式关闭 stdin/发送 EOF，并补一个真实 Docker 的集成测试（现有单测全是 gateway mock）。
  - 按任务硬规则本步**未改 Java 代码**，只记录；D11 面板在此期间会显示 `probe_up=0`，不能当作集群结论。
- 6447 在两次采样中分别落到 mysql-2 / mysql-3 / 回组后的 mysql-1，说明是按可用从库轮询；但没有应用侧 SQL 日志证明“某次读请求确实走了 6447”，只证明了端点配置 + Router 端口行为。

# 待定事项和注意事项

- 下一步应先修探针 stdin 缺陷（P1），再重跑本演练，补上 `mysql_cluster_primary`/`member_state` 的真实变化证据；修复前不要用这些指标做验收。
- 白名单已按交付配置放开到 HA MySQL 三节点（`jl-mysql` 仍拒绝）；这是安全边界变更，后续若不需要杀 MySQL 主库，应把 `.env` 收回 `^jl-redis-[1-3]$`。
- 演练后的正确拓扑是 `jl-mysql-2` 为主、`jl-mysql-1/3` 为从；不要为了“回到 mysql-1 为主”再制造一次切换。
- 恢复 app 时重建容器会短暂 502（本次约 20s，Nginx 仍指旧 IP，重载后恢复）；杀主窗口内 Nginx 读没有 5xx，两者不要混淆。
- 未运行 `verify.sh`/`smoke.sh`/`console-smoke.sh`；批次门禁负责。

---

## 补充（2026-10-27）：探针 exec 挂死缺陷的修复与真机复验

本步发现的 `DockerContainerGateway#execInContainer` 挂死（stdin 挂上后容器进程拿不到 EOF，`timedOut=true`、stdout/stderr 为空）已在 `fcd287d` 修复：

- 探针不再使用 stdin：SQL 作为容器环境变量 `MYSQL_CLUSTER_QUERY` 传入，容器内 `bash -c '... exec mysql ... -e "$MYSQL_CLUSTER_QUERY"'` 执行；口令仍只在容器内展开。
- `application.yml` 的 `chaos.mysql-cluster.command` 同步更新，并在注释里写明「SQL 不得走 stdin」的原因（docker-java 3.7.1 行为）。
- 回归：`mvn -pl services/chaos-controller -am test` → **138 tests / BUILD SUCCESS**。

真机复验（重建控制器镜像后）：

```
mysql_cluster_probe_up      -> 1
mysql_cluster_member_count  -> 3
mysql_cluster_primary{member="mysql-2"} -> 1
mysql_cluster_member_state{member="mysql-1",role="SECONDARY",state="ONLINE"} -> 1
```

即探针在真栈上按 10s 产出成员与主节点指标，D11 数据面可用；`mysql-2` 为主是本步杀主演练后的正确结果。

未做：探针在「探测节点本身被停」时的 `probe_up=0` 仍需一次针对性演练（当前默认探测 `jl-mysql-1`，与被杀节点重合时应把探针容器改到非目标节点）。
