# 压测结果 · load · pen-check

> 日期：2026-10-30T02:07:17Z（结束 2026-10-30T02:07:42Z）
> 分支 / commit：`feature/stage2-workbench` / `d42785629dcc508497c37ef80b3345fe1596b03f`
> run_id：`pen-check`
> 结论：**PASS**（k6 exit 0；k6 exit 0 and every configured threshold passed）
> 归档：`code/lab/k6/reports/pen-check/`

## 关键指标

| 指标 | 值 |
|---|---|
| iterations | 303 |
| iterations/s | 14.42 |
| http_reqs | 323 |
| RPS (avg) | 15.38 |
| http_req_duration avg | 11.20 ms |
| http_req_duration P95 | 22.19 ms |
| http_req_duration P99 | 35.00 ms |
| http_req_duration max | 46.38 ms |
| product read P95 | 22.33 ms |
| product read P99 | 35.25 ms |
| http_req_failed rate | 0.00% |
| flow_errors rate | 0.00% |
| server_errors rate | 0.00% |
| checks | 100.00% (323 pass / 0 fail) |
| seckill 409 (sold out / lock) | — |
| seckill 429 (rate limited) | — |
| seckill terminal failed | — |
| seckill poll timeouts | — |
| VUs max | 20 |

- 持续时间：26s
- k6 镜像：`grafana/k6:1.3.0`
- 远程写入：`http://prometheus:9090/api/v1/write`

## 阈值判定

| Metric | Rule | Observed | 结果 |
|---|---|---|---|
| `flow_errors` | `rate<0.01` | rate=0.00% | PASS |
| `http_req_duration{flow:product}` | `p(95)<300` | p(95)=22.33ms | PASS |
| `http_req_duration{flow:product}` | `p(99)<800` | p(99)=35.25ms | PASS |
| `server_errors` | `rate<0.01` | rate=0.00% | PASS |

## 运行信息

- 场景：`load`（`scenarios/load.js`）
- 时间窗（epoch）：1793326036000 → 1793326062000
- Run ID 查询：`k6_http_reqs_total{run_id="pen-check"}`
- Grafana：http://127.0.0.1:3000/d/ha-d4?from=1793326036000&to=1793326062000
- Prometheus：http://127.0.0.1:8080/prom/graph?g0.expr=k6_http_reqs_total%7Brun_id%3D%22pen-check%22%7D&g0.tab=0
- 数据重置：跳过（--no-reset）

## 镜像身份（最小记录，B0-08）

| 容器 | imageTag | imageId | 状态 |
|---|---|---|---|
| `jl-app-1` | `java-learn-lab/seckill-app:jdk21` | `sha256:408b427b685cace773e9f427063e4517c7024a4cf5649b6e2856f000f1a5c96b` | running |
| `jl-app-2` | `java-learn-lab/seckill-app:jdk21` | `sha256:408b427b685cace773e9f427063e4517c7024a4cf5649b6e2856f000f1a5c96b` | running |
| `jl-app-3` | `java-learn-lab/seckill-app:jdk21` | `sha256:408b427b685cace773e9f427063e4517c7024a4cf5649b6e2856f000f1a5c96b` | running |
| `jl-chaos-controller` | `java-learn-lab/chaos-controller:jdk21` | `sha256:3196662158e6fa986157a418cd741fc864e941e8cbd97b88ab628d1a8c04936a` | running |

- 取数方式：`docker inspect --format {{.Image}} + docker image inspect --format {{.RepoTags}}`（记录时间 2026-10-30T02:06:54Z，dockerAvailable=true）
- 口径：**只记录 tag/id**，不校验镜像来源、不归档构建日志、不锁 base digest（完整溯源在 Future）。
- 重建走显式 `--force-recreate`（`code/lab/scripts/tune.sh`），重建后应重新记录一次。


### Manifest 摘要

- verdict：`PASS`（k6 exit 0 and every configured threshold passed）
- k6 exit：0；image `grafana/k6:1.3.0`；output `experimental-prometheus-rw`
- git：`feature/stage2-workbench` / `d42785629dcc508497c37ef80b3345fe1596b03f`
- reset：executed=false, baselineStock=100
- 阈值结果：4 条（FAIL 0 条）

### 环境覆盖

- `LOAD_DURATION=20s`
- `LOAD_VUS=20`
- `LOAD_START_VUS=5`
- `PRODUCT_ID_MIN=9000`
- `PRODUCT_ID_MAX=11000`

## 归档文件

- `report.html`：本页 HTML 报告
- `summary.json`：k6 `--summary-export` 原始输出
- `k6.log`：k6 标准输出/错误
- `manifest.json`：场景、运行时间、git、reset、阈值、verdict 与镜像身份
- `image-identity.json`：关键容器的 imageTag + imageId 原始记录
