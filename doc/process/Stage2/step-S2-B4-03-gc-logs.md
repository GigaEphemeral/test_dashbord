# 步骤交接：Stage2 B4-03 GC 日志与诊断采集

> 任务/步骤：Stage2 **B4-03**：GC 日志选项版本适配、可写路径、JDK8 轮转、容器内日志回读
> 日期：2026-10-29
> 分支 / HEAD：`feature/stage2-workbench` / `8a45c4a`（本步改动本地待提交）
> 关联任务：计划 B4-03；方案 §4.3/§4.5、§16.7；B4-02 交接
> 测试：chaos 定向 **20/0**（validator 13、logs service 5、logs controller 2）；B4-02 全量 812/0 为上一提交基线
> 运行状态：app-1 已回滚到基线（512m、池 4/8/50、GC 日志关闭）；`gc-app-1.log`（56KB）保留在 host snapshots；无 parked 容器
> 口径指针：2026-10-29 本文第 4/5 节是**当时**状态快照；缺口口径见 `doc/design/Stage1/details/README.md` §3.2。

# 当前进展

- GC 日志闭环完成：配置生成 argv（JDK21 `-Xlog:gc*`、JDK8 `-Xloggc` + `UseGCLogFileRotation`）、写入 app 已有的可写 snapshots bind、控制器经 `docker exec tail` 回读（不把日志目录挂进控制器）。
- 真机：apply 配置（xmx384、GC 日志开启）后 `/lab/instances/app-1/logs/gc?lines=20` 返回真实 GC 事件；日志文件 55,664 字节；rollback 回基线后文件保留，`gcLogEnabled=false` 时端点返回 DISABLED。
- 未做：UI 日志查看器（B4-04）、OOM dump 事件进 run 时间线（B4-04/B5）。

# 完成功能项

- `lab.instance.gc-log-root` 默认改为 **`/app/lab-snapshots`**（app 用户可写的现有 bind，避免新增挂载/权限）；默认路径 `gc-<instance>.log`、`heap-<instance>.hprof`。
- JDK21：`-Xlog:gc*:file=<path>:time,level,tags[:filecount][,filesize]`；JDK8：`-Xloggc:<path>` + `-XX:+UseGCLogFileRotation -XX:NumberOfGCLogFiles -XX:GCLogFileSize`（已有生成逻辑，路径校验同根目录）。
- `GET /lab/instances/{instance}/logs/gc?lines=N`：`OK / DISABLED / NO_CONFIG / NO_DATA / ERROR`；行数 1..max、内容按字节上限截断只保留尾部；路径必须位于 allow-list 根目录。
- 配置外置：`LAB_INSTANCE_GC_LOG_ROOT`、`GC_LOG_DEFAULT_LINES=200`、`GC_LOG_MAX_LINES=2000`、`GC_LOG_MAX_BYTES=262144`、`GC_LOG_TAIL_TIMEOUT_SECONDS=5`；`lab.instance.names` 增加 `seckill-app-jdk8`（B4-04 的 LIMITED 实例）。
- 测试：`InstanceLogsServiceTest`（disabled/no-config/OK/truncate/NO_DATA/path+lines 拒绝）、`InstanceLogsControllerTest`；validator 测试路径更新为新根目录。

# 设计逻辑

- **不新增日志卷**：复用 app 已挂载且 app 用户可写的 `/app/lab-snapshots`；控制器通过 `docker exec tail` 读取，避免把宿主机路径挂进控制器、也避免容器删除后日志随之消失。
- **版本适配而非字符串拼接**：JDK8 与 21 的日志参数由版本分支生成，路径只来自白名单根目录。
- **无数据语义**：文件不存在/为空/命令失败都返回 `NO_DATA` 与原因，不伪造内容；配置关闭明确 `DISABLED`。
- **回滚不删日志**：日志是证据，回滚只换容器；文件保留供报告引用。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| UI 查看器 | 实例页还没有 GC 日志面板；端点已就绪 | B4-04 |
| OOM dump 时间线 | `HeapDumpOnOutOfMemoryError` 参数已生成，dump 事件未写 run 时间线 | B4-04/B5 |
| 日志采集边界 | 只回读最近 N 行与字节上限；未做归档/下载文件 | 记录 |
| JDK8 真机 | 参数生成与 LIMITED 边界有测试，dual-jdk 容器未在本步真机跑 | B4-04 |
| 全量回归 | 本步只跑定向测试；批次收口重跑 chaos/reactor | B4 收口 |

# 待定事项和注意事项

1. 下一步：B4-04 实例页（应用/重启/回滚、目标 vs 生效、GC 日志、jdk-matrix）→ B4 收口审核。
2. 现场：app-1 基线 512；`doc/process/Stage1/snapshots/gc-app-1.log` 是 B4-03 证据，保留。
3. 路径：GC 日志/堆转储必须落在 `LAB_INSTANCE_GC_LOG_ROOT` 下；换根目录需同时改 compose/env 与存储配置。

# 附录

## 真机证据

- apply（GC 日志开启）：9 项检查 PASS，`-Xlog:gc*:file=/app/lab-snapshots/gc-app-1.log:time,level,tags:filecount=3,filesize=10m`。
- `GET /lab/instances/app-1/logs/gc?lines=20` → `OK`，内容含 `GC(35) Pause Cleanup`、`Concurrent Mark Cycle`；文件 55,664 字节。
- rollback → `ROLLED_BACK`，9 项 PASS，`JAVA_TOOL_OPTIONS=-Xmx512m`，GC 日志关闭，文件保留。

## 关键文件

- controller：`instance/{InstanceLogsService,InstanceLogsController,InstanceLogsView}.java`、`InstanceConfigProperties/Config`、`application.yml`
- 配置：`compose.base.yml`、`.env.example`（GC 日志键、`LAB_INSTANCE_NAMES` 含 jdk8）
- 证据：`doc/process/Stage1/snapshots/gc-app-1.log`
