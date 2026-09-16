package io.github.lu1j.rolloutcore.sdk;

import java.net.URI;
import java.time.Duration;
import java.util.Objects;

/** maxRetries counts additional attempts; zero capacity disables LKG. */
public record ClientOptions(URI baseUrl, Duration connectTimeout, Duration requestTimeout,
        int maxRetries, Duration retryDelay, Duration lkgTtl, int lkgCapacity) {
    public ClientOptions {
        Objects.requireNonNull(baseUrl);
        if (!("http".equals(baseUrl.getScheme()) || "https".equals(baseUrl.getScheme()))
                || baseUrl.getHost() == null || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null || baseUrl.getUserInfo() != null)
            throw new IllegalArgumentException("baseUrl must be HTTP(S), without credentials/query/fragment");
        positive(connectTimeout); positive(requestTimeout); positive(lkgTtl);
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.compareTo(Duration.ofSeconds(10)) > 0
                || maxRetries < 0 || maxRetries > 5 || lkgCapacity < 0 || lkgCapacity > 100_000)
            throw new IllegalArgumentException("Invalid retry or LKG bounds");
    }
    private static void positive(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(1)) > 0)
            throw new IllegalArgumentException("Duration must be positive and at most one day");
    }
    public static ClientOptions defaults(String baseUrl) {
        return new ClientOptions(URI.create(baseUrl), Duration.ofMillis(200), Duration.ofMillis(500),
                1, Duration.ofMillis(25), Duration.ofSeconds(30), 1000);
    }
}
