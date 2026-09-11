package io.github.lu1j.rolloutcore.server.evaluation;

import tools.jackson.databind.JsonNode;
import java.util.List;

/** Complete environment/flag policy. Null lists mean absent rules/rollout. */
public record EvaluationPolicy(List<TargetingRule> rules, List<RolloutAllocation> rollout) {
    public enum MatchType { ALL, ANY }
    public enum Operator { EQ, NEQ, IN, NOT_IN, GT, GTE, LT, LTE, CONTAINS }
    public record TargetingRule(Integer priority, MatchType match, List<RuleCondition> conditions, String variantKey) {}
    public record RuleCondition(String attribute, Operator operator, JsonNode value) {}
    public record RolloutAllocation(String variantKey, Integer weight) {}
}
