# Micrometer 剥掉 `_info` 后缀

## 现象

代码里注册的 meter 名是 `mysql_cluster_primary_info`，但 Prometheus 查询 `mysql_cluster_primary_info` 永远为空；用 `curl /api/v1/query?query=mysql_cluster_primary` 才有数据：

```
mysql_cluster_primary{member="mysql-2"} 1.0
```

按设计稿写面板/告警的人会以为指标没实现。

## 根因

Micrometer 的 Prometheus 命名约定会处理保留后缀：计数器的 `_total` 由抓取端补，`_info` 则被剥掉。`MultiGauge.builder("mysql_cluster_primary_info")` 在导出时变成 `mysql_cluster_primary`。

## 正确做法

- **面板与告警按导出名写**，不要按代码常量名写。
- 想让导出名保留 `_info`，只能改 meter 名绕开约定（例如 `mysql_cluster_primary_marker`），但更推荐接受导出名并在代码常量旁注释。
- 写测试时直接用 `PrometheusMeterRegistry#scrape()` 断言导出文本，而不是断言 Micrometer 的 `Meter.Id`。

```java
assertThat(registry.scrape()).contains("mysql_cluster_primary{member=\"mysql-1\"} 1.0");
```

## 相关坑

`MultiGauge` 的行集渲染没有尾逗号（`{a="b",c="d"}`），而普通 Gauge 带 tag 时也遵循同一规则；写字符串断言时不要想当然加 `,}`。

## 出处

- 指标实现：`code/services/chaos-controller/.../cluster/MysqlClusterMonitor.java`
- 测试：`MysqlClusterMonitorTest`（用 scrape 文本断言）
- 面板：`code/lab/docker/grafana/dashboards/ha-d11.json`
