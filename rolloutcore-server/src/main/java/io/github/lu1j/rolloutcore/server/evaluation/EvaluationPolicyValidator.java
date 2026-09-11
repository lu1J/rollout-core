package io.github.lu1j.rolloutcore.server.evaluation;

import io.github.lu1j.rolloutcore.server.service.BusinessException;
import io.github.lu1j.rolloutcore.server.service.InputRules;
import java.util.HashSet;
import java.util.Set;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationPolicy.*;

/** Bounded policy grammar validated before any persistence. */
public class EvaluationPolicyValidator {
    public void validate(EvaluationPolicy policy, Set<String> variantKeys) {
        require(policy != null, "Policy is required");
        if (policy.rules() != null) {
            require(policy.rules().size() <= 100, "At most 100 rules");
            Set<Integer> priorities = new HashSet<>();
            for (var rule : policy.rules()) {
                require(rule != null && rule.priority() != null && rule.priority() >= 0, "Non-negative priority required");
                require(priorities.add(rule.priority()), "Duplicate priority");
                variant(rule.variantKey(), variantKeys);
                require(rule.match() != null, "Match type required");
                require(rule.conditions() != null && !rule.conditions().isEmpty() && rule.conditions().size() <= 20,
                        "Each rule requires 1-20 conditions");
                rule.conditions().forEach(this::condition);
            }
        }
        if (policy.rollout() != null) {
            require(!policy.rollout().isEmpty() && policy.rollout().size() <= 100, "Rollout requires 1-100 allocations");
            Set<String> allocated = new HashSet<>();
            int total = 0;
            for (var allocation : policy.rollout()) {
                require(allocation != null && allocation.weight() != null
                        && allocation.weight() >= 0 && allocation.weight() <= 10000, "Weight must be 0-10000");
                variant(allocation.variantKey(), variantKeys);
                require(allocated.add(allocation.variantKey()), "Duplicate rollout variant");
                total += allocation.weight();
            }
            require(total == 10000, "Rollout weights must sum to 10000");
        }
    }

    private void condition(RuleCondition c) {
        require(c != null && c.attribute() != null && c.attribute().matches("[a-zA-Z][a-zA-Z0-9_.-]{0,99}"),
                "Invalid condition attribute");
        require(c.operator() != null && c.value() != null, "Operator and value required");
        var value = c.value();
        switch (c.operator()) {
            case EQ, NEQ -> require(RuleEngine.scalar(value), "EQ/NEQ require a JSON scalar");
            case GT, GTE, LT, LTE -> require(value.isNumber(), "Ordered comparison requires a number");
            case CONTAINS -> require(value.isTextual(), "CONTAINS requires a string");
            case IN, NOT_IN -> {
                require(value.isArray() && !value.isEmpty() && value.size() <= 100, "IN/NOT_IN require 1-100 scalars");
                for (var item : value) require(RuleEngine.scalar(item)
                        && RuleEngine.compatible(value.get(0), item), "Membership values must be homogeneous scalars");
            }
        }
        if (c.attribute().equals("appVersion")) {
            require(c.operator() == Operator.EQ || c.operator() == Operator.NEQ
                    || c.operator() == Operator.IN || c.operator() == Operator.NOT_IN,
                    "appVersion supports exact matching only");
        }
        var sample = value.isArray() ? value.get(0) : value;
        switch (c.attribute()) {
            case "userId", "country", "appVersion" -> require(sample.isTextual(), "Built-in string attribute requires string values");
            case "vipLevel" -> require(sample.isNumber(), "vipLevel requires numeric values");
            default -> { }
        }
        for (var item : value.isArray() ? value : java.util.List.of(value)) {
            require(!item.isTextual() || item.asString().length() <= 1024, "Rule string exceeds 1024 characters");
        }
    }
    private static void variant(String key, Set<String> keys) {
        InputRules.key(key);
        require(keys.contains(key), "Variant must belong to the current Flag");
    }
    private static void require(boolean valid, String message) {
        if (!valid) throw BusinessException.validation(message);
    }
}
