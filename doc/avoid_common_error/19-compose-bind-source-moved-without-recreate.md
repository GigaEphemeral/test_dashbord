# 改了 compose 的挂载源但没重建容器：绑定挂载变悬空，快照抓取只留一行 error

## 现象

把 `doc/process/snapshots/` 移到 `doc/process/Stage1/snapshots/` 并同步改完
`compose.base.yml` 的三处宿主绑定挂载源之后，栈没重建。此时点线程实验室的抓取，
HTTP 仍是 200，`success=true`，但业务字段是失败的：

```json
{"captured":false,"trigger":"manual","directory":"","files":[],"durationMs":1,
 "error":"FileAlreadyExistsException: /app/lab-snapshots"}
```

容器里看，挂载点已经不存在，而 `/proc/mounts` 里那条 bind 还在：

```bash
docker exec jl-app-1 sh -c 'ls -ld /app/lab-snapshots'
# ls: cannot access '/app/lab-snapshots': No such file or directory
docker exec jl-app-1 sh -c 'grep -c lab-snapshots /proc/mounts'   # => 1
```

`docker inspect` 的 `Mounts` 也是空的（旧 inode 指向已消失的宿主目录），
所以「容器在跑、health 是 green、挂载查不到」三件事同时成立——最容易被当成没配挂载。

## 根因

绑定挂载记的是**宿主机目录的 inode**，不是路径字符串。宿主侧目录被移走（或改名）之后：

1. 容器内的挂载点变成一个悬空引用：`stat` 失败，但 mount 表项还在；
2. 于是 `Files.createDirectories()` 既不能复用（ENOTDIR）也不能新建（EEXIST），
   抛的是 `FileAlreadyExistsException`，而不是直觉里的 `NoSuchFileException`；
3. 快照抓取按设计「永不抛异常」，把这条塞进 `ThreadSnapshotView.error` 返回 200，
   演示不会中断，但**产物一个都不会落到宿主**——只有 UI 上那行错误会被注意到。

`compose.base.yml` 里的 `image`/`volumes` 属于服务配置哈希的一部分，所以
**重跑一次 `up.sh` 就会重建 app 三实例并恢复**；坑在于改完 compose 直接去改文档、
没有再跑 `up.sh`，于是工作区是对的、跑着的栈是坏的。

## 正确做法

1. 改完任何 compose 的 `volumes` 源路径，立刻重建受影响的服务，别只改文件：

   ```bash
   cd code/lab/docker
   docker compose -f compose.base.yml -f compose.dev.yml \
     up -d --no-deps --force-recreate app-1 app-2 app-3
   docker exec jl-nginx nginx -s reload   # 容器 IP 变了，Nginx 上游要重新解析
   ```

   `--no-deps` 保证不动 MySQL/Redis/观测栈；向导状态、chaos 动作都在
   `jl-chaos-controller` 里，不受影响。
2. 用**宿主侧看得到产物**来验收，而不是用 HTTP 200 验收：

   ```bash
   ls doc/process/Stage1/snapshots | wc -l          # 记一次数
   docker exec jl-app-1 sh -c 'wget -qO- --post-data="" \
     --header="X-Lab-Token: $LAB_TOKEN" http://127.0.0.1:8080/lab/thread/snapshot'
   ls doc/process/Stage1/snapshots                 # 应当多出 <时间戳>_app-1/ 且是五件套
   ```

   `captured=true` + 宿主目录多出一个 bundle 才算通；只看 `captured` 字段会漏掉
   「写进了容器可写层、宿主永远为空」这一类更难发现的变体。
3. 文档目录搬迁前，先查它有没有被当运行时路径消费：本仓库里
   `doc/process/{snapshots,snapshot-analysis-template.md,benchmark}` 同时是
   compose 绑定挂载源、`bench.sh`/`tune.sh`/`compare.sh` 的默认归档目录、
   `.gitignore` 的忽略项，以及两个单测里用 `Path.of("doc","process",...)` 分段拼出的断言路径。

## 验证

```bash
# 修复前
docker exec jl-app-1 sh -c 'ls /app/lab-snapshots'          # No such file or directory
# 修复后（同一命令）
docker exec jl-app-1 sh -c 'ls -ld /app/lab-snapshots'      # drwxr-xr-x ... /app/lab-snapshots
docker inspect -f '{{range .Mounts}}{{.Source}}{{end}}' jl-app-1
#   D:\<repo>\doc\process\Stage1\snapshots
# 端到端：抓取返回 captured=true，宿主多出 20261027-141618-261_app-1/（五件套）
```

## 出处

- `code/lab/docker/compose.base.yml:21,324,327`（三条 `doc/process/Stage1/...` 绑定挂载）
- `code/services/seckill-app-jdk21/.../lab/thread/ThreadSnapshotService.java`
  （`createDirectory()` 与「失败也返回 200 + error」的口径）
- `code/services/seckill-app-jdk21/.../resources/application.yml`（`LAB_THREAD_SNAPSHOT_DIR=/app/lab-snapshots`）
- `doc/process/Stage2/架构现状与功能清单.md` §10（本次搬迁与重建记录）
