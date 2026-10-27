> 步骤交接 · chaos-controller 的 Toxiproxy 网络故障动作（设计 §11.3 / §3.3）
> 日期：2026-10-27
> 分支 / HEAD：worktree `agent-general-purpose-13dc9850`，基线 `699942c`（`Merge branch 'worktree-agent-general-purpose-6412944d' ...`）
> 关联任务：M5 网络故障能力半部分；compose overlay 与脚本由并行代理负责，本步只交付 chaos-controller
> 测试 / verify：本 worktree 内 `mvn -pl services/chaos-controller -am test` → **191 tests / 0 failures / 0 errors / 0 skipped**（138 基线 + 53 新增）；未执行任何 Docker / compose / 活栈命令
> 运行状态：代码与测试完成，未启动栈；Toxiproxy 的真实链路、Grafana P99 变化、stop-all 的活栈恢复均未验证
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- 在 chaos-controller 内新增 Toxiproxy 客户端、动作服务、控制面 API、配置与指标，并把网络恢复接入全局 stop-all；容器动作语义未改动。
- 变更范围仅 `code/services/chaos-controller/**` 与本文档；未触碰 compose、脚本、应用模块或主工作树源码。
- 测试 191 个全绿：138 个基线 + 53 个新增（`ToxiproxyGatewayTest` 11、`ToxiproxyServiceTest` 17、`ToxiproxyControllerTest` 12、`ToxiproxyPropertiesTest` 10，`ChaosActionServiceTest` +2、`ChaosControllerTest` +1）。
- 未完成：活栈演练、k6 场景、compose overlay 接线（并行代理）、操作台按钮。Toxiproxy 的 `chaos-proxy` profile 默认不启用，应用必须带代理环境变量重启（设计 §3.3），本步无法在无 Docker 环境验证。

# 完成功能项

## 1. 客户端（`toxiproxy/`）

| 文件 | 作用 |
|---|---|
| `ToxiproxyGateway` | 接口：`addToxic` / `removeToxic` / `resetProxy` / `setProxyEnabled` / `listToxics` / `getProxy` |
| `HttpToxiproxyGateway` | 基于 JDK `HttpClient` 的实现；连接超时 + 整请求超时；Toxiproxy v2 `POST /proxies/{proxy}/toxics`、`DELETE .../toxics/{name}`、`POST /proxies/{proxy}`、`GET /proxies/{proxy}` |
| `ToxicType` / `ToxicSpec` | 有界类型模型：`latency`（latencyMs/jitterMs）、`bandwidth`（rateKbps）、`timeout`（timeoutMs） |
| `ToxicRequest` | API 入参：`{"type":..., "name":..., "params":{...}}`，参数可省略走默认值 |
| `ToxicView` / `ProxyView` / `ProxyStateView` / `ToxiproxyStateView` / `ProxyResetView` / `ProxyEnabledView` | 控制台视图，不含 URL/凭据 |

网关把 Toxiproxy HTTP 状态映射为控制器异常：400 → 400/40001，404 → 404/40401，409 → 409/40901，传输错误/5xx → 503/50301。

## 2. API 契约（均在 `TokenFilter` 的 `/chaos/**` 保护下，信封 `{code,message,data,success}`）

| 方法 | 路径 | 成功 | 说明 |
|---|---|---|---|
| POST | `/chaos/toxiproxy/{proxy}/toxics` | **202** + `ToxicView` | body `type` 必填；`name` 可选（默认类型名）；`params` 按类型可选；未知参数/越界 → 400/40001；代理不在 allow-list → 404/40401；同名毒化冲突 → 409/40901 |
| DELETE | `/chaos/toxiproxy/{proxy}/toxics/{name}` | 200 + `Result<Void>` | 删除即恢复该毒化；不存在 → 404/40401 |
| POST | `/chaos/toxiproxy/{proxy}/reset` | 200 + `{proxy,toxicsRemoved,enabled}` | 删除全部毒化 **并** 启用代理，单代理完整恢复 |
| POST | `/chaos/toxiproxy/{proxy}/disable` | 200 + `{proxy,enabled:false}` | 断网；保留已有毒化 |
| POST | `/chaos/toxiproxy/{proxy}/enable` | 200 + `{proxy,enabled:true}` | 恢复 |
| GET | `/chaos/toxiproxy/state` | 200 + `{enabled,allowedProxies,proxies[]}` | 每个代理 `{name,present,enabled,toxics[]}`；代理未注册 → `present=false`；控制面不可达 → 503/50301 |
| POST | `/chaos/toxiproxy/reset-all` | 200 + `NetworkResetReport` | 网络层单独恢复；与 stop-all 共用同一服务方法 |

禁用态（`chaos.toxiproxy.enabled=false`）所有动作与 state 均返回 503/50301，消息明确指向配置项与 `chaos-proxy` profile，不会 NPE。

## 3. 毒化模型（已选定并固定）

- **延迟**：`{"type":"latency","params":{"latencyMs":500,"jitterMs":50}}` → Toxiproxy `latency` + `jitter`。
- **带宽**：`{"type":"bandwidth","params":{"rateKbps":64}}` → Toxiproxy `rate`（KB/s）。
- **断网（毒化方式）**：`{"type":"timeout","params":{"timeoutMs":1000}}` → Toxiproxy `timeout`：数据停止通过，连接在超时后关闭；`timeoutMs` 必须 ≥1，0 会让连接永久挂起而不是断开。
- **断网（代理方式）**：`/disable` 直接拒绝新连接，`/enable` 恢复；这是更硬的切断，适合演示“MySQL 整个不可用”。
- 毒化固定 `toxicity=1.0`、Toxiproxy 默认 `downstream` 流；不暴露部分毒化/双向流，避免教学演示不确定。
- 毒化没有 TTL：Toxiproxy 状态不在 Docker 里，无法像容器动作那样由 watchdog 推断回滚；模型是“施加 → 观察 → reset/stop-all”。

## 4. 指标（与既有 meter 一致）

代码 meter 名 `chaos.actions`；Prometheus 导出为 `chaos_actions_total`。本步动作标签：

| `action` | 触发点 |
|---|---|
| `toxiproxy-add` | 创建毒化 |
| `toxiproxy-remove` | 删除单个毒化 |
| `toxiproxy-reset` | 单代理 reset |
| `toxiproxy-disable` / `toxiproxy-enable` | 断网/恢复 |
| `toxiproxy-reset-all` | stop-all 或 `/reset-all`，每个代理计数一次 |

`result` 取值 `accepted` / `rejected` / `failed`。`target` 为配置内的代理名；未配置/禁用的请求统一记 `target="unknown"`，避免用户输入造成标签基数爆炸。GET state 不计数。示例：

```text
chaos_actions_total{action="toxiproxy-add",target="redis",result="accepted"}
chaos_actions_total{action="toxiproxy-reset-all",target="mysql",result="failed"}
```

## 5. stop-all 集成

- `ChaosActionService.stopAll()` 在容器回滚循环之后调用 `NetworkFaultCleaner.resetAll()`；`ToxiproxyService` 实现该接口，对每个配置代理执行“删全部毒化 + enable”。
- `StopAllResult` 新增 `toxiproxy` 字段（`NetworkResetReport`：`status/proxiesReset/proxiesTotal/errors`），保留原三参构造器。网络清理失败只记入报告并 WARN，**不影响**容器回滚与 stop-all 的 200。
- 控制面不可达时，reset-all 在首个失败代理后停止继续尝试，避免 stop-all 按代理数累加连接超时；剩余代理记 `not attempted`。
- 暂停（guide pause）不阻止 stop-all 的网络恢复：stop-all 本来就是“全局急停”的逃生口。

## 6. 配置（`chaos.toxiproxy.*`，全部可在 `application.yml` / 环境变量覆盖）

| 键 | 环境变量 | 默认 | 校验 |
|---|---|---|---|
| `enabled` | `CHAOS_TOXIPROXY_ENABLED` | `true` | 关闭后端点 503/50301，stop-all 返回 `disabled` |
| `url` | `CHAOS_TOXIPROXY_URL` | `http://toxiproxy:8474` | 启用时必填；必须 absolute http(s) 且有 host |
| `connect-timeout-seconds` | `CHAOS_TOXIPROXY_CONNECT_TIMEOUT_SECONDS` | `2` | 1–60 |
| `request-timeout-seconds` | `CHAOS_TOXIPROXY_REQUEST_TIMEOUT_SECONDS` | `5` | 1–60 |
| `proxies` | `CHAOS_TOXIPROXY_PROXIES` | `redis,mysql` | 每项 `[A-Za-z0-9][A-Za-z0-9_-]{0,63}`，不得重复；启用时不得为空 |
| `default-latency-ms` / `max-latency-ms` | `CHAOS_TOXIPROXY_DEFAULT_LATENCY_MS` / `..._MAX_LATENCY_MS` | `500` / `30000` | default 0..max；max 1..600000 |
| `default-jitter-ms` / `max-jitter-ms` | `CHAOS_TOXIPROXY_DEFAULT_JITTER_MS` / `..._MAX_JITTER_MS` | `50` / `5000` | default 0..max |
| `default-bandwidth-kbps` / `max-bandwidth-kbps` | `..._DEFAULT_BANDWIDTH_KBPS` / `..._MAX_BANDWIDTH_KBPS` | `100` / `10240` | default 1..max |
| `default-timeout-ms` / `max-timeout-ms` | `..._DEFAULT_TIMEOUT_MS` / `..._MAX_TIMEOUT_MS` | `1000` / `60000` | default 1..max |

`@PostConstruct ChaosProperties.validate()` 在启动时校验以上全部值，错误消息带属性路径与环境变量名。compose overlay 只需提供 `CHAOS_TOXIPROXY_URL`（默认已指向 `http://toxiproxy:8474`）与代理名（默认 `redis,mysql`）；如默认栈不跑 `chaos-proxy`，建议显式 `CHAOS_TOXIPROXY_ENABLED=false`。

## 7. 5 分钟演示脚本

前置：`chaos-proxy` profile 已起、应用已用代理连接重启（设计 §3.3）、`LAB_TOKEN` 已导出、控制器 `http://localhost:8081` 可达。

```bash
export LAB_TOKEN=...            # 不写入文档/命令历史
CTRL=http://localhost:8081
H="X-Lab-Token: $LAB_TOKEN"

# 0) 看状态：redis/mysql 都存在且 enabled=true，toxics 为空
curl -s -H "$H" $CTRL/chaos/toxiproxy/state | jq

# 1) 给 redis 链路加 500ms 延迟（再带 50ms 抖动）
curl -s -X POST -H "$H" -H 'Content-Type: application/json' \
  -d '{"type":"latency","params":{"latencyMs":500,"jitterMs":50}}' \
  $CTRL/chaos/toxiproxy/redis/toxics | jq
# 期望：202，data.name=latency，attributes.latency=500

# 2) 观察 60s：商品查询 P99 在 Grafana D1/D3（或 /metrics 的 HTTP 直方图）上升；
#    Redis 命令耗时随之上升，应用本身 CPU/GC 无异常。

# 3) 复位 redis：删除毒化 + 启用
curl -s -X POST -H "$H" $CTRL/chaos/toxiproxy/redis/reset | jq
# 期望：toxicsRemoved=1，enabled=true；P99 在一个采样窗口内回落

# 4) 断网 mysql：拒绝连接，应用报 SQL 连接失败/超时（5xx 或降级）
curl -s -X POST -H "$H" $CTRL/chaos/toxiproxy/mysql/disable | jq
# 5) 恢复 mysql
curl -s -X POST -H "$H" $CTRL/chaos/toxiproxy/mysql/enable | jq

# 6) 一键恢复（含网络）：容器动作回滚 + 所有代理 reset + enable
curl -s -X POST -H "$H" $CTRL/chaos/stop-all | jq
# 期望：data.toxiproxy.status="reset"，proxiesReset=2，errors=[]
```

可选带宽演示：`{"type":"bandwidth","params":{"rateKbps":64}}`（当前仅单测验证，未活栈验证）。

# 设计逻辑

- **客户端选型**：仓库现有出站 HTTP 只有 `HttpGrafanaAnnotator` 使用 JDK `java.net.http.HttpClient`；本步沿用同一栈，不引入 `RestClient`/`WebClient` 或新依赖。连接超时与整请求超时分别外置，避免 Toxiproxy 挂死拖住 stop-all。
- **为什么 `ToxicSpec` 用 sealed 接口而非一个万能 DTO**：延迟/带宽/超时的属性互斥，类型化后服务层不可能把 `rateKbps` 塞进 latency 毒化；网关只负责把 variant 翻译成 Toxiproxy 的 `attributes`。
- **为什么断网选 `timeout` 毒化而不是再发明一种类型**：Toxiproxy 原生 `timeout` 就是“停止数据、超时后断连”，语义与演示一致；`/disable` 作为更硬的补充。两者都由 reset（删毒化 + enable）恢复，演示脚本不需要记两套清理方式。
- **为什么代理名走 allow-list 而不是任意路径**：控制面路径直接拼代理名，若允许任意字符串会把控制器变成任意 Toxiproxy 代理的操作入口；allow-list 与容器 `lab=java-learn` 白名单是同一安全思路。未列入的代理返回 404/40401，与既有“资源不存在”契约一致（任务简报写作 404/40001，此处按仓库实际 `CommonErrorCode.NOT_FOUND=40401` 落地，见缺陷项）。
- **为什么禁用默认 true**：能力默认在场，overlay 起来即可用；profile 未起时端点 503/50301 而不是“功能不存在”。默认栈若长期不跑 `chaos-proxy`，会看到 stop-all 的网络清理失败（报告 `failed`），因此文档建议显式关闭。
- **为什么不给毒化加 TTL/watchdog**：容器动作的回滚依据是 Docker 里的真实状态；毒化只存在于 Toxiproxy 内存，watchdog 无法区分“操作者故意保留”与“任务丢失”。教学语义是手动观察后 reset，stop-all 兜底。
- **指标基数**：只对配置内代理打 `target`；未知/禁用的请求记 `target="unknown"`，与既有 `chaos.actions` 的 `action/target/result` 维度保持一致，Prometheus 侧仍是 `chaos_actions_total`。
- **stop-all 不抛异常**：网络清理是“尽力而为”，容器回滚已经发生，不能因为 Toxiproxy 不在而让急停返回失败；报告字段让操作台仍能看见网络未恢复。

# 存在缺陷项&约束

1. **未做活栈验证**：本环境禁止 Docker/compose；Toxiproxy 版本行为、代理拓扑、Redis/MySQL 客户端重连、Grafana P99 变化均未实测。单测用 JDK `HttpServer` 在 loopback 上验证 HTTP 语义，不等于真实 Toxiproxy。
2. **compose overlay 不在本步**：代理服务、`CHAOS_TOXIPROXY_URL`、应用侧代理环境变量、`chaos-proxy` profile 由并行代理提交；本步只守契约（URL 默认 `http://toxiproxy:8474`，代理名默认 `redis,mysql`）。若 overlay 的代理名不同，必须同时改 `CHAOS_TOXIPROXY_PROXIES`。
3. **默认 enabled=true 的噪音**：默认栈不跑 Toxiproxy 时，每次 stop-all 都会尝试连接并在报告里出现 `toxiproxy.status=failed`（约一个连接超时）；建议默认栈设置 `CHAOS_TOXIPROXY_ENABLED=false`，或由 overlay 在 `chaos-proxy` profile 下显式置 true。
4. **reset 非原子**：Toxiproxy 没有单代理 reset API，本实现是 list + 逐个 DELETE；若 resetProxy 成功而随后的 enable 失败，会返回 503 但毒化已删除、代理仍禁用。报告与日志保留错误，需再次 `/enable` 或 `/reset`。
5. **带宽毒化未演练**：代码、边界与 HTTP 形状有单测，但没有真实链路吞吐数据；演示脚本把它列为可选。
6. **无 k6 场景**：未新增/修改 `code/lab/k6/**`；网络故障的 P99/错误率对比需要后续 k6 场景与报告。
7. **无操作台 UI**：只交付 HTTP API；控制台按钮/状态卡未做。
8. **错误码偏差**：任务简报写“404/40001”，仓库既有契约是 404 → `40401`（`CommonErrorCode.NOT_FOUND`），本步按后者实现；若确需 40001，可改 handler 一行，但会破坏“404 用 40401”的一致性。
9. **毒化无 TTL**：不会自动回滚；只能 `/reset`、`/reset-all` 或 stop-all。暂停（pause）也不会冻结毒化（毒化本来就没有计时器）。
10. **Toxiproxy 无认证**：控制面只在 compose 内部网络暴露，未加 TLS/token；若未来暴露到宿主，需要额外的网络/认证设计，不能用本 API 直接穿透。
11. **state 的失败粒度**：单个代理未注册 → `present=false` 并继续；控制面整体不可达 → 整个 state 503，不会返回部分旧数据（没有缓存）。
12. **StopAllResult 结构变化**：新增 `toxiproxy` 字段；旧三参构造器保留，现有调用方/测试不受影响，但严格校验 JSON 字段集的客户端需要更新。

# 待定事项和注意事项

- **活栈验收顺序**：起 `chaos-proxy` profile → 确认应用已通过代理访问 Redis/MySQL（重启应用）→ `GET /chaos/toxiproxy/state` 两个代理 `present=true` → 按 5 分钟脚本执行 → 观察 `chaos_actions_total{action=~"toxiproxy-.*"}` 与 D1/D3 的 P99/错误率 → stop-all 后确认 state 为空毒化且两个代理 enabled。
- **compose 契约复核**：并行代理合并后，核对 `CHAOS_TOXIPROXY_URL`、代理名、应用侧 `spring.data.redis.host/port` 与 MySQL URL 是否真的指向 Toxiproxy 的 `listen` 端口；任何一项不一致，延迟/断网会打在错误链路上。
- **k6 场景**：建议新增“redis latency 500ms 前后 P99 对比”和“mysql disable 期间错误率”两个场景；本步未做。
- **带宽演示**：若教学需要，先在活栈用 `rateKbps=64` 验证吞吐下降曲线，再写入主演示脚本。
- **操作台**：把 `GET /state` 渲染成代理卡片，按钮顺序建议“加延迟 / reset / disable / enable / stop-all”，并显示 `NetworkResetReport.errors`。
- **默认栈开关**：与 compose 负责人确认默认 profile 是否显式 `CHAOS_TOXIPROXY_ENABLED=false`，避免每次 stop-all 出现网络清理失败报告。

## 附录：验证命令与关键文件

```bash
cd /d/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-13dc9850/code
mvn -pl services/chaos-controller -am test
# 本 worktree 结果：191 tests / 0 failures / 0 errors / 0 skipped / BUILD SUCCESS
```

关键文件：`code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/toxiproxy/**`、`web/ToxiproxyController.java`、`action/NetworkFaultCleaner.java`、`action/NetworkResetReport.java`、`action/StopAllResult.java`、`config/ChaosProperties.java`、`config/ChaosConfig.java`、`web/LabExceptionHandler.java`、`src/main/resources/application.yml`。
