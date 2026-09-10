package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.*;
import io.github.lu1j.rolloutcore.server.service.Commands.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ControlPlaneServiceTest extends ServiceFixture {
    @Test void createsProjectAndAudit() {
        Project result = service.createProject(new CreateProject("shop", "Shop"), "alice");
        assertEquals(1L, result.getId());
        assertEquals(ResourceStatus.ACTIVE, result.getStatus());
        assertNotNull(result.getCreatedAt());
        ArgumentCaptor<AuditLog> capture = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).insert(capture.capture());
        AuditLog audit = capture.getValue();
        assertEquals("PROJECT_CREATED", audit.getOperation());
        assertEquals("alice", audit.getOperatorName());
        assertEquals(1L, audit.getProjectId());
        assertNull(audit.getBeforeJson());
        assertEquals("shop", json.readTree(audit.getAfterJson()).get("projectKey").asText());
    }
    @Test void readsProject() { assertSame(project, service.getProject("shop")); }
    @Test void validatesProjectKeyBeforeQuery() {
        assertThrows(BusinessException.class, () -> service.getProject("SHOP"));
        verify(projects, never()).findByKey("SHOP");
    }
    @Test void rejectsMissingProject() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.getProject("missing")).getStatus());
    }
    @Test void createsEnvironmentInProject() {
        Environment result = service.createEnvironment("shop", new CreateEnvironment("prod", "Production"), "alice");
        assertEquals(1L, result.getProjectId());
        assertEquals(ResourceStatus.ACTIVE, result.getStatus());
        verify(audits).insert(argThat(a -> a.getOperation().equals("ENVIRONMENT_CREATED")
                && a.getEnvironmentId() == 2L));
    }
    @Test void listsOnlyProjectEnvironments() {
        when(environments.listByProject(1)).thenReturn(List.of(environment));
        assertEquals(List.of(environment), service.listEnvironments("shop"));
    }
    @Test void createsFlagWithTypedInitialVariantsAndSnapshot() {
        FeatureFlag result = service.createFlag("shop", new CreateFlag("pay", "Payment", FlagValueType.BOOLEAN,
                List.of(new CreateVariant("old", json.readTree("false")),
                        new CreateVariant("new", json.readTree("true")))), "alice");
        assertEquals(3L, result.getId());
        ArgumentCaptor<FlagVariant> capture = ArgumentCaptor.forClass(FlagVariant.class);
        verify(variants, times(2)).insert(capture.capture());
        assertEquals(List.of("false", "true"), capture.getAllValues().stream().map(FlagVariant::getValueJson).toList());
        verify(audits).insert(argThat(a -> a.getOperation().equals("FLAG_CREATED")
                && json.readTree(a.getAfterJson()).get("variants").size() == 2));
    }
    @Test void permitsFlagWithoutInitialVariants() {
        service.createFlag("shop", new CreateFlag("pay", "Pay", FlagValueType.JSON, null), "alice");
        verify(flags).insert(any());
        verify(variants, never()).insert(any());
    }
    @Test void validatesAllInitialVariantsBeforeWriting() {
        assertThrows(BusinessException.class, () -> service.createFlag("shop",
                new CreateFlag("pay", "Pay", FlagValueType.BOOLEAN,
                        List.of(new CreateVariant("old", json.readTree("false")),
                                new CreateVariant("new", json.readTree("\"true\"")))), "alice"));
        verify(flags, never()).insert(any());
        verify(variants, never()).insert(any());
        verify(audits, never()).insert(any());
    }
    @Test void rejectsDuplicateInitialVariantKeysBeforeWriting() {
        BusinessException error = assertThrows(BusinessException.class, () -> service.createFlag("shop",
                new CreateFlag("pay", "Pay", FlagValueType.BOOLEAN,
                        List.of(new CreateVariant("duplicate", json.readTree("false")),
                                new CreateVariant("duplicate", json.readTree("true")))), "alice"));
        assertEquals(400, error.getStatus());
        assertEquals("validation_error", error.getCode());
        assertEquals("duplicate variantKey: duplicate", error.getMessage());
        verify(flags, never()).insert(any());
        verify(variants, never()).insert(any());
        verify(audits, never()).insert(any());
    }
    @Test void rejectsInvalidNestedVariantKey() {
        assertThrows(BusinessException.class, () -> service.createFlag("shop",
                new CreateFlag("pay", "Pay", FlagValueType.BOOLEAN,
                        List.of(new CreateVariant("BAD", json.readTree("false")))), "alice"));
        verify(flags, never()).insert(any());
    }
    @Test void readsFlagWithinProject() { assertSame(flag, service.getFlag("shop", "pay")); }
    @Test void rejectsMissingFlag() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.getFlag("shop", "missing")).getStatus());
    }
    @Test void createsVariantWithExistingFlagType() {
        FlagVariant result = service.createVariant("shop", "pay", new CreateVariant("new", json.readTree("true")), "alice");
        assertEquals(3L, result.getFlagId());
        assertEquals("true", result.getValueJson());
        verify(audits).insert(argThat(a -> a.getOperation().equals("VARIANT_CREATED")));
    }
    @Test void rejectsWrongTypeWhenAddingVariant() {
        assertThrows(BusinessException.class, () -> service.createVariant("shop", "pay",
                new CreateVariant("new", json.readTree("1")), "alice"));
        verify(variants, never()).insert(any());
    }
    @Test void listsVariantsForResolvedFlag() {
        when(variants.listByFlag(3)).thenReturn(List.of(variant));
        assertEquals(List.of(variant), service.listVariants("shop", "pay"));
    }
    @Test void duplicateProjectDoesNotWriteAudit() {
        doThrow(new DuplicateKeyException("sql details")).when(projects).insert(any());
        assertThrows(DuplicateKeyException.class, () -> service.createProject(new CreateProject("shop", "Shop"), "alice"));
        verify(audits, never()).insert(any());
    }
    @Test void duplicateEnvironmentDoesNotWriteAudit() {
        doThrow(new DuplicateKeyException("duplicate")).when(environments).insert(any());
        assertThrows(DuplicateKeyException.class, () -> service.createEnvironment("shop", new CreateEnvironment("prod", "Prod"), "alice"));
        verify(audits, never()).insert(any());
    }
    @Test void duplicateFlagDoesNotWriteVariantsOrAudit() {
        doThrow(new DuplicateKeyException("duplicate")).when(flags).insert(any());
        assertThrows(DuplicateKeyException.class, () -> service.createFlag("shop",
                new CreateFlag("pay", "Pay", FlagValueType.BOOLEAN, List.of()), "alice"));
        verify(variants, never()).insert(any());
        verify(audits, never()).insert(any());
    }
    @Test void duplicateVariantDoesNotWriteAudit() {
        doThrow(new DuplicateKeyException("duplicate")).when(variants).insert(any());
        assertThrows(DuplicateKeyException.class, () -> service.createVariant("shop", "pay",
                new CreateVariant("old", json.readTree("false")), "alice"));
        verify(audits, never()).insert(any());
    }
    @Test void createsDisabledConfigAtVersionZero() {
        FlagEnvironmentConfig result = service.createConfig("shop", "prod", "pay", new CreateConfig("old", false), "alice");
        assertEquals(0, result.getVersion());
        assertFalse(result.isEnabled());
        assertEquals(4L, result.getDefaultVariantId());
        assertEquals(2L, result.getEnvironmentId());
        verify(audits).insert(argThat(a -> a.getOperation().equals("CONFIG_CREATED")));
    }
    @Test void duplicateConfigDoesNotWriteAudit() {
        doThrow(new DuplicateKeyException("duplicate")).when(configs).insert(any());
        assertThrows(DuplicateKeyException.class, () -> service.createConfig("shop", "prod", "pay",
                new CreateConfig("old", false), "alice"));
        verify(audits, never()).insert(any());
    }
    @Test void getsConfig() { assertSame(config, service.getConfig("shop", "prod", "pay")); }
    @Test void rejectsMissingConfig() {
        when(configs.find(3, 2)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> service.getConfig("shop", "prod", "pay")).getStatus());
    }
    @Test void rejectsMissingEnvironment() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.getConfig("shop", "missing", "pay")).getStatus());
    }
    @Test void rejectsMissingVariant() {
        assertEquals(404, assertThrows(BusinessException.class, () -> service.createConfig("shop", "prod", "pay",
                new CreateConfig("missing", false), "alice")).getStatus());
        verify(configs, never()).insert(any());
    }
    @Test void rejectsEnvironmentFromAnotherProject() {
        environment.setProjectId(99L);
        assertThrows(BusinessException.class, () -> service.getConfig("shop", "prod", "pay"));
        verify(configs, never()).find(anyLong(), anyLong());
    }
    @Test void rejectsFlagFromAnotherProject() {
        flag.setProjectId(99L);
        assertThrows(BusinessException.class, () -> service.getConfig("shop", "prod", "pay"));
        verify(configs, never()).find(anyLong(), anyLong());
    }
    @Test void rejectsVariantFromAnotherFlag() {
        variant.setFlagId(99L);
        assertThrows(BusinessException.class, () -> service.createConfig("shop", "prod", "pay",
                new CreateConfig("old", false), "alice"));
        verify(configs, never()).insert(any());
    }
    @Test void updatesConfigWithVersionAndBeforeAfterAudit() {
        FlagEnvironmentConfig result = service.updateConfig("shop", "prod", "pay", new UpdateConfig("old", true, 0L), "alice");
        assertEquals(1, result.getVersion());
        assertTrue(result.isEnabled());
        verify(configs).update(any(), eq(0L));
        ArgumentCaptor<AuditLog> capture = ArgumentCaptor.forClass(AuditLog.class);
        verify(audits).insert(capture.capture());
        assertEquals("CONFIG_UPDATED", capture.getValue().getOperation());
        assertFalse(json.readTree(capture.getValue().getBeforeJson()).get("enabled").asBoolean());
        assertTrue(json.readTree(capture.getValue().getAfterJson()).get("enabled").asBoolean());
        assertEquals(0, json.readTree(capture.getValue().getBeforeJson()).get("version").asLong());
        assertEquals(1, json.readTree(capture.getValue().getAfterJson()).get("version").asLong());
    }
    @Test void detectsConcurrentSqlConflictAndDoesNotAudit() {
        when(configs.update(any(), eq(0L))).thenReturn(0);
        assertThrows(OptimisticLockConflictException.class, () -> service.updateConfig("shop", "prod", "pay",
                new UpdateConfig("old", true, 0L), "alice"));
        verify(audits, never()).insert(any());
    }
    @Test void rejectsStaleUpdateBeforeChangingData() {
        config.setVersion(1);
        assertThrows(OptimisticLockConflictException.class, () -> service.updateConfig("shop", "prod", "pay",
                new UpdateConfig("old", true, 0L), "alice"));
        verify(configs, never()).update(any(), anyLong());
        assertFalse(config.isEnabled());
    }
    @Test void enablesAndPreservesDefaultVariant() {
        FlagEnvironmentConfig result = service.setEnabled("shop", "prod", "pay", new SwitchFlag(0L), true, "alice");
        assertTrue(result.isEnabled()); assertEquals(1, result.getVersion()); assertEquals(4L, result.getDefaultVariantId());
        verify(audits).insert(argThat(a -> a.getOperation().equals("FLAG_ENABLED")));
    }
    @Test void disablesUsingNextVersion() {
        config.setEnabled(true); config.setVersion(1);
        FlagEnvironmentConfig result = service.setEnabled("shop", "prod", "pay", new SwitchFlag(1L), false, "alice");
        assertFalse(result.isEnabled()); assertEquals(2, result.getVersion());
        verify(configs).update(any(), eq(1L));
        verify(audits).insert(argThat(a -> a.getOperation().equals("FLAG_DISABLED")));
    }
    @Test void rejectsStaleEnable() {
        config.setVersion(1);
        assertThrows(OptimisticLockConflictException.class, () -> service.setEnabled("shop", "prod", "pay",
                new SwitchFlag(0L), true, "alice"));
        verify(audits, never()).insert(any());
    }
    @Test void rejectsStaleDisable() {
        config.setVersion(2);
        assertThrows(OptimisticLockConflictException.class, () -> service.setEnabled("shop", "prod", "pay",
                new SwitchFlag(0L), false, "alice"));
        verify(audits, never()).insert(any());
    }
    @Test void switchAlsoChecksSqlAffectedRows() {
        when(configs.update(any(), anyLong())).thenReturn(0);
        assertThrows(OptimisticLockConflictException.class, () -> service.setEnabled("shop", "prod", "pay",
                new SwitchFlag(0L), false, "alice"));
        verify(audits, never()).insert(any());
    }
    @Test void repeatedSameStateStillConsumesVersion() {
        assertEquals(1, service.setEnabled("shop", "prod", "pay", new SwitchFlag(0L), false, "alice").getVersion());
    }
    @Test void rejectsMissingExpectedVersion() {
        assertThrows(BusinessException.class, () -> service.setEnabled("shop", "prod", "pay",
                new SwitchFlag(null), true, "alice"));
        verify(configs, never()).update(any(), anyLong());
    }
    @Test void rejectsNegativeExpectedVersion() {
        assertThrows(BusinessException.class, () -> service.updateConfig("shop", "prod", "pay",
                new UpdateConfig("old", true, -1L), "alice"));
    }
    @Test void rejectsBlankOperatorBeforeWrites() {
        assertThrows(BusinessException.class, () -> service.createProject(new CreateProject("shop", "Shop"), " "));
        verify(projects, never()).insert(any());
    }
    @Test void listsAuditsWithinProjectAndPage() {
        AuditLog audit = new AuditLog();
        when(audits.list(1, 10, 20)).thenReturn(List.of(audit));
        assertEquals(List.of(audit), service.listAudits("shop", 10, 20));
    }
    @Test void rejectsOversizedAuditPage() {
        assertThrows(BusinessException.class, () -> service.listAudits("shop", 101, 0));
    }
    @Test void rejectsNegativeAuditOffset() {
        assertThrows(BusinessException.class, () -> service.listAudits("shop", 10, -1));
    }
}
