package io.github.lu1j.rolloutcore.domain;

import java.time.LocalDateTime;

/** Project persisted state. Setters also support MyBatis generated keys and result mapping. */
public class Project {
    private Long id;
    private String projectKey;
    private String name;
    private ResourceStatus status;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getProjectKey() { return projectKey; }
    public void setProjectKey(String projectKey) { this.projectKey = projectKey; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public ResourceStatus getStatus() { return status; }
    public void setStatus(ResourceStatus status) { this.status = status; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
