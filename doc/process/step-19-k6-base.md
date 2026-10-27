> 步骤交接 · M2-1 k6 基础场景与 remote write
> 日期：2026-10-26
> 分支 / HEAD：`feature/ha-lab` / `feat: add k6 base scenarios and remote write`（父提交 `f11a9bc`）
> 关联任务：#19（M2-1）
> 测试 / verify：无 Java 代码变更、未编译；k6 smoke 绿、load 2m 绿、spike 短跑绿、stress/soak 短跑绿；负向阈值退出 99；`verify.sh` **96 PASS / 1 WARN**（用 `SECKILL_PRODUCT_ID=2`，见缺陷项）
> 运行状态：16 个 `lab=java-learn` 容器运行中、三实例健康；product 1 DB 77、product 2 DB/Redis 99、product 20 DB/Redis **0**（spike 打空）、订单 124
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

# 当前进展

- Task 19 完成：新增 `code/lab/k6/`，包含共享 config/http/flow 库、五个基础场景（smoke/load/stress/spike/soak）、dockerized `run.sh` 与完整 README；k6 通过 `experimental-prometheus-rw` 写入 Prometheus，所有指标带 `run_id`。
- 固定镜像 **`grafana/k6:1.3.0`**（`sha256:3ddc8b1a33a2c3d8edc6e99b6a762ae36cba08788463458f5e6a7703e14eb77d`），已 `docker pull` 并实跑验证；未使用 `latest`。
- 默认在 `jl-net` 内运行，`BASE_URL=http://nginx`；`K6_NETWORK=host` 时 `BASE_URL` 默认切到 `http://127.0.0.1:8080`，也可用 `BASE_URL=http://host.docker.internal:8080`。
- 未改应用代码、未重建应用镜像、未改 `verify.sh`；未触碰 Task 18/17 的 review 分支或 worktree。
- 验收实跑：
  - smoke：**exit 0**，5 checks 全过、7 requests，run_id `k6-20261026132531-8977`；提交后最终复跑 **exit 0**（5 requests，run_id `k6-20261026140400-16603`，product 20 已空故 409=1）；
  - load 2m：**exit 0**，18,195 requests / 17,995 iterations，product P95 **5.63ms**、P99 **11.56ms**，错误 0，run_id `k6-20261026132550-20193`；
  - spike 短跑（5s baseline + 5s ramp + 20s hold + 5s down，800 VU）：**exit 0**，18,815 requests，409=**4,366**、429=**13,391**、202=86，错误 0，run_id `k6-20261026132911-21845`；
  - stress 短跑（5s/5/5s，10 VU）：exit 0，无阈值，88 requests；
  - soak 短跑（10s/3 VU，另 5s 复测）：exit 0，阈值全过；
  - 负向阈值：`ERROR_RATE=0 bash run.sh smoke` **exit 99**，`flow_errors`/`server_errors` 的 `rate<0` 恒不成立，证明阈值失败会传播非零退出；run_id `k6-20261026133110-8136`。
- Prometheus 已确认 `k6_http_reqs_total{run_id=...}`、`k6_http_req_duration_p95/p99/max`、`k6_flow_errors_rate`、`k6_server_errors_rate`、`k6_checks_rate`、`k6_seckill_expected_409_total`、`k6_seckill_expected_429_total` 等序列带 `run_id`。
- 未跑默认 10m load / 12m stress / 30m soak；按验收要求跑 2m load 与短 spike，并用短 profile 验证 stress/soak 语法与执行。
- 下一步：M2-2（bench/compare/报告归档与 D9）。

# 完成功能项

## 1. 目录与共享库

- `code/lab/k6/lib/config.js`：所有 URL/路径前缀/产品 id/用户前缀/时长/VU/阈值/超时/轮询次数/状态列表/摘要统计均由环境变量覆盖；缺失时使用集中默认值并 fail-fast 校验。
  - `RUN_ID` 在 `run.sh` 生成并传入所有 VU；直接 `k6 run` 时 config 也会生成（见缺陷项）。
  - `BASE_URL` 默认 `http://nginx`，去尾部 `/` 并校验 `http(s)`。
  - `PRODUCT_ID=1`、`SECKILL_PRODUCT_ID=20`、`ORDER_QUANTITY=1`、`SECKILL_QUANTITY=1`、`USER_PREFIX=k6`。
  - `THINK_TIME=1s`、`HTTP_TIMEOUT=10s`、`GRACEFUL_STOP=5s`、`GRACEFUL_RAMP_DOWN=10s`。
  - `SECKILL_POLL_ATTEMPTS=30`、`SECKILL_POLL_INTERVAL=1s`、`SECKILL_EXPECTED_STATUSES=200,202,409,429`。
  - `P95_MS=300`、`P99_MS=800`、`ERROR_RATE=0.01`、`CHECKS_RATE=0.99`。
  - `SUMMARY_TREND_STATS=avg,min,med,p(90),p(95),p(99),max`、`SUMMARY_TIME_UNIT=ms`。
  - 路径均可覆盖：`LOGIN_PATH`、`PRODUCTS_PATH`、`ORDERS_PATH`、`SECKILL_ORDERS_PATH`。
- `code/lab/k6/lib/http.js`：统一 `http.request` 包装；每个请求带 `run_id` + `flow` tag；`http.expectedStatuses(...)` 标记预期状态；自定义 `flow_errors`/`server_errors` Rate、`seckill_expected_409`/`seckill_expected_429` Counter、`seckill_terminal_failed`/`seckill_poll_timeouts` Counter；每次请求一个 check。
- `code/lab/k6/lib/flow.js`：
  - `login`：`POST /api/login`，唯一 userId，token 存 VU 内存，后续 `Authorization: Bearer`；
  - `product`：`GET /api/products/{PRODUCT_ID}`，断言 200/code 0/id；
  - `order`：`POST /api/orders` 唯一 `Idempotency-Key`，断言 200/code 0/id；`replay:true` 同 key 重放并断言同 id（smoke）；
  - `seckill`：`POST /api/seckill/orders` 唯一 key，接受 200/202/409/429；200 断言 CREATED+orderId；202 轮询 `GET /api/seckill/orders/{requestId}` 至 CREATED/FAILED，尝试次数与间隔外置；超时记失败 check 并计数。

## 2. 五个场景

- `scenarios/smoke.js`：`per-vu-iterations`，1 VU × 1 iteration，`maxDuration=SMOKE_DURATION(30s)`；一次跑完 login→product→order→replay→seckill→poll；阈值 error<1%、checks>99%。
- `scenarios/load.js`：`ramping-vus`，从 `LOAD_DURATION` 统一派生三段（20% 到 50、20% 到 200、60% 保持）；只读 product；阈值 error<1%、`http_req_duration{flow:product}` P95<300ms/P99<800ms。
- `scenarios/stress.js`：`STRESS_STAGES` JSON（默认 1m/50、2m/100、2m/200、2m/300、2m/400、2m/500、1m/0）；只读 product；**无阈值**，用于看拐点。
- `scenarios/spike.js`：默认 baseline 50 持续 30s → 10s 冲到 800 → 60s 保持 → 10s 回落；打秒杀产品；阈值 5xx/error<1%；409/429 只计数不报错。
- `scenarios/soak.js`：`constant-vus`，100 VU × `SOAK_DURATION(30m)`；只读 product；error<1%、checks>99%。

所有场景共用 `baseOptions`：`summaryTrendStats`、`summaryTimeUnit`、`tags.run_id`；没有额外的自定义输出/报告。

## 3. `run.sh`

- `set -euo pipefail`；`K6_IMAGE`、`K6_NETWORK`、`K6_DIR`、`SCENARIO`、`RUN_ID`、`BASE_URL`、`PROM_URL` 均可覆盖。
- 验证镜像本地存在（缺失时提示 `docker pull`，不自动使用 latest）、验证 `scenarios/<name>.js` 存在、把 `K6_DIR` 归一化为绝对路径。
- 仅把已设置的环境变量显式传给容器；`K6_PROMETHEUS_RW_SERVER_URL`、`K6_PROMETHEUS_RW_TREND_STATS=p(95),p(99),max`、`K6_PROMETHEUS_RW_PUSH_INTERVAL=5s` 默认注入。
- 打印 scenario/run_id/base_url/network/image/remote write/PromQL/Graph URL；Graph URL 对查询做 URL 编码。
- 兼容 Git Bash：mount source 用 `cygpath -m`，docker 调用加 `MSYS_NO_PATHCONV=1`，避免 `/scripts` 被改写成 Windows 路径。
- 直接 `exit` k6 的退出码；实测 smoke 0、负向阈值 99。

## 4. README

- `code/lab/k6/README.md`：前提、固定镜像与 digest、全部环境变量默认值、五个场景命令、409/429 语义、阈值与负向测试、remote-write 查询（含实际 `*_p95/_p99/_max` 名称）、WSL2/Docker 注意事项、Task 20 边界。

# 设计逻辑

- **配置唯一来源**：场景只做流程编排，URL/路径/id/时长/VU/阈值/超时/轮询全部从 `lib/config.js` 读取；scripts 里不出现运行期魔法值。评审的配置外置检查只会在 config 默认值处看到具体值。
- **一次 run 一个 run_id**：`run.sh` 在宿主机生成 `RUN_ID` 并显式 `-e` 给容器，避免 k6 每个 VU 的 init 各自生成不同 id；`options.tags` 让 HTTP、自定义 Rate/Counter、checks、iterations、VUs 全部带 `run_id`。
- **预期状态不是错误**：秒杀用 `http.expectedStatuses(200,202,409,429)` 作为 `responseCallback`，因此 409/429 的 `http_req_failed=0`、`expected_response=true`；再用 Counter 单独报告业务拒绝。这样 spike 的 13k 429 不会污染 5xx/error 判定。
- **错误率口径**：`flow_errors` 按请求累计（含轮询），任何 unexpected status/断言失败/transport error 都计入；`server_errors` 只计 5xx/0。spike 的 409/429 不计入二者。
- **只读场景避免耗库存**：load/stress/soak 只 `GET /api/products/1`，登录仅在每 VU 首次迭代；smoke 用 per-vu-iterations 保证一次运行只产生一单/一次秒杀，而不是 30s 内循环下单；spike 是唯一允许打空 product 20 的场景。
- **时长派生**：load 只给一个 `LOAD_DURATION`，按 20%/20%/60% 生成三段，2m override 自动得到 24s/24s/72s；避免同时维护总时长和阶段时长导致不一致。stress 用 JSON stages，便于后续场景 JSON 复用。
- **异步契约**：202 必须带 requestId，轮询到 CREATED 必须有 orderId；FAILED 是终态但单独计数；耗尽 attempts 记失败 check。200 表示 mq toggle 关闭的同步路径，仍接受并断言 CREATED。
- **阈值传播**：k6 1.3 默认 threshold 失败即中止并返回非零（实测 99），`run.sh` 不吞退出码；负向测试用 `ERROR_RATE=0` 让 `rate<0` 恒假，不依赖环境偶发错误。
- **远程写入标签**：Trend 以 `_p95/_p99/_max` 等独立序列导出（不是 native histogram），与运行环境 `K6_PROMETHEUS_RW_TREND_STATS` 一致；查询文档使用实测名称。
- **镜像固定**：`grafana/k6:1.3.0` 有 digest 记录，避免 `latest` 漂移造成压测结果不可复现。

# 存在缺陷项&TODO

- **product 20 已被 spike 打空**：DB/Redis 均为 0；默认 `smoke.sh` / `verify.sh` 的秒杀段现在会得到 409 并失败（这是 spike 的预期数据后果）。本次 verify 用 `SECKILL_PRODUCT_ID=2` 得到 96 PASS / 1 WARN。Task 20/UI reset 应补一键重置/重新播种；在此之前跑默认 verify 前需先恢复 product 20 的 DB 与 Redis 库存。
- **尚无 reset.sh**：设计中有 `reset.sh`，本任务未实现；手工改 DB/Redis 库存可能造成两边漂移（交接文档已有 Redis restore 非原子缺陷）。
- **直接 k6 运行的 run_id 兜底**：`lib/config.js` 在未传 `RUN_ID` 时会生成 id，但 k6 init 每个 VU 执行一次，多 VU 直跑可能得到不同 id；`run.sh` 总是生成一个并传入。README 已要求直跑显式设置 `RUN_ID`。
- **spike 的 409 聚合了两种业务原因**：`k6_seckill_expected_409_total=4366` 包含 sold out + lock timeout；应用侧 `seckill_attempts{result}` 才区分（短跑窗口内约 sold_out 1519、lock_timeout 2859）。若要 k6 侧区分，需要按错误码/响应 message 增加低基数 counter。
- **`name`/`url`/`status`/`check` 标签基数**：spike 短跑产生约 2,084 条 `run_id` 序列（smoke 130、load 58）；默认 Prometheus 保留 7d，实验室可接受，大规模跑需收窄 URL/name 标签。
- **smoke 不是持续 30s**：`per-vu-iterations` + `maxDuration=30s` 使一次 smoke 实际约 1s 结束；这是为了避免 30s 循环消耗大量库存，与设计文字“1 VU × 30s”有语义差异，已在 README 说明。
- **stress 无阈值**：只记录 summary/remote-write，拐点需要 Task 20 或人工按时间窗分析；本任务未实现自动拐点断言。
- **soak 未跑默认 30m**：只用 10s/5s 验证执行与阈值；堆/线程趋势判断按设计留给 Task 20 的压测后脚本。
- **无报告归档/D9**：按任务边界未做；没有 `summary.json`、HTML、manifest。
- **run.sh 依赖本地镜像**：不自动 pull，镜像缺失时要求手工 pull；这是为了避免隐式网络行为。
- **Docker Desktop host 网络**：`K6_NETWORK=host` 在部分 Docker Desktop 配置不可用；容器内访问宿主机应优先 `host.docker.internal`，README 已说明。
- **verify 未做默认 product 20 回归**：受上述库存耗尽影响；应用代码未改，verify 本身未改，`SECKILL_PRODUCT_ID=2` 的 96/1 证明现有检查逻辑仍通过。
- **OCR 审核未执行**：当前 `ocr llm test` 仍缺 LLM endpoint/token（见阶段交接）；本任务未用其他模型冒充审核。

# 待定事项和注意事项

- **下一步 M2-2**：bench/compare/报告归档与 D9。复用本目录的 `run_id`、remote-write 查询与五个场景；在编排里加入数据重置/重新播种，尤其是 product 20，避免默认 smoke/verify 因 409 失败。
- **运行状态**：16 个容器运行中，三实例与 Nginx/Prometheus/Grafana/chaos-controller 健康；未重建应用镜像，未改 compose。
- **数据状态**：product 1 DB 77、product 2 DB/Redis 99、product 20 DB/Redis 0、订单 124；`idempotent_reject` 等业务指标继续累积。无 reset 脚本，演示前需知悉。
- **Prometheus 查询窗口**：instant query 默认只看 5 分钟回看；查历史 run 需带 `time=<unix>` 或改用 range query。远程写入的 trend 序列名是 `k6_http_req_duration_p95/_p99/_max`，不是 `quantile` 标签。
- **k6 资源**：spike 800 VU 在 Docker Desktop/WSL2 上短跑约 35s 完成；正式 60s spike 需确认宿主机内存/CPU，避免和 16 个容器争抢。
- **库存边界**：不要用 load/stress/soak 打 `SECKILL_PRODUCT_ID`；它们只读 product。smoke/spike 会消耗库存。
- **安全边界**：README 不写密码/token；run.sh 只传 k6 配置与 remote-write 参数，不传 `.env`/数据库凭据。
- **提交**：功能提交 `feat: add k6 base scenarios and remote write`；文档提交随后记录本文件与阶段交接。报告在 `.superpowers/sdd/ha-lab/task-19-report.md`（gitignored）。

# 附录

## 关键文件

- `code/lab/k6/lib/config.js`（新增）
- `code/lab/k6/lib/http.js`（新增）
- `code/lab/k6/lib/flow.js`（新增）
- `code/lab/k6/scenarios/{smoke,load,stress,spike,soak}.js`（新增）
- `code/lab/k6/run.sh`（新增）
- `code/lab/k6/README.md`（新增）

## 复现命令

```bash
bash code/lab/k6/run.sh smoke
LOAD_DURATION=2m bash code/lab/k6/run.sh load
SPIKE_BASELINE_DURATION=5s SPIKE_RAMP_UP=5s SPIKE_DURATION=20s SPIKE_RAMP_DOWN=5s \
  bash code/lab/k6/run.sh spike
STRESS_STAGES='[{"duration":"5s","target":5},{"duration":"5s","target":10},{"duration":"5s","target":0}]' \
  bash code/lab/k6/run.sh stress
SOAK_DURATION=10s SOAK_VUS=3 bash code/lab/k6/run.sh soak
set +e; ERROR_RATE=0 bash code/lab/k6/run.sh smoke; echo $?; set -e  # 99
SECKILL_PRODUCT_ID=2 bash code/lab/scripts/verify.sh                    # 96/1
```

## 证据摘要

- 镜像：`grafana/k6:1.3.0`，digest `sha256:3ddc8b1a...e14eb77d`；`k6 version` = `v1.3.0+dirty`。
- smoke run_id `k6-20261026132531-8977`：7 requests、5 checks、exit 0；Prometheus `k6_http_req_duration_p95{flow="product"}=17.417ms`。
- 最终提交后 smoke run_id `k6-20261026140400-16603`：5 requests、5 checks、exit 0；product 20 已空，`seckill_expected_409=1` 仍按预期通过。
- load run_id `k6-20261026132550-20193`：18,195 requests、17,995 iterations、P95=5.629ms、P99=11.565ms、errors=0、exit 0。
- spike run_id `k6-20261026132911-21845`：18,815 requests、17,843 iterations、409=4,366、429=13,391、202=86、errors=0、exit 0；`k6_vus_max=800`。
- 负向 run_id `k6-20261026133110-8136`：exit 99，`thresholds on metrics 'flow_errors, server_errors' have been crossed`。
- `verify.sh`：`VERIFY PASSED: 96 checks passed, 1 warning(s)`（`SECKILL_PRODUCT_ID=2`）。
- Prometheus 序列数：smoke 130、load 58、spike 2,084（按 `run_id` count）。
