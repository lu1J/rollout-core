# Engineering validation

验证日期：2026-09-17。基线 HEAD `913ee2e`。本次在已有未提交 observability / Docker / Testcontainers / CI 半成品上审查并完成；未提交或推送。

## 当前验证矩阵

| 项目 | 结果 | 证据与边界 |
| --- | --- | --- |
| `mvn clean verify` | PASSED，441 tests / 0 failures / 0 errors / 0 skipped | Surefire XML；7 个 reactor 项目 BUILD SUCCESS |
| 压测工具 correctness | PASSED，3 tests | Python 3.13 标准库 unittest，本地 HTTP fixture |
| PowerShell scripts | PASSED，5 files | PowerShell Parser 语法检查，不代表 E2E 运行 |
| YAML 静态语法 | PASSED，6 files | SnakeYAML，拒绝重复 key；不是 Compose config |
| Testcontainers 测试源码编译 | PASSED | 默认构建 testCompile 编译 InfrastructureIT |
| `mvn -Ptestcontainers verify` | NOT_RUN | 当前找不到 Docker CLI/daemon；未安装 Docker |
| Docker build / `docker compose config` | NOT_RUN | 无 Docker；YAML 语法检查不能替代 Compose 验证 |
| 完整 Compose E2E | NOT_RUN | 未启动容器 |
| 真实 Server Prometheus HTTP | NOT_RUN | 尚无真实 Server scrape 验证证据；MockMvc scrape 已通过 |
| 真实 Evaluation 压测 | PASSED | 用户提供的本地单实例 benchmark（Windows 10 19045、Java 21.0.8、Python 3.13.5、RolloutCore Server + Local MySQL 8.0）：10000 requests；success 10000；errors 0；concurrency 8；QPS 2857.70；P99 5.01ms。详见 [性能验证](PERFORMANCE.md)，用于验证正确性和基础性能，不代表生产容量 |
| GitHub Actions | NOT_RUN | workflow 已配置，本轮未推送或触发远端运行 |
| `git diff --check` | PASSED | 按仓库现有换行配置执行，退出码 0；不代替测试 |

本地日志 `workspace/mvn-clean-verify.log` 和各模块 `target/surefire-reports/TEST-*.xml` 被 Git 忽略。
CI 上传 Surefire/Failsafe artifacts，公开的 README 不依赖本地日志可访问。

| 模块 | tests | failures / errors / skipped |
| --- | ---: | --- |
| server | 360 | 0 / 0 / 0 |
| sdk | 45 | 0 / 0 / 0 |
| spring-boot-starter | 6 | 0 / 0 / 0 |
| openfeature-provider | 28 | 0 / 0 / 0 |
| demo-service | 2 | 0 / 0 / 0 |
| domain | 0 | 无测试类 |

## 测试组织

六个业务模块加根 POM，共七个 reactor 项目。Java 21、Spring Boot 4.1.1；JUnit Jupiter 固定 5.14.4，应用装配用 Context Runner，避免依赖要求 JUnit 6 的 SpringExtension。
Prometheus / Testcontainers 与 Surefire / Failsafe 版本沿用 Boot 管理；未改生产依赖基线。
`.mvn/maven.config` 将依赖缓存放在 `workspace/maven-repository`，CI actions/cache 使用同一路径。

默认 `mvn clean verify` 运行 Surefire 普通测试，编译但不执行 `InfrastructureIT`。普通测试不依赖 Docker，也没有 H2 冒充 MySQL。
`mvn -Ptestcontainers verify` 在 server 模块绑定 Failsafe integration-test/verify，`failIfNoTests=true`；未提供 Docker 自动跳过开关。
缺少 Docker 或无法拉取镜像应导致 IT 失败，不能以 skipped 充当通过。

`InfrastructureIT` 有两个中间件场景：

- MySQL 8.0.36 + Kafka 4.2.1：空库执行真实 Flyway V1/V2/V3，启动两个真实 Spring Boot HTTP 应用上下文，共享 MySQL/Broker，使用两个独立 group。HTTP 创建配置、读取并预热 B，再由 A 更新，45 秒内等待 B 新版本（L1=10m、LKG=15m）。验证真实 Outbox SENT、stale PUT=409 不新增审计/Outbox、两 group 消费以及 Prometheus scrape。实际 Producer、codec/key 校验、Listener 和 Cache 均参与；不是两个独立 JVM，跨 JVM 的历史实验另列。
- Redis 7.4.2：真实 RedisSnapshotStore Lua put/get/invalidate、版本 tombstone 拒绝旧回填、大于 2^53 的 long 精度、TTL、相同版本回填和低版本失效不能删除新 payload。原有单字段 HSET 脚本未改动。

容器、Spring context、HttpClient、AdminClient 使用 try-with-resources；Redis connection factory 在 finally 中关闭。应用关闭先于容器，容器测试不连接用户本机数据库或使用本机 secret。
IT 不是穷尽 HA/并发事务实验；真实 SKIP LOCKED 高并发竞争、Broker outage 大矩阵未在该套 IT 重复实现。

CI 两个 job：unit 用 Java 21 执行 clean verify、Python fixture、Compose config；integration 先 docker info，再用 testcontainers profile 执行 server 及依赖模块，包含普通回归与真实容器 IT。都有 timeout、Maven cache 和 always 上传结果，不启动全栈 Compose，不调用用户真实外部服务（依赖与镜像下载仍需要网络）。

## 历史真实环境证据

用户此前已确认原生 MySQL Control Plane、策略、SDK 正常与停服回退 E2E。完整现状与复现命令见正式 API/SDK/部署文档；没有将旧结果记为本次重跑。
2026-09-16 Windows 原生 Kafka 的 Broker smoke、双 JVM 独立 group 主动失效、Broker outage/recovery 已通过。
恢复后的 MySQL SENT 未获直接 SQL 查询证据；89.5 秒是人工操作过程间隔，不是传播时延 benchmark。
[Kafka validation](KAFKA_VALIDATION.md) 保留原始日志定位、实验条件与 NOT_VERIFIED / NOT_RUN 边界。

## 审查发现与处理

- 初始 dirty 修改属于同一工程化目标，保留并完善，没有发现无关用户改动，没有 reset/checkout。
- 补齐 Kafka applied/stale，避免把旧事件忽略统称 applied；只让原有锁内失效路径返回结果，不改变版本/确认/Outbox 语义。
- Evaluation Timer 只计一次；Outbox 成功计发送尝试，明确不等于 SENT 提交；缓存复用原有指标，无高基数 tag。
- 原 IT 的 L1=10m/LKG 默认5m 不满足配置约束，修正为 LKG=15m；Kafka 断言同步使用新的真实终态。
- Compose 增加 host/container 双 listener、loopback 端口、dev-only env defaults、健康依赖和可选 replica；CI 取消尚无实证的整栈启动，保留 config 检查。
- Demo 默认项目改为 sdk-demo，与 Compose/脚本一致；HTTP SDK 集成测试覆盖默认项目与显式覆盖。
- 测试类/脚本采用语义命名，原有测试行为与数据库迁移保留；历史报告中的契约合并至 Architecture/Database/API/Evaluation/SDK，移除过期流水账。

## Known limitations

这是 production-oriented portfolio project，不是完整 production platform。没有 Auth/RBAC、multi-region、生产 HA 编排或跨系统 exactly-once。
Outbox 网络等待仍占用批次事务行锁，没有终止重试/清理策略。Kafka listener 停止未专门映射整体 health；Kafka 本地单节点不是生产 HA。
版本水位有限内存/TTL，重复事件是语义幂等，非永久去重；缓存与 SDK LKG 无瞬时全局一致保证。
SDK 仍依赖中央 Evaluation，不全量下载和本地执行规则。负载工具是闭环，受客户端资源限制，任何本地结果都不能作为线上容量保证。
本机 Docker / Testcontainers / 完整 Compose 均未运行，不能根据编译或静态检查宣称部署成功。真实 Evaluation 本地单实例压测已通过，结果仅用于验证正确性和基础性能，不代表生产容量。
