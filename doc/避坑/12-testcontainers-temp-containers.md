# Testcontainers 的临时容器不是实验室容器

## 现象

`docker ps` 里偶尔出现一个随机名的 MySQL：

```
admiring_beaver   mysql:8.4.4   Up 2 minutes
```

它没有 `jl-` 前缀，也没有 `lab=java-learn` 标签；几十秒后又自己消失。有时会误以为「实验室多起了一个 MySQL」或「db-ha 集群异常」。

## 根因

Maven 测试里的集成用例用 Testcontainers 启动临时依赖（如 `MySQLContainer`），容器名由 Testcontainers 随机生成，生命周期跟测试 JVM 走：测试结束即被 Ryuk 回收。它不属于 compose 项目，也不在 chaos 白名单里。

## 正确做法

- 判断「实验室容器」只看两个条件：`label=lab=java-learn` 且属于项目 `java-learn-lab`。随机名容器一律视为测试副产物。
- 统计/巡检命令都带标签过滤：
  ```bash
  docker ps --filter "label=lab=java-learn" --format "{{.Names}}\t{{.Status}}"
  ```
- 不要手工 `docker rm -f` 随机容器（可能与正在跑的测试抢占），等测试结束或停掉测试进程。
- 如果测试异常退出留下孤儿，可用 `docker ps --filter "ancestor=mysql:8.4.4"` 辨认，但先确认没有构建在跑。

## 验证

```bash
docker ps -a --format '{{.Names}}\t{{.Label "lab"}}' | grep -v '^jl-'   # 只应有测试临时容器，且很快消失
```

## 出处

- 本仓库测试栈：`RabbitMqTestContainer` 等（见 `step-16`）
- 现场观察：2026-10-27 M3-2 真机验收期间（`admiring_beaver`）
