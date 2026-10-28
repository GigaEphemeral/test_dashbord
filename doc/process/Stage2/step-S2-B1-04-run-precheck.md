# 当前进展

> 任务/步骤：Stage2 **B1-04 环境预检查（run 前门禁）**
> 分支：`feature/stage2-workbench`（隔离 worktree `agent-general-purpose-b80b4255`，基线 `7e7792d`）
> 最新 commit：`d1d2800`（代码+测试+配置）；本文件单独一笔 `docs(B1-04)` 提交
> 测试：`mvn -pl services/chaos-controller -am test` → **485 tests / 0 fail / 0 error**（chaos-controller **471**、
> platform-common 10、platform-observability 4）；chaos 由 B1-03 的 435 增至 471（+36）。
> 静态闸门：`test-lab-safety.sh` PASS、`test-chaos-env-wiring.sh` PASS（8 chaos + 56 ledger/metrics/precheck，
> 含 9 个 `LAB_PRECHECK_*`）、`docker compose ... config` 渲染成功。
> 真机：**未起/未停共享 lab 栈，未执行 compose/up/down/verify/reset**；A26 演练清单见附录 C，由主会话执行。

`POST /lab/runs/{runId}/start` 从“只走状态机”变成“先过只读门禁”。门禁在**任何状态转移与抢锁之前**执行，
阻断项返回 **409 / 40903** 且 run 保持 `DRAFT`；新增 `GET /lab/runs/{runId}/precheck` 只跑检查、返回同一份报告。
阻断项没有 force/skip 参数。

# 完成功能项

## 1. 七个检查项（key、判定、证据）

报告每项：`{key, status: PASS|WARN|BLOCK, message, detail}`。`detail` 只含容器名、动作、剩余 TTL、
imageId、内存数字等排障证据；**不含容器 env/凭据**。

| key | 判定 | 证据 detail | 只读来源 |
|---|---|---|---|
| `traffic.stopped` | 背景 `jl-traffic` 在跑 → **BLOCK**；未创建/已停 → PASS；Docker 不可达（无法证明已停）→ **BLOCK**（fail closed） | `container,state` | `TrafficService.state(lab.precheck.traffic-container)` → `ContainerGateway.inspectLabContainer` |
| `chaos.clean` | `activeActions()` 非空 → **BLOCK**（含 FAILED 待重试） | `activeActions, actions[{id,target,action,status,remainingTtlSeconds}]` | `ChaosActionService.activeActions()` + 注入 Clock |
| `toxiproxy.clean` | 有 toxic → **BLOCK**；proxy 被 disable（断网残留）→ **BLOCK**；`chaos.toxiproxy.enabled=false` 或已启用但不可达 → **WARN** | `enabled,proxies,toxics[{proxy,toxic,type}],disabledProxies` | `ToxiproxyService.state()`（控制面 GET） |
| `operations.settled` | 仍有 `REQUESTED/PENDING_CONFIRMATION/UNKNOWN` → **BLOCK**；start 路径只忽略“本次自己插入的 REQUESTED start 行”，同 run 的 PENDING/UNKNOWN 兄弟仍阻断 | `unsettledOperations, operations[{operationId,runId,action,target,status,requestedAt}]` | `lab_operation`（有界查询，上限 `lab.ledger.query-max-limit`） |
| `monitoring.ready` | Prometheus 不可达 → **BLOCK**；`sum(up{job="seckill-app"}) != required-app-targets`、`sum(up{job="redis-exporter"}) < 1`、`sum(mysql_cluster_probe_up) != 1` 任一不满足 → **BLOCK** | `requiredAppTargets,appUp,redisExporterUp,mysqlClusterProbeUp` | Prometheus `/api/v1/query`（instant，独立 bean/超时） |
| `resources.budget` | `sum(运行中 lab 容器 mem_limit) + memory-reserve-mb > Docker Info.MemTotal` → **BLOCK**；刚好相等 → PASS；Docker 信息不可得、无运行容器、任一容器无 `mem_limit` → **WARN** | `runningContainers,containers[{container,limitMb}],limitTotalMb,reserveMb,hostTotalMb,overByMb,(unboundedContainers)` | Docker list+inspect `HostConfig.Memory`、`docker info .MemTotal` |
| `images.consistent` | 运行中 app 容器 imageId 不一致 → **BLOCK**；配置了 `expected-app-image-id` 且不符 → **BLOCK**；<2 台在跑、少于 `required-app-targets`、inspect 失败 → **WARN** | `containers[{container,state,imageId}],missingContainers,expectedImageId,(runningContainers)` | Docker inspect（`jl-app-1..3`，可配） |

判级原则：**能证明干净才 PASS；证明不了就 BLOCK 或 WARN，绝不把“不知道”当 0/PASS**。

## 2. 接入 start 的顺序（关键语义）

```
POST /lab/runs/{id}/start
  └ OperationService.submit：写 lab_operation=REQUESTED（幂等键生效）
      └ doStart（operation 线程）:
          1) requireRun + requireTransition   ← 只读合法性；非 DRAFT 仍是 409/40901
          2) precheck.enforce(runId)          ← B1-04 只读门禁（本轮新增）
               阻断 → 抛 PrecheckBlockedException
          3) envLock.acquire                  ← 抢锁
          4) DRAFT → VALIDATING → READY → launch hook → RUNNING
```

- 阻断时：**run 仍 DRAFT**、`lab_env_lock` **无行**、`lab_run_step` 无新行、start operation 由
  `OperationService` 落为 **FAILED**，`last_error` 含 `PrecheckBlockedException: run precheck BLOCKED: <key> (...)`；
  HTTP **409/40903**。修复环境后直接重试即可（幂等键相同且仍在窗口内会重放那条 FAILED operation，不会重跑检查；
  重试请换 key 或省略 `Idempotency-Key`）。
- 门禁通过（含仅 WARN）：照常抢锁并到 RUNNING；operation 的 `afterSummary` 增加
  `precheckVerdict`（PASS/WARN）与 `precheckWarnings`（逗号分隔的 key）。完整证据留在
  `GET /lab/runs/{id}/precheck`（不再重复写 run step detail）。
- 预检查只读：不 start/stop 容器、不 reset toxic、不写台账（除 operation 自身行的状态机外无副作用）。

## 3. REST 契约

| 端点 | 结果 |
|---|---|
| `POST /lab/runs/{id}/start`（有阻断） | **409**，`code=40903`，`message` 为一行阻断摘要，**`data` = 完整 `PrecheckReport`**（含 `blocking[]` 明细） |
| `POST /lab/runs/{id}/start`（仅 warning） | 200 APPLIED（或 202 PENDING_CONFIRMATION），run → RUNNING，`afterSummary.precheckVerdict=WARN` |
| `GET /lab/runs/{id}/precheck` | **200**（即使 verdict=BLOCKED）；未知 run **404/40401**；台账不可用 **503/50302** |
| 报告结构 | `{runId, checkedAt, verdict: PASS|WARN|BLOCKED, blocking[], warnings[], passed[], items[]}`，每项 `{key,status,message,detail}`；`items[]` 完整，`blocking/warnings/passed` 每类最多 `max-listed-items` 条 |

不提供 `force`/`skip` 参数；`lab.precheck.enabled=false` 是**整体**关闭（面向只用台账 API 的部署），
不是单个阻断项的后门。

## 4. 配置（全外置，三处一致）

`application.yml`（`lab.precheck.*`）+ compose `chaos-controller.environment` + `.env.example`，默认值三处相同：

| 键 | env | 默认 | 口径 |
|---|---|---|---|
| `enabled` | `LAB_PRECHECK_ENABLED` | `true` | 整体开关（非单项跳过） |
| `traffic-container` | `LAB_PRECHECK_TRAFFIC_CONTAINER` | `jl-traffic` | 与 `chaos.traffic.container-name` 独立，便于按 profile 改名 |
| `app-containers` | `LAB_PRECHECK_APP_CONTAINERS` | `jl-app-1,jl-app-2,jl-app-3` | 镜像一致性比较名单（新增键：避免在代码里拼 `jl-` 前缀） |
| `prometheus-timeout-seconds` | `LAB_PRECHECK_PROMETHEUS_TIMEOUT_SECONDS` | `5` | 预检查自己的 HTTP 请求预算（独立于 B1-03 聚合的 10s） |
| `required-app-targets` | `LAB_PRECHECK_REQUIRED_APP_TARGETS` | `3` | 健康的 `up{job="seckill-app"}` 目标数 |
| `memory-reserve-mb` | `LAB_PRECHECK_MEMORY_RESERVE_MB` | `1024` | 阈值口径：`sum(mem_limit)+reserve <= Docker Info.MemTotal`（严格大于才阻断） |
| `expected-app-image-id` | `LAB_PRECHECK_EXPECTED_APP_IMAGE_ID` | 空 | 空=只查一致性；非空接受完整 id 或唯一前缀（如 12 位短 id） |
| `max-listed-items` | `LAB_PRECHECK_MAX_LISTED_ITEMS` | `10` | `blocking/warnings/passed` 每类上限；`items[]` 不受限 |
| `check-timeout-seconds` | `LAB_PRECHECK_CHECK_TIMEOUT_SECONDS` | `30` | 单份报告总预算；预算耗尽后未开始的检查记 **BLOCK**（fail closed），单次网络等待另由 `prometheus-timeout-seconds` 与 Docker 客户端超时约束 |

`test-chaos-env-wiring.sh` 的发现正则已扩展到 `LAB_PRECHECK_*`（与 LEDGER/METRICS/SOURCE_COMMIT 同规则）。

# 设计逻辑

1. **门禁放在锁之前**。B1-02 的 `RunLifecycleHook.precheck` 在 VALIDATING 之后调用，此时 run 已变状态、锁已持有；
   B1-04 把 precheck 从 hook 移到 `envLock.acquire` 之前，hook 只保留 B2-02 的 `launch`。这样“阻断”不会产生任何
   需要回滚的状态：不写锁、不改状态，重试成本为零。
2. **不把“不知道”变成 PASS**。Docker 不可达时 traffic 阻断、resources 警告；Prometheus 无数据阻断；imageId 拿不到
   警告而不是“一致”。这与 B1-03 的 `NO_DATA ≠ 0` 同一口径。
3. **Toxiproxy 的 dev 例外**。dev 栈本来没有代理层：`enabled=false` 或控制面不可达只 WARN，不挡 run；但一旦层在，
   toxic 或 disable 的 proxy 都是残留故障，必须阻断。
4. **operations.settled 不检查自己**。start 的 operation 行在 work 执行前已插入 `REQUESTED`，若不过滤会让每次 start
   自阻断。只忽略 `REQUESTED + action=start + target=本 run` 的行；PENDING_CONFIRMATION/UNKNOWN 的 start（上次
   超时/重启遗留）仍阻断。GET 报告不过滤任何行。
5. **Docker 只读事实单独建模**。`ContainerGateway` 是变更边界（每次调用校验 `lab=java-learn`），没有 imageId/
   mem_limit；新增 `PrecheckDockerFacts` + `DockerClientFacts` 只做 inspect/list/info，测试用内存替身，不触碰
   `ContainerGateway` 的既有实现与其测试。
6. **Prometheus 复用解析、独立预算**。`PrometheusGateway.queryInstant` 走 `/api/v1/query`（vector），
   `HttpPrometheusGateway` 新增显式超时构造器，预检查 bean 与 B1-03 聚合 bean 分开，互不改超时。
7. **报告可截断但证据不可丢**。`items[]` 永远完整；三个分组列表按 `max-listed-items` 截断，避免一个陈年
   operation 列表把响应撑大。`detail` 使用可含 null 的不可变 Map（`Map.copyOf` 不接受 null 样本）。

# 存在缺陷项&TODO

| 级别 | 项 | 说明 / 下一步 |
|---|---|---|
| 中 | 真机 A26 未执行 | 本 worktree 不动共享栈；主会话按附录 C 执行并回填证据 |
| 中 | 监控检查绑定现栈拓扑 | `seckill-app` / `redis-exporter` job 名与 `mysql_cluster_probe_up` 是现栈事实（`prometheus.yml`、`MysqlClusterMonitor`）。dev 单机栈没有 `jl-mysql-3`，`probe_up=0` 会先阻断 monitoring；A26 需在 ha+db-ha 栈执行（当前共享栈已含 `jl-mysql-3`）。若将来 job 改名，改 `RunPrecheckService.PROMQL_*` 常量并跑一次真机 |
| 低 | `check-timeout-seconds` 不抢占在途调用 | 只保证“预算耗尽后不再开始新检查并记 BLOCK”；单个 Docker/Prometheus 调用仍由各自超时兜底。若需要硬截止，B2/B4 再引入带 jitter 的取消 |
| 低 | 镜像一致性只看“运行中”容器 | 停止的实例不参与比较（monitoring 会因 `up != 3` 阻断）；`expected-app-image-id` 支持前缀，不做 digest 溯源（B0-08 最小闭环口径） |
| 低 | `images.consistent` 的 `required-app-targets` 与 `monitoring.ready` 共用一个数 | 当前两处语义一致；若将来 JDK8 外壳也纳入名单，再拆独立键 |
| 低 | 阻断后重试与幂等键 | 同 key 且在窗口内重放 FAILED operation（不会再跑检查）；文档已提示换 key。若 UI 固定复用 key，B1-05 需在“重试”按钮换 key |

# 待定事项和注意事项

1. **B1-05 UI**：`blocking[]/warnings[]/passed[]` 与 `detail` 已按控制台可直接渲染的形状返回；409 body 与 GET 报告同构，
   前端不需要第二个请求。403/409 之外的错误码不变。
2. **B2-02**：`launch` hook 仍在 READY→RUNNING 之间；k6 启动失败会走既有 `closeFailedStart`（run FAILED、锁释放），
   与门禁失败（run DRAFT、无锁）语义不同，不要混用。
3. **B2-04 stop-all**：toxiproxy 的“proxy 被 disable”已视为残留故障；stop-all 继续负责 reset。
4. **真机验收前置**：控制器镜像需重建/重建容器后才有 40903 与 `/precheck`；当前共享栈的 `jl-chaos-controller` 还是旧镜像。
5. **行尾**：`compose.base.yml` / `.env.example` / `application.yml` 保持 CRLF（字节级插入，已核对 bare LF=0）；
   新增 Java/测试/脚本文档为 LF。

---

## 附录 A：提交

| commit | 内容 |
|---|---|
| `d1d2800` | feat(B1-04)：门禁实现、409/40903、`/precheck`、9 个配置键、36 个新测试 |
| 本文件随 `docs(B1-04)` 提交 | 五段式交接 |

## 附录 B：测试分布与命令

```
cd code && mvn -pl services/chaos-controller -am test
→ platform-common 10 / platform-observability 4 / chaos-controller 471，0 fail / 0 error，BUILD SUCCESS
```

| 测试 | 数量 | 覆盖 |
|---|---|---|
| `precheck/RunPrecheckServiceTest` | 22 | 七项 PASS/WARN/BLOCK；traffic 在跑/未装/不可达；chaos 剩余 TTL；toxiproxy toxic/disabled/unreachable/整体关闭；operations 自身 start 过滤 + UNKNOWN 兄弟阻断；monitoring 缺 app/redis/probe 与 Prometheus 不可达；内存超限/刚好/不可得/无 mem_limit；镜像不一致/期望不符/前缀命中/少于 3 台/缺容器；未知 run 404、台账故障 503、整体关闭 |
| `precheck/PrecheckControllerTest` | 3 | blocking 仍 200；404；503/50302 |
| `precheck/PrecheckPropertiesTest` | 4 | 默认值、名单解析、校验消息（属性+env）、不可变 |
| `ledger/LedgerControllerTest` | +1（19） | start 409/40903，body `data.verdict=BLOCKED`、`blocking[0]` 明细 |
| `ledger/RunLifecycleIT` | 12（+2，Testcontainers） | 阻断：run 仍 DRAFT、`lab_env_lock` 无行、无 step、operation FAILED 含阻断摘要；仅 warning：到 RUNNING 且 afterSummary 有 `precheckVerdict/precheckWarnings`；launch 失败仍 FAILED+释放锁 |
| `metrics/HttpPrometheusGatewayTest` | 12（+4） | `/api/v1/query` path/time、vector 解析、空 vector、错误 resultType、预检查独立超时 |

其他闸门：

```
bash code/lab/scripts/test-lab-safety.sh              → LAB SAFETY TESTS PASSED.
bash code/lab/scripts/test-chaos-env-wiring.sh        → PASS（8 chaos + 56 ledger/metrics/precheck）
docker compose --env-file code/lab/docker/.env.example \
  -f code/lab/docker/compose.base.yml config           → EXIT=0（只渲染）
```

## 附录 C：A26 真机演练清单（主会话执行）

前置：重建控制器镜像并重建容器（当前 `jl-chaos-controller` 是旧镜像）；栈为当前 ha+db-ha（`jl-mysql-3` 在跑）。
`BASE=http://localhost:8080/lab-api/chaos`，所有请求带 `X-Lab-Token: $LAB_TOKEN`。

> 只读快照（2026-10-28 本步收尾时，未改栈）：`sum(up{job="seckill-app"})=3`、`sum(up{job="redis-exporter"})=1`、
> `sum(mysql_cluster_probe_up)=1`；运行中 32 个 lab 容器 `mem_limit` 合计 8.28GiB，Docker `MemTotal` 15.24GiB
> （+1GiB 预留 < 总量）；三台 app 运行镜像同为 `sha256:5b18a49d504c…`；`lab_operation` 无
> REQUESTED/PENDING_CONFIRMATION/UNKNOWN，`lab_env_lock` 0 行。因此基线报告预期为 **WARN**（仅
> `toxiproxy.clean`：控制面未起），没有阻断项。

1. 建 DRAFT：`POST $BASE/lab/runs` body `{"scenario":"load"}`，记 `RUN`。
2. 基线：`GET $BASE/lab/runs/$RUN/precheck` → 200，`verdict` 为 PASS 或 WARN（无 chaos-proxy overlay 时
   `toxiproxy.clean` 应为 WARN“enabled but unreachable”，这是预期）。
3. 制造脏环境：`POST $BASE/lab/traffic/start`；`POST $BASE/chaos/actions/jl-app-2`
   body `{"action":"freeze","ttlSeconds":120}`。
4. 再跑报告：`verdict=BLOCKED`，`blocking` 至少含 `traffic.stopped` 与 `chaos.clean`（HTTP 仍 200）。
5. 阻断 start：`POST $BASE/lab/runs/$RUN/start`（`Idempotency-Key: a26-blocked`）→ **409**、`code=40903`、
   `data.blocking[].key` 含上述两项。
6. 核验未越界：
   - `GET $BASE/lab/runs/$RUN` → `status=DRAFT`；
   - MySQL 台账：`SELECT COUNT(*) FROM lab_env_lock;` → `0`；`SELECT COUNT(*) FROM lab_run_step WHERE run_id='$RUN';` → `0`；
   - `GET $BASE/lab/operations?runId=$RUN&status=FAILED` → start operation，`lastError` 含 `traffic.stopped`。
7. 清理：`POST $BASE/chaos/stop-all`；`POST $BASE/lab/traffic/stop`。复查 `GET .../precheck` 无 `blocking`。
8. 重试（**换 key**）：`POST $BASE/lab/runs/$RUN/start`（`Idempotency-Key: a26-clean`）→ 200/202，run `RUNNING`；
   `GET $BASE/lab/operations/{operationId}` → `afterSummary.precheckVerdict`（WARN）与 `precheckWarnings=toxiproxy.clean`。
9. 收尾：`POST $BASE/lab/runs/$RUN/stop` → `COMPLETED`，锁释放；记为 A26 通过并回填本节。
