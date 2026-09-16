package io.github.lu1j.rolloutcore.demo;

import com.sun.net.httpserver.HttpServer;
import io.github.lu1j.rolloutcore.sdk.RolloutCoreClient;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

/** Real demo HTTP -> auto-discovered starter -> real SDK HTTP -> local evaluation fixture. */
class DemoIntegrationTest {
    @Test void independentBusinessApplicationUsesHttpSdkAndFallback() throws Exception {
        var json = JsonMapper.builder().build();
        var status = new AtomicInteger(200);
        var requests = new AtomicInteger();
        var requestBody = new AtomicReference<String>();
        var evaluation = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        evaluation.createContext("/api/v1/evaluate", exchange -> {
            requests.incrementAndGet();
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes = (status.get() == 200
                ? "{\"flagKey\":\"new-payment-flow\",\"variantKey\":\"new\",\"value\":true,\"reason\":\"RULE_MATCH\",\"configVersion\":1}"
                : "{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        evaluation.start();
        try (var app = new SpringApplicationBuilder(DemoApplication.class).run(
                "--server.port=0", "--spring.main.banner-mode=off", "--demo.project-key=integration",
                "--rolloutcore.sdk.base-url=http://127.0.0.1:"+evaluation.getAddress().getPort(),
                "--rolloutcore.sdk.max-retries=0");
             var http = HttpClient.newHttpClient()) {
            assertNotNull(app.getBean(RolloutCoreClient.class));
            int port = ((WebServerApplicationContext)app).getWebServer().getPort();
            String base = "http://127.0.0.1:"+port;
            var response = http.send(HttpRequest.newBuilder(URI.create(base+"/demo/payment?userId=user1")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200,response.statusCode());
            var result = json.readTree(response.body());
            assertEquals("new",result.path("flow").asString());
            assertEquals("REMOTE",result.path("source").asString());
            assertEquals("NONE",result.path("error").asString());
            var request = json.readTree(requestBody.get());
            assertEquals("integration",request.path("projectKey").asString());
            assertEquals("user1",request.path("context").path("userId").asString());
            assertEquals(1,requests.get());
            status.set(503);
            result = json.readTree(http.send(HttpRequest.newBuilder(URI.create(base+"/demo/payment?userId=user1")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("new",result.path("flow").asString());
            assertEquals("LKG",result.path("source").asString());
            assertEquals("SERVER_UNAVAILABLE",result.path("error").asString());
            result = json.readTree(http.send(HttpRequest.newBuilder(URI.create(base+"/demo/payment?userId=unknown")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("old",result.path("flow").asString());
            assertEquals("DEFAULT",result.path("source").asString());
            status.set(404);
            result = json.readTree(http.send(HttpRequest.newBuilder(URI.create(base+"/demo/payment?userId=user1")).build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals("old",result.path("flow").asString());
            assertEquals("FLAG_NOT_FOUND",result.path("error").asString());
        } finally { evaluation.stop(0); }
    }
}
