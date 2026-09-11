package io.github.lu1j.rolloutcore.server.evaluation;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy.*;

/** Interprets validated data only; no expressions, coercion, reflection or executable code. */
@Component
public class RuleEngine {
    public Optional<TargetingRule> firstMatch(List<TargetingRule> rules, EvaluationContext context) {
        if (rules == null) return Optional.empty();
        return rules.stream().sorted(Comparator.comparingInt(TargetingRule::priority))
                .filter(rule -> rule.match() == MatchType.ALL
                        ? rule.conditions().stream().allMatch(c -> matches(c, context))
                        : rule.conditions().stream().anyMatch(c -> matches(c, context)))
                .findFirst();
    }

    public boolean matches(RuleCondition condition, EvaluationContext context) {
        JsonNode actual = context.resolve(condition.attribute());
        JsonNode expected = condition.value();
        // Missing and incompatible values never match, including negative operators.
        if (actual == null || !scalar(actual)) return false;
        return switch (condition.operator()) {
            case EQ -> compatible(actual, expected) && equal(actual, expected);
            case NEQ -> compatible(actual, expected) && !equal(actual, expected);
            case IN, NOT_IN -> {
                boolean compatible = false;
                boolean found = false;
                if (expected != null && expected.isArray()) {
                    for (JsonNode item : expected) {
                        compatible |= compatible(actual, item);
                        found |= compatible(actual, item) && equal(actual, item);
                    }
                }
                yield compatible && (condition.operator() == Operator.IN ? found : !found);
            }
            case GT, GTE, LT, LTE -> {
                if (expected == null || !actual.isNumber() || !expected.isNumber()) yield false;
                int comparison = actual.decimalValue().compareTo(expected.decimalValue());
                yield switch (condition.operator()) {
                    case GT -> comparison > 0;
                    case GTE -> comparison >= 0;
                    case LT -> comparison < 0;
                    default -> comparison <= 0;
                };
            }
            case CONTAINS -> expected != null && actual.isTextual() && expected.isTextual()
                    && actual.asString().contains(expected.asString());
        };
    }

    static boolean scalar(JsonNode value) {
        return value != null && (value.isNull() || value.isTextual() || value.isNumber() || value.isBoolean());
    }
    static boolean compatible(JsonNode a, JsonNode b) {
        return scalar(b) && (a.isNull() && b.isNull() || a.isNumber() && b.isNumber()
                || a.isTextual() && b.isTextual() || a.isBoolean() && b.isBoolean());
    }
    private static boolean equal(JsonNode a, JsonNode b) {
        return a.isNumber() && b.isNumber() ? a.decimalValue().compareTo(b.decimalValue()) == 0 : a.equals(b);
    }
}
