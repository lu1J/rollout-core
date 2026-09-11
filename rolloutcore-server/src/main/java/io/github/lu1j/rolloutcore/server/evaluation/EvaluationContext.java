package io.github.lu1j.rolloutcore.server.evaluation;

import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import java.math.BigDecimal;
import java.util.Map;

public record EvaluationContext(
        @NotBlank @Size(max = 256) @Pattern(regexp = "[^\\x00]*") String userId,
        @Size(max = 256) String country,
        BigDecimal vipLevel,
        @Size(max = 256) String appVersion,
        @Size(max = 100) Map<@NotNull @Pattern(regexp = "[a-zA-Z][a-zA-Z0-9_.-]{0,99}") String, JsonNode> attributes) {
    /** Built-in names are reserved, even when their values are absent. No dotted-path traversal. */
    public JsonNode resolve(String attribute) {
        var nodes = JsonNodeFactory.instance;
        return switch (attribute) {
            case "userId" -> userId == null ? null : nodes.stringNode(userId);
            case "country" -> country == null ? null : nodes.stringNode(country);
            case "vipLevel" -> vipLevel == null ? null : nodes.numberNode(vipLevel);
            case "appVersion" -> appVersion == null ? null : nodes.stringNode(appVersion);
            default -> attributes == null ? null : attributes.get(attribute);
        };
    }
}
