# Kafka validation

历史实验日期：2026-09-16；不是当前修改后的重跑结果。当前验证矩阵见 [工程验证](ENGINEERING_VALIDATION.md)。

## Windows Native Kafka 4.2.1 Manual E2E — PASSED

### 证据来源与状态矩阵

保留原始目录 `workspace/kafka-manual-e2e-20260916-202127/`，未删除或改写日志。
该次验证核对上述本地目录中的 `instance-a-retry.log` 和 `instance-b-retry.log`。
原始日志包含环境信息，仅保留在被 Git 忽略的本地 workspace，不作为公开仓库附件。
下文行号均针对这些 retry 日志；最初的 instance-a.log/instance-b.log 不是本次成功运行的证据。
PID/端口/group/订阅/partition/cluster ID/eventId/ACK/超时由日志直接核对；
Broker 配置、CLI smoke、health/HTTP 响应、端口停启观测、TTL 参数、89.5 秒耗时和 mysql.exe 错误
来自用户该次验证提供的真实手工实验记录，未声称它们全部存在于应用日志。

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
不表示本次 Java 应用连接 MySQL 或配置事务失败。该次验证未安装/修复该客户端。

自动测试已证明 ACK success → markSent、send failure → 不 markSent/保持待重试；
真实手工实验已证明同一 PENDING event 在 Broker 恢复后自动获得 ACK 并传播到两实例。
**Producer ACK 和消费成功日志不是 MySQL SENT 提交的直接 SQL 证据**。
所以不能写成“真实 SQL 已确认最终 SENT”，也不能把它扩大为 DB+Kafka exactly-once。


## Docker 开发环境和自动 E2E

[infra/kafka-compose.yml](../infra/kafka-compose.yml)：
固定官方 apache/kafka:4.2.1、单节点 KRaft、独立 Compose project=rolloutcore-kafka；
外部 127.0.0.1:9092，容器内部 kafka:19092，controller=29093；
无需 ZooKeeper，命名 volume 保留数据，不包含 MySQL/Redis/应用的全栈 Compose。
单节点 replication=1 不能证明副本容错或生产级持久性。

[scripts/kafka-e2e.ps1](../scripts/kafka-e2e.ps1)，只做过语法检查，未在真实 Docker 上执行。
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
不得将原生手工实验记成此脚本完整通过。该次验证未获取或猜测密码。

## 没有 Docker 时的最短 Broker 手工步骤

若用户已有 Linux/WSL + Java 17+，可使用官方 Kafka 4.2.1 解压包；
该次验证不会自动安装 WSL/Java/Kafka。按 [Apache Kafka Quick Start](https://kafka.apache.org/42/getting-started/quickstart/)：
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
