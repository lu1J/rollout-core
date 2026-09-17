# Observability

Server 使用 Spring Boot Actuator / Micrometer；Prometheus registry 版本由 Boot dependency management 管理。
只暴露 `/actuator/health` 和 `/actuator/prometheus`。Health 隐藏组件详情、保留数据库检查；Redis 是可降级缓存，未纳入整体健康检查。
Kafka listener 停止不一定使 HTTP health DOWN，必须同时观察消费失败、日志和 consumer lag。

| Micrometer 名称 | 类型 / tags | 语义 |
| --- | --- | --- |
| rolloutcore.evaluation | Timer；outcome=disabled/rule_match/percentage_rollout/default/error | 每次进入 EvaluationService 恰好一个 sample，含异常耗时 |
| rolloutcore.cache.l1_hit / l1_miss | Counter；无 tag | L1 查询命中/未命中 |
| rolloutcore.cache.l2_hit / l2_miss / l2_error | Counter；无 tag | Redis 快照读取与故障 |
| rolloutcore.cache.db_load | Counter；无 tag | DB 加载尝试 |
| rolloutcore.cache.negative_hit / singleflight_join | Counter；无 tag | 负缓存/同 JVM 请求合并 |
| rolloutcore.cache.lkg_fallback | Counter；无 tag | 受限基础设施故障回退 |
| rolloutcore.cache.cache_invalidation / stale_fill_rejected | Counter；无 tag | 实际失效/拒绝旧回填 |
| rolloutcore.outbox.send | Counter；outcome=success/failure | 每次 producer.send 的结果 |
| rolloutcore.kafka.consume | Counter；outcome=applied/stale/invalid/failure/interrupted | 每次 listener 调用的终态 |
| rolloutcore.kafka.retry | Counter；无 tag | 本地额外处理重试 |
| cache.* | Caffeine binder；cache=evaluation_l1/evaluation_lkg/evaluation_negative 等固定维度 | 底层缓存统计 |

缓存沿用既有 CacheMetrics 和 Caffeine recordStats，不新造 tier 计数器。Binder 的底层查询次数与业务缓存计数不是可相加的总请求数。
Timer 自带 count/sum/buckets，不增加重复的 evaluation 请求 Counter。Service 的 error 包括业务异常；请求体解析和 Controller validation 在 Service 前拒绝的请求应查看 Boot 的 `http.server.requests`。
自定义 Timer 不是网络端到端时延，端到端性能用压测工具测量。

Outbox success 表示传输返回（Kafka 模式为 Broker ACK），即使之后 markSent 或事务提交失败也保留该次发送计数。
重投是新的发送尝试，计数不会假装是唯一业务事件数。Logging 模式 success 仅表示日志传输成功。
不增加需要高频 DB COUNT 的 pending gauge。

Kafka 在重试结束时只记录一个终态；retry 单独计数。applied 包含相同版本的保守再次失效，stale 表示低于本地版本水位的事件。
两者都可正常确认；invalid/failure/interrupted 仍抛异常，原有停止订阅和 offset 规则不变。
版本判断与返回结果在缓存 stripe 锁内完成，没有新增先读版本再失效的竞态。

没有 projectKey、environmentKey/envKey、flagKey、userId、eventId、context 或异常文本 tag。所有自定义标签值是固定枚举，用户/flag 增长不会增加指标维度。
Boot/Caffeine 的基础指标使用框架维度；不要给 registry 配置资源 key 或用户作为 common tags。资源定位信息留在日志中。

## Prometheus

Compose 中 Prometheus 每 5 秒抓取 `server:8080/actuator/prometheus`，界面为 http://localhost:9090。

```promql
sum(rate(rolloutcore_evaluation_seconds_count[5m]))
histogram_quantile(0.95, sum by (le) (rate(rolloutcore_evaluation_seconds_bucket[5m])))
sum(rate(rolloutcore_evaluation_seconds_count{outcome="error"}[5m]))
rate(rolloutcore_cache_lkg_fallback_total[5m])
rate(rolloutcore_outbox_send_total{outcome="failure"}[5m])
rate(rolloutcore_kafka_consume_total{outcome="failure"}[5m])
```

指标可能直到首次路径执行才出现；histogram 分位数是 bucket 近似值。单元测试验证 Prometheus scrape、health 与未暴露 env endpoint；不是容器 E2E。
没有配置身份认证；部署示例仅绑定 loopback，不能直接作为公网管理端点。

## 本次原生检查（2026-09-17）

local Windows / Java 21.0.8，native MySQL 服务运行且监听 3306。缺少可复用的数据库凭据，Server 未启动；对 loopback 8080 的 health/prometheus 探测均未获得 HTTP 响应。真实 scrape 与运行时标签检查 **NOT_RUN**，没有实际观察到的工程指标，以上名称仅为代码/测试所描述的协议。

源码审计确认 Evaluation 只有 Timer，无重复 request counter；cache 无资源标签，Outbox/Kafka outcome 为固定枚举。未发现 userId、projectKey、environmentKey、flagKey、eventId 或任意 context 值作为自定义标签。运行时仍需以真实 scrape 复核。默认 logging 模式不启动 Kafka listener，consume/retry 指标不能据此声称已验证；未触发重试时，也不能把 retry 指标缺席解释成验证失败或伪造一次重试。
