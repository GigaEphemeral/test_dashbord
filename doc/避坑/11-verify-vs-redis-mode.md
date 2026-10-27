# `verify.sh` 与 `REDIS_MODE` 的耦合：sentinel 模式期间假失败

## 现象

刚做完 Sentinel 演练、应用还处在 `REDIS_MODE=sentinel` 时直接跑 `verify.sh`，出现 4 项 FAIL：

```
FAIL  cache:product key exists in redis
FAIL  login token written to redis
FAIL  seckill stock key present
FAIL  ...
```

但业务其实是好的。

## 根因

`verify.sh` 的 Redis 内容检查是 `docker exec jl-redis redis-cli …`——它钉死在 **dev 单机 Redis** 上。应用在 sentinel 模式下所有写入都落在 HA 集群的主节点（`jl-redis-1/2/3`），两边不是同一个实例，所以「dev Redis 里没有这些 key」是预期结果，不是故障。

## 正确做法

1. **明确验收顺序**：`up.sh --profile ha` 的演练完成后，先把 app 层还原成 standalone（base+dev 重建 app-1/2/3），再跑 `verify.sh` / `smoke.sh`。
2. 或者给 verify 加模式感知：读取 app 的 `REDIS_MODE`，sentinel 模式时把 Redis 检查改成 `docker exec jl-redis-N`（主节点），并把这组检查标注为「HA 视角」。当前仓库选择的是前一种（保持 verify 语义单一）。
3. 在交接/README 里写清这个顺序，避免下次又踩。

## 验证

```bash
docker exec jl-app-1 sh -c 'echo $REDIS_MODE'      # standalone 才跑 verify
bash code/lab/scripts/verify.sh                     # 期望 104 PASS / 1 WARN
```

## 出处

- `doc/process/step-24b-sentinel-client.md`（还原 Runbook）
- `doc/process/阶段交接-2026-10-26.md`「已知缺陷」第 6 条
- `doc/readme.md` §8 FAQ
