# k6 远程写的 url 标签把 Prometheus 打爆

## 现象

负载实验进行到中段，实时监控页面的 Prometheus 查询开始变慢直到超时；`jl-prometheus` 随后被重启（`RestartCount=1`、`ExitCode=0`、`OOMKilled=false`）。重启日志里留着上次没跑完的查询：

```
level=INFO source=query_logger.go:85 msg="These queries didn't finish in prometheus' last run:"
  queries="[{\"query\":\"sum(executor_queued_tasks{job=\\\"seckill-app\\\"})\"...},
            {\"query\":\"max by (instance) (jvm_gc_pause_seconds_max{job=\\\"seckill-app\\\"})\"...}]"
```

同时 Tempo 侧每个抓取都超时：

```
level=warn caller=server.go:2171 msg="GET /metrics 51.33s, error: write tcp ...: i/o timeout
  X-Prometheus-Scrape-Timeout-Seconds: 5;"
level=warn caller=server.go:2171 msg="GET /metrics 1m58.49s, ... i/o timeout"
```

关键数字（`docker inspect` + Prometheus 自身接口）：

| 证据 | 值 |
|---|---|
| `process_resident_memory_bytes{job="prometheus"}` | **460 MB**，容器 `mem_limit: 512m` |
| `/api/v1/status/tsdb` `headStats.numSeries` | **209,258** |
| 单个 `k6_http_req_*` 指标族的序列数 | **8,151**（`k6_http_req_duration_max`、`k6_http_req_failed_rate` …） |
| 同一窗口内 k6 实际请求数 | 188,168（500 VU、313 req/s） |

## 根因

k6 的 `experimental-prometheus-rw` 输出把**每条指标的每个 tag 变成 Prometheus label**。默认 system tags 含 `url` 与 `name`，而秒杀压测的 URL 带商品 ID（`/api/seckill/{productId}`），一次负载实验就有 8k 个不同 URL。于是每个 k6 HTTP 指标族膨胀到 8k 条序列，20 多个族合计 20 万+，全部堆在 head 里（head 压缩 2 小时一次才回收），把 512MB 的 Prometheus 顶到 90%。

Tempo 的 `/metrics` 慢是同一场事故的另一半：应用用 OTel agent 默认 **100% 采样**，500 VU 的全部 span 涌进单机 Tempo，压缩/写入把 `/metrics` 拖到 1–2 分钟；Prometheus 全局 `scrape_timeout: 5s` 每 5s 再来一次，双向互相拖累。

## 正确做法

1. **在 k6 侧限制 system tags**（`code/lab/k6/lib/config.js` 的 `baseOptions`），只留面板和字典真正查询的低基数标签：

   ```js
   systemTags: ['status', 'method', 'expected_response', 'error', 'error_code'],
   ```

   `url`/`name`/`proto`/`tls_version` 全部丢弃；自定义 `run_id`/`flow`/`instance`/`traffic_mode` 不受影响，D2/D4/D7 面板只按 `run_id` 过滤，无需改动。

2. **降低 trace 采样**（`compose.base.yml`，默认 10%，可外置）：

   ```yaml
   OTEL_TRACES_SAMPLER: ${OTEL_TRACES_SAMPLER:-parentbased_traceidratio}
   OTEL_TRACES_SAMPLER_ARG: ${OTEL_TRACES_SAMPLER_ARG:-0.1}
   ```

3. **Tempo 自身指标降频**：`prometheus.yml` 的 tempo job 改 `scrape_interval: 60s` / `scrape_timeout: 20s`（没有面板消费 Tempo 自身指标）。

4. **给 Prometheus 留余量**：`mem_limit` 与 retention 外置为 `${PROMETHEUS_MEM_LIMIT:-768m}` / `${PROMETHEUS_RETENTION:-7d}`。

## 判断与验证

- 看 `GET /prom/api/v1/status/tsdb` 的 `headStats.numSeries`：正常应在 2 万以内；单族 8k 即命中本坑。
- 跑一次 load 后对比 `process_resident_memory_bytes{job="prometheus"}`；同时确认 `k6_vus`/`k6_iterations_total` 面板仍有数据。
- `docker inspect jl-prometheus` 里 `OOMKilled=false` 不等于没出事：容器被外部重启（Exit 0）时同样会丢查询，必须回到 tsdb/RSS 找证据。

## 出处

- k6 选项：`code/lab/k6/lib/config.js`（`baseOptions.systemTags`）
- 应用采样：`code/lab/docker/compose.base.yml`（`OTEL_TRACES_SAMPLER*`）
- 抓取配置：`code/lab/docker/prometheus/prometheus.yml`（tempo job）
- 容量配置：`code/lab/docker/compose.base.yml`（`PROMETHEUS_MEM_LIMIT` / `PROMETHEUS_RETENTION`）
- 实测记录：2026-10-29 负载 run（`run-20261029-005`，188,168 请求）后的只读取证
