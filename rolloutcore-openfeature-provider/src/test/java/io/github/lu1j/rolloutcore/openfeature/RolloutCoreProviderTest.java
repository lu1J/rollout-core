package io.github.lu1j.rolloutcore.openfeature;

import dev.openfeature.sdk.*;
import io.github.lu1j.rolloutcore.sdk.RolloutCoreClient;
import io.github.lu1j.rolloutcore.sdk.EvaluationResult;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.lu1j.rolloutcore.sdk.EvaluationResult.Source.*;
import io.github.lu1j.rolloutcore.sdk.EvaluationResult.Error;

class RolloutCoreProviderTest {
    final RolloutCoreClient sdk = mock(RolloutCoreClient.class);
    final RolloutCoreProvider provider = new RolloutCoreProvider(sdk,"project","prod");
    final ImmutableContext context = new ImmutableContext("user1");
    final JsonMapper json = JsonMapper.builder().build();
    <T> EvaluationResult<T> ok(T value, String reason) {
        return new EvaluationResult<>(value,REMOTE,Error.NONE,null,"new",reason,7L,1);
    }
    @Test void booleanResolutionAndMetadata() {
        when(sdk.booleanFlag(any(),any(),any(),any(),anyBoolean())).thenReturn(ok(true,"RULE_MATCH"));
        var result = provider.getBooleanEvaluation("flag",false,context);
        assertTrue(result.getValue()); assertEquals("new",result.getVariant());
        assertEquals("TARGETING_MATCH",result.getReason()); assertNull(result.getErrorCode());
        assertEquals(7L,result.getFlagMetadata().getLong("rolloutcore.configVersion"));
        assertEquals("RolloutCore",provider.getMetadata().getName());
    }
    @Test void stringResolution() {
        when(sdk.stringFlag(any(),any(),any(),any(),any())).thenReturn(ok("hello","DEFAULT"));
        assertEquals("hello",provider.getStringEvaluation("flag","safe",context).getValue());
    }
    @Test void integerAndDoubleResolution() {
        when(sdk.numberFlag(any(),any(),any(),any(),any())).thenReturn(ok(new BigDecimal("12"),"DEFAULT"));
        assertEquals(12,provider.getIntegerEvaluation("flag",0,context).getValue());
        when(sdk.numberFlag(any(),any(),any(),any(),any())).thenReturn(ok(new BigDecimal("12.25"),"DEFAULT"));
        assertEquals(12.25,provider.getDoubleEvaluation("flag",0d,context).getValue());
    }
    @ParameterizedTest @ValueSource(strings={"1.5","2147483648","-2147483649"})
    void integerNeverSilentlyTruncatesOrOverflows(String number) {
        when(sdk.numberFlag(any(),any(),any(),any(),any())).thenReturn(ok(new BigDecimal(number),"DEFAULT"));
        var r = provider.getIntegerEvaluation("flag",42,context);
        assertEquals(42,r.getValue()); assertEquals(ErrorCode.TYPE_MISMATCH,r.getErrorCode());
    }
    @Test void nonFiniteDoubleIsRejected() {
        when(sdk.numberFlag(any(),any(),any(),any(),any())).thenReturn(ok(new BigDecimal("1E400"),"DEFAULT"));
        assertEquals(ErrorCode.TYPE_MISMATCH,provider.getDoubleEvaluation("flag",0d,context).getErrorCode());
        assertEquals(ErrorCode.TYPE_MISMATCH,provider.getDoubleEvaluation("flag",Double.NaN,context).getErrorCode());
    }
    @Test void nestedObjectAndArrayResolution() {
        var value = json.readTree("{\"a\":[true,null,\"x\",3,1.5],\"b\":{}}");
        when(sdk.jsonFlag(any(),any(),any(),any(),any())).thenReturn(ok(value,"DEFAULT"));
        var result = provider.getObjectEvaluation("flag",new Value(new ImmutableStructure()),context);
        var list = result.getValue().asStructure().getValue("a").asList();
        assertTrue(list.get(0).asBoolean()); assertTrue(list.get(1).isNull());
        assertEquals("x",list.get(2).asString()); assertEquals(3,list.get(3).asInteger());
        assertEquals(1.5,list.get(4).asDouble());
        when(sdk.jsonFlag(any(),any(),any(),any(),any())).thenReturn(ok(json.readTree("[false]"),"DEFAULT"));
        assertFalse(provider.getObjectEvaluation("flag",new Value(List.of()),context).getValue().asList().getFirst().asBoolean());
    }
    @ParameterizedTest @CsvSource({"RULE_MATCH,TARGETING_MATCH","PERCENTAGE_ROLLOUT,SPLIT","DEFAULT,DEFAULT","DISABLED,DISABLED","FUTURE,UNKNOWN"})
    void reasonMapping(String input,String output) {
        when(sdk.booleanFlag(any(),any(),any(),any(),anyBoolean())).thenReturn(ok(true,input));
        assertEquals(output,provider.getBooleanEvaluation("flag",false,context).getReason());
    }
    @ParameterizedTest @CsvSource({"FLAG_NOT_FOUND,FLAG_NOT_FOUND","TYPE_MISMATCH,TYPE_MISMATCH",
        "INVALID_CONTEXT,INVALID_CONTEXT","PROTOCOL_ERROR,PARSE_ERROR","TIMEOUT,GENERAL",
        "CONNECTION,GENERAL","SERVER_UNAVAILABLE,GENERAL","BUSINESS_ERROR,GENERAL","INTERRUPTED,GENERAL"})
    void errorsAndDefaultsAreNotSwallowed(String input,String output) {
        when(sdk.booleanFlag(any(),any(),any(),any(),anyBoolean())).thenReturn(
                new EvaluationResult<>(false,DEFAULT,Error.valueOf(input),"failure",null,"ERROR",null,1));
        var r = provider.getBooleanEvaluation("flag",false,context);
        assertFalse(r.getValue()); assertEquals(ErrorCode.valueOf(output),r.getErrorCode());
        assertEquals("failure",r.getErrorMessage()); assertEquals("ERROR",r.getReason());
    }
    @Test void contextMapsTargetingKeyBuiltinsAndAttributes() {
        when(sdk.booleanFlag(any(),any(),any(),any(),anyBoolean())).thenReturn(ok(true,"DEFAULT"));
        var input = new ImmutableContext("target",Map.of("userId",new Value("ignored"),
                "country",new Value("JP"),"vipLevel",new Value(3),"appVersion",new Value("2.0"),
                "plan",new Value("pro")));
        provider.getBooleanEvaluation("flag",false,input);
        var captor = org.mockito.ArgumentCaptor.forClass(io.github.lu1j.rolloutcore.sdk.EvaluationContext.class);
        verify(sdk).booleanFlag(eq("project"),eq("prod"),eq("flag"),captor.capture(),eq(false));
        var c = captor.getValue();
        assertEquals("target",c.userId()); assertEquals("JP",c.country());
        assertEquals(0,new BigDecimal("3").compareTo(c.vipLevel())); assertEquals("2.0",c.appVersion());
        assertEquals(Set.of("plan"),c.attributes().keySet()); assertEquals("pro",c.attributes().get("plan").asString());
    }
    @Test void missingTargetingKeyAndInvalidBuiltinsReturnContextErrors() {
        assertEquals(ErrorCode.TARGETING_KEY_MISSING,provider.getBooleanEvaluation("flag",false,new ImmutableContext()).getErrorCode());
        assertEquals(ErrorCode.INVALID_CONTEXT,provider.getBooleanEvaluation("flag",false,
                new ImmutableContext("u",Map.of("country",new Value(1)))).getErrorCode());
        assertEquals(ErrorCode.INVALID_CONTEXT,provider.getBooleanEvaluation("flag",false,
                new ImmutableContext("u",Map.of("time",new Value(java.time.Instant.EPOCH)))).getErrorCode());
        verifyNoInteractions(sdk);
    }
    @Test void unsupportedObjectDefaultReturnsExplicitTypeError() {
        assertEquals(ErrorCode.TYPE_MISMATCH,provider.getObjectEvaluation("flag",new Value("scalar"),context).getErrorCode());
        assertEquals(ErrorCode.TYPE_MISMATCH,provider.getObjectEvaluation("flag",
                new Value(List.of(new Value(java.time.Instant.EPOCH))),context).getErrorCode());
        verifyNoInteractions(sdk);
    }
    @Test void callerObjectDefaultIsPreservedExactly() {
        var defaultValue = new Value(List.of(new Value(1), new Value("safe")));
        when(sdk.jsonFlag(any(),any(),any(),any(),any())).thenReturn(
                new EvaluationResult<>(json.readTree("[1.0,\"safe\"]"),DEFAULT,Error.TIMEOUT,"timeout",null,"ERROR",null,1));
        assertSame(defaultValue,provider.getObjectEvaluation("flag",defaultValue,context).getValue());
    }
    @Test void standardOpenFeatureClientPreservesLkgAndDefaultsOnUnrecoveredErrors() {
        var api = OpenFeatureAPI.getInstance();
        api.setProviderAndWait("rolloutcore-test",provider);
        try {
            var standard = api.getClient("rolloutcore-test");
            when(sdk.booleanFlag(any(),any(),any(),any(),anyBoolean())).thenReturn(
                    new EvaluationResult<>(true,LKG,Error.TIMEOUT,"timeout","new","RULE_MATCH",7L,2));
            var r = standard.getBooleanDetails("flag",false,context);
            assertTrue(r.getValue()); assertEquals("CACHED",r.getReason()); assertNull(r.getErrorCode());
            assertEquals("TIMEOUT",r.getFlagMetadata().getString("rolloutcore.error"));
            assertEquals("timeout",r.getFlagMetadata().getString("rolloutcore.errorMessage"));
            when(sdk.booleanFlag(any(),any(),any(),any(),anyBoolean())).thenReturn(
                    new EvaluationResult<>(false,DEFAULT,Error.FLAG_NOT_FOUND,"missing",null,"ERROR",null,1));
            r = standard.getBooleanDetails("flag",false,context);
            assertFalse(r.getValue()); assertEquals(ErrorCode.FLAG_NOT_FOUND,r.getErrorCode());
            assertEquals("ERROR",r.getReason());
        } finally { api.shutdown(); }
        verify(sdk,never()).close(); // shared client's lifecycle belongs to caller/starter
    }
    @Test void unexpectedSdkProgrammingErrorsRemainVisibleToProviderCaller() {
        when(sdk.booleanFlag(any(),any(),any(),any(),anyBoolean())).thenThrow(new IllegalStateException("bug"));
        assertThrows(IllegalStateException.class,() -> provider.getBooleanEvaluation("flag",false,context));
    }
}
