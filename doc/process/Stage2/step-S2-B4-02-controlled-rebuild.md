# 步骤交接：Stage2 B4-02 受控重建、健康确认、回滚与稳定期

> 任务/步骤：Stage2 **B4-02**：按存储配置受控重建单实例、健康/上游/Prometheus/真实探针门禁、失败自动回滚、稳定期、run 中禁改
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `d4a5c82`（B4-01；本步改动**本地待提交**）
> 关联任务：计划 B4-02；方案 §4.2/§4.6、§10.7；B4-01 交接
> 测试 / verify：chaos 模块 **812 / 0 fail**；smoke **7/7**；verify **109 PASS / 1 WARN / 0 FAIL**；console-smoke **138/0**（executor.rejected 已接线，断言由“未接线”改为“真实值”）
> 运行状态：dev 栈运行中；app-1 当前为基线配置 `-Xms256m -Xmx512m` + G1（实验参数已解锁），池 4/8/50/60；无 parked 容器；app-2/3 容器 ID 未变
> 口径指针：2026-10-29 本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次可能已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- B4-02 完成：控制器的 `POST /lab/instances/{instance}/apply|rollback` 真实重建 app-N，按序校验 Docker health、应用探针、`/lab/pool` 回读、Nginx reload + 入口探针、Prometheus `up`、镜像 tag/id；失败自动恢复旧容器并记录 FAILED，成功写 APPLIED + `stable_until`。
- 真机：目标配置（xmx 384、池 2/4/10/30）apply **APPLIED**，9 项检查全 PASS；rollback 回基线（xmx 512、池 4/8/50/60）同样全 PASS；两次故障注入（JVM 参数错误、Metaspace OOM）都自动恢复旧容器（`rollback=RESTORED`）。
- 未做：B4-03 GC 日志采集、B4-04 实例页 UI；GET config 尚未暴露 `stableUntil/appliedImage*`（属 B4-04 展示字段）。

# 完成功能项

- `InstanceRebuildGateway`：snapshot（镜像/Env/labels/cmd/entrypoint/hostConfig/network aliases/healthcheck）→ stop+park 旧容器 → 复制创建新容器（仅替换 `JAVA_TOOL_OPTIONS`）→ start → restore/rename/remove；镜像只允许 `<lab.instance.image-repository>:<stored imageTag>`，从不 pull。
- `InstanceRebuildService`：
  - 拒绝：run 处于 RUNNING/STOPPING/VERIFYING（409/40906）、无存储配置（409）、jdk8 用于 app-N（400）、ledger 降级（503）、同实例并发 apply（409）。
  - 成功：Docker health（冷启动瞬时 unhealthy 不再提前失败）→ 应用 `/api/hello` → `/lab/pool`（JDK21；core/max/queue/keepAlive/policy 与配置一致）→ `nginx -s reload` + `http://nginx/api/hello` → Prometheus `up{job="seckill-app",instance="app-N:8080"}=1` → 运行镜像 tag/id 核对；写 `APPLIED`、`applied_image_*`、`last_apply_at`、`stable_until`，删除 parked 容器。
  - 失败：移除新容器 → parked 重命名回原名称并启动 → 重载 Nginx → 写 `FAILED`（恢复失败则 `DEGRADED`）+ 失败检查 + 回滚结果 + `stable_until`；HTTP 200 返回证据，不吞错。
  - rollback：用 `previous_config` 走同一流程，成功后配置与上一版交换（可重复回滚）并写 `ROLLED_BACK`。
- ledger **V5**：`stable_until/applied_image_tag/applied_image_id/last_apply_at/previous_image_tag/previous_image_id`。
- 配置：`LAB_INSTANCE_STABLE_SECONDS=60`、`APPLY_TIMEOUT_SECONDS=240`、`HEALTH_TIMEOUT_SECONDS=150`、`NGINX/PROBE/POOL` URL 模板、`PROBE_TIMEOUT_SECONDS=5`、`PROMETHEUS_UP_WAIT_SECONDS=30`、`IMAGE_REPOSITORY`、`NGINX_CONTAINER`；Nginx `/lab-api` 读/写超时 30s→300s（冷启动 + 多门禁）。
- 测试：`InstanceRebuildServiceTest`（12）、`InstanceRebuildControllerTest`（8）、`InstanceConfigRepositoryIT`（5，含 V5）、配置 schema 测试（13+7+8）；console-smoke 的两条 `executor.rejected` 断言改为“已接线、真实值”。

# 设计逻辑

- **旧容器只 park 不删**：健康门禁全过才移除；任何失败先恢复旧容器（同 ID、同 Env），保证演示环境不因一次坏配置消失。
- **Docker health 的瞬时 unhealthy 不是终态**：Spring Boot 冷启动实测 20–100s，Docker 在 `start_period + retries` 后先标 unhealthy；门禁改为等到健康预算耗尽，只在容器退出/超时时失败，并把最后日志写进检查证据。
- **预算与实际类加载**：显式 `-XX:MaxMetaspaceSize=128m` 会让本应用启动即 Metaspace OOM（compose 与自建容器都复现）；因此 apply 示例不显式限制 Metaspace，B4-04 需在 UI 提示“Metaspace 建议 ≥192m 或留空用默认”。预算公式中的 96/64 只是估算默认值。
- **G1 实验参数**：`G1NewSizePercent/G1MaxNewSizePercent/InitiatingHeapOccupancyPercent` 需 `-XX:+UnlockExperimentalVMOptions`，生成器已在实验参数前注入；否则 JVM 直接拒绝启动（本次真机首轮失败原因）。
- **超时预算**：apply 会阻塞到健康门禁，Nginx 300s > apply 240s > health 150s，避免 504 而服务端继续跑造成“客户端失败、服务端成功”的歧义。
- **run 中禁改**：启动参数只允许 run 外应用；pool-only 保存仍允许，但本步不会热更新。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| GET config 缺展示字段 | `stableUntil/appliedImageTag/appliedImageId` 只在 apply 响应与 DB，未进 `InstanceConfigView` | B4-04 |
| apply 同步阻塞 | 无 operationId/202；最长 240s，Nginx 已放宽到 300s；界面需 loading 与超时提示 | B4-04/记录 |
| Metaspace 提示 | 校验允许 128，但真机会 OOM；需 UI/校验警告 | B4-04 |
| GC 日志 | 配置与 argv 已生成，日志目录挂载/采集/回读未做 | B4-03 |
| 镜像回滚 | 自动回滚恢复旧容器；人工 rollback 用 previous_config 重建，未单独保留旧镜像 tag（同 tag 场景） | 记录 |
| 失败容器诊断 | 失败容器被移除后才恢复；日志已截取进检查证据（约 1200 字符） | 记录 |
| 稳定期约束 | `stable_until` 已写，但预检查/开跑尚未拒绝稳定期内正式对照 | B4-04/B5 |

# 待定事项和注意事项

1. **下一步**：B4-03 GC 日志（挂载/轮转/回读）→ B4-04 实例页 UI（应用/重启/回滚、状态、jdk-matrix）。
2. **现场状态**：app-1 基线 512/pool 4-8-50/60；ledger `lab_instance_config` status APPLIED（revision 20，最后一次 rollback 由旧版本控制器执行，状态显示 APPLIED；新控制器回滚会写 ROLLED_BACK）。无 `jl-app-1-pre-*` 残留。
3. **重建后必须 reload Nginx**：apply 内部已做；手工 compose 重建仍需 `docker exec jl-nginx nginx -s reload`。
4. **冷启动时间**：本机实测 20–100s；健康/apply/Nginx 超时按此设置，勿按秒级压测思路收紧。
5. **安全**：重建只操作 `lab=java-learn` 的 app 容器；镜像 ref 只来自配置，不接受请求传参；不 pull、不挂载任意路径。

# 附录

## 真机证据（2026-10-29）

- apply（rev19 target）：`APPLIED`，duration ~39s，9 项检查 PASS：health、`/api/hello`、`/lab/pool core=2 max=4 queue=10 keepAlive=30`、Nginx reload + 入口 200、Prometheus up=1、image `jdk21/408b427b…`。
- rollback（previous rev18 baseline）：`APPLIED`（旧控制器；新版本为 `ROLLED_BACK`），9 项 PASS，`JAVA_TOOL_OPTIONS=-Xms256m -Xmx512m …`，池 4/8/50/60。
- 失败回滚：`G1NewSizePercent` 未解锁 → JVM 拒绝启动 → `docker-health FAIL`、`rollback RESTORED`；显式 Metaspace 128m → OOM，同样 RESTORED。
- app-2/3 容器 ID：`e399090c…` / `a9376cc4…` 全程未变。
- 最终 `docker inspect jl-app-1`：healthy；无 parked 容器。

## 命令

```bash
cd code && mvn -o -pl services/chaos-controller -am test     # 812 tests / 0 fail
bash code/lab/scripts/smoke.sh                               # 7/7
bash code/lab/scripts/verify.sh                              # 109/1/0
bash code/lab/scripts/console-smoke.sh --no-build            # 138/0
```

## 关键文件

- controller：`instance/{InstanceRebuildGateway,DockerInstanceRebuildGateway,InstanceRebuildService,InstanceRebuildController,InstanceProbeGateway,HttpInstanceProbeGateway,InstanceSnapshot}.java`、`db/ledger/V5__instance_config_apply.sql`、`application.yml`
- Nginx：`code/lab/docker/nginx/conf.d/default.conf`（300s）
- 配置：`compose.base.yml`、`.env.example`（LAB_INSTANCE_* 时间/探针/镜像/stable）
- 冒烟：`code/lab/scripts/console-smoke.mjs`（executor.rejected 断言）
