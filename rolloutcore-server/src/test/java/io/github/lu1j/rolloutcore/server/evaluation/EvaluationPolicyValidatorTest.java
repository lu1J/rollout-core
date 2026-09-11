package io.github.lu1j.rolloutcore.server.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import io.github.lu1j.rolloutcore.server.service.BusinessException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class EvaluationPolicyValidatorTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final EvaluationPolicyValidator validator = new EvaluationPolicyValidator();
    private void validate(String document) {
        validator.validate(json.readValue(document, EvaluationPolicy.class), Set.of("old", "new"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"rollout\":[]}",
            "{\"rollout\":[{\"variantKey\":\"new\",\"weight\":9999}]}",
            "{\"rollout\":[{\"variantKey\":\"new\",\"weight\":-1},{\"variantKey\":\"old\",\"weight\":10001}]}",
            "{\"rollout\":[{\"variantKey\":\"unknown\",\"weight\":10000}]}",
            "{\"rollout\":[{\"variantKey\":\"new\",\"weight\":5000},{\"variantKey\":\"new\",\"weight\":5000}]}",
            "{\"rollout\":[null]}", "{\"rollout\":[{\"variantKey\":\"new\"}]}",
            "{\"rules\":[null]}", "{\"rules\":[{\"priority\":0,\"match\":\"ALL\",\"variantKey\":\"new\",\"conditions\":[]}]}"})
    void invalidPolicyRejected(String document) {
        assertThrows(BusinessException.class, () -> validate(document));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"attribute\":\"x\",\"operator\":\"GT\",\"value\":\"3\"}",
            "{\"attribute\":\"x\",\"operator\":\"CONTAINS\",\"value\":3}",
            "{\"attribute\":\"x\",\"operator\":\"IN\",\"value\":3}",
            "{\"attribute\":\"x\",\"operator\":\"NOT_IN\",\"value\":[]}",
            "{\"attribute\":\"x\",\"operator\":\"IN\",\"value\":[3,\"3\"]}",
            "{\"attribute\":\"x\",\"operator\":\"EQ\",\"value\":{}}",
            "{\"attribute\":\"appVersion\",\"operator\":\"GT\",\"value\":3}",
            "{\"attribute\":\"appVersion\",\"operator\":\"CONTAINS\",\"value\":\"2\"}",
            "{\"attribute\":\"vipLevel\",\"operator\":\"EQ\",\"value\":\"3\"}",
            "{\"attribute\":\"country\",\"operator\":\"EQ\",\"value\":3}",
            "{\"attribute\":\"x()\",\"operator\":\"EQ\",\"value\":true}",
            "{\"attribute\":\"x\",\"value\":true}",
            "{\"attribute\":\"x\",\"operator\":\"EQ\"}"})
    void incompatibleConditionRejected(String condition) {
        assertThrows(BusinessException.class, () -> validate("{\"rules\":[{\"priority\":1,\"match\":\"ALL\",\"variantKey\":\"new\",\"conditions\":[" + condition + "]}]}"));
    }

    @Test void prioritiesVariantsAndBoundsValidated() {
        var condition = new RuleCondition("country", Operator.EQ, json.readTree("\"JP\""));
        var rule = new TargetingRule(10, MatchType.ALL, List.of(condition), "new");
        assertThrows(BusinessException.class, () -> validator.validate(new EvaluationPolicy(List.of(rule, rule), null), Set.of("new")));
        assertThrows(BusinessException.class, () -> validator.validate(new EvaluationPolicy(List.of(rule), null), Set.of("old")));
        assertThrows(BusinessException.class, () -> validator.validate(new EvaluationPolicy(Collections.nCopies(101, rule), null), Set.of("new")));
        var tooMany = new TargetingRule(1, MatchType.ALL, Collections.nCopies(21, condition), "new");
        assertThrows(BusinessException.class, () -> validator.validate(new EvaluationPolicy(List.of(tooMany), null), Set.of("new")));
        var membership = new RuleCondition("x", Operator.IN, json.valueToTree(Collections.nCopies(101, "x")));
        assertThrows(BusinessException.class, () -> validator.validate(new EvaluationPolicy(List.of(new TargetingRule(1, MatchType.ANY, List.of(membership), "new")), null), Set.of("new")));
    }

    @Test void absentPolicyListsAndZeroWeightAndNullScalarSupported() {
        validate("{}");
        validate("{\"rules\":[],\"rollout\":[{\"variantKey\":\"new\",\"weight\":0},{\"variantKey\":\"old\",\"weight\":10000}]}");
        validate("{\"rules\":[{\"priority\":0,\"match\":\"ANY\",\"variantKey\":\"new\",\"conditions\":[{\"attribute\":\"x\",\"operator\":\"EQ\",\"value\":null}]}]}");
    }
}
