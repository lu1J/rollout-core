# Day1 架构

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
禁用状态的 Data Plane 返回值语义尚未实现，不应将 enabled=false 自动等同于 value=false。

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
