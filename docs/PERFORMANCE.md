# Performance validation

真实 Evaluation HTTP 压测结果：**PASSED**。以下记录依据用户提供的本地 benchmark 实测结果。

## 测试环境

| 项目 | 配置 |
| --- | --- |
| 操作系统 | Windows 10 19045 |
| Java | 21.0.8 |
| Python | 3.13.5 |
| Server | 单实例 RolloutCore Server |
| 数据库 | Local MySQL 8.0 |

## 测试配置

| 项目 | 配置 |
| --- | --- |
| 测试接口 | `POST /api/v1/evaluate` |
| Project | `performance-demo` |
| Environment | `prod` |
| Flag | `new-payment-flow` |
| 压测工具 | `scripts/load-test.py` |
| concurrency | 8 |
| warmup | 500 |
| requests | 10000 |

## 实测结果

| 指标 | 结果 |
| --- | --- |
| success | 10000/10000 |
| errors | 0 |
| QPS | 2857.70 |
| P50 | 2.69 ms |
| P95 | 4.08 ms |
| P99 | 5.01 ms |

## 验证口径与边界

warmup 不计入测量结果。QPS 为测量阶段完成的全部请求数除以测量墙钟时长；延迟从发起 HTTP 请求到完整读取响应或失败，分位数采用 nearest-rank。
只有 HTTP 200 且响应包含匹配的 flagKey、configVersion/value/reason 才计为 success。压测工具的独立 fixture 测试不计为 RolloutCore 性能结果。

这是本地单实例 benchmark，用于验证正确性和基础性能，不代表生产容量。工具采用闭环固定并发，结果受客户端资源、网络和连接数影响，不能直接外推为生产吞吐上限。
本次未记录缓存 hit/miss/db_load 前后增量，不能仅凭 warmup 将结果认定为纯 Warm L1 场景。

工程验证状态见 [工程验证](ENGINEERING_VALIDATION.md)。
