# k6 `per-vu-iterations` 默认 `maxDuration=10m`：长时间线被静默截断

## 现象

`cache-lab` 带压测的 armed 演练（八种模式 × 90s dwell = 12 分钟时间线）跑完，
k6 **退出码 0**、阈值全过、checks 100%，看起来是一次成功运行。但日志里的模式切换
只有 **7 次**，第 8 个 dwell（`db-fallback`）从未出现：

```
execution: local
     scenarios: (100.00%) 2 scenarios, 11 max VUs, 12m5s max duration (incl. graceful stop):
              * modes: 1 iterations for each of 1 VUs (maxDuration: 10m0s, exec: modeTimeline, gracefulStop: 5s)
              * read: 10 looping VUs for 12m0s (exec: readSeededProduct, gracefulStop: 5s)

[cache-lab] t=+0s   mode=off            apps=3/3
[cache-lab] t=+90s  mode=null-cache     apps=3/3
...
[cache-lab] t=+540s mode=l1-cache       apps=3/3          # 到此为止

running (10m05.9s), 10/11 VUs, 29300 complete and 1 interrupted iterations
modes ✓ [ 100% ] 1 VUs 10m0s 0/1 iters, 1 per VU           # 100% 但 0/1 iters
```

于是三类后果同时发生，而且都不报错：

- `db-fallback` 从未被 arm，`limiter_rejections` 全程 0（限流演示直接消失）；
- 时间线末尾的 `final reset mode=off` 没执行，靠 `teardown()` 兜底才把三实例复位；
- 进度条显示 `modes ✓ [100%]`，把「被打断」渲染成了「跑完了」。

`1 interrupted iterations` 是唯一线索，而它在最后 40 分钟滚动进度里只出现一行。

## 根因

k6 的 `per-vu-iterations` 执行器有一个**默认 `maxDuration: 10m0s`**。
它和场景里其它时长互不相干：`read` 用的是 `constant-vus`，其 `duration` 由场景
自己按 `modes.length × CACHE_LAB_MODE_DURATION` 推导（`cache-lab.js` 的
`readDurationMs`），所以读流量跑满了 12 分钟；`modes` 没有声明 `maxDuration`，
就吃默认值，10 分钟一到被硬切。

坑的隐蔽性来自两点：

1. **默认 20s dwell 时不触发**（8 × 20s = 160s ≪ 10m），所以场景单测与短跑全绿；
2. 触发它的是**修复缺陷的那个参数**。`cache-lab` 要把雪崩重武装进对应 dwell，
   有 init 期硬门：`CACHE_LAB_MODE_DURATION ≥ CACHE_LAB_AVALANCHE_TTL_SECONDS + CACHE_LAB_ARM_MARGIN`
   （默认 60s + 15s = **75s**）。也就是说「能用到新特性」和「踩到这个默认值」是同一件事。

## 正确做法

1. **`maxDuration` 跟着 dwell 推导，不要相信默认值**。任何用
   `per-vu-iterations`（或其它无时长执行器）承载「按外部参数伸缩的长循环」的场景，
   都要显式给出预算：

   ```js
   // code/lab/k6/scenarios/cache-lab.js
   const timelineSlack = duration('CACHE_LAB_TIMELINE_SLACK', '30s');
   const timelineMaxMs = readDurationMs + timelineSlack.ms;
   // options.scenarios.modes.maxDuration = `${timelineMaxMs}ms`
   ```

   slack 也要外置（工程规约：不允许新增魔法值），因为末尾还有一次
   `final reset mode=off` 与若干次 actuator 采样。
2. **判断这类问题时别看退出码**。`exit 0` 与 `checks 100%` 对「循环被截断」完全无感。
   要对**事件计数**：`grep -c 't=+'` 是否等于 dwell 数、
   `cache_lab_mode_switches` 是否等于 `modes.length + 1`、Prometheus 里
   `k6_cache_lab_*{mode="db-fallback"}` 序列**存不存在**（缺序列比值为 0 更能说明问题）。
3. **进度条的 `✓ [100%]` 不是完成证明**。`N/N iters` 才是；
   `0/1 iters ... 1 interrupted` 就是被打断。
4. 加回归测试钉住契约：`code/lab/k6/test/cache-lab-scenario.test.mjs`
   断言 `options.scenarios.modes.maxDuration` 严格大于 `readDurationMs`，
   并随 `CACHE_LAB_TIMELINE_SLACK` 变化。

## 验证

```bash
node --test "code/lab/k6/test/*.test.mjs"        # 79 pass / 0 fail（含新增预算断言）

# 默认 5s dwell：预算 8×5s+30s = 70s
# 90s dwell：预算 8×90s+30s = 750s
LAB_TOKEN="$TOKEN" CACHE_LAB_MODE_DURATION=90s bash code/lab/k6/run.sh cache-lab
# 期望 8 条 `[cache-lab] t=+Ns mode=...`，最后一条是 t=+630s mode=db-fallback，
#      加一条 `final reset mode=off`，且 `0 interrupted iterations`
```

## 出处

- `code/lab/k6/reports/20261027-175754-cache-lab-armed/run.log`（被截断那次：7 次切换、`maxDuration: 10m0s`、`1 interrupted`）
- `code/lab/k6/scenarios/cache-lab.js`（`timelineSlack` / `timelineMaxMs` 与 `options.scenarios.modes.maxDuration`）
- `code/lab/k6/test/cache-lab-scenario.test.mjs`（预算断言）
- `doc/process/step-56-cache-lab-defect-arming.md`「真机运行」节
- 修复提交 `96939cf`
