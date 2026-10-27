# `K6_SUMMARY_EXPORT` 是容器内路径：填宿主机路径要等整轮跑完才报错

## 现象

`cache-lab` 一轮 12 分钟的演练跑完，退出码 **107**，报告目录里只有日志没有 summary：

```
level=error msg="failed to handle the end-of-test summary"
  error="Could not save some summary information:
        - could not open 'summary.json': open summary.json: read-only file system"
EXIT=107
```

触发方式是按直觉写的：

```bash
K6_REPORT_DIR="$DIR" K6_SUMMARY_EXPORT=summary.json bash code/lab/k6/run.sh cache-lab
```

`summary.json` 看起来是「文件名，交给运行器拼到报告目录里」，实际不是。

## 根因

k6 容器以 `-v "$K6_MOUNT_DIR:/scripts:ro" -w /scripts` 启动：**工作目录是只读挂载的
`/scripts`**。所以裸文件名会解析成 `/scripts/summary.json`，落在只读文件系统上。
真正可写的挂载点是 `K6_REPORT_MOUNT`（默认 `/reports`），它才对应宿主侧的
`K6_REPORT_DIR`。

两个放大器让它特别贵：

1. k6 在**测试结束后**才写 summary，所以这个错误一定在整轮跑完之后才暴露，
   前面 12 分钟的流量与时间线全部白跑（远端写入 Prometheus 的序列还在，
   但归档与本地阈值判定证据没了）；
2. 退出码非 0 出现在最后一行，容易误读成「阈值没过」，而阈值其实全过。

## 正确做法

1. `K6_SUMMARY_EXPORT` 给**容器内路径**，且必须在报告挂载点下：

   ```bash
   K6_REPORT_DIR="$DIR" K6_SUMMARY_EXPORT=/reports/summary.json bash code/lab/k6/run.sh cache-lab
   ```

   `K6_REPORT_DIR` 才是宿主机路径，两者配对使用。
2. `code/lab/k6/run.sh` 现在在启动前 fail-fast，不用等跑完才发现（`3e7a9ed`）：

   ```bash
   if [[ -n "$K6_SUMMARY_EXPORT" && "$K6_SUMMARY_EXPORT" != "$K6_REPORT_MOUNT"/* ]]; then
     printf 'ERROR: K6_SUMMARY_EXPORT is a path INSIDE the k6 container, not on the host: it must start with %s/ (for example %s/summary.json).\n' ...
     exit 2
   fi
   ```

   同一条检查也保证了「只给 `K6_SUMMARY_EXPORT` 却没给 `K6_REPORT_DIR`」会被拒。
3. 通用判断：`run.sh` 里凡是「宿主机侧」的路径参数都叫 `*_DIR`，
   「容器内」的都叫 `*_MOUNT` / 绝对路径。给路径前先确认它属于哪一侧——
   同一份脚本里两种口径混着，是这类坑的源头。

## 验证

```bash
# 立刻失败，不启动容器
K6_REPORT_DIR=/tmp/x K6_SUMMARY_EXPORT=summary.json bash code/lab/k6/run.sh smoke
# ERROR: K6_SUMMARY_EXPORT is a path INSIDE the k6 container, not on the host:
#        it must start with /reports/ (for example /reports/summary.json).
# EXIT=2

# 正确用法（短跑到完成，summary 落在宿主机目录）
K6_REPORT_DIR="$DIR" K6_SUMMARY_EXPORT=/reports/summary.json \
  LAB_TOKEN="$TOKEN" LOAD_DURATION=25s bash code/lab/k6/run.sh smoke
ls "$DIR/summary.json"
```

## 出处

- `code/lab/k6/reports/20261027-175434-cache-lab-armed/k6.log`（`exit 107` 那次）
- `code/lab/k6/run.sh`（`K6_SUMMARY_EXPORT` 前缀校验、`K6_REPORT_MOUNT` 定义）
- `code/lab/k6/README.md`（参数表里 `K6_SUMMARY_EXPORT` 标注为 “Container path”）
- 修复提交 `3e7a9ed`
