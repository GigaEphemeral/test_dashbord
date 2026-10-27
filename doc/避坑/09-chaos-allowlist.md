# 故障白名单默认全拒，且必须按 canonical name 校验

## 现象

1. 部署后从操作台/接口对数据节点发起故障，一律 403：
   ```
   {"code":"40301","message":"target \"jl-redis-1\" is a datastore and is refused by
    chaos.datastore.allowed-target-pattern=\"\": only a datastore container matching that allow-list may be faulted"}
   ```
   明明代码里配了白名单，容器里却是空值。
2. 审核还发现更隐蔽的一条：用**容器 id 前缀**引用目标时能绕过校验——
   `POST /chaos/instances/0a1b2c3d…/actions` 解析到 `jl-mysql`，请求里没有任何关键字。

## 根因

- **闭环默认**：白名单的语义被定义成「空 = 全拒」，而 compose 没有把 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN` 传给 chaos-controller，于是容器里始终是空串。安全上是 fail-closed（这是刻意的），演示上表现为「全阻塞」。
- **校验对象错了**：校验发生得比 Docker 的引用解析早。Docker 允许短 id 前缀指向容器，而前缀字符串本身不含 `mysql`/`redis` 等关键字，旧实现只匹配「用户传入的字符串」。

## 正确做法

1. **compose 接线**：`compose.base.yml` 的 chaos-controller 服务显式传 `CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN`，`.env.example` 给出示例（如 `^jl-(redis|mysql)-[1-3]$`；不要把单机 `jl-redis`/`jl-mysql` 放进去）。
2. **校验 canonical name**：先 `inspect` 把引用解析成真实容器名（`DockerContainerGateway.canonicalName()` 会剥掉 Docker 返回的前导 `/`），再用**同一个策略**校验；路径参数、向导步骤、k6 场景三条入口都走这一个校验点。
3. **整名匹配**：用 `Matcher.matches()` 而不是 `find()`，避免 `jl-redis-1` 匹配到 `jl-redis-10`。
4. **消息里带规则**：拒绝时回显实际生效的 pattern，否则运维无法区分「策略没接上」和「目标不合法」。

## 验证

```bash
# 允许的
curl -s -X POST .../chaos/instances/jl-redis-1/actions -d '{"action":"start"}'      # 202
# 开发库与 id 前缀都应被拒
curl -s -X POST .../chaos/instances/jl-redis/actions -d '{"action":"start"}'       # 403/40301
curl -s -X POST .../chaos/instances/79dbbf218e65/actions -d '{"action":"start"}'   # 403，且消息里是 jl-mysql
```

## 出处

- 实现：`DatastoreTargetPolicy` / `ChaosActionService` / `DockerContainerGateway`
- 审核修复：`876bf4b`/`5651b0e`（id 前缀），`72ac215`
- 真机：`doc/process/step-24d-sentinel-live-drill.md`、`step-31-mysql-router-live-drill.md`
