# Day1 交付报告

验证日期：2026-09-10（Asia/Shanghai）。未执行 Git commit，未修改 Windows MySQL 服务。
真实 MySQL 与 Flyway V1 已验证，完整 HTTP E2E 已通过；最终构建结果见第 19、20 节。未启动 Day2。

## 1. 初始仓库状态

重新以 UTF-8 读取 workspace/codex_prompts/day1.txt 后探索仓库：
只有单模块 pom.xml、.gitignore、IDE 配置和空的 src/main/java、src/main/resources、src/test/java。
原坐标 io.github.lu1j:rollout-core:1.0-SNAPSHOT，Java source/target 为 21。
没有业务代码、迁移、依赖或自动测试，也未找到适用的 AGENTS.md。

本机 Java 21.0.8、Maven 3.9.4。初始 .gitignore、pom.xml 和三个 .idea 文件已被暂存。
保留原有用户文件；原空 src 目录未删除。Git 因执行账户与目录所有者不同提示 dubious ownership，
所有 Git 命令采用进程级 -c safe.directory=E:/download/code/rollout-core，没有修改全局配置。

## 2. 最终 Maven 模块结构

```text
rollout-core                       packaging=pom
├── rolloutcore-domain             领域模型
├── rolloutcore-server             可执行 Control Plane
├── rolloutcore-sdk                空 JAR 骨架
├── rolloutcore-spring-boot-starter 空 JAR 骨架
├── rolloutcore-openfeature-provider 空 JAR 骨架
└── rolloutcore-demo-service       空 JAR 骨架
```

统一 groupId=io.github.lu1j，version=0.1.0-SNAPSHOT，package root=io.github.lu1j.rolloutcore。
server 依赖 domain；domain 无外部依赖，更不依赖 server。另四模块没有未来功能代码。

## 3. 新增 / 修改文件

修改根 pom.xml 与 .gitignore；新增 51 个可审阅文件，共 53 个项目文件（不计忽略内容）。
其中包括 6 个模块 POM、8 个 domain Java 文件、15 个 server 主代码文件、8 个 server 资源文件、
7 个测试 Java 文件（6 个测试类和 1 个共享 fixture）、3 份 docs、README、E2E 脚本和 2 个 .mvn 文件。

主要分组：

- .mvn/maven.config、.mvn/settings.xml：缓存和 settings 均限定在仓库。
- rolloutcore-domain/src/main/java/io/github/lu1j/rolloutcore/domain/：六模型和两枚举。
- server/api/：ControlPlaneController、ApiExceptionHandler、JsonConfiguration。
- server/service/：Commands、InputRules、ControlPlaneService、BusinessException、OptimisticLockConflictException。
- server/mapper/：Project、Environment、FeatureFlag、FlagVariant、FlagEnvironmentConfig、AuditLog 的六个 Mapper。
- server/resources/：application.yml、V1__init.sql、六个 Mapper XML。
- 测试：InputRulesTest、ControlPlaneServiceTest、TransactionBoundaryTest、ControlPlaneControllerTest、MapperContractTest、ApplicationWiringTest、ServiceFixture。
- README.md、docs/ARCHITECTURE.md、docs/DATABASE.md、本文、scripts/day1-e2e.ps1。

workspace 中的构建日志、Maven 缓存和用户 prompt 均被忽略。三个 .idea 文件仅移出 Git 索引，
已检查本地文件仍存在。没有执行全量 git add 或改变用户原先暂存的 pom/.gitignore 基线。

## 4. Maven 依赖与版本

以下为实际测试 classpath / 已解析 POM 中的版本：

| 组件 | 版本 |
| --- | --- |
| Java / Maven | 21.0.8 / 3.9.4 |
| Spring Boot parent、WebMVC、Validation、Actuator、Flyway starter | 4.1.1 |
| Spring Framework（core、webmvc、jdbc、tx） | 7.0.9 |
| MyBatis Spring Boot Starter / MyBatis Spring | 4.0.0 |
| MyBatis | 3.5.19 |
| MySQL Connector/J | 9.7.0 |
| Flyway Core / Flyway MySQL | 12.4.0 |
| Jackson Databind | 3.1.5 |
| Jakarta Validation API | 3.1.1 |
| Hibernate Validator | 9.1.3.Final |
| JUnit Jupiter | 5.14.4 |
| Mockito | 5.23.0 |
| Maven Compiler / Surefire / JAR | 3.15.0 / 3.5.6 / 3.5.1 |

MySQL Connector/J 版本不改变目标 SQL 的 MySQL 8 基线。Hibernate Validator 是校验库，
未引入 Hibernate ORM / JPA。未引入 H2、Redis、Kafka 或其他禁止组件。

Boot 默认管理 JUnit 6，根 POM 通过 junit-jupiter.version 固定 JUnit 5。
当前 SpringExtension 调用了 JUnit 6 的 Store.computeIfAbsent，
因此装配测试使用 Context Runner，保留 JUnit 5 并仍加载实际 Spring 配置。

Jackson 采用 Boot 默认的 tools.jackson 包。
参考的官方说明：[Spring MVC](https://docs.spring.io/spring-boot/reference/web/index.html)、
[Jackson 支持](https://docs.spring.io/spring-boot/reference/features/json.html)、
[MyBatis Starter](https://mybatis.org/spring-boot-starter/mybatis-spring-boot-autoconfigure/)。

## 5. Controller → Service → Mapper 调用链

以 enable 为例：

```text
POST /api/v1/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/enable
  → ControlPlaneController.enable（DTO + X-Operator）
  → ControlPlaneService.setEnabled（事务）
  → ProjectMapper.findByKey
  → EnvironmentMapper.findByKey(projectId, envKey)
  → FeatureFlagMapper.findByKey(projectId, flagKey)
  → FlagEnvironmentConfigMapper.find(flagId, environmentId)
  → FlagEnvironmentConfigMapper.update(config, expectedVersion)
  → AuditLogMapper.insert
  → commit → HTTP 200
```

Controller 无 Mapper 引用，所有依赖使用构造器注入。Service 持有业务规则与事务边界，
Mapper XML 展示实际 SQL，未使用生成器隐藏访问逻辑。

## 6. 六张表与职责

| 表 | 职责 |
| --- | --- |
| ff_project | 项目及全局唯一 projectKey |
| ff_environment | 项目内环境，支持 prod/dev 等独立配置 |
| ff_feature_flag | Flag 的定义、名称、固定 valueType、status |
| ff_flag_variant | Flag 下可供引用的类型化 JSON 值 |
| ff_flag_config | Flag 在某环境的 enabled、默认 Variant 和 version |
| ff_audit_log | 项目/环境/Flag 上下文、操作人、动作、前后快照和时间 |

Flyway 还会管理自己的 flyway_schema_history 元数据表，不计入六张业务表。

## 7. Definition / Environment Config 为什么分离

Flag 定义不随部署环境复制。环境配置单独保存开关和默认值，
因此同一个 Flag 可以在 dev 启用、在 prod 禁用，不会互相覆盖。
环境配置自己拥有 version，避免跨环境共享一个无关版本号。
未来 Data Plane 可以消费这些配置，但 Day1 未提供求值或传播机制。

## 8. Variant 模型

FlagVariant 以 flagId 归属 FeatureFlag，用稳定 variantKey 作为业务标识，
valueJson 保存标准 JSON 文本，数据库列为 JSON。
多个环境配置可以复用同一 Variant。创建 Flag 可一次携带 old=false / new=true；
也可以后续调用单独的新增 Variant API。

HTTP 用 JsonNode，Variant 查询响应还原为原生 value 节点，不把 value 返回成转义字符串。
新增了 GET variants API 便于查看创建 Flag 时插入的初始值。

## 9. JSON 类型校验

Service 依据 FlagValueType 严格执行：

| 类型 | 条件 |
| --- | --- |
| BOOLEAN | value.isBoolean() |
| STRING | value.isTextual() |
| NUMBER | value.isNumber() |
| JSON | value.isObject() 或 value.isArray() |

Java null 和 JSON null 均拒绝。BOOLEAN 不接受字符串 "true" 或数字 1，
NUMBER 不接受字符串 "12"，JSON 不接受标量。错误为 400 validation_error。
初始 Variants 全部校验后才写 Flag，避免无意义的部分写入。

JsonConfiguration 还禁止小数截断为 expectedVersion、字符串强制转版本号以及数字枚举序号。
这些设置由实际应用上下文测试验证，不只是单独测试一个自建 ObjectMapper。

## 10. Flyway 工作方式

启动应用时使用 ROLLOUTCORE_DB_* 连接 MySQL，扫描 db/migration 并执行 V1__init.sql，
记录版本和校验和。应用不创建数据库，不开启自动 baseline 或 clean。
首次使用应准备独立空数据库；后续变化增加 V2 等迁移，不能改写已应用的 V1。

MySQL DDL 具有隐式提交特性，迁移失败不能假设整体回滚。此行为与业务 DML 的 Spring 事务区别开来。
用户已确认 Flyway V1 在真实 MySQL 执行成功。

## 11. 数据库唯一约束

严格建立 project_key、(project_id, env_key)、(project_id, flag_key)、
(flag_id, variant_key)、(flag_id, environment_id) 五个业务唯一约束。
并发重复写由数据库最终裁决，DuplicateKeyException 转成 HTTP 409 duplicate_resource。

有必要的外键，且 Config 的 (flag_id, default_variant_id) 通过复合外键引用 Variant 的 (flag_id, id)，
进一步阻止引用别的 Flag 的值。其他索引限于主键、唯一约束和外键需要。
没有通过先查询再插入或 synchronized 代替数据库约束。

## 12. 事务边界

所有写操作的 public Service 方法使用 @Transactional，读操作默认 readOnly=true。
创建 Flag 的 INSERT flag、INSERT old、INSERT new、INSERT audit 在一个 Spring 事务中。
写审计失败同样回滚业务写入；配置更新与审计也具有相同原子边界。

TransactionBoundaryTest 使用真实事务拦截器、AnnotationTransactionAttributeSource、
DataSourceTransactionManager 和 mock JDBC Connection，验证活动事务、commit/rollback 及调用顺序。
这验证 Spring 事务边界；真实 MySQL 回滚另由用户完成故障实验验证，见第 21 节。

## 13. 乐观锁 SQL 原文

来自 FlagEnvironmentConfigMapper.xml：

```sql
UPDATE ff_flag_config
SET enabled = #{config.enabled},
    default_variant_id = #{config.defaultVariantId},
    version = version + 1,
    updated_at = #{config.updatedAt}
WHERE id = #{config.id}
  AND version = #{expectedVersion}
```

运行时使用 JDBC 绑定参数。MyBatis XML 合约测试验证最终 SQL、id 和 expectedVersion 参数位置。

## 14. 乐观锁冲突流程

先读取 config，若当前 version != expectedVersion，立即抛 OptimisticLockConflictException。
匹配时保留 before 快照，再执行上述 SQL。如果读取后有其他事务抢先更新，
本次影响 0 行，再抛同一异常。Advice 返回 409 optimistic_lock_conflict。
失败不写成功审计，事务回滚；客户端需要重新 GET，再决定是否提交新版本。

成功后返回 version+1，并将该版本写入 after 快照。无需依赖服务进程内锁。

## 15. Audit 设计

保存 projectId、可空 environmentId/flagId、operatorName、operation、beforeJson、afterJson、createdAt。
支持全部八种 Day1 operation。创建操作 before=null，after 含生成 id 和业务状态；
创建 Flag 的 after 还包含初始 Variants。单独新增 Variant 才记录 VARIANT_CREATED。

配置变更快照包含 enabled、defaultVariantId、version 和更新时间。
GET /audits 按 projectKey 限定，limit 默认 50、最大 100、offset 默认 0，按 id DESC 返回。
HTTP before/after 为 JSON 节点，方便直接查看。

X-Operator 必填、非空且最长 100 字符。它只是客户端声明的审计名称，不构成鉴权。
没有审计删除 API或外部投递，也不宣称管理员无法篡改数据库。

## 16. Kill Switch 实现

enable 和 disable 都调用 setEnabled，再进入相同版本更新路径。
它们只改变 enabled 并保留 defaultVariantId；同状态重复请求仍消费版本。
已测试 enable 0→1、disable 1→2、stale enable/disable 拒绝及数据库影响 0 行的竞争路径。

Day1 Kill Switch 范围为 Control Plane 数据库配置，不包含客户端立即生效保证、
消息通知、SDK 缓存失效或禁用值求值语义。

## 17. API 清单

所有要求的路径保持原样，仅新增 GET variants。统一前缀 /api/v1：

| 方法 | 路径 |
| --- | --- |
| POST | /projects |
| GET | /projects/{projectKey} |
| POST / GET | /projects/{projectKey}/environments |
| POST | /projects/{projectKey}/flags |
| GET | /projects/{projectKey}/flags/{flagKey} |
| POST / GET | /projects/{projectKey}/flags/{flagKey}/variants |
| POST / GET / PUT | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/config |
| POST | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/enable |
| POST | /projects/{projectKey}/environments/{envKey}/flags/{flagKey}/disable |
| GET | /audits?projectKey=...&limit=50&offset=0 |

共 14 个 HTTP 操作。创建返回 201，读取/更新返回 200。配置写入 DTO 与运行示例详见 README。
错误为 Spring ProblemDetail：400 validation_error、404 resource_not_found、
409 duplicate_resource / optimistic_lock_conflict；非预期错误使用 500 internal_error，不泄露 SQL 或堆栈。

## 18. 测试数量和覆盖

| 测试类 | 执行用例数 |
| --- | ---: |
| InputRulesTest | 44 |
| ControlPlaneServiceTest | 44 |
| ControlPlaneControllerTest | 22 |
| TransactionBoundaryTest | 7 |
| MapperContractTest | 5 |
| ApplicationWiringTest | 7 |
| 合计 | 129 |

参数化测试按实际独立输入计数。没有 assertTrue(true) 或为了数量创建的空测试。
覆盖 Key、四种类型与错误类型、重复项目/环境/Flag/Variant/Config、缺失资源、归属校验、
创建 Flag 带初始值、配置 CRUD、乐观锁成功/冲突、开关、审计、事务失败、Header 与 Controller 400/404/409。
另覆盖实际上下文、health 可用/未就绪/不暴露其他端点、JSON 请求标量严格性与 XML 参数绑定。

## 19. mvn test 结果

最终收尾执行：mvn -pl rolloutcore-server -am test。
BUILD SUCCESS；129 tests、0 failures、0 errors、0 skipped，Maven 退出码为 0。
本次三个 Reactor 项目全部 SUCCESS。日志：workspace/mvn-server-test.log。
其中 rejectsDuplicateInitialVariantKeysBeforeWriting 使用两个 duplicate key 和合法 BOOLEAN false/true，
断言 400 validation_error 及重复 key 消息，并验证 flags、variants、audits 的 insert 从未调用，证明首次写入前失败。
该测试已在上一轮补入，本次复核保留，没有重复新增测试或修改手工业务实现。

前期测试发现并修复了 Mockito 重设 stub 的空指针、SpringExtension/JUnit 版本不兼容、
Context Runner 生命周期事件缺失，以及 Jackson 3 的 EnumFeature 配置位置差异。
未削弱业务约束来消除失败。

## 20. mvn clean verify 结果

最终收尾执行：mvn clean verify。
BUILD FAILURE，退出码 1：clean 阶段无法删除被其他进程占用的 server JAR。
本次未进入全量测试/打包验证，不能将此前 128 个测试的历史成功视为当前最终验证成功。
需释放 rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar 的文件占用后重跑；本次未擅自停止服务。
日志：workspace/mvn-clean-verify.log。

Jackson 的 deprecation 提示和 Mockito 动态 agent 警告未影响本次定向测试；最终构建阻塞原因是 JAR 文件占用。

## 21. Real MySQL E2E

**REAL_MYSQL_E2E=PASSED**

用户确认真实 MySQL 已连接，Flyway V1 已执行。此前本会话在 http://127.0.0.1:8080 执行完整脚本成功，
ProjectKey 为 checkout-service-e2e-66a39f1c4949，退出码 0；本轮未重复写入 E2E 数据。
health=UP，Project、prod Environment、new-payment-flow 和 old=false/new=true 均通过；
Config version 0→1→2，stale expectedVersion=0 真实返回 HTTP 409 optimistic_lock_conflict，
最终 GET Config enabled=false、version=2。六种成功 Audit 各一条，operatorName=day1-e2e，
enable before/after 为 0→1，disable 为 1→2，冲突未产生成功 Audit。

用户另行完成真实事务 rollback 实验：应用层 Fail Fast 加入前，用两个重复 initial variantKey
使前面的写入发生后触发后续 UNIQUE 冲突，最终查询不到对应 FeatureFlag，证明业务事务回滚。
此实验由用户报告，本轮未重跑，也未删除数据库 UNIQUE 约束。

PowerShell 5.1 下 Invoke-WebRequest 的 health Content 为 byte[]，直接管道给 ConvertFrom-Json
会得到数字数组，导致 status 为空。最小修复是仅对 byte[] 使用 UTF8.GetString 后再解析；字符串保持原样。
此前已验证 health=UP 并通过完整 E2E。

ControlPlaneService.createFlag 使用 HashSet 检测 initial variantKey 重复，在 flags.insert 前抛出
BusinessException.validation（400 validation_error）；原有 Variant value 类型校验保留，
MySQL UNIQUE (flag_id, variant_key) 完全保留作为最终兜底。
脚本不清理已有数据或复用同名项目，重复运行须指定新的合法 ProjectKey。

## 22. Actuator

生产只通过 management.endpoints.web.exposure.include=health 暴露 health，
show-details=never。正常运行保留真实 DB health。
测试 mock DataSource，关闭 Flyway 和 DB health；通过发布真实 availability 事件模拟应用就绪，
验证 HTTP 200/UP、不暴露组件详情，以及未就绪 HTTP 503；/actuator/env 返回 404。
这些检查不能推导出本机 MySQL 健康。

## 23. git diff --check

最终收尾 git diff --check 退出 0；该命令不覆盖未跟踪文件。
本次另对四份修改的 Markdown 文档和现有 Fail Fast 测试扫描行尾空白，无匹配。
Git 提示 LF 将按本机设置转换 CRLF，但未报告差异格式错误。
执行账户无法读取用户的全局 ignore 文件的提示不影响已验证的仓库 .gitignore。

## 24. git diff --stat

本次输出：

```text
 .gitignore |  7 ++++++-
 pom.xml    | 23 +++++++++++++++++++++--
 2 files changed, 27 insertions(+), 3 deletions(-)
```

这是相对既有索引的普通 diff，**不包含 51 个新增、未跟踪文件**，不能将它当成本次总改动规模。
完整新增范围见第 3 项。保留新增文件未暂存，以便用户自行审阅，不用 git add 改变其暂存选择。

## 25. git status --short

```text
AM .gitignore
AM pom.xml
?? .mvn/
?? README.md
?? docs/
?? rolloutcore-demo-service/
?? rolloutcore-domain/
?? rolloutcore-openfeature-provider/
?? rolloutcore-sdk/
?? rolloutcore-server/
?? rolloutcore-spring-boot-starter/
?? scripts/
```

AM 表示文件原先已暂存为新增，本次工作区又有修改。没有 Git commit。
workspace/.idea/.env/模块 target 的 check-ignore 均命中，IDE 文件在本地保留。

## 26. 当前 Known Limitations

无 Authentication/Authorization、资源归档/删除、Variant 修改、规则求值、灰度 Hash、SDK 行为、
配置分发、缓存、消息投递、Outbox、OpenFeature、Prometheus、Docker 或压测。
状态枚举存在，但 Day1 只创建 ACTIVE。审计支持有界 offset 分页，无防篡改保证。
配置中的环境/Flag 同项目关系通过 Service scoped 查询验证；不支持绕过 Service 任意写库。
真实数据库迁移和回滚已验证；实际并发竞争及 JSON/外键完整边界未穷尽验证。

## 27. Day2 准备情况

已有清晰的领域模型、环境隔离配置、稳定 Key、类型化 Variant、version 和可追踪写入。
六模块依赖边界与四个占位模块可以承接后续工作。
未提前实现任何被禁止的后续能力；真实 MySQL Day1 验证已完成，后续根据届时要求扩展，
不应将 Day1 开关管理直接当作完整 Feature Flag Data Plane。

## 28. 最值得亲自阅读的 10 个源文件

建议按以下顺序阅读（XML/SQL 同样属于核心源码）：

1. [FeatureFlag.java](../rolloutcore-domain/src/main/java/io/github/lu1j/rolloutcore/domain/FeatureFlag.java)：理解定义字段。
2. [FlagEnvironmentConfig.java](../rolloutcore-domain/src/main/java/io/github/lu1j/rolloutcore/domain/FlagEnvironmentConfig.java)：理解环境开关和 version。
3. [Commands.java](../rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/service/Commands.java)：阅读请求结构与嵌套校验。
4. [InputRules.java](../rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/service/InputRules.java)：类型和 Key 规则。
5. [ControlPlaneService.java](../rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/service/ControlPlaneService.java)：完整业务链、事务和审计。
6. [FlagEnvironmentConfigMapper.xml](../rolloutcore-server/src/main/resources/mapper/FlagEnvironmentConfigMapper.xml)：直接阅读条件 UPDATE。
7. [V1__init.sql](../rolloutcore-server/src/main/resources/db/migration/V1__init.sql)：六表、唯一约束和外键。
8. [ControlPlaneController.java](../rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/api/ControlPlaneController.java)：HTTP 如何进入 Service。
9. [ApiExceptionHandler.java](../rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/api/ApiExceptionHandler.java)：异常到稳定 ProblemDetail。
10. [TransactionBoundaryTest.java](../rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/service/TransactionBoundaryTest.java)：理解事务失败应如何验证。
