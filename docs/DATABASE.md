# RolloutCore 数据库

迁移文件：`rolloutcore-server/src/main/resources/db/migration/V1__init.sql`。
目标为 MySQL 8.0.16+、InnoDB、utf8mb4。Key 列使用 utf8mb4_bin，避免大小写不敏感比较干扰稳定标识。
应用层不转换大小写，Key 范围为 1–100 字符。

## 六张业务表

| 表 | 职责 | 唯一约束 |
| --- | --- | --- |
| ff_project | 项目命名空间 | project_key |
| ff_environment | 项目中的环境 | project_id, env_key |
| ff_feature_flag | Flag 定义和类型 | project_id, flag_key |
| ff_flag_variant | Flag 可选值，value_json 为 JSON | flag_id, variant_key |
| ff_flag_config | 环境级开关、默认 Variant、版本 | flag_id, environment_id |
| ff_audit_log | 写操作上下文和 before/after JSON | 主键 |

所有主键均为 BIGINT AUTO_INCREMENT。MyBatis useGeneratedKeys 将 id 回填到领域对象，
后续 Variant/FK/Audit INSERT 使用真实生成 id，不通过“查最大 id”等不安全方式获取。

## 关系与索引

- Environment / FeatureFlag → Project。
- Variant → FeatureFlag。
- Config → FeatureFlag、Environment。
- Config 的 (flag_id, default_variant_id) → Variant 的 (flag_id, id)，数据库阻止引用另一个 Flag 的 Variant。
- Audit → Project，Environment 和 Flag 外键可空。
- 没有 ON DELETE CASCADE；无删除 API，避免历史审计随业务资源隐式消失。

Variant 额外的 (flag_id, id) 唯一索引支撑复合外键，并非查询加速的随意索引。
除主键、唯一约束和外键所需索引，没有提前建立大量猜测性索引。
Config 的环境与 Flag 是否属于同项目由 Service 的 projectId scoped 查询和 owner 检查保障；
当前表结构没有用冗余 project_id 复合外键表达这一规则，不支持绕过 Service 任意写库。

为什么必须数据库唯一约束：先查询再插入无法避免并发请求同时认为资源不存在。
唯一约束让最终判定在数据库完成；MyBatis/Spring 异常翻译产生 DuplicateKeyException，
HTTP Advice 转成 409 duplicate_resource。不会用应用内集合代替数据库约束。

## JSON 与状态

value_json、before_json、after_json 都使用 MySQL JSON。
Mapper 用绑定参数传递 JSON 文本，不拼接用户输入，不用 ${} 字符串替换。
Service 用 JsonNode 类型检查 value，不能因为数据库接受 JSON 数字，就允许 BOOLEAN Variant 写入 1。

ResourceStatus 使用 ACTIVE / ARCHIVED 的数据库 CHECK；只创建 ACTIVE，未提供归档接口。
Flag 类型有 CHECK；config version >= 0，enabled 只允许 0 或 1。
created_at / updated_at 是应用生成的 UTC DATETIME(6)。

## 乐观锁 SQL 原文

```sql
UPDATE ff_flag_config
SET enabled = #{config.enabled},
    default_variant_id = #{config.defaultVariantId},
    version = version + 1,
    updated_at = #{config.updatedAt}
WHERE id = #{config.id}
  AND version = #{expectedVersion}
```

这是 MyBatis XML 中的原文；运行时 #{} 变成 JDBC 参数占位符。
version 初始 0；每次成功 UPDATE 加 1。
Service 在调用 SQL 前校验当前读取版本；即便读取后发生竞争，SQL 的版本条件仍会阻止覆盖。
影响 0 行抛 OptimisticLockConflictException，整个操作回滚，返回 409。
enable/disable 和完整 Config PUT 共享该语句，不存在绕过版本机制的快捷更新。

## Flyway 生命周期

Spring Boot 的 Flyway starter 在启动时连接配置的数据源，读取 db/migration，
创建 flyway_schema_history 并在空数据库顺序执行 V1、V2、V3。该历史表是 Flyway 元数据，不属于上述六张业务表。
成功后记录版本与校验和；后续启动不会重复执行 V1。
不要修改已部署的迁移，后续变更增加新版本。未启用 baseline-on-migrate 或 clean，
已有非空数据库需要先明确迁移策略，不能通过自动清理“解决”问题。

MySQL DDL 有隐式提交特性，不能假设失败迁移能像业务 DML 一样整体回滚。
若首次迁移中途失败，应由操作者检查数据库与 Flyway 历史，再决定修复方法；应用不自动删除表。

## 审计与事务

Audit 最少包含 project_id、可空 environment_id / flag_id、operator_name、operation、
before_json、after_json、created_at。支持的 operation：

PROJECT_CREATED、ENVIRONMENT_CREATED、FLAG_CREATED、VARIANT_CREATED、
CONFIG_CREATED、CONFIG_UPDATED、FLAG_ENABLED、FLAG_DISABLED、EVALUATION_POLICY_UPDATED。

审计与业务 INSERT/UPDATE 在同一个 Service 事务，保证“业务成功但无审计”不会成为正常成功结果。
创建快照 before 为 SQL NULL，after 包含生成 id、业务字段和时间。
查询按项目限制，以 id DESC 返回，limit 最大 100。
当前不提供审计写入/删除 API，但数据库管理员仍可修改数据，不宣称不可篡改。

## 实际验证状态

真实 MySQL 连接和 Flyway V1 已验证，REAL_MYSQL_E2E=PASSED。
默认单元测试不使用 H2、Docker 或外部 MySQL；可选 IT 使用真实 MySQL Testcontainer。XML 能加载、参数能绑定以及事务代理行为已纳入自动验证；
完整 HTTP E2E 已验证持久化、version 0→1→2、旧版本 409 和六条成功审计。
用户在应用层 Fail Fast 加入前，用重复 initial variantKey 触发后续 UNIQUE 冲突，最终查询不到对应 FeatureFlag，确认真实事务回滚。
现在 HashSet 在首次写入前拒绝重复 initial variantKey；UNIQUE (flag_id, variant_key) 保持不变。
实际并发竞争和 JSON/外键完整边界未穷尽验证。

## V2 migration

V1 保持原样；新增 `db/migration/V2__add_evaluation_policy.sql`：

```sql
ALTER TABLE ff_flag_config ADD COLUMN evaluation_policy_json JSON NULL;
```

已执行 V1 的库在下一次启动执行 V2；空库顺序执行 V1、V2。旧 Config 的字段为 SQL NULL，创建配置仍允许无 policy。
JSON 保存一个环境下一个 Flag 的完整策略，不新增规则表或基础设施依赖。
数据库仅校验 JSON 格式；规则合法性、Variant 归属、priority 唯一和权重和由 EvaluationPolicyValidator 在写入前保证。
不支持绕过 Service 直接写入策略，JSON 内的 variantKey 没有独立数据库外键。

```sql
UPDATE ff_flag_config
SET evaluation_policy_json = #{config.evaluationPolicyJson},
    version = version + 1,
    updated_at = #{config.updatedAt}
WHERE id = #{config.id}
  AND version = #{expectedVersion}
```

这条语句保留 enabled/default_variant_id；原有普通配置 UPDATE 保留 evaluation_policy_json。
两种更新竞争同一个 version，policy 无独立版本。stale 读取或条件更新影响 0 行都返回 409，并且不插入成功 Audit。
before/after 是 JSON 对象，包含 `configVersion` 和 `evaluationPolicy`（初始值 null），与更新同事务。
GET Config 的 evaluationPolicyJson 保持 domain 字符串表示；policy PUT 响应的 policy 为解析后的对象。

自动测试加载真实 MyBatis XML，并使用 ResultSetHandler 验证列映射；迁移测试验证 V2 内容和 V1 原文校验和。
这些 XML 单元测试不执行 MySQL DDL；实际中间件覆盖与执行状态见 [工程验证](ENGINEERING_VALIDATION.md)。

## V3 Transactional Outbox

outbox_event 包含用户指定的全部字段：id、event_id、event_type、project_key、
environment_key、flag_key、version、payload、status、created_at、updated_at。
使用 InnoDB、JSON payload、唯一 event_id、非负 version 约束、状态约束与 (status,id) 扫描索引。
状态枚举包含 PENDING/SENT/FAILED。

Mapper 提供 insert/findPending/markSent。
findPending 在 Relay 事务内执行 ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED，
避免并行 Relay 正常执行时同时处理相同行。
markSent 仅更新仍为 PENDING 的行，并更新 updated_at。
