# 步骤交接：S2-B0-08 镜像身份最小记录 + MySQL 探针与故障目标分离

> 任务/步骤：Stage2 B0-08 —— 按**最小闭环**（确认 #8，方案 §10.4）把关键容器的 imageTag + imageId 写进 run manifest/报告，并把 MySQL 集群探针容器与可杀故障目标强制分离
> 日期：2026-10-28
> 分支 / HEAD：基线 feature/stage2-workbench（合并点 b196e29 已含 B0-03/B0-06），worktree agent-general-purpose-0fb03d73；本步已有提交 38b9449（切片 A：镜像身份记录）、e19205c（切片 B：探针隔离），本次收尾提交见附录
> 关联任务：B0 收口闸门；Stage1 遗留 P0#3（官方 build 溯源证据）以本口径收口，完整溯源进 Future（方案 §15）
> 测试 / verify：`mvn -f code/pom.xml -pl services/chaos-controller -am test` → chaos-controller **296 tests, 0 failures, 0 errors**（EXIT=0，BUILD SUCCESS）；`bash code/lab/scripts/test-image-identity.sh` → 26 项全 PASS；`node code/lab/scripts/bench-report.test.js` → 8/8；`bash -n` 四脚本全过；未跑真机
> 运行状态：仅脚本 + 单测层交付；真机 db-failover 演练（`mysql_cluster_probe_up` 恒 1）与 Docker 实况解析未执行
> 口径指针：本文第 4/5 节是当时的状态快照，当前缺口口径以 Stage2 计划文档为准

# 当前进展

- 切片 A（38b9449）已在位并有行为：`image-identity.sh` 在启动/重建后读一次 `docker inspect --format '{{.Image}}'` + `docker image inspect --format '{{.RepoTags}}'`，把 tag/id 写进 `image-identity.json` → bench.sh 的 run 归档与 `manifest.json.images`、tune.sh 的结果说明表格；重建路径保持显式 `--force-recreate`。
- 切片 B（e19205c）已在位：`ProbeTargetGuard` 在**任何 Docker 调用之前**拒绝对探针容器执行破坏性动作，配 `LabErrorCode.PROBE_TARGET_PROTECTED` → **403 / 40302**。
- 本次收尾（接手前任未提交的半成品）：容器解析从硬编码 `jl-` 前缀改为 **compose 逻辑服务名**，三级解析链 + `resolvedBy` 取证字段；修掉前任留下的一个真实解析缺陷（见「设计逻辑」第 1 条，实测 2 项自测 FAIL → 修复后全 PASS）；bench.sh/tune.sh 同步改为传逻辑服务名与 `--project`；自测新增 8 项覆盖逻辑名/标签栈/错误 project 回退。
- 三个脏脚本处置：`image-identity.sh` **完成**（保留前任解析链，修字段错位 + 空字段防护）、`bench.sh` **完成**（逻辑名清单 + `IMAGE_IDENTITY_PROJECT` 透传，均为前任已写内容，核对后保留）、`tune.sh` **完成**（逻辑名清单 + project 透传 + 结果表改为显示解析后的真实容器名 + 两处过时注释/帮助文本回正）。无一回退重写。
- 合并：`git merge feature/stage2-workbench` 无冲突（b196e29），与主分支 B0-03/B0-06 改动零重叠。

# 完成功能项

## A. 镜像身份最小记录（最小闭环边界见「设计逻辑」末条）

- `code/lab/scripts/image-identity.sh`
  - 逻辑名解析链（新增，替代硬编码 `jl-`）：`真实容器名/id` → `jl-<spec>` 别名（lab 栈 `container_name: jl-app-1`）→ `com.docker.compose.service` 标签（无 `container_name` 的栈由 Compose 加项目目录前缀，此时只有服务标签可用）。
  - `IMAGE_IDENTITY_PROJECT`（可 `--project` / `--project=`）用于收窄标签查询；查不到时**无条件再做一次不带 project 的标签查询**，所以 project 写错不会静默丢容器。
  - 每条记录新增 `requested` / `container`（解析到的真实容器名）/ `resolvedBy`（`container|labAlias|composeService|unresolved|noDocker`）；顶层新增 `nameResolution`、`composeProject`。原 `imageTag`/`imageId`/`state`/`status`/`schemaVersion`/`dockerAvailable`/`note` 语义不变。
  - 降级契约不变：无 docker、daemon 不可达、容器缺失一律 exit 0，字段落 `unavailable`/`missing`，每项一行 WARN；未解析到的行仍保留请求名（行可归属）。
- `code/lab/scripts/bench.sh`：`IMAGE_IDENTITY_CONTAINERS` 默认改为逻辑服务名 `app-1 app-2 app-3 chaos-controller`（对照 `compose.base.yml` 的真实服务名），调用时注入 `IMAGE_IDENTITY_PROJECT="$COMPOSE_PROJECT"`（默认 `java-learn-lab`）；帮助文本同步。记录仍写 `$RUN_DIR/image-identity.json` 并以 `--image-identity` / `--image-identity-containers` 转交 bench-report.js。
- `code/lab/scripts/tune.sh`：`record_image_identity()` 缺省清单由 `printf 'jl-%s '` 拼接改为直接用 `APP_SERVICES[*]`（逻辑服务名）；同样注入 `IMAGE_IDENTITY_PROJECT`；结果说明的镜像表格首列改为**解析后的真实容器名**（sed 取 `container` 字段，四列结构不变）；帮助文本与两处注释去掉「jl-<service>」旧口径。重建仍显式 `--force-recreate`（未动）。
- `code/lab/scripts/test-image-identity.sh`：原 18 项保留，新增 8 项——逻辑名经 `jl-` 别名解析（requested/container/resolvedBy 断言）、显式容器名仍按 `resolvedBy=container`、**无 container_name 的标签栈**（独立 mock，`docker ps -aq --filter label=…`）按 `composeService` 解析、错误 `--project` 经无 project 回退仍能命中、缺失行保留请求名、未知逻辑服务不进入别名死循环、无 docker 时 `resolvedBy=noDocker`。
- `code/lab/scripts/bench-report.js`：本步未改（前任 38b9449 已接线，读的是 `containers[*].imageTag/imageId/container/state/status`，新字段向后兼容）。

## B. 探针与故障目标分离（按代码实际口径）

- `chaos/cluster/ProbeTargetGuard.java`：护栏来源 `chaos.mysql-cluster.container`（`application.yml` 默认 `${CHAOS_MYSQL_CLUSTER_CONTAINER:jl-mysql-1}`）。
  - **拒绝的动作**：`kill` / `freeze` / `restart`（`ChaosActionType.destructive()` 是唯一判据，`start`/`resume` 属恢复动作**永不受限**——它们正是把探针救回来的手段）。
  - **拒绝时机**：`ChaosActionService` 在解析出 canonical 容器名之后、任何 `ContainerGateway`/Docker 调用之前抛出，不改栈、不注释 Grafana。
  - **错误口径**：`ProbeTargetProtectedException` → `LabErrorCode.PROBE_TARGET_PROTECTED("40302", "Target is the MySQL cluster probe container")` → **HTTP 403**（`LabExceptionHandler` 注册）；文案点名规则键 `chaos.mysql-cluster.container`、env `CHAOS_MYSQL_CLUSTER_CONTAINER` 与补救步骤（先把探针切到别的成员并重启 chaos-controller，再打故障）。
  - **比对方式**：对 trim 后的配置值做**整名相等**，不用前缀/contains（`jl-mysql-3` 不会连带保护 `jl-mysql-30`）；id 引用先解析成 canonical 名再判。
  - **不生效场景**：探针 `enabled=false`（如无 db-ha 层的 dev 栈）或未配置容器名 → `active()==false`，护栏放行。
  - 拒绝计入 `chaos.actions{result="rejected"}` 计数并 WARN。
- `chaos/cluster/MysqlClusterMonitor.java`：失败时先 inspect 探针容器自身状态，区分「节点侧失败」与「探针被杀」，按分类变化 WARN（检查异常不掩盖原始失败）；开关 `chaos.mysql-cluster.failure-container-check-enabled`（`ChaosProperties` + `application.yml`）。
- 单测（e19205c，本次未改）：`ProbeTargetIsolationTest` 10 例、`MysqlClusterMonitorTest` +6 例、`ChaosControllerTest` 40302 Web 断言 +1 例。

## 明确不做（最小闭环边界）

不做镜像来源校验、不归档构建日志、不锁 base-image digest、不把 tag/id 写入 claims.yml 或设计文档；不引入 `docker compose images` 全量清单；探针隔离只做「显式拒绝」，**不做**「操作员显式放行的配置开关」。

# 设计逻辑

1. **为什么必须改解析而不是继续硬编码 `jl-`**：`jl-` 前缀只属于 lab 栈的 `container_name` 约定。同一服务在无 `container_name` 的栈里叫 `<项目目录>-app-1`，硬编码直接解析不到，记录就全变 `missing`；更糟的是调用方（bench/tune）把容器名当参数传，一旦栈形态变化就得猜名字。逻辑服务名是 compose 的稳定标识，别名与标签都只是它的两种落地拼写。
2. **本次修的缺陷（`IFS=$'\t' read` 吞空首字段）**：`lab_ii_resolve` 原样打印 `target<TAB>how`，未命中时首字段为空（`\tunresolved`）。bash 的 `read` 把 TAB 当 IFS 空白处理，**前导空字段被丢弃**，于是调用方把字符串 `unresolved` 当成了容器名继续走 ok 分支——实测表现为 daemon 不可达时 JSON 里 `status:"ok"`、`composeProject:"ok"` 这类字段串位的假记录（38b9449 的自测有 2 项因此 FAIL）。修法是**把非空标记放前面**（`how<TAB>target`，末字段为空能被 `read` 保留），并在注释里把这条解析陷阱写死；顺带给 ok 分支的每个字段加非空兜底（`imageId` 等在容器中途消失时也会空，中间空字段同样会串位）。
3. **降级行仍写请求名**：未命中时 `container` 落请求的 spec 而不是 `unavailable`，`resolvedBy=unresolved`/`noDocker` 承担「没解析到」的事实——这样报告表格里每一行仍可归属到某个容器，而不是多行都叫 `unavailable`。
4. **project 先窄后宽**：project-scoped 标签查询命中率高、避免跨栈误配；未命中时无条件退到全量标签查询，代价是一次额外的 `docker ps`，换来「project 配错不会静默丢记录」。这里仍然只有 `docker ps -aq --filter label=…` 一条列举命令，不碰生命周期。
5. **探针隔离选「拒绝」而非「避让」**：自动把故障目标改到别的节点会让演练结果依赖运行时推断（谁当时是 PRIMARY 是可变的），拒绝则把决定权交回操作员，且失败模式是显式 403，可被控制台与 `chaos.actions{result="rejected"}` 观测到。
6. **只拦破坏性动作**：`start/resume` 是被拒后的唯一救济路径，若一并拦截就会出现「探针被保护得救不回来」；护栏在探针本身关闭时失效也同理——没有观测者就没有可自损的观测。
7. **最小闭环的取舍**：tag + imageId 一次性读取即可回答「这个 run 跑的是哪个镜像」，这是 Stage1 P0#3 的实用答案；溯源链（谁构建、基于哪个 base digest、构建日志）需要 buildkit 层证据，属方案 §15 的 Future，不在本步。

# 存在缺陷项&TODO

- 真机未验：Docker 实况下的三级解析（尤其 `composeService` 标签路径）只由 mock 证明；lab 栈当前有 `container_name`，实际只会走 `labAlias` 分支。
- `docker ps -aq` 取的是**最新一个**匹配容器（`tail -n 1`），对同一服务多副本历史残留是有意选择，但极端情况下（服务被 scale 到多实例）只会记录其中一个 id；需要全副本 tag/id 时得改成列举全部（本步不做）。
- `ProbeTargetGuard` 的 javadoc 写「lab default `jl-mysql-3`」，而 `application.yml` 实际默认 `jl-mysql-1`。仅注释与配置不一致，行为不受影响；本步不改 Java（切片 B 已提交），留给主会话回写或在 B0 收口时一并纠正。
- 拒绝变体的体验缺口：操作员若确实想「连探针一起杀」以演练观测丢失，本步只能改 env 重启控制器；「显式放行开关」按边界属 Future。
- `tune.sh` 的 `NGINX_CONTAINER=jl-nginx`、`TOXIPROXY_CONTAINER=jl-toxiproxy`、`bench.sh` 的 `K6_NETWORK=jl-net` 仍是写死的 lab 栈名（都可 env 覆盖），与镜像身份无关，本步未纳入逻辑名改造，若要统一应另开步骤。

# 待定事项和注意事项

- **Future（不进本步）**：完整 build 溯源（构建日志归档、base digest 锁定、镜像来源校验）、`docker compose images` 全量清单、探针冲突的显式放行开关、台账里长期保存 tag/id（B1-01 `lab_run_env` 是自然落点，届时 manifest 的记录格式要与之对齐）。
- 主会话需补的真机验收（B0-08 验收项原文）：db-failover 演练期间 `mysql_cluster_probe_up` **恒 1**；以及 `运行容器 tag/id 与 manifest 一致`（`bash code/lab/scripts/bench.sh` 跑一轮，看 `code/lab/k6/reports/<run>/image-identity.json` 与 `docker inspect jl-app-1` 对齐）。
- 若把 bench/tune 指向**非 lab 栈**（无 `container_name`）：`IMAGE_IDENTITY_CONTAINERS` 传 compose 服务名即可，`--project` 给该栈的 compose project 名；解析结果用 JSON 的 `resolvedBy` 自证（应为 `composeService`）。
- 记录形状有消费方，改字段前先确认三处：`bench-report.js`（读 `containers[*].imageTag/imageId/container/state/status`）、`tune.sh` 的镜像表格 sed（读 `container/imageTag/imageId/state`）、`test-image-identity.sh` 的 grep 断言（依赖 `"<key>": {"container": …, "requested": …, "resolvedBy": …` 的**字段顺序**）。
- 本步禁改项实际未触碰：`compose*.yml`、`claims.yml`、Stage2 三份设计/计划文档正文、`seckill-app-jdk21`、`AppProxyService` 白名单；未执行任何 docker/compose 生命周期命令。

## 补充（真机验证，R2 · 2026-10-28，详见 `b0-live-verification.md` §R2-2 / §R2-3）

- **403 / 40302 真机复现 = PASS**：对运行态探针容器 `POST /chaos/instances/<probe>/actions {"action":"kill|freeze|restart"}`
  一律 `HTTP=403` + `{"code":"40302", …}`，拒绝在任何 Docker 调用之前（容器状态逐字未变），
  控制器出 `ChaosActionService` + `LabExceptionHandler` 两条 WARN，`chaos_actions_total{result="rejected"}` 计 1；
  恢复动作 `start` 不被拦（`HTTP=202`、`status=completed`）。三种破坏动作 + start 四种组合均已留响应体。
- **纠一条前置口径（重要）**：本步文档第 35 行写「`application.yml` 实际默认 `jl-mysql-1`」是对的，
  但**现栈运行态是 `jl-mysql-3`** —— `compose.base.yml:482` 有意把 lab 默认覆盖为 `jl-mysql-3`
  （注释 + `Stage1/step-47-probe-target.md:27` 记录在案）。任何按「受保护目标 = jl-mysql-1」做的演练编排
  都会打空：R2 首次按此口径对 `jl-mysql-1` 下 KILL 被正常受理（非缺陷），30s TTL 自动归队，
  集群与 Router 未受影响，`RESULT: ok` 复核通过。文档/javadoc/默认值三处口径不一致请主会话一并回写。
- **db-failover 期间 `mysql_cluster_probe_up` 恒 1**：见 §R2-3（真机区间查询）。
- **`failure-container-check-enabled` 三态分类文案 = unverified**：真机上 `grep -ac "cluster probe failed"` = 0，
  分类分支从未触发；而触发它需要杀探针容器，恰被本步 403 规则挡住 ⇒ 受保护拓扑下不可自然复现，
  绕开 API 直接停容器属演练禁区。已核到：键存在（`application.yml:170`）、默认 true、容器 env 未覆盖。
- **受理型 KILL 的 HTTP 响应是同步阻塞的**（停止期间不返回，客户端超时；拒绝路径 0.05s 返回）
  ⇒ 时间线断言必须以控制器日志 / Prometheus 采样为时刻基准，不能以 POST 返回为准。

# 附录（可选）

- 提交（本步累计，fork 点 `d07590c`）：
  - `38b9449 feat(B0-08a): record imageTag+imageId of the key containers in the run archive`
  - `e19205c feat(B0-08b): refuse destructive actions on the MySQL cluster probe container`
  - `b196e29 Merge branch 'feature/stage2-workbench' into worktree-agent-general-purpose-0fb03d73`（无冲突）
  - 本次收尾两笔：`feat(B0-08c)`（三脚本逻辑服务名解析 + 字段错位修复 + 自测扩 8 项）、`docs(B0-08)`（本交接文档）。
- 证据：
  - surefire：`Tests run: 296, Failures: 0, Errors: 0, Skipped: 0`（chaos-controller；`/tmp/b008f2.log`，EXIT=0，BUILD SUCCESS）。
  - mock：`bash code/lab/scripts/test-image-identity.sh` → `image-identity tests: ALL PASS`（26 项，EXIT=0，`/tmp/b008-ii-final.log`）；`node code/lab/scripts/bench-report.test.js` → `# tests 8 / # pass 8 / # fail 0`（EXIT=0）。
  - `bash -n`：`image-identity.sh` / `bench.sh` / `tune.sh` / `test-image-identity.sh` 全过。
- 关键文件（绝对路径基于 worktree 根 `D:\000CODE\JAVA-LEARN\.qoder\worktrees\agent-general-purpose-0fb03d73`）：
  - `code/lab/scripts/image-identity.sh`（`lab_ii_resolve` / `lab_ii_ps_service` / `lab_ii_container_fields`）
  - `code/lab/scripts/test-image-identity.sh`（标签栈 mock）
  - `code/lab/scripts/bench.sh`、`code/lab/scripts/tune.sh`（清单与 project 透传）
  - `code/services/chaos-controller/src/main/java/com/javalearn/lab/chaos/cluster/ProbeTargetGuard.java`、`.../action/ChaosActionService.java`、`.../error/LabErrorCode.java`、`.../cluster/MysqlClusterMonitor.java`、`.../resources/application.yml`
- unverified：真机 db-failover 期间 `mysql_cluster_probe_up` 恒 1；真机 `composeService` 标签解析路径；真机 bench/tune 端到端归档（tag/id 与运行容器一致）；`--force-recreate` 实际重建行为；探针自检开关 `failure-container-check-enabled` 的真机表现。
