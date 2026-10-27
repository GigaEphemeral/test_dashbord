# 步骤交接：S2-B0-07 Sentinel / HA Redis exporter 纳入 Prometheus 抓取

> 任务/步骤：Stage2 B0-07 —— 把 HA Redis exporter（`jl-redis-1/2/3-exporter`）纳入 Prometheus 抓取，Sentinel 可行性核实；验收：HA 页面不存在「有图无数据」，对应指标字典更新
> 日期：2026-10-28
> 分支 / HEAD：feature/stage2-workbench 基线 `6a41c7c`；本步代码单元 commit `1a1327a`（feat: scrape HA redis exporters…），字典回填 + 本交接随后一笔 docs 提交
> 关联任务：`doc/process/Stage2/stage2-开发测试与验收计划.md` §B0-07（100–105 行）；关键输入 `doc/process/Stage2/指标字典.md` 特别结论 c
> 测试 / verify：`promtool check config` SUCCESS；`--web.enable-lifecycle` 热加载；`/api/v1/targets` 新 job 3/3 UP；关键序列即时查询均出数；`bash code/lab/scripts/verify.sh` = **109 PASS / 1 WARN / 0 FAIL**（EXIT=0，与基线逐字一致）
> 运行状态：**真机已验证**（在跑的 dev+ha+db-ha 34 容器栈上完成加载与查询），非仅配置落盘

# 当前进展

- `code/lab/docker/prometheus/prometheus.yml` 新增 `redis-exporter-ha` job：`static_configs` 三 target `redis-1-exporter:9121` / `redis-2-exporter:9121` / `redis-3-exporter:9121`，各带低基数 `node` 标签（redis-1/2/3）。仿既有 `mysqld-exporter-ha`（单 job 多 target + 成员标签、固定 compose service name + 内置端口、profile 关时 down 而非 error）模式。
- 热加载成功并已生效：`/api/v1/targets` 显示 `redis-exporter-ha` 三 target `health=up`、`droppedTargets=0`；standalone `redis-exporter` 仍单独一条 target（未受影响）。
- Sentinel **不新增抓取 job**（可行性核实结论，见设计逻辑）：compose.ha.yml 有意无 sentinel-exporter sidecar，且 Sentinel 只说 RESP、不暴露 HTTP `/metrics`，在不新建容器（禁区）前提下无可行接线；master/replica/角色证据改由数据节点 exporter 自身提供，quorum 视图仍走 `sentinel-status.sh`。
- `.env.example` **未新增键**（理由见设计逻辑）。
- `doc/process/Stage2/指标字典.md` 回填：redis_up（部分→已接线）、redis_role（未接线→部分）、手工行「Redis 角色/主从切换（Sentinel）」（未接线→部分）、缺口行（未接线→部分已接线）、B0 必修 item 2（整 job 缺失→已接线）、特别结论 c 追加「B0-07 接线后（真机复验）」小节。

# 完成功能项

- **prometheus.yml `redis-exporter-ha` job**（`code/lab/docker/prometheus/prometheus.yml` 末尾，紧接 mysqld-exporter-ha）：
  - 三 target：`redis-1/2/3-exporter:9121`（jl-net 上的 compose service name；exporter 端口是 redis_exporter 内置 9121，compose.ha.yml 仅 `expose` 不发布宿主端口）。
  - 每 target 带 `node: redis-N` 低基数标签；`instance` 由 Prometheus 自动置为 `redis-N-exporter:9121`（与 standalone `redis-exporter:9121` 同口径）。
  - 独立 job 名（不复用 `redis-exporter`）：现有面板全部按 `job="redis-exporter"` 精确匹配（D3 `redis_memory_used_bytes`、D11 `redis_up`/`redis_connected_clients`/`redis_connected_slaves`，均文档标注「仅代表 dev 单机」），保持其只命中 1 台单机、不扩为 4 台，避免 D4/D10 类面板意外聚合（D4/D10 走 `job=~"seckill-app.*"` 的 app 指标，本就不含 redis 族，双保险）。
- **真机验证证据**（全部为改配后即时查询）：
  - `docker exec jl-prometheus promtool check config /etc/prometheus/prometheus.yml` → `SUCCESS: ... is valid prometheus config file syntax`。
  - 热加载：`--web.enable-lifecycle=true`（实测 flags），容器内 `wget --post-data="" http://127.0.0.1:9090/-/reload` → rc=0（宿主 9090 未发布，仅 jl-net 内可达，故走容器侧；未 restart 任何容器）。
  - `/api/v1/targets`（新 job health 原始摘要行）：
    - `job=redis-exporter-ha instance=redis-1-exporter:9121 node=redis-1 health=up scrapeUrl=http://redis-1-exporter:9121/metrics lastError=''`
    - `job=redis-exporter-ha instance=redis-2-exporter:9121 node=redis-2 health=up ...lastError=''`
    - `job=redis-exporter-ha instance=redis-3-exporter:9121 node=redis-3 health=up ...lastError=''`
    - 对照：`job=redis-exporter instance=redis-exporter:9121 node=- health=up`（单机 job 不变）。activeTargets 共 19、droppedTargets 0。
  - 关键序列即时查询（PromQL → 返回样本数）：
    - `redis_exporter_build_info{job="redis-exporter-ha"}` → **3**（redis-1/2/3 各一，version=v1.67.0）
    - `redis_up{job="redis-exporter-ha"}` → **3**（值均 1）
    - `redis_connected_slaves{job="redis-exporter-ha"}` → **3**（redis-3=2 主、redis-1/2=0）
    - `redis_instance_info{job="redis-exporter-ha"}` → **3**（role：redis-3=master、redis-1/2=slave）
    - `redis_master_link_up{job="redis-exporter-ha"}` → **2**（redis-1/2 → master_host=172.28.190.13）
    - `redis_memory_used_bytes{job="redis-exporter-ha"}` → **3**（值 2.6–3.0 MB）
    - `count by (role) (redis_instance_info{job="redis-exporter-ha"})` → **2**（slave=2 / master=1）
  - 面板口径核对（≥2 条改查出数）：D11「Redis 可达」`redis_up` 与「Redis 连接与从节点」`redis_connected_slaves`、D3「Redis 内存」`redis_memory_used_bytes`，把 `job` 由 `redis-exporter` 改为 `redis-exporter-ha` 后即按 HA 三节点出数（见上样本）；主从角色由 `redis_instance_info{role}` 判定（此前字典记「HA 节点无序列」的缺口消除）。当前真机 redis-3 为 master（此前一次 failover 演练遗留，符合预期）。
- **verify.sh 复跑**：`bash code/lab/scripts/verify.sh > /tmp/verify-b007.log 2>&1; echo EXIT=$?` → `EXIT=0`，末行 `VERIFY PASSED: 109 checks passed, 1 warning(s).`；唯一 WARN = `cadvisor container series absent`（WSL2 既有限制，非本步引入）。HA Sentinel 断言块（profile ha）全 PASS：3 sentinel 各发现 3 sentinels + 2 replicas、单一可写 master、`redis exporter exposes redis_up 1`。日志留存 `/tmp/verify-b007.log`（未用管道吞码）。
- **未动**：compose 任何文件（`compose.ha.yml` 只读确认拓扑/exporter 定义）、chaos-controller/app Java 代码、`claims.yml`、stage2 三份设计/计划文档、Grafana 面板 JSON（面板查询保持 dev 单机口径，HA 出数靠新 job，改查证据用 API 即时查询核对）、除 jl-prometheus 外任何容器（且未 restart 它，走 lifecycle reload）。

# 设计逻辑

- **为何独立 job 而非并入 `redis-exporter`**：并入会让所有 `job="redis-exporter"` 精确匹配面板从 1 序列变 4 序列（D3/D11 单机面板语义被破坏、stat/timeseries 出现意外聚合）；独立 job + `node` 标签让「单机」「HA」两套口径解耦，与 `mysqld-exporter`/`mysqld-exporter-ha` 的关系完全对称。
- **为何 `node` 标签**：任务要求「instance/node 类低基数标签」。`instance` 由 target 自动生成（service:9121）；`node` 为人类可读节点身份（redis-1/2/3），基数=3，供面板 `by(node)` 分组。字典 B0-01 建议的 `member`（对齐 mysqld-exporter-ha）属同类低基数标签；本步取 `node` 以贴合任务原文措辞与 Redis 面板习惯，HA MySQL 保持 `member` 不受影响（不同面板段、不同查询）。
- **为何不动 compose / 不暴露端口**：抓取在容器网络内用 service_name:port 完成（Stage1 mysqld-exporter-ha 先例），exporter 仅 `expose:9121` 不发布宿主端口即可被 jl-net 上的 Prometheus 访问；无需新端口、无需改 compose、无需 recreate 服务（禁区）。
- **Sentinel 无可行抓取（如实标注，不虚报）**：`compose.ha.yml:309-312` 明示 sentinel-exporter sidecar「有意未加」；Sentinel 进程只监听 RESP（26379），无 HTTP `/metrics`，Prometheus 无法直接抓。加真 sentinel exporter 需新建容器 + 新镜像 tag → 触禁区（不许 up/recreate compose 服务、不改 compose）。故按「实际可行的接线」落地：数据节点 exporter 已给出 `redis_instance_info{role}`、`redis_connected_slaves`、`redis_master_link_up` 等 master/slave 与回链证据；Sentinel quorum/切换视图维持 `sentinel-status.sh`（verify.sh 已在验，profile ha 下 PASS）。因此**无** `redis_sentinel_*` 序列属预期、非缺口。
- **无序列/dev 栈不冒红 target**：采用 `static_configs`，与 mysqld-exporter-ha 同款约定——ha profile 关时三 target 显示 `down`（非配置错误、非红 error）；文件内既有注释「Targets are down (not an error) whenever the profile is off」已把该取舍文档化。verify.sh 默认 dev 基线本就容许 db-ha 这类 down（无「全 target 必须 UP」断言），实测 109/1/0 不变，证明未新增红 target。若后续要「profile 关时完全不配出该 job」，需 http_sd/文件发现或外部模板渲染 prometheus.yml；本步判定：down 即非红、且与既有 HA 作业一致，不引入 SD 复杂度，取舍已写入配置注释与本交接。
- **为何 `.env.example` 不加键**：Prometheus v3 不在配置文件里做 `${VAR:default}` 插值（证据：整份 prometheus.yml 含 mysqld-exporter-ha 全为硬编码 target，无一处 `${}`）。本步未引入任何「新端口/主机/开关」——exporter 端口(9121)与 service name 均由 compose 固定、且与 standalone redis-exporter 用同一 `REDIS_EXPORTER_IMAGE`。往 prometheus.yml 写 `${}` 只会产生字面量坏 target，故遵「与文件现有风格一致」硬编码。开发要求 2 的 env 化/补键在「无新增可变项」时为空操作，已如实说明。

# 存在缺陷项&TODO

- Sentinel 指标域仍是**部分**：无 `redis_sentinel_*`（设计上不抓取），主从/角色证据来自数据节点 exporter。若 Stage2 后续要真·Sentinel 监控指标，须开安全边界评审新增 sentinel-exporter 容器（本步禁区不允许）。
- Grafana D11「Redis 主从（dev 单机导出器）」面板段仍指向 dev 单机 job（step-38 文档即如此标注）；本步交付的是「HA 有序列可查」，面板改指向 `redis-exporter-ha` / 增补 HA 分组面板属**面板改造**范畴，本任务未列入改动清单（只要求面板查询出数核对，已用 API 即时查询证明改查即出数）。若验收要求面板可视，需另开改面板步骤。
- `node` vs `member` 标签命名：本步 redis 用 `node`、mysql 用 `member`，跨栈分组面板若欲统一 `$member` 变量需后续约定。

## 真机样本验证

| 项目 | 状态 | 备注 |
|---|---|---|
| `redis-exporter-ha` 三 target `health=up`（/api/v1/targets） | **verified** | 3/3 up、droppedTargets=0，lastError 空 |
| `redis_exporter_build_info{job="redis-exporter-ha"}` 可查 | **verified** | series=3，v1.67.0 |
| master/slave 角色即时可判（`redis_instance_info{role}`） | **verified** | slave=2 / master=1（redis-3=master） |
| D11/D3 redis 面板查询改 `job="redis-exporter-ha"` 出数（≥2 条） | **verified** | redis_up=3、redis_connected_slaves=3、redis_memory_used_bytes=3 |
| standalone `job="redis-exporter"` 仍单命中（不扩为 4） | **verified** | targets 中该 job 仅 1 条 instance |
| `verify.sh` 109/1/0、EXIT=0 | **verified** | 唯一 WARN=cadvisor WSL2 |
| Grafana UI 面板实际渲染出图 | unverified | 用 /api/v1/query 即时查询核对口径，未在浏览器截图面板（任务未要求动面板/截图） |
| Sentinel quorum Prometheus 序列 | 不适用（设计不抓取） | 证据走 sentinel-status.sh，verify profile ha 下 PASS |

# 待定事项和注意事项

- 合回单元：代码 = `1a1327a`（feat，prometheus.yml），文档 = 本步 docs 提交（指标字典回填 + 本交接）。
- 并行注意：本步独占主工作树与 Docker；仅动 prometheus.yml 与两份 Stage2 文档，未触碰 compose / app / chaos / claims / 设计计划文档。jl-prometheus 未 restart（走 lifecycle reload），若后续步骤需要 promtool/配置回滚，直接改该 bind-mount 文件再 reload 即可。
- 若 B3/后续步骤要新增 sentinel-exporter 或改 Grafana 面板指向 HA，需先确认禁区边界（新建容器/改 compose 属安全边界变更，另行评审）。
- 全量 verify 日志：`/tmp/verify-b007.log`（EXIT=0）。targets/query 证据命令见「完成功能项」，均为只读 API 调用。
