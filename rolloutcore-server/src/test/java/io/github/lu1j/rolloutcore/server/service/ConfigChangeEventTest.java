package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.server.cache.*;
import io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.UpdatePolicy;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConfigChangeEventTest extends ServiceFixture {
    private final CacheKey key = new CacheKey("shop", "prod", "pay");
    @Test void allConfigurationWritePathsPublishLatestCommittedCandidateVersion() {
        service.createConfig("shop", "prod", "pay", new Commands.CreateConfig("old", true), "alice");
        verify(events).publishEvent(new ConfigChanged(key, 0));
        service.updateConfig("shop", "prod", "pay", new Commands.UpdateConfig("old", true, 0L), "alice");
        verify(events).publishEvent(new ConfigChanged(key, 1));
        service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(1L), true, "alice");
        verify(events).publishEvent(new ConfigChanged(key, 2));
        service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(2L), false, "alice");
        verify(events).publishEvent(new ConfigChanged(key, 3));
        when(variants.listByFlag(3)).thenReturn(List.of(variant)); when(configs.updatePolicy(any(), anyLong())).thenReturn(1);
        service.updatePolicy("shop", "prod", "pay", new UpdatePolicy(3L, null, null), "alice");
        verify(events).publishEvent(new ConfigChanged(key, 4));
    }
    @Test void staleWriteDoesNotPublishInvalidation() {
        assertThrows(OptimisticLockConflictException.class, () -> service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(9L), false, "alice"));
        verifyNoInteractions(events);
    }
    @Test void failedAuditDoesNotPublishInvalidation() {
        when(audits.insert(any())).thenThrow(new org.springframework.dao.DataIntegrityViolationException("audit error"));
        assertThrows(org.springframework.dao.DataIntegrityViolationException.class, () -> service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(0L), false, "alice"));
        verifyNoInteractions(events);
    }
    @Test void unreferencedNewVariantDoesNotInvalidateExistingEvaluations() {
        service.createVariant("shop", "pay", new Commands.CreateVariant("new", json.readTree("true")), "alice");
        verifyNoInteractions(events);
    }
}
