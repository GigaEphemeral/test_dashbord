# L1 缓存掩盖切换症状

## 现象

Redis Sentinel 切换演练时，杀了主节点，Nginx 产品读仍然 200、P99 几乎没抖；学员以为「高可用无感」，但真实原因是**读根本没打到 Redis**：

- L1（Caffeine）默认 60s TTL，热点商品在切换窗口内由本地缓存直接命中；
- 即使 L1 过期，L2 命中也会避开故障主节点的影响（部分读走旧值或降级 DB）。

## 根因

两层缓存的设计目标就是「降低对下游的依赖」。切换发生在 L2 主节点时，L1 仍有 60s 的陈旧数据；应用侧 Lettuce/Redisson 也可能因为懒连接与重连而看不到错误。

## 正确做法

演示要区分两个阶段：

1. **想看「无感」**：直接杀主 + 打热点读，观察 200 与切换日志，说明缓存与客户端自动重连的价值。
2. **想看「错误毛刺」**：
   - 关闭/绕过 L1（把 `cache` toggle 关掉，或使用未被缓存的商品 id），并让读真正打到 Redis；
   - 显式设置 Lettuce 命令超时（默认可能很长），否则请求会挂起而不是快速失败；
   - 用 `redis_up` / `redis_sentinel_*` 或应用侧错误率来度量，而不是只看 P99。
3. 演练后等 L1 过期或手动清理，避免旧值影响下一次对比。

## 验证

```bash
# 切换前后对比同一路径，并确认你打的是「冷的」key
curl -s -o /dev/null -w "%{http_code} %{time_total}\n" http://127.0.0.1:8080/api/products/999
# 应用日志应出现 Lettuce/Redisson 的重连行（而不是只有 200）
```

## 出处

- `doc/process/step-24b-sentinel-client.md`（「L1 60s TTL 掩盖 failover」）
- `doc/process/step-24d-sentinel-live-drill.md`（实测 9.1s 恢复、app 不重启）
- `doc/design/details/03-缓存体系.md`（两级缓存与一致性窗口）
