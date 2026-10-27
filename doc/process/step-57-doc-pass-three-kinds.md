# 步骤交接 · 文档整理三项（步骤文档 / 使用文档 / 打包安装手册）

> 任务/步骤：#67 #68 #69 —— 把三类文档拉回与代码一致的口径（不改 Java、不改 compose、不改脚本）
> 日期：2026-10-27
> 分支 / HEAD：`feature/ha-lab` / 起点 `96939cf`
> 关联任务：#67（1.1 步骤文档）、#68（1.2 使用文档）、#69（1.3 打包安装手册）；口径来源 #61/#62/#64
> 测试 / verify：`check-doc-claims` **31 total / 31 PASS / 0 FAIL / 0 STALE**；k6 Node 回归 **79 pass / 0 fail**；`verify.sh` **109 PASS / 1 WARN / 0 FAIL**；reactor 沿用 **784 tests**（Java 零改动）
> 运行状态：栈保持运行，ha + db-ha = **34 服务 / 32 常驻容器**；app 处于 `standalone`

# 当前进展

- 用户指令的三小项全部完成：**1.1 步骤文档**（已完成待办删除、可复用内容上收到设计）、**1.2 使用文档**（精确到每一项设计的异常如何触发）、**1.3 项目打包与安装明细文档**（新文件）。
- 里程碑状态不变：M1–M6 已交付，本步属于「文档与验收」的收口，不新增能力。
- 本步之后按指令**停下**；`details/README` §3.2 仍是唯一的「还有什么没做」口径。
- 未完成/进行中：#66（运行中的 app 容器仍指向 `:jdk21-pre-docs`，需 `--force-recreate` 才与 `:jdk21` 一致）仍开放；§3.2 的其余缺口未消化。

# 完成功能项

**1.2 使用文档 —— `doc/readme.md`（297 → 524 行，LF）**

- 新增 **§4「每一项设计的异常如何触发」**：§4.0 通道矩阵（四条触发通道 + 两条通用规则）→ §4.1 业务主链路 → §4.2 缓存八模式 → §4.3 Redis Sentinel → §4.4 MySQL Router/Cluster（含 `db-failover`）→ §4.5 混沌（5 个容器动作 + 3 类 toxic + 3 个场景 JSON + 向导/k6）→ §4.6 线程实验室（七种故障 + 生命周期 + 快照）→ §4.7 双 JDK 与 `tune.sh` → §4.8 k6 九场景与 bench → §4.9 操作台 → §4.10 安全边界本身当异常演示。
- 每行要么给 `path:line`，要么标 **⚪**；✅/⚪ 的含义写在 §4 开头（✅ = 本机真机跑过、证据在 `doc/process/step-*.md`；⚪ = 按代码/配置口径，本轮未逐条真机复现）。
- 修掉的过期口径：verify 基线 104 → **109 PASS**；`--profile db-ha` 不是 `up.sh` 的 profile（`up.sh:101-103` 显式拒绝）；「34 个 lab 容器」→ **34 服务 / 32 常驻容器**；「五个可演示实验」与实际族数不符；`DB_FAILOVER_TARGET`（默认 `jl-mysql-2`，`run.sh:255`）与探针容器 `CHAOS_MYSQL_CLUSTER_CONTAINER`（代码默认 `jl-mysql-1`，`.env.example:330` 建议 `jl-mysql-3`）此前被写成同一个东西；§115-122 的「已知缺口」已被 `be84c13`/`96939cf` 关闭。
- §9/§10 改为指向 `details/README` §3.2/§3.3 与 `安装与打包手册.md`，不再复制清单。

**1.1 步骤文档 —— `doc/process/阶段交接-2026-10-26.md`（735 → 631 行）**

- 「立即待办」：三条已完成项删除，改为指向 `details/README` §3.2 与本文件末尾批次段落；只保留「不得新增硬编码」这一条长期事项。
- 「已知 Minor / 限制」：删除 3 条已完成项（MySQL Router 环境变量接入 compose、JDK8 外壳、"未做缓存实验室/Sentinel/Cluster/线程实验室/调优台/动画/Mermaid/readme"）；M3-2a 的「live bootstrap 完全未验证 + 2.1GB 高于设计」按实测收口（`RESULT: ok`、实测 ≈1.87GB、设计改记 2.1GB）。
- 「配置外置审计清单」：8 张任务键表（约 160 行）**整体上收**到 `details/11` §3.1.1，本节只留指针 + 仍然成立的两条硬编码事实（缓存 L1 兜底常量与 `KEY_PREFIX`、订单 `RETRY_SLEEP_MILLIS` 与两处 `MAX_TAIL`）。
- 「后续任务」「下一步」：M3/M4/M5/M6 与 #49/#51/#29/#30/#32/#34 均已完成，改为指针 + 两条不变的工作约定。
- 「运行与验证」重写：五套 profile（含 `db-ha-up.sh` 不是 profile）、还原 standalone 的命令、八个验收脚本入口与当前基线、`REDIS_MODE` 与 verify 的耦合、PASS 基线漂移说明、reactor 与 Java 零改动的证据命令。
- 「交接资料」：step 范围补到 `step-56`，新增 details/手册/图集三条。
- 历史批次段落**只加指针不改写**（5 处「仍缺/下一步」清单 + 第五批次「遗留」后一行），保持历史证据原样。
- 追加「2026-10-27 第六批次（文档整理）」段落。

**融合到设计 —— `doc/design/details/11-配置与安全边界.md`（435 行，LF）**

- 新增 **§3.1.1 秒杀准入与弹性键（Task 16/17）**：24 行键表（`SECKILL_RATE_LIMIT_*`、`SECKILL_CB_*`、`ignore-exceptions`、MQ exchange/queue/routing-key、consumer delay、Redisson 池与懒初始化与 Sentinel 旋钮、`RABBITMQ_CONSUMER_CONCURRENCY/_MAX_CONCURRENCY/_PREFETCH`、`default-requeue-rejected`、RabbitMQ 连接四项），env 名与 `application.yml` 行号逐条核对；表头写明「原先只在阶段交接的逐任务审计清单里」。
- **修 30 处 `path:line` 漂移**：缓存与线程键族插入 `application.yml` 后，§3.1/§7 的行号整体位移（如 `REDIS_MODE` 67→77、`DB_WRITE_URL` 79→95、`LAB_TOKEN` 103→123、`SECKILL_REDISSON_READ_MODE` 304→425、`INSTANCE_ID` 351→472、`management.endpoint.health.group.app.exclude` 388→509）。用可复核脚本逐行改正后，再跑一遍复核得到 `rows 0 moved 0 notfound 0`。

**1.3 打包与安装 —— `doc/design/安装与打包手册.md`（新，363 行，LF）**

- 11 节：前置软件与端口、目录约定与**混合行尾**、`.env` 必改键、21 个拉取镜像（tag + digest + 国内镜像源溯源结论）、3 张构建镜像的 Dockerfile 结构与实测耗时/镜像 ID/体积/回滚 tag、profile 矩阵与首次启动顺序与入口、八个验收闸门基线、改完怎么生效、停止/重置/清理、13 条安装故障、相关文档索引。
- 关键新增事实：冷构建墙钟 **3m37.719s**（app mvn 180.2s、controller mvn 140.7s、OTel 下载 3.8s）；`:jdk21` → `cfb7a164e9b3`（940MB）而**运行中的 `jl-app-1/2/3` 仍指向 `694d4586c398`**（= `:jdk21-pre-docs`），compose 只认 tag 不比对 image ID；exporter 是 **3 个镜像 / 9 个容器**。

**文档漂移登记与机检**

- `doc/design/details/README.md` §3.3 新增 5 行（`details/01:438`、`details/05:307`、`details/09:82`、`verify.sh:931`、`db-ha-up.sh:241`）。
- `code/lab/docclaims/claims.yml` 追加 7 条「文档形状」断言（§4 章节存在、手册存在、键表已上收到 §3.1.1、阶段交接不再复制限流键、§3.2 是唯一未收口口径、k6 白名单含三个 HA/缓存场景、readme 不再写 104 PASS）→ **31/31 PASS**。

# 设计逻辑

- **单一口径**：仓库里同时存在「agent 任务清单 / 阶段交接的待办 / 各 step 的仍缺清单 / details/README」四处缺口登记，历史上反复漂移。本步把它们定死为：缺口只看 `details/README` §3.2，文档说反话看 §3.3，历史交接段落只加指针不改写。这样每步交接可以保持「当时的真相」，而现状文档只有一份。
- **可证伪优先**：使用文档新增的每一条触发方式要么给 `path:line`、要么标 ⚪；能被静态检查的（章节存在、旧数字不再出现、键不再被复制）一律进 `claims.yml`。这是「文档断言要证伪」这条规约第一次用在文档自身（此前只断言代码能力）。
- **行尾是承重结构**：`阶段交接` 前 447 行是 CRLF、之后是 LF，`details/README.md` 与 `claims.yml` 全 CRLF。这类文件不能用会重写行尾的工具改，本步全部用字节级脚本改并在改后统计 CRLF/LF 数量确认（447 / 184）。
- **上收而非删除**：配置审计清单是逐任务攒出来的实现事实，删掉等于丢证据，所以整体上收到 `details/11` 并顺手修掉 30 处行号漂移——设计文档里的 `path:line` 一旦失真，「严格与代码对照」这条约定就废了。
- 未新增任何配置项；未改 `.env`、compose、脚本、Java。

# 存在缺陷项&TODO

- **`verify.sh` 的 PASS 基线还会继续漂**：脚本 `:931` 的注释仍写「97 PASS」，本次只登记不修（改注释不影响结果，但下一次加断言时顺手改掉更省事）。
- **`details/09:82` 等五处反话仍未改正文**：只在 §3.3 登记。逐份改写历史/专题正文的成本高且容易再漂，当前策略是「以 §3.3 为准」；若之后有人照 `details/09` 的六场景说法写脚本，就会踩空。
- **⚪ 的密度**：`doc/readme.md` §4 里真机跑过的（✅）与只按代码口径写的（⚪）混排，⚪ 主要集中在缓存八模式的逐条指标名、线程七故障的逐条症状、Toxiproxy 三类 toxic。要转 ✅ 需要一次专项真机巡礼。
- **面板视觉仍未验证**：D6/D8/D10/D11 只做了置备断言与 PromQL 非空取数，没有做过渲染核对。
- **文档自身没有回归门禁**：`check-doc-claims` 是脚本，不在 `verify.sh` 里；改完文档不会自动跑。可考虑把 `--only` 子集并入 verify（需先确认 verify 无 Node 依赖的边界）。
- 本步未发现代码缺陷（未触碰代码）。

# 待定事项和注意事项

- 按用户指令，**文档整理完成后停下**；后续开发（§3.2 清单、#66 容器与 tag 对齐、⚪ 转 ✅ 巡礼）需要用户再发令。
- 若继续：**先做 #66**（`up -d --force-recreate app-1 app-2 app-3` 让容器与 `:jdk21` 对齐，再复跑 verify/smoke/console-smoke）——它是文档里已经写明但状态未收口的唯一一项；然后才是调优参数扫描、`/lab/cache/reset`、D7 控制台入口。
- 运行注意：本批改了 `阶段交接` 与 `details/README`（CRLF）与 `doc/readme.md`/`details/11`/`手册`（LF）。用会规范化行尾的编辑器打开 `阶段交接` 会产生整文件 diff；评审前先看 `git diff --stat` 的行数是否与预期一致。
- 安全边界不变：token 只从 `code/lab/docker/.env` 读、不回显、不写进文档；只打 `label=lab=java-learn` 的容器；不 `--purge`、不删卷。
- 数据状态：`MYSQL_MODE=standalone`；product 1 库存 17、product 2 库存 15（第五批次演练残留，写在集群库里）——文档整理没有触碰数据。

# 附录

```bash
# 本步用到的复核命令
bash code/lab/scripts/check-doc-claims.sh          # 31 total / 31 PASS / 0 FAIL / 0 STALE
node --test code/lab/k6/test/*.test.mjs            # 79 pass / 0 fail
bash code/lab/scripts/verify.sh                    # 109 PASS / 1 WARN / 0 FAIL（栈在运行、standalone）
git log b5f203c..HEAD -- '*.java'                  # 空：本批次 Java 零改动
docker inspect jl-app-1 --format '{{.Image}}'      # 694d4586c398 ≠ :jdk21 的 cfb7a164e9b3
docker ps -q --filter label=lab=java-learn | wc -l # 32 常驻
```

关键文件：`doc/readme.md`、`doc/process/阶段交接-2026-10-26.md`、`doc/design/details/11-配置与安全边界.md`、`doc/design/details/README.md`、`doc/design/安装与打包手册.md`、`code/lab/docclaims/claims.yml`、`doc/process/step-56-cache-lab-defect-arming.md`（上一步的交接，随本批一起提交）、`doc/避坑/17-*.md`、`doc/避坑/18-*.md`。
