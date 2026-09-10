package io.github.lu1j.rolloutcore.domain;

import java.time.LocalDateTime;

/** Environment persisted state. Setters also support MyBatis generated keys and result mapping. */
public class Environment {
    private Long id;
    private Long projectId;
    private String envKey;
    private String name;
    private ResourceStatus status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getProjectId() { return projectId; }
    public void setProjectId(Long projectId) { this.projectId = projectId; }

    public String getEnvKey() { return envKey; }
    public void setEnvKey(String envKey) { this.envKey = envKey; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ResourceStatus getStatus() { return status; }
    public void setStatus(ResourceStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
