# 复盘 · 2026-10-27（M0–M6 主线收口）

> 目的：验收后停止迭代，把「哪些做法真的有效、哪些坑差点漏掉」沉淀下来，供后续维护或同类项目复用。
> 结论先行：**7 批独立审核全部发现了真问题；6 个真机缺陷只有跑起来才会暴露；文档漂移比代码缺陷更常见。**

---

## 1. 交付面（最终状态）

| 维度 | 结果 |
|---|---|
| 代码 | 5 个模块（core release-8 / jdk21 / jdk8 / chaos-controller / 两个 platform），lab 代码含 compose 5 层、12 个脚本、k6 场景、Vue 操作台 |
| 自动化 | Maven 全量绿（app 434、jdk8 11、chaos 244、core 13、platform 14）；k6 Node 41；`verify.sh` 104 PASS / 1 WARN / 0 FAIL；`smoke.sh` 7/7；`console-smoke.sh` 37/0 |
| 真机证据 | Sentinel 晋升 3.0s、12.4s 恢复 200、50.1s 归队；MySQL Router 切换 12.0s、62.5s 归队；Toxiproxy 写延迟 80ms→3.5s、断网写 504/读 200；集群探针 `probe_up=1`；D7 启动 9.33s vs 30.52s |
| 文档 | 设计 1 份 + 细节 11 份（含代码对照）+ 图集 9 张 + 46 份步骤交接 + 避坑 14 篇 + readme 手册 |
| 可重跑验收 | `ha-acceptance.sh sentinel|mysql`（含 dry-run、自动解析当前主、失败也 stop-all） |

## 2. 有效的做法（建议复用）

1. **独立审核 + 隔离 worktree + 修复后合入**：7 批审核（M3-1b、M3-1c、M4-2a、M4-2b、M4-3、M5-1、M5-4）**每一批都发现了真问题**，包括：热点榜 `DEL`+`ZADD` 会清空共享榜、逻辑过期把旧值写回 `@Cacheable`、`retention < hot-key-ttl` 让策略静默退化、id 前缀绕过 datastore 白名单、线程死锁模式没有真 BLOCKED、oom 分配器有封顶永不 OOM、指标 remove 竞态、把「未接线」写成既定事实。
2. **回报契约 + git 侧证**：要求原样粘贴 `git log`/`diff --stat`/测试计数，并抽查。曾出现子代理虚报 4 提交/16 文件（实际为零），契约之后未再发生。
3. **提交纪律**：150 轮硬上限下，三个长任务在收尾前耗尽；规定「编译过 + 首测绿立即 commit」后，中断只损失最后一段，后续可从现有 worktree 接手。
4. **真机演练不可替代**：六个缺陷只在真栈出现——
   - docker-java exec 挂 stdin 不给 EOF（探针 100% 超时）；
   - Toxiproxy 镜像是 distroless（healthcheck/init 全跑不了）；
   - 运行中的 app 镜像是旧 jar（缺雪崩类，面板整块为空）；
   - 同步 kill POST 超出探针超时预算（动作已生效却报 000）；
   - `/lab/cache/seed` 让 verify 的「恰好 20 条商品」必然失败；
   - 一次性/按需容器被控制台当成常驻，冒烟假失败。
5. **验收脚本要真跑**：`ha-acceptance.sh` 在 mock 下 38/37 全绿，真机首跑仍有 2 个 FAIL（把从节点解析成主、kill 超时预算）——mock 只能证明逻辑，不能证明环境。
6. **文档带代码基线 + 一致性清单**：details 每篇记录基线 commit，图集 README 给「改动需回访的代码路径」；文档漂移（Toxiproxy/D6/D10/D11「未实现」）靠这套机制被定位并修正。

## 3. 没做好 / 需要改的

1. **文档漂移是系统性风险**：多篇 step 与 details 写过「未实现/未接线」，后来被推翻。补救靠人工 grep，没有自动化校验（可考虑：文档断言清单 + CI 里按符号 grep）。
2. **测试自身的真实性**：M4-2b 两个用例把实现写错（Mockito 未打桩、计数器写在没用上的 lambda）；线程指纹测试因相位锁步假失败。**测试也会撒谎**，审核必须审「测试是否证明行为」。
3. **并发上限**：5–6 个子代理同时跑会触发「Queuing failed」/当日额度耗尽，且中断可能留下半演练状态（app 指向 toxiproxy、mysql 代理被禁用）。教训：同时最多 3–4 个，Docker 单一所有者，中断后先看栈状态再继续。
4. **验证命令的管道陷阱**：`mvn ... | grep ...` 的退出码是 grep 的，曾把失败当成功；构建期间改文件造成 111 个假错误。现在统一「全量日志 + 独立 EXIT 捕获 + 不在构建中改源码」。
5. **探针与演练目标的耦合**：集群探针默认 `jl-mysql-1`，正好是杀主目标；演练时 `probe_up=0` 被误读为集群故障。解法是把探针移到非目标节点（`CHAOS_MYSQL_CLUSTER_CONTAINER` 尚未由 compose 转发）。
6. **D8 调优面板与缓存实验控制台页**在收口时仍是缺口，之后已补：D8 + `tune.sh`（step-51）、缓存实验室页 + `/lab/cache/**` 窄代理（step-50）；设计 §7.4 的面板至此齐全。

## 4. 数字口径（最终）

- Maven：app-jdk21 **434**、seckill-app-jdk8 **11**、chaos-controller **244**、seckill-core 13、platform-common 10、platform-observability 4。
- 脚本：`verify.sh` **104 PASS / 1 WARN**（WARN=cAdvisor/WSL2）、`smoke.sh` **7/7**、`console-smoke.sh` **37/0**、k6 Node **41/41**、`ha-acceptance.sh` sentinel **38/0** + mysql **36/0**。
- compose 不变式：默认 **16**、`chaos-proxy` **18**、`ha` **25**、`db-ha` **25**、`dual-jdk` **17**。
- 资源：MySQL HA 实测 **1.87GB**（声明 2.1GB）；db-ha + ha + dev 同开约 9–10GB。

## 5. 维护待办（按优先级）

1. `CHAOS_MYSQL_CLUSTER_CONTAINER` 由 compose 转发（探针避开被杀节点）。
2. D8 调优面板；缓存实验的控制台页（需要把 `/lab/cache/**` 纳入窄代理）。
3. 线程快照真机验证（容器内 `jcmd` 自 attach、`docker cp`、真实 annotation）。
4. 文档断言自动校验（把「未实现/未接线」列成清单，CI 按符号 grep）。
5. k6 侧补 `db-failover`/`cache-*` 场景，用错误率阈值替代单请求探针。
