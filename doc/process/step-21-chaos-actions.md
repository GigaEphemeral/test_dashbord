> 步骤交接 · M2-3 chaos-controller 容器动作与 TTL 看门狗
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / 本次提交 `feat: add chaos container actions and TTL watchdog`（父提交 `8744854`）
> 关联任务：#21（M2-3）
> 测试 / verify：全 reactor **192 tests, 0 failures**（chaos-controller 39，原 26；新增 `ChaosActionServiceTest` 14 + `ChaosControllerTest` 9，`TokenFilterTest` 8）；`verify.sh` **97 PASS / 1 WARN**（WARN 仍是 cAdvisor/WSL2）
> 运行状态：16 个 lab 容器运行中、三实例健康；控制器镜像已重建并 `up.sh`；app-3 仅用于验收，MySQL/Redis/RabbitMQ 未触碰；非 lab 容器 `redis` 保持运行 9 天

# 当前进展

- Task 21 完成：chaos-controller 新增 `kill`/`start`/`restart`/`freeze`/`resume` 容器动作、TTL 自动回滚、看门狗、全局 `stop-all`、Grafana annotation 与三项指标；原有 token 校验、`lab=java-learn` 白名单、日志/列表接口保持不变。
- 新增 `POST /chaos/instances/{name}/actions`、`GET /chaos/actions`、`POST /chaos/stop-all`，经 `/lab-api/` 代理访问，仍受 `X-Lab-Token` 保护。
- 所有 TTL/超时/URL/凭据外置（`chaos.*` + `LAB_DOCKER_*` + 既有 `GRAFANA_ADMIN_*`），Java 代码内无 URL/端口/凭据常量。
- 单元/MockMvc 测试覆盖：kill→start、freeze→SIGCONT、TTL 清理、stop-all、非 lab 拒绝、重复 409、TTL 上限、annotation 失败不影响动作、FAILED 可见/可替换、指标。
- 真实验收（仅 app-3）：kill 30s、freeze 20s、freeze 300s + stop-all、start（幂等）、restart；Nginx 全程 200 且其余实例接管；TTL 后自动恢复并重新进入轮询；Grafana 出现 annotation；非 lab 容器未被触碰。
- 未修改应用 Java、k6、dashboard、`verify.sh`；未提交 `.env`。

# 完成功能项

## 1. Docker 网关动作（`gateway/`）

- `ContainerGateway` 新增 `inspectLabContainer`、`stop`、`start`、`restart`、`signal`；`SIGSTOP`/`SIGCONT` 为接口常量。
- `DockerContainerGateway` 每个动作**先校验 `lab=java-learn`** 再调用 Docker Java 3.7.1：
  - `stop` 使用手动 `stopContainerCmd`（不是 kill），因此 `restart: unless-stopped` 不会立即拉起，TTL 到点由控制器 `start`；
  - `start`/`stop` 的 304 `NotModifiedException` 视为幂等成功；
  - `signal` 仅允许 `SIGSTOP`/`SIGCONT`，其他信号在网关层 400；容器未运行时 Docker 409 → 400；
  - `restart` 使用 `restartContainerCmd` 并带外置的 stop grace；未知容器 404、非 lab 403、守护进程异常 503。
- `DockerConfig` 顺带外置连接参数：`lab.docker.max-connections` / `connection-timeout-seconds` / `response-timeout-seconds`。

## 2. `ChaosActionService`（`action/`）

- `ConcurrentHashMap<String, ChaosAction>` 以容器名为 key：同一 target 同时只允许一个待回滚动作。
- `ChaosAction` 用 CAS 状态机 `ACTIVE → REVERTING → REVERTED/FAILED`，TTL 任务、看门狗、stop-all 三方竞争不会重复回滚。
- 每个 kill/freeze 在**执行 Docker 动作之前**先注册并提交 TTL 任务；Docker 动作失败则取消任务并移除记录，不会留下“无人回滚”的清单。
- kill → 到时 `start`；freeze → 到时 `SIGCONT`；start/restart/resume 为一次性动作，不进入 active，`ttlSeconds` 被忽略。
- 回滚成功后移除 active、计数并触发一次异步就绪探测；失败则标记 FAILED、`chaos_reverts_total{result="failed"}` 并保留在 `GET /chaos/actions`，看门狗按 `max-revert-attempts` 重试。
- 看门狗 `scheduleAtFixedRate`：扫过期的 ACTIVE（TTL 任务丢失时的第二道保险）并重试 FAILED；`stop-all` 取消定时任务后立即 start/SIGCONT，成功项清除，失败项保留 FAILED。
- 就绪探测在调度线程异步执行，最多等待 `chaos.action-timeout-seconds`，HTTP 线程不阻塞；超时记 `chaos_actions_total{result="unready"}`。

## 3. Grafana annotation（`annotation/`）

- `ChaosAnnotator` 接口 + `HttpGrafanaAnnotator`（JDK `HttpClient`，basic auth）+ `NoOpChaosAnnotator`。
- `POST {chaos.annotation.grafana-url}/api/annotations`，body `{tags,text}`；tags 固定包含 `chaos`、`java-learn`、`<target>`、`<action>`，可追加 `chaos.annotation.tags`；text 含 action/id/ttl/expires/revert/trigger。
- 所有异常（连接、非 2xx、中断）只记 WARN，**绝不使动作失败**；`enabled=false` 时使用 NoOp。

## 4. API 与错误语义（`web/`）

| 方法与路径 | 说明 | 状态码 |
|---|---|---|
| `POST /chaos/instances/{name}/actions` | body `{"action":"kill|start|restart|freeze|resume","ttlSeconds":120}`，ttl 仅 kill/freeze 生效 | 202 `Result<ChaosActionView>` |
| `GET /chaos/actions` | 仍在跟踪的动作（active/failed） | 200 |
| `POST /chaos/stop-all` | 立即回滚全部，返回 `{reverted,failed}` | 200 |
| 参数/动作/JSON 非法 | `InvalidChaosActionException`、不可解析 body | 400 `40001` |
| 非 lab 容器 | 网关白名单 | 403 `40301` |
| 未知容器 | Docker inspect | 404 `40401` |
| 同一 target 已有待回滚动作 | `ChaosConflictException` | 409 `40901` |
| Docker 守护进程失败 | `DockerUnavailableException` | 503 `50301` |

校验顺序为 **404/403 先于 409**：先 inspect 确认容器存在且属于 lab，再检查冲突，避免通过 409 探测非 lab 容器。

## 5. 指标

- `chaos_actions_total{action,target,result}`：`result=accepted|rejected|failed|unready`。
- `chaos_active_actions`：当前跟踪（待回滚/失败）的动作数。
- `chaos_reverts_total{action,result}`：`result=success|failed`。
- 均由现有 `/metrics`（prometheus endpoint）导出；`target` 取值是容器名（≤16），基数可控。

## 6. 配置（全部外置）

`code/services/chaos-controller/src/main/resources/application.yml` 与 `code/lab/docker/compose.base.yml` 使用同一组键：

| 配置键 | 环境变量 | 默认值 | 用途 |
|---|---|---|---|
| `chaos.default-ttl-seconds` | `CHAOS_DEFAULT_TTL_SECONDS` | 120 | kill/freeze 未传 ttl 时的 TTL |
| `chaos.max-ttl-seconds` | `CHAOS_MAX_TTL_SECONDS` | 600 | 调用方 TTL 上限 |
| `chaos.action-timeout-seconds` | `CHAOS_ACTION_TIMEOUT_SECONDS` | 30 | 回滚后就绪探测截止 |
| `chaos.watchdog-interval-seconds` | `CHAOS_WATCHDOG_INTERVAL_SECONDS` | 5 | 看门狗周期（也是探测间隔） |
| `chaos.stop-grace-seconds` | `CHAOS_STOP_GRACE_SECONDS` | 10 | Docker stop/restart 宽限 |
| `chaos.max-revert-attempts` | `CHAOS_MAX_REVERT_ATTEMPTS` | 3 | 看门狗重试上限 |
| `chaos.annotation.enabled` | `CHAOS_ANNOTATION_ENABLED` | true | annotation 开关 |
| `chaos.annotation.grafana-url` | `CHAOS_ANNOTATION_GRAFANA_URL` | `http://grafana:3000` | Grafana 基址（仅 YAML/compose） |
| `chaos.annotation.user` | `GRAFANA_ADMIN_USER` | 来自 `.env` | basic auth 用户 |
| `chaos.annotation.password` | `GRAFANA_ADMIN_PASSWORD` | 来自 `.env` | basic auth 密码 |
| `chaos.annotation.timeout-seconds` | `CHAOS_ANNOTATION_TIMEOUT_SECONDS` | 3 | annotation 连接/请求超时 |
| `chaos.annotation.tags` | `CHAOS_ANNOTATION_TAGS` | 空 | 追加标签，逗号分隔 |
| `lab.docker.max-connections` | `LAB_DOCKER_MAX_CONNECTIONS` | 16 | Docker HTTP 连接池 |
| `lab.docker.connection-timeout-seconds` | `LAB_DOCKER_CONNECTION_TIMEOUT_SECONDS` | 5 | Docker 连接超时 |
| `lab.docker.response-timeout-seconds` | `LAB_DOCKER_RESPONSE_TIMEOUT_SECONDS` | 30 | Docker 响应超时 |

`.env.example` 未新增必填键（`up.sh` 会强校验 `.env` 与 example 键一致）；chaos 默认值由 compose 内联默认提供，临时覆盖可直接 export 同名环境变量后重启控制器。

# 设计逻辑

- **白名单优先**：所有动作都在网关 `requireLabContainer` 内二次校验 `lab=java-learn`，控制器无法触达 MySQL/Redis/RabbitMQ 或宿主上的非 lab 容器；`kill` 只做 Docker stop（带宽限），不做 `docker kill`/删卷。
- **kill 用 stop 而不是 kill**：`restart: unless-stopped` 在手动 stop 后不会自动拉起，故障窗口完全由 TTL 控制；`docker kill` 会被策略立即重启，演示不可控。
- **先调度后执行**：TTL 任务先提交再 stop/freeze；若 Docker 动作抛错则 cancel + 从 active 移除。反向顺序（先停再调度）在调度器拒绝时会留下永久停机容器。
- **三方回滚竞争**：TTL 任务、看门狗、stop-all 都通过 `claimRevert()` CAS 抢占；重复调用直接返回，因此 `start`/`SIGCONT` 最多执行一次（单测断言 start 调用恰为 1 次）。
- **看门狗是第二保险**：常规定时任务负责按时回滚；看门狗额外扫描“已过期但仍是 ACTIVE”的动作（任务丢失/线程饿死），并重试 FAILED。FAILED 动作不会被静默移除，`GET /chaos/actions` 与 `chaos_reverts_total{result=failed}` 都能看到。
- **404/403 先于 409**：先 inspect 再判冲突，避免把非 lab 容器的存在性通过 409 暴露；inspect 同时提供后续就绪探测的状态来源。
- **annotation 是旁路**：动作与回滚都不依赖 Grafana；annotation 失败只 WARN。真实验收中 Grafana 正常，8 条 annotation 全部落库。
- **就绪探测异步**：回滚只负责把容器拉起来，健康恢复由调度线程轮询（间隔取看门狗周期、上限取 action-timeout）；HTTP 202 立即返回，避免占满 Tomcat 线程与 Nginx 30s 读超时。
- **一次性动作不登记**：start/restart/resume 没有 TTL，不进入 active；start/resume 可用于人工恢复正在进行的故障，不返回 409（运维优先）。
- **FAILED 可被新动作替换**：同一 target 的 FAILED 记录允许新 kill/freeze 覆盖（并取消旧任务），避免一次失败把容器永久锁死；正常 ACTIVE 仍返回 409。

# 存在缺陷项&TODO

- **就绪探测默认 30s 偏紧**：验收中 app-3 冷启动约 32s（容器 07:45:15 启动、07:45:48 healthy），30s 截止时报了一条 `chaos_actions_total{action="start",result="unready"}`，随后容器仍恢复健康。默认值按 brief 保持 30s；演示冷启动建议 `CHAOS_ACTION_TIMEOUT_SECONDS=60`。
- **控制器重启会丢失 active 清单**：动作注册表在内存中；控制器重启/重建后，已 kill 的容器不会自动 start、已 freeze 的不会自动 SIGCONT（Docker 侧 stop/SIGSTOP 仍然生效）。当前需人工 `start`/`resume` 或 `stop-all`。后续可在启动时根据 Docker 状态做 reconciliation，或持久化到配置卷。
- **stop-all 计数只统计成功回滚**：失败项保留为 FAILED 并计入 `failed`，不重试超过 `max-revert-attempts`；`GET /chaos/actions` 仍是唯一完整视图。
- **单实例控制器**：多副本会各自维护 active，无法保证全局唯一；当前 lab 只有一个 chaos-controller（设计如此）。
- **freeze 的代价**：SIGSTOP 冻结整个 JVM，Nginx 对该上游的请求会等满 `proxy_read_timeout 10s` 后切到其他实例（验收中 9 个请求出现 1 个 10.01s 慢请求），属预期；仅影响 2 次失败计数后 Nginx 会摘除该上游。
- **信号白名单**：只开放 SIGSTOP/SIGCONT；`kill` 语义固定为 Docker stop。若未来要验证 SIGKILL/OOM，需要新增显式动作与策略处理（本任务不做）。
- **annotation 无鉴权降级**：`GRAFANA_ADMIN_USER` 为空时不发 Authorization 头，Grafana 会 401，annotation 只 WARN；compose 默认注入 `.env` 凭据，正常路径已验证。
- **指标基数**：`target` 标签为容器名；若未来允许任意命名建议先做白名单收敛（当前仅 lab 容器可达，最多 16 个）。

# 待定事项和注意事项

- 下一步 Task 22（M2-4）：场景 JSON、k6 时间线与向导状态机；可直接复用 `/chaos/instances/{name}/actions` 与 `/chaos/stop-all`，TTL 由场景步骤下发。bench 预检需按 Task 20a 的限制用 `BENCH_REQUIRED_SERVICES` 收窄（chaos 期间容器会暂时不健康）。
- 演示/验收只对 app-1/2/3 使用破坏性动作；**不要**对 `jl-mysql`、`jl-redis`、`jl-rabbitmq` 执行 kill/freeze（本任务未做，也不在验收范围）。
- 手动恢复：`POST /lab-api/chaos/stop-all`（带 token）会立即 start/SIGCONT 所有跟踪动作；若控制器已重启导致清单丢失，用 `docker start jl-app-N` / `docker kill -s SIGCONT` 人工恢复。
- `kill` 的 TTL 默认 120s、上限 600s；想缩短故障窗口请在请求里显式传 `ttlSeconds`（验收使用 5–30s）。
- 控制器重建后 Prometheus 计数器归零（进程内计数），跨重启请用 `increase()`/`rate()` 观察。
- 现有 `verify.sh` 不覆盖 chaos 动作（未按要求改动）；chaos 验收证据见本文件与任务报告。

# 附录

## 关键文件

- `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/gateway/ContainerGateway.java`
- `.../gateway/DockerContainerGateway.java`
- `.../action/ChaosActionService.java`、`ChaosAction.java`、`ChaosActionType.java`、`ChaosActionStatus.java`、`ChaosActionRequest.java`、`ChaosActionView.java`、`StopAllResult.java`
- `.../annotation/ChaosAnnotator.java`、`HttpGrafanaAnnotator.java`、`NoOpChaosAnnotator.java`
- `.../config/ChaosProperties.java`、`ChaosConfig.java`、`DockerConfig.java`
- `.../web/ChaosController.java`、`LabExceptionHandler.java`
- `.../resources/application.yml`
- `code/lab/docker/compose.base.yml`
- 测试：`ChaosActionServiceTest`、`ChaosControllerTest`、`TokenFilterTest`

## 复现命令

```bash
export JAVA_HOME="C:\\Program Files\\Java\\jdk-21.0.12.1"
cd code
mvn -pl services/chaos-controller -am test

cd ..
docker compose --project-directory code/lab/docker --env-file code/lab/docker/.env \
  -f code/lab/docker/compose.base.yml -f code/lab/docker/compose.dev.yml build chaos-controller
bash code/lab/scripts/up.sh

TOKEN=$(grep -E '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)
# kill app-3 for 30s
curl -s -H "X-Lab-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"action":"kill","ttlSeconds":30}' \
  http://127.0.0.1:8080/lab-api/chaos/instances/jl-app-3/actions
curl -s -H "X-Lab-Token: $TOKEN" http://127.0.0.1:8080/lab-api/chaos/actions
# freeze / resume
curl -s -H "X-Lab-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{"action":"freeze","ttlSeconds":20}' \
  http://127.0.0.1:8080/lab-api/chaos/instances/jl-app-3/actions
# stop-all
curl -s -X POST -H "X-Lab-Token: $TOKEN" http://127.0.0.1:8080/lab-api/chaos/stop-all
# annotations
GU=$(grep -E '^GRAFANA_ADMIN_USER=' code/lab/docker/.env | cut -d= -f2-)
GP=$(grep -E '^GRAFANA_ADMIN_PASSWORD=' code/lab/docker/.env | cut -d= -f2-)
curl -s -u "$GU:$GP" 'http://127.0.0.1:3000/api/annotations?tags=chaos&limit=5'
# metrics
curl -s http://127.0.0.1:8080/lab-api/metrics | grep '^chaos_'
```

## 证据摘要

- 单元/切片测试：`ChaosActionServiceTest` 14、`ChaosControllerTest` 9、`TokenFilterTest` 8、`LabControllerTest` 8，chaos-controller 模块 **39/39 绿**；`mvn test` 全 reactor **192 tests, 0 failures**（app-jdk21 126、seckill-core 13、platform 14）。
- `verify.sh`（验收与重建后复跑）：**97 PASS / 1 WARN**，WARN 仍是 cAdvisor/WSL2；新增 chaos API 不改变既有断言。
- 真实 kill（ttl=30，07:44:45Z 提交）：
  - `202`，`expiresAt=07:45:15.492Z`，`docker inspect` → `exited exit=143`；
  - 同 target 再 kill → `409 40901`；
  - 停机期间经 Nginx 发 18 个 `/api/hello`：**18/18 200**，仅 app-1(9)/app-2(9)，无 5xx；
  - TTL 到点 07:45:15.78 自动 `start`（日志 `Reverted kill ... trigger=ttl, attempt=1`），`/chaos/actions` 清空，07:45:48 healthy；
  - 恢复后 12 个请求重新轮询 app-1/app-2/app-3 各 4 次。
- 就绪探测：30s 截止（07:45:45.9）时容器仍 `starting`，记录 `chaos_actions_total{action="start",result="unready"}`；随后 07:45:48 healthy。
- 真实 freeze（ttl=20，07:46:00Z）：9 个 Nginx 请求 **9/9 200**；两个慢请求 10.01s（Nginx 读超时后切 app-1）与 6.25s（SIGCONT 后 app-3 完成）；07:46:27 恢复 healthy、`/chaos/actions` 空。
- 真实 stop-all：freeze ttl=300 后 `POST /chaos/stop-all` → `{"reverted":1,"failed":0}`，`/chaos/actions` 空，2s 后 app-3 healthy。
- 一次性动作：`start`（运行中，Docker 304）→ 202 `completed`；`restart`（body 带 `ttlSeconds=999`）→ 202 `completed` 且 `ttlSeconds=null`（被忽略），07:47:14 恢复 healthy。
- 校验路径：无 token 401；`redis`（非 lab，已运行 9 天）403；`jl-missing` 404；`explode` 400；`ttlSeconds=9999` 400；重复动作 409。
- Grafana：`/api/annotations?tags=chaos&limit=20` 返回 8 条（kill/freeze/revert/start/restart），标签 `chaos,java-learn,jl-app-3,<action>`；重建后最终一条 text 含 `trigger=ttl`。
- 指标实例（重建前那次进程）：`chaos_actions_total{result="accepted"|"rejected"|"unready"}`、`chaos_reverts_total{result="success"}`、`chaos_active_actions 0`。
- 重建后 `up.sh`：16 个容器全部 running/healthy；非 lab `redis` 容器 `Up 9 days`、`StartedAt` 未变化。
