# 当前进展

B5-05d：修复故障注入 `40401 Container not found: app-1`，并补齐容器/线程故障使用说明。
根因：控制台下拉传实例名 `app-1`，控制器直接把它交给 Docker；实际容器名是 `jl-app-1`。
已在控制器入口统一规范化，控制台与 API 两种写法都可用；已部署控制器并用 `app-1` 短 TTL freeze 实测通过。

# 完成功能项

| 项 | 落地 |
|---|---|
| 实例名 → 容器名 | `ChaosController.resolveTarget`：`app-<数字>` 前缀 `lab.instance.container-prefix`（默认 `jl-`）后再查 Docker；`jl-app-1` 等已是容器名的原样通过 |
| 操作台账 | `before.container` / operation target 记录规范化后的 `jl-app-1` |
| 测试 | 新增 `instanceNameIsMappedToTheContainerName`；controller 测试通过 |
| 真机 | `POST /lab-api/chaos/instances/app-1/actions {freeze,ttl=8}` 返回 target `jl-app-1`，8s 后自动恢复，`/chaos/actions` 为空，app-1 healthy |
| 使用说明 | 新增 [`故障注入使用说明.md`](故障注入使用说明.md)：容器动作、TTL、线程故障七模式、实际线程名、OOM 拒绝原因与一次性实例开关 |
| UI 提示 | 故障抽屉说明“操作整个容器，不是线程”；线程抽屉说明 `thread-lab-*` 演示线程；选择 OOM 时显示禁用原因 |

# 设计逻辑

- `app-1` 是控制台/实例台账名称，`jl-app-1` 是 Docker 容器名。把规范化放在控制器入口，避免每个调用方各自拼 `jl-`，也兼容直接调用 API 的旧脚本。
- 容器故障作用于整个 JVM/容器；线程故障只创建 `thread-lab-<mode>-*` 演示线程，不碰 Tomcat `http-nio-*` 或业务池。
- OOM 是无上限分配，必须显式在一次性小堆实例上设置 `LAB_THREAD_OOM_THROWAWAY=true`；共享 app-1/2/3 继续拒绝，使用有上限的 heap-leak 演示。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| OOM 无一次性实例 | 当前三实例均为共享业务实例，OOM 只能用外部临时实例演示 | 可选增强 |
| 控制器镜像需重建 | 本次已用 `Dockerfile.runtime` 构建并 recreate；后续代码变更仍需同步 | 部署 |
| Redis 2+2 运行栈 | 配置已改，运行中的旧 3+3 仍需按 B5-05c 重建 | 部署 |

# 待定事项和注意事项

1. `app-N → jl-app-N` 只对纯 `app-<数字>` 生效；数据类容器仍使用 `jl-mysql`/`jl-redis` 等完整名称。
2. 容器 kill/freeze 会中断业务请求；正式对照一次只注入一个变量，结束用「清除全部故障」。
3. OOM 的 TTL 不能恢复已退出的 JVM；一次性实例需依赖重启策略，不能当作可回滚故障。
