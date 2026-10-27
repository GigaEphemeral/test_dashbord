# compose 嵌套默认值静默变空串

## 现象

`compose.ha.yml` 里给应用注入哨兵列表：

```yaml
SPRING_DATA_REDIS_SENTINEL_NODES: ${REDIS_SENTINEL_NODES:-sentinel-1:${REDIS_HA_SENTINEL_PORT},sentinel-2:${REDIS_HA_SENTINEL_PORT},sentinel-3:${REDIS_HA_SENTINEL_PORT}}
```

`.env` 里没有 `REDIS_HA_SENTINEL_PORT` 时，`docker compose config` **不报错**，渲染结果却是：

```
SPRING_DATA_REDIS_SENTINEL_NODES: sentinel-1:,sentinel-2:,sentinel-3:
```

应用直到启动期做 `host:port` 校验才失败，错误信息离现场很远。

## 根因

Compose 的插值只解析一层：外层 `${A:-默认值}` 里的「默认值」文本仍会继续参与插值，未定义的变量**替换为空串且不警告**（`docker compose config` 只打印 warning，不阻断）。嵌套默认值本身是支持的，但「内层变量未定义」不会让外层回退，只会得到空值。

## 正确做法

- 给内层变量也写默认值：`${REDIS_HA_SENTINEL_PORT:-26379}`。
- 或者在脚本层统一渲染：`up.sh`/`down.sh` 在调用 compose 前检查关键键是否齐备，缺失则打印键名并退出（本仓库 down.sh 的「env-key 门」就是这个思路）。
- 应用侧保留启动期校验作为最后一道闸，但不要把它当作第一发现点。

## 验证

```bash
cd code/lab/docker
docker compose --project-name jl-check -f compose.base.yml -f compose.dev.yml -f compose.ha.yml --profile ha \
  config | grep -A1 SPRING_DATA_REDIS_SENTINEL_NODES
# 期望：sentinel-1:26379,sentinel-2:26379,sentinel-3:26379（没有空端口）
```

三种 env 状态都要试：两键全缺、只置空 nodes、完整 `.env.example`。

## 出处

- 独立审核发现：`doc/process/Stage1/step-24b-sentinel-client.md` 的「审核修复」节（`fe8dfb7`）
- 脚本门：`code/lab/scripts/down.sh`、`step-24a`
