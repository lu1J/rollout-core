# RolloutCore 架构（Day1 + Day2 + Day3）

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
  6 个 Mapper 接口 + 6 个 XML
                 |
                 v
  MySQL：业务表 + ff_audit_log
```

Controller 只处理 HTTP 路径、Header、DTO 与 JSON 响应；不依赖 Mapper。Service 使用构造器注入，
负责资源存在/归属、类型规则、事务、乐观锁结果和 Audit。Mapper 只负责数据库访问。
Commands 是不可变输入 record，避免将请求直接绑定到可修改 id/version 的实体。

domain 保存六个普通 Java 对象和两个枚举，无 Spring、MyBatis 或 server 依赖。领域对象使用明确的 getter/setter，
配合 MyBatis 查询映射和 JDBC generated keys。Variant 在 domain 内以 valueJson 保存规范 JSON 文本，
HTTP 入参和响应使用 Jackson JsonNode，避免让纯领域模块依赖具体 JSON 库。

目前选择模块化单体：Day1 写入有强事务关系，单一数据库即可原子更新和审计。
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
Day2 禁用状态返回 defaultVariant，reason=DISABLED；enabled=false 不等同于 value=false。

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
仅暴露 /actuator/health；生产配置保留 DB 健康检查且隐藏详情。

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

## Day2 求值与策略写入

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

Day3 将 REPEATABLE_READ 移到 SnapshotRepository.load，让 DB miss 的多次 scoped 查询仍读取一致快照。
缓存命中不再调用 ControlPlaneService 或创建数据库事务；DB miss 仍复用现有 scoped 读接口。
求值无写入、不产生成功 Audit，响应只暴露 Variant value、原因、版本、命中 priority 或 bucket。

policy 写入与 Day1 写入共用 checkVersion/persist，SQL 分别只写 policy 或 enabled/defaultVariant。
两种 SQL 都使用同一行的 `WHERE id=? AND version=?` 和数据库原子 `version=version+1`。
业务校验、旧快照、条件更新、新快照和 EVALUATION_POLICY_UPDATED 在一个写事务内；审计失败回滚。
完整策略进入 Day3 配置快照；当前没有 SDK、消息推送或微服务拆分。

## Day3 Cache Aside

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
最终响应仍按 Day2 的 JsonNode 数值语义构造。没有 userId cache key，也没有缓存最终 EvaluationResponse。

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
默认不增加 Actuator 暴露面；不添加高基数资源或用户 tag。依赖版本由 Spring Boot BOM 管理。
Day4 可消费 config-change event 调用同一失效逻辑以主动清理其他 JVM 的 L1；Day3 不实现 Kafka、Outbox 或跨 JVM single-flight。
