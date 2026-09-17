# Java SDK / Spring Boot / OpenFeature

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
它仅为有限故障兜底，不复制 Snapshot，不在健康请求路径跳过远端。
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

业务增加 `rolloutcore-openfeature-provider` 依赖后，使用共享客户端注册 Provider：

```java
var api = dev.openfeature.sdk.OpenFeatureAPI.getInstance();
api.setProviderAndWait("payment",
        new io.github.lu1j.rolloutcore.openfeature.RolloutCoreProvider(client, "sdk-demo", "prod"));
boolean enabled = api.getClient("payment").getBooleanValue("new-payment-flow", false,
        new dev.openfeature.sdk.ImmutableContext("sdk-new"));
```

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


## 独立 Demo

Demo 是独立可执行应用（8081），`GET /demo/payment?userId=sdk-new` 通过 SDK 调用中央求值 API。
调用方默认值为 false；响应中的 source/reason/variant/error 用于解释实际分支。
创建示例配置与验证见 [部署](DEPLOYMENT.md)。SDK 不订阅 Kafka，也不执行本地规则。
