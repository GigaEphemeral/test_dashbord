# 当前进展

按“只保留有消费者的指标”重审 Prometheus 抓取层：删除 2 个无人读取的 job，给 6 个 exporter 改为
keep-list，对 app/controller 显式 drop 未被面板、控制台 API、k6 或指标字典使用的家族。
真机 series：**19,550 → 5,988（-69.4%）**，闸门无回归。

验证（2026-10-30 真机）：

| 项 | 结果 |
|---|---|
| `promtool check config` | SUCCESS |
| 热加载 | `POST /prom/-/reload` 200，`/api/v1/status/config` 已含新规则 |
| 每 job series | 见附录 A |
| `verify.sh` | **110 PASS / 1 WARN / 0 FAIL** |
| `console-smoke.sh` | **159 PASS / 0 FAIL** |
| `check-doc-claims.sh` | **92 PASS / 0 FAIL** |

# 完成功能项

| 项 | 落地 |
|---|---|
| 删除无人消费的 job | `prometheus`（自身 internals 无面板/告警读取）与 `tempo`（无面板；其 `/metrics` 在压测下 51–118s，是最慢目标）。Traces 仍走 Tempo HTTP API |
| cAdvisor 白名单 | 只保留 D3/字典用到的 `container_cpu_usage_seconds_total`、`container_memory_working_set_bytes`、`container_network_{receive,transmit}_bytes_total`、`container_last_seen`、`container_oom_events_total`、`container_start_time_seconds`、`cadvisor_version_info`；filesystem/blkio/tasks 等 1.5k series 丢弃 |
| MySQL exporter 白名单 | 保留 `mysql_up` + 字典/面板用到的 10 个 `mysql_global_status_*`（questions/queries/threads_*/lock/deadlocks/slow）；命令、info_schema、perf_schema 目录丢弃（dev -1,034 / ha -3,309 series） |
| Redis exporter 白名单 | 保留 `redis_up`、memory/connected/commands/keyspace/db_keys/instance_info 等 13 个；命令级 latency/duration（原约 90% payload）丢弃 |
| RabbitMQ 白名单 | 聚合 job 只留 `rabbitmq_up/channels/connections/queues/queue_*`；detailed job 在 alias 后只留 `rabbitmq_queue_*`；Erlang allocator/msacc（1.7k series）丢弃 |
| Nginx exporter 白名单 | 只留 `nginx_*`，丢掉 Go runtime 指标 |
| app/controller 显式 drop | 82 / 32 个家族：Tomcat 非字典项、per-command JDBC/Spring-Data/Hikari 计时器、`system_*`/`process_*`/`disk_*`/`logback`、`thread_lab_demo_*` 多余项、app 侧 Rabbit producer 计数、JDK21-only 且 D7 明确不用的 `jvm_*` 派生项 |
| 注释与恢复路径 | 每个 job 注明“保留依据”；恢复某个家族只需删掉对应 keep/drop 规则并 reload |

# 设计逻辑

- **指标字典是契约**：`doc/process/Stage2/指标字典.md` 已被 B5-04b 逐行核对；本次只裁剪“既不在字典、
  也没有任何运行时消费者”的家族，因此字典行全部仍然有效。若未来字典新增一行，必须同步检查抓取规则。
- **exporter 的天花板在采集端**：keep/drop 只减少 Prometheus 存储与查询基数，不减少 exporter 自身的序列计算；
  Redis/MySQL/RabbitMQ 若还需进一步减负，可再加 `--skip-command-stats`、只开 global_status collector、
  或关闭 RabbitMQ per-object metrics（属可选优化，见待定）。
- **为什么删 Tempo 自抓取**：Tempo 的 traces 查询走 `:3200` HTTP API，Grafana 不读 `tempo_*`；
  它同时是最慢、最抖的目标。删掉后压测期间不再有 5s 超时噪声。
- **为什么保留 D7 job**：`seckill-app-jdk8` 是 `dual-jdk` 的可选对照外壳，不在 Nginx 上游；
  D7 的 run 需要它，不能因为 app-3 已是 JDK8 就删（app-3 会承接真实流量，对照会被流量差异污染）。
- **cAdvisor 只剩 23 series**：这是 Docker Desktop/WSL2 的既有限制（只能看到 root 序列），
  不是本次裁剪造成的；D3 面板仍按“无数据即无数据”渲染。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 历史序列未立即删除 | 旧序列仍在 TSDB（7 天 retention 内），当前 `/status/tsdb` head 仍约 2.2 万 series；新写入已裁剪，7 天后自然过期。需要立刻回收可启 admin API 后 `POST /api/v1/admin/tsdb/delete_series`（不可逆，默认未执行） | 按需 |
| exporter 端仍有无效计算 | Redis 命令级统计、MySQL 全量 collector、RabbitMQ per-object 仍在 exporter 内生成后再被丢弃；可加启动参数进一步降负载 | 可选 |
| 字典与注释漂移风险 | 后续新增面板/代码误用被 drop 的家族时，查询会“无数据”；review 清单已把 `prometheus.yml` 纳入 | 流程 |
| Tempo 可观测性 | 删掉自抓取后，Tempo 自身健康只能看容器状态与日志 | 记录 |

# 待定事项和注意事项

1. **改抓取规则后必须 `promtool check config` + reload**；本次用容器内 `promtool` 校验，
   注意 Git Bash 会改写 `/etc/...` 路径，需 `MSYS_NO_PATHCONV=1`。
2. **新增指标先证明消费者**：面板表达式、控制台 API 查询、脚本断言、或指标字典行，至少占一项；
   否则不要进 keep 集合。
3. **remote-write 不受影响**：k6 的 `k6_*` 走 remote-write，不经过这些 scrape 规则。
4. **cAdvisor 的 23 series** 是 WSL2 限制，排查 D3“无数据”时不要误判为裁剪故障。

---

## 附录 A：每 job series 前后

| job | 清理前 | 清理后 | 说明 |
|---|---:|---:|---|
| seckill-app | 3,720 | 3,420 | drop 312；冒烟新增 URI 抵消部分 |
| seckill-app-jdk8 | 237 | 237 | D7 对照壳，保留 |
| cadvisor | 1,602 | 23 | keep-list |
| mysqld-exporter | 1,049 | 15 | keep-list |
| mysqld-exporter-ha | 3,351 | 45 | keep-list（3 节点 × 15） |
| redis-exporter | 1,233 | 33 | keep-list |
| redis-exporter-ha | 1,279 | 66 | keep-list（2 节点 × 33） |
| nginx-exporter | 62 | 14 | keep-list |
| rabbitmq | 2,756 | 38 | keep-list |
| rabbitmq-queue | 16 | 10 | alias + keep-list |
| chaos-controller | 2,097 | 2,087 | drop 50，冒烟新增 HTTP URI 抵消 |
| tempo | 1,118 | 0 | job 删除 |
| prometheus | 1,267 | 0 | job 删除 |
| **合计** | **19,550** | **5,988** | **-69.4%** |

## 附录 B：关键文件

- `code/lab/docker/prometheus/prometheus.yml`（全部 keep/drop 规则与注释）
- `doc/process/Stage2/指标字典.md`（契约说明与裁剪指引）
- `code/lab/docclaims/claims.yml`（新增 `metric-budget` 断言）
