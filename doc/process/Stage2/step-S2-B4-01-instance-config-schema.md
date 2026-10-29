# 步骤交接：Stage2 B4-01 实例配置 schema 与白名单校验

> 任务/步骤：Stage2 **B4-01**：每实例 JVM/GC/业务池/Caffeine/GC 日志配置 schema、白名单校验、预算校验、草稿持久化与生效参数回读
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `9100b04`（本步改动**本地已提交、未推送**）
> 关联任务：计划 v1.6 B4-01；方案 §4.3/§4.4/§4.6、§10.2/§10.7、§16.4–§16.7；B3 收口 `9100b04`
> 测试 / verify：reactor **1385 tests / 0 fail**（chaos 790、app 509、jdk8 59、core 13、platform 14）；smoke **7/7**；verify **109 PASS / 1 WARN / 0 FAIL**；console-smoke 本步未改 UI，引用 B3 基线 **138/0**
> 运行状态：dev 栈运行中；app/controller 已用新 jar 重建（app imageId `408b427b…`、controller `4647eabad…`）；ledger Flyway **v4**（`lab_instance_config`）；无 RUNNING run
> 口径指针：2026-10-29 本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次可能已消化；当前缺口口径统一看 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- B4-01 完成：app 侧业务线程池与 Caffeine 启动参数闭环 + 控制器配置 schema/校验/存储/回读；B4-02 的“应用并重启”尚未实现（本步只到草稿与预检）。
- 前一轮失败会话留下的未提交产物（`BusinessPool*`、`LabBusinessPoolController`、`SeckillOrderConsumer` 改造、Caffeine `enabled/expireAfterAccess`）已审阅、补齐配置接线与测试并纳入本步；不是丢弃重写。
- 闸门：reactor **1385/0**（B3 收口 1337 → +48）；chaos 790；app 509；smoke 7/7；verify 109/1。
- 未做：B4-02 受控重建/回滚/稳定期、B4-03 GC 日志采集、B4-04 实例页 UI 与 jdk-matrix。

# 完成功能项

## app 侧（seckill-app-jdk21）

- **业务线程池**（`admission/config/BusinessPool*`）：有界 `ThreadPoolExecutor` bean `businessExecutor`；`SeckillOrderConsumer` 把处理投递到池并等待，拒绝时标记请求 FAILED 并恢复 Redis 预占，再走 `AmqpRejectAndDontRequeueException`。
- 指标：`executor_active_threads/queued_tasks/pool_size/completed_tasks`（Micrometer binder）+ `executor_rejected_total`（自定义计数，修复 B1-03 记录的未接线项）。
- **`GET /lab/pool`**（`LabBusinessPoolController`）：回读实际 core/max/queue/keepAlive/policy + 活跃/池大小/队列/完成/拒绝计数；仅经控制器 app-proxy（`GET /lab/pool` 白名单）访问。
- **Caffeine 启动参数**：`app.lab.cache.preheat.l1.enabled`（`LAB_CACHE_L1_ENABLED`）与 `expire-after-access-seconds`（`LAB_CACHE_L1_EXPIRE_AFTER_ACCESS_SECONDS`，0=不启用）；`TwoLevelCacheSettings` 增加两字段（保留旧构造器兼容），启动即按配置决定 L1 开关与访问过期。
- 配置外置：`app.business-pool.*` 六键 + 两个 L1 键进 app yml / `x-app-env` / `.env.example`（默认 4/8/50/60/AbortPolicy/business-pool、true、0）。
- 测试：`BusinessPoolPropertiesTest`、`BusinessPoolConfigTest`、`LabBusinessPoolControllerTest`、`TwoLevelCacheSettingsTest`、两个 consumer 测试补真实小池与拒绝路径。

## 控制器（chaos-controller）

- 新包 `instance/`：`InstanceConfig`（§16.4–§16.7 全字段）、`InstanceConfigValidator`、`InstanceConfigProperties`、`InstanceConfigRepository`、`InstanceConfigService`、`InstanceConfigController`、`InstanceViews`、`InstanceConfigStatus/Config`。
- 校验：JDK 8/21、GC 类型与参数门禁（G1/Parallel/Serial；无 ZGC/Shenandoah）、取值范围、`xmx>=xms`、`maxPool>=core`、Caffeine 秒语义、GC 日志路径必须落在 `lab.instance.gc-log-root`；`extraJvmArgs` 逐 token 白名单（拒绝 `-javaagent/-agentlib/-Xbootclasspath/-XX:OnError*/-XX:ErrorFile` 等、空白/shell 字符、`..`、绝对路径以外的危险参数），每 token 独立 argv，禁止 shell 字符串。
- 预算：`xmx + metaspace(默认128) + direct(默认64) + maxPoolSize*threadStack(默认1) + nativeReserve(默认128) <= memory-limit-mb(默认768)`，拒绝信息含完整算术与 env 名。
- 持久化：ledger **V4** `lab_instance_config`（config/previous_config/status/revision/updated_at/applied_at/last_error）；保存草稿保留上一版并递增 revision，`markApplied/markFailed` 留给 B4-02。
- 端点：`GET/PUT /lab/instances/{instance}/config`、`POST .../config/validate`、`GET .../effective`；404 未知实例、400/40001 校验失败、409/40906 run 中修改启动字段、503/50302 ledger 降级。
- 回读：`effective` 经 Docker inspect 返回 `JAVA_TOOL_OPTIONS`、`INSTANCE_ID`、镜像 tag/id；无 Docker 为 `UNAVAILABLE`，JDK8 为 `READBACK_UNAVAILABLE` + `LIMITED-OBSERVABILITY`（不伪造数值）。
- 配置外置：`LAB_INSTANCE_*` 11 键进 controller yml + compose + `.env.example`。

# 设计逻辑

- **目标值 vs 生效值分离**：草稿只写 `lab_instance_config`，`effective` 只读运行容器；真机证据：目标 `-Xmx384m`，容器实际 `JAVA_TOOL_OPTIONS=-Xmx512m`，状态 `DRAFT`。
- **启动参数不伪装热更新**：run 中只允许 pool/Caffeine/Tomcat 等非启动字段保存；JVM/GC/GC 日志变更在 RUNNING 时 409/40906（B4-02 再实现“应用并重启/回滚/稳定期”）。
- **预算是硬门禁**：按容器 `mem_limit` 计算而不是只看 `-Xmx`；默认值在拒绝消息中显式标注 `(default)`。
- **路径与参数只从结构化字段生成**：GC 日志/OOM dump 路径限定在允许根目录；`extraJvmArgs` 只做白名单 token 追加。
- **JDK8 边界**：允许镜像、JVM 参数、重启生效、GC 日志（`-Xloggc`）；应用内热回读显式 `READBACK_UNAVAILABLE`。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| 应用并重启/回滚 | 本步只存草稿与校验；无重建、健康检查、Nginx/Prometheus/真实探针验证、失败回滚、稳定期 | B4-02 |
| 生效值仅 Env 级 | `effective` 读容器 env/镜像，未用 `jcmd` 核对堆/GC 实际值；B4-02 补 | B4-02 |
| GC 日志文件采集 | argv 已生成（JDK21 `-Xlog:gc*`、JDK8 `-Xloggc`），但日志目录挂载/回读未做 | B4-03 |
| 实例页 UI | 向导配置组仍未接 `/lab/instances/**`；B4-04 接应用/重启/回滚与状态 | B4-04 |
| 未设 maxPoolSize 的预算 | 未显式设置时只告警不计线程栈；显式值计入 | 记录 |
| JDK8 日志轮转 | 当前只生成 `-Xloggc:<path>`，未生成 `-XX:+UseGCLogFileRotation`/大小/数量 | B4-03 |
| 池保存与 run | pool-only 修改在 run 中允许保存，但本步不会生效；B4-02 决定是否收紧 | 记录 |
| 文档/claims | B4-01 断言 2 条已加；B4-02/03/04 后续补 | 记录 |

# 待定事项和注意事项

1. **下一步**：B4-02 受控重建（固定模板/Docker API）→ 健康/上游/Prometheus/真实探针 → 失败回滚 → 稳定期；run 中禁改启动参数。
2. **重建与重载**：重建任一 upstream 容器后必须 `docker exec jl-nginx nginx -s reload`；本步已实测 `GET /lab/pool` 与 `PUT/effective`。
3. **数据状态**：`lab_instance_config` 有 app-1 的一条 DRAFT（目标 G1/384m/池 2-4-10/GC 日志开启），**未应用**；容器仍是 `-Xmx512m`。A/B 与对账历史 run 保留。
4. **Windows 编码**：中文 JSON 用 UTF-8 文件 + `--data-binary`。
5. **安全边界**：配置 API 不执行任意命令/挂载；重建实现（B4-02）必须沿用固定模板和 `lab=java-learn` 白名单。

# 附录

## 真机证据（2026-10-29）

- `GET /lab-api/chaos/app/app-1/lab/pool` → `core=4,max=8,queue=50,keepAlive=60,AbortPolicy,rejectedTotal=0`。
- ledger Flyway `1,2,3,4`，`lab_instance_config` 存在。
- `PUT /lab/instances/app-1/config` → `DRAFT`、revision 1、预算 `708/768`，argv：
  `-Xms256m -Xmx384m -XX:MaxMetaspaceSize=128m -XX:MaxDirectMemorySize=64m -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:G1NewSizePercent=5 -XX:G1MaxNewSizePercent=60 -XX:G1HeapRegionSize=4m -XX:InitiatingHeapOccupancyPercent=45 -Xlog:gc*:file=/app/logs/gc.log:time,level,tags:filecount=3,filesize=10m -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/logs/heap.hprof -Dlab.sample=1`。
- `GET .../effective` → `JAVA_TOOL_OPTIONS=-Xmx512m`、imageId `408b427b…`、`readback=OK`（目标 vs 生效分离）。
- 拒绝样本：`xmx=700` → `1024 MB > memLimit 768`；`-javaagent:/tmp/x.jar` → 400；`gcLogPath=/etc/passwd` → 400（escape allow-listed root）。
- JDK8 validate：`limitedObservability=true`，argv 使用 `-Xloggc:/app/logs/gc8.log`。

## 命令

```bash
cd code && mvn -o test                                   # 1385 tests / 0 fail
bash code/lab/scripts/smoke.sh                           # 7/7
bash code/lab/scripts/verify.sh                          # 109/1/0
```

## 关键文件

- app：`admission/config/{BusinessPoolConfig,BusinessPoolProperties}.java`、`admission/app/SeckillOrderConsumer.java`、`lab/api/LabBusinessPoolController.java`、`catalog/cache/TwoLevelCacheSettings.java`、`lab/cache/preheat/{LabCachePreheatProperties,CachePreheatConfig}.java`
- controller：`instance/*`、`gateway/{ContainerGateway,ContainerFacts,DockerContainerGateway}.java`、`appproxy/AppProxyService.java`、`db/ledger/V4__instance_config.sql`、`application.yml`
- 配置：`code/lab/docker/compose.base.yml`、`.env.example`（BUSINESS_POOL_*、LAB_CACHE_L1_*、LAB_INSTANCE_*）
