# Bench comparison

- A: `20261026-145555-smoke` (D:\000CODE\JAVA-LEARN\code\lab\k6\reports\20261026-145555-smoke)
- B: `neg-threshold` (D:\000CODE\JAVA-LEARN\code\lab\k6\reports\neg-threshold)

| Metric | A | B | Δ (B-A) |
|---|---:|---:|---:|
| Scenario | smoke | smoke | n/a |
| Run ID | 20261026-145555-smoke | neg-threshold | n/a |
| Verdict | PASS | FAIL | n/a |
| Git commit | 0c00e09 | 0c00e09 | n/a |
| Reset baseline stock | 100 | 100 | n/a |
| Duration (s) | 7 | 6 | -1 |
| RPS (avg) | 1.69 | 1.93 | 0.24 |
| Requests | 7 | 7 | 0 |
| Iterations | 1 | 1 | 0 |
| http_req_duration P95 (ms) | 931.79 | 832.64 | -99.15 |
| http_req_duration P99 (ms) | 989.99 | 927.30 | -62.69 |
| http_req_duration max (ms) | 1004.54 | 950.97 | -53.57 |
| Failed rate | 0.00% | 0.00% | 0.00pp |
| Flow error rate | 0.00% | 0.00% | 0.00pp |
| Server error rate | 0.00% | 0.00% | 0.00pp |
| Checks rate | 100.00% | 100.00% | 0.00pp |
| Expected 409 | n/a | n/a | n/a |
| Expected 429 | n/a | n/a | n/a |
| VUs max | 1 | 1 | 0 |
| Peak heap (MB) | 72.8 | 70.8 | -2.0 |
| Peak threads | 43 | 43 | 0 |
| GC pauses (count) | 4 | 6 | 1 |
| Peak Hikari pending | 0 | 0 | 0 |
