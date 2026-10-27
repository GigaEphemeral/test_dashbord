# k6 的动作超时短于 Docker stop：kill 生效了却报 HTTP 0

## 现象

`db-failover` 真机演练里，时间线 VU 在 kill 之后 5 秒报错并中止后续事件：

```
08:08:04 [db-failover] t=+20.0s action=kill target=jl-mysql-2 ttl=45s
08:08:09 [db-failover] failed action=kill target=jl-mysql-2:
         POST /chaos/instances/jl-mysql-2/actions returned HTTP 0 (expected 200/202):
08:10:49 [db-failover] teardown stop-all reverted=0 failed=0
```

`HTTP 0` = k6 侧传输层超时，不是控制器返回的状态码。但同一时刻容器**确实被停了**：

```
jl-mysql-2   query failed: container ... is not running
read/write :6446 -> mysql-1 (server_id=1, READ_WRITE)     # 新主已晋升
```

于是 k6 汇总里 `chaos_events{result="failed"}` 计 1、`server_errors` 计 1，
而 step-48 的验收清单第 1 条要求「accepted ≥ 1，无 failed」——演练物理上成功，
证据上却被判失败。多事件时间线（如 `ha-app-failover.json` 的 3 个事件）更糟：
第一个 kill 超时后 `break`，后面的 freeze/resume 全部不会执行。

## 根因

`POST /chaos/instances/{target}/actions` 是**同步**的：控制器要等 Docker 调用返回
才回 202（`ChaosActionService.execute` → `DockerContainerGateway.stopContainerCmd`），
而 `chaos.stop-grace-seconds` 默认 **10s**——MySQL 收到 SIGTERM 要刷 InnoDB，
实测这次 stop 用了 **11s**（08:18:18 发出 → 08:18:29 收到 202）。

k6 侧 `CHAOS_HTTP_TIMEOUT` 默认只有 **5s**（`code/lab/k6/lib/chaos.js`），
比服务端的同步预算还短，所以「慢但成功」必然被读成失败。
app 容器（`jl-app-*`）停得快，掩盖了这个差值；换到 datastore 容器就暴露了。

## 正确做法

1. **客户端超时必须大于服务端的同步预算**：`CHAOS_HTTP_TIMEOUT` 默认改成 `20s`
   （stop grace 10s + Docker API 余量）。它是上界不是 sleep，调大不拖慢正常路径。
2. 判断这类问题时先分清「动作没生效」和「动作生效但回执超时」：
   查容器状态与 `mysql_cluster_primary` 是否翻转，再看 k6 日志。
   回执超时时 **TTL 仍然是有效的回滚路径**（本例 `teardown stop-all reverted=0`
   正是因为 TTL 已经把旧主拉回来了）。
3. 加回归测试把这条契约钉住：`code/lab/k6/test/db-failover-scenario.test.mjs`
   断言 kill 请求的 `params.timeout` 解析后 ≥ 15000ms。
4. 同样的教训适用于任何「客户端超时 vs 服务端同步操作」的组合；
   `ha-acceptance.sh` 的 kill 探测预算此前也踩过一次（`24dcad0`）。

## 验证

```bash
node --test "code/lab/k6/test/*.test.mjs"     # 60 pass / 0 fail（含新增超时契约）

# 真机复跑：kill 被接受且返回 202，chaos_events 只有 accepted
LAB_TOKEN="$TOKEN" CHAOS_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  CHAOS_DATASTORE_ALLOWED_TARGET_PATTERN='^jl-mysql-[0-9]+$' \
  DB_FAILOVER_TARGET=<当前 PRIMARY> DB_FAILOVER_DURATION=3m \
  bash code/lab/k6/run.sh db-failover
# 08:18:29 [db-failover] accepted action=kill target=jl-mysql-1 status=202
```

## 出处

- `code/lab/k6/reports/20261027-160742-db-failover/k6.log`（超时那次）
- `code/lab/k6/reports/20261027-161756-db-failover/k6.log`（修复后 202，stop 耗时 11s）
- `doc/process/Stage1/step-48-k6-ha-cache-scenarios.md`「真机运行」节
