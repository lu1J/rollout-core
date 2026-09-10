# RolloutCore

RolloutCore 是一个学习型 Feature Flag 与渐进式发布平台。Day1 实现 Control Plane：管理项目、环境、Flag 定义、类型化 Variant、环境配置和审计。当前是模块化单体，尚未实现请求求值、灰度分流或客户端分发。

## Day1 能力

- Project / Environment / FeatureFlag / FlagVariant / FlagEnvironmentConfig / AuditLog。
- BOOLEAN、STRING、NUMBER、JSON 类型校验；JSON 只接受对象或数组。
- MySQL 持久化、Flyway V1、显式 MyBatis Mapper + XML。
- Service 事务、数据库唯一约束、配置 version 乐观锁、环境级 enable/disable。
- Jakarta Validation、ProblemDetail 错误响应、必填 X-Operator。
- 仅开放 Actuator health。
- 默认测试不依赖外部 MySQL，不使用 H2 或 Docker。

Control Plane 负责配置写入和管理；Data Plane 将来负责应用请求中的 Flag 求值。Day1 的 Kill Switch 只修改数据库中对应环境配置的 enabled 状态，不代表已有 SDK 能立即感知变化，也不定义禁用时的求值返回值。

## 模块

| 模块 | 当前内容 |
| --- | --- |
| rolloutcore-domain | 六个领域对象、FlagValueType、ResourceStatus；不依赖 server 或 Spring |
| rolloutcore-server | Spring Boot HTTP API、Service、MyBatis、Flyway、测试 |
| rolloutcore-sdk | 可构建空骨架 |
| rolloutcore-spring-boot-starter | 可构建空骨架 |
| rolloutcore-openfeature-provider | 可构建空骨架 |
| rolloutcore-demo-service | 可构建空骨架 |

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

`.mvn/maven.config` 将本地依赖缓存放在被忽略的 `workspace/maven-repository`，并使用仓库内的空 settings，从 Maven Central HTTPS 下载，不读取或更改用户 Maven 配置。首次构建需要联网，后续可以使用缓存。四个占位模块产生空 JAR 警告属于预期。

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

## Known Limitations / Day2 准备

- 没有鉴权、租户权限、归档 API、删除 API、Variant 修改 API。
- status 已建模，Day1 只创建 ACTIVE；尚无资源生命周期工作流。
- Kill Switch 仅完成 Control Plane 持久化，未实现 Data Plane 求值或传播延迟保证。
- 没有 Redis、Caffeine、Kafka、Outbox、规则引擎、灰度 Hash、OpenFeature、Docker、Prometheus 或压测。
- 审计与业务共享数据库事务，没有外部投递或不可篡改保证；分页使用 offset。
- 项目与环境的归属由 Service 和 scoped 查询保障；数据库外键保证资源存在，Variant 归属另有复合外键。绕过 Service 直接写库不属于支持的写入方式。
- 默认自动测试不依赖 MySQL；真实 V1、HTTP 持久化与回滚已另行验证，实际并发竞争和 JSON/外键完整边界未穷尽验证。
- domain / server 边界、独立配置、version 和预留模块可供 Day2 使用；没有提前实现未来功能。

进一步阅读：[架构](docs/ARCHITECTURE.md)、[数据库](docs/DATABASE.md)、[Day1 交付报告](docs/DAY1_REPORT.md)。
