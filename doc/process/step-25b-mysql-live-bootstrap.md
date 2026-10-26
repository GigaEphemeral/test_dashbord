> 步骤交接 · M3-2a 实机引导与 Router 修复（step 25b）
> 日期：2026-10-27
> 分支 / HEAD：`feature/ha-lab`；本步提交 `18f854e fix: make the 8.4 Router serve the RW/RO routes in the lab db-ha tier`，文档随后 `docs:` 提交
> 关联任务：#50（M3-2a 实机验收）；下游 #51（应用接 Router 双数据源与集群指标，本步**不做**）
> 设计依据：`doc/design/2026-10-26-ha-concurrency-lab-design.md` §8.3（InnoDB Cluster 拓扑）、§3.1（内存预算）、§3.3（profile）
> 运行状态：db-ha 层**在线并已服务**（3 节点组复制单主 + Router 6446/6447 + 3 exporter）；dev 常驻栈 16 容器与 Redis HA 层未触碰。

# 当前进展

- 本次是 db-ha 层在本机的**第一次真实引导**。上一位 agent 完成了节点成组（提交 `22ea2b3`），留下三个未提交文件和一个"Router 容器 healthy 但 6446 拒连、6447 回落到主库"的残局。
- 根因已定位并修复：**8.0 Router 配 8.4 服务器**。Router 8.0 把单主组的三个成员全部判成 `mode=RO`，于是按设计**主动关闭读/写监听**（`Stop accepting connections for routing:bootstrap_rw listening on 6446`），而健康检查只是 TCP 探测，容器仍显示 healthy。切到 `container-registry.oracle.com/mysql/community-router:8.4`（8.4.10）并重新引导后，6446 跟随主库、6447 在 SECONDARY 间轮询，`db-cluster-status.sh` 返回 `RESULT: ok`。
- 第二个坑是配置里的 `[logger] level`：8.0/8.4 都**没有** `--log-level` 之类的开关，级别只存在于生成配置的 `[logger]` 段，且非法值让 Router 直接拒启（`Configuration error: Log level 'debug_3' is not valid`）。之前有一次手工把卷里的级别改成 `debug_3`，重启后错误随卷持久化——这就是"日志只有一行错误、端口一个都不听"的来源。现已改为外置且校验的 `MYSQL_HA_ROUTER_LOG_LEVEL`。
- 上一位 agent 的三个未提交文件已审阅：SSL `REQUIRED` 的 2061 论证、`user: 0` 的 init 提权、root 后 `chown -R mysqlrouter` 三处**成立并保留**；"8.4 支持 `--log-to-console`/`--config-name`/`--conf-scope`"的判断**不成立**（8.4.10 实测全部 `unknown option`），相应注释与参数已改正/删除。
- 验收证据见附录 B；内存口径见"设计逻辑"第 4 条。

# 完成功能项

## 1. Router 真正服务（本步核心）

- `compose.mysql.yml`：两个 Router 服务的镜像默认值从 `mysql/mysql-router:8.4.4`（Docker Hub 上不存在该 tag，实际拉到的是 8.0）改为 `container-registry.oracle.com/mysql/community-router:8.4`；新增 `MYSQL_HA_ROUTER_LOG_LEVEL: ${MYSQL_HA_ROUTER_LOG_LEVEL:-info}` 传给引导 job；删除 `MYSQL_HA_ROUTER_CONF_SCOPE` 键。
- `router-bootstrap-entrypoint.sh`：
  - 新增 `validate_log_level`：小写化后比对 `debug|error|fatal|info|note|system|warning`，非法值在写任何文件前就失败并打印合法枚举（Router 的解析本身大小写不敏感，生成配置里的默认值就是 `INFO`）。
  - `bootstrap_args` 用 `--conf-set-option=logger.level=<level>` 写入级别；`apply_log_level` 在引导后与"配置已存在、跳过引导"两条路径上都把 `[logger] level=` 校正为当前值——**旧卷里残留的非法级别会被就地修复，不必手动删卷**。
  - 删除 `--conf-scope` / `--config-name` 的发射逻辑（8.4.10 与 8.0 都不认；bootstrap 默认就写 6446/6447 + 未使用的 6448/6449），日志行改打 `log-level=`。
  - 保留：`--ssl-mode=$MYSQL_HA_ROUTER_SSL_MODE`（默认 `REQUIRED`）、`--user` 仅在 uid=0 时追加、`chown_data_dir`、`--force`、口令三模式（`stdin` 默认）。
- 长驻 `mysql-router` 的 `command:` 只留 `--config <dir>/mysqlrouter.conf`：`--log-to-console` 在 8.0/8.4 都不存在，已删除并把"日志在卷里的 `log/mysqlrouter.log`"写进注释（读取命令见附录 B）。

## 2. 状态脚本口径

- `db-cluster-status.sh` 成功行由 `RESULT: healthy - ...` 改为 `RESULT: ok - ...`（退出码语义不变），使验收门与脚本输出一致；`db-ha-up.sh` 只判退出码，不受影响。

## 3. 本机 `.env`（gitignored，不入库）

- `MYSQL_HA_ROUTER_IMAGE` 指向 Oracle 8.4 tag；新增 `MYSQL_HA_ROUTER_LOG_LEVEL=info`；删除已失效的 `MYSQL_HA_ROUTER_CONF_SCOPE`；重新引导期间临时置 `MYSQL_HA_ROUTER_FORCE_BOOTSTRAP=true`，**验收后已改回 `false`**。

## 4. 文档

- `.env.mysql.example` 同步：镜像默认值与"为什么必须是 8.4 Router（8.0 把 8.4 成员判成 RO、静默关掉 6446）"的注释；`MYSQL_HA_ROUTER_LOG_LEVEL` 的取值枚举与"非法值拒启"说明；SSL `REQUIRED` 的 2061 论证（保存上一位 agent 的原文）。

# 设计逻辑

1. **镜像与服务器必须同代**：Router 通过元数据缓存判定成员角色（`performance_schema.replication_group_members` + `show status like 'group_replication_primary_member'`）。该 status 变量在 8.4 已返回 0 行，8.0 Router 因此把三人全判 `RO`，并按 `first-available` 策略"没有可写成员 → 关闭 6446"。这不是配置错误，是版本混搭，所以修法是换镜像而不是改路由策略。
2. **日志级别是配置项不是命令行项**：Router 只在 `[logger] level` 读取级别，且对未知值**硬失败**。把它外置成 `MYSQL_HA_ROUTER_LOG_LEVEL` 并做枚举校验，既满足"配置外置"规约，又避免"手改卷内文件 → 重启后又崩"的不可复现状态。
3. **init 仍以 root 跑**：8.4 镜像同样以 uid 999(`mysqlrouter`) 运行，镜像内没有 `/var/lib/mysqlrouter`，新卷由 root 创建；引导 job 以 root 写配置后 `chown -R mysqlrouter`，长驻进程以 999 只读配置。实测卷内 `mysqlrouter.conf` 与 `data/` 均为 `mysqlrouter:mysqlrouter`（`docker exec jl-mysql-router id` → `uid=999`）。
4. **内存：接受 ≈2.1GB，不硬压到 1.8GB**（在案偏差，与 step-25a 同一结论）
   - 声明合计（稳态）= 3×640m + 64m + 3×48m = **2128MiB ≈ 2.1GB**（引导 job 128m 一次性，不计入）；设计 §3.1 写 ≈1.8GB。
   - 实测（`docker stats --no-stream`）：节点 630.8 / 631.5 / 626.8 MiB，Router 14.86 MiB，三个 exporter 2.31 / 2.43 / 2.31 MiB，**合计 1911.0 MiB ≈ 1.87GB**，实际落在 1.8GB 附近，天花板比设计高 0.3GB。
   - 取舍：另一条路是把 `MYSQL_HA_NODE_MEM_LIMIT` 与 `MYSQL_HA_INNODB_BUFFER_POOL_SIZE` 成对下调（如 512m / 96M）让声明合计 ≤1.8GB，但那需要重建并重新引导三个节点，本步无法在保留现有集群的前提下验证；而实测已经证明真实占用 ≈1.87GB。故**记录 2.1GB 为接受值**，并把设计 §3.1 的数字改为约 2.1GB 作为后续文档动作（本任务硬规则禁止改 `doc/` 下其它文件，见"待定事项"）。
   - 注意节点 cgroup 用量已达 limit 的 97–99%，其中含可回收页缓存；若后续出现节点 OOM 或 `InnoDB Buffer Pool` 命中率异常，再走"512m + 96M"那条路。

# 存在缺陷项&TODO

- **未验证：应用经 Router 访问**（#51 的范围）。本步只证明了 `mysql` 客户端经 6446/6447 能读写；`seckill-app-*` 仍连单机 `mysql`，`MYSQL_MODE=router` 与 `mysql_cluster_*` 指标尚未接线。
- **未验证：故障转移与重加入**（`db-failover` 动作、被踢节点 `clone` 重加入）。Router 切主依赖同一套元数据缓存，预期成立，但本步没有做停机演练。
- **未验证：prometheus 抓取**（`mysql-ha-scrape.yml` 仍未接进 `prometheus.yml`，属主会话接线项）。
- 状态脚本的 6447 探测只做一轮，若想让"RO 不是回落主库"更醒目，可在输出里带上路由名（当前靠 `read_only=1` + 轮询到不同 `server_id` 判断，本步实测已足够）。
- `db-ha-up.sh` 的提示文案仍写 `RESULT: healthy`；实际脚本已输出 `RESULT: ok`（文案在脚本内，未改，避免扩大本步范围）。

# 待定事项和注意事项

## 引导 / 复验 / 拆除（Runbook 指针）

```bash
# 引导（幂等；Router 配置已存在时只校正日志级别并跳过）
bash code/lab/scripts/db-ha-up.sh
# 只读状态（本步验收门）
bash code/lab/scripts/db-cluster-status.sh
# 拆除（只拆本层，保留卷与 dev 栈）
bash code/lab/scripts/db-ha-down.sh            # --dry-run 预演，--purge 才删集群卷
```

- 重新引导 Router（例如改了镜像或日志级别）：把 `MYSQL_HA_ROUTER_FORCE_BOOTSTRAP=true` 写进 `.env`，然后
  `docker compose --project-name java-learn-lab --project-directory code/lab/docker --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml -f code/lab/docker/compose.mysql.yml --profile db-ha up -d --force-recreate mysql-router-init mysql-router`，
  完成后再改回 `false`。
- 节点重加入：见 `doc/process/step-25a-mysql-cluster-compose.md` 附录 A（成员 `RECOVERING` 时等 clone 搬完；`ERROR`/`ON_NO_QUORUM` 先看 `docker logs jl-mysql-cluster-init`）。
- 拆除顺序有讲究：`db-ha-down.sh` 先 `stop` 再 `rm`（InnoDB 干净关闭），**不要**用 `docker compose down`。

## 安全

- 本步所有探测口令都在节点容器内由 `MYSQL_PWD="$MYSQL_ROOT_PASSWORD"` 展开（变量名进 argv，值不进），未打印、未写入任何文件或提交。
- 卷内 `mysqlrouter.conf` 只含 Router 自己的元数据账号名，口令在 `data/keyring`；`MYSQL_ROOT_PASSWORD` 只在引导 job 的 stdin 中出现一次。

## 本步触碰范围

- 改动文件：`code/lab/docker/compose.mysql.yml`、`code/lab/docker/.env.mysql.example`、`code/lab/docker/mysql-cluster/router-bootstrap-entrypoint.sh`、`code/lab/scripts/db-cluster-status.sh`、本文件；本机 `.env`（不入库）。
- 未触碰：dev 栈 16 容器、Redis HA 层、`seckill-app-*`、`chaos-controller`、`doc/` 其它文件、任何卷。

# 附录 A：验收命令与期望输出

```bash
# 1) 集群 + 路由（门禁：末行 RESULT: ok，且两行路由都有后端）
bash code/lab/scripts/db-cluster-status.sh
#    期望：
#      read/write :6446   -> mysql-1        (server_id=1, READ_WRITE)
#      read-only  :6447   -> mysql-2        (server_id=2, READ_ONLY)   # 也可能是 mysql-3
#      RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY.

# 2) 服务数不泄漏（期望 25 / 16）
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  -f code/lab/docker/compose.dev.yml -f code/lab/docker/compose.mysql.yml \
  --profile db-ha config --services | wc -l     # 25
#    （去掉 -f compose.mysql.yml 与 --profile db-ha）→ 16

# 3) Router 日志（无 --log-to-console，日志在卷内）
docker exec jl-mysql-router tail -n 20 /var/lib/mysqlrouter/log/mysqlrouter.log

# 4) 内存
docker stats --no-stream jl-mysql-1 jl-mysql-2 jl-mysql-3 jl-mysql-router
```

# 附录 B：本步实测输出（逐字）

```text
=== Router routes (via mysql-router; host publishes 127.0.0.1 only) ===
read/write :6446   -> mysql-1        (server_id=1, READ_WRITE)
read-only  :6447   -> mysql-3        (server_id=3, READ_ONLY)

RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY.
```

真实读写探针（经 6446 建库建表写一行，经 6447 读三次，随后 DROP DATABASE 清理）：

```text
--- 6446 write ---
6446 target: mysql-1 server_id=1 read_only=0
row inserted by mysql-1 server_id=1
--- 6447 reads (x3, round-robin) ---
6447 target: mysql-2 server_id=2 read_only=1 rows=1
6447 target: mysql-3 server_id=3 read_only=1 rows=1
6447 target: mysql-2 server_id=2 read_only=1 rows=1
--- cleanup ---
dropped lab_probe, remaining schemas=0
```

内存（`docker stats --no-stream`，单位 MiB）：

```text
jl-mysql-1	630.8MiB / 640MiB	98.57%	13.15%
jl-mysql-2	631.5MiB / 640MiB	98.67%	9.63%
jl-mysql-3	626.8MiB / 640MiB	97.94%	2.41%
jl-mysql-router	14.86MiB / 64MiB	23.21%	5.94%
jl-mysql-1-exporter	2.305MiB / 48MiB	4.80%	0.00%
jl-mysql-2-exporter	2.426MiB / 48MiB	5.05%	0.00%
jl-mysql-3-exporter	2.309MiB / 48MiB	4.81%	0.00%
合计 1911.0MiB（≈1.87GB）；声明稳态上限 2128MiB（≈2.1GB）
```
