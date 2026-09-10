package io.github.lu1j.rolloutcore.domain;

import java.time.LocalDateTime;

/** FlagVariant persisted state. Setters also support MyBatis generated keys and result mapping. */
public class FlagVariant {
    private Long id;
    private Long flagId;
    private String variantKey;
    private String valueJson;
    private LocalDateTime createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getFlagId() { return flagId; }
    public void setFlagId(Long flagId) { this.flagId = flagId; }

    public String getVariantKey() { return variantKey; }
    public void setVariantKey(String variantKey) { this.variantKey = variantKey; }

    public String getValueJson() { return valueJson; }
    public void setValueJson(String valueJson) { this.valueJson = valueJson; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
