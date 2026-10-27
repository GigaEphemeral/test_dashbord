# 步骤 53 · JDK 8 jstack/jmap 线程快照（镜像升级 + 真机验收）

> 步骤交接 · §9.3 快照在 JDK 8 外壳上的对等实现
> 日期：2026-10-27
> 代码基线：`d62dc31`（`feature/ha-lab`，step-52 之后）
> 交付：`code/services/seckill-app-jdk8/**`（新 `lab` 包、配置、Dockerfile）、`code/lab/docker/compose.base.yml`（仅 jdk8 服务）、`code/lab/docclaims/claims.yml`、本文档。
> 验证：`cd code && JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1" mvn -pl services/seckill-app-jdk8 -am clean test` → **46 tests, 0 failures / 0 errors（11 基线 + 35 新增），BUILD SUCCESS**；`node code/lab/scripts/check-doc-claims.mjs` → 18/18 PASS。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

<!-- live-capture-verified: jdk8-snapshot-live-capture host=doc/process/snapshots/20261027-061239-395_app-jdk8 files=5 trigger=manual jvm=1.8.0_504 -->

## 1. 镜像变更与体积

### 1.1 运行时从 JRE 换成 JDK

`code/services/seckill-app-jdk8/Dockerfile` 的运行阶段：

```diff
-# JRE 8 (not JDK): ... the snapshot features that need jcmd live only in the JDK 21 shell.
-FROM eclipse-temurin:8-jre-alpine
+# design 9.3 requires jstack/jmap inside the container ...
+FROM eclipse-temurin:8-jdk-alpine
```

构建阶段未动（仍是 `maven:3.9-eclipse-temurin-21` + `--release 8`），入口参数未动。容器的 `user`/`ENTRYPOINT` 与 JDK 21 外壳保持同构。

容器内实测（`jl-seckill-app-jdk8`）：

```text
uid=100(app) gid=101(app)
/opt/java/openjdk/bin/jstack
/opt/java/openjdk/bin/jmap
/opt/java/openjdk/bin/jcmd
java version "1.8.0_504"  (Temurin, build 25.504-b01)
```

### 1.2 本机拉镜像受阻与本地组装（如实记录）

本机 Docker daemon 出网经 `http.docker.internal:3128`：

```text
$ docker pull eclipse-temurin:8-jdk-alpine
8-jdk-alpine: Pulling from library/eclipse-temurin
9c29415c2cf9: Pulling fs layer        # 长时间无进展，host 访问 auth.docker.io 返回 000
```

因此先按官方内容本地组装，保证真机演练不被网络阻塞：

1. 从 Adoptium 官方 API 取得 8u504b01 alpine 包的 sha256 `f76b7c98a593f15bfbc9042866141185d63aa87e0afde3b7e48d2c4789a2def5`；
2. 经 TUNA 镜像下载 `OpenJDK8U-jdk_x64_alpine-linux_hotspot_8u504b01.tar.gz`（101,404,284 B），`sha256sum` 与上值一致；
3. 用本地已有的 `alpine:3.19` 写临时 Dockerfile（`tmp/jdk8-image/Dockerfile.base`，**未提交**）解压到 `/opt/java/openjdk`，打 tag `eclipse-temurin:8-jdk-alpine`；
4. 用本地 `mvn -DskipTests package` 的 jar 与旧镜像里的 OTel agent（`/app/otel/opentelemetry-javaagent.jar`）按仓库 Dockerfile 相同步骤组装 `java-learn-lab/seckill-app:jdk8`（`tmp/jdk8-image/Dockerfile.app`，未提交）。

之前挂起的 `docker pull` 后来在后台完成，tag 已指向官方镜像。当前本地镜像的首个 rootfs 层与官方一致（`sha256:75c137aedfb2760f88629236cb7b797630aed0c3d91d626d1d1bb694878fc2ba`），最终镜像 `java-learn-lab/seckill-app:jdk8`（`2adbb43705ef`）就是在这个官方 base 上重建的；**最后一份证据包 `20261027-061239-395_app-jdk8` 由官方 8-jdk-alpine 运行产生**。更早两份由同一 Dockerfile、同一 tarball 的本地重建 base 产生，产物格式无差异。

### 1.3 体积代价（`docker images` DISK USAGE）

| 镜像 | 磁盘占用 | 说明 |
|---|---|---|
| 旧 `seckill-app:jdk8`（JRE 8） | 469MB | step-52 前 |
| 新 `seckill-app:jdk8`（JDK 8） | 521MB | +52MB（约 +11%） |
| `eclipse-temurin:8-jdk-alpine` | 274MB | 压缩下载 73.8MB；JDK 层 152MB |

多出的 ~52MB 只为 `jstack`/`jmap`/`jcmd` 付一次，且只作用于可选的 `dual-jdk` profile；默认 16 服务栈不受影响。

## 2. 端点、鉴权与配置

### 2.1 端点（Boot 2.7 风格，`Result` 信封）

| 方法/路径 | 作用 | 说明 |
|---|---|---|
| `POST /lab/snapshot` | 抓一份五件套，返回 metadata 视图 | 失败/关闭仍 200，`captured=false` |
| `GET /lab/snapshot` | 列出磁盘上的 bundle | 目录、UTC 时间、trigger、每文件大小，最新在前 |

成功响应（真机逐字）：

```json
{"code":"0","message":"OK","data":{"captured":true,"trigger":"manual",
 "directory":"/app/lab-snapshots/20261027-061239-395_app-jdk8",
 "files":["thread-dump.txt","heap-info.txt","class-histogram.txt","metadata.json","analysis-template.md"],
 "durationMs":1425,"capturedAtEpochMs":1793081560818,"error":null},"success":true}
```

`GET /lab/snapshot` 返回的单项包含 `name/directory/timestamp/trigger/totalBytes/files[{name,bytes}]`，与 JDK 21 的 bundle 字段同构，D6 可共用渲染逻辑。

### 2.2 鉴权

JDK 8 外壳此前没有 `LabTokenFilter`。本步移植了 JDK 21 的同款过滤器（`javax.servlet` 版）：

- `X-Lab-Token` 与 `app.lab.token`（`${LAB_TOKEN:lab-token}`，与 chaos-controller、JDK 21 同一个 `.env` 值）做 `MessageDigest.isEqual` 常量时间比较；
- 只由 `FilterRegistrationBean` 注册到 `/lab/*`，`/api/**` 不受影响；
- 失败返回 401 + 共享错误码 `40101`，不打印任何 token。

真机（经 `jl-net`，从一次性 `maven:3.9-eclipse-temurin-21` 容器执行 `curl`，token 从 `.env` 读取且不回显）：

```text
GET  /lab/snapshot            → {"code":"40101","message":"Unauthorized","data":null,"success":false}  HTTP 401
POST /lab/snapshot (错 token)  → {"code":"40101",...}                                                    HTTP 401
POST /lab/snapshot (对 token)  → captured=true / files=5                                                  HTTP 200
```

Nginx 不代理该路径：`curl http://127.0.0.1:8080/lab/snapshot` 返回静态控制台 `index.html`（468 B，`Content-Type: text/html`），响应中不含 `captured` 字段。与 step-52 相同，运行时路径只能从 `jl-net` 内部访问。

### 2.3 配置（`application.yml` + `@ConfigurationProperties`，全部 `${ENV:default}`）

| 键 | 环境变量 | 默认 | 校验 |
|---|---|---|---|
| `app.lab.token` | `LAB_TOKEN` | `lab-token` | 非空，缺失即启动失败 |
| `app.lab.snapshot.enabled` | `LAB_SNAPSHOT_ENABLED` | `true` | — |
| `app.lab.snapshot.root-dir` | `LAB_SNAPSHOT_DIR` | `/app/lab-snapshots` | enabled 时非空 |
| `app.lab.snapshot.max-count` | `LAB_SNAPSHOT_MAX_COUNT` | `20` | ≥1 |
| `app.lab.snapshot.max-age-seconds` | `LAB_SNAPSHOT_MAX_AGE_SECONDS` | `604800` | ≥0（0=关闭年龄规则） |
| `app.lab.snapshot.command-timeout-ms` | `LAB_SNAPSHOT_COMMAND_TIMEOUT_MS` | `5000` | >0 |
| `app.lab.snapshot.jmap-mode` | `LAB_SNAPSHOT_JMAP_MODE` | `histo` | `histo`/`histo-live` |
| `app.lab.snapshot.jstack-path` | `LAB_SNAPSHOT_JSTACK_PATH` | `jstack` | enabled 时非空 |
| `app.lab.snapshot.jmap-path` | `LAB_SNAPSHOT_JMAP_PATH` | `jmap` | enabled 时非空 |
| `app.lab.snapshot.jcmd-path` | `LAB_SNAPSHOT_JCMD_PATH` | `jcmd` | enabled 时非空 |
| `app.lab.snapshot.template-path` | `LAB_SNAPSHOT_TEMPLATE_PATH` | `doc/process/snapshot-analysis-template.md` | enabled 时非空，抓取时不存在则本次失败 |

**`jmap` 模式取舍**：默认 `-histo`（不触发 GC）。`-histo:live` 会先做一次 Full GC，改变你正要观察的堆，所以只作为显式 opt-in（`LAB_SNAPSHOT_JMAP_MODE=histo-live`），用于“这些对象到底还活着吗”的排查。测试与真机都固定 `histo`。

**为什么 heap-info 用 `jcmd`**：JDK 8 的 `jmap -heap` 已被移除。8u504 上实测 `jmap -heap 1` 直接以 exit 1 打印 usage；`jcmd 1 help` 里仍有 `GC.heap_info`。因此：

```text
thread-dump.txt      jstack -l 1
heap-info.txt        jcmd 1 GC.heap_info      # 与 JDK 21 的 heap-info 同一诊断命令，可直接对比
class-histogram.txt  jmap -histo 1
```

`metadata.json.commands` 逐条记录真实 argv，`toolset` 为 `jstack+jmap+jcmd`，不掩盖这一点。

### 2.4 metadata 契约

与 JDK 21 共用：`instance`、`trigger`、`capturedAt`、`capturedAtEpochMs`、`jvmVersion`、`jvmVendor`、`uptimeMs`、`pid`、`files`（完整 5 件）、`commands`。JDK 8 额外/差异：

- `jvmVersion=1.8.0_504`（`java.version`，便于 D7 直接比较），`vmVersion=25.504-b01`（`RuntimeMXBean.getVmVersion()` 原值）；
- `toolset=jstack+jmap+jcmd`、`jmapMode=histo`、`gcCollectors=Copy,MarkSweepCompact`（`-Xmx512m`/768m 限额下 HotSpot 选 Serial，collector 名在 metadata 里显式给出，因为 `jmap -heap` 不可用）；
- 没有 lifecycle 的 `state/stateCode/step/threadId/threadName`（JDK 8 外壳没有单线程实验室）；消费方需容忍缺失字段。

pid 来源：Java 8 没有 `ProcessHandle`，用 `ManagementFactory.getRuntimeMXBean().getName()` 的 `"<pid>@<host>"` 前缀解析（真机 pid=1）。

## 3. 宿主机文件证据

### 3.1 最终 bundle（`doc/process/snapshots/`，宿主机直接可见）

`20261027-061239-395_app-jdk8`（`POST /lab/snapshot`，官方 `8-jdk-alpine` 运行）：

| 文件 | 字节 |
|---|---|
| `thread-dump.txt` | 31,380 |
| `heap-info.txt` | 772 |
| `class-histogram.txt` | 715,021 |
| `metadata.json` | 669 |
| `analysis-template.md` | 4,030 |

`du -sb` = 751,872 B。目录名格式 `<UTC yyyyMMdd-HHmmss-SSS>_app-jdk8`，与 JDK 21 的 `<...>_app-1` 并排。

当前宿主机共有 7 份 bundle：3 份 JDK 21（step-52 证据，已从备份恢复）+ 4 份 JDK 8（`061011/061145/061148/061239`，后两份为保留演练、最后一份为最终证据）。

### 3.2 真实 Java 8 线程

```text
2026-10-27 06:12:39
Full thread dump OpenJDK 64-Bit Server VM (25.504-b01 mixed mode):

"http-nio-8080-exec-1" #32 daemon prio=5 ... nid=0x9a waiting on condition [0x000072f2437ff000]
   java.lang.Thread.State: WAITING (parking)
	at sun.misc.Unsafe.park(Native Method)
```

dump 共 47 个线程，含 `http-nio-8080-exec-1..10`、`Catalina-utility-1/2`、`seckill-jdk8-pool housekeeper`、`OkHttp otel-collector`、`BatchSpanProcessor_WorkerThread-1`、`DestroyJavaVM`。

**与验收清单的偏差（真实发现，不修饰）**：dump 里没有字面量 `"main"` 线程。Spring Boot 2.7 的 `main()` 在 `SpringApplication.run()` 返回后结束，JVM 靠 Tomcat 等非 daemon 线程存活，原 main 线程被 VM 改名为 `DestroyJavaVM`：

```text
"DestroyJavaVM" #44 prio=5 ... nid=0x7 waiting on condition [0x0000000000000000]
   java.lang.Thread.State: RUNNABLE
```

JDK 21 外壳同样如此（step-52 的样本也没有 `main`）。要在 dump 里看到 `main`，只能让 main 阻塞（Boot 3.2+ 的 `spring.main.keep-alive` 在 2.7 不存在），这属于启动语义变更，本步不做。

### 3.3 堆与类直方图

```text
 def new generation   total 21376K, used 9585K [0x00000000e0000000, 0x00000000e1730000, 0x00000000eaaa0000)
 tenured generation   total 47380K, used 45296K [0x00000000eaaa0000, 0x00000000ed8e5000, 0x0000000100000000)
 Metaspace       used 78063K, capacity 83286K, committed 83456K, reserved 1120256K
```

```text
 num     #instances         #bytes  class name
----------------------------------------------
   1:        144082       17137376  [C
   2:         15048       10236384  [B
   3:         14675        3660608  [I
   4:        127674        3064176  java.lang.String
```

无 `Picked up JAVA_TOOL_OPTIONS` 噪声：执行器像 JDK 21 一样从子进程环境移除了 `JAVA_TOOL_OPTIONS`/`_JAVA_OPTIONS`/`JDK_JAVA_OPTIONS`（真机三份文件首行均为 `1:`/时间戳/表头）。

### 3.4 metadata（最终包逐字节节选）

```json
"instance" : "app-jdk8",
"trigger" : "manual",
"toolset" : "jstack+jmap+jcmd",
"jvmVersion" : "1.8.0_504",
"vmVersion" : "25.504-b01",
"jvmVendor" : "Temurin",
"gcCollectors" : "Copy,MarkSweepCompact",
"pid" : 1,
"jmapMode" : "histo",
"files" : [ "thread-dump.txt", "heap-info.txt", "class-histogram.txt", "metadata.json", "analysis-template.md" ],
"commands" : {
  "thread-dump.txt" : "jstack -l 1",
  "heap-info.txt" : "jcmd 1 GC.heap_info",
  "class-histogram.txt" : "jmap -histo 1"
}
```

`analysis-template.md` 4,030 B，与仓库 `doc/process/snapshot-analysis-template.md` 同一文件（compose 只读挂载，服务复制进 bundle），没有在 resources 里 fork 第二份。

### 3.5 指标（同一套名字，`jvm_version` 区分运行时）

```text
thread_lab_snapshot_attempts_total{app="seckill-app-jdk8",instance="app-jdk8",jvm_version="1.8.0_504",trigger="manual"} 1.0
thread_lab_snapshot_success_total{...trigger="manual"} 1.0
thread_lab_snapshot_failures_total{...trigger="manual"} 0.0
thread_lab_snapshot_duration_seconds_count{...trigger="manual"} 1.0
thread_lab_snapshot_retention_deleted_total{...} 0.0
```

JDK 8 没有 `freeze` 触发器，只有 `manual`；`jvm_version` 来自既有的 `management.metrics.tags`，D6 不需要新标签。

## 4. 保留策略证据

与 JDK 21 同一套规则：只识别 `^\d{8}-\d{6}-\d{3}_.+$` 目录，先年龄、后数量，`current` 永不删；无关目录不碰。**两个运行时共用宿主根目录，因此 max-count 是跨运行时全局计数**（JDK 21 的实现本来也是全根计数，这里保持一致）。

演练前宿主机 5 份：3 份 `app-1`（step-52）+ 2 份 `app-jdk8`（`060516`、`061011`）。为不丢失 step-52 证据，先把 3 份 `app-1` 复制到 `tmp/step52-snapshots-backup/`，演练后原样恢复。

以 `LAB_THREAD_SNAPSHOT_MAX_COUNT=3`（`LAB_THREAD_SNAPSHOT_MAX_AGE_SECONDS` 仍为 604800）重建 `jl-seckill-app-jdk8`，连续两次 `POST /lab/snapshot`：

```text
1 /app/lab-snapshots/20261027-061145-016_app-jdk8
2 /app/lab-snapshots/20261027-061148-477_app-jdk8
```

宿主机只剩 3 份：

```text
20261027-061011-573_app-jdk8
20261027-061145-016_app-jdk8
20261027-061148-477_app-jdk8
```

即最早的 `060516` 与 3 份 `app-1` 共 4 份被删，指标同步：

```text
thread_lab_snapshot_retention_deleted_total{app="seckill-app-jdk8",instance="app-jdk8",jvm_version="1.8.0_504"} 4.0
```

随后：恢复 `app-1` 备份 → 不带覆盖参数重建（容器内 `LAB_SNAPSHOT_MAX_COUNT=20`、`MAX_AGE_SECONDS=604800`）→ 再抓一份 `20261027-061239-395_app-jdk8`。默认栈 16 服务保持 healthy，演练后的 jdk8 服务已停止（容器 `exited`），bundle 保留在宿主机。

## 5. 剩余事项

- **D6 面板按运行时区分**：JDK 8 已导出与 JDK 21 同名的 `thread_lab_snapshot_*`，且带 `jvm_version="1.8.0_504"`；但 D6 目前只有 JDK 21 视角。后续 panel 建议 `sum by (jvm_version, trigger)`，并把 `freeze` 缺失视为正常（JDK 8 只有 manual）。本步未动 dashboard。
- **JDK 8 未做 Grafana annotation**：JDK 21 的 `HttpGrafanaSnapshotAnnotator` 用 `java.net.http`（Java 11+），JDK 8 若要对齐需另写 `HttpURLConnection` 版本并复用 `GRAFANA_ADMIN_*`。当前选择“只落文件 + 指标”，D6 可用 `thread_lab_snapshot_success_total` 的跳变代替 annotation；若要做，建议放进同一 `SnapshotMetrics` 的 `annotations_total{result}` 家族。
- **`jmap -histo:live` 的真实代价**：会先 Full GC，堆使用率与直方图都会变化，恰好污染“抓现场”的目的。默认 `histo`；只有确认泄漏对象存活时才 `LAB_SNAPSHOT_JMAP_MODE=histo-live`，并应在报告里注明该次抓取触发了 GC。
- **`jmap -heap` 在 8u 已移除**：heap-info 改用 `jcmd GC.heap_info`（与 JDK 21 同一命令）。如果教学上必须“只用 jstack/jmap”，备选是 `jstat -gc`，但它不打印 collector 名称且格式与 JDK 21 不可比，故未采用。
- **dump 里没有字面量 `main`**：Spring Boot 2.7 的 main 在启动完成后退出，原线程被 VM 改名为 `DestroyJavaVM`；JDK 21 外壳一致。验收时以 `http-nio-8080-exec-*` + `DestroyJavaVM` 为准，或另做“启动瞬间抓取”的演示。
- **官方 `compose build` 未在本机端到端跑通**：daemon 拉取仍受代理影响；本机镜像是“官方 manifest 内容 + 本地 jar/agent”组装的（§1.2）。网络恢复后应执行一次 `docker compose --profile dual-jdk build seckill-app-jdk8`，验证仓库 Dockerfile 的完整构建；本步的最终证据已由官方 base 产生的镜像给出。
- **恢复状态（已验证）**：`jl-seckill-app-jdk8` 已停止；`jl-app-1/2/3`、`jl-nginx`、`jl-mysql` 均 healthy；`http://127.0.0.1:8080/health` → 200，`/api/products/1` → 200；7 份 bundle（3 份 JDK 21 + 4 份 JDK 8）保留在 `doc/process/snapshots/`（gitignored）。
