package io.github.lu1j.rolloutcore.domain;

import java.time.LocalDateTime;

/** FeatureFlag persisted state. Setters also support MyBatis generated keys and result mapping. */
public class FeatureFlag {
    private Long id;
    private Long projectId;
    private String flagKey;
    private String name;
    private FlagValueType valueType;
    private ResourceStatus status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getFlagKey() { return flagKey; }
    public void setFlagKey(String flagKey) { this.flagKey = flagKey; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public FlagValueType getValueType() { return valueType; }
    public void setValueType(FlagValueType valueType) { this.valueType = valueType; }

    public ResourceStatus getStatus() { return status; }
    public void setStatus(ResourceStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
