package io.github.lu1j.rolloutcore.sdk;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.lu1j.rolloutcore.sdk.EvaluationResult.Source.*;
import io.github.lu1j.rolloutcore.sdk.EvaluationResult.Error;

class HttpRolloutCoreClientTest {
    final JsonMapper json = JsonMapper.builder().build();
    final HttpClient transport = mock(HttpClient.class);
    final AtomicLong clock = new AtomicLong();
    final EvaluationContext context = new EvaluationContext("u1");
    HttpRolloutCoreClient client;
    @BeforeEach void setup() {
        // Keep scripted transport exceptions synchronous inside this deterministic seam.
        when(transport.sendAsync(any(), any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            try { return CompletableFuture.completedFuture(transport.send(inv.getArgument(0), inv.getArgument(1))); }
            catch (Exception ex) { return CompletableFuture.failedFuture(ex); }
        });
        clearInvocations(transport);
        client = client(2, 2);
    }
    @AfterEach void cleanup() { client.close(); }
    ClientOptions options(int retries, int capacity) {
        return new ClientOptions(URI.create("http://localhost:8080/prefix/"), Duration.ofMillis(100),
                Duration.ofMillis(200), retries, Duration.ZERO, Duration.ofSeconds(10), capacity);
    }
    HttpRolloutCoreClient client(int retries, int capacity) {
        return new HttpRolloutCoreClient(options(retries, capacity), transport, clock::get);
    }
    HttpResponse<String> response(int status, String body) {
        return new HttpResponse<>() {
            public int statusCode() { return status; }
            public String body() { return body; }
            public HttpRequest request() { return null; }
            public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a,b) -> true); }
            public Optional<javax.net.ssl.SSLSession> sslSession() { return Optional.empty(); }
            public URI uri() { return URI.create("http://localhost"); }
            public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
    String body(String value) {
        return "{\"flagKey\":\"flag\",\"variantKey\":\"new\",\"value\":" + value
                + ",\"reason\":\"RULE_MATCH\",\"configVersion\":7,\"matchedRulePriority\":1,\"bucket\":null}";
    }
    void status(int status) throws Exception {
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(status, "{}"));
    }
    void success(String value) throws Exception {
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenReturn(response(200, body(value)));
    }
    EvaluationResult<Boolean> evaluate() { return client.booleanFlag("project", "prod", "flag", context, false); }

    @Test void normalEvaluationAndRequestContract() throws Exception {
        success("true");
        var r = evaluate();
        assertTrue(r.value()); assertEquals(REMOTE, r.source()); assertEquals(Error.NONE, r.error());
        assertEquals("new", r.variant()); assertEquals("RULE_MATCH", r.reason()); assertEquals(7L, r.configVersion());
        var request = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);
        verify(transport).send(request.capture(), any());
        assertEquals("http://localhost:8080/prefix/api/v1/evaluate", request.getValue().uri().toString());
        assertEquals(Duration.ofMillis(200), request.getValue().timeout().orElseThrow());
        assertEquals("POST", request.getValue().method());
    }
    @Test void stringType() throws Exception {
        success("\"hello\"");
        assertEquals("hello", client.stringFlag("project","prod","flag",context,"default").value());
    }
    @Test void numberTypePreservesDecimal() throws Exception {
        success("12.12345678901234567890123456789");
        assertEquals(new BigDecimal("12.12345678901234567890123456789"), client.numberFlag("project","prod","flag",context,BigDecimal.ZERO).value());
    }
    @ParameterizedTest @ValueSource(strings={"{\"nested\":[true,null,3]}", "[1,\"a\"]"})
    void jsonTypeAndDefensiveCopies(String value) throws Exception {
        success(value);
        var r = client.jsonFlag("project","prod","flag",context,json.readTree("{}"));
        assertEquals(json.readTree(value), r.value());
        if (r.value() instanceof ObjectNode object) object.put("mutated", true);
        status(503);
        assertEquals(json.readTree(value), client.jsonFlag("project","prod","flag",context,json.readTree("{}")).value());
    }
    @Test void timeoutRetriesAreBoundedAndDefaultIsCallerOwned() throws Exception {
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new HttpTimeoutException("test"));
        var r = client.booleanFlag("project","prod","flag",context,true);
        assertTrue(r.value()); assertEquals(DEFAULT, r.source()); assertEquals(Error.TIMEOUT, r.error());
        assertEquals(3,r.attempts()); verify(transport,times(3)).send(any(),any());
    }
    @Test void connectionFailure() throws Exception {
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new ConnectException("test"));
        assertEquals(Error.CONNECTION,evaluate().error());
        verify(transport,times(3)).send(any(),any());
    }
    @Test void connectionResetRetries() throws Exception {
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new IOException("reset"));
        assertEquals(Error.CONNECTION,evaluate().error());
        verify(transport,times(3)).send(any(),any());
    }
    @ParameterizedTest @ValueSource(ints={502,503,504})
    void transientServerErrorsRetry(int code) throws Exception {
        when(transport.send(any(), any(HttpResponse.BodyHandler.class)))
                .thenReturn(response(code,"{}"),response(200,body("true")));
        assertEquals(2,evaluate().attempts()); verify(transport,times(2)).send(any(),any());
    }
    @ParameterizedTest @ValueSource(ints={400,401,403,404,409,429,500,501,302})
    void otherStatusesNeverRetryOrUseLkg(int code) throws Exception {
        success("true"); evaluate(); clearInvocations(transport); status(code);
        var r = evaluate();
        assertEquals(DEFAULT,r.source()); assertFalse(r.value()); assertEquals(1,r.attempts());
        if (code == 404) assertEquals(Error.FLAG_NOT_FOUND,r.error());
        verify(transport).send(any(),any());
        status(503); assertEquals(DEFAULT,evaluate().source());
    }
    @Test void lkgOnlyOnInfrastructureFailureAndDoesNotRefreshTtl() throws Exception {
        success("true"); evaluate(); status(503);
        clock.set(Duration.ofSeconds(9).toNanos());
        var recovered = evaluate();
        assertEquals(LKG,recovered.source()); assertTrue(recovered.value());
        assertEquals(Error.SERVER_UNAVAILABLE,recovered.error()); assertEquals("new",recovered.variant());
        clock.set(Duration.ofSeconds(10).toNanos());
        assertEquals(DEFAULT,evaluate().source());
    }
    @Test void healthyReadsAlwaysGoRemote() throws Exception {
        success("true"); evaluate(); success("false");
        assertFalse(evaluate().value()); verify(transport,times(2)).send(any(),any());
    }
    @Test void notFoundInvalidatesOtherContextsOfSameFlag() throws Exception {
        success("true");
        evaluate();
        var other = new EvaluationContext("u2");
        client.booleanFlag("project","prod","flag",other,false);
        status(404); evaluate();
        status(503);
        assertEquals(DEFAULT,client.booleanFlag("project","prod","flag",other,false).source());
    }
    @Test void timedOutAsyncExchangeIsCancelled() {
        var pending = new CompletableFuture<HttpResponse<Object>>();
        when(transport.sendAsync(any(),any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        client = client(0,0);
        assertEquals(Error.TIMEOUT,evaluate().error());
        assertTrue(pending.isCancelled());
    }
    @Test void capacityEvictsLeastRecentlyUsed() throws Exception {
        client = client(0,1); success("true"); evaluate();
        client.booleanFlag("project","prod","flag",new EvaluationContext("u2"),false);
        status(503); assertEquals(DEFAULT,evaluate().source());
        assertEquals(LKG,client.booleanFlag("project","prod","flag",new EvaluationContext("u2"),false).source());
    }
    @Test void zeroCapacityDisablesLkg() throws Exception {
        client = client(0,0); success("true"); evaluate(); status(503);
        assertEquals(DEFAULT,evaluate().source());
    }
    @Test void contextAndRequestedTypeArePartOfCacheIdentity() throws Exception {
        success("true"); evaluate(); status(503);
        var changed = new EvaluationContext("u1","JP",null,null,Map.of("plan",json.readTree("\"pro\"")));
        assertEquals(DEFAULT,client.booleanFlag("project","prod","flag",changed,false).source());
        assertEquals(DEFAULT,client.stringFlag("project","prod","flag",context,"safe").source());
        assertEquals(DEFAULT,client.booleanFlag("other","prod","flag",context,false).source());
        assertEquals(DEFAULT,client.booleanFlag("project","dev","flag",context,false).source());
    }
    @Test void typeMismatchClearsOldValue() throws Exception {
        success("true"); evaluate(); success("\"true\"");
        assertEquals(Error.TYPE_MISMATCH,evaluate().error()); status(503);
        assertEquals(DEFAULT,evaluate().source());
    }
    @ParameterizedTest @ValueSource(strings={"not-json","{}", "{\"flagKey\":\"other\"}",
        "{\"flagKey\":\"flag\",\"variantKey\":\"v\",\"reason\":\"DEFAULT\",\"configVersion\":-1,\"value\":true}"})
    void invalidProtocolNeverFallsBack(String body) throws Exception {
        success("true"); evaluate();
        when(transport.send(any(),any(HttpResponse.BodyHandler.class))).thenReturn(response(200,body));
        assertEquals(Error.PROTOCOL_ERROR,evaluate().error());
        status(503); assertEquals(DEFAULT,evaluate().source());
    }
    @Test void interruptionPreservesThreadFlagAndDoesNotRetry() throws Exception {
        when(transport.send(any(), any(HttpResponse.BodyHandler.class))).thenThrow(new InterruptedException());
        try {
            assertEquals(Error.INTERRUPTED,evaluate().error()); assertTrue(Thread.currentThread().isInterrupted());
            verify(transport).send(any(),any());
        } finally { Thread.interrupted(); }
    }
    @Test void tlsFailureIsNotTransient() throws Exception {
        when(transport.send(any(),any(HttpResponse.BodyHandler.class))).thenThrow(new javax.net.ssl.SSLHandshakeException("bad cert"));
        assertEquals(Error.PROTOCOL_ERROR,evaluate().error()); verify(transport).send(any(),any());
    }
    @Test void invalidLocalInputReturnsExplicitErrorWithoutNetwork() {
        assertEquals(Error.INVALID_CONTEXT,client.booleanFlag("project","prod","flag",new EvaluationContext(""),false).error());
        assertThrows(IllegalArgumentException.class, () -> client.evaluate("project","prod","flag",context,
                RolloutCoreClient.ValueType.BOOLEAN,json.readTree("\"false\"")));
        verifyNoInteractions(transport);
    }
    @Test void contextDefensivelyCopiesMutableAttributes() {
        ObjectNode node = json.createObjectNode().put("a",1);
        var c = new EvaluationContext("u",null,null,null,Map.of("plan",node));
        node.put("a",2);
        ((ObjectNode)c.attributes().get("plan")).put("a",3);
        assertEquals(1,c.attributes().get("plan").path("a").asInt());
    }
    @Test void concurrentCallsAndFallbackAreSafe() throws Exception {
        success("true");
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var work = new ArrayList<Callable<Boolean>>();
            for (int i=0;i<100;i++) work.add(() -> evaluate().value());
            for (var future : executor.invokeAll(work)) assertTrue(future.get());
            status(503);
            for (var future : executor.invokeAll(work)) assertTrue(future.get());
        }
    }
    @Test void lateSuccessCannotRepopulateAfterNotFound() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var call = new AtomicInteger();
        when(transport.send(any(),any(HttpResponse.BodyHandler.class))).thenAnswer(inv -> {
            if (call.getAndIncrement() == 0) {
                started.countDown(); assertTrue(release.await(5,TimeUnit.SECONDS)); return response(200,body("true"));
            }
            return response(404,"{}");
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            var old = executor.submit(this::evaluate);
            assertTrue(started.await(5,TimeUnit.SECONDS));
            try { assertEquals(Error.FLAG_NOT_FOUND,evaluate().error()); }
            finally { release.countDown(); }
            assertTrue(old.get(5,TimeUnit.SECONDS).value());
        }
        status(503); assertEquals(DEFAULT,evaluate().source());
    }
    @ParameterizedTest @ValueSource(ints={-1,6,Integer.MAX_VALUE})
    void retryConfigurationIsBounded(int retries) {
        assertThrows(IllegalArgumentException.class, () -> options(retries,1));
    }
    @Test void configurationRejectsUnsafeUrlsAndInvalidLimits() {
        var d = Duration.ofSeconds(1);
        for (String url : List.of("ftp://localhost","http://u:p@localhost","http://localhost?q=1","http://localhost#fragment"))
            assertThrows(IllegalArgumentException.class, () -> new ClientOptions(URI.create(url),d,d,0,d,d,1));
        assertThrows(IllegalArgumentException.class, () -> options(0,-1));
        assertThrows(IllegalArgumentException.class, () -> new ClientOptions(URI.create("http://localhost"),Duration.ZERO,d,0,d,d,1));
    }
    @Test void realLocalHttpSerializesContextAndDecodesResponse() throws Exception {
        var received = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/evaluate", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            byte[] bytes = body("true").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try (var output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        try (var live = RolloutCoreClient.create(ClientOptions.defaults("http://127.0.0.1:"+server.getAddress().getPort()))) {
            assertTrue(live.booleanFlag("project","prod","flag",context,false).value());
            var request = json.readTree(received.get());
            assertEquals("project",request.path("projectKey").asString());
            assertEquals("prod",request.path("environmentKey").asString());
            assertEquals("u1",request.path("context").path("userId").asString());
        } finally { server.stop(0); }
    }
    @Test void realHttpTimeoutHasFiniteDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/evaluate", exchange -> {
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var d = Duration.ofMillis(150);
        var config = new ClientOptions(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),d,d,0,Duration.ZERO,d,0);
        try (var live = RolloutCoreClient.create(config)) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                assertEquals(Error.TIMEOUT,live.booleanFlag("project","prod","flag",context,false).error()));
        } finally { release.countDown(); server.stop(0); }
    }
    @Test void deadlineAlsoCoversStalledResponseBody() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/evaluate", exchange -> {
            exchange.sendResponseHeaders(200,1000);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try { release.await(5,TimeUnit.SECONDS); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        var d = Duration.ofMillis(200);
        var config = new ClientOptions(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),d,d,0,Duration.ZERO,d,0);
        try (var live = RolloutCoreClient.create(config)) {
            assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertEquals(Error.TIMEOUT,live.booleanFlag("project","prod","flag",context,false).error()));
        } finally { release.countDown(); server.stop(0); }
    }
}
