> 步骤交接 · Step 44 线程实验室控制台 + chaos-controller 窄代理
> 日期：2026-10-27
> 分支 / HEAD：`feature/ha-lab`（主工作树 `D:\000CODE\JAVA-LEARN`）/ 本次提交 `feat: proxy the app thread lab to the console and add the thread page`、`test: cover the thread proxy allow-list and extend the console smoke`、`docs: record the thread console handover (step-44)`
> 关联任务：M5 线程实验室（设计 §9.1/§9.2/§9.3、§12.1 `/thread`、§12.2 交互约定）；上游 step-32（故障注入）、step-34（生命周期）、step-35（快照）、step-36（D6 面板）
> 验证：`cd code && JAVA_HOME=jdk-21.0.12.1 mvn -pl services/chaos-controller -am test` → **244 tests, 0 failures / 0 errors / 0 skipped / BUILD SUCCESS**（基线 191，本步新增 53）；`npm run build`（`code/lab/dashboard`）→ **成功**；`bash code/lab/scripts/console-smoke.sh` → **30 PASS / 0 FAIL**（基线 26 + 新增 4；M6-1 动画视图落地后为 **37 PASS**，见 `step-46-animation-views.md` §6）；三个实例 `GET /lab/thread/state` 经代理均为空数组
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 1. 代理契约（proxy contract）

Nginx 仍**不**代理应用 `/lab/**`（`code/lab/docker/nginx/conf.d/default.conf` 未改）。控制台经 `/lab-api/` 到达 chaos-controller，再由本步新增的窄代理转发到单个应用实例：

| 控制台调用（经 Nginx `/lab-api/`） | chaos-controller 实际映射 | 转发上游 |
|---|---|---|
| `GET /chaos/app/{instance}/lab/thread/state` | `AppProxyController#get` | `GET http://{instance}:8080/lab/thread/state` |
| `GET .../lab/thread/lifecycle` | 同上 | `GET .../lab/thread/lifecycle` |
| `POST .../lab/thread/fault` | `AppProxyController#post` | `POST .../lab/thread/fault`（仅此路径转发 body） |
| `POST .../lab/thread/fault/{mode}/{stop,pause,resume}` | 同上 | 原样转发；`mode` 只允许七种 |
| `POST .../lab/thread/stop-all` | 同上 | 不转发 body |
| `POST .../lab/thread/lifecycle/{start,step,freeze,resume,reset}` | 同上 | 不转发 body |
| `POST .../lab/thread/snapshot` | 同上 | 不转发 body |

- **实例白名单**：`chaos.app-proxy.instances`，默认 `app-1,app-2,app-3`（env `CHAOS_APP_PROXY_INSTANCES`）。启动时 `ChaosProperties.AppProxy#validate()` 校验名字格式、重复项、scheme/port/超时/body 上限；未知实例 → **404 / 40401**（不暴露拓扑）。
- **路径白名单**（`AppProxyService`，锚定正则 + 显式集合）：`/lab/thread/fault` 只接受 `POST`；`fault/{mode}/{action}` 的 `mode` 只允许 `deadlock|stuck|cpu-burn|heap-leak|oom|thread-leak|starvation`，`action` 只允许 `stop|pause|resume`；`GET` 只允许 `state` 与 `lifecycle`；其余 `POST` 仅 `stop-all`、五个 lifecycle 动作和 `snapshot`。任何其他路径（包括 `/lab/toggles`、`/lab/thread/../toggles`、尾部多一段、双斜杠、带查询串）→ **404 / 40401**。
- **方法限制**：控制器只映射 `GET` 与 `POST`；`DELETE/PUT/PATCH` → **405 / 40501**。
- **请求体**：只有 `POST /lab/thread/fault` 会把原始 JSON 字节转发给上游；上限 `chaos.app-proxy.max-request-bytes`（默认 8192，env `CHAOS_APP_PROXY_MAX_REQUEST_BYTES`），超限 → **400 / 40001**，控制器最多只缓冲 `limit+1` 字节。
- **令牌**：调用方的 `X-Lab-Token` 由既有 `TokenFilter` 校验；转发时由 `HttpAppProxyGateway` 注入 **controller 自己的** `lab.token`，不复制调用方请求头，不写日志、不回显、不放入错误信息。
- **超时外置**：`chaos.app-proxy.connect-timeout-seconds`（默认 2s）与 `request-timeout-seconds`（默认 10s）。
- **错误映射**：

| 情况 | HTTP / code | 说明 |
|---|---|---|
| 未知实例 | 404 / 40401 | `UnknownAppInstanceException` |
| 方法+路径不在白名单 | 404 / 40401 | `AppPathNotAllowedException`（deny-by-default） |
| 实例不可达/连接超时/中断 | 503 / 50301 | `AppUnreachableException`，消息含实例与目标 URL，不含令牌 |
| 上游非 JSON / 空 body | 502 / 50201 | `AppResponseNotJsonException`，消息含 HTTP 状态、content-type 与前 120 字符 |
| 上游 2xx/4xx/5xx 且为 JSON | 原状态原 body | `Result` 信封逐字回传（如 409/40901 冲突） |

实现文件：`appproxy/{AppProxyGateway,HttpAppProxyGateway,AppProxyService,ProxyResponse}.java`、`web/AppProxyController.java`、`error/{UnknownAppInstanceException,AppPathNotAllowedException,AppUnreachableException,AppResponseNotJsonException,InvalidAppProxyRequestException}.java`、`config/ChaosProperties.java`（`AppProxy`）、`config/ChaosConfig.java`（两个 bean）、`application.yml`（`chaos.app-proxy.*`）。

# 2. 页面行为（thread page）

顶部新增视图导航（`data-testid="nav-console"` / `"nav-thread"`），默认仍是「实验操作台」，所以既有向导/流量/日志行为与冒烟断言不受影响。线程页沿用现有卡片、pill、badge、mono 字体的视觉语言，未改版式。

- **实例选择**：`app-1`…`app-3`（`VITE_LAB_APP_INSTANCES` 可覆盖显示列表；最终仍以服务端白名单为准），切换即重新拉取。
- **注入**：模式 7 选 1、hold `auto|manual`、TTL（1–600，manual 时禁用）、`thread-leak` 时显示虚拟线程开关；请求体只带 `mode`、`hold`，auto 时带 `ttlSeconds`，thread-leak 时带 `virtual`。同模式重复注入由上游返回 409/40901，页面原样显示。
- **当前故障**：每个 mode 一行，显示 state（ACTIVE/PAUSED）、hold、剩余 TTL（manual 显示「manual 保持」）、开始时间、参与线程 id；行内「暂停/继续/停止」，卡片头「全部停止」。`pause` 只冻结 TTL，页面文案明确这一点。
- **生命周期演示**：六态 chip 轨道 `NEW → RUNNABLE → BLOCKED → WAITING → TIMED_WAITING → TERMINATED`，以 `stateCode` 高亮当前态、已过态变暗；显示 `step/maxSteps`、frozen、线程名与 id、alive、machineStage。按钮 `start / step / freeze / resume / reset` 的可用性与服务端契约一致（未启动不能 step/freeze；仅 frozen 能 resume；reset 任意已启动态可用）。
- **快照**：`抓取快照` 调 `POST /lab/thread/snapshot`；结果区显示 `captured=true/false`、trigger、耗时、文件数、目录与错误文本。`capture=false` 也按 200 处理，不当作请求失败。
- **错误**：页面独立 `data-testid="thread-error"` banner，`describeError` 从 `api.js` 复用（新增 502 文案）。应用实例的 401/403/404/409/502/503 均按既有格式呈现。

文件：`src/components/ThreadLab.vue`（新）、`src/App.vue`（导航/视图切换）、`src/api.js`（7 个线程 API + `describeError`）、`src/style.css`（导航与线程页样式）。

# 3. 轮询与 busy 规则（polling / busy discipline）

- 线程页每 **5s** 轮询一次（`VITE_LAB_SYNC_INTERVAL_MS`，与主控制台同一个 env/默认值），同时取 `state` 与 `lifecycle`；仅在当前视图为线程页且令牌非空时轮询。
- 与既有控制台一致的单 `busy` 字符串：注入、暂停/继续/停止、stop-all、lifecycle 动作、快照、手动同步都会置 `busy`；**busy 非空时定时器跳过本轮**，所有按钮同时禁用，避免动作与轮询互相覆盖。另有 `pollInFlight` 防止慢请求叠加。
- 切换视图时主控制台的定时器停止（`watch(view)`），线程页组件卸载时 `onBeforeUnmount(stopPolling)`；同一时刻只有一个页面在轮询。
- 动作成功后会先采用接口返回（lifecycle 视图直接作为真相），再静默刷新一次故障列表；动作失败保留错误 banner，下一次成功刷新自动清除。

# 4. 实栈冒烟结果（live smoke）

- 重建 `java-learn-lab/chaos-controller:jdk21` 并 `up -d --build --no-deps chaos-controller` 重建 `jl-chaos-controller`，健康状态 `healthy`；未改 compose、未动其他容器。
- 三个应用实例 `jl-app-1/2/3` 均 `healthy`，且为 standalone 拓扑（`MYSQL_MODE=standalone`，无 `jl-toxiproxy` 容器、未加载 chaos-proxy overlay）。dashboard `dist` 重新构建后由 Nginx 只读挂载直接生效，无需重建 Nginx。
- `bash code/lab/scripts/console-smoke.sh` → **30 PASS / 0 FAIL**（基线 26 PASS，本步 +4；M6-1 动画视图落地后为 **37 PASS**，见 `step-46-animation-views.md` §6）：
  1. `thread lab page opens with the six-state display`
  2. `injected cpu-burn fault is listed as ACTIVE`（页面注入 `cpu-burn`，hold=auto，TTL=5）
  3. `stopping the fault empties the thread fault list`
  4. `proxy reports no active thread fault after cleanup`
- 冒烟结束态：`app-1/app-2/app-3` 经代理 `GET /lab/thread/state` 全为 `{"code":"0","message":"OK","data":[],"success":true}`；向导 `idle`、`activeActions=0`；`jl-traffic` `exited`；`jl-chaos-controller` `healthy`。
- 代理调用样本（`X-Lab-Token` 不打印）：
  - `GET /lab-api/chaos/app/app-1/lab/thread/state` → `{"code":"0","message":"OK","data":[],"success":true}`
  - 注入 → `{"code":"0","message":"OK","data":{"mode":"cpu-burn","hold":"auto","state":"ACTIVE","startedAtEpochMs":1793067832511,"remainingTtlMillis":4982,"threadIds":[93,94]},"success":true}`
  - 停止 → `{"code":"0","message":"OK","data":{"mode":"cpu-burn","hold":"auto","state":"STOPPED","startedAtEpochMs":1793067832511,"remainingTtlMillis":0,"threadIds":[93,94]},"success":true}`
  - 未知实例 → `HTTP 404` / `{"code":"40401","message":"Unknown app instance 'app-9'; allowed instances: [app-1, app-2, app-3]","data":null,"success":false}`
  - 白名单外 `GET .../lab/toggles` → `HTTP 404` / `{"code":"40401","message":"Path is not in the thread-lab proxy allow-list: GET /lab/toggles",...}`
  - `DELETE .../lab/thread/state` → `HTTP 405` / `{"code":"40501","message":"Method 'DELETE' is not allowed; the proxy forwards GET and POST only",...}`
- 测试覆盖（新增 53）：`AppProxyServiceTest` 34（白名单正/反例、模式×动作、body 转发/丢弃、未知实例、超限）、`HttpAppProxyGatewayTest` 6（token/Accept 头、JSON body、409 原样回传、HTML→502 条件、空 body、连接拒绝→不可达且不含 token）、`AppProxyControllerTest` 9（透传、404/503/502/405/400、响应不回显 `X-Lab-Token`、body 上限）、`AppProxyPropertiesTest` 4（默认值、空/非法/重复实例、传输与超时边界）。

# 5. 安全边界与遗留（security note / what remains）

**安全边界**：代理不是通用反向代理，而是「实例白名单 × 方法/路径白名单 × 小 body × 自身令牌」的组合。`/lab/**` 中只有上表列出的线程端点可达；带 `..`、编码斜杠、额外段、查询串的请求都会在正则匹配阶段失败；白名单外的路径统一 404 而不是 403，避免向调用方确认内部端点存在。控制器只注入自己的 `lab.token`，既不接受调用方指定上游令牌，也不把令牌写入响应/日志/异常。Nginx 对应用 `/lab/**` 的「不代理」约定保持不变。

**遗留 / TODO**：

- **Playwright 深断言未做**：只加了 4 条（打开页面、注入并显示 ACTIVE、停止后清空、代理最终为空），未逐步点击 lifecycle 的 `start/step/freeze/resume/reset`，也未在浏览器里触发快照按钮。生命周期与快照的 UI 已通过实现 review 与 `npm run build`，但真实点击路径只做了人工代码核对；后续可补 5–8 条 Playwright 断言。
- **快照实栈未点**：本步冒烟未调用 `POST /lab/thread/snapshot`（会写 app 容器内 `doc/process/Stage1/snapshots`），留待线程实验室完整演示时验证 captured/files/annotation。
- **多实例聚合未做**：页面一次只看一个实例；「全部实例 stop-all」需要控制器广播，属设计 §10 的后续任务。
- **令牌转发在实栈无法区分**：controller 与应用共用同一个 `LAB_TOKEN`，所以「调用方令牌被误转发」在实栈与正确实现表现一致；该点由 `HttpAppProxyGatewayTest` 的 header 断言钉死（网关收到的 token 来自构造参数）。
- **页面实例下拉来自构建时 env**：`VITE_LAB_APP_INSTANCES` 只影响显示；若与服务端 `CHAOS_APP_PROXY_INSTANCES` 不一致，选择被移除的实例会得到 404/40401，这是预期的服务端兜底。
- **未改 compose**：新配置项全部有代码默认值，现有 compose 无需新增 env；需要收紧/放宽实例或超时时，在 controller 服务上补 `CHAOS_APP_PROXY_*` 即可，无需改 Dockerfile。
