# MySQL Router 8.4 的 SSL 与命令行

## 现象

两类故障经常一起出现：

1. Router 容器起来了，但 `mysql -h router -P 6446` 报：
   ```
   ERROR 2061 (HY000): Authentication requires secure connection
   ```
2. Router 反复退出，日志：
   ```
   Configuration error: unknown option '--log-to-console'
   ```
   或 `--conf-scope` / `--config-name` 不被识别。

## 根因

- **2061**：MySQL 8.4 默认账号插件是 `caching_sha2_password`。Router 与后端之间若是明文连接，该插件拒绝认证；Router 拿不到元数据就不会开启 6446/6447 路由（表现为端口无人监听，而容器 healthcheck 可能仍显示 healthy）。
- **命令行不兼容**：`--log-to-console` 是某些 8.x 版本的选项，8.4.10 不接受；`--conf-scope`/`--config-name` 也不是 8.4 的 bootstrap 参数。用错镜像版本时，配置模板按 8.4 写法生成、二进制却是 8.0（或反之），启动即失败。
- **数据目录权限**：8.4 Router 容器以 uid 999 运行，而镜像内 `/var/lib/mysqlrouter` 不存在，新建的命名卷属 root，init 无法写入配置。

## 正确做法

1. **镜像与服务端对齐**：服务端 8.4.4 时，Router 用 `container-registry.oracle.com/mysql/community-router:8.4`（Docker Hub 的 8.0 镜像会拒 8.4 的参数）。
2. **SSL 用 `REQUIRED`**：8.4 镜像自带 auto-TLS（`server-cert.pem`），无需额外证书即可满足 `caching_sha2_password`。只有改用 `mysql_native_password` 账号时才可以 `DISABLED`。
3. **去掉不兼容参数**：不传 `--log-to-console`/`--conf-scope`/`--config-name`；路由证据从 `db-cluster-status.sh` 的 6446/6447 探针取，而不是 `docker logs`。
4. **init 以 root 运行并 chown**：bootstrap job 用 `user: "0"` 写配置，然后把数据目录 `chown 999:999`；长驻 Router 仍用镜像默认 uid 只读配置。
5. **日志级别外置**：`log_level` 由 `MYSQL_HA_ROUTER_LOG_LEVEL` 渲染（8.4 只接受 `debug|error|fatal|info|note|system|warning`），不要写 `debug_3` 这类旧值。

## 验证

```bash
bash code/lab/scripts/db-cluster-status.sh
# read/write :6446 -> mysql-1 (server_id=1, READ_WRITE)
# read-only  :6447 -> mysql-2 (server_id=2, READ_ONLY)
# RESULT: ok - 3/3 node(s) answering, exactly one PRIMARY.
```

## 出处

- `doc/process/step-25a-mysql-cluster-compose.md`（静态层）
- `doc/process/step-25b-mysql-live-bootstrap.md`（2061、镜像/参数、uid 999，真机）
- `doc/process/step-31-mysql-router-live-drill.md`（切换与重定向）
