# RolloutCore Day2 交付报告

## 需求与实现范围

在 Day1 的 Maven multi-module、Spring Boot Control Plane、MyBatis/MySQL 和事务审计上实现确定性、可解释的 Evaluation Engine。
保留 Day1 的四种 Variant 类型、资源归属验证、重复 initial variantKey Fail Fast、Kill Switch、version 乐观锁和原有测试。
不做 Git commit，不修改 V1，不引入 Redis、Kafka、Docker、SDK、本地缓存或微服务。
先只读查看 README、架构/数据库文档、真实 Service、Mapper、Controller、事务测试及 Day1 E2E，再做增量扩展。

## A. 新增/修改文件

新增生产文件：

- `server/api/EvaluationController.java`：独立求值 HTTP 入口。
- `server/evaluation/EvaluationCommands.java`：求值/策略更新 DTO、响应和 reason。
- `server/evaluation/EvaluationContext.java`：内置属性与扩展字段解析。
- `server/evaluation/EvaluationPolicy.java`：策略、Rule、Condition、Allocation 数据模型。
- `server/evaluation/EvaluationService.java`：读取、优先级编排与 Variant 返回。
- `server/evaluation/RuleEngine.java`：类型安全 DSL 匹配。
- `server/evaluation/StableBucketService.java`：固定分桶算法。
- `server/evaluation/EvaluationPolicyValidator.java`：策略写入前校验。
- `src/main/resources/db/migration/V2__add_evaluation_policy.sql`。

这里 `server/` 指 `rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/`。
修改 domain 的 FlagEnvironmentConfig、ControlPlaneController、ControlPlaneService、FlagEnvironmentConfigMapper 接口及 XML。
新增 StableBucketServiceTest、RuleEngineTest、EvaluationPolicyValidatorTest、Day2ServiceTest、EvaluationControllerTest、Day2PersistenceTest；
在 TransactionBoundaryTest 和 ApplicationWiringTest 追加验证，原有测试未删除或削弱。
新增 `scripts/day2-e2e.ps1` 和本报告；更新 README、ARCHITECTURE、DATABASE。

## B. Day2 架构

EvaluationController → EvaluationService → 现有 ControlPlaneService scoped 读接口 → MyBatis → MySQL。
EvaluationService 调用 RuleEngine 和 StableBucketService，自己不实现操作符、Hash 或 SQL。
读取 Project/Environment/Flag/Config/Variants 使用同一个只读 REPEATABLE_READ 事务快照。
当前复用读接口会重复读取 Project/Flag，未加入缓存或复杂查询优化。
完整策略保存在一个 JSON 列，未来可作为配置快照缓存；JSON DTO 放在 server/evaluation，domain 继续不依赖 Jackson/Spring。

## C. Evaluation precedence

1. `enabled=false`：直接选择 defaultVariant，`DISABLED`；不执行规则或 Hash。
2. 已启用：按 priority 升序查找第一个匹配 Rule，返回 `RULE_MATCH` 和 matchedRulePriority。
3. 没有 Rule 命中且存在 rollout：按 bucket 落入累计权重区间选择，返回 `PERCENTAGE_ROLLOUT` 和 bucket。
4. 无规则命中且没有 rollout：defaultVariant，`DEFAULT`。

默认 Variant 也可为 true、字符串、数字、对象或数组，禁用不强制返回 false。
除对应 reason 的解释字段外，matchedRulePriority/bucket 为 null。所有响应携带读取快照的 configVersion。
不返回 SQL、内部实体 id、全部策略或用户上下文；求值不产生写入审计。

## D. Stable Hash 精确算法契约

输入原样连接（不 trim、不 lowercase、不做 Unicode normalization）：

```text
projectKey + U+0000 + environmentKey + U+0000 + flagKey + U+0000 + userId
```

以 UTF-8 编码，计算 JDK SHA-256。取 digest[0..3]，按 **big-endian** 解读为 unsigned 32-bit：
`u = b0×2^24 + b1×2^16 + b2×2^8 + b3`，每个字节取 0..255。
`bucket = u % 10000`，结果为 0..9999。ByteBuffer 默认大端，Integer.toUnsignedLong 避免符号问题。
userId 必填、非空白、最长 256 字符、禁止 NUL；项目/环境/Flag 沿用 Day1 Key 约束，因此分隔无歧义。
不包含 configVersion，不使用 Random、Object.hashCode 或进程状态。

固定向量由独立 .NET SHA256 计算，再写入 Java Golden Tests：

| project | environment | flag | userId | bucket |
| --- | --- | --- | --- | --- |
| shop | prod | pay | user-123 | 2750 |
| checkout-service | prod | new-payment-flow | user-123 | 4086 |
| shop | test | pay | 用户-一 | 48 |

固定样本 `shop/prod/pay/user-000001` 至 `user-050000`，bucket<1000 命中 **5061/50000 = 10.122%**，容差 9%–11%。
测试无随机样本，同时检查范围与重复输入；Service 测试重复求值并比较完整响应。
区间按 rollout 数组顺序累计，采用 `[lower, upper)`；0 权重不命中，10000 为 100%。
这是永久 v1 分桶契约。将来改变编码、分隔、字节序、Hash 或取模方式必须显式迁移，不能静默重分桶。
调整权重/数组顺序会改变部分 bucket 的 Variant，但同一标识的 bucket 不变。

## E. Rule Engine semantics

安全的结构化 JSON DSL，不执行表达式、脚本、反射路径或任意代码。
ALL=AND，ANY=OR；priority 非负且不重复，越小越先执行，first-match-wins，不依赖输入 Rule 数组顺序。

| Operator | 语义 |
| --- | --- |
| EQ / NEQ | 合法 JSON 标量同类型比较；数字 BigDecimal.compareTo，因此 5 与 5.0 相等 |
| IN / NOT_IN | rule value 为 1–100 个同类型标量数组；不做类型转换 |
| GT / GTE / LT / LTE | 仅数字比较，不对字符串排序 |
| CONTAINS | 字符串字面子串，大小写敏感 |

缺失属性和运行时类型不兼容统一不匹配，NEQ/NOT_IN 也返回 false，不产生 ClassCastException/500。
attributes 中显式 JSON null 可以 EQ null；缺失与 null 区分。内置字段 null 视为缺失。
userId/country/appVersion 为字符串，vipLevel 为数字；内置名称优先且保留，即使缺失也不会从 attributes 补值。
扩展属性键按字面匹配，允许点号但不遍历嵌套对象。对象/数组运行时值不参与标量比较。
appVersion 仅 EQ/NEQ/IN/NOT_IN 精确匹配，未实现 SemVer range。

写入限制：最多 100 Rule、每条 1–20 Condition、最多 100 Allocation；priority 和 rollout variantKey 不重复。
Condition attribute 为 `[a-zA-Z][a-zA-Z0-9_.-]{0,99}`，字符串 rule value 最长 1024；IN 数组 1–100 个同类型标量。
weight 为 0..10000 整数且总和恰好 10000；Rule/rollout 的 Variant 必须属于当前 Flag。
context country/appVersion 最长 256，attributes 最多 100 个 key。

## API

- `POST /api/v1/evaluate`：请求 projectKey/environmentKey/flagKey/context；无 X-Operator 要求。
- `PUT /api/v1/projects/{projectKey}/environments/{envKey}/flags/{flagKey}/evaluation-policy`：expectedVersion/rules/rollout，必填 X-Operator。
- policy 成功返回 `{"version":n,"policy":{"rules":...,"rollout":...}}`。
- evaluation 返回 flagKey/variantKey/value/reason/configVersion/matchedRulePriority/bucket。
- 400 validation_error；404 resource_not_found；409 optimistic_lock_conflict，沿用 ProblemDetail。
- rules 缺省/null/[] 表示无规则；rollout 缺省/null 表示无 rollout，[] 不合法。
- policy PUT 是完整替换，不做局部合并；仅传 expectedVersion 可清空策略，仍递增版本并审计。

完整请求示例见 README。GET Config 保持实体的 evaluationPolicyJson 字符串形式，policy PUT 响应和 Audit 中为解析后的 JSON 对象。

## F. V2 database migration

`V2__add_evaluation_policy.sql` 仅执行 `ALTER TABLE ff_flag_config ADD COLUMN evaluation_policy_json JSON NULL;`。
已部署 V1 未修改；原文 Git blob SHA-1：`67d5730a1f7b06c3a8065842d10b9f34e155a7fe`，测试保护其内容。
旧数据默认 SQL NULL，create Config 仍不要求 policy；Mapper SELECT 能读取该列并映射 evaluationPolicyJson。
普通配置 UPDATE 保留 policy；policy UPDATE 保留 enabled/defaultVariant。

## G. Policy optimistic lock

共用 Day1 checkVersion/persist：先校验当前读取版本，防止旧快照审计；再由 SQL `WHERE id=? AND version=?` 仲裁并发。
数据库原子执行 `version=version+1`，SQL affectedRows=0 抛 409。policy、enable/disable、普通更新共用同一 version。
已有普通 UPDATE SQL 契约保持不变；新增 policy UPDATE 只写策略、版本及更新时间。

## H. Audit

成功操作名为 EVALUATION_POLICY_UPDATED，before/after 包含 configVersion 与 evaluationPolicy，初始策略为 null。
与 policy UPDATE 在同一个 Spring 写事务；validation/stale/CAS 失败不写成功 Audit。
测试覆盖活动事务、提交顺序、审计异常回滚和条件更新冲突回滚。
mock JDBC 的 rollback 验证仅证明 Spring 事务边界，不等同于真实 MySQL 回滚实验。

## I–J. 自动测试与构建

`mvn -pl rolloutcore-server -am test`：BUILD SUCCESS，235 tests，0 failures / 0 errors / 0 skipped。
Day1 原有 129 项全部保留并通过，Day2 新增 106 项有效验证（包含参数化用例）。
`mvn clean verify`：BUILD SUCCESS，进程退出码 0，全部 7 个 reactor 项目成功；同样 235 tests，0 failures / 0 errors / 0 skipped。
生成 `rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar`。
构建仅保留既有 Mockito 动态 agent、deprecated API 和占位模块空 JAR 提示，无构建失败。
日志位于忽略目录 `workspace/day2-test.log`、`workspace/day2-clean-verify.log`。

| 测试类/增量 | Day2 新增用例数 |
| --- | ---: |
| StableBucketServiceTest | 5 |
| RuleEngineTest | 32 |
| EvaluationPolicyValidatorTest | 24 |
| Day2ServiceTest | 18 |
| EvaluationControllerTest | 20 |
| Day2PersistenceTest | 3 |
| TransactionBoundaryTest 追加 | 3 |
| ApplicationWiringTest 追加 | 1 |
| 合计 | 106 |
新增覆盖：3 个固定分桶向量、5 万固定用户分布、类型和缺失属性、ALL/ANY/priority、策略边界、
全部四类 Variant JSON 值、重复求值、分桶区间边界、共用版本与互相保留字段、审计/事务、HTTP 错误、真实 XML 映射及迁移契约。
默认测试不依赖外部 MySQL，不引入 H2 或 Docker；没有真实 DDL 执行的自动测试。

## K. Day2 E2E PowerShell

`scripts/day2-e2e.ps1` 默认生成唯一 ProjectKey，也接受显式参数；沿用 Day1 UTF-8 byte[] 响应解码。
已通过 Windows PowerShell 语法解析，无 destructive cleanup，不操作 Windows Service。
验证 health UP → Project/prod/BOOLEAN Flag/old-new Variants/Config → Rule+10% rollout policy → JP VIP 规则命中 →
普通用户重复 bucket/Variant 稳定 → 实际修改 priority 并 version+1 → stale 409 → 读取确认最新策略未被覆盖 →
两次成功 policy Audit 快照且 stale 不新增 → disable 强制 default → enable 恢复最新策略。
HTTP 仅少量重复请求，统计测试在 Java 内完成。

`REAL_MYSQL_DAY2_E2E=NOT_RUN`：当前环境没有提供 ROLLOUTCORE_DB_PASSWORD，不猜密码、不管理数据库服务。
Day1 已完成的真实 MySQL/Flyway/HTTP/409/rollback 验证仍是 Day1 历史结果，不冒充 Day2 结果。

## L–M. Git 验证

`git diff --check` 已通过，未运行 git commit 或 git add；所有变更留在工作区供审查。
Git 命令使用进程级 `-c safe.directory=E:/download/code/rollout-core` 适配沙箱账户，不修改用户全局 Git 配置。
`git status --short`：10 个已修改文件，17 个新增文件（默认将新目录折叠显示）；无删除、无暂存：

```text
 M README.md
 M docs/ARCHITECTURE.md
 M docs/DATABASE.md
 M rolloutcore-domain/src/main/java/io/github/lu1j/rolloutcore/domain/FlagEnvironmentConfig.java
 M rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/api/ControlPlaneController.java
 M rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/mapper/FlagEnvironmentConfigMapper.java
 M rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/service/ControlPlaneService.java
 M rolloutcore-server/src/main/resources/mapper/FlagEnvironmentConfigMapper.xml
 M rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/ApplicationWiringTest.java
 M rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/service/TransactionBoundaryTest.java
?? docs/DAY2_REPORT.md
?? rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/api/EvaluationController.java
?? rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/evaluation/
?? rolloutcore-server/src/main/resources/db/migration/V2__add_evaluation_policy.sql
?? rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/api/EvaluationControllerTest.java
?? rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/evaluation/
?? rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/mapper/Day2PersistenceTest.java
?? rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/service/Day2ServiceTest.java
?? scripts/day2-e2e.ps1
```

## N. Known Limitations / Day3 准备

- 当前 Evaluation 仍直接读 MySQL，每次请求有多次查询；Day3 将优化 Data Plane latency/cache。
- 无 Caffeine/Redis、本地缓存、Kafka 配置推送、SDK 本地求值。
- 无真正 SemVer range；appVersion 仅支持上述精确匹配安全操作。
- 当前是一个 server module 内的逻辑边界，未拆成微服务。
- 无嵌套规则组、正则、动态表达式、批量 evaluation 或配置推送协议。
- 无鉴权；X-Operator 仍是客户端声明的审计名。未新增资源归档/删除/Variant 修改 API。
- JSON 内 Variant 引用靠 Service 验证，不支持绕过 Service 直接写库；不保证数据库管理员手工损坏策略后的求值行为。
- 没有真实并发 MySQL 竞争/性能压测；重复读快照和事务代理测试不替代数据库环境验收。
- Day3 可复用完整策略快照、configVersion 和固定 Hash 契约；必须保留 Kill Switch 优先级及版本一致性。

## O. 用户亲自验证

使用原有安全方式提供 MySQL 环境变量，启动 clean verify 产出的新 server JAR，确认 Flyway V2 成功且 V1 checksum 未变化。
执行 `powershell -ExecutionPolicy Bypass -File scripts/day2-e2e.ps1`（可加 `-ProjectKey <新的唯一key>`）。
检查最终 REAL_MYSQL_DAY2_E2E=PASSED；根据需要在专用数据库补做 policy 审计失败的真实回滚和真实并发竞争实验。
脚本只新增数据，不清理既有数据，重复执行显式 ProjectKey 应换新值。
