# RolloutCore 架构

## 分层与模块边界

```text
HTTP + Jackson JsonNode + Jakarta Validation
  ControlPlaneController / ApiExceptionHandler
                 |
                 v
  ControlPlaneService（Spring 事务）
  InputRules（Key、操作者、类型及 command 校验）
                 |
                 v
  MyBatis Mapper 接口 + XML（包含 Outbox）
                 |
                 v
  MySQL：业务表 + ff_audit_log + outbox_event
```

Controller 只处理 HTTP 路径、Header、DTO 与 JSON 响应；不依赖 Mapper。Service 使用构造器注入，
负责资源存在/归属、类型规则、事务、乐观锁结果和 Audit。Mapper 只负责数据库访问。
Commands 是不可变输入 record，避免将请求直接绑定到可修改 id/version 的实体。

domain 保存普通 Java 领域对象和枚举，无 Spring、MyBatis 或 server 依赖。领域对象使用明确的 getter/setter，
配合 MyBatis 查询映射和 JDBC generated keys。Variant 在 domain 内以 valueJson 保存规范 JSON 文本，
HTTP 入参和响应使用 Jackson JsonNode，避免让纯领域模块依赖具体 JSON 库。

目前选择模块化单体：写入有强事务关系，单一数据库即可原子更新和审计。
微服务会增加跨服务事务、部署和故障恢复成本，尚无独立伸缩或团队边界需求。

## Definition 与环境配置

FeatureFlag 保存 projectId、flagKey、name、valueType、status；描述“这个功能是什么”。
FlagEnvironmentConfig 保存 flagId、environmentId、enabled、defaultVariantId、version；
描述“这个功能在某环境如何运行”。

同一个 Flag 可以在 prod 关闭、在 dev 启用，并使用不同默认 Variant。分离后不用复制 Flag 定义，
也不会把一个环境的开关误当作全局开关。Config 使用 (flag_id, environment_id) 唯一约束。

Variant 独立存储，一组有稳定 Key 和严格类型的值可以被多个环境复用。
创建配置按 flagId + variantKey 查询；不能引用另一个 Flag 的 Variant。
BOOLEAN 仍用两个普通 Variant 表达，不把 on/off 固定写进模型。
禁用状态返回 defaultVariant，reason=DISABLED；enabled=false 不等同于 value=false。

## 请求、类型和归属规则

DTO 用 Jakarta Validation 校验字段、长度、嵌套 Variants；Service 再验证 command，
确保直接调用 Service 也不会绕过输入约束。路径 Key 和 X-Operator 在 Service 校验。

InputRules.value 按 FeatureFlag.valueType 判断：
BOOLEAN → isBoolean()，STRING → isTextual()，NUMBER → isNumber()，
JSON → isObject() 或 isArray()；null 总是拒绝。字符串 "true" 不是布尔值，字符串 "12" 不是数值。
数据库 JSON 列只保障 JSON 格式，不能代替这些业务规则。

读取 Environment 和 Flag 时用 projectId 限定查询；Variant 用 flagId 限定查询。
Service 额外检查返回对象的 owner id。所属范围不匹配与不存在都返回 404，
避免将其他项目资源误用到当前配置。

## 事务边界与审计

所有写入口都在 public Service 方法上标注 @Transactional；类默认只读事务。
内部私有方法不创建额外事务，因此一项业务操作使用同一连接和事务：

```text
createFlag
  校验全部初始值
  INSERT ff_feature_flag
  INSERT ff_flag_variant old
  INSERT ff_flag_variant new
  INSERT ff_audit_log（FLAG_CREATED，包含 Flag + Variants）
  COMMIT
```

任意 INSERT 或审计失败会抛出运行时异常，由 Spring 回滚。
单独新增 Variant 记录 VARIANT_CREATED；嵌入创建 Flag 的 Variants 被收录在 FLAG_CREATED 快照内，
不额外制造多个独立业务操作。

Audit 单独建表，保留操作上下文、操作人、时间以及 before/after。
创建时 before=null；配置更新先序列化旧状态，条件更新成功后递增内存 version，再记录新状态。
冲突不会记成功审计。Audit 写入失败也不能留下没有审计的业务变更。
X-Operator 是调用者声明的名称，不是已认证的身份。

## 版本与 Kill Switch

更新、enable、disable 都走同一个条件 SQL。Service 首先核对读取版本，
保证 before 快照与客户端预期一致，再由 SQL 的 WHERE version 防止读取后的并发竞争。
affectedRows=0 抛 OptimisticLockConflictException → 409。

成功后客户端拿到 version+1；旧版本不能覆盖新状态。
enable/disable 保留原 defaultVariantId，即使布尔状态相同也消费一个版本。
没有 synchronized 或进程内锁，因此互斥依据在数据库，不依赖服务实例数量。

## 错误与可观测性

ApiExceptionHandler 生成 ProblemDetail 和稳定 code。DuplicateKeyException → duplicate_resource；
其他数据库异常响应为泛化 internal_error，不把 SQL 或堆栈发送给调用者。
HTTP 框架自身的 404/405/415 等保留相应状态。
仅暴露 /actuator/health 和 /actuator/prometheus；生产配置保留 DB 健康检查且隐藏详情。

## 验证边界

- MockMvc 验证状态、Header、DTO、错误码和 JSON 响应。
- Service 测试验证业务分支、资源归属、快照和 Mapper 调用。
- TransactionBoundaryTest 用真实 Spring 事务代理和 DataSourceTransactionManager，
  mock JDBC Connection 验证 commit/rollback、活动事务以及写入顺序。
- MapperContractTest 加载真正 XML，验证绑定参数、generated keys 和条件 SQL。
- ApplicationWiringTest 使用 Context Runner 加载实际配置与 Mapper，
  仅 mock DataSource 并关闭 Flyway/DB health，不声称数据库可用。

真实 MySQL Flyway V1、完整 HTTP E2E、旧版本 409 和成功审计已验证；用户另行完成 UNIQUE 冲突触发的事务回滚实验。
initial variants 重复 key 在首次写入前返回 validation_error，数据库 UNIQUE 保留兜底。实际并发竞争和 JSON/外键完整边界未穷尽验证。

## 求值与策略写入

```text
EvaluationController → EvaluationService
                         ├─ SnapshotProvider → SnapshotCache → SnapshotRepository（DB miss 的 REPEATABLE_READ）
                         ├─ RuleEngine → priority 排序、ALL/ANY、类型安全匹配
                         └─ StableBucketService → 固定 SHA-256 bucket
ControlPlaneController → ControlPlaneService.updatePolicy（写事务）
                         ├─ EvaluationPolicyValidator
                         ├─ FlagEnvironmentConfigMapper.updatePolicy（CAS）
                         └─ AuditLogMapper.insert
```

EvaluationService 编排读取和选择，RuleEngine 只负责匹配，StableBucketService 只负责 Hash。
EvaluationPolicy / TargetingRule / RuleCondition / RolloutAllocation 是 server/evaluation 包内的 JSON 数据模型；
domain 继续保持无 Jackson/Spring 依赖，Config 新增 evaluationPolicyJson 字符串用于持久化。
EvaluationContext 含 userId/country/vipLevel/appVersion 和可选 attributes。内置字段名保留，不允许 attributes 覆盖；扩展 key 按字面匹配，不执行路径遍历。

求值优先级严格为 DISABLED → RULE_MATCH → PERCENTAGE_ROLLOUT → DEFAULT。
Rule 按 priority 从小到大排序，首个满足即停止；ALL 所有条件满足，ANY 任一满足。
数字用 BigDecimal 比较，无字符串/布尔到数字的转换；missing 和 incompatible 均 false（包括 NEQ/NOT_IN）。
扩展 attributes 显式 JSON null 可匹配 null；缺失字段不能匹配 null。内置字段为 null 视为缺失。
IN/NOT_IN 要求非空同类型标量数组；字符串比较大小写敏感，CONTAINS 为字面子串。
appVersion 只支持 EQ/NEQ/IN/NOT_IN，不支持 SemVer range。

将 REPEATABLE_READ 移到 SnapshotRepository.load，让 DB miss 的多次 scoped 查询仍读取一致快照。
缓存命中不再调用 ControlPlaneService 或创建数据库事务；DB miss 仍复用现有 scoped 读接口。
求值无写入、不产生成功 Audit，响应只暴露 Variant value、原因、版本、命中 priority 或 bucket。

policy 写入与 写入共用 checkVersion/persist，SQL 分别只写 policy 或 enabled/defaultVariant。
两种 SQL 都使用同一行的 `WHERE id=? AND version=?` 和数据库原子 `version=version+1`。
业务校验、旧快照、条件更新、新快照和 EVALUATION_POLICY_UPDATED 在一个写事务内；审计失败回滚。
完整策略进入配置快照；SDK 通过 HTTP 求值，不复制策略；Kafka 传播配置失效事件，server 保持单体部署。

## Cache Aside

```text
EvaluationService → SnapshotCache.get(project, env, flag)
  ├─ L1 Caffeine hit → immutable snapshot
  ├─ negative hit → resource_not_found
  └─ per-key single-flight
       ├─ L2 Redis JSON hit → decode/validate → L1 + LKG
       └─ L2 miss/error/disabled → SnapshotRepository.load
              ├─ DB success → best-effort L2 → L1 + LKG
              ├─ confirmed Not Found → short negative cache，清除 LKG
              └─ infrastructure failure → bounded LKG 或原异常
snapshot → 原 RuleEngine / StableBucketService → 最终 Variant
```

EvaluationSnapshot 是不可变对象：CacheKey、configVersion、enabled、defaultVariantKey、已解析策略和 immutable Map。
Variant value 是递归不可变 Map/List/String/Boolean/BigDecimal/null 图；policy 向内和向外都深拷贝，防止 JsonNode 被调用者修改。
最终响应仍按 的 JsonNode 数值语义构造。没有 userId cache key，也没有缓存最终 EvaluationResponse。

ConfigChanged(key, latestVersion) 在成功写入及 Audit 后发布，SnapshotCache 用 AFTER_COMMIT 同步监听。
事务回滚不触发失效。create Config、普通 update、enable、disable、policy update 全覆盖。
新增 Variant 无需失效：现有策略无法引用尚未存在的 Variant，新增后也不会自动改变选择；后续引用它的配置写入会消费 version 并失效。

每个 JVM 使用 256 个固定 stripe 锁和 generation，只有短小的缓存操作、回填及有短 timeout 的 Redis 写入在锁内；DB 加载在锁外。
miss 开始捕获 generation；提交事件递增 generation，设置 minimumVersion，清除 L1/negative/LKG。旧加载不允许回填，最多重试 3 次，持续冲突则失败。
版本水位是 bounded Caffeine（TTL=lkgTTL+l2TTL，容量=l1MaximumSize）。为避免水位容量淘汰或 Redis 删除失败导致旧共享值重新进入本 JVM，
提交事件让该 stripe 在一个 l2TTL 内绕过 Redis 读取；Redis fill 的 TTL 扣除本次加载耗时，超时加载不写 L2。
该保守策略会增加同 stripe 的 DB miss，保留了固定大小锁数组和有限水位存储，无永久增长的 key map。

Redis Hash 保存 version 和 JSON payload。Lua 使用十进制字符串长度/字典序比较版本，避免大于 2^53 时的精度损失。
更新失效保留有 TTL 的版本 tombstone；旧版本 fill 不能覆盖更新的 payload 或 tombstone。Lua 操作是单 key 原子的，不是分布式锁。
Redis hit 的解析或结构验证错误按缓存故障回退；Redis get/put/invalidate 异常仅记录指标和固定内容日志。

LKG 只用于 DataAccessResourceFailureException、TransientDataAccessException、CannotCreateTransactionException 等基础设施故障。
不对普通业务异常、真正 Not Found、数据完整性错误回退；fallback 不重新填 L1，也不刷新 LKG TTL。
本实例已知写入后清除旧 LKG，宁可该 key 在 DB 故障时失败，也不回退到旧 enabled=true。
无 Kafka 时不能对未知的跨实例 Kill Switch 更新做同等保证；多实例缓存及 LKG 都不是强一致。

默认 L1=10s/10000，L2=60s，negative=2s/10000，LKG=5m/10000；全部通过 CacheProperties/application.yml 配置。
Micrometer 计数器覆盖 hit/miss/error/db_load/negative/singleflight/LKG/invalidation/stale-fill；Caffeine recordStats 提供容量和命中基础指标。
通过 Prometheus 暴露现有指标；不添加高基数资源或用户 tag。依赖版本由 Spring Boot BOM 管理。
Kafka 消费 config-change event，调用同一失效逻辑主动清理其他 JVM 的 L1；single-flight 仅限单 JVM。

## 写平面、读平面及业务接入

| 边界 | 当前职责 | 工作负载 |
| --- | --- | --- |
| Control Plane | ControlPlaneController、ControlPlaneService、配置写入、校验、Audit、Outbox/Relay | 低频写，事务正确性和持久化优先 |
| Evaluation Plane | EvaluationController/API、EvaluationService、Snapshot Cache、RuleEngine、StableBucketService | 高 QPS 读，低延迟；命中配置快照后不访问 DB |
| Business Service | Demo 或业务自己的 Controller/Service、调用方默认值与业务分支 | 通过 SDK HTTP 请求 Evaluation Plane |

当前 server 保持一个部署单元。已有 controller/service/cache/evaluation/event 包已经表达职责，
业务接入不要求拆分 server 进程。Outbox 与 Audit 在配置写事务内；
LoggingProducer 仍只是日志传输占位，不是 Kafka。Kafka transport 已提供真实 Producer/Listener 代码，
已在 Windows 原生 Kafka 4.2.1 单节点通过双 JVM 主动失效及 Broker outage/recovery 手工验证；
Docker 路径未运行，恢复后最终 SENT 未通过真实 SQL 直接查询，不扩展为生产部署或 exactly-once 保证。

这是自然的未来微服务边界：写操作关注事务、审计和低频变更，读操作关注独立扩容、缓存命中与请求延迟。
若实际容量或部署需求推动拆分，EvaluationController、EvaluationService、SnapshotProvider/Cache/Repository、
RuleEngine、StableBucketService 及只读存储适配应进入 Evaluation 服务；
配置写 controller/service、Audit、Outbox/Relay 和写 mapper 留在 Control 服务。
EvaluationPolicy 等共享协议/校验模型应先抽成稳定契约，避免 Evaluation 服务反向依赖整个 Control 服务。
配置事件已有 Kafka transport 调用各实例 ConfigChangedConsumer，
但未来拆分仍需完善版本演进、启动恢复和部署运维，不能仅改 Maven 模块名称。

SDK 的 baseUrl 可以直接指向 Evaluation 服务或其负载均衡地址，业务 API 无需因此变化。
今天没有无法用 baseUrl 解决的服务发现需求，因此没有引入 Nacos、Eureka、Spring Cloud 或 Kubernetes。

### 模块依赖与调用链

```text
rolloutcore-server → rolloutcore-domain
rolloutcore-sdk → Java 21 java.net.http + Jackson 3
rolloutcore-spring-boot-starter → rolloutcore-sdk + Spring Boot autoconfigure
rolloutcore-openfeature-provider → rolloutcore-sdk + OpenFeature SDK 1.20.2
rolloutcore-demo-service → rolloutcore-spring-boot-starter + Spring Boot Web/Actuator

Demo HTTP Controller → RolloutCoreClient.booleanFlag
  → SDK-owned request DTO → POST {baseUrl}/api/v1/evaluate
  → EvaluationController → EvaluationService → Snapshot/Rule/Stable Rollout
  → JSON response → SDK type validation/result → Demo old/new business response

OpenFeature Client → RolloutCoreProvider → same SDK HTTP/fallback implementation
```

SDK 不依赖 server、MyBatis Domain 或数据库。HTTP 请求字段沿用 projectKey/environmentKey/flagKey/context；
响应使用 flagKey/variantKey/value/reason/configVersion，容忍额外字段以便演进。
协议没有显式 valueType，SDK 按 JSON 节点严格判断 boolean/string/number/object-or-array；
不把字符串数字转换成 NUMBER，也不把字符串 true 转换成 BOOLEAN。

SDK 的重试、LKG、线程安全、Starter 与 OpenFeature 映射见 [SDK 接入](SDK_INTEGRATION.md)。

## Kafka transport：至少一次与每实例订阅

```text
ControlPlaneService 写事务
  config CAS → Audit INSERT → Outbox(event_id=固定 UUID, PENDING) → COMMIT
  → 本 JVM AFTER_COMMIT 失效

OutboxRelayService（原有事务、LIMIT 20 FOR UPDATE SKIP LOCKED）
  → KafkaConfigEventProducer
  → 独立 JSON envelope + key=project:environment:flag
  → KafkaTemplate.send().get(timeout) → Broker ACK
  → markSent → MySQL COMMIT

Kafka topic → group=rolloutcore-cache-instance-a → A Listener → A ConfigChangedConsumer → A SnapshotCache
            → group=rolloutcore-cache-instance-b → B Listener → B ConfigChangedConsumer → B SnapshotCache
```

Boot 4.1.1 管理的 starter-kafka 实际解析为 Spring Kafka 4.1.1、kafka-clients 4.2.1。
transport=logging 保留原日志开发模式，不启动 config-event listener；transport=kafka 启用真实 adapter。
实例 ID 必须明确配置且唯一稳定，不生成随机 group，不共享一个默认 group。
相同 group 的消费者分摊 partition；不同 group 各自维护 offset，才能让每个独立 L1 都收到完整事件流。
同一实例内 listener concurrency=1，可消费所有分配的 partition。
新的 group 从 earliest 开始，已有 group 从已提交 offset 恢复；不包含 retention 之外的历史。
启动时 L1 本来为空，仍从数据库权威配置回源，不把消息日志当完整配置存储。

协议字段 eventId/schemaVersion/projectKey/environmentKey/flagKey/configVersion/occurredAt 均校验，
拒绝标量强制转换、负版本、错误 UUID/资源 key、不支持的 schema、无时区时间以及 record key 不一致。
允许未来增加字段；schemaVersion 当前仅接受 1。
eventId 复用 Outbox 唯一 event_id，occurredAt 取原 created_at（UTC），重试不重新生成。
三段资源 key 不含冒号，组合无歧义；固定 partition 数下同 key 进入同一 partition。
多 relay 的 SKIP LOCKED 和多 producer 仍可能先发高版本，不能把 partition 顺序当全局 DB 版本顺序。

Producer 强制 acks=all、enable.idempotence=true、max.in.flight=5、retries=Integer.MAX_VALUE；
delivery.timeout.ms=sendTimeout、request.timeout.ms=min(1000,sendTimeout)、linger.ms=0，
满足 delivery timeout >= request timeout + linger。max.block.ms=min(1000,sendTimeout) 限制 metadata/buffer 等待。
默认 sendTimeout=5s（允许 100ms–60s），send 调用阻塞与 future 等待总计最多约 1s+5s，
Kafka 内部 retry 由 delivery timeout 收敛。Relay 本身后续轮询仍会重试 PENDING，没有终止投递策略。
网络等待保留在原 Relay 锁行事务中，20 行最坏可能放大事务时长，这是当前小批量实现的已知限制。

Kafka ACK 和 MySQL SENT 提交不原子：ACK 后宕机/SQL 失败/提交失败会再次发送相同 eventId。
Kafka producer idempotence 只处理 producer 会话内协议重试造成的部分重复，
不能消除 Outbox 重投、不同 producer、数据库事务回滚产生的重复。
这是 at-least-once + semantic idempotency，不是跨 MySQL/Kafka exactly-once。
配置写事务从不直接依赖 Broker 在线；Broker 不可用时仍可提交 config/Audit/PENDING。

消费端使用 StringDeserializer，然后 adapter 自己校验 JSON/schema/key。
enable.auto.commit=false，AckMode.RECORD，syncCommits=true。
成功返回表示本地版本失效已完成，随后容器才提交该记录的 offset；
失败本地处理默认额外重试两次（0–5 可配），间隔 250ms（0–5s 可配）。
协议错误不做无意义重试；耗尽/协议错误交由 CommonContainerStoppingErrorHandler 停止实例订阅，
失败 offset 不被提交，修复并重启可重放。没有静默跳过、DLT 或 processed_event 表。
无效消息需修复原因或明确运维处置；盲目重启仍会停在同一记录。
监听器停止不等于业务 HTTP 进程停止；目前未新增订阅专用 health/告警，运维必须观察日志/group lag。

KafkaConfigChangedListener 仅委托既有 ConfigChangedConsumer，
SnapshotCache 保留原语义：incoming < minimum 忽略；incoming == minimum 仍失效；
更高版本更新水位、推进 generation、清 L1/negative/LKG 并执行原 Redis 策略。
相同版本重复可能多一次回源，但不会写配置/Audit/Outbox；create version=0 不能被省略。
version 水位有 TTL/容量限制，不是永久 eventId 去重；TTL、DB 权威回源及 generation 共同保证缓存行为，
不承诺缓存瞬时全局一致。指标区分 applied/stale，版本判断与失效操作在同一 stripe 锁内完成。
日志包含 eventId/key/version/instance/group；没有高基数 Micrometer tag。

默认 logging 已标 SENT 的历史行不会在切换 Kafka 后自动重放；需要计划迁移/TTL 恢复，
不能把日志成功解释为以前已广播。
真实 Broker、双 JVM 和停机恢复验证状态及脚本见 [Kafka 验证](KAFKA_VALIDATION.md)。


## 工程化边界

EvaluationService 用一个 Timer 同时提供 count 和 latency；缓存复用 CacheMetrics/Caffeine binder。
Outbox send counter 表示传输尝试，不等于 MySQL SENT 已提交；Kafka outcome 反映实际本地失效结果。
标签为固定枚举，无资源或用户维度。详见 [可观测性](OBSERVABILITY.md)。

普通 Surefire 测试无 Docker 依赖；`-Ptestcontainers` 启用 Failsafe，使用真实 MySQL、Redis、Kafka。
完整 Compose 提供开发环境；CI 分开运行普通验证与容器集成测试。
参见 [部署](DEPLOYMENT.md)、[工程验证](ENGINEERING_VALIDATION.md)、[性能](PERFORMANCE.md)。
