# 步骤 50 · 缓存实验室控制台页与 `/lab/cache/**` 窄代理

> 步骤交接 · 控制台（Vue）与 chaos-controller
> 日期：2026-10-27
> 代码基线：`d8cedee`（`docs: hand over the step-47 probe target wiring and self-primary WARN`）
> 交付：`AppProxyService` 第二份缓存白名单及测试、`CacheLab.vue`、`api.js` 缓存客户端、`App.vue` 导航、`style.css`、`console-smoke.mjs/.sh`、`doc/design/details/10-操作台与API.md`、本文档。
> 边界：只改控制器代理、dashboard、console smoke、操作台文档与本交接；不动应用端 `/lab/cache/**` 实现，不改线程页白名单。

---

## 1. 代理契约

### 1.1 精确白名单

`code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/appproxy/AppProxyService.java` 现在有两份互不覆盖的白名单，线程列表保持原样：

| 分组 | 方法 | 精确路径 |
|---|---|---|
| 线程 | GET | `/lab/thread/state`、`/lab/thread/lifecycle` |
| 线程 | POST | `/lab/thread/fault`、`/lab/thread/stop-all`、`/lab/thread/lifecycle/{start,step,freeze,resume,reset}`、`/lab/thread/snapshot`、`/lab/thread/fault/{deadlock,stuck,cpu-burn,heap-leak,oom,thread-leak,starvation}/{stop,pause,resume}` |
| 缓存 | GET | `/lab/cache/state`、`/lab/cache/preheat` |
| 缓存 | POST | `/lab/cache/mode`（JSON body）、`/lab/cache/seed`、`/lab/cache/preheat` |

**刻意不在白名单**：`GET /lab/cache/mode`（控制台只读 `/state`）、`POST /lab/cache/preheat/run`（实际契约就是 `POST /preheat`）、`/lab/toggles`、`/lab/config`。尾斜杠、`..`、查询串、额外路径段都不会匹配。

### 1.2 安全属性（与线程代理一致）

- 实例白名单：`CHAOS_APP_PROXY_INSTANCES`（默认 `app-1,app-2,app-3`），未知实例 404/40401。
- 方法与路径：只映射 GET/POST；白名单外 404/40401，其他动词 405/40501。
- 请求体：上限 `CHAOS_APP_PROXY_MAX_REQUEST_BYTES`（默认 8192）；只有 `POST /lab/thread/fault` 与 `POST /lab/cache/mode` 转发 body，其余 POST 即使带 body 也丢弃。
- 超时外置：`CHAOS_APP_PROXY_CONNECT_TIMEOUT_SECONDS`（2s）、`CHAOS_APP_PROXY_REQUEST_TIMEOUT_SECONDS`（10s）。
- 上游错误：不可达 503/50301，非 JSON 502/50201；`X-Lab-Token` 由控制器注入，不回显、不日志。
- 上游仍由应用内 `LabTokenFilter` 校验，代理不是绕过鉴权。

### 1.3 样例调用（真机）

```bash
# 读状态：defense.mode=off，dataset 90000–90999，rowsInRange=1000
curl -s -H 'X-Lab-Token: <token>' \
  http://127.0.0.1:8080/lab-api/chaos/app/app-1/lab/cache/state

# 切模式：返回 {"mode":"null-cache"}，再读 state 立即反映
curl -s -X POST -H 'X-Lab-Token: <token>' -H 'Content-Type: application/json' \
  -d '{"mode":"null-cache"}' \
  http://127.0.0.1:8080/lab-api/chaos/app/app-1/lab/cache/mode

# 播种：inserted=0（幂等），alreadyPresent=1000，avalancheKeysWritten=200
curl -s -X POST -H 'X-Lab-Token: <token>' \
  http://127.0.0.1:8080/lab-api/chaos/app/app-1/lab/cache/seed

# 预热：GET 读最近一次，POST 同步跑一次 manual
curl -s -H 'X-Lab-Token: <token>' \
  http://127.0.0.1:8080/lab-api/chaos/app/app-1/lab/cache/preheat
curl -s -X POST -H 'X-Lab-Token: <token>' \
  http://127.0.0.1:8080/lab-api/chaos/app/app-1/lab/cache/preheat
```

实测：`POST /seed`（已存在 1000 行）0.37s；`POST /preheat` 0.02s；两者都返回 200 `Result` 信封。

---

## 2. 页面行为（缓存实验室）

入口：顶部导航 `缓存实验室`（`data-testid="nav-cache"`），单页 `view='cache'`，无 Vue Router。组件：`code/lab/dashboard/src/components/CacheLab.vue`。

- **实例选择**：默认 `app-1`，可切 `app-2/3`；切换时清空旧 state/预热/播种结果，避免跨实例串数据。慢响应带实例快照校验，旧实例的迟到响应不会覆盖新实例。
- **状态区**：`GET /lab/cache/state` 渲染当前 `defense.mode`、`cacheToggleEnabled`、击穿/雪崩策略参数（mutex、logical-expire、TTL 抖动、L1 镜像、DB 兜底预算）、数据集区间与行数、L1/L2 键数量、Bloom 几何、空值标记。全部来自应用真值，不从 Prometheus 推断。
- **模式切换**：8 个 key（`off/null-cache/bloom/mutex/logical-expire/ttl-jitter/l1-cache/db-fallback`）。请求在途时按钮禁用并显示“切换中…”；成功后用响应覆盖当前模式并静默刷新；失败在 `cache-error` 横幅显示服务端错误码/原因。
- **5s 轮询**：与线程页相同的 `SYNC_INTERVAL_MS`；`busy`（手动刷新/模式切换/预热）期间跳过。**播种不占 `busy`**，只置 `seeding`，因此播种期间轮询继续，页面不冻结。
- **播种**：按钮文案显示数据量；运行中禁用并显示“播种中…”；完成后展示 id 区间、`rowsInRange`、`inserted/alreadyPresent`、雪崩键、热键、Bloom、seed 时的 mode；失败走错误横幅。
- **预热**：展示 `enabled/cacheEnabled/hotListKey/topN/hotListSize/trackedKeys/running` 与最近一次 `lastRun`（trigger/at/outcome/duration/requested/loaded/skipped/missing/failed/error）；“立即预热”同步触发一次 `manual`。
- **Grafana D10**：状态栏右侧新标签页链接 `http://127.0.0.1:3000/d/ha-d10`（`VITE_LAB_GRAFANA_URL` 可覆盖），`target="_blank" rel="noreferrer"`，**不内嵌 iframe**。
- **可访问性/错误**：错误横幅 `role="alert"`；所有按钮有 `data-testid` 供冒烟使用。

---

## 3. 播种成本与 UX

- 应用端 `POST /lab/cache/seed` 是**同步**调用：补齐预留区间缺失行（默认 1000）、写 200 个同 TTL 的 L2 雪崩键与热键、重建 Bloom。首次是真写 MySQL + Redis，通常是秒级；之后幂等（`inserted=0`）。
- 真机实测（数据已存在）：0.37s。代理请求超时默认 10s，远高于本次观测；若实验主机首次播种超过 10s，改 `.env` 的 `CHAOS_APP_PROXY_REQUEST_TIMEOUT_SECONDS` 后重建控制器，**不要改代码**。
- UX 取舍：播种不占用全局 `busy`，避免长请求阻塞 5s 状态轮询；只禁用自身按钮并显示进度标签。播种完成后静默刷新一次 state/preheat。
- 失败语义：应用把 Redis 部分失败降级为更小的 `avalancheKeysWritten` / `bloomInitialised=false`，HTTP 仍是 200；页面原样展示这些字段，不把“部分成功”伪装成全成功。

---

## 4. 冒烟结果

`code/lab/scripts/console-smoke.mjs` 新增 5 条断言（基线 37 → **42 PASS, 0 FAIL**）：

1. 缓存页打开：`cache-page` 渲染、实例默认 `app-1`、`cache-mode` 是 8 个已知 key 之一、策略区含 `defense.mode`/`击穿防护`、D10 链接含 `/d/ha-d10` 且 `target=_blank`。
2. 经代理切到 `null-cache`，`cache-mode` 立即反映。
3. 切回 `off`，`cache-mode` 反映。
4. 播种返回 `ids <start>–<end>` 与 `rows <n>`，按钮在完成后恢复可用。
5. 手动预热后 `cache-preheat-last` 显示 `manual` 与 `requested`。

清理：`restoreLabState` 在冒烟开始与结束都把 `app-1/2/3` 的缓存模式重置为 `off`；`stop-all` 清空容器/线程故障。本轮结束栈状态：

- `GET /lab-api/chaos/actions` → `data: []`。
- `app-1/2/3` 的 `GET /lab-api/chaos/app/{instance}/lab/cache/state` → `defense.mode=off`。
- Toxiproxy 未注入任何 toxic；栈保持 healthy。

验证命令与结果：

```text
mvn -pl services/chaos-controller -am test  → BUILD SUCCESS，chaos-controller 264 tests（250 基线 + 14 新增）
npm run build (code/lab/dashboard)          → ✓ built
bash code/lab/scripts/console-smoke.sh --no-build → console smoke: 42 PASS, 0 FAIL
```

---

## 5. 仍未完成 / 后续

1. **Grafana 内嵌**：按本步要求只做新标签页深链，没有 iframe/面板嵌入；若要内嵌需处理 Grafana 匿名访问、CSP 与刷新，不建议在教学栈默认开启。
2. **独立 reset 端点**：没有“缓存实验恢复 off + 清空实验数据”的控制器端点；目前靠页面逐实例切 `off`、冒烟自动归零。删除 90000–90999 实验数据仍无 API。
3. **模式广播**：模式是应用实例内存态，按实例独立；没有“一次切三个实例”的控制器批量端点。
4. **docclaims 负断言待翻转**：`code/lab/docclaims/claims.yml` 的 `cache-console-page-missing` 与 `lab-cache-proxy-missing` 仍按“未实现”登记，功能落地后会报 STALE。本步文件范围不含 `code/lab/docclaims/**`，需下一步把这两条改成 positive 并重跑校验。
5. **首次播种耗时**：本轮数据已存在，只测得幂等播种 0.37s；未删除实验行重测首次 1000 行写入的耗时。
