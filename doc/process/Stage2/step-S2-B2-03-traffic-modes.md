# 步骤交接：S2-B2-03a k6 流量模式（`via-nginx` / `target-instance`）与受控定向归因

> 任务/步骤：Stage2 B2-03a —— runner 侧流量模式选择、固定目标表、`BASE_URL` 按模式解析、k6 请求级归因标签、执行台账字段
> 日期：2026-10-28
> 分支 / HEAD：`feature/stage2-workbench` 的基线 `a95dadc`，隔离 worktree 自检通过（`git rev-parse --show-toplevel` ≠ 主仓库根）
> 关联任务：`stage2-开发测试与验收计划.md`「### B2-03」；`doc/design/Stage2/stage2改造方案.md` §10.6（流量归因与公平对比）；工程规约 §1（配置外置）
> 测试：chaos-controller `Tests run: 603, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（基线 591 → 本步 +12）；k6 node `# tests 105 / # pass 105 / # fail 0`（基线 100 → +5）
> 运行状态：**只跑单测与静态接线核对**；未执行任何 docker/compose 生命周期命令，未跑真机 A17/A06
> 范围边界：本步只做后端与 k6 侧；UI 展示归 B2-03b/主会话，真机交叉核对归主会话

# 当前进展

- `BenchLaunchPlanner` 接受两个**受控选择键** `TRAFFIC_MODE`（默认 `via-nginx`）与 `TARGET_INSTANCE`。它们不是任意 k6 参数：模式只接受闭集枚举，目标只接受固定表内的 Docker 服务名；`BASE_URL` 由模式解析，请求仍不可覆盖，也不存在 URL/host/port 请求字段。
- `via-nginx`：`BASE_URL = LAB_BENCH_EXEC_NGINX_BASE_URL`（默认 `http://nginx`），k6 侧实例标签为 `nginx`；若同时显式传了非空 `TARGET_INSTANCE` 直接拒绝，避免"以为定向了其实没定向"。
- `target-instance`：必须给 `TARGET_INSTANCE` 且命中固定表；`BASE_URL = http://<target>:<port>`，端口取 `LAB_BENCH_EXEC_TARGET_PORT`（默认 `8080`）；`bypassNginx=true`。
- `BenchLaunchPlan` 新增 `traffic` 子 record（`mode/targetInstance/baseUrl/bypassNginx/mixed/upstream`）；容器标签新增 `lab.traffic-mode`、`lab.target-instance`；env 强制写入 `TRAFFIC_MODE`、`TARGET_INSTANCE`。
- k6：`lib/config.js` 的 `config.traffic` 增加 `mode` / `targetInstance`（保留原 `vus`/`duration`），模式枚举与目标格式在 init 期校验；`lib/http.js` 每个请求 tags 增加 `instance`、`traffic_mode`，且 `opts.tags` 无法覆盖这两个归因键。
- 执行/台账：`BenchExecutionState` 增加 `trafficMode`/`targetInstance`（从容器标签读取，ABSENT 或 B2-03a 前创建的容器为 `null`），`GET /lab/runs/{id}/k6` 自然返回；`BenchRunLifecycleHook.launch` 的 details 增加 `k6TrafficMode`/`k6TargetInstance`/`k6BypassNginx`/`k6TrafficMixed`/`k6Upstream`。
- 配置外置：`LAB_BENCH_EXEC_NGINX_BASE_URL`、`LAB_BENCH_EXEC_TARGET_INSTANCES`、`LAB_BENCH_EXEC_TARGET_PORT` 在 `application.yml` / `compose.base.yml` / `.env.example` 三处默认一致；旧 `LAB_BENCH_EXEC_BASE_URL`（`base-url`）**退役**，由 `LAB_BENCH_EXEC_NGINX_BASE_URL` 承接同一默认值。
- 最新代码提交：`dda2051`（planner + k6 + 执行层）、`b0a1b11`（配置外置）；本文件为第三笔 docs 提交。

# 完成功能项

## 1. 模式矩阵

| `TRAFFIC_MODE` | `TARGET_INSTANCE` | 解析后的 `BASE_URL` | k6 请求标签 `instance` | 容器标签 `lab.target-instance` | `bypassNginx` | `trafficMixed` |
|---|---|---|---|---|---|---|
| 省略（默认 `via-nginx`） | 省略 | `LAB_BENCH_EXEC_NGINX_BASE_URL`（默认 `http://nginx`） | `nginx` | `nginx` | `false` | `false` |
| `via-nginx` | 省略或空白 | 同上 | `nginx` | `nginx` | `false` | `false` |
| `via-nginx` | 非空（如 `app-2`） | — 拒绝（400/40002） | — | — | — | — |
| `target-instance` | `app-2`（表内） | `http://app-2:8080` | `app-2` | `app-2` | `true` | `false` |
| `target-instance` | 省略/空白 | — 拒绝 | — | — | — | — |
| `target-instance` | 表外值/URL/`app-2:8080` | — 拒绝 | — | — | — | — |

`TRAFFIC_MODE` 与 `TARGET_INSTANCE` 都会被强制写进容器 env（k6 用来打标签），即使场景 manifest 的 `env` 列表没有声明它们。

## 2. 固定目标表与 `BASE_URL` 解析

- 表来源：`LAB_BENCH_EXEC_TARGET_INSTANCES`（默认 `app-1,app-2,app-3`），逗号分隔、去空白、去重保序。
- 每个条目必须匹配 `[a-z][a-z0-9-]{0,62}`（纯 Docker 服务名）：包含 `://`、`:`、`/`、`.`、大写或空白的条目 → **503/50303**（配置问题，不是请求问题）。
- 端口：`LAB_BENCH_EXEC_TARGET_PORT`（默认 `8080`），只允许 `1..65535`，越界 → 503/50303。
- `target-instance` 的 `BASE_URL` 由 planner 自己拼 `http://<service>:<port>`；请求里没有 `TARGET_URL`/`TARGET_HOST`/`TARGET_PORT` 之类的键（未知参数直接拒绝）。
- `via-nginx` 的 `BASE_URL` 取 `lab.bench.exec.nginx-base-url`，必须匹配 `https?://\S+`，否则 503/50303。

## 3. 拒绝矩阵（`BenchLaunchPlannerTest` 逐条钉住）

| 请求 | 结果 |
|---|---|
| 省略两个键 | 默认 `via-nginx`，`BASE_URL=http://nginx` |
| `TRAFFIC_MODE=direct` / `VIA-NGINX` / 空串 | 400 拒绝：`unknown traffic mode` |
| `TRAFFIC_MODE=1`、`TARGET_INSTANCE=2` | 400 拒绝：`must be a string` |
| 值含换行/控制字符 | 400 拒绝：`control characters` |
| `via-nginx` + `TARGET_INSTANCE=app-2` | 400 拒绝：`must be omitted` |
| `target-instance` 缺 `TARGET_INSTANCE` | 400 拒绝：`requires TARGET_INSTANCE` |
| `target-instance` + `app-4`（表外） | 400 拒绝：`unknown target instance` |
| `target-instance` + `app-2:8080` 或 `http://app-2:8080` | 400 拒绝：`unknown target instance` |
| `TARGET_URL` / `TARGET_PORT` 参数 | 400 拒绝：`unknown parameter` |
| `BASE_URL=http://evil`（任何模式） | 400 拒绝：`forced` |
| 固定表含 `http://...`、`../etc`、空表 | 503：`LAB_BENCH_EXEC_TARGET_INSTANCES` 配置错误 |
| `target-port=70000` | 503：`is not a valid TCP port` |
| `nginx-base-url=nginx:8080` | 503：`must be an absolute http(s) URL` |

拒绝分类沿用既有语义：请求值问题 → `BenchLaunchRejectedException`（400/40002）；配置/可用性问题 → `BenchUnavailableException`（503/50303）。

## 4. 标签、env 与台账字段

| 位置 | 字段 | 取值 |
|---|---|---|
| 容器 env | `TRAFFIC_MODE` / `TARGET_INSTANCE` | planner 强制写入，覆盖任何场景声明 |
| 容器 env | `BASE_URL` | 按模式解析（仍不可由请求覆盖） |
| 容器标签 | `lab.traffic-mode` / `lab.target-instance` | 模式与实例（`via-nginx` 时实例为 `nginx`） |
| `BenchLaunchPlan.traffic` | `mode/targetInstance/baseUrl/bypassNginx/mixed/upstream` | 计划期快照 |
| `GET /lab/runs/{id}/k6` | `trafficMode` / `targetInstance` | 从容器标签读；`ABSENT` 或 B2-03a 前容器为 `null` |
| start operation `afterSummary` | `k6TrafficMode` / `k6TargetInstance` / `k6BypassNginx` / `k6TrafficMixed` / `k6Upstream` | 记录本次 run 的路由事实 |
| k6 HTTP 指标 | `instance` / `traffic_mode`（+ 既有 `run_id`/`flow`） | 每请求标签；`opts.tags` 不能覆盖前两者 |

`k6Upstream` 形如 `app-1:8080=1,app-2:8080=1,app-3:8080=1`，是**镜像记录**而非实时探测：它由固定表 + `target-port` 推导，对应仓库内 `code/lab/docker/nginx/conf.d/default.conf` 的 `upstream seckill_backend`（三个服务等权 round-robin，`max_fails=2 fail_timeout=5s`）。改表或改端口时必须同步改 Nginx 配置，否则该字段会与实际镜像不一致。

## 5. `traffic_mixed=false` 口径

run 预检查会要求背景 `jl-traffic` 停止，因此受控 run 的 `trafficMixed` **恒为 false**，并作为事实写入计划与 operation details。本步**没有**实现混合流量自动检测：若操作员绕过预检查另行制造流量，台账不会自动翻成 true。文档与报告不得声称"已自动检测/保证无混合流量"——它只是"预检查通过 + 本执行层只启动一个 k6 容器"这一受控前提的记录。

## 6. k6 侧归因

- `lib/config.js`：`config.traffic.mode`（默认 `via-nginx`，仅接受 `via-nginx`/`target-instance`）、`config.traffic.targetInstance`（默认 `nginx`，非空且匹配 `[A-Za-z0-9][A-Za-z0-9._-]*`）。非法值在 init 期抛错，任何场景都不会带着错误归因启动。
- `lib/http.js`：`call()` 的请求 tags 固定包含 `run_id`、`flow`、`instance`、`traffic_mode`。`opts.tags` 仍可追加自定义标签，但 `instance`/`traffic_mode` 在合并后被重新写回归因值，场景无法伪造。
- 未改场景业务逻辑，`BASE_URL` 仍完全由 runner 注入；`config.traffic.vus/duration` 原语义不变。

## 7. 配置键（三处默认一致）

| yml 键 | env 键 | 默认值 | 用途 |
|---|---|---|---|
| `lab.bench.exec.nginx-base-url` | `LAB_BENCH_EXEC_NGINX_BASE_URL` | `http://nginx` | `via-nginx` 的 `BASE_URL` |
| `lab.bench.exec.target-instances` | `LAB_BENCH_EXEC_TARGET_INSTANCES` | `app-1,app-2,app-3` | 固定目标表（仅服务名） |
| `lab.bench.exec.target-port` | `LAB_BENCH_EXEC_TARGET_PORT` | `8080` | 直连端口 |

三处落点：`code/services/chaos-controller/src/main/resources/application.yml`、`code/lab/docker/compose.base.yml`、`code/lab/docker/.env.example`；三者 CRLF 保持，Python 字节级替换完成（无混入 LF）。`code/lab/scripts/test-chaos-env-wiring.sh` 自动发现 `LAB_BENCH_*`，实测 **PASS**（8 chaos + 85 其它键）。

## 8. 单测

| 套件 | 结果 | 新增覆盖 |
|---|---|---|
| chaos-controller（`mvn -f code/pom.xml -pl services/chaos-controller -am test`） | **603 / 0 fail / 0 error / 0 skip**，BUILD SUCCESS | 默认 via-nginx 的 env/标签；target-instance app-2 的 `BASE_URL`/bypass/标签；未知模式/未知 target/via-nginx 带 target/URL 与端口注入/`BASE_URL` 覆盖；固定表与端口可配置且表外仍拒绝；配置错误 503；gateway 标签读取（mock docker-java，不启 Docker）；state 标签透出与 ABSENT=null；hook 的 5 个 details |
| k6 node（`node --test "code/lab/k6/test/*.test.mjs"`） | **105 tests / 105 pass / 0 fail** | config 默认值、env 覆盖、非法模式、空白/URL 目标；http 请求 tags 含 `instance`/`traffic_mode` 且不可伪造 |

# 设计逻辑

- **为什么是"受控选择键"而不是 k6 参数**：`TRAFFIC_MODE`/`TARGET_INSTANCE` 直接决定网络目标与归因，若走场景参数白名单，manifest 一改就可能放行任意值；因此 planner 用独立闭集/表校验，命中后强制回写 env，请求永远拿不到"原样透传"通道。
- **为什么 `via-nginx` 拒绝显式 target**：`via-nginx` 时 `BASE_URL` 指向 Nginx，`TARGET_INSTANCE` 只影响标签；若默默接受 `app-2`，台账会出现"定向 app-2"的假象。显式拒绝迫使调用方选择真正的 `target-instance` 模式。
- **为什么 400 与 503 分开**：表/端口/Nginx 入口是部署配置，坏了应该让所有 run 明确 503 而不是让每个请求看起来"参数写错了"。
- **为什么 upstream 从固定表推导**：§10.6 要求每次 run 记录 upstream 权重/路由规则；仓库内 Nginx 配置是三个等权服务（权重 1），表也是这三个服务，推导值可复现且无需运行时抓取 Nginx 配置。代价是"改表必须同步改 Nginx"，已在字段文档中写明。
- **为什么 k6 侧"重写"而不是"抛错"**：场景传 `opts.tags` 是正常能力；归因键被静默纠正比在压测中途抛错更安全，同时用测试钉住"伪造无效"。
- **为什么保留 `null` 而不是猜默认**：B2-03a 前创建的容器没有新标签；把 `null` 显示为 `unknown` 比回填 `via-nginx` 更诚实（老容器可能来自任意入口）。

# 存在缺陷项&TODO

### 未验证项（本步明确不做，归主会话 / B2-03b）

- **A17 真机验收**：`target-instance` 定向到 `app-2` 后"其余实例请求数≈0"、Nginx upstream 计数与应用侧计数交叉核对，均未跑真机。定向模式绕开 Nginx 后，Nginx 侧总量与 `seckill-app` 侧总量本就不应相等，交叉核对的口径需要主会话在真栈上定稿。
- **UI 展示**：执行记录/基准面板尚未展示 `trafficMode`/`targetInstance`/`bypassNginx`/`trafficMixed`/`k6Upstream`，`GET /lab/runs/{id}/k6` 的 JSON 已就绪，接线归 B2-03b。
- **混合流量检测**：`traffic_mixed` 恒 false，仅代表预检查前提；无独立检测器。

### 已知边界

- 旧键 `LAB_BENCH_EXEC_BASE_URL` 退役：已有 `.env` 若仍设置它，会被忽略（不再生效）；新键默认值相同，未显式覆盖的环境行为不变。B2-02 交接文档的配置表已加指针（历史表格未重写）。
- 直接 `run.sh` 手工运行 k6 且不注入 `TRAFFIC_MODE`/`TARGET_INSTANCE` 时，标签退化为 `via-nginx`/`nginx`；这是 CLI 默认口径，不代表受控 run 的台账字段。
- `baseOptions.tags` 仍只含 `run_id`：`http_req_*`/`http_reqs` 带实例归因，自定义 `flow_errors` 等指标不带；按实例聚合请用 HTTP 指标，避免把自定义指标当 per-instance 数据。
- `k6Upstream` 的端口复用 `target-port`（默认 8080）；若只改 `target-port` 而不改 Nginx upstream 端口，该记录会与镜像配置不一致（受控直连本身仍正确）。

# 待定事项和注意事项

1. **改表/改端口 = 改 Nginx**：`LAB_BENCH_EXEC_TARGET_INSTANCES`/`TARGET_PORT` 与 `code/lab/docker/nginx/conf.d/default.conf` 的 `upstream seckill_backend` 必须同步；`k6Upstream` 只是镜像记录。
2. **真机顺序建议**：先 `via-nginx` 跑一次 `smoke` 确认三项新标签出现在容器与 `GET /lab/runs/{id}/k6`；再 `target-instance app-2` 跑 `load`，用 Prometheus 按 `instance="app-2"` 看请求数，确认 app-1/app-3 无新增；最后做 Nginx 与应用侧计数交叉核对并记录口径。
3. **报告口径**：定向 run 的实例级 P95 只来自目标实例；`traffic_mixed=false` 是预检查前提的记录，不是自动检测结论；报告不得把 Nginx 总量与 app 侧总量差异解读为丢请求。
4. **老容器兼容**：B2-03a 前创建的 k6 容器 `trafficMode`/`targetInstance` 为 `null`；UI 应显示"未记录"而不是默认值。

## 最新 commit

- `dda2051` `feat(B2-03): add controlled traffic modes and per-instance attribution`
- `b0a1b11` `chore(B2-03): externalize the nginx entry and target-instance table`
- docs（本文件）提交见 `git log -- doc/process/Stage2/step-S2-B2-03-traffic-modes.md`
