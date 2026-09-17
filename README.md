# RolloutCore

RolloutCore 是 Feature Flag 与渐进式发布服务：MySQL 保存权威配置，中央 Evaluation API 执行确定性规则与百分比分流，业务应用通过 Java SDK、Spring Boot Starter 或 OpenFeature 接入。
基于 Java 21 / Maven multi-module / Spring Boot 4.1.1，采用模块化单体架构。

## Architecture

```text
Business / Demo → Java SDK / OpenFeature → Evaluation HTTP API
                                            ↓
                                      L1 Caffeine → L2 Redis → MySQL

Control API → config + audit + outbox（同一 MySQL 事务）
                               ↓
                            Relay → Kafka → 每实例独立 group → 版本感知缓存失效
```

| 模块 | 职责 |
| --- | --- |
| rolloutcore-domain | 纯 Java 领域模型 |
| rolloutcore-server | Control Plane、Evaluation、缓存、Outbox/Kafka、MyBatis/Flyway |
| rolloutcore-sdk | HTTP 求值、严格类型、有限重试、有限 LKG、调用方默认值 |
| rolloutcore-spring-boot-starter | 配置绑定、客户端注入与生命周期 |
| rolloutcore-openfeature-provider | OpenFeature context/type/reason/error 适配 |
| rolloutcore-demo-service | 独立业务 HTTP 服务，通过 SDK 选择 payment 分支 |

## Key capabilities

- 项目/环境/Flag/Variant 管理；BOOLEAN、STRING、NUMBER、JSON 严格类型。
- 配置与策略共用乐观锁 version；变更、审计、Outbox 原子提交。
- 求值优先级：DISABLED → RULE_MATCH → PERCENTAGE_ROLLOUT → DEFAULT；固定 SHA-256 分桶协议。
- 不可变配置快照、单 JVM single-flight、负缓存、受限 LKG、generation/version 防旧回填。
- Redis Lua 保留十进制长整型版本比较和单字段 HSET 兼容行为。
- Micrometer/Prometheus、真实中间件 IT、本地 Docker Compose 配置、分离的 CI 验证。

## Reliability model

MySQL 是事实来源；缓存和 Kafka 传播是最终一致。写事务成功后本实例失效；各独立缓存实例使用不同且稳定的 Kafka group。
Relay 等 Broker ACK 后才标记 SENT；ACK 与 MySQL 提交不原子，因此提供 **at-least-once + semantic idempotency**。
低版本事件忽略，相同版本保守再次失效，不宣称跨系统 exactly-once。
logging transport 仅用于开发，SENT 不代表跨进程传播。SDK 不订阅 Kafka，断网 LKG 可能暂时保留旧值。
详见 [架构与一致性](docs/ARCHITECTURE.md)、[数据库](docs/DATABASE.md)、[Kafka 实证](docs/KAFKA_VALIDATION.md)。

## SDK usage

业务应用依赖 `io.github.lu1j:rolloutcore-spring-boot-starter:0.1.0-SNAPSHOT`，设置：

```yaml
rolloutcore:
  sdk:
    base-url: http://127.0.0.1:8080
    connect-timeout: 200ms
    timeout: 500ms
    max-retries: 1
    retry-delay: 25ms
    lkg-ttl: 30s
    lkg-capacity: 1000
```

注入 `RolloutCoreClient`：

```java
var result = client.booleanFlag("sdk-demo", "prod", "new-payment-flow",
        new io.github.lu1j.rolloutcore.sdk.EvaluationContext("sdk-new"), false);
boolean useNewPayment = result.value();
// source(): REMOTE / LKG / DEFAULT；error() 保留故障原因。
```

普通 Java 只依赖 SDK，使用 `RolloutCoreClient.create(ClientOptions.defaults(baseUrl))`，应用退出时 close。
接入模块尚未发布至公共 Maven 仓库；本地消费可先运行 `mvn install`。
[SDK / OpenFeature](docs/SDK_INTEGRATION.md) 说明类型、超时、重试和 LKG 边界。

## Local deployment

```powershell
docker compose -f infra/compose.yml up -d --build
powershell -ExecutionPolicy Bypass -File scripts/sdk-e2e.ps1 -ProjectKey sdk-demo
```

包含 MySQL、Redis、Kafka KRaft、Server、Demo、Prometheus，以及一次性 topic 初始化。
Server 8080、Demo 8081、Prometheus 9090；MySQL 3306、Redis 6379、Kafka 9092，全部发布到主机 loopback。
Demo 使用容器内 Server，Server 使用容器内中间件，Prometheus scrape Server。
默认凭据仅供本地开发，可用环境变量覆盖。首次示例脚本创建数据；重复运行需新项目 key。
[部署文档](docs/DEPLOYMENT.md) 包含原生启动、env 文件、健康检查、双 listener、可选第二实例与持久化说明。
Docker Compose 与 Testcontainers 配置已提供，但当前环境未执行完整 Docker E2E 验证。

## Testing

```powershell
mvn clean verify
mvn -Ptestcontainers verify
python -m unittest discover -s scripts/tests -v
```

默认构建不要求 Docker；`*IT` 编译但仅由 testcontainers profile 下 Failsafe 执行。缺少 Docker 的 IT 会失败，不会静默跳过。
普通测试覆盖规则、缓存竞态、事务代理、SQL 合约、HTTP、SDK 与 OpenFeature；容器 IT 覆盖真 MySQL/Flyway/HTTP/Outbox、Redis Lua、Kafka Producer→Broker→两独立 group。
CI 在 Java 21 hosted Linux runner 分开运行普通验证与 Testcontainers，缓存实际的 `workspace/maven-repository`，检查 Compose config。

普通构建验证结果：**441 tests / 0 failures / 0 errors / 0 skipped**；压测工具 fixture：**3 tests passed**。
原生 MySQL/SDK 与 Kafka E2E 验证证据见 [工程验证](docs/ENGINEERING_VALIDATION.md)；这些结果独立于 Docker 容器验证。

## Observability

仅暴露 `/actuator/health` 和 `/actuator/prometheus`。Evaluation Timer 提供 count/latency/outcome；缓存复用既有指标；Outbox 和 Kafka 记录发送/消费结果。
标签为固定枚举，禁止资源 key、userId、eventId 和 context，避免高基数。Prometheus UI：http://localhost:9090。
[指标语义与查询](docs/OBSERVABILITY.md) 明确区分传输成功、事务提交和消费处理。

## Performance evidence

真实 Evaluation HTTP benchmark 已完成。

环境：

- Windows 10 19045
- Java 21.0.8
- Python 3.13.5
- local MySQL 8.0
- 单实例 RolloutCore Server

测试工具：`scripts/load-test.py`。

测试：

- endpoint: `POST /api/v1/evaluate`
- project: `performance-demo`
- environment: `prod`
- flag: `new-payment-flow`
- concurrency: 8
- warmup: 500
- requests: 10000

结果：

| 指标 | 结果 |
| --- | --- |
| success | 10000 |
| errors | 0 |
| QPS | 2857.70 |
| P50 | 2.69 ms |
| P95 | 4.08 ms |
| P99 | 5.01 ms |

这是本地单实例 benchmark，不代表生产容量。测量口径与边界见 [性能验证](docs/PERFORMANCE.md)。

## Known limitations

- 无 Auth/RBAC、multi-region 或生产 HA 编排。
- `X-Operator` 只是调用方提供的审计名称，不是身份认证；管理与业务 API 不应直接公开。
- 单节点 Kafka/Compose 不代表生产 HA。缓存最终一致，Kill Switch 不保证瞬时全球生效。
- Outbox 网络发送持有小批次数据库行锁；无终止重试、自动清理、DLT 或专用 listener health。
- at-least-once + 有界版本语义幂等，非跨系统 exactly-once，也非永久 eventId 去重。
- SDK 使用中央 Evaluation；无全量本地规则执行。LKG 有 TTL 和容量边界，断网可能返回旧配置；安全敏感业务可禁用。
- 本地 load test 受客户端资源及闭环模型限制，不是生产容量保证。
- Docker Compose 与 Testcontainers 配置已提供，但当前环境未执行完整 Docker E2E 验证。

进一步阅读：[HTTP API](docs/API.md)、[求值契约](docs/EVALUATION.md)。
