package io.github.lu1j.rolloutcore.server.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy.*;
import static org.junit.jupiter.api.Assertions.*;

class RuleEngineTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final RuleEngine engine = new RuleEngine();
    private final EvaluationContext context = new EvaluationContext("u", "JP", new BigDecimal("5"), "2.3.1", null);

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "EQ|5.0|5|true", "EQ|5|6|false", "NEQ|5|6|true", "NEQ|5.0|5|false",
            "EQ|true|true|true", "NEQ|true|false|true", "EQ|null|null|true",
            "IN|5|[4,5.0]|true", "IN|6|[4,5]|false", "NOT_IN|6|[4,5]|true", "NOT_IN|5|[4,5]|false",
            "GT|5|4|true", "GT|5|5|false", "GTE|5|5|true", "GTE|4|5|false",
            "LT|4|5|true", "LT|5|5|false", "LTE|5|5|true", "LTE|6|5|false",
            "CONTAINS|\"professional\"|\"pro\"|true", "CONTAINS|\"PRO\"|\"pro\"|false",
            "EQ|\"5\"|5|false", "NEQ|\"5\"|5|false", "GT|\"5\"|4|false",
            "NOT_IN|\"5\"|[4,5]|false", "CONTAINS|5|\"5\"|false",
            "NEQ|{}|5|false", "NOT_IN|[]|[1]|false", "NEQ|null|5|false"})
    void operatorsAndRuntimeTypes(Operator operator, String actual, String expected, boolean matches) {
        var ctx = new EvaluationContext("u", null, null, null, Map.of("x", json.readTree(actual)));
        assertEquals(matches, engine.matches(new RuleCondition("x", operator, json.readTree(expected)), ctx));
    }

    @Test void allAndAnyAndFirstMatchUsePriorityNotInputOrder() {
        var country = new RuleCondition("country", Operator.EQ, json.readTree("\"JP\""));
        var vip = new RuleCondition("vipLevel", Operator.GTE, json.readTree("3"));
        var miss = new RuleCondition("country", Operator.EQ, json.readTree("\"US\""));
        var all = new TargetingRule(10, MatchType.ALL, List.of(country, vip), "new");
        var any = new TargetingRule(20, MatchType.ANY, List.of(miss, vip), "old");
        assertEquals(all, engine.firstMatch(List.of(any, all), context).orElseThrow());
        assertEquals(any, engine.firstMatch(List.of(any), context).orElseThrow());
        assertTrue(engine.firstMatch(List.of(new TargetingRule(1, MatchType.ALL, List.of(country, miss), "old")), context).isEmpty());
        assertTrue(engine.firstMatch(List.of(new TargetingRule(1, MatchType.ANY, List.of(miss, miss), "old")), context).isEmpty());
    }

    @Test void missingNeverMatchesEvenNegativeOperatorsAndBuiltinsCannotBeOverridden() {
        var ctx = new EvaluationContext("u", null, null, null, Map.of("country", json.readTree("\"JP\"")));
        for (Operator op : List.of(Operator.EQ, Operator.NEQ, Operator.IN, Operator.NOT_IN)) {
            var value = json.readTree(op == Operator.IN || op == Operator.NOT_IN ? "[\"JP\"]" : "\"JP\"");
            assertFalse(engine.matches(new RuleCondition("country", op, value), ctx));
            assertFalse(engine.matches(new RuleCondition("missing", op, value), ctx));
        }
    }

    @Test void appVersionIsExactAndAttributesAreLiteralKeys() {
        assertTrue(engine.matches(new RuleCondition("appVersion", Operator.IN, json.readTree("[\"2.3.1\",\"3.0\"]")), context));
        assertFalse(engine.matches(new RuleCondition("appVersion", Operator.EQ, json.readTree("\"2.3\"")), context));
        var ctx = new EvaluationContext("u", null, null, null, Map.of("plan.tier", json.readTree("\"pro\"")));
        assertTrue(engine.matches(new RuleCondition("plan.tier", Operator.EQ, json.readTree("\"pro\"")), ctx));
    }
}
