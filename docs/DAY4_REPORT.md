# RolloutCore Day4 交付报告

## Kafka 后补（2026-09-16）

Day5 未提交工作保留；本轮在原 Outbox/Relay/版本失效语义上补上真实 Kafka Producer/Listener，
未改 Outbox 表、写事务和业务幂等算法。仅给低版本忽略分支补诊断日志。
新增 `spring-boot-starter-kafka`，实际解析 Spring Kafka 4.1.1 / kafka-clients 4.2.1（Boot 4.1.1 管理）。
消息复用数据库已有稳定 `event_id`，schemaVersion=1，occurredAt 来自 UTC created_at。
Relay 等 Kafka Broker ack 后才 mark SENT，DB 提交仍可能失败，因此是至少一次 + 消费者语义幂等，非 exactly-once。

每实例使用不同稳定 group：`rolloutcore-cache-{instance-id}`；Kafka 模式缺少 instance-id 启动失败。
低版本忽略；同版本继续清 L1/negative/LKG、推进 generation，可能多一次 DB reload，非完全 no-op。
version=0 创建仍能清负缓存。JSON/schema/key 校验在 Listener adapter，缓存算法仍在原 Consumer/Cache。
关闭 auto commit，RECORD 同步提交；本地失效成功才返回，默认重试两次后停止订阅，失败 offset 不提交。
无 DLT、无 processed_event 表。LoggingProducer 完整保留，由 transport=logging/kafka 明确切换。

已加入确定性测试和独立 Kafka KRaft Compose/双 JVM E2E 脚本。
后续 Windows 原生 Kafka 4.2.1 手工实验已通过 CLI smoke、
`REAL_KAFKA_CROSS_JVM_INVALIDATION=PASSED` 和 `REAL_KAFKA_OUTAGE_RECOVERY=PASSED`。
A/B 两个 JVM、两个独立 group 消费同一 v1 record；B 在约 89.5s（小于 L1 TTL 600s）返回新版本。
Broker DOWN 时 A 的配置 v2 PUT 仍成功，B 暂读 v1；A 有 retaining PENDING 日志，
Broker 恢复后 B 的 Relay 获得该事件 ACK、A/B 均消费并最终返回 v2，没有再次 PUT 或手工重发。
恢复后最终 SENT 未经 SQL 直接查询：mysql.exe 启动报 -1073741515 / 0xC0000135。
自动测试的 ACK→markSent 验证与真实消息传播证据分开记录。
Docker 仍未安装/运行，**Docker Compose E2E=NOT_RUN**；真实重复/乱序独立实验未执行，仍只有自动测试结果。
依赖证据、最终回归数量、运行步骤和限制见 [KAFKA_E2E_REPORT](KAFKA_E2E_REPORT.md)。
用户另已确认 MySQL 8.0、Flyway V2/V3、Day5 正常链与停服链通过；该结果不能代替 Kafka E2E。

以下内容是原 Day4 交付时的历史记录，“未接入 Kafka”等描述仅指当时状态。

## 实现范围

实现 MySQL Outbox、可替换 ConfigEventProducer、定时 Relay 和版本感知 Consumer 入口。
默认 LoggingProducer 只记录事件，SENT 表示日志发送成功，不表示其他 JVM 已接收。
未接入 Kafka 或其他跨进程传输；两个独立缓存的广播使用测试 Producer 验证。
未执行 Git commit，未连接或修改真实数据库，未运行 E2E 脚本。

## 本次文件清单

新增：

- domain：OutboxEvent.java、OutboxStatus.java。
- server/mapper：OutboxEventMapper.java。
- server/resources/mapper：OutboxEventMapper.xml。
- server/resources/db/migration：V3__add_outbox_event.sql。
- server/event：ConfigEventProducer.java、LoggingProducer.java、OutboxConfiguration.java、OutboxRelayService.java、ConfigChangedConsumer.java。
- 测试：Day4OutboxTest.java、Day4ConsumerTest.java、OutboxRelayServiceTest.java、OutboxMapperTest.java。
- 文档：本报告。

修改：

- ControlPlaneService.java：配置写入与审计之后，在原事务内插入 Outbox，然后发布原本地事件。
- SnapshotCache.java：抽取 invalidateForVersion，拒绝低于已知水位的事件。
- application.yml：Outbox 调度开关和间隔。
- ServiceFixture.java：提供 Outbox mapper 测试依赖。
- ApplicationWiringTest.java：外部数据库被 mock 时禁用 Outbox 调度。

工作区原先已有未提交 Day3 变更，不把它们算作本次新增。
本次不修改 EvaluationService、规则、Hash、缓存 TTL/容量、回源次序、single-flight 或 LKG 准入策略。
V1/V2 保留；不增加 Kafka 依赖，pom.xml 本次没有修改。

## 写事务

createConfig、updateConfig、setEnabled（启用/禁用）、updatePolicy 都写 Outbox。
createVariant 等不影响既有求值配置的操作沿用 Day3 行为，不机械生成配置变更事件。

```text
ControlPlaneService 写事务
  → 配置 SQL / 乐观锁 version
  → Audit
  → OutboxEvent(PENDING)
  → 发布本地 ConfigChanged
  → 提交
  → AFTER_COMMIT → invalidateForVersion
```

配置创建版本为 0，后续配置和策略共用 version 序列。
Outbox 的 event_id 为 UUID；payload 是 ConfigChanged 的 JSON：
key 包含 projectKey/environmentKey/flagKey，latestVersion 与行 version 一致。
审计、Outbox 插入或后续运行时异常使原事务回滚；事件不是在 AFTER_COMMIT 才落库。

## 表与 Mapper

outbox_event 包含用户指定的全部字段：id、event_id、event_type、project_key、
environment_key、flag_key、version、payload、status、created_at、updated_at。
使用 InnoDB、JSON payload、唯一 event_id、非负 version 约束、状态约束与 (status,id) 扫描索引。
状态枚举包含 PENDING/SENT/FAILED。

Mapper 提供 insert/findPending/markSent。
findPending 在 Relay 事务内执行 ORDER BY id LIMIT 20 FOR UPDATE SKIP LOCKED，
避免并行 Relay 正常执行时同时处理相同行。
markSent 仅更新仍为 PENDING 的行，并更新 updated_at。

## Relay 与 Producer

OutboxRelayService 使用 @Scheduled 和 @Transactional。
扫描已提交的 PENDING → producer.send → 返回成功后 markSent。
发送异常逐条记录，保留 PENDING，下轮重试；不会将可重试事件置为 FAILED 后遗忘。
FAILED 当前保留，未实现终止重试或人工重放接口。
markSent 失败抛出异常，让 Relay 批次事务回滚。

默认每秒扫描，启动延迟一秒，每批最多 20 条：

- ROLLOUTCORE_OUTBOX_ENABLED：默认 true。
- ROLLOUTCORE_OUTBOX_POLL_INTERVAL_MS：默认 1000。
- ROLLOUTCORE_OUTBOX_INITIAL_DELAY_MS：默认 1000。

LoggingProducer 是默认 bean；提供另一个 ConfigEventProducer bean 可替换它。
接入真实 Producer 时，send 必须等待发送确认，并设置有限的网络超时。
当前发送期间保持批次数据库行锁，适合小批量学习实现；慢网络会延长事务。
发送成功后、数据库提交前崩溃会导致重复发送，属于至少一次尝试模型。
没有消息重试上限、退避、清理任务、积压告警或严格业务版本发送排序。

## Consumer 与缓存

ConfigChangedConsumer.consume(ConfigChanged) 是传输无关的入口。
未来传输适配器需要验证消息类型、反序列化 payload 并调用它；
成功处理之后才能确认消息。
当前没有后台消费者连接真实消息系统。

```text
Outbox → Relay → ConfigEventProducer → LoggingProducer（当前终点）
                                   → 未来消息传输 → 每实例 Consumer
                                                     ↓
本地 AFTER_COMMIT ───────────────────────→ invalidateForVersion
                                                     ↓
                             generation / 水位推进 + L1/negative/LKG 清理
                                                     ↓
                             保留 Day3 Redis 版本失效与临时绕过
```

小于本地已知最低版本的事件被忽略，不清理更高版本缓存。
相同版本仍保守失效，重复处理不会恢复旧配置，但可能多一次回源。
不能简单忽略相等版本：普通加载也会更新水位，且版本 0 创建必须清理负缓存。
方法继续在同一 stripe 锁中执行，保持与并发加载发布的排序关系。
原 generation、Redis Lua 版本保护、短期绕过 L2 的安全措施保留。

版本水位仍采用 Day3 有界缓存，其淘汰/过期后不提供永久事件去重保证。
跨实例持久重放、消费者组广播、启动恢复属于未来传输适配器的工作；
多个本地缓存实例不能简单共用一个 Kafka consumer group 来期待每个实例收到每条事件。
本次不承诺跨 JVM 强一致或 Kill Switch 瞬间全局生效。

## 测试与验证

新增 14 项测试：

- Day4OutboxTest：5 项，全部配置写路径、事务提交顺序、审计失败、
  Outbox 失败、Outbox 插入后异常触发回滚。
- Day4ConsumerTest：5 项，失效委托、重复/旧版本、版本 0 负缓存清理、
  L1/LKG 清理、测试 Producer 广播到两个独立缓存。
- OutboxRelayServiceTest：3 项，发送与标记顺序、失败重试、标记失败传播。
- OutboxMapperTest：1 项，真实 MyBatis XML 解析与锁定/状态条件契约。

事务测试使用真实 Spring 事务代理和 DataSourceTransactionManager，JDBC Connection/Mapper 是 mock；
验证 commit/rollback 边界，不代表真实 MySQL 中已执行 SQL 或验证物理持久化回滚。
两个缓存对象的测试不是两个 JVM 的网络 E2E。
真实 MySQL V3、SKIP LOCKED 并发行为和真实消息传输均待环境集成验证。

最终验证记录：

- 首轮 mvn test：BUILD SUCCESS，300 tests / 0 failures / 0 errors / 0 skipped。
- 补充 Mapper 测试及调度隔离后，mvn clean verify：BUILD SUCCESS，
  301 tests / 0 failures / 0 errors / 0 skipped，7 个 reactor 项目全部成功。
- Surefire XML 汇总与构建日志一致。当前基线实际为 287 项，本次新增 14 项；
  Day3 文档中的 284 是历史记录，不覆盖工作区后续已有测试。
- git diff --check 通过。
- 日志：workspace/day4-test.log、workspace/day4-clean-verify.log。
- 保留原有测试中的预期异常日志、Mockito agent 和空骨架 JAR 提示。
