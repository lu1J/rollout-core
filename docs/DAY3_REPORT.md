# RolloutCore Day3 交付报告

## 范围与验证边界

先只读检查当前真实 EvaluationService、ControlPlaneService、Mapper、配置和 Day1/Day2 测试，再增量接入缓存。
未做 Git commit；保留原有 235 项测试及业务能力。没有修改 V1/V2，没有新增 V3，也没有修改 DATABASE.md。
用户已确认 Day2 的真实 MySQL V1/V2 与 HTTP E2E 通过；该历史结果不等同于本次 Day3 验证。

## A. 文件清单

新增生产代码位于 `rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/cache/`：

| 文件 | 职责 |
| --- | --- |
| CacheKey.java | 项目/环境/Flag 标识及 Redis key |
| EvaluationSnapshot.java | 不可变求值快照及 Variant JSON 值 |
| SnapshotRepository.java | DB miss 的 REPEATABLE_READ 读取事务 |
| SnapshotProvider.java | 内部 LoadResult/Source 抽象 |
| SnapshotCache.java | L1/L2/DB、negative、single-flight、LKG 和失效编排 |
| SnapshotCodec.java | 带 schemaVersion 的 Redis JSON 编解码及验证 |
| L2SnapshotStore.java | 可替换的 L2 接口 |
| RedisSnapshotStore.java | StringRedisTemplate/Lettuce 与原子版本脚本 |
| CacheProperties.java | 类型安全缓存参数及约束 |
| CacheMetrics.java | 固定名称、无资源标签的计数器 |
| ConfigChanged.java | key/latestVersion 事件 |
| CacheConfiguration.java | Spring 装配及 Redis 开关 |

修改 `EvaluationService.java`、`ControlPlaneService.java`、server `pom.xml`、`application.yml`。
测试新增 AfterCommitCacheTest、CachedEvaluationTest、RedisSnapshotStoreTest、SnapshotCacheTest、SnapshotConcurrencyTest、SnapshotModelTest、CacheFixture、ConfigChangeEventTest、SnapshotReadTransactionTest。
Day2ServiceTest 和 ServiceFixture 仅调整构造依赖与数据库事务代理目标，原有用例及断言能力保留。
新增 `scripts/day3-e2e.ps1`、本报告；更新 README、ARCHITECTURE。

## B–C. 缓存对象与架构

不按 userId 缓存最终 EvaluationResponse：这会随用户量扩张，而用户规则匹配和 Hash 可以复用同一个配置快照执行。
CacheKey 只包含 projectKey/environmentKey/flagKey，遵守 Day1 Key 规则，不允许冒号导致 Redis key 歧义。

EvaluationSnapshot 包含 key、configVersion、enabled、defaultVariantKey、已解析 EvaluationPolicy、immutable Map<String, VariantSnapshot>。
Variant value 使用递归不可变的 Map/List/String/Boolean/BigDecimal/null；最外层 value 仍不得为 null，与 Day1 一致。
policy 在构造和访问时深拷贝，避免可变 JsonNode/列表污染共享快照；Variant 的嵌套集合不可修改。
对外仍返回 Day2 JSON 类型和字段；重新构造响应 JsonNode 保留既有数值节点语义，不暴露 L1/L2 等内部调试字段。

```text
Evaluation Request
  → SnapshotCache.get(project, environment, flag)
  → L1 Caffeine
  → negative marker
  → 同 key single-flight
  → L2 Redis（可选）
  → SnapshotRepository / MySQL
  → 回填 L2、L1、LKG
  → Kill Switch → RuleEngine → StableBucketService → Default
  → Variant + reason + configVersion + matchedRulePriority / bucket
```

SnapshotRepository.load 才开启只读 REPEATABLE_READ，复用原 scoped Service/Mapper 查询，保持 Config/Policy/Variants 一致读取。
EvaluationService 外层不再开启数据库事务。测试验证首次 miss 获取并提交连接，重复 L1 hit 不调用 Mapper、不获取连接。

## D. L1 Caffeine

L1 每个 JVM 独立，命中无网络、无数据库事务，多实例之间不共享。
默认 expireAfterWrite=10s、maximumSize=10000、recordStats=true。Negative 和 LKG 也是有容量上限与 TTL 的 Caffeine store。
不是按 userId 缓存，不设置无界本地结果集。

## E. L2 Redis

使用 Spring Boot BOM 管理的 `spring-boot-starter-data-redis`、Lettuce 和 Caffeine，不额外覆盖依赖版本。
Redis key：`rolloutcore:eval:v1:{projectKey}:{environmentKey}:{flagKey}`。
单个 Redis Hash 包含十进制字符串 `version` 和 JSON `payload`；JSON 有 schemaVersion=1，并完整验证 key、版本、开关、默认 Variant 和策略。
不使用 Java native serialization。get 使用 HGET；写入/失效使用单 key Lua 原子操作，不使用 Redis distributed lock。

Redis get 出错继续 DB；put/invalidate 出错只记 metric/log，不让正常 DB 求值或已提交配置写入失败。
损坏 JSON、schema/key 不符或非法策略按缓存故障处理并回退 DB；尽力失效损坏 payload，版本栅栏不允许该动作删除已知更高版本。
Redis 默认为禁用；没有 Redis 的环境使用 L1 + DB。不会猜环境密码、安装或管理服务。
Redis health contributor 关闭，因为它是优化层；Actuator 默认仍只暴露 health，Redis 可用性通过缓存指标和日志观察。

## 默认参数

| 参数 | 默认值 | 环境变量 |
| --- | --- | --- |
| redis-enabled | false | ROLLOUTCORE_CACHE_REDIS_ENABLED |
| l1-ttl | 10s | ROLLOUTCORE_CACHE_L1_TTL |
| l1-maximum-size | 10000 | ROLLOUTCORE_CACHE_L1_MAXIMUM_SIZE |
| l2-ttl | 60s | ROLLOUTCORE_CACHE_L2_TTL |
| negative-ttl | 2s | ROLLOUTCORE_CACHE_NEGATIVE_TTL |
| lkg-ttl | 5m | ROLLOUTCORE_CACHE_LKG_TTL |
| lkg-maximum-size | 10000 | ROLLOUTCORE_CACHE_LKG_MAXIMUM_SIZE |
| host | localhost | ROLLOUTCORE_REDIS_HOST |
| port | 6379 | ROLLOUTCORE_REDIS_PORT |
| password | 空 | ROLLOUTCORE_REDIS_PASSWORD |
| connect-timeout | 200ms | ROLLOUTCORE_REDIS_CONNECT_TIMEOUT |
| command timeout | 200ms | ROLLOUTCORE_REDIS_TIMEOUT |

这些是开发默认值，不代表生产最优参数，也不宣称整个请求有严格 200ms 总耗时上限。
TTL 必须介于 1ms 和 1 天；negative TTL 小于两级 positive TTL，LKG TTL 大于 L1 TTL；容量必须为正。
negative 容量与 l1MaximumSize 共用；minimumVersion 水位容量同样为 l1MaximumSize，TTL=lkgTTL+l2TTL。

## F–G. Cache Aside 与 AFTER_COMMIT

READ：L1 hit 直接返回；miss 检查 negative，然后合并同 key 请求；L2 hit 解析验证并放入 L1/LKG；L2 miss/error/disabled 则一致读 DB。
DB success 尽力写 L2，再写 L1/LKG。仅缓存成功验证的快照。

WRITE：在 ControlPlaneService 原有写事务内完成业务 SQL、Audit，再 publish ConfigChanged(key, latestVersion)。
事件在事务内发布，缓存处理由 `@TransactionalEventListener(AFTER_COMMIT)` 延后至提交成功执行，不使用 fallbackExecution。
create Config、普通 update/defaultVariant、enable、disable、policy update 都发布事件；stale 和 Audit 失败不发布成功候选事件。
外层事务即使在事件发布后回滚，也不执行缓存失效；有真实 Spring 事件监听器和事务管理器测试验证。

本实例失效动作：更新 generation/version 水位，清除 L1、negative、LKG，再尽力失效 Redis payload 并保存 latestVersion tombstone。
新增 Variant 不机械失效：现有策略不能引用尚不存在的 Variant；新增一个未引用值不影响结果。
后续将它设为 default 或写入 policy 时，已有 Config 写路径会消费 version 并失效。

## H. Version-aware stale-fill protection

本地使用 256 个固定 stripe，每个保存 generation 和一个临时 L2 绕过截止时间。相同 key 始终进入相同 stripe。
加载开始捕获 generation，DB IO 在锁外进行；发布结果前在同一 stripe 锁内检查 generation 和 minimumVersion。
AFTER_COMMIT 增加 generation，并将 latestVersion 写入有界水位；成功观察的快照版本也更新水位。
旧 generation 或更低 version 的 snapshot 不能回填 L1/LKG/Redis，丢弃并重试，最多 3 次；持续竞争明确失败，不无限循环。
缓存发布、future 完成和 flight 移除在同一锁内，避免已提交失效后新请求加入一个尚未清理的旧成功结果。

竞态测试：A 读到 v5 后暂停；写入 v6 提交并失效；A 恢复时拒绝 v5，重新加载 v6；最终 L1/L2 均为 v6。
另验证创建 Config 与旧 Not Found 加载竞争时，不允许旧 negative marker 重新进入缓存。

Redis 原子脚本按十进制版本字符串长度/字典序比较，不用 Lua tonumber，避免 64-bit version 被浮点数舍入。
旧版本不能覆盖更高版本 payload/tombstone；乱序旧失效不能清除新版本。
fill 的 Redis TTL 从本次加载开始计算剩余时间，而非慢加载完成后重新给足 TTL；加载超过 l2TTL 不再写 L2。

为避免水位容量淘汰或 Redis 失效失败造成旧共享值重新进入本 JVM，每次提交让该 stripe 在一个 l2TTL 内绕过 L2 读取。
旧负载的存活期限不会因迟到 fill 被重新延长；generation 在固定 stripe 中不被按 key 淘汰。
这是保守的有限内存方案：同 stripe 的其他 key miss 可能额外访问 DB，L1 已命中项不受影响；Redis 写入仍允许回填新快照。
不是跨 JVM 强一致协议；其他实例没有接到本地事件时，仍可能在缓存 TTL 窗口内返回旧配置。

## I. Negative cache

仅缓存 `404 resource_not_found`，默认 2s，maximumSize=l1MaximumSize。
Redis timeout、DB infrastructure failure、业务校验和数据错误均不写 negative。
真实 Not Found 同时清除旧 LKG，不能用旧配置伪装资源仍存在。
对应 Config create 的 AFTER_COMMIT 会立即清理本实例 negative；其他实例等待自己的短 negative TTL。
内部 Source=NEGATIVE 转成既有 HTTP 404，无新公共字段。

## J. Single-flight

ConcurrentHashMap<CacheKey, CompletableFuture<LoadResult>> 仅保存正在执行的加载；成功、RuntimeException、Error 都清理条目。
同 key miss 只有 leader 读 L2/DB，其他请求共享结果；不同 key 可以并行，局部 stripe 锁只覆盖缓存发布/失效和短 timeout 的 Redis 写操作。
20 并发测试验证 DB 恰好调用 1 次，全部拿到同一快照；10 并发故障测试验证全部 waiter 退出，下一次请求可重新成功加载。
仅单 JVM，不实现跨 JVM 请求合并或分布式锁。活跃 flight 数由同时执行的不同 key 请求数决定，完成后不保留。

## K. LKG 与 Kill Switch safety

DB 或 L2 成功读取并验证后建立本 JVM bounded LKG，默认 5m/10000。
只有 DataAccessResourceFailureException、TransientDataAccessException、CannotCreateTransactionException 等基础设施异常允许 LKG。
正常顺序仍为 L1 → L2 → DB；LKG 不抢占正常 DB 读取，不替代真正 Not Found，也不掩盖数据完整性错误。
fallback 不刷新 LKG TTL，不回填 L1；每次使用有计数与固定内容日志。

本实例收到 Config/Policy/Kill Switch 提交事件后清除旧 LKG；DB outage 时宁可该 key 失败，也不回退旧 enabled=true。
重新成功加载最新 disabled 快照后，才可建立新的安全 LKG。
其他 JVM 未收到事件时，不能保证其 LKG 已知道 Kill Switch 变化；LKG 是限时可用性兜底，不是强一致。

## L. Metrics

以下计数器名为 `rolloutcore.cache.<name>`，无 userId/projectKey/flagKey 等高基数 tag：

`l1_hit`、`l1_miss`、`l2_hit`、`l2_miss`、`l2_error`、`db_load`、`negative_hit`、`singleflight_join`、`lkg_fallback`、`cache_invalidation`、`stale_fill_rejected`。

Caffeine recordStats 通过 Micrometer binder 提供 cache.size/命中/加载/淘汰等基础指标，cache 标签只有 evaluation_l1/evaluation_lkg/evaluation_negative 三个固定值。
内部 LoadResult 区分 L1/L2/DB/LKG/NEGATIVE；HTTP EvaluationResponse 保持 Day2 合约。
未增加 Prometheus exporter，默认不开放 metrics HTTP endpoint；需要时可由部署者显式配置 Actuator exposure。

## M–N. 自动测试与构建

`mvn -pl rolloutcore-server -am test`：BUILD SUCCESS，退出码 0，284 tests / 0 failures / 0 errors / 0 skipped。
Day1/Day2 235 项保留，Day3 新增 49 项有效用例（包括参数化用例）。
新增测试覆盖 L1/L2/expiry/容量、Redis 失败和坏 JSON、负缓存、并发合并、版本竞态、事件提交/回滚、LKG 和 metrics。

| 新增测试类 | 用例数 |
| --- | ---: |
| AfterCommitCacheTest | 2 |
| CachedEvaluationTest | 5 |
| RedisSnapshotStoreTest | 4 |
| SnapshotCacheTest | 20 |
| SnapshotConcurrencyTest | 4 |
| SnapshotModelTest | 9 |
| ConfigChangeEventTest | 4 |
| SnapshotReadTransactionTest | 1 |
| 合计 | 49 |

`mvn clean verify`：BUILD SUCCESS，退出码 0，全部 7 个 reactor 项目成功；再次通过 284 tests / 0 failures / 0 errors / 0 skipped。
测试日志：`workspace/day3-test.log`；完整构建日志：`workspace/day3-clean-verify.log`；可运行 JAR：`rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar`。
保留既有 Mockito 动态 agent/deprecated API/占位模块空 JAR 提示，无构建失败。
SnapshotReadTransactionTest 证明 hit 不获取 JDBC Connection；原 Day2 事务测试的 proxy 移到 SnapshotRepository，仍验证相同的 REPEATABLE_READ、提交和关闭行为。
RedisSnapshotStoreTest 检查真实 adapter 的 key/版本/TTL 绑定及 Lua 契约，并使用 fake store 验证版本行为。
fake/mock Redis 测试不代表 Lua 已在真实 Redis 执行，也不代表已完成 Redis/MySQL 集成或生产级 benchmark。

## O–P. Day3 E2E 与真实 Redis 状态

- `scripts/day3-e2e.ps1` 已生成并通过 Windows PowerShell 语法解析，复用 Day2 Windows PowerShell 5.1 UTF-8 byte[] 解码，使用唯一 ProjectKey，无 destructive cleanup。
- 覆盖 Day2 Rule Match、稳定 bucket/Variant、stale 409、Audit；进一步验证已预热缓存后的 policy/config/enable/disable 立即生效，以及创建 Config 清除 negative。
- 默认无 Redis 模式；脚本仍可验收真实 MySQL + L1，并明确输出 REAL_REDIS_DAY3_E2E=NOT_RUN。
- `-RedisEnabled` 需要显式 RedisCliPath/RedisHost/RedisPort，且服务端另行启用 Redis；读取本次运行 exact key 的 JSON、版本和 PTTL，才报告真实 Redis smoke 通过。
- 密码可由调用者通过 REDISCLI_AUTH 安全提供；不放进命令行、不猜密码。
- Redis smoke 的 payload/version/TTL 观察证明实际共享缓存写入，不能据此断言每一次请求究竟来自 L1/L2/DB；Source 在自动测试内验证。

本次执行环境未提供数据库密码或明确 Redis 环境：

```text
REAL_MYSQL_DAY3_E2E=NOT_RUN
REAL_REDIS_DAY3_E2E=NOT_RUN
```

用户可启动连接既有 MySQL 的新 server JAR，再执行脚本；无需额外 Flyway Migration。
未安装/重启任何系统服务，未执行数据库清理或破坏性 SQL。

## Q–R. Git

`git diff --check` 通过。工作区 8 个修改文件、23 个新增文件，无删除、无暂存、无 commit；Git 使用进程级 safe.directory 参数，不修改全局配置。
V1/V2 和 DATABASE.md 保持原样，没有 V3。

`git status --short`（新目录折叠）：

```text
 M README.md
 M docs/ARCHITECTURE.md
 M rolloutcore-server/pom.xml
 M rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/evaluation/EvaluationService.java
 M rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/service/ControlPlaneService.java
 M rolloutcore-server/src/main/resources/application.yml
 M rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/service/Day2ServiceTest.java
 M rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/service/ServiceFixture.java
?? docs/DAY3_REPORT.md
?? rolloutcore-server/src/main/java/io/github/lu1j/rolloutcore/server/cache/
?? rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/cache/
?? rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/service/ConfigChangeEventTest.java
?? rolloutcore-server/src/test/java/io/github/lu1j/rolloutcore/server/service/SnapshotReadTransactionTest.java
?? scripts/day3-e2e.ps1
```

## S. Known Limitations

- 无 Kafka；多实例 L1 只能依靠 TTL/本实例 after-commit，不能立即互相通知；Redis 失效失败也有 TTL 陈旧窗口。
- 单 JVM single-flight；未实现跨 JVM distributed lock 或合并。
- LKG 有界、有期限，但不是强一致；未知的跨实例更新可能使 LKG 返回较旧状态。
- Redis 真实集成未验证；Lua 真实执行及 Redis/MySQL 故障切换需真实环境验收。
- 没有 SDK 本地求值、OpenFeature、Kafka、Outbox、Docker/Kubernetes 或微服务拆分。
- 没有完整生产压测、容量调优或吞吐量/SLA 承诺；默认 TTL/容量只作起点。
- stripe L2 绕过是保守安全策略，会使共享该 stripe 的其他 miss 暂时增加 DB 读取；Redis 写/失效在该 stripe 锁内，受网络超时影响。
- 进程在 DB commit 后、事件处理前崩溃，Redis 仍可能残留旧值至 TTL；没有 Outbox 的可靠投递保证。
- 仍保留 Day2 的 appVersion 精确匹配限制、无鉴权和不支持直接手工写库损坏策略的边界。

## T. Day4 准备

ConfigChanged 已携带稳定 CacheKey/latestVersion；Day4 可以把配置变化发布到 Kafka，由各实例在消费后调用失效逻辑，主动清理跨实例 L1/negative/LKG。
可复用 minimumVersion、generation 和 Redis 版本比较应对乱序事件；仍需设计投递、重复消费和故障恢复边界。
本次只建立本地事件与缓存基础，不提前实现 Kafka/Outbox。

## 参考

依赖与用法参照 [Caffeine 官方 Population](https://github.com/ben-manes/caffeine/wiki/Population)、[Spring Data Redis 驱动说明](https://docs.spring.io/spring-data/redis/reference/redis/drivers.html)、[Spring Boot 配置属性](https://docs.spring.io/spring-boot/redirect.html?page=application-properties)。实际实现与版本以当前仓库源码及 Boot BOM 为准。
