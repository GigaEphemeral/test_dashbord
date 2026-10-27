# 步骤交接：S2-B0-05 窄代理 query string 透传与上限外置

> 任务/步骤：Stage2 B0-05 —— 修复 chaos-controller 窄代理的 query string 丢弃问题，并把请求体/响应体/超时三个运行参数外置可配（为 B3 `/lab/cache/keys?cursor=...` 铺路）
> 日期：2026-10-27
> 分支 / HEAD：基线 feature/stage2-workbench（提交 ca76318），worktree agent-general-purpose-a3bb1fde，本步提交 d767a2f
> 关联任务：B3 缓存 key 分页接口（依赖本步的透传与响应上限）；改造方案 §5.4/§10.4
> 测试 / verify：`mvn -f code/pom.xml -pl services/chaos-controller -am test` → chaos-controller 278 tests 全绿（基线 264，净增 14）；未跑真机
> 运行状态：仅单测层交付；栈演练（Nginx → /lab-api/ → 8081 → app:8080）未执行
> 口径指针：本文第 4/5 节是当时的状态快照，当前缺口口径以 Stage2 计划文档为准

# 当前进展

- 缺陷**证实**：改造方案「当前代理会丢弃 query string」的断言属实。证据（改动前代码）：
  - `AppProxyController.java:60-67`（原）：仅取 `request.getRequestURI()` 截路径，全文没有任何 `getQueryString()` 调用；
  - `HttpAppProxyGateway.java:56`（原）：`URI.create(scheme + "://" + instance + ":" + port + path)` 拼接 URI 时只带 path，query 无入口（`exchange`/`forward` 签名里根本没有 query 参数）。
  - 结论：浏览器 `GET /lab-api/chaos/app/app-1/lab/thread/state?cursor=x` 到达 app 时会变成 `GET /lab/thread/state`（参数全丢）。
- 已修复：query string 沿 Controller → Service → Gateway 全链路透传；三个运行参数全部外置；单测补齐；模块 278 tests 绿，已提交 d767a2f。
- 顺带修正任务口径：白名单外路径实际是 **404/40401**（`LabExceptionHandler` 把 `AppPathNotAllowedException` 映射 NOT_FOUND），不是任务描述里的 405/40501；405/40501 只用于不支持的 HTTP 方法（既有 `unsupportedVerbMapsTo405` 测试覆盖）。本次未改变该语义。

# 完成功能项

- query string 透传（只透传、不解释）：
  - `web/AppProxyController.java`：`request.getQueryString()` 原样传给 service（允许为 null/空）。
  - `appproxy/AppProxyService.java`：`forward(instance, method, path, query, body)`；白名单只匹配 path，query 完全不参与判定与解码。
  - `appproxy/AppProxyGateway.java` / `HttpAppProxyGateway.java`：`exchange(..., query, ...)`；URI 拼接 `? + rawQuery`。
- 三参数外置（全部在 `application.yml` + `@ConfigurationProperties(chaos.app-proxy)`，可 env 覆盖）：

  | 键 | 默认值 | env | 超限/超时行为 |
  |---|---|---|---|
  | `chaos.app-proxy.max-request-bytes` | 8192 | `CHAOS_APP_PROXY_MAX_REQUEST_BYTES` | 请求体超限 **413/41301**（原 400/40001，语义修正） |
  | `chaos.app-proxy.max-response-bytes`（新增） | 2097152（2MB） | `CHAOS_APP_PROXY_MAX_RESPONSE_BYTES` | 上游响应超限 **502/50202**（报错不截断） |
  | `chaos.app-proxy.request-timeout-seconds` | 10 | `CHAOS_APP_PROXY_REQUEST_TIMEOUT_SECONDS` | 上游超时 **503/50301**（沿用 `AppUnreachableException` 风格） |

  默认值维持现值（yml 注释说明 seed 最坏 1000 行需要 >5s，故 timeout 未降到 5s；8192B 对 fault/mode JSON 已足够，B3 分页靠 response 上限而不是放大请求上限）。
- 新异常与映射：`AppProxyRequestTooLargeException`（413/41301，`LabErrorCode.PAYLOAD_TOO_LARGE` 新增）、`AppProxyResponseTooLargeException`（502/50202，`LabErrorCode.RESPONSE_TOO_LARGE` 新增），均注册进 `LabExceptionHandler`。
- 单测（净增 14 条）：
  - `AppProxyServiceTest`（50）：query 原样到达 gateway（含 `%20`、`+`、`==`、裸 flag、空值）；null/空 query 原样传递；query 不参与白名单（带 query 的白名单外路径仍 404，`/lab/cache/keys` 仍拒绝）。
  - `HttpAppProxyGatewayTest`（13）：真 `HttpServer` mock 上游，断言上游 `getRawQuery()` 逐字节等于原始 query；空 query 不发 `?`；URI 非法字符（`|`）只做字节级转义其余原样；畸形 `%zz` 拒绝为 400 且请求不出网；响应超限 502 异常；限额内正常透传；慢上游触发外置超时 → 503 风格异常。
  - `AppProxyControllerTest`（14）：GET 带 query 时 controller 原样转给 service；超限体 413/41301；service 侧超限异常也映射 413；响应超限映射 502/50202；回归位保留（白名单外 404/40401、DELETE 405/40501）。
  - `AppProxyPropertiesTest`（4，边界断言并入 `transportAndTimeoutsAreBounded`）：`max-response-bytes` 边界（0 / 16MB+1 拒绝，2MB 通过）。
- 白名单未动：精确路径集合零新增（`/lab/cache/keys` 留给 B3）。
- 未触碰：`code/lab/docker/**`、`code/lab/scripts/**`、`claims.yml`、`doc/readme.md`、app 模块。

# 设计逻辑

- 透传即安全：query 在 Servlet 层拿到的就是请求行原始 percent 编码串，代理不 decode、不重新编码、不参与 allow-list，注入面不扩大（`..`、`//`  tricks 依旧只看 path）。
- URI 构造兜底：`URI.create` 对 `| { } < > ^` 等 RFC3986 非法 query 字符直接拒绝（实测 JDK21 行为），若原样拼接会在网关层炸出 500。策略是「先原样尝试；失败时仅对非法字节做 %XX 转义（已存在的 `%XX` 序列不动，逐字节 UTF-8）」——语义等价、可被 app 端原样解码；转义后仍解析不了（如 `%zz` 这类畸形转义）则 400/40001 拒绝且**不出网**。
- 响应超限选择「报错」而非「截断」：本代理的响应契约是完整的 `Result` JSON 信封（与 `AppResponseNotJsonException` 同一族校验），截断必然产生非法 JSON，截断即谎言；所以沿用 502 语义，用独立码 50202 方便控制台区分「上游坏了」和「上游太大了」。
- 请求超限从 400 改 413：请求本身格式合法、只是超字节预算，413 是 HTTP 正解；`Result` 信封风格不变，控制台读 message 里的上限即可自适应。
- 超时保持 `AppUnreachableException`（503/50301）：与既有「连不上/读不到」同一呈现口径，避免控制台新增分支。

# 存在缺陷项&TODO

- 真机分页链路（Nginx → 8081 → app）未演练——本步无 `/lab/cache/keys` 路由，端到端只能等 B3；单测层已锁定「上游收到原样 query」。
- 上游超限目前是全量读入后再判长（JDK `ofByteArray`），2MB 上限下内存放大风险可接受；若 B3 把上限调很大，考虑改用 `BodyHandlers` 流式限长。
- MockMvc 对 query 的透传保真度不等于真 Tomcat（如空格、`%` 编码差异）；真机验证时留意 Nginx 规范化行为。
- 改造方案 §5.4/§10.4 的「代理丢弃 query」断言已修复，若方案文档需同步状态，由主会话统一回写（本步不改 doc/design 与 claims.yml）。

# 待定事项和注意事项

- **Nginx 侧结论（未动 docker/）**：`code/lab/docker/nginx/conf.d/default.conf` 的 `location /lab-api/ { proxy_pass http://chaos-controller:8081/; }` 为固定 URI 代理，Nginx 在该形式下会自动追加原始 args（等价 `$is_args$args`），query string 可穿过现配置，**预计无需改 default.conf**。真机跑 B3 时用 `curl '.../lab-api/chaos/app/app-1/lab/thread/state?cursor=v2%3A8'` 复核 app 访问日志即可；若发现被裁剪再统一处理（禁在本步改动）。
- 给 B3 的接口约束：新路由必须加进 `AppProxyService` 的 `CACHE_GET_PATHS`（精确匹配，含查询串的路径永远进不来）；分页页大小需保证 `GET /lab/cache/keys?cursor=` 的响应 ≤ `CHAOS_APP_PROXY_MAX_RESPONSE_BYTES`（默认 2MB），否则先在 .env 扩上限再上线。
- 安全边界不变：GET/POST 二法、实例白名单、精确路径白名单、`X-Lab-Token` 注入点均未被本步弱化（回归测试在位）。

# 附录（可选）

- 提交：`d767a2f fix: preserve query string and externalize app proxy limits`（14 files, +440/-60）。
- 证据：surefire 汇总 `Tests run: 278, Failures: 0, Errors: 0, Skipped: 0`（chaos-controller；reactor 上游 platform-common 10、platform-observability 4 全绿，BUILD SUCCESS）；日志临时路径 `/tmp/b005-test2.log`。
- 关键文件（绝对路径基于 worktree 根 `D:\000CODE\JAVA-LEARN\.qoder\worktrees\agent-general-purpose-a3bb1fde`）：
  - `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/appproxy/HttpAppProxyGateway.java`（buildUri/escape、响应判长）
  - `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/appproxy/AppProxyService.java`（query 参数、413）
  - `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/web/AppProxyController.java`（getQueryString）
  - `code/services/chaos-controller/src/main/resources/application.yml`（`chaos.app-proxy.max-response-bytes`）
- unverified：真机经 Nginx 的 query 透传、B3 游标分页可用性、compose 侧 env 注入生效（本步只保证 yml/env 键名存在）。
