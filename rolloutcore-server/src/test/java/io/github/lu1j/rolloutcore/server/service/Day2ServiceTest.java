package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.*;
import io.github.lu1j.rolloutcore.server.evaluation.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.math.BigDecimal;
import java.util.List;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.*;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Day2ServiceTest extends ServiceFixture {
    private EvaluationService evaluation;
    private ValidatorFactory factory;
    private StableBucketService buckets;
    private FlagVariant newVariant;

    @BeforeEach void evaluationFixture() {
        factory = Validation.buildDefaultValidatorFactory();
        buckets = spy(new StableBucketService());
        evaluation = new EvaluationService(service, new InputRules(factory.getValidator()), new RuleEngine(), buckets, json);
        newVariant = new FlagVariant();
        newVariant.setId(6L); newVariant.setFlagId(3L); newVariant.setVariantKey("new"); newVariant.setValueJson("true");
        when(variants.listByFlag(3)).thenReturn(List.of(variant, newVariant));
        when(configs.updatePolicy(any(), anyLong())).thenReturn(1);
    }
    @AfterEach void close() { factory.close(); }

    private EvaluateRequest request(String country) {
        return new EvaluateRequest("shop", "prod", "pay", new EvaluationContext("user-123", country, new BigDecimal("5"), "2.3.1", null));
    }
    private EvaluationPolicy policy() {
        return new EvaluationPolicy(List.of(new TargetingRule(10, MatchType.ALL,
                List.of(new RuleCondition("country", Operator.EQ, json.readTree("\"JP\"")),
                        new RuleCondition("vipLevel", Operator.GTE, json.readTree("3"))), "new")),
                List.of(new RolloutAllocation("new", 1000), new RolloutAllocation("old", 9000)));
    }
    private UpdatePolicy update(long version) { return new UpdatePolicy(version, policy().rules(), policy().rollout()); }
    private void enabledPolicy() { config.setEnabled(true); config.setEvaluationPolicyJson(json.writeValueAsString(policy())); }

    @Test void disabledOverridesBothRuleAndRollout() {
        config.setEvaluationPolicyJson(json.writeValueAsString(policy()));
        var result = evaluation.evaluate(request("JP"));
        assertEquals(Reason.DISABLED, result.reason());
        assertEquals("old", result.variantKey()); assertFalse(result.value().asBoolean());
        assertNull(result.bucket()); assertNull(result.matchedRulePriority());
        verifyNoInteractions(buckets);
    }
    @Test void matchingRuleOverridesRolloutAndExplainsPriority() {
        enabledPolicy(); config.setVersion(3);
        var result = evaluation.evaluate(request("JP"));
        assertEquals(Reason.RULE_MATCH, result.reason()); assertEquals("new", result.variantKey());
        assertTrue(result.value().asBoolean()); assertEquals(10, result.matchedRulePriority());
        assertEquals(3, result.configVersion()); assertNull(result.bucket()); verifyNoInteractions(buckets);
    }
    @Test void ordinaryUserRepeatedlyGetsSameBucketAndVariant() {
        enabledPolicy();
        var expected = evaluation.evaluate(request("US"));
        assertEquals(Reason.PERCENTAGE_ROLLOUT, expected.reason()); assertEquals(2750, expected.bucket());
        assertEquals("old", expected.variantKey()); assertNull(expected.matchedRulePriority());
        for (int i = 0; i < 50; i++) assertEquals(expected, evaluation.evaluate(request("US")));
    }
    @Test void cumulativeIntervalsAreHalfOpenAndZeroWeightNeverWins() {
        config.setEnabled(true);
        config.setEvaluationPolicyJson(json.writeValueAsString(new EvaluationPolicy(null, List.of(
                new RolloutAllocation("new", 1000), new RolloutAllocation("old", 9000)))));
        for (int bucket : new int[]{0, 999, 1000, 9999}) {
            doReturn(bucket).when(buckets).bucket(anyString(), anyString(), anyString(), anyString());
            var result = evaluation.evaluate(request("US"));
            assertEquals(bucket < 1000 ? "new" : "old", result.variantKey());
            assertEquals(bucket, result.bucket());
        }
        config.setEvaluationPolicyJson(json.writeValueAsString(new EvaluationPolicy(null,
                List.of(new RolloutAllocation("new", 0), new RolloutAllocation("old", 10000)))));
        doReturn(0).when(buckets).bucket(anyString(), anyString(), anyString(), anyString());
        assertEquals("old", evaluation.evaluate(request("US")).variantKey());
    }
    @ParameterizedTest
    @ValueSource(strings = {"false", "\"hello\"", "123.45", "{\"nested\":[1,true]}", "[1,2]"})
    void defaultReturnsEverySupportedJsonValueWithoutStringEncoding(String value) {
        config.setEnabled(true); variant.setValueJson(value);
        var result = evaluation.evaluate(request("US"));
        assertEquals(Reason.DEFAULT, result.reason()); assertEquals(json.readTree(value), result.value());
        assertNull(result.bucket()); assertNull(result.matchedRulePriority());
    }
    @Test void noMatchingRuleAndNoRolloutReturnsDefault() {
        config.setEnabled(true); config.setEvaluationPolicyJson(json.writeValueAsString(new EvaluationPolicy(policy().rules(), null)));
        assertEquals(Reason.DEFAULT, evaluation.evaluate(request("US")).reason());
    }
    @Test void missingResourcesRemainNotFoundAndInvalidContextNeverReads() {
        assertThrows(BusinessException.class, () -> evaluation.evaluate(new EvaluateRequest("shop", "prod", "pay",
                new EvaluationContext(" ", null, null, null, null))));
        verify(configs, never()).find(anyLong(), anyLong());
        when(configs.find(3, 2)).thenReturn(null);
        assertEquals(404, assertThrows(BusinessException.class, () -> evaluation.evaluate(request("US"))).getStatus());
    }
    @Test void policyUpdateIncrementsVersionPreservesSettingsAndAuditsParsedSnapshots() {
        var result = service.updatePolicy("shop", "prod", "pay", update(0), "alice");
        assertEquals(1, result.version()); assertEquals(policy(), result.policy());
        assertFalse(config.isEnabled()); assertEquals(4L, config.getDefaultVariantId());
        verify(configs).updatePolicy(config, 0); verify(configs, never()).update(any(), anyLong());
        var capture = ArgumentCaptor.forClass(AuditLog.class); verify(audits).insert(capture.capture());
        var audit = capture.getValue(); assertEquals("EVALUATION_POLICY_UPDATED", audit.getOperation());
        assertEquals(0, json.readTree(audit.getBeforeJson()).get("configVersion").asInt());
        assertTrue(json.readTree(audit.getBeforeJson()).get("evaluationPolicy").isNull());
        assertEquals(1, json.readTree(audit.getAfterJson()).get("configVersion").asInt());
        assertEquals(10, json.readTree(audit.getAfterJson()).at("/evaluationPolicy/rules/0/priority").asInt());
    }
    @Test void stalePolicyDoesNotOverwriteOrAudit() {
        enabledPolicy(); config.setVersion(2); String before = config.getEvaluationPolicyJson();
        assertThrows(OptimisticLockConflictException.class, () -> service.updatePolicy("shop", "prod", "pay", update(0), "alice"));
        assertEquals(before, config.getEvaluationPolicyJson()); assertEquals(2, config.getVersion());
        verify(configs, never()).updatePolicy(any(), anyLong()); verifyNoInteractions(audits);
    }
    @Test void concurrentPolicyWriterLosingConditionalUpdateDoesNotAudit() {
        when(configs.updatePolicy(any(), anyLong())).thenReturn(0);
        assertThrows(OptimisticLockConflictException.class, () -> service.updatePolicy("shop", "prod", "pay", update(0), "alice"));
        verifyNoInteractions(audits);
    }
    @Test void invalidPolicyDoesNotWriteOrAudit() {
        var invalid = new UpdatePolicy(0L, null, List.of(new RolloutAllocation("missing", 10000)));
        assertEquals(400, assertThrows(BusinessException.class, () -> service.updatePolicy("shop", "prod", "pay", invalid, "alice")).getStatus());
        verify(configs, never()).updatePolicy(any(), anyLong()); verifyNoInteractions(audits);
    }
    @Test void policyAndDay1SwitchShareVersionAndOrdinaryUpdatePreservesPolicy() {
        service.updatePolicy("shop", "prod", "pay", update(0), "alice");
        String policyJson = config.getEvaluationPolicyJson();
        service.setEnabled("shop", "prod", "pay", new Commands.SwitchFlag(1L), true, "alice");
        assertEquals(2, config.getVersion()); assertEquals(policyJson, config.getEvaluationPolicyJson());
        service.updateConfig("shop", "prod", "pay", new Commands.UpdateConfig("old", false, 2L), "alice");
        assertEquals(3, config.getVersion()); assertEquals(policyJson, config.getEvaluationPolicyJson());
        assertThrows(OptimisticLockConflictException.class, () -> service.updatePolicy("shop", "prod", "pay", update(1), "alice"));
    }
    @Test void clearingPolicyIsVersionedAndAuditContainsPreviousPolicy() {
        enabledPolicy();
        service.updatePolicy("shop", "prod", "pay", new UpdatePolicy(0L, null, null), "alice");
        assertEquals(Reason.DEFAULT, evaluation.evaluate(request("JP")).reason());
        var capture = ArgumentCaptor.forClass(AuditLog.class); verify(audits).insert(capture.capture());
        assertEquals(10, json.readTree(capture.getValue().getBeforeJson()).at("/evaluationPolicy/rules/0/priority").asInt());
    }

    @Test void evaluationReadsShareRepeatableReadTransactionAndNeverAudit() throws Exception {
        var connection = mock(java.sql.Connection.class);
        var dataSource = mock(javax.sql.DataSource.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(java.sql.Connection.TRANSACTION_READ_COMMITTED);
        var interceptor = new org.springframework.transaction.interceptor.TransactionInterceptor();
        interceptor.setTransactionManager(new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource));
        interceptor.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        interceptor.afterPropertiesSet();
        var proxy = new org.springframework.aop.framework.ProxyFactory(evaluation); proxy.addAdvice(interceptor);
        when(configs.find(3, 2)).thenAnswer(inv -> {
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return config;
        });
        when(variants.listByFlag(3)).thenAnswer(inv -> {
            assertSame(connection, org.springframework.jdbc.datasource.DataSourceUtils.getConnection(dataSource));
            return List.of(variant, newVariant);
        });
        ((EvaluationService) proxy.getProxy()).evaluate(request("US"));
        verify(connection).setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
        verify(connection).commit(); verify(connection).close(); verifyNoInteractions(audits);
    }
}
