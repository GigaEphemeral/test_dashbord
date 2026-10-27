# Redis Sentinel 用主机名 monitor 会 `#tilt`

## 现象

哨兵日志持续刷：

```
#tilt mode
master mymaster ... address is unresolvable or invalid
```

或哨兵起来后 `SENTINEL get-master-addr-by-name mymaster` 返回错误地址，客户端连不上主节点。

## 根因

`sentinel.conf` 的 `sentinel monitor <name> <host> <port> <quorum>` 对 `<host>` 的要求比普通配置严格：哨兵会用这个名字做复制地址推导与故障转移后的 `REPLICAOF` 目标。用 compose 服务名时，解析结果依赖各容器的 DNS 视图，在「数据节点与哨兵不在同一网络」或「Docker Desktop 服务发现抖动」时不稳定，哨兵会进入 `#tilt`。

## 正确做法

1. **monitor 用固定 IP**：给 Redis 数据节点分配固定 IP（`REDIS_HA_REDIS_N_IP`），哨兵配置里写 IP，不写服务名。
2. **跨网段要显式 announce**：应用与哨兵不在同一网络时，让数据节点用 `replica-announce-ip` / `replica-announce-port` 对外声明可达地址，否则切换后从节点会去连一个自己视角的地址。
3. **客户端只按主名发现**：应用侧（Lettuce/Redisson）配置 `sentinel.master=mymaster` 与哨兵列表，**绝不写死主节点地址**；主名是唯一契约。

本仓库实现：`code/lab/docker/redis/sentinel.conf.tpl`（占位符渲染固定 IP）+ `compose.ha.yml` 的固定 IP 网络 `jl-sentinel-net` + 应用侧 `SentinelMasterDiscoveryReporter`。

## 验证

```bash
bash code/lab/scripts/sentinel-status.sh
# 三个哨兵应一致：master0:name=mymaster,status=ok,address=172.28.190.11:6379,slaves=2,sentinels=3
# 不应出现 #tilt / -sdown 反复抖动
```

切主后再看 `master_link_status=up`，且旧主以 slave 身份指向新主 IP。

## 出处

- `doc/process/Stage1/step-24a-sentinel-compose.md`
- `doc/process/Stage1/step-24b-sentinel-client.md`
- `doc/process/Stage1/step-24d-sentinel-live-drill.md`（真机切换与归队证据）
