# RolloutCore Day5 交付报告

后续状态补记：用户已实际确认 MySQL 8.0、Flyway V2/V3、
`REAL_MYSQL_DAY5_E2E=PASSED`、`REAL_DAY5_OUTAGE_E2E=PASSED`。
Day4 的真实 Kafka Producer/Listener 已后补，Windows 原生 Kafka 双 JVM 失效及 Broker 停机恢复手工实验已通过；
Docker E2E 未运行，恢复后最终 SENT 未经真实 SQL 直接查询，详见
[KAFKA_E2E_REPORT](KAFKA_E2E_REPORT.md)。以下保留首次 Day5 交付时的实现和验证记录，不将历史 NOT_RUN 当作最新用户验证结果。

## 实际结果

已完成业务应用接入链路：Java HTTP SDK、Spring Boot Starter、OpenFeature Provider 和独立 Demo。
没有 git commit，没有修改 server/domain 的生产代码、既有测试或数据库迁移。
起始 HEAD 为 `bccf3c49684625c68a054ffbc34cec91271cd5d7`，起始工作区无变更。
Git 因沙箱用户不同提示 ownership，所有查询使用命令级 safe.directory，未改全局 Git 配置。

2026-09-16 执行 `mvn -B -ntp clean verify`，结果 **BUILD SUCCESS**，7 个 reactor 项目全部成功。
Surefire XML 汇总：**381 tests / 0 failures / 0 errors / 0 skipped**，原有 301 项均通过。
`git diff --check` 通过。PowerShell Parser 检查 day5-e2e.ps1 无语法错误。
构建日志：`workspace/mvn-clean-verify.log`（被 Git 忽略）。

| 模块 | 测试数量 | 验证内容 |
| --- | ---: | --- |
| rolloutcore-server | 301 | Day1–Day4 现有测试原样保留 |
| rolloutcore-sdk | 45 | 各类型、协议、超时/连接、重试边界、default、LKG、TTL/容量/隔离、并发及本地 HTTP |
| rolloutcore-spring-boot-starter | 6 | properties、默认/显式启用、禁用、自定义 Bean、非法配置 |
| rolloutcore-openfeature-provider | 28 | 类型、数值边界、context、reason/variant/error/default、真实 OpenFeature Client |
| rolloutcore-demo-service | 1 | 一个完整集成场景，连续验证远端成功、热用户 LKG、冷用户 default、404 |
| 合计 | 381 | 新增 80 项 |

首次 SDK 测试发现测试夹具在 Mockito 未结束的 stubbing 内嵌套创建 mock，
已改为简单 HttpResponse 实现；最终全量结果如上，没有删除或跳过测试。
保留既有预期异常日志与 Mockito 动态 agent 提示，不把它们当作构建失败。

## 检查到的真实基线

根 reactor 为 domain、server、sdk、spring-boot-starter、openfeature-provider、demo-service，
加根 POM 共 7 个项目。四个接入模块原本都仅有 skeleton POM。
现有版本是 Java 21、Spring Boot 4.1.1、MyBatis 4.0.0、JUnit Jupiter 5.14.4；
没有升级或替换这些版本。

现有 Evaluation URL 是 `POST /api/v1/evaluate`，不是按项目拼接的 REST 子路径。
请求为 projectKey/environmentKey/flagKey/context，context 包含
userId/country/vipLevel/appVersion/attributes。响应为
flagKey/variantKey/value/reason/configVersion/matchedRulePriority/bucket。
value 是 JSON 节点，没有 valueType 字段。SDK 定义独立 transport record 并校验 JSON 形状，
不使用 MyBatis Domain 或 server 的 Commands/Service/Mapper。
现有 HTTP 协议无需改动，SDK 容忍额外响应字段。

## 模块关系与公共 API

```text
server → domain
sdk → Java HttpClient + Jackson 3
starter → sdk + Spring Boot autoconfigure
openfeature-provider → sdk + dev.openfeature:sdk:1.20.2
demo-service → starter + Spring Boot Web/Actuator
```

SDK runtime 不依赖 Spring；spring-boot-starter-test 仅用于测试。
Demo 可执行 JAR 包含 Starter/SDK，不包含 rolloutcore-server 或 rolloutcore-domain。

公共入口是 `io.github.lu1j.rolloutcore.sdk.RolloutCoreClient`：

```java
try (var client = RolloutCoreClient.create(ClientOptions.defaults("http://127.0.0.1:8080"))) {
    var context = new EvaluationContext("user-123");
    var result = client.booleanFlag("checkout-service", "prod", "new-payment-flow", context, false);
    // result.value(), source(), error(), errorMessage(), variant(), reason(), configVersion(), attempts()
}
```

同样提供 stringFlag（String）、numberFlag（BigDecimal）、jsonFlag（JsonNode 对象/数组）。
统一入口 evaluate 接受 ValueType 和同类型 JsonNode default。
错误类型 default 是调用编程错误，抛 IllegalArgumentException；
非法 key/userId 返回 INVALID_CONTEXT/default，不发送 HTTP。
健康/已处理故障通过 EvaluationResult 明确表示，不只返回一个无法解释的值。

| source | 含义 |
| --- | --- |
| REMOTE | 远端正常响应且类型匹配，error=NONE |
| LKG | 有效历史成功结果；error/message 保留触发故障，variant/reason/version 来自历史结果 |
| DEFAULT | 调用方默认值；error/message 表示具体失败，variant/version 为空 |

配置包含 baseUrl、connectTimeout、requestTimeout、maxRetries、retryDelay、lkgTtl、lkgCapacity。
默认 200ms 连接、500ms 每次请求、额外 1 次重试、25ms 固定间隔、LKG 30s/1000 条。
maxRetries 0–5；容量 0 可关闭 LKG，容量最大 100000；正超时/TTL 最大一天，retryDelay 0–10s。
baseUrl 支持路径前缀，不能包含凭据/query/fragment；不跟随重定向。

## 重试、默认值和 LKG 边界

- 连接/传输 IOException、timeout、502/503/504：有限重试后才考虑 LKG。
- 4xx（包括 429）、类型错误、协议错误、TLS/明确 HTTP 协议异常：不重试、不使用旧值。
- 普通 HTTP 500 的 internal_error 无法证明是瞬时基础设施失败，保守归为 PROTOCOL_ERROR/default。
  因此当前 server DB 故障若表现为普通 500，客户端不会用 LKG；连接断开或网关 502/503/504 才符合该策略。
- 中断停止重试、恢复线程中断标记，返回 INTERRUPTED/default。
- sendAsync + 有期限的 future.get 对完整响应体设截止时间，timeout/interruption 取消网络交换。
  测试既验证完全无响应，也验证发送响应头后卡住的 body。
- NUMBER 使用 BigDecimal 节点读取，测试包含高精度小数；不从 String/Boolean 猜测类型。

LKG key 为项目/环境/flag + 完整序列化 context + 期望类型；不会把其他用户或属性的值混用。
它仅为有限故障兜底，不复制 Day3 Snapshot，不在健康请求路径跳过远端。
单调时钟控制 TTL，fallback 不延长存活，超期访问时删除，同步 LRU 严格控制条目数。
容量限制不是字节预算；上下文字段顺序可能形成不同条目，但不会突破容量。

网络在缓存锁外执行；缓存操作/generation 在短临界区内完成。64 个固定 generation stripe 避免无界水位表。
观察到远端业务/协议失败会清除同一 flag 所有上下文 LKG；
在途旧成功不能在该失败之后回填，已有更高版本也不会被低版本回填覆盖。
JSON 输入输出防御性复制，测试验证并发读取/回退以及 404 与旧成功交错。

SDK 不订阅配置事件，所以无法知道断网时发生的 Kill Switch；LKG 不保证强一致，
最多返回 TTL 范围内的历史结果。业务可禁用 LKG。没有熔断、指数退避/jitter、重试预算共享、
批量/公开异步 API、SDK metrics 或持久化 LKG；不能声称已做生产容量压测。

## Starter 自动配置

```text
AutoConfiguration.imports
  → RolloutCoreAutoConfiguration
  → rolloutcore.sdk properties
  → enabled=true（默认）
  → ConditionalOnMissingBean(RolloutCoreClient)
  → RolloutCoreClient.create(options)
  → 业务构造器注入；容器关闭时 close
```

所有配置及示例见 README。enabled=false 不创建默认客户端。
用户 Bean 可以覆盖默认实现，未使用的非法默认客户端参数不会阻止自定义 Bean 启动。
配置绑定与启用/禁用/覆盖/非法参数均已测试，Demo 的完整 Boot 启动测试验证 imports 自动发现。
Demo 本身需要客户端；将其 SDK 禁用而不提供替代 Bean 会因缺少依赖启动失败，这是业务应用的明确依赖。

## OpenFeature 兼容性与职责

固定依赖 `dev.openfeature:sdk:1.20.2`，不是猜测一个版本：

1. 官方 [Java SDK 仓库](https://github.com/open-feature/java-sdk) 和
   [Java SDK 文档](https://openfeature.dev/docs/reference/sdks/server/java/) 提供 Java 要求和 Provider 接口。
2. 实际从 Maven Central 下载该版本 POM/JAR，检查 POM 的 compiler.source/release=11，
   用 javap 检查 FeatureProvider 的 class major version=55（Java 11），并核对五种 resolution 方法。
3. 发布 POM runtime 依赖仅 SLF4J；Lombok/SpotBugs 为 provided，
   Jackson 2、JUnit 6、CEL 等都是上游 test scope，不会把这些依赖传递进当前工程。
4. 在实际 Java 21.0.8、Spring Boot 4.1.1、Jackson 3.1.5 下编译运行 Provider 测试与全 reactor。
   没有为了适配改动项目的 Boot/JUnit/Java 版本。

RolloutCoreProvider 是标准适配层，不是新的 Evaluation Engine。
内部委托注入的 RolloutCoreClient，不重复网络/重试/fallback。
支持 boolean/string/integer/double/object/array。
integer 用 intValueExact 防止截断/溢出，double 超过有限范围报 TYPE_MISMATCH；
OpenFeature 的 double 和嵌套 JSON 数值遵循浮点精度限制。

targetingKey → userId（优先于同名属性）；country/vipLevel/appVersion 提取为内置字段，
其他属性进入 attributes。缺少 targetingKey 返回 TARGETING_KEY_MISSING，
内置字段类型不对或 Instant 属性返回 INVALID_CONTEXT；不隐式格式化时间。
object default 必须为对象/数组且可表示为 JSON；fallback 保留调用方原始 default。

| RolloutCore | OpenFeature |
| --- | --- |
| RULE_MATCH | TARGETING_MATCH |
| PERCENTAGE_ROLLOUT | SPLIT |
| DISABLED / DEFAULT | 同名 reason |
| 未知 reason | UNKNOWN |
| FLAG_NOT_FOUND / TYPE_MISMATCH / INVALID_CONTEXT | 对应 errorCode |
| PROTOCOL_ERROR | PARSE_ERROR |
| 未恢复的 timeout/connection/unavailable 等 | GENERAL + ERROR + caller default |
| 成功 LKG 恢复 | CACHED，历史 variant，原始故障存 metadata |

实际反编译并测试 OpenFeature Client，确认非空 errorCode 会将 Provider value 覆盖为 caller default。
因此 LKG 使用 CACHED 而不设置 errorCode，并保留 rolloutcore.error/errorMessage/source/reason/configVersion/attempts。
业务错误不会被吞成成功；未知编程异常也不会被 Provider catch-all 隐藏。
Provider 不持有注入客户端的生命周期，应用/Starter 负责 close。
OpenFeature 注册由业务明确调用，不在 Starter 内修改全局 singleton。

## Demo 与 E2E

DemoApplication 是独立可执行 Spring Boot 应用，默认端口 8081。
`GET /demo/payment?userId=...` → 注入 SDK → HTTP Evaluation API →
根据 new-payment-flow boolean 选择 new/old，caller default=false；
响应含 userId/flow/source/reason/variant/error 便于验证。

自动 DemoIntegrationTest 启动真实 Boot HTTP listener 和本地 JDK HttpServer fixture，
验证业务 HTTP → 自动配置 → SDK HTTP → fixture，并依次模拟 503/404。
这是同一测试 JVM 内两个 HTTP listener，不是真实 MySQL、两个独立 JVM 或真实 server E2E。

已生成 [scripts/day5-e2e.ps1](../scripts/day5-e2e.ps1)，支持：
正常 health 检查、创建项目/环境/boolean flag/定向规则、直接求值与 Demo 对比；
可选 -VerifyOutage 验证暖用户 LKG/冷用户 default。
脚本不停止进程、不修改 Windows 服务、不安装数据库、不清库、不猜密码。
默认新项目 day5-demo；重复运行需同步更换 Demo 与脚本 ProjectKey，否则创建冲突直接失败。

准确三终端启动步骤见 [README Day5](../README.md#独立-demo-与真实-e2e)：
A 配置实际 MySQL 凭据并启动 server JAR；
B 设置 DEMO_PROJECT_KEY/ROLLOUTCORE_SDK_BASE_URL 并启动 Demo JAR；
C 执行脚本。可选 outage 要先完成正常验证、手动停止 server 应用并保持 Demo 存活，
在所配置 LKG TTL 内调用 -VerifyOutage；为手动操作可给 Demo 显式设置 5m 测试 TTL。

本次没有提供实际数据库凭据，也未启动真实 server/MySQL：
**REAL_MYSQL_DAY5_E2E=NOT_RUN**，**REAL_DAY5_OUTAGE_E2E=NOT_RUN**。
没有重新验证 Day3 Redis、Day4 V3/SKIP LOCKED 的真实数据库行为。
Day4 LoggingProducer 仍只记录日志；没有真实 Kafka 跨进程传播，SENT 不等于其他进程已接收。

## Control Plane / Evaluation Plane

Control Plane 负责低频配置写、Audit、Outbox，正确性优先；
Evaluation Plane 负责 Evaluation API、Snapshot Cache、Rule Engine、Stable Rollout，低延迟和高 QPS 读优先。
二者在吞吐、故障和部署节奏上有自然边界，但本次没有为了微服务标签拆 server。

未来独立部署时，Evaluation controller/service/cache/rule/hash 和只读存储适配移入 Evaluation 服务；
配置写 controller/service、Audit、Outbox/Relay、写 mapper 留在 Control 服务。
共享策略/校验/事件协议先抽成稳定契约，再完成真实事件分发及启动恢复。
SDK 只需切换 baseUrl 到 Evaluation 服务地址。
今天配置 baseUrl 已足够，无 Nacos/Eureka/Spring Cloud/服务网格/RBAC/审批流。
详细边界和未来迁移约束见 [ARCHITECTURE](ARCHITECTURE.md#day4-与-day5写平面读平面及业务接入)。

## 文件清单

修改 6 个文件：

- README.md、docs/ARCHITECTURE.md。
- rolloutcore-sdk/pom.xml。
- rolloutcore-spring-boot-starter/pom.xml。
- rolloutcore-openfeature-provider/pom.xml。
- rolloutcore-demo-service/pom.xml。

新增 18 个文件：

- SDK main：ClientOptions.java、EvaluationContext.java、EvaluationResult.java、RolloutCoreClient.java、HttpRolloutCoreClient.java。
- SDK test：HttpRolloutCoreClientTest.java。
- Starter main：RolloutCoreProperties.java、RolloutCoreAutoConfiguration.java、
  resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports。
- Starter test：RolloutCoreAutoConfigurationTest.java。
- Provider main/test：RolloutCoreProvider.java、RolloutCoreProviderTest.java。
- Demo main：DemoApplication.java、PaymentController.java、resources/application.yml。
- Demo test：DemoIntegrationTest.java。
- scripts/day5-e2e.ps1、docs/DAY5_REPORT.md。

没有 git add/commit，最终状态应为 6 个 tracked 修改 + 18 个 untracked 新文件。
生成 JAR、Surefire XML 和下载依赖位于被忽略的 target/workspace。

## Day6 准备与已知限制

优先在真实 MySQL 环境执行 Day5 正常及停服脚本，验证凭据、Flyway 和独立 JVM 调用链。
随后按真实需求补 SDK metrics/重试退避、协议契约测试和响应体大小预算。
若要增强缓存变更传播，需要真实消息 Producer/Consumer 与多实例广播、版本兼容、恢复/重放测试；
不能把当前 LoggingProducer 视为该能力已经存在。
认证/授权、TLS 部署、完整压测、强一致 Kill Switch 和生产容错策略均未完成。
当前没有新增服务发现需求或高风险模块迁移理由。
