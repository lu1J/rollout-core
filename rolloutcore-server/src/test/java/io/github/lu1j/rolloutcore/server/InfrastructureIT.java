package io.github.lu1j.rolloutcore.server;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.containers.GenericContainer;
import io.github.lu1j.rolloutcore.server.cache.CacheKey;
import io.github.lu1j.rolloutcore.server.cache.RedisSnapshotStore;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in Failsafe suite. Missing Docker is a failure, never a silently skipped success. */
class InfrastructureIT {
    private final ObjectMapper json = new ObjectMapper();

    @Test void mysqlFlywayHttpOutboxAndTwoIndependentKafkaSubscriptions() throws Exception {
        try (var mysql = new MySQLContainer("mysql:8.0.36");
             var kafka = new KafkaContainer("apache/kafka:4.2.1")) {
            mysql.start();
            kafka.start();
            try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
                admin.createTopics(java.util.List.of(new NewTopic("rolloutcore.config-events", 3, (short) 1)))
                        .all().get(30, TimeUnit.SECONDS);
            }
            try (var a = start(mysql, kafka, "it-a"); var b = start(mysql, kafka, "it-b");
                 var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
                var db = a.getBean(JdbcTemplate.class);
                assertEquals(3, db.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1", Integer.class));
                String base = url(a), other = url(b);
                request(http, base, "POST", "/api/v1/projects", "{\"projectKey\":\"it\",\"name\":\"Integration\"}", 201);
                request(http, base, "POST", "/api/v1/projects/it/environments", "{\"envKey\":\"prod\",\"name\":\"Production\"}", 201);
                request(http, base, "POST", "/api/v1/projects/it/flags", """
                        {"flagKey":"pay","name":"Pay","valueType":"BOOLEAN",
                         "variants":[{"variantKey":"off","value":false},{"variantKey":"on","value":true}]}
                        """, 201);
                String config = "/api/v1/projects/it/environments/prod/flags/pay/config";
                request(http, base, "POST", config, "{\"enabled\":true,\"defaultVariantKey\":\"on\"}", 201);
                await(() -> db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status='PENDING'", Integer.class) == 0);
                String evaluate = """
                        {"projectKey":"it","environmentKey":"prod","flagKey":"pay","context":{"userId":"u"}}
                        """;
                assertTrue(json.readTree(request(http, other, "POST", "/api/v1/evaluate", evaluate, 200)).get("value").asBoolean());
                request(http, base, "PUT", config, "{\"enabled\":false,\"defaultVariantKey\":\"off\",\"expectedVersion\":0}", 200);
                // Ten-minute L1 TTL; this bounded wait must observe event-driven invalidation.
                await(() -> json.readTree(request(http, other, "POST", "/api/v1/evaluate", evaluate, 200))
                        .get("configVersion").asLong() == 1);
                assertFalse(json.readTree(request(http, other, "POST", "/api/v1/evaluate", evaluate, 200)).get("value").asBoolean());
                await(() -> db.queryForObject("SELECT COUNT(*) FROM outbox_event WHERE status='SENT'", Integer.class) == 2);
                int audits = db.queryForObject("SELECT COUNT(*) FROM ff_audit_log", Integer.class);
                request(http, base, "PUT", config, "{\"enabled\":true,\"defaultVariantKey\":\"on\",\"expectedVersion\":0}", 409);
                assertEquals(audits, db.queryForObject("SELECT COUNT(*) FROM ff_audit_log", Integer.class));
                assertEquals(2, db.queryForObject("SELECT COUNT(*) FROM outbox_event", Integer.class));
                for (var context : java.util.List.of(a, b)) {
                    var meters = context.getBean(io.micrometer.core.instrument.MeterRegistry.class);
                    await(() -> meters.find("rolloutcore.kafka.consume").counters().stream()
                            .filter(c -> java.util.Set.of("applied", "stale").contains(c.getId().getTag("outcome")))
                            .mapToDouble(io.micrometer.core.instrument.Counter::count).sum() >= 2);
                }
                String scrape = request(http, base, "GET", "/actuator/prometheus", null, 200);
                assertTrue(scrape.contains("rolloutcore_outbox_send_total"));
                assertTrue(scrape.contains("rolloutcore_cache_l1_hit_total"));
            }
        }
    }

    @Test void realRedisLuaRejectsStaleFillAndRetainsLongPrecisionAndTtl() {
        try (var redis = new GenericContainer<>("redis:7.4.2-alpine").withExposedPorts(6379)) {
            redis.start();
            var connection = new LettuceConnectionFactory(redis.getHost(), redis.getMappedPort(6379));
            connection.afterPropertiesSet();
            try {
                var template = new StringRedisTemplate(connection);
                var store = new RedisSnapshotStore(template);
                var key = new CacheKey("it", "prod", "pay");
                long version = 9007199254740993L;
                store.put(key, version, "{\"version\":1}", Duration.ofSeconds(60));
                assertTrue(store.get(key).isPresent());
                store.invalidate(key, version + 1, Duration.ofSeconds(60));
                store.put(key, version, "stale", Duration.ofSeconds(60));
                assertTrue(store.get(key).isEmpty());
                assertEquals(Long.toString(version + 1), template.opsForHash().get(key.redisKey(), "version"));
                Long ttl = template.getExpire(key.redisKey(), TimeUnit.MILLISECONDS);
                assertNotNull(ttl);
                assertTrue(ttl > 0 && ttl <= 60000);
                store.put(key, version + 1, "fresh", Duration.ofSeconds(60));
                store.invalidate(key, version, Duration.ofSeconds(60));
                assertEquals("fresh", store.get(key).orElseThrow());
            } finally { connection.destroy(); }
        }
    }

    private ConfigurableApplicationContext start(MySQLContainer mysql, KafkaContainer kafka, String id) {
        return SpringApplication.run(RolloutCoreApplication.class,
                "--server.port=0", "--spring.datasource.url=" + mysql.getJdbcUrl(),
                "--spring.datasource.username=" + mysql.getUsername(), "--spring.datasource.password=" + mysql.getPassword(),
                "--rolloutcore.events.transport=kafka", "--rolloutcore.events.instance-id=" + id,
                "--spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers(),
                "--rolloutcore.cache.redis-enabled=false", "--rolloutcore.cache.l1-ttl=10m",
                "--rolloutcore.cache.lkg-ttl=15m",
                "--rolloutcore.outbox.poll-interval-ms=100", "--rolloutcore.outbox.initial-delay-ms=100");
    }
    private String url(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }
    private String request(HttpClient http, String base, String method, String path, String body, int status) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("X-Operator", "integration-test").header("Content-Type", "application/json");
        var response = http.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(status, response.statusCode(), response.body());
        return response.body();
    }
    private interface Check { boolean done() throws Exception; }
    private void await(Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (!check.done()) {
            if (System.nanoTime() >= deadline) fail("Condition not satisfied within 45 seconds");
            Thread.sleep(100);
        }
    }
}
