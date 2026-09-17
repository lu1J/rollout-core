# Local deployment

这是可重复的本地开发环境，不是 production orchestration。需要 Docker Engine / Docker Desktop 和 Compose v2；镜像构建会下载 Maven 依赖与基础镜像。

```powershell
docker compose -f infra/compose.yml up -d --build
docker compose -f infra/compose.yml ps
curl.exe --fail http://localhost:8080/actuator/health
curl.exe --fail http://localhost:8080/actuator/prometheus
powershell -ExecutionPolicy Bypass -File scripts/sdk-e2e.ps1 -ProjectKey sdk-demo
```

服务启动不自动创建业务数据。最后一条命令创建示例项目、环境、flag、策略，并通过 Demo 验证 old/new 分支。
脚本遇到已存在的项目会失败，不会覆盖数据。重新验证时选择新 ProjectKey，并同步修改 Demo 的 `DEMO_PROJECT_KEY` 后重新创建 Demo 容器。

| 服务 | 容器内部 | 主机地址 | 就绪依赖 |
| --- | --- | --- | --- |
| MySQL | mysql:3306 | 127.0.0.1:3306 | 真实 SELECT 1 healthcheck |
| Redis | redis:6379 | 127.0.0.1:6379 | PING healthcheck |
| Kafka KRaft | kafka:19092；controller 29093 | 127.0.0.1:9092 | topic list healthcheck |
| kafka-init | kafka:19092 | 无 | Kafka 健康后创建 3 partition topic，成功退出 |
| Server | server:8080 | 127.0.0.1:8080 | MySQL/Redis 健康、topic 初始化成功 |
| Demo | demo:8081 | 127.0.0.1:8081 | Server 健康 |
| Prometheus | prometheus:9090 | 127.0.0.1:9090 | Server 健康，scrape server:8080 |

如主机已占用 3306/6379/9092 等端口，请自行停止对应开发环境或修改 Compose 的主机端口映射。
修改 Kafka 主机端口时也必须同步修改 EXTERNAL advertised listener；不能只改 ports。
容器客户端使用 `kafka:19092`，主机客户端使用 `localhost:9092`，两条 listener 分别发布对应网络可达地址。
Controller listener 不对主机暴露。所有应用发布端口仅绑定 loopback；不适用于远程客户端直接访问。

默认密码 `dev-only-rolloutcore` / `dev-only-root` 仅用于这个隔离开发环境，不能用于生产。
覆盖示例：复制 `infra/.env.example` 为被忽略的 `infra/.env`，设置本地值，然后显式运行：

```powershell
docker compose --env-file infra/.env -f infra/compose.yml up -d --build
```

已初始化的 MySQL volume 不会因修改初始化环境变量而重置账户密码；需要显式管理现有数据库账户。
MySQL/Kafka 使用命名 volume 保留数据。`docker compose -f infra/compose.yml down` 停止环境并保留 volume；不要为了重跑脚本清空已有数据。
Redis 不持久化，丢失缓存后由 MySQL 回源。

可选第二实例：

```powershell
docker compose -f infra/compose.yml --profile replica up -d --build
```

Server B 在 8082，instance-id=compose-b；A 为 compose-a。不同 group 各收完整事件流。
默认 Prometheus 只抓取 Server A；如需 B，显式在 `infra/prometheus.yml` targets 中添加 `server-b:8080`。
已有 `infra/kafka-compose.yml` 保留为仅启动 Broker 的独立环境；不要同时运行两个争用 9092 的 Compose。

根 Dockerfile 在 Maven / Java 21 阶段构建整个 reactor，运行阶段仅复制指定模块可执行 JAR。
Server 使用默认 APP_MODULE；Demo 通过 build arg 选择 rolloutcore-demo-service。运行用户非 root，curl 用于健康检查。
Docker build 跳过测试；本地/CI 的 `mvn clean verify` 承担测试验证。

## 不使用 Docker

Java 21 / Maven 3.9+，空的 MySQL 8 数据库及具有迁移权限的账户：

```powershell
mvn clean verify
$env:ROLLOUTCORE_DB_URL = 'jdbc:mysql://127.0.0.1:3306/rolloutcore?connectionTimeZone=UTC'
$env:ROLLOUTCORE_DB_USERNAME = 'rolloutcore'
$credential = Get-Credential -UserName $env:ROLLOUTCORE_DB_USERNAME
$env:ROLLOUTCORE_DB_PASSWORD = $credential.GetNetworkCredential().Password
java -jar rolloutcore-server/target/rolloutcore-server-0.1.0-SNAPSHOT.jar
```

另一个终端：

```powershell
$env:ROLLOUTCORE_SDK_BASE_URL = 'http://127.0.0.1:8080'
$env:DEMO_PROJECT_KEY = 'sdk-demo'
java -jar rolloutcore-demo-service/target/rolloutcore-demo-service-0.1.0-SNAPSHOT.jar
```

原生 Server 默认 Redis 禁用、events.transport=logging。启用 Redis 需设置 `ROLLOUTCORE_CACHE_REDIS_ENABLED=true`、`ROLLOUTCORE_REDIS_HOST/PORT`。
启用 Kafka 需设置 `ROLLOUTCORE_EVENTS_TRANSPORT=kafka`、唯一稳定的 `ROLLOUTCORE_INSTANCE_ID`、`ROLLOUTCORE_KAFKA_BOOTSTRAP_SERVERS`，并预先创建 topic。
LoggingProducer 的 SENT 仅代表日志成功，不代表其他实例收到事件。

当前 Docker build / compose config / Compose E2E 均为 **NOT_RUN**：执行环境未发现 Docker。部署文件已完成代码审查，但尚无容器运行证据。
