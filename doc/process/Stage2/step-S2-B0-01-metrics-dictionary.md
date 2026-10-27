# step-S2-B0-01 · Stage2 指标字典与真机接线审计

> 门禁：方案 §10.4「B0 还应验证 Stage1 现有指标是否真实接线：每项指标记录来源、标签、PromQL、聚合窗口、空数据语义、状态和验收证据」。
> 交付物：`doc/process/Stage2/指标字典.md`（220 行，含特别结论 a–d）。
> 审计方式：**只读 HTTP 查询正在运行的真机栈**（34 容器 dev+ha+db-ha 同开），未执行任何 docker/compose 生命周期命令、未改任何代码。

## 1. 当前进展

- 字典成表：7 个指标族 × 220 条目录行（覆盖方案 §6.2 全部目录 + 架构现状 §5 已注册项 + 18 条「目录有项、无对应序列」的手工行）。
- 状态判定全部来自真机查询（探测时刻 2026-10-27T15:40:07Z，24h 窗口）：
  **OK 169 / 部分 23 / NO_DATA 3 / 未接线 25**。
- 特别结论已成文：a) Stage1 §3.2 未接线/口径类缺口的字典对应行；b) §6.2 有目录但真机查无序列的 B0/Future 靶子清单；c) Sentinel/HA redis exporter 抓取现状（B0-07 输入）；d) k6 run_id 标签现状。

## 2. 完成功能项

1. 词表全表抓取：`/api/v1/label/__name__/values` = 2014 个导出名，按前缀聚类圈定审计范围。
2. 批量 24h 窗口接线探测：约 197 个候选导出名逐一 `{__name__=~"^<name>$"}` series 探测 + 即时/`max_over_time` 取样（脚本 `tmp-metrics/audit.py`，JSON 结果 `tmp-metrics/audit.json`）。
3. 每行标签集、来源 job、样本值入表；代码名（`mysql_cluster_primary_info`、`orders_total` relabel 等）放「导出名（代码名）」列，按导出名记录。
4. 抓取 job 现状核对（prometheus.yml 只读）：15 静态 job，`up` 实测 14/15 为 1，唯一 down 的是 `seckill-app-jdk8`（dual-jdk 未开 profile）。
5. 假阴性修正记录：首版探测把 `match[]` 写成裸 `^name$`（非合法 selector）导致 0 命中，已改为 label selector 语法重跑，字典数据为修正后结果。

## 3. 设计逻辑

- 接线状态四值口径（OK/NO_DATA/未接线/部分）按「存在性 + 样本值」判定，不做修复、不评价设计；`未接线` 用「词表全表无此词」与「prometheus.yml 无 job」双重证据，`NO_DATA` 与 `未接线` 用历史词表区分（k6 场景没跑 ≠ 没接线）。
- 空数据语义逐族写明「0 是否可信」：业务计数器 0=真实零（如 `stock_oversold_total`=0），k6 无序列=窗口内没跑，禁止固定 0 冒充（§6.1）。
- 采集端不提供（nginx 状态码、MySQL deadlocks、cAdvisor per-container、resilience4j transitions 等）标 `未接线` 并在特别结论 b 归入 Future，不与「代码注册但抓取不通」混为一谈。
- B0-07 输入：HA Redis exporter 三台在 compose 已定义但未入抓取；对照 `mysqld-exporter-ha` 的单 job 三 target + member 标签 pattern 给出可照抄结论。

## 4. 存在缺陷项 & TODO

- `部分` 23 行中 11 行集中在 Redis/RabbitMQ/Nginx/容器族（cAdvisor WSL2 root-only + exporter 维度缺失），Stage2 面板设计须按「无数据/降级」呈现。
- `k6_http_reqs_total` 带 `url` 标签、24h 3369 条序列，违反 §6.1 低基数原则——B1 前需 relabel（本审计只登记不修）。
- k6 序列无 `job`/`instance` 标签，D9 类变量必须走 `run_id`；字典已注明。
- `stock_oversold_total` 接线存在但真实递增路径未证（B0-02 收口时应在本字典追加证据行）。
- 本字典的 OK 仅代表「窗口内有真实序列 + 样本值」；D1–D11 面板查询与字典 PromQL 的一致性核对不在本步范围。

## 5. 待定事项和注意事项

- 字典按探测时刻快照记录；后续 B0-02/03/07/08 修复合入后需按 §「复跑口径」重跑并更新对应行的证据列。
- `seckill-app-jdk8` 当前 up=0：dual-jdk profile 开起来后 jdk8 族的 24h 结论（当前多为窗口内历史序列）应复核。
- 与主会话的衔接：`code/lab/docclaims/claims.yml`（CRLF）、`doc/readme.md`、`code/lab/docker/**` 本步一律未动。
- Stage2 计划文档 §10.4 各门禁行与字典行的映射见特别结论 a/b 两表，可作 B0 收口评审底稿。

## 附录 · 复跑命令

```bash
# 0. 变量
PROM=http://127.0.0.1:8080/prom/api/v1
END=$(date -u +%s); START=$((END-86400))

# 1. 词表全表（2014 名基线）
curl -s "$PROM/label/__name__/values" -o names.json

# 2. 单指标 24h 接线探测（注意 label selector 语法，裸 ^name$ 是假阴性陷阱）
curl -s -G "$PROM/series" \
  --data-urlencode 'match[]={__name__=~"^jvm_gc_pause_seconds_count$"}' \
  --data-urlencode "start=$START" --data-urlencode "end=$END"

# 3. 取样（k6 等停更序列用 max_over_time，勿用裸即时查询判 0）
curl -s -G "$PROM/query" --data-urlencode 'query=max_over_time(k6_vus[24h])'

# 4. 抓取面现状
curl -s -G "$PROM/query" --data-urlencode 'query=up'

# 5. 批量审计（生成 tmp-metrics/audit.json，再跑 gen_dict.py 重建字典）
python tmp-metrics/audit.py

# 6. 专项
curl -s -G "$PROM/series" --data-urlencode 'match[]={__name__=~"^k6_.*$"}' \
  --data-urlencode "start=$START" --data-urlencode "end=$END" | \
  grep -o '"run_id":"[^"]*"' | sort -u          # k6 run_id 清单
grep -n exporter code/lab/docker/compose.ha.yml  # HA redis exporter 定义
sed -n 45,52p code/lab/docker/prometheus/prometheus.yml  # 唯一 redis job
```
