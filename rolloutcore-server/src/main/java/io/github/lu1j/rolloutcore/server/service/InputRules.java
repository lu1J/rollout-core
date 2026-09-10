package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.FlagValueType;
import tools.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import jakarta.validation.Validator;

@Component
public class InputRules {
    private final Validator validator;
    public InputRules(Validator validator) { this.validator = validator; }

    public void command(Object command) {
        if (command == null || !validator.validate(command).isEmpty()) {
            throw BusinessException.validation("Invalid request fields");
        }
    }
    public static void key(String value) {
        if (value == null || !value.matches(Commands.KEY)) {
            throw BusinessException.validation("Keys must contain 1-100 lowercase letters, digits, '.', '_' or '-'");
        }
    }
    public static void operator(String operator) {
        if (operator == null || operator.isBlank() || operator.length() > 100) {
            throw BusinessException.validation("X-Operator must contain 1-100 characters");
        }
    }
    public static void value(FlagValueType type, JsonNode value) {
        if (type == null || value == null || value.isNull()) {
            throw BusinessException.validation("Variant value is required");
        }
        boolean valid = switch (type) {
            case BOOLEAN -> value.isBoolean();
            case STRING -> value.isTextual();
            case NUMBER -> value.isNumber();
            case JSON -> value.isObject() || value.isArray();
        };
        if (!valid) {
            throw BusinessException.validation("Variant value must match " + type);
        }
    }
}
