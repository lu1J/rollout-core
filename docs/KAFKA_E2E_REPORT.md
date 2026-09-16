# Kafka 后补实现与验证报告

## 结论与证据边界

已补齐真实 Kafka Producer/Consumer adapter、稳定协议、每实例订阅、ack/offset 配置与有限消费处理重试。
保留全部 Day5 未提交 SDK/Starter/Provider/Demo/脚本；没有 commit/push/reset/restore/checkout。
HEAD 仍为 `bccf3c49684625c68a054ffbc34cec91271cd5d7`。

**自动回归通过：437 tests / 0 failures / 0 errors / 0 skipped。**
2026-09-16 `mvn -B -ntp clean verify` BUILD SUCCESS，7 个 reactor 项目成功，未遇到 JAR 文件锁。
`git diff --check` 通过；PowerShell Parser 检查 kafka-e2e.ps1 通过。
构建日志 `workspace/kafka-clean-verify.log`，依赖日志 `workspace/kafka-dependencies.log`。
日志、target 与下载依赖位于被 Git 忽略的目录。

2026-09-16 用户已完成 **Windows Native Kafka 4.2.1 Manual E2E — PASSED**：
真实 Broker smoke、双 JVM 主动失效及 Broker outage/recovery 均已验证。
本轮只读核对原始应用日志并更新文档，没有重新运行实验或修改生产/测试代码；
437 项是此前自动回归结果，本轮不重复运行。
Docker 仍未安装/运行，**Docker Compose E2E — NOT_RUN**；不能把原生手工结果移记到 Docker 脚本。
恢复后 outbox_event.status=SENT 未经真实 SQL 直接查询，详见下方证据边界。

用户已另外真实确认 MySQL 8.0、Flyway V2/V3、
REAL_MYSQL_DAY5_E2E=PASSED、REAL_DAY5_OUTAGE_E2E=PASSED；
这部分已更新 README/Day5 状态补记，但不作为 Kafka 验证证据。

## Windows Native Kafka 4.2.1 Manual E2E — PASSED

### 证据来源与状态矩阵

保留原始目录 `workspace/kafka-manual-e2e-20260916-202127/`，未删除或改写日志。
本轮核对 [instance-a-retry.log](../workspace/kafka-manual-e2e-20260916-202127/instance-a-retry.log)
及 [instance-b-retry.log](../workspace/kafka-manual-e2e-20260916-202127/instance-b-retry.log)。
下文行号均针对这些 retry 日志；最初的 instance-a.log/instance-b.log 不是本次成功运行的证据。
PID/端口/group/订阅/partition/cluster ID/eventId/ACK/超时由日志直接核对；
Broker 配置、CLI smoke、health/HTTP 响应、端口停启观测、TTL 参数、89.5 秒耗时和 mysql.exe 错误
来自用户本轮提供的真实手工实验记录，未声称它们全部存在于应用日志。

| 验证项 | 状态 | 证据范围 |
| --- | --- | --- |
| automated tests | PASSED，437 项 | 此前 Maven/Surefire，不等同于真实 Broker |
| real Kafka broker CLI smoke | PASSED | 用户确认原生 CLI Producer/Consumer 实测 |
| REAL_KAFKA_CROSS_JVM_INVALIDATION | PASSED | 两 JVM 日志 + 用户 HTTP/TTL/耗时观测 |
| REAL_KAFKA_OUTAGE_RECOVERY | PASSED | 超时/PENDING/ACK/两 group 消费日志 + 用户停启/HTTP 观测 |
| recovery 后真实 SQL 直接查询 SENT | NOT_VERIFIED | mysql.exe 无法启动，没有最终 SQL 查询结果 |
| Docker Compose E2E | NOT_RUN | 未安装/未运行 Docker；脚本仍是独立待验证路径 |
| 真实 Broker duplicate / out-of-order 独立实验 | NOT_RUN | 本次未提供这两项手工实验，仍只有自动测试通过 |

### 真实环境与 Windows workaround

- Apache Kafka Broker 4.2.1，Windows 本地原生运行，Java 21（应用日志为 21.0.8）。
- KRaft 单节点，cluster.id=`i3ZTpFF0T0iCxRF54XQiPw`。
- Broker listener `127.0.0.1:9092`；Controller listener `9093`。
- Topic `rolloutcore.config-events`，partitions=3，replication-factor=1。
- CLI Producer/Consumer smoke 已真实通过；单节点不代表多副本容错验证。

本机 Kafka 4.2.1 Windows .bat 展开 classpath 后出现“输入行太长 / 命令语法不正确”。
实际绕过方式是在 Kafka 解压目录直接由 Java 使用 classpath wildcard：

```powershell
# 下面展示入口调用形式；参数使用本次实际配置，不重新格式化已有集群。
java -cp ".\libs\*" kafka.tools.StorageTool --help
java -cp ".\libs\*" kafka.Kafka ".\config\server.properties"
java -cp ".\libs\*" org.apache.kafka.tools.TopicCommand --bootstrap-server 127.0.0.1:9092 --describe --topic rolloutcore.config-events
```

这是本地 Windows 启动 workaround，不是 RolloutCore/Kafka 的架构能力；
Broker 恢复使用原集群与原数据，不能重新 format 或生成新 cluster.id 来替代恢复实验。

### 两个 JVM 与独立订阅

| 实例 | PID（实验时） | 端口 | instance-id | group.id | 分配 partition |
| --- | ---: | ---: | --- | --- | --- |
| A | 26784 | 8080 | instance-a | rolloutcore-cache-instance-a | 0、1、2 |
| B | 4784 | 8082 | instance-b | rolloutcore-cache-instance-b | 0、1、2 |

用户确认两边 health 均 UP。A/B 日志均在第 11 行记录 PID/Java，第 25 行记录端口，
第 44 行记录各自 group.id，第 148 行订阅同一 topic，第 150 行记录同一 cluster ID，
第 159 行确认各自取得三个 partition。
这是真实 per-instance Consumer Group：相同 group 是负载均衡，不同 group 才能让各独立 L1 获得完整消息流。

### 跨 JVM 主动失效：v0 → v1

Project：`kafka-manual-07cebbd63d04432b98fe665b9aba5cdd`，environment=prod，flag=pay。
用户记录 B warm：configVersion=0、variant=new、value=true、reason=DEFAULT；启动 L1 TTL=10m。
经 A 修改为 v1、enabled=false、defaultVariant=old 后：

- eventId=`098ece2d-3723-4d0d-940c-04cc974cde2c`，configVersion=1，partition=0，offset=1。
- A 第 294 行、B 第 293 行在 `20:40:28.637+08:00` 分别记录各自 group 消费同一事件。
- B 第 294 行在 `20:40:28.640+08:00` 记录该事件的 Producer ACK。
- 用户随后 B evaluate：configVersion=1、variant=old、value=false、reason=DISABLED。
- 用户记录 warm→fresh 约 89.5 秒，**89.5s < 600s L1 TTL**。

两 JVM 的相同 Kafka record 消费日志结合 TTL 内的 HTTP 变化，证明本次通过
Kafka ConfigChanged → ConfigChangedConsumer → SnapshotCache.invalidateForVersion
主动清除 B 的旧 L1，随后读取新版本；不是等 TTL 自然过期。
因此记录 `REAL_KAFKA_CROSS_JVM_INVALIDATION=PASSED`。
89.5 秒是人工操作全过程间隔，不是 Kafka 传播延迟 benchmark。

### Broker outage / recovery：v1 → v2

用户人工停止 Broker 后确认 9092 不再 LISTEN，A/B health 仍 UP。
Kafka DOWN 时通过 A PUT 将配置更新到 v2、enabled=true、defaultVariant=new，HTTP 成功返回 version=2。
立即请求 B 仍得到 configVersion=1、value=false、reason=DISABLED：
MySQL 权威配置已推进，但 B 旧 L1 尚未收到事件，真实展示最终一致性窗口。

v2 eventId=`f61d304b-e24e-42e6-813c-7f547f44440c`：

| 日志位置 | 时间（+08:00） | 直接观测 |
| --- | --- | --- |
| A 第 310 行 | 20:45:10.231 | Producer 无法连接 127.0.0.1:9092 |
| A 第 1637–1641 行 | 20:47:42.241–42.255 | v2 send 异常、TimeoutException、retaining PENDING，同一 eventId |
| B 第 3258 行 | 20:50:24.247 | v2 Broker ACK，partition=0、offset=2 |
| A 第 3778 行 | 20:50:24.267 | group A 消费该 v2 record |
| B 第 3259 行 | 20:50:24.267 | group B 消费同一 v2 record |

用户确认原集群 Broker 重启后 9092 恢复 LISTEN，未再次 PUT，也未手工重发业务事件。
恢复 ACK 位于 **B 的 Relay** 日志；A/B 共享 Outbox 并通过 SKIP LOCKED 领取行，
不应误写成“A 的失败事件必须由 A 重发”。
最终用户 B evaluate：configVersion=2、variant=new、value=true、reason=DEFAULT。

实际链路为：Broker outage → 配置事务提交成功 → publish 失败/PENDING 保留 →
B 暂时读取旧 L1 → 原 Broker 恢复 → Relay 自动重试 → 两 group 消费 → B 失效并收敛到 v2。
记录 `REAL_KAFKA_OUTAGE_RECOVERY=PASSED`。
Kafka 故障没有使该配置 PUT 回滚；日志中的 Spring Kafka `LoggingProducerListener`
是发送异常记录器，不是项目的开发占位 `LoggingProducer`。

### 最终 SENT 的严格边界

本次未通过 SQL CLI 直接查询恢复后的 `outbox_event.status=SENT`。
用户找到的 `E:\download\JAVA\mysql-8.0.34-winx64\bin\mysql.exe` 启动失败：
exit code `-1073741515 / 0xC0000135`，属于 Windows DLL/runtime 客户端环境问题，
不表示本次 Java 应用连接 MySQL 或配置事务失败。本轮未安装/修复该客户端。

自动测试已证明 ACK success → markSent、send failure → 不 markSent/保持待重试；
真实手工实验已证明同一 PENDING event 在 Broker 恢复后自动获得 ACK 并传播到两实例。
**Producer ACK 和消费成功日志不是 MySQL SENT 提交的直接 SQL 证据**。
所以不能写成“真实 SQL 已确认最终 SENT”，也不能把它扩大为 DB+Kafka exactly-once。

## 只读检查到的真实源码（实现阶段记录）

开始时 git status 是已有 Day5 的 6 个 tracked 修改及接入模块等 untracked 文件。
git diff --stat 为 6 files / 210 insertions / 14 deletions（不含 untracked）。
检查了 server POM/application、事件接口/实现、CacheKey/ConfigChanged、
SnapshotCache、Outbox Domain/Mapper/XML/V3 和 Day4 测试，未从文档推断已有 API。

OutboxEvent 实际字段：

| 字段 | 来源/含义 |
| --- | --- |
| id | MySQL BIGINT 自增主键，Relay markSent 使用 |
| eventId | 唯一 VARCHAR(36)，配置写事务创建一次 UUID |
| eventType | 当前 CONFIG_CHANGED |
| projectKey / environmentKey / flagKey | 事件所属配置 |
| version | 非负配置版本 |
| payload | 原 ConfigChanged JSON，保留原 schema |
| status | PENDING / SENT / FAILED（FAILED 仍保留） |
| createdAt / updatedAt | UTC LocalDateTime，落 DATETIME(6) |

无需新建表/迁移/processed_event，Kafka eventId 直接使用已有稳定唯一 event_id。
V3 没有修改。Kafka wire DTO 从可靠 Outbox 列构造，不把数据库实体整体序列化，也不修改原 payload。

原 relay 使用事务内 LIMIT 20 / ORDER BY id / FOR UPDATE SKIP LOCKED。
producer.send 返回后才执行 markSent，返回行数不为 1 抛异常回滚；SENT 随事务提交才持久化。
send 抛 RuntimeException 则不 markSent，保留 PENDING 下轮重试。
原配置写/Audit/Outbox 事务链保持原样，没有把 Kafka send 放入配置写事务。

原 invalidateForVersion 在 stripe 锁内比较最低水位：
低版本忽略，同版本仍推进 generation 并清 L1/negative/LKG，高版本推进水位。
同版本不是 no-op，这是为了不漏掉 version=0 创建/负缓存清除等情形。
此次只补旧版本忽略日志，不改变失效算法。

## 依赖实际解析

server POM 新增不写 version 的：

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-kafka</artifactId>
</dependency>
```

实际 dependency:tree：

```text
spring-boot-starter-kafka:4.1.1
  spring-boot-kafka:4.1.1
    spring-kafka:4.1.1
      kafka-clients:4.2.1
```

版本来自现有 Boot 4.1.1 BOM，并已下载实际 JAR 编译测试；不是硬编码猜测。
Java 21 / Boot 4.1.1 / JUnit 5.14.4 不变。
默认 logging 场景原有应用上下文测试继续通过，不需要 Broker。
下载最初被沙箱网络限制阻止，提升权限下载后实际编译与回归成功。

## 消息示例

Topic 默认 `rolloutcore.config-events`，record key 为 `shop:prod:pay`：

```json
{
  "eventId": "12345678-1234-1234-1234-123456789abc",
  "schemaVersion": 1,
  "projectKey": "shop",
  "environmentKey": "prod",
  "flagKey": "pay",
  "configVersion": 7,
  "occurredAt": "2026-09-16T00:00:00Z"
}
```

eventId 复用 Outbox UUID，occurredAt 复用 createdAt 并按 UTC 转换；任何重试都不重新生成。
key 使用不包含冒号的资源 key 组合，同 flag 在 partition 数稳定时进入同一 partition。
多 relay/producer 仍可能乱序，版本保护不可省略。
Codec 拒绝缺字段、类型强转、非法 UUID/key/负数或非整数版本、不支持的 schema 和无时区日期；
Listener 还校验 record key 与 envelope 一致。允许新增未知字段，便于协议扩展。
消息长度上限 8192 字符；不是任意 Domain JSON。

## Producer、Outbox 与重复窗口

```text
ControlPlaneService: config CAS + Audit + Outbox PENDING → MySQL COMMIT
OutboxRelayService → ConfigEventProducer → KafkaConfigEventProducer
  → KafkaTemplate.send(topic,key,json).get(timeout)
  → Broker ACK → markSent → MySQL COMMIT
```

acks=all、enable.idempotence=true、max.in.flight.requests.per.connection=5、retries=Integer.MAX_VALUE。
delivery.timeout.ms=sendTimeout，request.timeout.ms=min(1000,sendTimeout)，linger.ms=0，
max.block.ms=min(1000,sendTimeout)。默认 sendTimeout=5s，总等待约最多 1s metadata/buffer + 5s future。
sendTimeout 校验范围 100ms–60s，delivery timeout 约束合法；
Kafka 内部重试有 delivery timeout 上限，Outbox 轮询重试仍会持续等待 Broker 恢复。

future 异常/超时/同步 send 失败都不 mark SENT。超时可能是 ACK 未被及时观察，Broker 仍可能收到；
后续重投仍使用相同 eventId。中断恢复 interrupt 标记。
Producer 日志记录 ACK 的 eventId/key/configVersion/partition/offset。

Producer idempotence 不会消除：
Broker 已 ACK → Java 崩溃或 MySQL SENT 事务失败 → 下轮再发送。
因此这是 **at-least-once + semantic idempotent consumer**，不是 DB+Kafka exactly-once。
配置事务本身与 Broker 在线状态无关；既有写事务测试仍覆盖 config/Audit/Outbox 原子性。
Relay 在网络发送期间持有原批次数据库行锁，最多 20 行的故障等待会拉长事务；
未加入批次拆分、指数退避、Outbox 清理或终止投递策略。

## Consumer 与 per-instance subscription

```text
Kafka → KafkaConfigChangedListener
  → JSON/schema/key 校验
  → ConfigChangedConsumer.consume
  → SnapshotCache.invalidateForVersion
  → 成功返回 → RECORD offset commit
```

- enable.auto.commit=false；AckMode.RECORD；syncCommits=true；max.poll.records=1；concurrency=1。
- 本地失效失败默认额外尝试 2 次、间隔 250ms；参数上限 5 次 / 5s。
- 协议错误不重试；处理耗尽或无效消息由 CommonContainerStoppingErrorHandler 停止当前实例 listener，
  失败 offset 不提交，修复后重启可重放。已有成功记录可以提交。
- 不实现 DLT/自动跳过。永久坏消息必须修复协议/原因或明确运维处理，盲目重启仍会失败。
- Broker 暂时离线由 Kafka client 连接恢复机制处理，Relay 保持 PENDING 并后续轮询。
- 记录 eventId/key/configVersion/instance/group，不增加高基数 metrics tag。
- listener 停止不主动关闭 HTTP 服务；目前无订阅专用 health/告警，需检查日志和 group lag。

instance-id 在 Kafka 模式必须匹配明确的非空标识，否则 Fail Fast。
实例 ID 由部署方保证不同 JVM 间唯一且重启稳定；代码不能替部署系统检查全局唯一性。

| 实例 | 端口 | instance-id | group |
| --- | --- | --- | --- |
| A | 8080 | instance-a | rolloutcore-cache-instance-a |
| B | 8082 | instance-b | rolloutcore-cache-instance-b |

**Same group = load balancing；different group = 独立完整订阅。**
新 group 的 auto.offset.reset=earliest，已有 group 继续已提交 offset；
没有 retention 之外的无限重放保证。L1 启动为空，仍依赖数据库权威回源。
不同 group 使每个独立 Caffeine L1 都接收事件，不能共用一个组期待广播。

## 幂等与自动测试结果

| 场景 | 自动测试观察 |
| --- | --- |
| 同一 eventId/version/record 两次 | 两次保守失效、可能两次 reload，最终版本 7 |
| v7 → v6 | v6 不增加 invalidation 计数，保留 v7 L1 命中 |
| v6 → v7 | 水位与结果推进到 v7 |
| create v0 | negative cache 被清理，读取到新配置 |
| Kafka ACK 前 | markSent 从未调用 |
| Broker 异常 | 不 markSent，下轮可发送同一 payload/eventId 并成功 |
| ACK 后 markSent 失败 | 异常传播；再次 relay 可产生重复，Consumer 最终状态正确 |
| 永久处理失败 | 3 次本地处理后抛出，停止 handler 不调用 consumer commit |
| duplicate 业务写入 | adapter 仅调用缓存失效/只读 loader，不写 config/Audit/Outbox |

版本水位仍是 Day3 有 TTL/容量的缓存；不是永久 eventId 去重，不能声称无限保留历史版本。
相同版本并非 no-op，重复执行只允许多一次失效/回源，不伪造“完全 exactly-once”。

新增 56 项确定性测试：

| 新测试类 | 数量 |
| --- | ---: |
| KafkaConfigEventCodecTest | 22 |
| KafkaConfigurationTest | 16 |
| KafkaProducerRelayTest | 7 |
| KafkaListenerTest | 6 |
| KafkaCacheSemanticsTest | 5 |

总计 server=357（原 301 + 56），SDK=45、Starter=6、Provider=28、Demo=1，
**437 全通过**。没有改旧测试来避开失败。
这里的 Broker ACK/failure 是 KafkaTemplate future 的确定性测试，缓存使用真实 codec/listener/cache 与受控 loader；
这些自动测试本身不代表实际 Kafka Broker 或两个 JVM 已运行；真实手工证据单独记录于上方。

## Docker 开发环境和自动 E2E

新增 [infra/kafka-compose.yml](../infra/kafka-compose.yml)：
固定官方 apache/kafka:4.2.1、单节点 KRaft、独立 Compose project=rolloutcore-kafka；
外部 127.0.0.1:9092，容器内部 kafka:19092，controller=29093；
无需 ZooKeeper，命名 volume 保留数据，不包含 MySQL/Redis/应用的全栈 Compose。
单节点 replication=1 不能证明副本容错或生产级持久性。

新增 [scripts/kafka-e2e.ps1](../scripts/kafka-e2e.ps1)，只做过语法检查，未在真实 Docker 上执行。
脚本需要 Docker daemon + Compose、Java、mysql CLI、构建好的 server JAR、
实际数据库环境变量与空闲的 8080/8082。

在设置好实际凭据的 PowerShell 会话中：

```powershell
# 沿用用户真实可用的数据库 URL/用户名，密码不得提交到仓库。
$env:ROLLOUTCORE_DB_URL = 'jdbc:mysql://127.0.0.1:3306/rolloutcore?connectionTimeZone=UTC'
$env:ROLLOUTCORE_DB_USERNAME = '<实际用户名>'
$credential = Get-Credential -UserName $env:ROLLOUTCORE_DB_USERNAME
$env:ROLLOUTCORE_DB_PASSWORD = $credential.GetNetworkCredential().Password

mvn clean verify
powershell -ExecutionPolicy Bypass -File scripts/kafka-e2e.ps1 -MySqlClient '<mysql.exe实际路径>' -RunOutageExperiment
```

脚本会：

1. 只读检查 Docker/CLI/数据库连接与端口；端口占用时直接报错，不 kill。
2. 启动专用 Kafka Compose，health ready 后创建本次唯一 topic，3 partitions。
3. 启动两个真实 server JVM，分别 instance-a/b、8080/8082，共用传入 MySQL，Redis 禁用；
   输出重定向到唯一 workspace/kafka-e2e-*，不把数据库密码放命令行或证据文件。
4. 为本次实验显式 L1=10m/LKG=15m，Relay initial delay=120s；
   创建 flag，在 B warm v0=true，再由 A 改 v1=false，读取真实 SQL 验证 PENDING。
   若启动过慢错过 PENDING 窗口则明确失败，不伪造证据。
5. 等到 Broker ACK/两个进程对应 eventId 的 applied 日志及 SQL SENT，
   在 10m L1 TTL 以内验证 B 新版本和值。读取 Broker console records 和两个 group 描述。
6. 重复投递同 eventId/version 两次，等待两份实例日志各增加两次，
   检查 B 状态、Audit/Outbox 行数不因重复消费增加。
7. 真实更新到 v2 后重投 v1，等待 B 的低版本忽略日志，验证仍为 v2。
8. 可选 outage：只停止该 Compose 的 Kafka 容器，A 配置仍提交 v3、Outbox PENDING；
   等实际 relay 失败日志，再启动 Broker，等待 SENT、两实例消费、新版本结果。
   异常退出时尽力恢复该 Broker，不删除 volume。
9. 留下两个 Java 进程并输出 PID，用户自行停止；不停止用户原进程、MySQL 或 Windows 服务。

证据目录保存 topic、Broker records、group offsets、PENDING 行、两份实例日志、进程 PID。
脚本正常运行约需等待两分钟 Relay 首轮延迟；这是为使 PENDING 可观察而设计的测试参数，
不是生产推荐。如果复用过相同 instance-id 的其他 JVM，它们会与本次实例竞争同组 partition，
必须先由用户处理部署冲突。

Docker 自动化路径状态（不等同于上方 Windows 原生手工实验）：

```text
DOCKER_COMPOSE_E2E=NOT_RUN
REAL_KAFKA_E2E=NOT_RUN (Docker script marker only)
REAL_KAFKA_DUPLICATE_E2E=NOT_RUN
REAL_KAFKA_OUT_OF_ORDER_E2E=NOT_RUN
REAL_KAFKA_OUTAGE_RECOVERY_E2E=NOT_RUN (Docker script marker only)
```

Docker 未安装/未运行，脚本未执行；原生 Broker 手工验证已通过上述限定场景。
Docker 脚本仍要求可用 mysql CLI 直接检查状态，本次本机客户端故障尚未解决；
不得将原生手工实验记成此脚本完整通过。本轮未获取或猜测密码。

## 没有 Docker 时的最短 Broker 手工步骤

若用户已有 Linux/WSL + Java 17+，可使用官方 Kafka 4.2.1 解压包；
本轮不会自动安装 WSL/Java/Kafka。按 [Apache Kafka Quick Start](https://kafka.apache.org/42/getting-started/quickstart/)：
在新建、专用的 Kafka 开发目录中，先确认 config/server.properties 的 log.dirs 指向该环境自己的空目录，
不要对已有 Broker 日志执行 format。

```bash
# 在已解压的 Kafka 4.2.1 专用开发目录中；仅首次初始化空日志目录执行 format。
KAFKA_CLUSTER_ID="$(bin/kafka-storage.sh random-uuid)"
bin/kafka-storage.sh format --standalone -t "$KAFKA_CLUSTER_ID" -c config/server.properties
bin/kafka-server-start.sh config/server.properties
# 另一个终端：
bin/kafka-topics.sh --bootstrap-server localhost:9092 --create --if-not-exists \
  --topic rolloutcore.config-events --partitions 3 --replication-factor 1
```

Windows JVM 必须能访问 Broker advertised.listeners 中的地址；
若 Broker 在 WSL/其他主机，不要把只在另一网络命名空间可用的 localhost 当可达地址。

两个应用终端分别已有真实 DB 环境变量后运行：

```powershell
# A
java -jar rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar --server.port=8080 --rolloutcore.events.transport=kafka --rolloutcore.events.instance-id=instance-a --spring.kafka.bootstrap-servers=127.0.0.1:9092 --rolloutcore.cache.redis-enabled=false --rolloutcore.cache.l1-ttl=10m --rolloutcore.cache.lkg-ttl=15m
# B（另一个终端）
java -jar rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar --server.port=8082 --rolloutcore.events.transport=kafka --rolloutcore.events.instance-id=instance-b --spring.kafka.bootstrap-servers=127.0.0.1:9092 --rolloutcore.cache.redis-enabled=false --rolloutcore.cache.l1-ttl=10m --rolloutcore.cache.lkg-ttl=15m
```

kafka-e2e.ps1 的 Broker 控制部分专用于 Docker；原生 Broker 应按上述相同证据步骤手工运行，
不能直接宣称脚本已覆盖原生模式。先 warm B，再从 A 修改配置，保存两个 eventId 日志、
SQL status、Broker records 和 groups 描述，且在 L1 TTL 前确认 B 更新。
Broker 命令用本机 bin/*.sh 代替脚本中的 docker compose exec；可按 Ctrl-C 停自己启动的 Broker、
完成配置写/PENDING 检查后再启动，不能停止其他用途的 Broker。

## 文件与未实现项（此前 Kafka 实现阶段）

以下是此前 Kafka 实现阶段的文件清单；本次真实 E2E 收尾仅更新文档和脚本说明注释。

实现阶段修改：
server/pom.xml、server/resources/application.yml、OutboxConfiguration.java、
SnapshotCache.java（仅旧事件诊断），README.md、docs/ARCHITECTURE.md、docs/DAY4_REPORT.md；
docs/DAY5_REPORT.md 仅追加用户真实验证与 Kafka 后补状态说明。

新增生产文件：
ConfigEventsProperties.java、KafkaConfigChanged.java、KafkaConfigEventCodec.java、
KafkaConfigEventProducer.java、KafkaConfigChangedListener.java、KafkaEventsConfiguration.java。

新增测试：
KafkaEventTestSupport.java、KafkaConfigEventCodecTest.java、KafkaProducerRelayTest.java、
KafkaListenerTest.java、KafkaConfigurationTest.java、cache/KafkaCacheSemanticsTest.java。

新增交付：
infra/kafka-compose.yml、scripts/kafka-e2e.ps1、docs/KAFKA_E2E_REPORT.md。

原 Day5 所有其他文件保留。LoggingProducer、OutboxRelayService、V3、Outbox Mapper/Domain 均保留原实现。
未引入 Nacos、Spring Cloud、Kubernetes、服务网格、审批或权限系统。
尚无生产 Broker 多副本/SASL/TLS 部署验证、订阅告警、DLT、自动毒消息处置、全量事件恢复、
Outbox 清理/终止重试、全局强一致 Kill Switch 或生产压测。
Logging 模式已 SENT 的历史行不会自动重新发 Kafka。

实现参考：
[Spring Kafka 异常处理/停止容器语义](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)、
[Apache Kafka 官方 KRaft Compose 示例](https://github.com/apache/kafka/blob/4.2.1/docker/examples/docker-compose-files/single-node/plaintext/docker-compose.yml)。
框架 API 已用实际 4.1.1 JAR 核对并通过项目测试；Compose 运行兼容性仍需真实环境验证。
