# 当前进展

收尾四项维护：jdk8 两级缓存补真实 Redis 覆盖；清理文档/注释漂移；删除离线演示副本；清理仓库与镜像卫生。
不涉及运行时代码行为，app-1/2/3 与控制器均未重启。

验证（2026-10-30）：

| 项 | 结果 |
|---|---|
| jdk8 模块 `mvn test` | **361 tests / 0 failures / 0 errors**（原 356 + 5 个真实 Redis IT） |
| `check-doc-claims.sh` | **94 PASS / 0 FAIL**（新增 2 条防回退断言） |
| `ha-d11.json` | JSON 解析通过（仅改面板说明文字） |
| `bash -n` | `verify.sh`、`db-ha-up.sh` 语法通过 |
| 工作树 | `git status` 仅本步预期改动；`git worktree list` 只剩主工作树 |

# 完成功能项

| 项 | 落地 |
|---|---|
| jdk8 真实 Redis IT | 新增 `RedisTestContainer`（Testcontainers `redis:7.4.2-alpine`）+ `TwoLevelCacheRedisIT`：L1/L2 填充、L2 命中回填 L1、evict、按前缀 SCAN clear、外部化后的 key 前缀/TTL；`FakeRedis` 单测保留用于离线快速回归 |
| 测试接线 | jdk8 pom 增加 `org.testcontainers:testcontainers:1.21.4`（与 JDK21 同版本、Java 8 字节码）并把 `**/*IT.java` 纳入 surefire；修正 `FakeRedis`/`TwoLevelCacheTest` 的错误 javadoc（不再自称“against a real Redis”） |
| 文档漂移 | 修正 `details/08`（JDK8 壳已全业务，不再“仅热读”）、`details/01`（仅 SIGSTOP 未做）、`details/05`（Router 切主已演练、HA exporter 已抓取）、`details/09`（D8、JDK8 login）、`diagrams/README`（D7/D8）、`details/README §3.2/§3.3`（7 条已收口 + 状态表）、`ha-d11.json` 面板说明（Redis HA 已抓取） |
| 基线/注释 | README 门禁表（chaos 893、console 159、verify 110、claims 92）、`doc/readme.md` verify 基线、`verify.sh` 注释、`db-ha-up.sh` 内存提示（2.1GB 声明 / 1.87GB 实测）、`架构现状`（2+2 Redis、Stage2 起点基线说明）、`指标字典`（app-3 JDK8 家族差异） |
| claims 指针 | `wizard-jdk8-column`、`console-usability-fixes` 指向 B5-05e；新增 `jdk8-real-redis-cache-it`、`demo-html-removed` |
| 离线演示副本 | 删除 `doc/demo.html`；README 移除引用；`step-S2-B5-04g` 的历史行加删除指针 |
| 仓库卫生 | 移除 27 个过期 worktree 与全部 `worktree-agent-*` / `review/*` 分支（清理前已归档到 `%TEMP%\java-learn-worktree-archive-20261030\branches.bundle` 及两份 dirty patch）；删除 5 个旧镜像（`*-pre-b0r1/pre-docs/pre-d10`）与 5 份根目录合并/验收日志、demo 构建脚本与临时抓取目录 |

# 设计逻辑

- **FakeRedis 与真实 Redis 分工**：FakeRedis 让缓存/L1 逻辑的单元测试不依赖 Docker、迭代快；真实 Redis IT 负责服务端语义
  （JSON 往返、真实 TTL、SCAN 分批）。两者都保留，避免“要么慢要么假”。
- **Testcontainers 版本钉 1.21.4**：与 JDK21 壳一致，且该版本仍是 Java 8 字节码；Boot 2.7 BOM 不管理它，必须显式声明。
- **文档修正边界**：现状/使用文档直接改；历史 `step-*.md` 只加指针（step-49 保护名单），不重写历史记录。
- **仓库清理先归档**：worktree 分支里可能有未合并的 review/fix 提交，删除前用 `git bundle --all` 归档全部 refs，
  并对两个 dirty worktree 单独导出 patch；确认主工作树不受影响后再删。
- **演示副本删除**：`demo.html` 是旧 IA 的录制快照，B5-04g 已列“需重新录制”，用户明确要求删除；保留会持续误导。

# 存在缺陷项&TODO

| 项 | 说明 | 归属 |
|---|---|---|
| `tmp/` 仍有历史抓取文件 | 仅清理了 demo 构建脚本与本轮 metrics；其余 `tmp/*.json`、`tmp/app-image/` 等为旧会话证据，未擅自删除 | 按需 |
| D7 对照壳仍在运行 | `jl-seckill-app-jdk8` 已运行 11h、100% OTel 采样；属 `dual-jdk` 按需容器，不在默认栈，未停止 | 按需 |
| 归档包位置 | worktree 分支归档在 `%TEMP%`（不在仓库内）；需要历史时可 `git fetch <bundle> 'refs/*:refs/archive/*'` | 记录 |
| jdk8 IT 需要 Docker | 与 jdk21 的 Testcontainers IT 一致；无 Docker 时模块测试会失败，CI 需先确保 Docker 可用 | 记录 |

# 待定事项和注意事项

1. 新增缓存语义断言时，优先加到 `TwoLevelCacheRedisIT`（真实服务端）；FakeRedis 只覆盖分支/指标逻辑。
2. 修改 `prometheus.yml`、`verify.sh`、`db-ha-up.sh` 后，注意 claims 与 `details/README §3.3` 的注释同步。
3. 历史交接文档不再回改；发现新漂移时在 `details/README §3.3` 或本步文档登记指针。
4. 需要恢复被删的 worktree 分支时，从归档 bundle 取；`tmp/` 的旧证据如需清理请先确认。

---

## 附录：命令与证据

```text
mvn -pl services/seckill-app-jdk8 test
-> Tests run: 361, Failures: 0, Errors: 0, Skipped: 0
-> TwoLevelCacheRedisIT: 5 tests against redis:7.4.2-alpine

bash code/lab/scripts/check-doc-claims.sh
-> Summary: 94 total, 94 PASS

git worktree list
-> D:/000CODE/JAVA-LEARN  dc56e27 [master]

docker images (java-learn-lab)
-> seckill-app:jdk21 / seckill-app:jdk8 / chaos-controller:jdk21
```
