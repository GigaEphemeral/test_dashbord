# 步骤 52 · 线程快照实栈验收（宿主机挂载 + Grafana annotation + 保留策略）

> 步骤交接 · §9.3 线程快照在真实 compose 栈上的落地与证据
> 日期：2026-10-27
> 代码基线：`8fdc0a2`（`feature/ha-lab`，step-51 之后）
> 交付：`code/lab/docker/compose.base.yml`（仅 app 层 volume/env）、`ProcessJvmCommandExecutor` 与 `ThreadSnapshotService` 两处缺陷修复、`ThreadSnapshotServiceTest` 断言、`claims.yml` 新断言、本文档。
> 验证：`cd code && JAVA_HOME="C:\Program Files\Java\jdk-21.0.12.1" mvn -pl services/seckill-app-jdk21 -am test` → **434 tests, 0 failures / 0 errors / 0 skipped, BUILD SUCCESS**；`node code/lab/scripts/check-doc-claims.mjs` 全 PASS。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

<!-- live-capture-verified: thread-snapshot-live-capture host=doc/process/snapshots/20261027-050153-652_app-1 files=5 trigger=freeze annotation=75 -->

## 1. 挂载与环境变量契约

### 1.1 compose（只改 app 层）

`code/lab/docker/compose.base.yml` 的 `x-app-common` 增加一个 bind mount，`x-app-env` 增加快照与 annotation 变量：

```yaml
x-app-common: &app-common
  volumes:
    - ../../../doc/process/snapshots:/app/lab-snapshots

x-app-env: &app-env
  LAB_THREAD_SNAPSHOT_DIR: /app/lab-snapshots
  LAB_THREAD_SNAPSHOT_MAX_COUNT: ${LAB_THREAD_SNAPSHOT_MAX_COUNT:-20}
  LAB_THREAD_SNAPSHOT_MAX_AGE_SECONDS: ${LAB_THREAD_SNAPSHOT_MAX_AGE_SECONDS:-604800}
  LAB_THREAD_SNAPSHOT_GRAFANA_URL: ${LAB_THREAD_SNAPSHOT_GRAFANA_URL:-http://grafana:3000}
  GRAFANA_ADMIN_USER: ${GRAFANA_ADMIN_USER}
  GRAFANA_ADMIN_PASSWORD: ${GRAFANA_ADMIN_PASSWORD}
```

- **相对路径是三级的 `../../../`**，不是任务草稿里的 `../../`：compose 文件位于 `code/lab/docker/`，`../../doc/...` 会解析成不存在的 `code/doc/process/snapshots`；容器内看到的是仓库根目录的 `doc/process/snapshots`。
- **属性名是 `app.lab.thread.snapshot.root-dir`，环境变量是 `LAB_THREAD_SNAPSHOT_DIR`**（不是草稿里的 `..._ROOT`）；`application.yml` 是唯一默认值来源，本次没有改配置键。
- `app` 容器以 `uid=100(app)` 运行；Windows/Docker Desktop 的 9p bind mount 实测为 `drwxrwxrwx`，非 root 可直接写，无需改 `user:` 或 `chown`。
- `doc/process/snapshots/` 早已在 `.gitignore`（`# Runtime artifacts` 段），本次无需新增；快照是运行期数据，不提交。
- annotation 的 URL 默认为 `http://grafana:3000`，凭据沿用 `.env` 里 chaos-controller 已在用的 `GRAFANA_ADMIN_USER/PASSWORD`；应用只读环境变量，日志与 annotation 文本都不含凭据。

容器内实测：

```text
$ docker exec jl-app-1 sh -c 'id; command -v jcmd; printenv LAB_THREAD_SNAPSHOT_DIR'
uid=100(app) gid=101(app) groups=101(app)
/opt/java/openjdk/bin/jcmd
/app/lab-snapshots
$ docker exec jl-app-1 jcmd 1 Thread.print -l | head -5
1:
2026-10-27 04:55:32
Full thread dump OpenJDK 64-Bit Server VM (21.0.12.1+1-LTS mixed mode, sharing):
```

`jcmd` 自 attach（同容器、同 uid、pid=1）成立；运行镜像确为 `eclipse-temurin:21-jdk-alpine`（`/opt/java/openjdk/bin` 下有 `jcmd`）。

**环境注记（仅本机、不落仓库）**：本机 Docker daemon 的出网被 `http.docker.internal:3128` 代理卡死，`docker pull` / `compose build` 拉基础镜像与 OTel agent 均超时；容器网络正常，因此用一次性 helper 容器按官方 amd64 manifest（`sha256:8a99cd…`，config `sha256:e2a900…`，layers `7b2295…/3cdcf9…/a297d6…/c2398a…/5ab974…`）下载并 `docker import` 成同名 `eclipse-temurin:21-jdk-alpine`，再用本地 `mvn package` 的 jar 与旧镜像里的 OTel agent 按仓库 Dockerfile 相同步骤组装 `java-learn-lab/seckill-app:jdk21`（临时 Dockerfile 只在 `tmp/`，未提交）。网络恢复后直接 `docker compose build app-1` 即可得到与仓库 Dockerfile 完全一致的镜像，仓库无需为此改动。镜像体积：JDK 基础层解压约 366MB，组装后的 app 镜像 `docker images` 显示 940MB。

## 2. 实抓文件（宿主机五件套）

通过 Nginx → chaos-controller 代理调用（`X-Lab-Token` 由 `.env` 读取，不回显）：

```bash
TOKEN=$(sed -n 's/^LAB_TOKEN=//p' code/lab/docker/.env)
curl -s -XPOST -H "X-Lab-Token: $TOKEN" \
  http://127.0.0.1:8080/lab-api/chaos/app/app-1/lab/thread/snapshot
```

响应：`captured=true, trigger=manual, directory=/app/lab-snapshots/20261027-050152-267_app-1, files=[5 个], durationMs=985`。

宿主机目录（`doc/process/snapshots/`，三份共 3.5MB）：

| 目录 | 场景 | thread-dump | heap-info | class-histogram | metadata | template |
|---|---|---|---|---|---|---|
| `20261027-050113-871_app-1` | max-age 演练留档 | 69,051 B | 741 B | 1,124,257 B | 644 B | 4,030 B |
| `20261027-050152-267_app-1` | 手动 `POST /snapshot` | 69,331 B | 741 B | 1,124,173 B | 644 B | 4,030 B |
| `20261027-050153-652_app-1` | `start → step → freeze` | 75,382 B | 743 B | 1,141,837 B | 664 B | 4,030 B |

字段 `metadata.json`（freeze 目录）逐字节选：

```json
"trigger" : "freeze",
"state" : "BLOCKED",
"stateCode" : 2,
"step" : 1,
"threadName" : "thread-lab-demo",
"pid" : 1,
"files" : [ "thread-dump.txt", "heap-info.txt", "class-histogram.txt", "metadata.json", "analysis-template.md" ],
"commands" : {
  "thread-dump.txt" : "jcmd 1 Thread.print -l",
  "heap-info.txt" : "jcmd 1 GC.heap_info",
  "class-histogram.txt" : "jcmd 1 GC.class_histogram"
}
```

线程 dump 含真实线程名与状态（freeze 目录）：

```text
"thread-lab-demo" #79 [177] daemon prio=5 os_prio=0 cpu=93.12ms elapsed=0.28s tid=0x000073ceb821ffe0 nid=177 waiting for monitor entry  [0x000073cead5a1000]
   java.lang.Thread.State: BLOCKED (on object monitor)
	at com.javalearn.lab.seckill.lab.thread.LifecycleDemoService.runBlockedStage(LifecycleDemoService.java:414)
```

手动目录同时含容器真实工作线程：

```text
"http-nio-8080-exec-1" #50 [80] daemon prio=5 os_prio=0 cpu=384.14ms elapsed=10.12s tid=0x000073ceb4889f20 nid=80 waiting on condition  [0x000073ceaf64b000]
```

堆与类直方图（freeze 目录，头部无 `Picked up JAVA_TOOL_OPTIONS` 噪声）：

```text
1:
 def new generation   total 48768K, used 29796K [0x00000000e0000000, 0x00000000e34e0000, 0x00000000eaaa0000)
 tenured generation   total 108120K, used 64869K [0x00000000eaaa0000, 0x00000000f1436000, 0x0000000100000000)

 num     #instances         #bytes  class name (module)
-------------------------------------------------------
   1:        187051       16432472  [B (java.base@21.0.12.1)
   2:          7393        4757632  [Ljdk.internal.vm.FillerElement; (java.base@21.0.12.1)
```

`analysis-template.md` 与 jar 内资源逐字节一致（`ThreadSnapshotServiceTest.classpathTemplateMatchesTheRepositoryCopy` 仍绿）。

**不提交样例**：单份约 1.2MB（直方图约 1.1MB）且含运行期线程/类名，超过“小且可脱敏”的合理范围；按上面的 curl 命令在本机随时可再生成，`.gitignore` 已覆盖整个目录。如需在无栈环境复现，至少要有 JDK 21 的 `jcmd` 与可写目录，或直接跑已提交的 `ThreadSnapshotServiceTest`（canned 输出，不依赖 Docker）。

**本步修复的两个逻辑缺陷**：

1. `metadata.json` 的 `files` 原来只列 3 个 jcmd 产物（`metadata.json` 与 `analysis-template.md` 尚未加入 `written` 就被序列化）；现改为写全 5 个文件，`ThreadSnapshotServiceTest` 增加逐项断言。
2. `jcmd` 子进程继承容器的 `JAVA_TOOL_OPTIONS=-Xmx512m`，导致每份产物开头都多一行 `Picked up JAVA_TOOL_OPTIONS: -Xmx512m`；`ProcessJvmCommandExecutor` 现在从子进程环境中移除 `JAVA_TOOL_OPTIONS`、`_JAVA_OPTIONS`、`JDK_JAVA_OPTIONS`，三份文件头部恢复为 `1:` + jcmd 原生内容（实测该行出现次数为 0）。

## 3. Grafana annotation 证据

app 以 `POST http://grafana:3000/api/annotations`（Basic Auth）写入，`time` 使用快照时刻 `capturedAtEpochMs`。宿主机 API 查询（凭据取自 `.env`，不打印）：

```bash
curl -s -u "$GRAFANA_ADMIN_USER:$GRAFANA_ADMIN_PASSWORD" \
  "http://127.0.0.1:3000/api/annotations?tags=snapshot&limit=20"
```

freeze 快照对应记录（逐字，仅 `avatarUrl` 为 Grafana 自带字段）：

```json
{
  "id": 75,
  "alertId": 0,
  "alertName": "",
  "dashboardId": 0,
  "dashboardUID": null,
  "panelId": 0,
  "userId": 0,
  "newState": "",
  "prevState": "",
  "created": 1793077314452,
  "updated": 1793077314452,
  "time": 1793077313651,
  "timeEnd": 1793077313651,
  "text": "thread-lab snapshot: instance=app-1 trigger=freeze state=BLOCKED(2) step=1 files=5 dir=20261027-050153-652_app-1",
  "tags": [
    "snapshot",
    "thread-lab",
    "app-1",
    "freeze"
  ],
  "login": "admin",
  "email": "admin@localhost",
  "avatarUrl": "/avatar/46d229b033af06a191ff2267bca9ae56",
  "data": {}
}
```

- `time == 1793077313651` 与 `metadata.json.capturedAtEpochMs` 完全一致，标记落在快照时刻而非 HTTP 完成时刻。
- 本次演练期间共 8 条 `snapshot` annotation（含修复前 1 条、保留策略 5 条、最终 manual/freeze 2 条）；annotation 失败只 WARN，不影响抓取。
- 默认 `LAB_THREAD_SNAPSHOT_GRAFANA_URL=http://grafana:3000`，无需改 `.env`。

## 4. 保留策略证据（真跑，不手工删）

所有演练都通过真实 `ThreadSnapshotService.prune()` 触发，宿主机目录与 `thread_lab_snapshot_retention_deleted_total` 双向核对。

**max-count（4 连抓，上限 3）**：临时以 `LAB_THREAD_SNAPSHOT_MAX_COUNT=3`、`MAX_AGE_SECONDS=3600` 重建 `app-1`，连续 4 次 `POST /snapshot`：

```text
1 /app/lab-snapshots/20261027-050037-072_app-1
2 /app/lab-snapshots/20261027-050038-245_app-1
3 /app/lab-snapshots/20261027-050039-346_app-1
4 /app/lab-snapshots/20261027-050040-361_app-1
```

事后宿主机只剩 `050038 / 050039 / 050040` 三份，最老的 `050037` 被删；指标：

```text
thread_lab_snapshot_retention_deleted_total{instance="app-1",...} 1.0
```

**max-age（3 份旧目录，上限 5 秒）**：再以 `LAB_THREAD_SNAPSHOT_MAX_COUNT=20`、`MAX_AGE_SECONDS=5` 重建 `app-1`，健康检查约 30 秒后抓 1 份 `20261027-050113-871_app-1`；此前 3 份均超过 5 秒，被年龄规则删除，宿主机只剩新目录；指标：

```text
thread_lab_snapshot_retention_deleted_total{instance="app-1",...} 3.0
```

**恢复**：随后不带覆盖参数重建 `app-1`，容器内 `printenv` 为 `count=20 age=604800 root=/app/lab-snapshots`。最终宿主机保留 3 份：`050113`（年龄演练留档）+ `050152`（manual）+ `050153`（freeze），共 3.5MB。

## 5. 剩余事项

- **JDK 8 的 `jstack`/`jmap` 对齐未做**：`seckill-app-jdk8` 仍只有 JDK 21 的同构接口，没有 `jstack <pid>` / `jmap -histo` 的 sink 实现；双 JDK 对比要补一套实现并复用 `metadata.json` / annotation 契约。
- **D6 面板仍无快照 annotation/计数可视化**：`thread_lab_snapshot_*` 已导出，`snapshot`/`thread-lab` annotation 也已在 Grafana 里，但 D6 没有对应 panel（属 #34/D6），本次未动 dashboard。
- **annotation 仍为同步 best-effort**：freeze 最坏会等 3 次 `jcmd`（每次 ≤5s）+ annotation ≤3s；教学规模可接受，压测下建议移到单线程 daemon 执行器（会牺牲“返回即文件就绪”）。
- **容器内手工操作口径**：演示时仍可用 `docker exec jl-app-1 jcmd 1 Thread.print -l`，但文件已直接落在宿主机 `doc/process/snapshots/`，不再需要 `docker cp`。
- **环境限制**：本机 daemon 代理导致官方 `compose build` 无法完成；本地镜像是按官方层 digest 导入组装的（见 §1 注记）。网络正常后应重跑一次 `docker compose build app-1` 验证仓库 Dockerfile 的端到端构建，这一步尚未在本机完成。
- **恢复状态（已验证）**：lifecycle `reset()` → `state=NEW, started=false`；`GET /lab/thread/state` → `data=[]`（无活跃故障）；`jl-app-1/2/3`、`jl-nginx`、`jl-chaos-controller` 均 `healthy`；`http://127.0.0.1:8080/health` → 200。
