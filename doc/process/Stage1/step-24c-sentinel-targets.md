> 步骤交接 · M3-1c Sentinel 故障目标白名单与切换场景（代码部分）
> 日期：2026-10-27
> 分支 / HEAD：`worktree-agent-general-purpose-96cc8572`（自 `91eb112`，基线 `feature/ha-lab`）/ `feat:` + `test:` + `docs:` 三提交
> 关联任务：#49（M3-1c 本步，代码部分）；上游 #47（24a Sentinel compose 拓扑，`step-24a-sentinel-compose.md`）、#48（24b 应用 Sentinel 客户端，`step-24b-sentinel-client.md`，其交接契约第 1、2 条即本步）；下游：活体验收（本步**未做**，见"存在缺陷项&TODO"与附录 runbook）、M3-2 MySQL InnoDB Cluster 复用同一白名单机制
> 设计依据：`doc/design/Stage1/2026-10-26-ha-concurrency-lab-design.md` §8.2（复制异步→切换可能丢最近写入；应用按主名发现、免重启重挂）与设计文档场景表 `redis-sentinel` / `db-failover` 行；`doc/design/工程规约.md` 第 1 节（一切路径/超时/TTL/池/模式外置）
> 测试 / verify：`mvn -pl services/chaos-controller -am test` → **Tests run: 122, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**（基线 114 + 新增 `DatastoreTargetAllowListTest` 8）；`node --test "code/lab/k6/test/*.test.mjs"` → **41 pass / 0 fail**（基线 37 + 新增 4）；**未跑** `verify.sh`/`smoke.sh`/`console-smoke.sh`/真实 k6/任何 Docker 命令（本步在隔离 worktree 内、且 25 容器栈正被其他 agent 使用，属任务约束）
> 运行状态：**未触碰活体栈**。默认配置（allow-list 为空）与改动前的拒绝行为逐条等价；`code/lab/docker/**`、`code/lab/scripts/**`、`code/lab/dashboard/src/**`、`seckill-app-jdk21` 均未改
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- 把"存储类容器绝对禁止"换成**默认关闭的白名单**：新增 `chaos.datastore.allowed-target-pattern`（env `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`，`DatastoreTargetPolicy`），**空值 = 全拒**，与 24a/24b 期间口径一致；只有显式配置且**整名匹配**的数据存储容器才允许成为故障目标。
- 同一策略在**两处**生效、共用一个类，因此两个判定不可能漂移：`ChaosActionService`（真实边界，403 / `40301`，且在任何 Docker 调用之前拒绝）与 `GuideScenarioLoader`（场景预检，400 / `INVALID_ARGUMENT`）；`code/lab/k6/lib/chaos-schema.js` 逐条镜像，k6 侧新增 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`。
- 新场景 **`code/lab/scenarios/redis-sentinel-failover.json`**（schema v1，三步全带 `explain` 现象/根因/处方/预期曲线）：停当前 HA 主（TTL 45s 自动还原）→ 观察点（幂等 `SIGCONT`，只作为时间线锚点/注解）→ 幂等 `start` 标记旧主以副本回归。执行**完全复用 `ChaosActionService`**（guide 的 `step` 走同一条 `execute()`），因此 `chaos_actions_total` / `chaos_active_actions` / `chaos_reverts_total` / Grafana 注解 / TTL watchdog 全部走既有单一路径，**无第二套实现**。
- 文档：`code/lab/scenarios/README.md` 新增 "Datastore targets (closed by default)" 一节，明确记录本步引入的**不对称**与"场景写死 `jl-redis-1` 是教学简化"——主身份会漂移，操作员**先跑 `code/lab/scripts/sentinel-status.sh` 核对 `get-master-addr-by-name mymaster`/`role=master`**，否则可能停的是副本、演示不出切换；`code/lab/k6/README.md` env 表与 bundled 表同步。
- **本步不含活体验收**（无 Docker、无真实切换演练）。附录给出可直接粘贴执行的 runbook + 验收阈值，交主会话执行。

# 完成功能项

## 1. `DatastoreTargetPolicy`（新增，`chaos/config/`）

- 关键字集合沿用旧规则 `(?i)(mysql|redis|rabbit|mongo|postgres)`（`find`，子串即算数据存储）；白名单用 **`matches()` 整名匹配**（JS 侧相应包成 `^(?:pattern)$`，两端语义一致）。
- `allowedTargetPattern` 为 null/空/纯空白 → `allowed = null` = 关闭：任何数据存储形状的名字一律拒；**非**数据存储名（`jl-app-*`、`jl-nginx`、`jl-sentinel-*`）不受该规则影响，仍由 target pattern / `lab=java-learn` 决定。
- 构造期编译正则，非法即 `IllegalStateException("chaos.datastore.allowed-target-pattern is not a valid regular expression: …")`——与 `chaos.guide.target-pattern` 同样的启动失败策略。
- 拒绝消息**点名是哪条规则拒的**：空值时保留原句 `is a datastore; datastore actions are forbidden` 并追加 `(chaos.datastore.allowed-target-pattern is empty: …)`；已配置但不匹配时 `is a datastore and is refused by chaos.datastore.allowed-target-pattern="<配置值>"`。旧句子原样保留，历史笔记与既有 Java/JS 断言不受影响（未削弱任何既有测试）。

## 2. 两处强制 + 一条豁免

- `ChaosActionService.execute()`：在 `requireLabTarget()`（gateway 的 `lab=java-learn`）之前做纯内存判断，命中即 `ContainerNotAllowedException`（→403 `40301`）并记 `chaos.actions{result="rejected"}`，**不产生任何 Docker 调用**。
- `GuideScenarioLoader`：target pattern 之后追加同一规则（→400），场景在第一步执行前就被整体拒绝；默认 app-only 配置下 `redis-sentinel-failover.json` 属无效文件，被 `list()` 跳过并 WARN（不半加载、不 500）。
- **TTL 自动回滚（`start` / `SIGCONT`）不受该规则约束**（`performRevert()` 不经 `execute()`）：能被打开的故障必须永远能被关闭，即使运维中途把白名单删空。这一点在代码注释、README 与本节都写明了。

## 3. 配置面（工程规约第 1 节）

- `application.yml`：`chaos.datastore.allowed-target-pattern: "${CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN:}"`，注释写明"空=全拒、教学实验室安全阀、非生产便利项"。
- `ChaosProperties.Datastore`（嵌套 properties 类）+ `chaos.guide.target-pattern` 注释更新（数据存储需另外许可）。
- k6：`chaos.js` 读取 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`（init 期即校验 RegExp，非法直接终止），并作为 `validateScenario` 的 `allowedDatastorePattern` 传入。
- 未改 `compose.base.yml`（本步禁改 `code/lab/docker/**`）：该 env 需两行接线，见"待定事项"第 1 条与 runbook 步骤 0。

## 4. `redis-sentinel-failover.json`（schema v1）

| atSeconds | action | target | ttl | 用途 |
|---|---|---|---|---|
| 10 | `kill` | `jl-redis-1` | 45 | 停掉"当前主"（固定名＝教学简化），制造 `+sdown`/`+promoted-slave`/`+switch-master` |
| 25 | `resume` | `jl-redis-2` | — | 观察点：对运行中容器 `SIGCONT` **无副作用**，只在 Grafana 打一条注解当时间锚；运维此时跑 `sentinel-status.sh` 核对新主、两从 `master_link_status=up`、三哨兵同意 |
| 60 | `start` | `jl-redis-1` | — | 幂等（Docker 304 视为完成）标记"旧主以副本回归"；TTL 在 t≈55s 已把它 `start` 过 |

- 观察/标记步选择"幂等动作"而非新增 action：schema v1 的动作白名单（`kill/start/restart/freeze/resume`）与 Java↔JS 手工镜像规则都不该在本步扩张，加 `observe` 语义会同时改 Java、JS、README 三处校验；用既有的幂等 `resume`/`start` 保持单一实现、零 schema 变更。
- 三条 `explain` 覆盖：读 5xx/超时毛刺（现象）、主观下线→仲裁→选主→客户端重挂的时间链 + 异步复制丢写（根因）、免重启/`REDIS_HA_DOWN_AFTER_MS`/`docker kill` 才稳定复现丢写（处方）、恢复秒数与哨兵视图（预期曲线）。

## 5. 新增测试

- `DatastoreTargetAllowListTest`（8）：默认全拒（9 个数据存储名逐个 403、gateway 零调用、rejected 计数）；白名单只放行 `jl-redis-1/2/3`（含 one-shot `start`），`jl-redis`/`jl-mysql`/`jl-rabbitmq`/`jl-redis-1-exporter` 仍拒且消息点名规则；`chaos.guide.target-pattern` 放宽到 `.*`/`^jl-.*$` 也打不开数据存储；allow-list 单独存在也不放宽 guide pattern（两道闸独立）；非法正则使 `ChaosActionService` 与 `GuideScenarioLoader` 构造期即失败；仓库里真实的 `redis-sentinel-failover.json` 默认配置下不出现在列表、HA 配置下通过校验并**逐步可被 `execute()` 执行**（断言 Docker 调用序列）。
- `chaos-schema.test.mjs`（+4）：bundled 场景在"宽 pattern + allow-list"下通过、默认配置下先 `does not match`、仅宽 pattern 时 `is a datastore; …`；空 allow-list 拒绝 7 个数据存储名；只整名放行（`jl-redis-1x`、`jl-redis-1-exporter`、`prefix-jl-redis-1-suffix` 均拒）且消息含规则键与配置值；非法 allow-list 正则报 `not a valid RegExp`。

# 设计逻辑

- **为什么"默认关闭"而不是"默认放行 HA 三节点"**：默认放行等于把 24b 的红线（数据存储容器不作故障目标）永久削弱；一旦某个 dev 环境忘配 `.env`，`jl-redis`/`jl-mysql` 就成了可点按钮。空=全拒让改动前后的默认安全等级完全相同，能力只在明确按演练打开时存在——这正是"教学实验室安全阀"与"生产便利项"的区别。
- **关键字判定在前、白名单在后，两道闸独立**：`target-pattern` 决定"这个时间线允许碰哪些容器名"，allow-list 决定"其中数据存储类是否被特别许可"。任何一道放宽都不放松另一道（测试分别放宽一侧钉住）。
- **一条策略、两个执行点、共享一个类**：action 侧是唯一能真正动 Docker 的边界，guide 侧决定"运维在控制台看到什么"。分两处生效但复用 `DatastoreTargetPolicy`，避免"loader 允许 / service 拒绝"（或反之）这种只能靠运气对齐的双源；Java 与 JS 手工镜像继续逐字对齐（整名匹配语义、消息主句、动作白名单、TTL 规则）。
- **执行面零新增**：`GuideService.step()` → `ChaosActionService.execute()` 已存在，故指标、注解、TTL watchdog、pause/resume 剩余 TTL、`stop-all`、`APPLYING` 门全都在既有单一路径上，本步只加"是否允许"这一条判定。
- **X-Lab-Token / `lab=java-learn` / TTL 上限 / `/lab/**` 鉴权一律未动**（任务红线）；本步只把"数据存储"这一条从硬编码常量变成外置、可审计、启动即校验的开关。
- **场景固定节点 vs 主漂移**：schema v1 的事件是 `(atSeconds, action, target)` 三元组、target 是容器名，无法表达"先查主再决定"。选项是(a)加发现型 action(改三处校验)、(b)驱动端预解析(把编排知识塞进 k6/console)、(c)固定名 + 显式文档 + 只读工具核对。选 (c)：本栈是教学实验室，运维**本来就该**先看哨兵视图再按按钮；同时 README/场景 `description` 里把这条简化写成第一步就看得见的告警。

# 存在缺陷项&TODO

- **评审修订（M3-1c review，2026-10-27，fix/test/docs 三提交）**：
  1. 白名单按"关键字命中输入名"判定，但 Docker 引用可以是**容器 ID/ID 前缀**（如 `POST /chaos/instances/0a1b2c3d/actions`），关键字规则看不见 ID 背后的真实容器。已修复：`DockerContainerGateway.inspectLabContainer` 现回报 inspect 的规范名，`execute()` 在 lab 标签校验后用规范名**二次套用同一 allow-list**（拒绝消息 `container reference "…" resolves to target "…" …`，零 Docker 变更调用）；`DatastoreTargetAllowListTest` 钉住"ID→jl-mysql 拒 / ID→jl-redis-1 放行"。k6 JS 侧是预检、非边界，不改。
  2. Java 侧补"整名匹配"语义测试（不设锚的 `jl-redis-1` 不放行 `jl-redis-10`/`xjl-redis-1`，`matches()` 而非 `find()`），与 JS 的 `^(?:pattern)$` 包裹对齐；上文"没有专门测试"一条在 Java 侧已补齐（正则层面仍无法禁止未锚定写法，靠 README 口径）。
  3. 修 `ChaosActionService` 类注释被本步改坏的半句话（"…max-revert-attempts}. Every attempts. Every Docker call…"）。
  4. 复核确认：**compose 未接线属 Important、部署阻塞项**（下第 2 条），本评审未修（`code/lab/docker/**` 归另一代理所有）；接线前活体默认值=空=全拒，方向安全、只是演示跑不起来。
- **活体验收未做（本步明确不声明任何实测结果）**：无 Docker、无 `verify.sh`/`smoke.sh`、无真实 k6、无真实 failover 观测。所有"预期/阈值"是**待验证**口径，见附录 runbook；主会话执行后请把实际秒数、日志行与截图时间线补进 24d。
- **compose 尚未接线新 env**：`compose.base.yml` 的 `chaos-controller` 段没有 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`（本步禁改 `code/lab/docker/**`）。**没有这两行，容器内该属性恒为空、Sentinel 场景无法驱动**，这是活体验收的第一道阻塞项。
- 场景第一步仍写死 `jl-redis-1`；主漂移后"按原样执行"会停到副本（README 与 `description` 已告警，但没有机器校验）。可选后续：驱动端读 `sentinel-status.sh` 输出替换 target（需新机制，勿加 action）。
- 观察点用幂等 `resume`/`start` 表达，语义上是"锚点/注解"而非真正的观测动作；Grafana 上会出现 `chaos resume on jl-redis-2` 这类事件，讲解时需说明它不是故障。若后续引入 `observe`/`no-op` action，Java+JS+README 三处校验必须同步（含 `ALLOWED_ACTIONS`）。
- `jl-redis-*-exporter` 因含 `redis` 关键字被拒——依赖 allow-list 写 `^jl-redis-[0-9]+$` 不匹配它；若有人把 allow-list 写成 `jl-redis`（不带锚点与序号），exporter 会一并放行。README 已强调**整名锚定**，但没有专门测试禁止"未锚定 pattern"（无法在正则层面禁止）。
- 允许 HA 主被 `kill` 后，`stop-all` 与 TTL 都能把它 `start` 回来，但**旧主回归后是否一定成为副本**取决于 Redis 自身持久化的 `replicaof` 配置（24a 的 `--replicaof` 命令行只在启动时注入）。runbook 第 7 步要求人工核对 `role=slave`；若发现它自认 master（双主），本步没有自动纠正路径。
- 场景在默认配置下被 `list()` 静默跳过（仅 WARN 日志），控制台只表现为"少一个场景"。对运维不友好，属 #50/控制台层可观测项。
- 24b 遗留不变：L1 Caffeine 60s 常量 TTL 仍会掩盖 L2 切换、`jl-redis-*-exporter` 仍未被 Prometheus 抓取、哨兵全组宕机下的 sentinel 模式活体降级仍未演练。

# 待定事项和注意事项

1. **主会话需补的 compose 两行**（`code/lab/docker/compose.base.yml` 的 `chaos-controller.environment`）：
   `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN: ${CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN:-}`
   并在 `code/lab/docker/.env.example` 记录默认空＝全拒、Sentinel 演练示例值 `^jl-redis-[0-9]+$`，同时提醒 `CHAOS_GUIDE_TARGET_PATTERN` 需放宽到 `^jl-(app|redis|sentinel)-[0-9]+$`。
2. **口径**：本步＝"代码 + 场景 + 文档"，#49 的活体切换与曲线证据另计（建议 24d）。
3. **不要**把 allow-list 放宽成含 `jl-mysql` / `jl-rabbitmq` / 单机 `jl-redis` / 观测栈的正则；不要把 `CHAOS_GUIDE_TARGET_PATTERN` 改成 `.*` 交付。
4. allow-list 变更后需 `docker compose up -d --force-recreate chaos-controller`（Spring 属性，不重启不生效）；场景 JSON 是只读挂载，改完即被下次 `GET /lab/guide/scenarios` 重扫。
5. 演练前后各跑一次 `GET /chaos/actions`（期望空）与 `POST /chaos/stop-all`；`LAB_RESET_MODE` 保持 lab 默认 `manual`，否则 guide 完成即自动回滚、看不到切换后的状态。

## 给主会话的活体验收 runbook（可直接粘贴）

```bash
cd /d/000CODE/JAVA-LEARN            # 合并本 worktree 的 3 个提交之后执行

# 0) 前置：compose 接线（缺失则第 4 步必然 403）
#    code/lab/docker/compose.base.yml → chaos-controller.environment 增一行
#      CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN: ${CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN:-}
#    code/lab/docker/.env 增两行（本演练用；演练后请删除第 2 行以回到默认）
#      CHAOS_GUIDE_TARGET_PATTERN=^jl-(app|redis|sentinel)-[0-9]+$
#      CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN=^jl-redis-[0-9]+$

# 1) 构建控制器镜像（只重建 controller，不动数据容器）
docker compose --project-name java-learn-lab --project-directory code/lab/docker \
  --env-file code/lab/docker/.env -f code/lab/docker/compose.base.yml \
  build chaos-controller

# 2) 进入 ha 拓扑（25 容器：含 jl-redis-1/2/3 + jl-sentinel-1/2/3，app 切 sentinel 模式）
bash code/lab/scripts/up.sh --profile ha
docker logs jl-app-1 2>&1 | grep -E 'Redis access mode|discovered master'
#   期望：Redis access mode: sentinel
#         Sentinel discovered master 'mymaster' at 172.28.190.x:6379

# 3) 先核对"谁是主"（场景第 1 步写死 jl-redis-1，不符就改场景 target 或改用真正的主）
bash code/lab/scripts/sentinel-status.sh
docker exec jl-sentinel-1 redis-cli -p 26379 SENTINEL get-master-addr-by-name mymaster
# 期望一个 IP（对应 jl-redis-<n>）。同时把它开着：
bash code/lab/scripts/sentinel-status.sh --watch 2 -n 30 &
TOKEN="$(grep -m1 '^LAB_TOKEN=' code/lab/docker/.env | cut -d= -f2-)"

# 4) 负面用例：allow-list 之外仍必须拒绝（默认空值时同样拒绝，用于回归）
curl -s -o /tmp/refuse.json -w '%{http_code}\n' -X POST \
  http://127.0.0.1:8080/lab-api/chaos/instances/jl-redis/actions \
  -H "X-Lab-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"action":"kill","ttlSeconds":30}'
#   期望 403 且 {"code":"40301",…,"message":"…is a datastore and is refused by chaos.datastore.allowed-target-pattern=…"}
for t in jl-mysql jl-rabbitmq jl-redis-1-exporter jl-nginx; do
  curl -s -o /dev/null -w "$t %{http_code}\n" -X POST \
    "http://127.0.0.1:8080/lab-api/chaos/instances/$t/actions" \
    -H "X-Lab-Token: $TOKEN" -H 'Content-Type: application/json' -d '{"action":"kill","ttlSeconds":30}'
done
#   期望：非 lab 容器 404；lab 内数据存储 403（jl-nginx 属应用层之外、无 token 时应为 401/403 族）
curl -s -o /dev/null -w 'no-token %{http_code}\n' -X POST \
  http://127.0.0.1:8080/lab-api/chaos/stop-all          # 期望 401/403：TokenFilter 未变
# 负面用例（场景层）：allow-list 为空时重启 controller，GET /lab/guide/scenarios 不应含
# redis-sentinel-failover，POST /lab/guide/start {redis-sentinel-failover} 应 400 INVALID_ARGUMENT

# 5) 通过 /lab/guide/** 驱动切换（控制台同一状态机）
curl -s -X POST http://127.0.0.1:8080/lab-api/lab/guide/start \
  -H "X-Lab-Token: $TOKEN" -H 'Content-Type: application/json' \
  -d '{"scenarioId":"redis-sentinel-failover"}' | tee /tmp/g1.json
#   期望 status=running、totalSteps=3、步骤里出现 jl-redis-1 / jl-redis-2 与 现象/根因/处方/预期曲线
curl -s -X POST http://127.0.0.1:8080/lab-api/lab/guide/step -H "X-Lab-Token: $TOKEN" >/dev/null  # kill jl-redis-1
# 6) 读恢复计时（L1 会掩盖 L2，轮换商品 id 或等 60s；见 24b 遗留）
T0=$(date +%s)
until [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "http://127.0.0.1:8080/api/products/$(( (RANDOM % 20) + 1 ))")" = 200 ]; do
  [ $(( $(date +%s) - T0 )) -gt 30 ] && { echo "超过 30s 未恢复"; break; }
  sleep 0.5
done
echo "recover_seconds=$(( $(date +%s) - T0 ))"
curl -s -X POST http://127.0.0.1:8080/lab-api/lab/guide/step -H "X-Lab-Token: $TOKEN" >/dev/null  # resume jl-redis-2（观察点）
docker logs jl-app-1 2>&1 | grep -E 'has been changed to|Reconnected to' | tail -4
curl -s -X POST http://127.0.0.1:8080/lab-api/lab/guide/step -H "X-Lab-Token: $TOKEN" >/dev/null  # start jl-redis-1（幂等）
curl -s http://127.0.0.1:8080/lab-api/lab/guide/state -H "X-Lab-Token: $TOKEN"
#   期望 status=completed、currentStep=3、activeActions=0
#   （reset-mode=manual 时第 1 步的 TTL 45s 已在 t≈55s 自行 start 回旧主，勿手动 stop 场景）
#   要验证 pause/resume：多步之间 POST /lab/guide/pause → GET /chaos/actions 看 remainingTtl 冻结 → /lab/guide/resume

# 7) 副本回归与哨兵视图
docker exec jl-redis-1 redis-cli INFO replication | grep -E 'role|master_host|master_link_status'
bash code/lab/scripts/sentinel-status.sh
#   期望：恰好 1 个 role=master；jl-redis-1 role=slave 且 master_link_status=up；另两从 up；三哨兵 quorum 一致

# 8) k6 时间线路径（同一 JSON、同一 controller）
LAB_TOKEN="$TOKEN" CHAOS_SCENARIO_FILE=redis-sentinel-failover.json \
CHAOS_TARGET_PATTERN='^jl-(app|redis|sentinel)-[0-9]+$' \
CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-redis-[0-9]+$' \
CHAOS_DURATION=2m CHAOS_VUS=6 bash code/lab/k6/run.sh ha-failover
#   期望：无 [k6/chaos] 校验错误；chaos_events{result="accepted"} 计数=3；teardown 后 GET /chaos/actions 为空
#   同一 env 若去掉 CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN，init 期即报 is a datastore; … 并终止（不产生任何动作）

# 9) 指标/注解同一路径
curl -s http://127.0.0.1:8080/lab-api/metrics | grep -E 'chaos_actions_total|chaos_active_actions|chaos_reverts_total'
# Grafana → Annotations：chaos/java-learn/jl-redis-1/kill、…/jl-redis-2/resume、reverted 各一条

# 10) 收尾（回到默认安全态）
curl -s -X POST http://127.0.0.1:8080/lab-api/chaos/stop-all -H "X-Lab-Token: $TOKEN"
#   期望 {"data":{"reverted":0,"failed":0,"pending":0}} 或 reverted≥1 后 GET /chaos/actions 为空、无 frozen（isPaused=false）
#   删除 .env 里两行白名单并 --force-recreate chaos-controller，回到"空=全拒"
#   需要还原 standalone 时按 24b 的 compose up -d app-1 app-2 app-3（不带 ha 文件）
```

**验收阈值（未实测，待主会话填数）**：
1. Nginx 商品读在停主后恢复 200 的耗时 **≤15s**（24b 基线 ≈9.5s；首次失败到恢复 ≈7.2s），**且全程无需重启应用容器**（`docker ps` 显示 app 三实例 `Up …` 未重建，日志有 `has been changed to` / `Reconnected to`）。
2. TTL 自动回滚在 t≈55s 把被停节点 `start` 回来，且它以 `role=slave`、`master_link_status=up` 重新加入（不是双主）。
3. guide 到 `COMPLETED`；`POST /chaos/stop-all` 后 `GET /chaos/actions` 为空、无冻结中的 action（`/lab/guide/state.activeActions=0`、服务未处于 paused）。
4. 白名单之外的数据存储目标仍返回拒绝：action 侧 403 `40301`、场景侧 400 `INVALID_ARGUMENT`，消息点明 `chaos.datastore.allowed-target-pattern`；`jl-redis`（单机）、`jl-mysql`、`jl-rabbitmq`、exporter、非 lab 容器与无 token 请求的结论与改动前一致。
5. 指标只来自一条路径：`chaos_actions_total` / `chaos_active_actions` / `chaos_reverts_total` 与 Grafana 注解条数与场景步数吻合（无第二实现产生的重复计数）。

# 附录

## 关键文件

- 主代码：`code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/config/DatastoreTargetPolicy.java`（新）、`.../config/ChaosProperties.java`（`chaos.datastore.*`）、`.../action/ChaosActionService.java`（execute 前置拒绝）、`.../guide/GuideScenarioLoader.java`（场景预检）、`.../src/main/resources/application.yml`
- 场景/文档：`code/lab/scenarios/redis-sentinel-failover.json`（新）、`code/lab/scenarios/README.md`（白名单一节 + 主漂移简化说明）、`code/lab/k6/README.md`（env 与 bundled 表）
- k6：`code/lab/k6/lib/chaos-schema.js`（镜像规则 + 整名匹配）、`code/lab/k6/lib/chaos.js`（`CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`）
- 测试：`code/services/chaos-controller/src/test/java/com/javalearn/lab/chaos/DatastoreTargetAllowListTest.java`、`code/lab/k6/test/chaos-schema.test.mjs`
- 只读诊断（未改）：`code/lab/scripts/sentinel-status.sh`

## 复现命令（本步实际执行）

```bash
cd /d/000CODE/JAVA-LEARN/.qoder/worktrees/agent-general-purpose-96cc8572
export JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1"
(cd code && mvn -pl services/chaos-controller -am test)                       # -am 必带
node --test "code/lab/k6/test/*.test.mjs"                                      # 必须用 glob 形式
```
