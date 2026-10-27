# 压测结果 · smoke · neg-threshold

> 日期：2026-10-26T07:06:01Z（结束 2026-10-26T07:06:07Z）
> 分支 / commit：`feature/ha-lab` / `0c00e09b37d38684efd795d4de872fab2e2477fd`
> run_id：`neg-threshold`
> 结论：**FAIL**（k6 exit 99；k6 exited 99 (crossed threshold or runtime failure)）
> 归档：`code/lab/k6/reports/neg-threshold/`

## 关键指标

| 指标 | 值 |
|---|---|
| iterations | 1 |
| iterations/s | 0.28 |
| http_reqs | 7 |
| RPS (avg) | 1.93 |
| http_req_duration avg | 374.40 ms |
| http_req_duration P95 | 832.64 ms |
| http_req_duration P99 | 927.30 ms |
| http_req_duration max | 950.97 ms |
| http_req_failed rate | 0.00% |
| flow_errors rate | 0.00% |
| server_errors rate | 0.00% |
| checks | 100.00% (5 pass / 0 fail) |
| seckill 409 (sold out / lock) | — |
| seckill 429 (rate limited) | — |
| seckill terminal failed | — |
| seckill poll timeouts | — |
| VUs max | 1 |

- 持续时间：6s
- k6 镜像：`grafana/k6:1.3.0`
- 远程写入：`http://prometheus:9090/api/v1/write`

## 阈值判定

| Metric | Rule | Observed | 结果 |
|---|---|---|---|
| `checks` | `rate>0.99` | rate=100.00% | PASS |
| `flow_errors` | `rate<0` | rate=0.00% | FAIL |
| `server_errors` | `rate<0` | rate=0.00% | FAIL |

## 运行信息

- 场景：`smoke`（`scenarios/smoke.js`）
- 时间窗（epoch）：1792998361000 → 1792998367000
- Run ID 查询：`k6_http_reqs_total{run_id="neg-threshold"}`
- Grafana：http://127.0.0.1:3000/d/ha-d4?from=1792998361000&to=1792998367000
- Prometheus：http://127.0.0.1:8080/prom/graph?g0.expr=k6_http_reqs_total%7Brun_id%3D%22neg-threshold%22%7D&g0.tab=0
- 数据重置：已执行，baseline stock=100（reset.log）

### Manifest 摘要

- verdict：`FAIL`（k6 exited 99 (crossed threshold or runtime failure)）
- k6 exit：99；image `grafana/k6:1.3.0`；output `experimental-prometheus-rw`
- git：`feature/ha-lab` / `0c00e09b37d38684efd795d4de872fab2e2477fd`
- reset：executed=true, baselineStock=100
- 阈值结果：3 条（FAIL 2 条）

### 环境覆盖

- `ERROR_RATE=0`

## 归档文件

- `report.html`：本页 HTML 报告
- `summary.json`：k6 `--summary-export` 原始输出
- `k6.log`：k6 标准输出/错误
- `manifest.json`：场景、运行时间、git、reset、阈值与 verdict
- `reset.log`：重置脚本输出
