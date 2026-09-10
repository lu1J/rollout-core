package io.github.lu1j.rolloutcore.server.service;

import io.github.lu1j.rolloutcore.domain.FlagValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class InputRulesTest {
    private final JsonMapper json = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"checkout-service", "prod_1", "flag.v2", "123", "a"})
    void acceptsStableKeys(String key) { assertDoesNotThrow(() -> InputRules.key(key)); }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"UPPER", "has space", "foo/bar", "中文", "a@", " a", "a\n"})
    void rejectsInvalidKeysWithoutNormalization(String key) {
        BusinessException exception = assertThrows(BusinessException.class, () -> InputRules.key(key));
        assertEquals("validation_error", exception.getCode());
        assertEquals(400, exception.getStatus());
    }

    @Test void rejectsOverlongKey() {
        assertThrows(BusinessException.class, () -> InputRules.key("a".repeat(101)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"BOOLEAN|true", "BOOLEAN|false", "STRING|\"hello\"",
            "STRING|\"\"", "NUMBER|12", "NUMBER|-1.25", "JSON|{}", "JSON|[]", "JSON|{\"nested\":[1,true]}"})
    void acceptsOnlyMatchingJsonKinds(FlagValueType type, String value) {
        assertDoesNotThrow(() -> InputRules.value(type, json.readTree(value)));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"BOOLEAN|\"true\"", "BOOLEAN|1", "STRING|true", "STRING|{}",
            "NUMBER|\"12\"", "NUMBER|false", "JSON|\"text\"", "JSON|1", "JSON|false",
            "BOOLEAN|null", "STRING|null", "NUMBER|null", "JSON|null"})
    void rejectsWrongJsonKinds(FlagValueType type, String value) {
        assertEquals("validation_error", assertThrows(BusinessException.class,
                () -> InputRules.value(type, json.readTree(value))).getCode());
    }

    @Test void rejectsMissingNode() {
        assertThrows(BusinessException.class, () -> InputRules.value(FlagValueType.JSON, null));
    }
    @Test void rejectsMissingType() {
        assertThrows(BusinessException.class, () -> InputRules.value(null, json.readTree("true")));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "\t"})
    void requiresOperator(String operator) {
        assertThrows(BusinessException.class, () -> InputRules.operator(operator));
    }
    @Test void rejectsLongOperator() {
        assertThrows(BusinessException.class, () -> InputRules.operator("a".repeat(101)));
    }
}
