# 线程实验室快照分析模板

> 本文件由 app 在抓取快照时自动复制进每个快照目录（design §9.3）。
> 快照内容：`jcmd Thread.print -l`（`thread-dump.txt`）、`jcmd GC.heap_info`（`heap-info.txt`）、
> `jcmd GC.class_histogram`（`class-histogram.txt`）、`metadata.json`。
> 仓库内的同源副本：`doc/process/Stage1/snapshot-analysis-template.md`（有测试保证两者一致）。

## 0. 先读 metadata.json

- `instance` / `trigger`（freeze | manual）：这份快照属于哪个实例、由谁触发。
- `state` + `stateCode` + `step`：抓取瞬间演示线程的真实 JVM 状态与已完成步数。
- `jvmVersion` / `jvmVendor` / `uptimeMs` / `pid` / `capturedAt`：JVM 版本、已运行时长、抓取时刻。
- 先记录结论：**哪个实例、状态机哪一步、JVM 跑了多久**；后面的三份原始输出都以此为坐标。

## 1. thread-dump.txt（`jcmd <pid> Thread.print -l`）

1. 首行是抓取时间与 JVM 版本，确认和 metadata 对得上。
2. 线程状态分布（Linux / Git Bash）：

   ```bash
   grep "java.lang.Thread.State:" thread-dump.txt | sort | uniq -c | sort -rn
   ```

3. 找演示线程：`grep -A 20 '"thread-lab-demo"' thread-dump.txt`，对照 §9.2 的六态：
   - `RUNNABLE`：可能正在自旋 gate（教学点：JVM 的 RUNNABLE 含"就绪"）。
   - `BLOCKED (on object monitor)`：等 `synchronized`；栈里会有 `waiting to lock <0x...>` 与 `- locked` 的持有者。
   - `WAITING (on object monitor)`：`Object.wait()` 无超时。
   - `TIMED_WAITING (sleeping)`：`Thread.sleep`。
4. `-l` 的额外信息：`Locked ownable synchronizers`（ReentrantLock / Condition / 虚拟线程 carrier），
   这是 `jstack` 无参版本看不到的部分。
5. 死锁：`grep "Found one Java-level deadlock" thread-dump.txt`；命中后按下面的线程对逐条读锁链。
6. `nid=0x...` 是操作系统线程号（十六进制）。容器内 `top -H -p 1` 得到的 TID 转十六进制后可回查栈，
   这是 `cpu-burn` 定位的关键一步。

## 2. heap-info.txt（`jcmd <pid> GC.heap_info`）

- 结构：`garbage-first heap`（G1）或 `PSYoungGen` 等分区，total / used / free。
- 用法：与故障注入前、注入后各一次的 `used` 对比，判断 `heap-leak` 是否阶梯上升。
- 注意：`used` 包含尚未回收的垃圾；单次读数不能推出泄漏，必须看趋势 + Full GC 前后对比。

## 3. class-histogram.txt（`jcmd <pid> GC.class_histogram`）

- 第 1 行是 `#instances` / `#bytes` 总量；`head -20 class-histogram.txt` 看 Top 类。
- `heap-leak` / `oom`：`[B`（byte[]）实例数与字节数应显著上涨；`thread-leak`：`Thread`、
  `ThreadLocal`、`VirtualThread` 相关条目上涨，而线程 dump 里线程数同步上升。
- 直方图按实例数排序，含垃圾对象，同样需要两份快照对比。

## 4. 对照实验（约 5 分钟）

1. 基线：`POST /lab/thread/lifecycle/start` 后 `POST /lab/thread/snapshot`
   （或用 `freeze`，它会暂停推进并自动抓拍）。
2. 注入：`POST /lab/thread/fault {"mode":"heap-leak"}`（或 `deadlock` / `cpu-burn`），等 10s。
3. 再抓一份（`freeze` 或 `POST /lab/thread/snapshot`）。
4. 对比两份 bundle：线程状态分布、Top 类、堆 used；在 Grafana 时间轴上找到 `snapshot` 标签的
   annotation，把指标曲线与快照时刻对齐。

## 5. 结论记录（写在本节）

- 症状（哪个指标、什么形状）：
- 证据（文件 + 行号/类名/线程名）：
- 与 Grafana 面板和 annotation 的对应关系：
- 教学结论（该故障的"观察 → 处理 → 对比"闭环）：

## 6. 保留与清理

- 保留策略由 `app.lab.thread.snapshot.max-count` / `max-age-seconds` 控制，超出的目录会被自动删除。
- 快照目录默认在 `doc/process/Stage1/snapshots/`（已被 `.gitignore` 忽略）；容器内路径为
  `/app/doc/process/Stage1/snapshots`，可用 `docker cp jl-app-1:/app/doc/process/Stage1/snapshots/<dir> .` 取出。
