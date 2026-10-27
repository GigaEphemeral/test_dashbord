# 运行镜像里没有 curl：文档里的 `docker exec ... curl` 全部跑不通

## 现象

照抄 `doc/readme.md` §4.1/§4.4（以及 `step-35` 的演示脚本）：

```
$ docker exec jl-app-1 sh -c 'curl -s -X POST localhost:8080/lab/cache/seed -H "X-Lab-Token: $LAB_TOKEN"'
sh: curl: not found
```

三台 app 实例都一样；`/lab/**` 只能从 `jl-net` 内访问，所以宿主机的 curl 也顶不上。

## 根因

app 运行镜像从 M0 起就是 `eclipse-temurin:21-jre-alpine`（step-32 为了 `jcmd` 快照换成
`21-jdk-alpine`），Alpine 只带 BusyBox 工具集：有 `wget`/`nc`/`java`/`jcmd`/`jstack`，
**没有 `curl`、也没有 `bash`**。文档里的命令是按「一般 Linux 容器」写的，从来没在真机上执行过
——和 `14-docs-must-be-falsified.md` 是同一类问题：断言没有被证伪过就当成了事实。

相邻的两个实测事实，容易一起踩：

- `jl-nginx` 里 `curl` 和 BusyBox `wget` 都有，但容器内监听 **80**（宿主 `127.0.0.1:8080`
  是端口映射），在 nginx 里 curl `localhost:8080` 会得到 `000`；要打 app 用 `http://app-1:8080`。
- Prometheus 与 chaos-controller **都没有发布宿主端口**，宿主机侧一律走
  `http://127.0.0.1:8080/prom/...` 和 `http://127.0.0.1:8080/lab-api/...`。

## 正确做法

容器内打 `/lab/**` 用 BusyBox `wget`，token 由容器自身环境提供（不出现在宿主命令行、不落盘）：

```bash
# GET
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" http://localhost:8080/lab/cache/state'

# POST 空体
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" --post-data="" http://localhost:8080/lab/thread/lifecycle/start'

# POST JSON：单引号包住 sh -c，JSON 里的双引号写 \"
docker exec jl-app-1 sh -c 'wget -qO- --header="X-Lab-Token: $LAB_TOKEN" \
  --header="Content-Type: application/json" --post-data="{\"mode\":\"bloom\"}" \
  http://localhost:8080/lab/cache/mode'
```

写文档前先确认工具实况：

```bash
docker exec jl-app-1 sh -c 'command -v curl wget nc bash'   # -> 只有 wget、nc
```

BusyBox wget 的能力边界：只有 `-c -q -S --spider -O -o --header --post-data --post-file -P -U -T`，
**没有 `-X`、没有 `-d/--data-urlencode`、没有 `-w`**；要状态码用 `-S`（打印响应头）或 `--spider` + `$?`。

## 验证

- 三种形式在 2026-10-27 的 `cache-lab` 演练中逐条实测，返回
  `{"code":"0","message":"OK","data":{"mode":"bloom"},"success":true}` 一类正常响应；
  seed 重复调用得到 `inserted=0 alreadyPresent=1000`（幂等）。
- `code/lab/docclaims/claims.yml` 的 `app-lab-commands-use-wget` 用 `grep_count`（`expect: 0`）
  盯住 `doc/` 下不再出现 `docker exec jl-app-N ... curl` 形式。

## 出处

- `doc/process/step-48-k6-ha-cache-scenarios.md` §7.4（发现现场与命令实测）
- 修复：`doc/readme.md` §4.1/§4.4、`doc/process/step-35-thread-snapshots.md` 演示脚本改用 wget
