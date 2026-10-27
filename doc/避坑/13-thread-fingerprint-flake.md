# 并发构建让线程指纹测试抖动（相位锁步）

## 现象

`ThreadStateFingerprintTest.defaultDeadlockContentionReallyBlocksParticipants` 时绿时红：

```
[the drainable inversion must still show real BLOCKED windows]
Expecting value to be true but was false
```

本机单独跑 5 次全绿；机器上同时跑两个 Maven 构建时，5 次尝试（每次 2s）全部观测不到 `BLOCKED`。

## 根因

「可排空反转」的两个 worker 各自按同一节奏循环：

```
worker A: synchronized(A){sleep(hold)}  →  synchronized(B){sleep(hold)}
worker B: synchronized(B){sleep(hold)}  →  synchronized(A){sleep(hold)}
```

两者同时启动、时长相同，于是**相位锁步**：一个持 A 时另一个正好持 B，交替时各自去抢刚空出来的监视器，永远不会同时抢同一个监视器。`BLOCKED` 只能靠调度抖动偶然出现——负载越高，抖动越少，越不容易出现。

另外，早期采样用 `Thread.getAllStackTraces()`，在高负载下自身开销大，也会错过毫秒级窗口。

## 正确做法

1. **生产侧加相位偏移**：给奇数序 worker 在进入循环前多睡 `hold/2`，让两侧永远错开半个周期，保证每轮都在同一监视器上相撞（仍是可排空反转，`stop()` 能收）。
2. **测试侧用精准采样**：`ThreadMXBean.getThreadInfo(ids)` 只查目标线程，避免全量 dump；窗口 500ms、最多 5 次有界重试；断言不变（必须真观测到 `Thread.State.BLOCKED`，不能用「没挂」代替）。
3. 排查同类问题时先判断「实现没产生状态」还是「测试没抓到状态」——本例两者都占一半。

## 验证

```bash
# 单类连跑，期望 5/5 绿
for i in 1 2 3 4 5; do
  mvn -pl services/seckill-app-jdk21 -am test -Dtest=ThreadStateFingerprintTest \
      -Dsurefire.failIfNoSpecifiedTests=false >/tmp/tsf-$i.log 2>&1; echo "$i=$?";
done
```

## 出处

- `doc/process/step-32-thread-lab.md`「补充」节
- 修复：`a37abff`（相位偏移 + `ThreadMXBean` 采样）
