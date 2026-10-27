# Toxiproxy 镜像是 distroless：没有 sh，也没有 wget

## 现象

`chaos-proxy` profile 起不来：

```
Container jl-toxiproxy Error
dependency failed to start: container jl-toxiproxy is unhealthy
```

进容器排查：

```
docker exec jl-toxiproxy sh -c 'command -v wget'
OCI runtime exec failed: exec failed: unable to start container process:
exec: "sh": executable file not found in $PATH
```

## 根因

`shopify/toxiproxy:2.9.0` 是 **distroless** 镜像，只有 `toxiproxy-server` 一个可执行文件：

- compose 里的 `healthcheck: wget … /version` 永远失败 → 容器被判 unhealthy；
- `toxiproxy-init` 用 `/bin/sh` 跑 wget upsert 脚本 → 同样无法启动；
- 依赖写成 `service_healthy` 时，app 层直接被卡死。

这也是「compose 渲染通过 ≠ 能跑」的典型：静态检查发现不了镜像里有没有 shell。

## 正确做法

1. **删掉 toxiproxy 容器的 in-container healthcheck**（distroless 无法执行任何 shell 探针）。
2. **init 用独立、带 shell 的镜像**：默认 `alpine:3.19`，通过 `TOXIPROXY_INIT_IMAGE` 配置。
3. 就绪判断放在 init 脚本里：轮询 `/version` 最多 60s，就绪后再创建代理。
4. app 层依赖 `toxiproxy-init: service_completed_successfully`（保证代理先于应用建好），而不是依赖 toxiproxy 的 healthy。

## 验证

```bash
docker logs jl-toxiproxy-init        # -> "toxiproxy: API ready at http://toxiproxy:8474" 与两个代理
docker exec jl-app-1 sh -c 'echo $REDIS_HOST'   # -> toxiproxy
```

## 出处

- 真机演练：`doc/process/Stage1/step-43-toxiproxy-live-drill.md`
- 叠加层修正：`compose.toxiproxy.yml` + `.env.example`（`TOXIPROXY_INIT_IMAGE`）
- 提交：`d61ecec`
