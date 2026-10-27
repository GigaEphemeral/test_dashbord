# docker-java exec 挂 stdin 不给 EOF

## 现象

chaos-controller 的 InnoDB Cluster 探针每 10s 报超时：

```
timedOut=true  ms=6030  stdout=""  stderr=""
```

同一 exec 去掉 stdin 后立刻返回（`ms=201`）。`mysql_cluster_*` 指标因此一条都不出现，Grafana 面板全空。

## 根因

`DockerContainerGateway#execInContainer` 通过 `execStartCmd(...).withStdIn(new ByteArrayInputStream(...))` 把 SQL 喂给容器内的 `mysql` 客户端。docker-java 3.7.1（httpclient5 传输）在附加 stdin 后**不会让容器进程看到 EOF**，`mysql` 认为输入还没结束，一直等，直到 Java 侧超时把流掐断。

## 正确做法

不要让 SQL 走 stdin。把 SQL 作为**容器内环境变量**传入，由 bash 展开给 `mysql -e`：

```yaml
command:
  - /bin/bash
  - -c
  - >-
    MYSQL_PWD="${!MYSQL_CLUSTER_PASSWORD_ENV}" exec mysql
    --batch --raw --skip-column-names -h127.0.0.1 -u"$MYSQL_CLUSTER_ADMIN_USER"
    -e "$MYSQL_CLUSTER_QUERY"
```

```java
List<String> env = List.of(
        ENV_ADMIN_USER + "=" + config.getAdminUser(),
        ENV_PASSWORD_VARIABLE + "=" + config.getPasswordEnvVar(),
        ENV_QUERY + "=" + config.getQuery());
gateway.execInContainer(container, command, env, /* stdin */ null, timeoutSeconds);
```

口令仍不进入 argv：`MYSQL_PWD` 由容器内的 bash 从容器自己的环境里展开。

## 验证

```bash
curl -s 'http://127.0.0.1:8080/prom/api/v1/query?query=mysql_cluster_probe_up'   # -> 1
curl -s 'http://127.0.0.1:8080/prom/api/v1/query?query=mysql_cluster_member_count' # -> 3
```

## 出处

- 缺陷发现：`doc/process/Stage1/step-31-mysql-router-live-drill.md`
- 修复：commit `fcd287d`（`ChaosProperties` / `MysqlClusterMonitor` / `application.yml`）
- 复验记录：`step-31` 的「补充」节
