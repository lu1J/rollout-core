# RolloutCore

RolloutCore 是一个学习型 Feature Flag 与渐进式发布平台。Day1–Day4 提供配置管理、确定性求值、快照缓存与 Outbox；Day5 增加 HTTP Java SDK、Spring Boot Starter、OpenFeature Provider 和独立业务 Demo。Server 保持模块化单体，业务应用通过 HTTP 接入。

## Day5 业务接入

```text
Business Service → Starter 注入 RolloutCoreClient → Java HttpClient
                                                   ↓ POST /api/v1/evaluate
                                             rolloutcore-server
OpenFeature Client → RolloutCoreProvider → 同一个 RolloutCoreClient
```

业务项目依赖 `io.github.lu1j:rolloutcore-spring-boot-starter:0.1.0-SNAPSHOT`，配置：

```yaml
rolloutcore:
  sdk:
    enabled: true
    base-url: http://127.0.0.1:8080
    connect-timeout: 200ms
    timeout: 500ms
    max-retries: 1
    retry-delay: 25ms
    lkg-ttl: 30s
    lkg-capacity: 1000
```

构造器注入 `RolloutCoreClient` 后调用：

```java
var result = client.booleanFlag("checkout-service", "prod", "new-payment-flow",
        new io.github.lu1j.rolloutcore.sdk.EvaluationContext("user-123"), false);
boolean useNewPayment = result.value();
// source(): REMOTE / LKG / DEFAULT；error() 保留故障。
```

普通 Java 项目仅依赖 `rolloutcore-sdk`，通过
`RolloutCoreClient.create(ClientOptions.defaults("http://127.0.0.1:8080"))` 创建共享客户端，退出时 close。
SDK 支持 boolean、String、BigDecimal、Jackson 3 JsonNode（object/array）及统一 `evaluate`，
不依赖 server/domain，不下载配置或执行规则。
重试仅针对连接/超时及 502/503/504，默认最多额外一次；4xx、类型和协议错误不使用 LKG。
现有 500 响应无法证明故障是瞬时基础设施错误，因此保守地不重试、不用 LKG。
LKG 按完整上下文隔离，有 TTL/容量边界；过期或未命中时返回调用方默认值。

使用 OpenFeature 时另加 `rolloutcore-openfeature-provider`：

```java
var api = dev.openfeature.sdk.OpenFeatureAPI.getInstance();
api.setProviderAndWait("payment",
        new io.github.lu1j.rolloutcore.openfeature.RolloutCoreProvider(client, "checkout-service", "prod"));
boolean enabled = api.getClient("payment").getBooleanValue("new-payment-flow", false,
        new dev.openfeature.sdk.ImmutableContext("user-123"));
```

Provider 是标准适配层，不是新的 Evaluation Engine。targetingKey 映射为 userId；
LKG 映射 CACHED，原错误保存在 `rolloutcore.error/errorMessage` metadata。
固定 OpenFeature 1.20.2，发布 POM/JAR 的 Java 11 target 已实际核对，并通过 Java 21 / Boot 4.1.1 构建测试。

### 独立 Demo 与真实 E2E

先执行 `mvn clean verify`。终端 A 按下方“本地运行”设置真实 MySQL 凭据并启动 server：

```powershell
java '-Djava.io.tmpdir=workspace' -jar rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar
```

终端 B 启动独立业务应用（不需要数据库凭据）：

```powershell
$env:DEMO_PROJECT_KEY = 'day5-demo'
$env:ROLLOUTCORE_SDK_BASE_URL = 'http://127.0.0.1:8080'
java -jar rolloutcore-demo-service/target/rolloutcore-demo-service-0.1.0-SNAPSHOT.jar
# GET http://127.0.0.1:8081/demo/payment?userId=day5-new
```

终端 C 创建真实 flag 并验证 old/new：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/day5-e2e.ps1 -ProjectKey day5-demo
```

重跑请换新的 ProjectKey，并同步修改 Demo 的 DEMO_PROJECT_KEY 后重启 Demo。
脚本遇到已有项目会报错，不覆盖原数据、不管理服务、不清空 Redis。
可选故障验证：Demo 启动时加 `--rolloutcore.sdk.lkg-ttl=5m`，正常脚本通过后手动停止 server 应用，
保持 Demo 存活，在五分钟内运行同一脚本加 `-VerifyOutage`，检查热用户 LKG 和冷用户默认值。
这会刻意扩大测试环境陈旧窗口，不是生产配置建议。

用户已确认 Day5 真实 MySQL 8.0、Flyway V2/V3、`REAL_MYSQL_DAY5_E2E=PASSED`
及 `REAL_DAY5_OUTAGE_E2E=PASSED`。这是用户执行的结果；自动测试仍使用本地 HTTP fixture。
详见 [Day5 报告](docs/DAY5_REPORT.md)。以下 Day1–Day3 数据库验证记录是历史结果。

## Kafka 事件传播（Day4 后补）

已增加真实 Kafka Producer/Listener 适配器；由 Boot 4.1.1 管理
Spring Kafka 4.1.1 / kafka-clients 4.2.1，版本来自实际 Maven 依赖树。

```text
配置 + Audit + Outbox PENDING（同一 MySQL 事务）
  → COMMIT → Relay → KafkaTemplate → Broker ack → Outbox SENT
Broker → 每实例独立 group → Listener → ConfigChangedConsumer → 本地 SnapshotCache 失效
```

默认仍为 `rolloutcore.events.transport=logging`，适合不运行 Broker 的开发环境；
LoggingProducer 只记录日志，SENT 不代表跨进程投递。启用 Kafka：

```yaml
spring:
  kafka:
    bootstrap-servers: 127.0.0.1:9092
rolloutcore:
  events:
    transport: kafka
    instance-id: instance-a
    topic: rolloutcore.config-events
    send-timeout: 5s
    consumer-retries: 2
    consumer-retry-delay: 250ms
```

每个缓存实例必须有唯一、稳定的 instance-id，Kafka 模式缺失时启动失败。
实例 A/B 分别使用 `rolloutcore-cache-instance-a` / `rolloutcore-cache-instance-b`。
**相同 group 是负载均衡，不同 group 才是独立订阅**；不得让两个独立 L1 共用 group。
Producer 的 acks=all/idempotence 不能消除 Broker 已收但 MySQL 未标 SENT 的重复窗口。
消费者使用版本语义幂等：低版本忽略，同版本保守再次失效，不写业务配置。
消费使用 RECORD ack、关闭自动提交；本地处理默认额外重试两次，耗尽/协议无效停止 listener，
失败记录不提交，修复后重启重放；未引入 DLT 或 processed_event 表。

开发 KRaft 配置：[infra/kafka-compose.yml](infra/kafka-compose.yml)，仅 Kafka，端口 127.0.0.1:9092。
有可用 Docker/MySQL CLI、已设置实际数据库环境变量且 8080/8082 空闲时：

```powershell
mvn clean verify
powershell -ExecutionPolicy Bypass -File scripts/kafka-e2e.ps1 -MySqlClient '<mysql.exe实际路径>' -RunOutageExperiment
```

脚本启动两个独立 server JVM，保存 Broker 记录、两个 group、两份进程日志和 PENDING/SENT 证据，
使用 10 分钟 L1 TTL 排除自然过期，验证重复、乱序及可选 Broker 停机恢复。
它不清空数据、不删除 volume、不停止 Java/Windows 服务；完成后两个 server 保留运行并输出 PID。
运行前若旧 JAR 正被占用，请自行停止对应应用；不要强杀或删除被锁文件。
**Windows 原生 Kafka 4.2.1 手工 E2E 已通过**：真实 CLI smoke、
`REAL_KAFKA_CROSS_JVM_INVALIDATION=PASSED`、`REAL_KAFKA_OUTAGE_RECOVERY=PASSED`。
A/B 独立 group 收到同一事件，B 在约 89.5s 内从 v0 收敛到 v1，早于 600s L1 TTL；
Broker 停机时配置 PUT v2 成功、B 暂读 v1，恢复后 Relay 自动重试、两实例消费并收敛到 v2。
恢复后的最终 SENT **未通过真实 SQL 直接查询**：mysql.exe 因 Windows DLL/runtime 问题无法启动；
ACK/markSent 的行为另有自动测试，不能将 ACK 日志当成 SQL 提交证据。
**Docker Compose E2E=NOT_RUN**，Docker 仍未安装/运行，以上脚本保留为 Docker 自动化路径。
日志行号、Windows `java -cp ".\libs\*" <MainClass>` workaround 和完整边界见
[KAFKA_E2E_REPORT](docs/KAFKA_E2E_REPORT.md)。

## Day1 能力

- Project / Environment / FeatureFlag / FlagVariant / FlagEnvironmentConfig / AuditLog。
- BOOLEAN、STRING、NUMBER、JSON 类型校验；JSON 只接受对象或数组。
- MySQL 持久化、Flyway V1、显式 MyBatis Mapper + XML。
- Service 事务、数据库唯一约束、配置 version 乐观锁、环境级 enable/disable。
- Jakarta Validation、ProblemDetail 错误响应、必填 X-Operator。
- 仅开放 Actuator health。
- 默认测试不依赖外部 MySQL，不使用 H2 或 Docker。

Control Plane 负责配置写入和管理；EvaluationService 从配置快照求值，缓存 miss 时由 SnapshotRepository 读取 MySQL。Kill Switch 关闭时返回配置的 defaultVariant，reason=DISABLED；不意味着 value 一定为 false，也不代表已有 SDK 或推送机制。

## 模块

| 模块 | 当前内容 |
| --- | --- |
| rolloutcore-domain | 六个领域对象、FlagValueType、ResourceStatus；不依赖 server 或 Spring |
| rolloutcore-server | Spring Boot HTTP API、Service、MyBatis、Flyway、测试 |
| rolloutcore-sdk | 独立 HTTP SDK、类型校验、有限重试和 LKG |
| rolloutcore-spring-boot-starter | 配置绑定、自动注入、用户 Bean 覆盖 |
| rolloutcore-openfeature-provider | OpenFeature 类型/context/reason/error 适配 |
| rolloutcore-demo-service | 独立 Spring Boot 业务应用，GET /demo/payment |

调用链：Controller → ControlPlaneService → Mapper 接口 → XML SQL → MySQL。
没有 JPA / Hibernate ORM、MyBatis-Plus 或 Lombok。依赖中的 Hibernate Validator 是 Jakarta Validation 实现，不是 Hibernate ORM。

## 构建与测试

需要 Java 21、Maven 3.9+，在仓库根目录执行：

```powershell
mvn test
mvn clean verify
```

根 POM 使用 Spring Boot 4.1.1，MyBatis Starter 固定 4.0.0，JUnit Jupiter 固定 5.14.4。
Boot 当前默认管理 JUnit 6，因此显式覆盖 `junit-jupiter.version`；应用装配测试使用 Context Runner，避免依赖需要 JUnit 6 的 SpringExtension。

`.mvn/maven.config` 将本地依赖缓存放在被忽略的 `workspace/maven-repository`，并使用仓库内的空 settings，从 Maven Central HTTPS 下载，不读取或更改用户 Maven 配置。首次构建需要联网，后续可以使用缓存。Day5 四个接入模块均已有实现。

测试分为规则测试、mock Mapper 的业务测试、MockMvc、真实 Spring 事务代理配合 mock JDBC Connection 的事务边界测试、MyBatis XML 合约测试、应用上下文及 health 测试。mock JDBC 的 rollback 验证不等于已验证 MySQL 实际回滚。

## 本地运行

准备一个独立、空的 MySQL 8 数据库（V1 使用 enforced CHECK，最低 8.0.16）及具有建表和读写权限的账户。应用不自动创建数据库，不管理 Windows MySQL 服务。

| 环境变量 | 含义 | 默认 |
| --- | --- | --- |
| ROLLOUTCORE_DB_URL | JDBC URL | jdbc:mysql://127.0.0.1:3306/rolloutcore?connectionTimeZone=UTC |
| ROLLOUTCORE_DB_USERNAME | 数据库用户名 | rolloutcore |
| ROLLOUTCORE_DB_PASSWORD | 数据库密码 | 无，必须提供 |

在当前 PowerShell 会话安全输入密码，然后启动：

```powershell
$env:ROLLOUTCORE_DB_URL = 'jdbc:mysql://127.0.0.1:3306/rolloutcore?connectionTimeZone=UTC'
$env:ROLLOUTCORE_DB_USERNAME = 'rolloutcore'
$credential = Get-Credential -UserName $env:ROLLOUTCORE_DB_USERNAME -Message '输入本地 MySQL 凭据'
$env:ROLLOUTCORE_DB_PASSWORD = $credential.GetNetworkCredential().Password
java '-Djava.io.tmpdir=workspace' -jar rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar
```

先执行 `mvn clean verify` 生成可运行 JAR。默认监听 8080，Flyway 启动时执行迁移；迁移或连接失败会使应用启动失败。不要在代码、命令示例或提交中写入真实密码。`.env` 被忽略，但应用不会自动读取 .env 文件。

`GET http://localhost:8080/actuator/health` 是唯一开放的管理端点；正常运行时包含真实数据库健康检查，但不暴露组件详情。测试上下文单独关闭数据库健康检查，不代表真实数据库健康。

## HTTP 约定

- 写操作必须携带非空、最长 100 字符的 `X-Operator`。
- **X-Operator 仅用于 Day1 审计身份传递，不是 Authentication / Authorization。** 客户端可自行填写，当前 API 不应直接暴露公网。
- Key 使用 `[a-z0-9._-]{1,100}`，不 trim、不 lowercase；名称为非空且最长 200 字符。
- 创建返回 201；读取和更新返回 200。
- 创建 Flag 可省略 variants，也可提供最多 100 个初始 Variant。
- 创建配置必须提供 enabled 和 defaultVariantKey，初始 version=0。
- 更新、enable、disable 必须提供非负整数 expectedVersion。
- 即使 enable/disable 没有改变布尔状态，成功请求仍递增 version 并记录审计。
- 审计查询必须指定 projectKey，limit 默认 50、范围 1–100，offset 默认 0。
- 时间以 UTC 生成并存储为 DATETIME(6)，JSON 中的 LocalDateTime 不带时区后缀，按 UTC 解释。

| 方法 | 路径（前缀 /api/v1） | 请求 |
| --- | --- | --- |
| POST | /projects | projectKey, name |
| GET | /projects/{projectKey} | — |
| POST | /projects/{projectKey}/environments | envKey, name |
| GET | /projects/{projectKey}/environments | — |
| POST | /projects/{projectKey}/flags | flagKey, name, valueType, variants? |
| GET | /projects/{projectKey}/flags/{flagKey} | — |
| POST | /projects/{projectKey}/flags/{flagKey}/variants | variantKey, value |
| GET | /projects/{projectKey}/flags/{flagKey}/variants | —，新增便于查看初始 Variants |
| POST | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config | enabled, defaultVariantKey |
| GET | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config | — |
| PUT | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config | enabled, defaultVariantKey, expectedVersion |
| POST | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/enable | expectedVersion |
| POST | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/disable | expectedVersion |
| GET | /audits?projectKey=checkout-service&limit=50&offset=0 | — |

创建 Flag 示例：

```json
{
  "flagKey": "new-payment-flow",
  "name": "New payment flow",
  "valueType": "BOOLEAN",
  "variants": [
    {"variantKey": "old", "value": false},
    {"variantKey": "new", "value": true}
  ]
}
```

创建配置：`{"enabled":false,"defaultVariantKey":"old"}`。
启用：`{"expectedVersion":0}` → version=1。
禁用：`{"expectedVersion":1}` → version=2。
再次使用 expectedVersion=0 → 409，失败写操作不会新增审计。

| HTTP | code | 含义 |
| --- | --- | --- |
| 400 | validation_error | 参数、类型、Key、Header 校验失败 |
| 404 | resource_not_found | 资源不存在或不属于路径指定的项目/Flag |
| 409 | duplicate_resource | 数据库唯一约束冲突 |
| 409 | optimistic_lock_conflict | 版本过期或并发条件更新影响 0 行 |
| 500 | internal_error | 未预期服务端错误，响应不包含 SQL 或堆栈 |

错误采用 Spring ProblemDetail（`application/problem+json`），包含稳定 `code`。
Variant 响应中的 value 是 JSON 节点；审计响应的 before / after 是解析后的 JSON 快照，创建操作 before=null。

## 真实 MySQL E2E

**REAL_MYSQL_E2E=PASSED**。真实 MySQL 连接和 Flyway V1 已验证；完整 HTTP E2E 已通过，
Config version 0→1→2，旧版本真实返回 409 optimistic_lock_conflict，最终 enabled=false、version=2，六条成功 Audit 正确。
用户另行完成真实 UNIQUE 冲突事务回滚实验。initial variants 重复 key 现已在首次写入前抛出 validation_error，MySQL UNIQUE 仍保留兜底。
PowerShell 5.1 的 byte[] 响应先按 UTF-8 解码再解析 JSON，字符串响应保持原样。详情见 Day1 报告。

连接真实 MySQL 的应用启动后，可使用新的 ProjectKey 运行：

```powershell
powershell -ExecutionPolicy Bypass -File scripts/day1-e2e.ps1
```

脚本访问实际 HTTP 服务，创建 checkout-service → prod → new-payment-flow → old=false/new=true，
验证 config version 0 → enable 1 → disable 2 → 旧版本 409，并检查审计。脚本不会启动、停止服务或删除数据。重复执行应使用独立项目 Key，例如 `-ProjectKey checkout-service-2`；默认 Key 已存在时会明确失败，不复用或清空已有数据。

## Day2 Evaluation

`POST /api/v1/evaluate` 无需 X-Operator（只读、不写 Audit）：

```json
{
  "projectKey": "checkout-service",
  "environmentKey": "prod",
  "flagKey": "new-payment-flow",
  "context": {"userId": "user-123", "country": "JP", "vipLevel": 5, "appVersion": "2.3.1", "attributes": {"plan": "pro"}}
}
```

响应包含 `flagKey / variantKey / value / reason / configVersion / matchedRulePriority / bucket`。
value 保留 BOOLEAN、STRING、NUMBER、JSON 类型。规则命中返回 priority；仅百分比分流返回 bucket，其余情况对应解释字段为 null。
求值顺序：关闭 → defaultVariant/DISABLED；规则首个命中 → RULE_MATCH；无命中且有 rollout → PERCENTAGE_ROLLOUT；否则 → defaultVariant/DEFAULT。

`PUT /api/v1/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/evaluation-policy` 必须带 X-Operator：

```json
{
  "expectedVersion": 0,
  "rules": [{"priority": 10, "match": "ALL", "conditions": [
    {"attribute": "country", "operator": "EQ", "value": "JP"},
    {"attribute": "vipLevel", "operator": "GTE", "value": 3}
  ], "variantKey": "new"}],
  "rollout": [{"variantKey": "new", "weight": 1000}, {"variantKey": "old", "weight": 9000}]
}
```

成功返回 `{"version":1,"policy":{...}}`。这是完整策略替换；`{"expectedVersion":1}` 清空规则和 rollout。
rules 缺省/null/空数组表示无规则；rollout 缺省/null 表示不分流，空数组不合法。存在 rollout 时整数权重之和必须为 10000。
priority 非负且不重复，越小越优先；ALL=AND，ANY=OR，first-match-wins。
支持 EQ/NEQ/IN/NOT_IN/GT/GTE/LT/LTE/CONTAINS；缺失字段或类型不兼容均不匹配，负向操作符也不例外。
内置字段优先；appVersion 仅精确 EQ/NEQ/IN/NOT_IN；无 SemVer range。userId 必填、最长 256 字符、禁止 NUL。
上限：100 条 Rule、每条 1–20 条 Condition、100 个 allocation、IN/NOT_IN 1–100 个同类型标量。
同一项目/环境/Flag/userId 使用永久固定的 UTF-8 + NUL 分隔 + SHA-256 前四字节大端无符号值 %10000，得到 0–9999。

V2 新增 `evaluation_policy_json JSON NULL`，未修改 V1。policy 与原有开关/默认值共用 config version 乐观锁，stale 返回 409 `optimistic_lock_conflict`，成功更新和 `EVALUATION_POLICY_UPDATED` 审计同事务。

```powershell
mvn -pl rolloutcore-server -am test
mvn clean verify
# 启动连接真实 MySQL 的新 JAR 后执行；默认生成唯一 ProjectKey，不清理数据。
powershell -ExecutionPolicy Bypass -File scripts/day2-e2e.ps1
```

Day2 真实数据库验证状态：用户已确认 `REAL_MYSQL_DAY2_E2E=PASSED`，包括真实 V1/V2、规则匹配、稳定分流、stale 409 和审计。历史实现记录见 [Day2 报告](docs/DAY2_REPORT.md)。

Day2 最终自动验证：235 项测试全部通过（Day1 129 + Day2 106），`mvn clean verify` 成功；固定 5 万用户的 10% rollout 命中率为 10.122%。

## Day3 配置快照缓存

缓存键为 `(projectKey, environmentKey, flagKey)`，不包含 userId，不缓存最终求值结果。
不可变 EvaluationSnapshot 包含版本、开关、默认 Variant、已解析 policy 和不可变 Variant Map；每次请求继续执行 Day2 规则和 Stable Hash。

读路径：`L1 → L2（可选）→ MySQL → 回填 L2/L1`。L1 命中无网络、无数据库连接和事务；仅 DB miss 在 SnapshotRepository 开启 REPEATABLE_READ。
同 JVM 同 key 的并发 miss 共享一个加载结果；真实 Not Found 短暂负缓存。Redis 失败降级到 DB，写 Redis 失败不影响正常求值。
DB 基础设施故障时才允许使用有期限的 LKG；真实 Not Found、校验或数据错误不回退。

所有 Config create/update/enable/disable/policy 更新在事务提交后执行失效：清理本实例 L1、negative、LKG，并更新 Redis 版本栅栏。
本地 generation 和 version 防止旧加载回填；刚修改过的 key 不会回退到旧 LKG。Redis 原子脚本只用于缓存版本比较，不用于执行规则 DSL，不是分布式锁。

| 配置 | 默认值 | 环境变量 |
| --- | --- | --- |
| redis-enabled | false | ROLLOUTCORE_CACHE_REDIS_ENABLED |
| l1-ttl | 10s | ROLLOUTCORE_CACHE_L1_TTL |
| l1-maximum-size | 10000 | ROLLOUTCORE_CACHE_L1_MAXIMUM_SIZE |
| l2-ttl | 60s | ROLLOUTCORE_CACHE_L2_TTL |
| negative-ttl | 2s | ROLLOUTCORE_CACHE_NEGATIVE_TTL |
| lkg-ttl | 5m | ROLLOUTCORE_CACHE_LKG_TTL |
| lkg-maximum-size | 10000 | ROLLOUTCORE_CACHE_LKG_MAXIMUM_SIZE |
| Redis host / port | localhost / 6379 | ROLLOUTCORE_REDIS_HOST / ROLLOUTCORE_REDIS_PORT |
| Redis password | 空 | ROLLOUTCORE_REDIS_PASSWORD |
| Redis connect / command timeout | 200ms / 200ms | ROLLOUTCORE_REDIS_CONNECT_TIMEOUT / ROLLOUTCORE_REDIS_TIMEOUT |

这是开发默认值，可通过环境变量覆盖，不代表生产最优参数。negative cache 和版本水位的 maximumSize 使用 l1-maximum-size；版本水位 TTL 为 lkg-ttl+l2-ttl。
L1/LKG 各 JVM 独立，不跨实例共享。Redis key 为 `rolloutcore:eval:v1:{projectKey}:{environmentKey}:{flagKey}`，Redis Hash 存储版本及 JSON payload，禁止 Java 原生序列化。
本 JVM 每次配置失效还会让该 key 所在的 256 个固定 stripe 之一暂时绕过 L2，持续一个 l2-ttl，防止 Redis 删除失败或水位被容量淘汰后重新读入旧值；其他同 stripe 的 miss 可能额外读 DB。

Micrometer 注册 `rolloutcore.cache.*` 计数器和 Caffeine stats，不带 userId/projectKey/flagKey 标签。公共 Evaluation 响应不增加缓存调试字段。
Actuator 默认仍仅开放 health；Redis 是可选优化层，Redis health contributor 关闭，避免 Redis 故障把可用的 L1/DB 模式标为不可用。Redis 错误通过 metric/log 观察。

```powershell
# 启动连接真实 MySQL 的 Day3 JAR 后；默认 redis-enabled=false 即 L1+DB。
powershell -ExecutionPolicy Bypass -File scripts/day3-e2e.ps1
# 服务端显式启用 Redis 后，可提供已安装 redis-cli 和实际地址验证 L2 JSON/version/TTL。
# 密码由调用者安全设置 REDISCLI_AUTH，不写入命令行。
powershell -ExecutionPolicy Bypass -File scripts/day3-e2e.ps1 -RedisEnabled -RedisCliPath '<redis-cli路径>' -RedisHost '<实际地址>' -RedisPort <实际端口>
```

脚本默认唯一 ProjectKey，不清理数据、不管理系统服务。当前 Codex 环境未提供 DB 密码或实际 Redis 配置：`REAL_MYSQL_DAY3_E2E=NOT_RUN`、`REAL_REDIS_DAY3_E2E=NOT_RUN`。
无需 Day3 schema 变化，未新增 V3，V1/V2 保持原样。详见 [Day3 报告](docs/DAY3_REPORT.md)。

Day3 自动验证：284 项测试全部通过（保留 235 + 新增 49），`mvn clean verify` 成功；真实 Redis/MySQL Day3 E2E 仍待实际环境执行。

## 当前限制

- 没有鉴权、租户权限、归档 API、删除 API、Variant 修改 API。
- status 已建模，Day1 只创建 ACTIVE；尚无资源生命周期工作流。
- Kafka transport 已实现；默认 logging 模式仍仅依靠 TTL 和本地 after-commit。Kafka 异步传播有延迟，停机/订阅异常期间仍可能使用旧缓存；Redis 失效失败时旧共享值也可能存活至 TTL。
- single-flight 仅在单 JVM；LKG 是限时可用性兜底，不是强一致，其他实例未知的 Kill Switch 更新仍受陈旧窗口影响。
- Day4 Outbox/Kafka 已通过 Windows 原生 Broker 双 JVM 失效及停机恢复验证；恢复后最终 SENT 尚无直接 SQL 查询证据，Docker E2E 未运行。LoggingProducer 仍只是日志。Day5 已有远端 SDK/OpenFeature；没有全栈容器化、SDK 本地求值、Prometheus exporter 或完整生产压测。
- 审计与业务共享数据库事务，没有外部投递或不可篡改保证；分页使用 offset。
- 项目与环境的归属由 Service 和 scoped 查询保障；数据库外键保证资源存在，Variant 归属另有复合外键。绕过 Service 直接写库不属于支持的写入方式。
- 默认自动测试不依赖 MySQL；真实 V1、HTTP 持久化与回滚已另行验证，实际并发竞争和 JSON/外键完整边界未穷尽验证。
- 当前仍是一个 server module 内的逻辑边界；appVersion 无真正 SemVer range，策略不支持嵌套规则组、正则或任意代码执行。

进一步阅读：[架构](docs/ARCHITECTURE.md)、[数据库](docs/DATABASE.md)、[Day1 交付报告](docs/DAY1_REPORT.md)。
