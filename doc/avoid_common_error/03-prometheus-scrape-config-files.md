# Prometheus `scrape_config_files` 让进程崩溃循环

## 现象

把 db-ha 的 exporter job 拆到 `prometheus/mysql-ha-scrape.yml`，用两行接进主配置：

```yaml
scrape_config_files:
  - /etc/prometheus/mysql-ha-scrape.yml
```

Prometheus 容器不断重启：

```
Error loading scrape config files ... err="\"/etc/prometheus/mysql-ha-scrape.yml\": yaml: unmarshal errors:
  line 27: cannot unmarshal !!seq into config.ScrapeConfigs"
```

Nginx 对 `/prom/` 返回 504，`verify.sh` 与所有面板一起失效。

## 根因

Prometheus 3.0.0 对被引用文件的**结构与主配置期望不一致**：该文件是裸列表（`- job_name: …`），加载器在某些版本/路径下按「带 `scrape_configs:` 键的配置片段」解析，于是报「序列无法反序列化成 ScrapeConfigs」。

即便格式对了，还有第二个坑：**该文件必须挂载进容器**。仓库里只有 `prometheus.yml` 被挂载，独立文件默认不存在，`scrape_config_files` 一定会失败。

## 正确做法

把 job **内联**进 `prometheus.yml` 的 `scrape_configs`，不再引入外部文件与额外挂载：

```yaml
  - job_name: mysqld-exporter-ha
    static_configs:
      - targets: ["mysql-1-exporter:9104"]
        labels: { member: mysql-1 }
      # …
```

代价：db-ha profile 关闭时这三个 target 显示 down（不是配置错误），面板要按 `member` 过滤，而不是要求恒为 1。

## 验证

```bash
docker ps --filter name=jl-prometheus --format '{{.Status}}'   # healthy，不再 Restarting
curl -s 'http://127.0.0.1:8080/prom/api/v1/query?query=mysql_up' | head -c 400
# 应看到 job="mysqld-exporter-ha" 的三个 member 序列
```

## 出处

- 现象与回退：`doc/process/Stage1/step-38-ha-acceptance.md`「补充」
- 修复：commit `c192a0a`（内联 job、删除从未被读取的 `mysql-ha-scrape.yml`）
