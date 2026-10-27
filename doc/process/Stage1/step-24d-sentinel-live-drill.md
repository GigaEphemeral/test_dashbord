> 步骤交接 · M3-1c 活体 Sentinel 切换演练（compose 接线 + 24d）
> 日期：2026-10-27
> 分支 / HEAD：`feature/ha-lab` / 接线提交 `58b1e75`（`feat: wire the datastore allow-list into the lab chaos-controller`）+ 本文档提交
> 关联任务：#49（M3-1c 活体验收，本步收口）；上游 #47（24a 拓扑）、#48（24b 客户端，`step-24b-sentinel-client.md`）、#49 代码部分（24c，`step-24c-sentinel-targets.md`）；下游 #61（批次门禁：`verify.sh` / `smoke.sh` / `console-smoke.sh`）
> 设计依据：`doc/design/Stage1/2026-10-26-ha-concurrency-lab-design.md` §8.2；`doc/design/工程规约.md` 第 1 节（配置外置）
> 验证：默认 compose 渲染 **16** 服务、`-f compose.ha.yml --profile ha` **25**；chaos-controller 重建后 healthy；`redis-sentinel-failover` 出现在 `/lab/guide/scenarios`；guide 实跑 `START → STEP1 → STEP2 → STEP3 → COMPLETED`；Nginx 商品读 52 次轮询 **50×200 / 2×000**，停主后首个 200 于 **+9.1s**（≤15s）；旧主 TTL 自动回归且 `role=slave, master_link_status=up`；同 JSON 经 k6 `ha-failover` 路径 3/3 动作 accepted、0 错误；app 已还原 standalone，HA 拓扑 1 主 2 从健康，全部容器 running。**未跑** `verify.sh` / `smoke.sh` / `console-smoke.sh`（#61 所有）。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- 24c 的部署阻塞项已关闭：`compose.base.yml` 的 `chaos-controller.environment` 现在注入 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`，lab compose 的默认值是**恰好** `^jl-redis-[1-3]$`（整名匹配）；代码默认仍是空 = 全拒。同步把 `CHAOS_GUIDE_TARGET_PATTERN` 默认放宽到 `^jl-(app|redis)-[0-9]$`，否则场景在 `GuideScenarioLoader` 预检阶段仍会被 guide pattern 拒掉。两键都写进 `.env.example` 并附原因；live `.env` 未改、未打印任何值（compose fallback 已生效，见下）。
- 只重建了 `java-learn-lab/chaos-controller:jdk21` 镜像并 recreate `jl-chaos-controller`，未动任何数据容器。容器内生效值实测为 `^jl-(app|redis)-[0-9]$` / `^jl-redis-[1-3]$`；`GET /lab/guide/scenarios` 在重建后出现 `redis-sentinel-failover`。
- 应用三实例经 `compose.ha.yml` 重建为 **sentinel**：日志 `Redis access mode: sentinel` + `Sentinel discovered master 'mymaster' at 172.28.190.11:6379`；演练期间无 app 容器重建（StartedAt 前后逐字节相同）。
- guide 实跑完成：停主 → Sentinel `+sdown/+odown/+switch-master` → 新主 `jl-redis-2`（172.28.190.12）→ TTL 45s 自动把 `jl-redis-1` start 回来并以副本加入 → guide `COMPLETED`、`GET /chaos/actions` 为空。HTTP 只出现 2 次 3s 客户端超时，停主后 +9.1s 恢复 200。
- 负面路径活体验证：`jl-redis`（dev 单机）、`jl-mysql`、`jl-rabbitmq`、`jl-redis-1-exporter`、`jl-redis-10` 全部 403 `40301`；**id 前缀引用**（`jl-mysql-1` 的 `16405467edcc`）也被拒并点名 canonical target；`jl-redis-1` 的 id 前缀 `b230e025ef0d` 的幂等 `start` 通过（202 completed），未产生任何故障。
- 同一 JSON 经 k6：`CHAOS_SCENARIO_FILE=redis-sentinel-failover.json` + `K6_FORWARD_ENV=CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` 跑 `run.sh ha-failover`，`chaos_events=3`、720/720 checks、HTTP 失败 0%、阈值全绿、teardown `reverted=0 failed=0`。此时主已是 `jl-redis-2`，场景固定写 `jl-redis-1`，所以这一轮停的是副本、**不是第二次切换**——k6 路径证明的是驱动/校验/白名单/回滚，不是第二次晋升。
- 收尾：app 三实例回到 standalone（日志 `Redis access mode: standalone`、env 无 `REDIS_MODE`、单网 `jl-net`）；HA 拓扑 **1 master（jl-redis-2）+ 2 replicas（jl-redis-1/3，`master_link_status=up`）+ 3 sentinels 一致**；master 漂移到 `jl-redis-2` 是本次 failover 的正确结果。未 `--purge`、未删卷、未触碰 MySQL/Router 层。

# 完成功能项

## 1. compose 接线与默认策略（`58b1e75`）

- `code/lab/docker/compose.base.yml` → `chaos-controller.environment`：
  - `CHAOS_GUIDE_TARGET_PATTERN: ${CHAOS_GUIDE_TARGET_PATTERN:-^jl-(app|redis)-[0-9]$$}`
  - `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN: ${CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN:-^jl-redis-[1-3]$$}`
- `code/lab/docker/.env.example` 记录同两键、整名匹配语义与"不得放宽到 `jl-redis` / `.*`"的交付口径。
- compose 内的 `$$` 是 Compose 的字面 `$`；容器内实测为 `...$`（`docker exec jl-chaos-controller printenv` 输出见附录）。live `.env` 没有这两键，走 compose fallback；运维仍可用 `.env` 覆盖，无需重建镜像，只需 recreate 容器。

## 2. Controller 重建与生效策略

- 仅 `build chaos-controller` + `up -d chaos-controller`（项目 `java-learn-lab`，base+dev 文件）。其他 24 个容器未重建。
- 验证：`GET /lab/guide/scenarios` 返回 3 个场景（含 `redis-sentinel-failover`）；`jl-redis` 等负面目标 403；`jl-redis-1` 的 `start` 202；id 前缀的解析结果被 canonical name 二次校验。

## 3. 应用进入 Sentinel 模式

命令（与 24b 一致，仅 app 三服务）：

```bash
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  -f code/lab/docker/compose.dev.yml -f code/lab/docker/compose.ha.yml \
  --profile ha up -d app-1 app-2 app-3
```

启动证据：三实例均有 `Redis access mode: sentinel` 与
`Sentinel discovered master 'mymaster' at 172.28.190.11:6379 (probe 1/12, sentinels [sentinel-1:26379, sentinel-2:26379, sentinel-3:26379])`。

## 4. Guide 实跑（`/lab/guide/**`）

- 起始主：`jl-redis-1` = `172.28.190.11`（与场景固定 target 一致）。
- 状态序列（`GET/POST /lab/guide/state`、`/step`）：

```text
start   : status=running   currentStep=0/3 activeActions=0 lastError=null
step 1  : status=running   currentStep=1/3 activeActions=1 lastError=null   # kill jl-redis-1 ttl=45
step 2  : status=running   currentStep=2/3 activeActions=1 lastError=null   # resume jl-redis-2 (anchor)
step 3  : status=completed currentStep=3/3 activeActions=0 lastError=null   # start jl-redis-1 (idempotent)
final   : status=completed currentStep=3/3 activeActions=0 lastError=null
GET /chaos/actions -> {"code":"0","message":"OK","data":[],"success":true}
```

- 实际时间线（UTC；`sentinel-status.sh` 单次需要约 20s，guide 是手动逐步，因此 step2/step3 的墙钟晚于场景 `atSeconds` 的提示值，属预期）：

```text
19:05:01.581 POST /lab/guide/start
19:05:03.950 STEP1 kill jl-redis-1 ttl=45
19:05:16Z    sentinel-status 快照：master 已切到 172.28.190.12
19:05:43.431 STEP2 resume jl-redis-2 (anchor)
19:05:49      TTL 到点，jl-redis-1 自动 start（docker events）
19:05:50.497  脚本观测到 jl-redis-1 state=running
19:06:30.697 STEP3 start jl-redis-1（幂等，仅标记回归）
```

- Sentinel 日志（`jl-sentinel-1`）：

```text
26 Oct 2026 19:05:09.993 # +sdown master mymaster 172.28.190.11 6379
26 Oct 2026 19:05:11.117 # +odown master mymaster 172.28.190.11 6379 #quorum 3/2
26 Oct 2026 19:05:11.630 # +switch-master mymaster 172.28.190.11 6379 172.28.190.12 6379
26 Oct 2026 19:05:53.263 # -sdown slave 172.28.190.11:6379 172.28.190.11 6379 @ mymaster 172.28.190.12 6379
```

- `docker events`（`jl-redis-1`，epoch 转 UTC）：`19:05:04 kill` → `19:05:05 stop` → `19:05:05 die` → `19:05:49 start`；即 TTL 45s 精确自动回滚，未做手工 `start`。
- 应用免重启：`jl-app-1/2/3` 的 `StartedAt` 演练前后均为 `2026-10-26T19:01:22.642589306Z` / `.625677369Z` / `.636497797Z`；三实例各出现一条 Lettuce
  `Reconnected to 172.28.190.12/<unresolved>:6379`（19:05:15.800 / .847 / .855）。本次窗口内 **未观察到** 24b 记录的 Redisson `master ... has been changed to ...` INFO 行（见缺陷项）。

## 5. 负面路径（含 id 前缀）

```text
jl-redis              HTTP 403 {"code":"40301","message":"target \"jl-redis\" is a datastore and is refused by chaos.datastore.allowed-target-pattern=\"^jl-redis-[1-3]$\": only a datastore container matching that allow-list may be faulted (keyword set (?i)(mysql|redis|rabbit|mongo|postgres))",...}
jl-mysql              HTTP 403 同上（target "jl-mysql"）
jl-rabbitmq           HTTP 403 同上（target "jl-rabbitmq"）
jl-redis-1-exporter   HTTP 403 同上（target "jl-redis-1-exporter"）
jl-redis-10           HTTP 403 同上（target "jl-redis-10"，整名不匹配 [1-3]）
id 前缀 16405467edcc  HTTP 403 {"code":"40301","message":"container reference \"16405467edcc\" resolves to target \"jl-mysql-1\" is a datastore and is refused by chaos.datastore.allowed-target-pattern=\"^jl-redis-[1-3]$\": ...",...}
```

允许路径（幂等、无故障）：`jl-redis-1` + `{"action":"start"}` → HTTP 202，`status=completed`；id 前缀 `b230e025ef0d` + `start` → HTTP 202，`target` 回显为前缀但实际执行在 canonical `jl-redis-1` 上。

**canonical-name 观察（评审"无法验证"项）**：Docker inspect 的 `.Name` 带前导 `/`，实测
`docker inspect -f '{{.Name}}' jl-redis-1 jl-redis jl-mysql-1` → `/jl-redis-1`、`/jl-redis`、`/jl-mysql-1`；`DockerContainerGateway.canonicalName()` 在回报 `ContainerView.name()` 前剥掉 `/`，id 前缀拒绝消息里是 `target "jl-mysql-1"`（无 `/`），且 `jl-redis-1` 的 id 前缀能正常通过——**没有**因前导斜杠误拒合法名。

## 6. k6 路径

```bash
LAB_TOKEN=... \
CHAOS_SCENARIO_FILE=redis-sentinel-failover.json \
CHAOS_TARGET_PATTERN='^jl-(app|redis)-[0-9]+$' \
CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-redis-[1-3]$' \
K6_FORWARD_ENV=CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN \
CHAOS_DURATION=2m CHAOS_VUS=6 bash code/lab/k6/run.sh ha-failover
```

结果：`chaos_events...................: 3`；`checks 720/720`；`http_req_failed 0.00%`；`http_req_duration{flow:product}` p95=29.14ms / p99=41.15ms（阈值 300/800）；teardown `[chaos] teardown stop-all reverted=0 failed=0`。k6 通过 **JSON 文件名**选场景（`CHAOS_SCENARIO_FILE`），不是 guide 的 scenario id；`run.sh` 只接受 `smoke|load|stress|spike|soak|ha-failover` 这些驱动名。任意 `code/lab/scenarios/*.json` 都可挂载运行，因此无需改代码；`CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` 不在 `run.sh` 的默认转发表里，本次用其文档化的 `K6_FORWARD_ENV` 透传。

## 7. 还原

```bash
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  -f code/lab/docker/compose.dev.yml up -d app-1 app-2 app-3
```

- 三实例日志 `Redis access mode: standalone`（19:11:58–19:12:00Z），env 中无 `REDIS_MODE`，仅 `jl-net`。
- 最终拓扑：master `jl-redis-2`（172.28.190.12）；`jl-redis-1`、`jl-redis-3` `role=slave`、`master_host=172.28.190.12`、`master_link_status=up`；三 Sentinel 的 `master0` 均为 `address=172.28.190.12:6379,slaves=2,sentinels=3`。
- Nginx `GET /api/products/1` → 200；controller healthy；默认/HA 渲染 16/25。
- 未 `down --purge`、未删卷、未重建/触碰 `jl-redis`（单机）、`jl-mysql*`、`jl-rabbitmq`、观测栈。所有 lab 容器保持 running。

# 设计逻辑

- **代码默认关闭、compose 默认按演练打开**：`DatastoreTargetPolicy` 的空值=全拒是安全基线；lab compose 的 `:-^jl-redis-[1-3]$` 只是"演示交付值"，且整名匹配（Java `matches()`、k6 `^(?:...)$`）保证 `jl-redis`（无数字后缀）、`jl-redis-10`、`jl-redis-1-exporter` 都不会被误放行。把默认值放在 compose（而不是只放 `.env`）让"克隆后照文档启动"即可演示；`verify.sh`/`smoke.sh` 的默认渲染仍是 16 服务，行为不变。
- **guide pattern 必须同步放宽**：allow-list 只决定"数据存储类是否特别许可"，`chaos.guide.target-pattern` 先决定"时间线允许碰哪些名字"。将其默认从 `^jl-app-[0-9]+$` 扩到 `^jl-(app|redis)-[0-9]$` 是让场景能通过 `GuideScenarioLoader` 的最小改动；有意**不**放 `sentinel`，因为 `jl-sentinel-*` 不是数据存储关键字，一旦进 target pattern 就会被 allow-list 之外的路径直接放行。数据存储侧仍由 `^jl-redis-[1-3]$` 收紧。
- **两道闸独立**：guide 侧是 400 预检，action 侧是 403 真边界；id 前缀在 action 侧解析出 canonical name 后**二次套用**同一策略，避免"关键字看不见 ID"的绕过。TTL 回滚（`start`/`SIGCONT`）刻意绕过策略——能开的故障必须能关。
- **为什么 step2/step3 晚于 `atSeconds`**：`GuideService` 是手动状态机，`atSeconds` 只是时间线提示；`sentinel-status.sh` 单次约 20s（多次 `docker exec` + 超时），脚本又在 step1 后先取证。TTL 在 19:05:49 已自动回滚，step3 只是幂等标记，不影响"TTL 自动还原"的结论。
- **k6 的选场景方式**：k6 驱动名固定为 `ha-failover`，场景内容由 `CHAOS_SCENARIO_FILE` 选择；这是文件级复用，不是 guide scenario id 查询。不同场景共用 `ChaosActionService`，因此 allow-list、TTL、stop-all 行为一致。

# 存在缺陷项&TODO

- **场景 target 仍写死 `jl-redis-1`**：本次演练后主已漂移到 `jl-redis-2`；k6 那一轮因此停的是副本，没有第二次晋升。这是 24c 已记录的"教学简化"；机器校验（先读 sentinel 再替换 target）仍未实现。重放前必须先跑 `sentinel-status.sh`。
- **Redisson 的 master-change INFO 行未出现**：三实例只看到 Lettuce `Reconnected to 172.28.190.12` 与 Sentinel `+switch-master`；24b 记录的 Redisson `master ... has been changed to ...` 本轮未复现（可能为日志级别/版本差异）。不影响免重启与读写恢复结论，但 24b 的日志断言需在 #61 或后续复核。
- **HTTP 抖动**：52 次轮询中 2 次 3s 客户端超时（第一次请求在 kill 前 0.25s 发出、跨越了停主；第二次在切换窗口内）。停主到首个 200 约 9.1s，最后一条失败请求结束到恢复约 2.2s；符合 ≤15s 阈值，但不是"全程 200"。
- **k6 未默认转发 allow-list**：`run.sh` 的 chaos `CONFIG_VARS` 不含 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`，本次靠 `K6_FORWARD_ENV`。建议后续把该键加入默认转发表，避免 operator 忘传时 init 期直接报 `is a datastore; ...`。
- **guide 的 step 时间与 `atSeconds` 不一致**：手动驱动 + 取证脚本的 20s 读耗时导致 step2/3 晚点；若演示要求严格 10/25/60s，需要把 `sentinel-status.sh` 放到独立后台或缩短其 exec 预算。本次没有为此改动代码。
- **未验证项**：Grafana 注解条数与内容、`chaos_actions_total`/`chaos_reverts_total` 指标曲线、`console-smoke.sh`/`smoke.sh`/`verify.sh`（#61 所有）。k6 的 `chaos` 时间线详情行未落盘（只保留了汇总与阈值），但 `chaos_events=3` 与退出码为 0。
- **24b 遗留**：L1 Caffeine 60s TTL 仍可能掩盖 L2 切换（本次轮换 product id 规避）；`jl-redis-*-exporter` 仍未进 Prometheus 抓取。

# 待定事项和注意事项

1. **批次门禁 #61**：请跑
   `verify.sh`（ha 在跑时预期含 HA 断言；本步未跑、不背书具体 PASS 数）、
   `smoke.sh`（预期 7/7）、
   `console-smoke.sh`（默认 `quick-failover`，不受本次 compose 默认放宽影响）。默认/HA compose 服务数应为 **16 / 25**。
2. **重放演练前**：先 `bash code/lab/scripts/sentinel-status.sh` 确认 `mymaster` 当前地址对应哪个 `jl-redis-<n>`；场景第一步固定 `jl-redis-1`，不匹配就先改场景（或等主漂移回来）。本栈当前主是 `jl-redis-2`。
3. **进入/退出 sentinel 演示态**：进入用本步第 3 节命令（base+dev+ha，`--profile ha`，仅 `app-1/2/3`）；退出用第 7 节命令（base+dev，不带 ha）。两态都会重建 app，属于预期。
4. **策略覆盖**：live `.env` 未写入两键，使用 compose fallback；如要临时改回全拒，在 `.env` 设空值并 `up -d --force-recreate chaos-controller`（Spring 属性启动期读取）。交付配置**不得**把 allow-list 放宽到 `jl-redis`、`.*` 或包含 `jl-mysql`/`jl-rabbitmq`。
5. **安全边界不变**：`X-Lab-Token`、`lab=java-learn`、TTL 上限、`/lab/**` 鉴权、`stop-all` 均未改；本次只动 `compose.base.yml` 的两行 env 默认与 `.env.example` 文档，外加本交接文档。未打印/提交任何 `.env` 值、token 或密码。

# 附录

## 关键命令与文件

- 接线：`code/lab/docker/compose.base.yml`（`chaos-controller.environment`）、`code/lab/docker/.env.example`
- 控制器重建（仅此服务）：
  ```bash
  docker compose --project-name java-learn-lab --project-directory code/lab/docker \
    --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
    -f code/lab/docker/compose.dev.yml build chaos-controller
  docker compose --project-name java-learn-lab --project-directory code/lab/docker \
    --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
    -f code/lab/docker/compose.dev.yml up -d chaos-controller
  ```
- Guide API（token 从 `.env` 读取，未回显）：
  `POST /lab-api/lab/guide/start {"scenarioId":"redis-sentinel-failover"}`、`POST /lab-api/lab/guide/step`、`GET /lab-api/lab/guide/state`、`GET /lab-api/chaos/actions`。
- 证据采集：`bash code/lab/scripts/sentinel-status.sh`（before/mid/after）、`docker events --filter container=jl-redis-1`、Nginx product 读 2s 轮询（轮换 id 1..20，`--max-time 3`）。

## 提交与生效值（verbatim）

```text
$ git show --stat --oneline 58b1e75
58b1e75 feat: wire the datastore allow-list into the lab chaos-controller
 code/lab/docker/.env.example     | 12 ++++++++++++
 code/lab/docker/compose.base.yml | 15 ++++++++++++++-
 2 files changed, 26 insertions(+), 1 deletion(-)

$ docker exec jl-chaos-controller printenv CHAOS_GUIDE_TARGET_PATTERN CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN
^jl-(app|redis)-[0-9]$
^jl-redis-[1-3]$
```

## Sentinel 状态（before / mid / after，节选 verbatim）

```text
[before 2026-10-26T19:03:40Z]
[jl-sentinel-1/2/3] get-master-addr-by-name : 172.28.190.11:6379
master0 summary : master0:name=mymaster,status=ok,address=172.28.190.11:6379,slaves=2,sentinels=3
[jl-redis-1] role : master
[jl-redis-2] role : slave  master_host:port : 172.28.190.11:6379  master_link_status : up
[jl-redis-3] role : slave  master_host:port : 172.28.190.11:6379  master_link_status : up

[mid 2026-10-26T19:05:16Z]
[jl-sentinel-1/2/3] get-master-addr-by-name : 172.28.190.12:6379
master0 summary : master0:name=mymaster,status=ok,address=172.28.190.12:6379,slaves=2,sentinels=3
[jl-redis-1] state=exited
[jl-redis-2] role : master  connected_slaves : 1
[jl-redis-3] role : slave  master_host:port : 172.28.190.12:6379  master_link_status : up

[after / final 2026-10-26T19:06:38Z]
[jl-sentinel-1/2/3] get-master-addr-by-name : 172.28.190.12:6379
master0 summary : master0:name=mymaster,status=ok,address=172.28.190.12:6379,slaves=2,sentinels=3
[jl-redis-1] role : slave  master_host:port : 172.28.190.12:6379  master_link_status : up
[jl-redis-2] role : master  connected_slaves : 2
[jl-redis-3] role : slave  master_host:port : 172.28.190.12:6379  master_link_status : up
```

## HTTP 轮询（2s，轮换 id 1..20，`--max-time 3`）

```text
total samples: 52
codes: 50 x 200, 2 x 000
failures:
  2026-10-26T19:05:03.702Z id=3 000 3.014418   # request started 0.25s before step1 kill
  2026-10-26T19:05:08.924Z id=5 000 3.015079
first 200 after stop:
  2026-10-26T19:05:14.112Z id=8 200 1.672090
stop events: 19:05:04Z kill, 19:05:05Z stop/die
=> stop to first 200 ~9.1s; last failed request ended ~19:05:11.9Z, recovery 2.2s later
app containers: StartedAt unchanged (no restart)
```

## 最终还原检查（verbatim）

```text
[jl-app-1] Redis access mode: standalone   (env: no REDIS_MODE; networks=jl-net)
[jl-app-2] Redis access mode: standalone   (env: no REDIS_MODE; networks=jl-net)
[jl-app-3] Redis access mode: standalone   (env: no REDIS_MODE; networks=jl-net)
Nginx GET /api/products/1 -> 200
jl-chaos-controller Up ... (healthy)
default compose services: 16
ha compose services (--profile ha): 25
```

## 与 #61 的边界

本步**没有**执行 `verify.sh`、`smoke.sh`、`console-smoke.sh`，也没有修改任何应用 Java 代码、k6 代码或既有文档。`verify.sh` 的 HA 断言、`smoke.sh` 的 7 项、`console-smoke.sh` 的 `quick-failover` 流程仍由 #61 统一跑；本次提供的基线是：16/25 渲染、controller healthy、Sentinel 1 主 2 从 up、Nginx 200、指南场景可用。
