# 避坑索引

> 只收录**真实发生过**的坑；每条都有现象、根因、正确做法、验证方式与出处。
> 找不到出处的条目不要写进来——文档断言会过时，代码与实测记录才算证据。

| # | 坑 | 一句话结论 | 状态 |
|---|---|---|---|
| 01 | [docker-java exec 挂 stdin 不给 EOF](01-docker-java-exec-stdin.md) | 用 stdin 喂 SQL 会挂到超时；SQL 走容器环境变量 + `-e` | 已写 |
| 02 | [Micrometer 剥掉 `_info` 后缀](02-prometheus-info-suffix.md) | 代码叫 `mysql_cluster_primary_info`，Prometheus 里是 `mysql_cluster_primary` | 已写 |
| 03 | [Prometheus `scrape_config_files` 让进程崩溃循环](03-prometheus-scrape-config-files.md) | 3.0.0 下外部文件格式不符会直接 `cannot unmarshal !!seq`；改内联 job | 已写 |
| 04 | [Toxiproxy 镜像是 distroless](04-toxiproxy-distroless.md) | 镜像里没有 sh/wget，healthcheck 与 init 都跑不了；init 用 alpine | 已写 |
| 05 | [Redis Sentinel 用主机名 monitor 会 `#tilt`](05-sentinel-hostname-monitor.md) | monitor 必须用固定 IP；跨网段要 announce | 已写 |
| 06 | [MySQL Router 8.4 的 SSL 与命令行](06-mysql-router-84-ssl-cli.md) | `caching_sha2_password` 必须 SSL（2061）；8.4 拒绝 `--log-to-console`/`--conf-scope` | 已写 |
| 07 | [compose 嵌套默认值静默变空](07-compose-nested-defaults.md) | `${A:-x:${B}}` 在 B 未定义时渲染成空串且不报错 | 已写 |
| 08 | [CRLF 脚本进容器](08-crlf-scripts.md) | 容器内报 `\r` 错误；`.gitattributes` 强制 LF | 已写 |
| 09 | [故障白名单默认全拒与 id 前缀绕过](09-chaos-allowlist.md) | 空=全拒；校验必须落在 inspect 后的 canonical name | 已写 |
| 10 | [L1 缓存掩盖切换症状](10-l1-masks-failover.md) | 60s L1 会让 Redis 切换"无感"；要毛刺需显式超时 | 已写 |
| 11 | [verify.sh 与 REDIS_MODE 的耦合](11-verify-vs-redis-mode.md) | sentinel 模式期间 verify 的 Redis 内容检查会假失败 | 已写 |
| 12 | [Testcontainers 临时容器](12-testcontainers-temp-containers.md) | 随机名容器不是实验室容器，别误判/清理 | 已写 |
| 13 | [并发构建让线程指纹测试抖动](13-thread-fingerprint-flake.md) | 相位锁步导致 BLOCKED 偶发不出现；需相位偏移 + 精准采样 | 已写 |
| 14 | [文档断言必须证伪](14-docs-must-be-falsified.md) | 多份 step 文档曾写"未接线/未实现"，后来被推翻 | 已写 |
| 15 | [k6 动作超时短于 Docker stop](15-k6-action-timeout-vs-docker-stop.md) | kill 已生效却报 `HTTP 0`；客户端超时必须大于 stop grace | 已写 |

## 维护约定

- 新坑先写入对应 `doc/process/step-*.md`，再提炼到本目录并登记索引。
- 每条必须能回答：怎么复现、怎么判断、怎么避免、去哪看证据。
- 本目录不收录"设计上不打算做"的取舍（那些放 `doc/design/`），只收录会让人踩第二次的坑。

## 完成情况

15 条全部成文，均来自真实事故或真机演练；新增坑请先写进对应 `doc/process/step-*.md`，再提炼到这里并登记。
