# 当前进展

B5-05 P0：控制面先具备“app-3 = JDK8 业务槽位（2×21 + 1×8）”能力，但**默认仍保持 shell 模式**，
因为 app-3 当前运行 JDK21 且已有 `APPLIED` 配置；在 P1 的 JDK8 完整业务镜像就绪前直接翻转会破坏
app-3 的登录/缓存/实验端点。本步只改控制面与部署参数，不切运行时。

验证：控制器编译通过；`InstanceConfigValidatorTest`、`InstanceRebuildServiceTest`、
`InstanceRebuildControllerTest`、`RunPrecheckServiceTest`、`RunEnvCaptureHookTest` 全部通过；
claims **86/86**。

# 完成功能项

| 项 | 落地 |
|---|---|
| JDK8 运行位置可切换 | `lab.instance.jdk8-mode=shell|app-slot`（`LAB_INSTANCE_JDK8_MODE`）。`shell` = 现有独立外壳；`app-slot` = `jdk8-instance` 指向 app-1/2/3 之一（默认目标 app-3）。 |
| 槽位校验 | `InstanceConfigValidator`：只有 `jdk8-instance` 槽位允许 `jdkVersion=8` / `imageTag=jdk8`；其他 app 槽位明确拒绝；槽位必须为 8。 |
| 受控重建 | `InstanceRebuildService.rejectJdk8` 改为按“槽位”判断，而不是按 `app-\d+` 一刀切；槽位必须是 jdk8。 |
| 起跑环境采集 | `RunEnvCaptureHook` 在 app-slot 模式不再排除 app-3；shell 模式仍排除外壳。 |
| 镜像预检查 | 新增 `lab.precheck.mixed-image-container`（`LAB_PRECHECK_MIXED_IMAGE_CONTAINER`）：允许唯一指定容器使用另一镜像，其余 app 容器必须一致；预期镜像校验只作用于多数派。默认空 = 历史“全部一致”。 |
| app-3 镜像可覆盖 | compose `jl-app-3` 支持 `APP3_IMAGE` / `APP3_DOCKERFILE`，默认仍 jdk21；P1 只需改 `.env` 即切 jdk8，无需改 compose。 |
| 配置文档 | `.env.example` 写明 shell/app-slot 两种口径与切换变量。 |

# 设计逻辑

- **为什么用 mode 开关**：app-3 现在是 JDK21，并已有 APPLIED 配置；如果直接把 `jdk8-instance` 改成 app-3，
  校验/重启会要求 jdk8 镜像，而 P1 镜像尚未构建。mode=shell 保证当前栈不变，P1 完成后一次性切到 app-slot。
- **2×21+1×8 语义**：app-1/app-2 固定 jdk21；app-3 在 app-slot 模式固定 jdk8；三者仍在同一 Nginx upstream
  和同一 run 节点集合中，业务流量仍按轮询各约 1/3。
- **混合镜像预检**：不是“随便允许两个镜像”，而是仅允许 `mixed-image-container` 指定的那一个容器为少数派，
  多数派必须完全一致；预期镜像只校验多数派，避免把 jdk8 槽位误判为漂移。
- **P1 未完成前的可逆性**：所有默认值保持 shell，当前 16 服务/17 dual-jdk 口径不变。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 默认未切 app-3 | 需要 P1 的 JDK8 完整业务镜像；当前 app-3 仍 jdk21 | P1 |
| 控制台仍显示外壳列 | UI 的 JDK8 外壳入口属于 shell 模式；app-slot 模式需要移除并在 app-3 上暴露 jdk8 | P1 |
| 外壳服务未删除 | `jl-seckill-app-jdk8` 仍保留在 `dual-jdk` profile，P1 完成后删除并更新 verify/claims | P1 |
| 镜像标签未校验 | 预检只知道 imageId，不知道 tag；混合模式只能验证“少数派唯一 + 多数派一致”，tag 由实例配置/effective 校验 | 记录 |
| 虚拟线程 | JDK8 无虚拟线程；P3 线程实验室需标 N/A 或平台线程替代 | P3 |

# 待定事项和注意事项

1. 切换到 app-slot 的最小 env：
   ```text
   LAB_INSTANCE_JDK8_MODE=app-slot
   LAB_INSTANCE_NAMES=app-1,app-2,app-3
   LAB_INSTANCE_JDK8_INSTANCE=app-3
   LAB_INSTANCE_JDK8_PROMETHEUS_JOB=seckill-app
   LAB_PRECHECK_MIXED_IMAGE_CONTAINER=jl-app-3
   APP3_IMAGE=java-learn-lab/seckill-app:jdk8
   APP3_DOCKERFILE=services/seckill-app-jdk8/Dockerfile
   ```
2. 不要把 `LAB_INSTANCE_JDK8_MODE=app-slot` 与当前 shell 镜像混用：app-3 会启动一个只有商品直读路径的 JDK8 外壳，登录/k6 会失败。
3. 当前默认仍是 `shell`，`console-smoke`/`verify.sh` 的现有结果不受本步影响；P1 翻转后需要同步 UI、smoke、claims 和 D7 面板口径。
