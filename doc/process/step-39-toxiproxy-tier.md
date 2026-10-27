# 步骤 39 · Toxiproxy 网络故障层（compose / scripts 半边）

> 步骤交接 · 设计 §3.1 / §3.3 / §11.3
> 日期：2026-10-27
> 分支 / HEAD：worktree `agent-general-purpose-588f772e`，基线 `699942ce`（M4-2b avalanche defenses）；本步 feat 提交 `92e03e1`。
> 关联任务：chaos-controller 的 Toxiproxy **Java 动作**由并行的另一步实现，本步只交付覆盖层、脚本接线、env 契约和本文档。
> 验证：4 个 `docker compose config --services` 计数（16 / 18 / 25 / 25）、覆盖层仅在选中 profile 时出现在渲染里、`bash -n` 通过；**未启动任何容器**，未做活体演练。
> 口径指针（2026-10-27 文档整理）：本文第 4/5 节的「未做 / 未验证 / 待补」是**当时**的状态快照，后续批次大多已消化；当前缺口口径统一看 `doc/design/details/README.md` §3.2。

# 1. 拓扑

```
jl-net（默认 profile 之外，只为 chaos-proxy 增加两个服务）

                    jl-nginx :8080
                         |
             +-----------+-----------+
             |           |           |
         jl-app-1    jl-app-2    jl-app-3
             |           |           |
             |  REDIS_HOST=toxiproxy   REDIS_PORT=16379
             |  JDBC  jdbc:mysql://toxiproxy:13306/seckill
             +-----------+-----------+
                         |
                         v
              +----------------------+   proxy "redis"    +-----------------+
              |     jl-toxiproxy     | -----------------> | jl-redis:6379   |
              |  control API :8474   |   proxy "mysql"    +-----------------+
              |  (host: 127.0.0.1)   | -----------------> | jl-mysql:3306   |
              +----------------------+                    +-----------------+
                   ^              ^
   POST /proxies   |              |  CHAOS_TOXIPROXY_URL=http://toxiproxy:8474
   (one-shot)      |              |
   jl-toxiproxy-init           jl-chaos-controller（Toxiproxy 动作，Java 半边）
```

- Toxiproxy 本身不访问数据：它是两条透明 TCP 代理（`redis` / `mysql`），toxic（延迟、带宽、断网）挂在代理上，恢复 = 移除 toxic 或 `POST /reset`。
- `jl-mysql` / `jl-redis` 及其 exporter、宿主机 `127.0.0.1:3306/6379`（compose.dev.yml）**都不变**；代理端口不发布到宿主机。
- 只有控制 API `8474` 以 `127.0.0.1:${TOXIPROXY_API_PORT}` 形式回环发布，方便宿主机 curl / toxiproxy-cli 排查。

# 2. 启停

启动（dev 栈 + 覆盖层，一条命令）：

```bash
bash code/lab/scripts/up.sh --profile chaos-proxy
```

- up.sh 渲染 `compose.base.yml + compose.dev.yml + compose.toxiproxy.yml` 并带 `--profile chaos-proxy`。
- `jl-toxiproxy` 健康后，一次性任务 `jl-toxiproxy-init` 通过 HTTP API **create-or-update** 两个代理（先 `GET /proxies/{name}`，存在则 `POST /proxies/{name}`，否则 `POST /proxies`）；它必须 `exited 0`，三个 app 容器通过 `depends_on: service_completed_successfully` 等它。
- up.sh 的 post-wait 检查对 `jl-toxiproxy-init` 单独放行：只接受 `exited 0`，其余 17 个服务仍必须是 `running`。

切回直连（重新创建 app 容器，代理容器会**残留**，见下）：

```bash
bash code/lab/scripts/up.sh --profile dev
```

- app 环境变了，Compose 会重建 `jl-app-1/2/3` 并重新指向 `redis:6379` / `mysql:3306`。
- `up --profile dev` **不会**停止未选 profile 的 `jl-toxiproxy` / `jl-toxiproxy-init`（Compose 不清理范围外容器）。只想清掉代理层：

```bash
docker rm -f jl-toxiproxy jl-toxiproxy-init
```

整体停机（down.sh 会按 env gate 自动带上覆盖层与 profile）：

```bash
bash code/lab/scripts/down.sh          # 命名卷保留
bash code/lab/scripts/down.sh --purge  # 覆盖层没有命名卷，--purge 行为不变
```

重启 `jl-toxiproxy` 后恢复代理（Toxiproxy 的代理与 toxic 都只在内存里，重启即丢）：

```bash
docker start jl-toxiproxy-init   # 一次性容器重新执行 create-or-update
```

# 3. 应用看到的

只在 `chaos-proxy` 渲染里，三个 app 服务的 environment 被并集覆盖（机制与 compose.ha.yml 相同）：

| 键 | 值 | 说明 |
|---|---|---|
| `REDIS_HOST` | `toxiproxy` | base 里是 `redis` |
| `REDIS_PORT` | `${TOXIPROXY_REDIS_PORT}`（16379） | base 里是 `${REDIS_PORT:-6379}` |
| `MYSQL_MODE` | `standalone` | 固定住，避免 `.env` 切到 router 后渲染漂移 |
| `DB_WRITE_URL` / `DB_READ_URL` | `jdbc:mysql://toxiproxy:13306/seckill?...` | 与 router 模式文档契约同名同值；standalone 下 app 不读它们 |
| `APP_DATASOURCE_WRITE_JDBC_URL` / `_READ_JDBC_URL` | 同上 | **真正生效的 MySQL 覆盖**，见下 |

关键点（代码依据：`DataSourceAccessProperties#effectiveWriteUrl` 与 `application.yml` 的 `app.datasource.write/read.jdbc-url`）：

- `MYSQL_MODE=standalone` 时两个 Hikari 池绑定的是 `app.datasource.write/read.jdbc-url`，`DB_*_URL` **只在 router 模式被读取**。只设 `DB_*_URL` 的话 MySQL 仍会直连 `mysql:3306`，MySQL 侧 toxic 对 app 无效。
- Spring 的 relaxed binding 允许环境变量 `APP_DATASOURCE_WRITE_JDBC_URL` / `APP_DATASOURCE_READ_JDBC_URL` 覆盖这两个 standalone 键，因此覆盖层同时注入它们：模式仍是 standalone（代码路径不变），但两个池都真正经过 `toxiproxy:13306`。standalone JDBC 串里的 `:3306` 是写死的，所以不能只改 `MYSQL_HOST`。
- Redis 侧不注入 `REDIS_MODE` / `REDIS_PASSWORD`：仍是 standalone、无鉴权，与 dev 语义一致（被代理的 `jl-redis` 也没有 `requirepass`）。
- 启动顺序：`toxiproxy-init` 完成后 app 才启动，Flyway/健康检查不会与代理创建竞态；代理创建失败会让整个 `up --wait` 失败，而不是静默直连。
- 运行期：对 `redis` / `mysql` 代理加 toxic 立即影响 app 的现有连接（新连接同样走代理）；`docker restart jl-toxiproxy` 会切断连接，Hikari/Redisson 会重连，但**代理本身丢失**，必须重跑 init。

# 4. profile 组合规则

| 渲染 | 服务数 | 说明 |
|---|---|---|
| base + dev | 16 | 默认栈，不含任何 Toxiproxy 键（实测渲染里 `TOXIPROXY` 出现 0 次） |
| base + dev + toxiproxy.yml + `--profile chaos-proxy` | **18** = 16 + `toxiproxy` + `toxiproxy-init` | 唯一支持的叠加方式 |
| base + dev + ha.yml + `--profile ha` | 25 | 不变 |
| base + dev + mysql.yml + `--profile db-ha` | 25 | 不变 |

- `chaos-proxy` 是 **dev 栈覆盖层**：隐含 base + dev，不能与 `ha` / `db-ha` 组合。`up.sh` 显式拒绝：`--profile db-ha`、以及含 `,` / `+` 的组合值（如 `--profile chaos-proxy,ha`）都以 exit 2 报错。
- 只带 `compose.toxiproxy.yml` 而不选 profile 会**失败关闭**：`service "app-2" depends on undefined service "toxiproxy-init": invalid compose project`。覆盖层不可能“半应用”。
- `down.sh` 沿用 compose.ha.yml 的 gate：只有覆盖层里所有不带 `:-default` 的 `${VAR}` 在 env 文件里都非空，才追加 `-f compose.toxiproxy.yml` + `--profile chaos-proxy`；否则打印 WARN 并退回原有 dev 清理路径（老 `.env` 不会把整个停机流程弄坏）。

# 5. 环境键契约（Java 侧依赖）

| 键 | 默认 / 值 | 谁用 |
|---|---|---|
| `CHAOS_TOXIPROXY_URL` | `http://toxiproxy:8474`（compose 默认按 `TOXIPROXY_API_PORT` 拼） | **chaos-controller Java 动作**读取的 base URL；只在 chaos-proxy 渲染注入 |
| `TOXIPROXY_IMAGE` | `shopify/toxiproxy:2.9.0`（Docker Hub 镜像，对应 ghcr.io/shopify/toxiproxy:2.9.0） | `toxiproxy` + `toxiproxy-init` 共用，不额外拉镜像 |
| `TOXIPROXY_API_PORT` | `8474` | 控制 API / cli；宿主机仅 127.0.0.1 |
| `TOXIPROXY_REDIS_PORT` | `16379` | 代理监听端口；app `REDIS_PORT` 取同值 |
| `TOXIPROXY_MYSQL_PORT` | `13306` | 代理监听端口；app JDBC URL 取同值 |
| `TOXIPROXY_MEM_LIMIT` | `64m` | 设计 §3.1 预算 |
| `TOXIPROXY_INIT_MEM_LIMIT` | `32m` | 一次性 init 任务 |

Java 动作必须按以下契约实现（本步没有改任何 Java）：

1. **缺失即禁用**：dev / ha 渲染里没有 `CHAOS_TOXIPROXY_URL`。动作应把“键缺失或为空”当作“chaos-proxy 未启用”（fail closed，返回 4xx/禁用状态），不要猜默认地址。
2. **代理名固定**：`redis`、`mysql`。动作按名字寻址（`GET/POST /proxies/{name}`、`POST /proxies/{name}/toxics`），不要按端口猜。
3. 链路：`redis` = `0.0.0.0:16379 -> jl-redis:6379`；`mysql` = `0.0.0.0:13306 -> jl-mysql:3306`。
4. 恢复/急停只作用于这两个代理（§11.3：延迟、带宽、断网、恢复）；Toxiproxy 状态在内存，控制器重启后应重新 `GET /proxies` 而不是假设 toxic 还在。
5. 网络：控制器在 `jl-net` 内，直连 `http://toxiproxy:8474`；宿主机侧仅为 `127.0.0.1:8474`。

# 6. 未完成 / 验收边界

- **未做活体演练**：本步没有启动任何容器，`shopify/toxiproxy:2.9.0` 是否可经本机镜像源拉取、init 任务里 busybox `wget --post-data` 是否按预期工作、`jl-toxiproxy` 健康检查是否通过，都**未经运行验证**（只验证了渲染）。
- **Java 动作未实现**：延迟/带宽/断网/恢复的控制器动作、接口、前端入口、Grafana annotation、TTL 回滚全部是另一步；`§11.3` 的能力表本步只交付了“可被作用的代理”。
- **带宽 toxic 未验证**：compose 只提供透明代理，带宽是否有意义取决于 Java 侧的 toxic 参数设计；本步没有定义任何 toxic 名称/默认值。
- **MySQL 覆盖依赖 relaxed binding**：`APP_DATASOURCE_*_JDBC_URL` 是 Spring 的合法环境变量映射，但本步无 JDK/容器运行证据。首次活体演练必须看 app 启动日志 `MySQL access mode: standalone (write endpoint jdbc:mysql://toxiproxy:13306/...)`；若仍是 `mysql:3306`，说明该映射未被接受，应改用 router 模式或改 app（另开步骤），不要当作已完成。
- `verify.sh` **未改**：没有 Toxiproxy 断言，dev 的 97 PASS / 1 WARN 基线不变。
- Toxiproxy 重启后代理丢失（内存态），恢复靠 `docker start jl-toxiproxy-init`；这一点没有自动化看护。
- `up.sh --profile dev` 切回后 `jl-toxiproxy` / `jl-toxiproxy-init` 会残留（不占 app 链路，但占 64MB），需要手动 `docker rm -f` 或整体 `down.sh`。

# 7. 验证记录（本步实际执行）

```text
docker compose --project-name jl-check -f compose.base.yml -f compose.dev.yml config --services | wc -l
16
docker compose ... -f compose.toxiproxy.yml --profile chaos-proxy config --services | wc -l
18
docker compose ... -f compose.ha.yml --profile ha config --services | wc -l
25
docker compose ... -f compose.mysql.yml --profile db-ha config --services | wc -l
25

默认渲染 app-1: REDIS_HOST=redis / REDIS_PORT="6379" / DB_WRITE_URL="" / DB_READ_URL="" /
               MYSQL_MODE=standalone / 无 CHAOS_TOXIPROXY_URL
chaos 渲染 app-1: REDIS_HOST=toxiproxy / REDIS_PORT="16379" /
               DB_WRITE_URL=DB_READ_URL=jdbc:mysql://toxiproxy:13306/seckill?... /
               APP_DATASOURCE_{WRITE,READ}_JDBC_URL 同值 / CHAOS_TOXIPROXY_URL=http://toxiproxy:8474
chaos 渲染 app-1.depends_on = mysql, otel-collector, rabbitmq, redis, toxiproxy-init(completed)
不选 profile 只加覆盖层：service "app-2" depends on undefined service "toxiproxy-init"

bash -n code/lab/scripts/up.sh && bash -n code/lab/scripts/down.sh && bash -n code/lab/scripts/verify.sh
bash -n OK
```

文件：`code/lab/docker/compose.toxiproxy.yml`（新）、`code/lab/docker/.env.example`、`code/lab/scripts/up.sh`、`code/lab/scripts/down.sh`、`.gitattributes`（覆盖层内嵌 init 脚本必须 LF）。


---

## 补充（2026-10-27 真机演练）：镜像无 shell，init 必须独立镜像

真机首次拉起时报 `container jl-toxiproxy is unhealthy`，根因是 `shopify/toxiproxy:2.9.0` 为 **distroless** 镜像：容器内没有 `/bin/sh`，也没有 `wget`。

- 本文件正文里「busybox wget 随镜像提供」的说法**不成立**，已由 `compose.toxiproxy.yml` 修正：
  - 删除 toxiproxy 容器的 in-container healthcheck（distroless 无法执行任何 shell 探针）；
  - `toxiproxy-init` 改用独立镜像 `TOXIPROXY_INIT_IMAGE=alpine:3.19`（可配），`depends_on` 降为 `service_started`，脚本开头轮询 `/version` 最多 60s；
  - 新增 `.env.example` / `.env` 键 `TOXIPROXY_INIT_IMAGE`。
- 演练与证据见 `doc/process/step-43-toxiproxy-live-drill.md`。
