package io.github.lu1j.rolloutcore.starter;

import io.github.lu1j.rolloutcore.sdk.ClientOptions;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rolloutcore.sdk")
public class RolloutCoreProperties {
    private boolean enabled = true;
    private String baseUrl = "http://127.0.0.1:8080";
    private Duration connectTimeout = Duration.ofMillis(200);
    private Duration timeout = Duration.ofMillis(500);
    private int maxRetries = 1;
    private Duration retryDelay = Duration.ofMillis(25);
    private Duration lkgTtl = Duration.ofSeconds(30);
    private int lkgCapacity = 1000;
    public ClientOptions toOptions() {
        return new ClientOptions(URI.create(baseUrl), connectTimeout, timeout, maxRetries, retryDelay, lkgTtl, lkgCapacity);
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String value) { baseUrl = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getTimeout() { return timeout; }
    public void setTimeout(Duration value) { timeout = value; }
    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int value) { maxRetries = value; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration value) { retryDelay = value; }
    public Duration getLkgTtl() { return lkgTtl; }
    public void setLkgTtl(Duration value) { lkgTtl = value; }
    public int getLkgCapacity() { return lkgCapacity; }
    public void setLkgCapacity(int value) { lkgCapacity = value; }
}
