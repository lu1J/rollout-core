package io.github.lu1j.rolloutcore.domain;

import java.time.LocalDateTime;

public class OutboxEvent {
    private Long id;
    private String eventId;
    private String eventType;
    private String projectKey;
    private String environmentKey;
    private String flagKey;
    private long version;
    private String payload;
    private OutboxStatus status;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }
    public String getEnvironmentKey() { return environmentKey; }
    public void setEnvironmentKey(String environmentKey) { this.environmentKey = environmentKey; }
    public String getFlagKey() { return flagKey; }
    public void setFlagKey(String flagKey) { this.flagKey = flagKey; }
    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }
    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }
    public OutboxStatus getStatus() { return status; }
    public void setStatus(OutboxStatus status) { this.status = status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
