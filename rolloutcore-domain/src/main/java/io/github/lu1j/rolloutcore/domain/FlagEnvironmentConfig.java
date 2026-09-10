package io.github.lu1j.rolloutcore.domain;

import java.time.LocalDateTime;

/** FlagEnvironmentConfig persisted state. Setters also support MyBatis generated keys and result mapping. */
public class FlagEnvironmentConfig {
    private Long id;
    private Long flagId;
    private Long environmentId;
    private boolean enabled;
    private Long defaultVariantId;
    private long version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getFlagId() { return flagId; }
    public void setFlagId(Long flagId) { this.flagId = flagId; }

    public Long getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(Long environmentId) { this.environmentId = environmentId; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Long getDefaultVariantId() { return defaultVariantId; }
    public void setDefaultVariantId(Long defaultVariantId) { this.defaultVariantId = defaultVariantId; }

    public long getVersion() { return version; }
    public void setVersion(long version) { this.version = version; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
