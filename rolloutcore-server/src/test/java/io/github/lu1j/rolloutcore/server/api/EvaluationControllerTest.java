package io.github.lu1j.rolloutcore.server.api;

import io.github.lu1j.rolloutcore.server.evaluation.*;
import io.github.lu1j.rolloutcore.server.service.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.cfg.EnumFeature;
import static io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class EvaluationControllerTest {
    private EvaluationService evaluation;
    private ControlPlaneService control;
    private MockMvc mvc;
    private JsonMapper json;
    private static final String POLICY = "/api/v1/projects/shop/environments/prod/flags/pay/evaluation-policy";
    private static final String REQUEST = "{\"projectKey\":\"shop\",\"environmentKey\":\"prod\",\"flagKey\":\"pay\",\"context\":{\"userId\":\"u\"}}";

    @BeforeEach void setup() {
        evaluation = mock(EvaluationService.class); control = mock(ControlPlaneService.class);
        json = JsonMapper.builder().disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS).enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build();
        mvc = MockMvcBuilders.standaloneSetup(new EvaluationController(evaluation), new ControlPlaneController(control, json))
                .setMessageConverters(new JacksonJsonHttpMessageConverter(json.rebuild()))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test void evaluationReturnsTypedValueAndExplanationWithoutOperatorHeader() throws Exception {
        when(evaluation.evaluate(any())).thenReturn(new EvaluationResponse("pay", "new", json.readTree("{\"ok\":true}"), Reason.RULE_MATCH, 3, 10, null));
        mvc.perform(post("/api/v1/evaluate").contentType("application/json").content(REQUEST))
                .andExpect(status().isOk()).andExpect(jsonPath("$.value.ok").value(true))
                .andExpect(jsonPath("$.reason").value("RULE_MATCH")).andExpect(jsonPath("$.configVersion").value(3))
                .andExpect(jsonPath("$.matchedRulePriority").value(10)).andExpect(jsonPath("$.bucket").isEmpty());
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"projectKey\":\"BAD\"}",
            "{\"projectKey\":\"shop\",\"environmentKey\":\"prod\",\"flagKey\":\"pay\",\"context\":{}}",
            "{\"projectKey\":\"shop\",\"environmentKey\":\"prod\",\"flagKey\":\"pay\",\"context\":{\"userId\":\" \"}}",
            "{\"projectKey\":\"shop\",\"environmentKey\":\"prod\",\"flagKey\":\"pay\",\"context\":{\"userId\":\"u\",\"vipLevel\":\"5\"}}",
            "{\"projectKey\":\"shop\",\"environmentKey\":\"prod\",\"flagKey\":\"pay\",\"context\":{\"userId\":\"u\\u0000x\"}}"})
    void invalidRequestIs400(String request) throws Exception {
        mvc.perform(post("/api/v1/evaluate").contentType("application/json").content(request))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
        verifyNoInteractions(evaluation);
    }
    @Test void resourceNotFoundIs404() throws Exception {
        when(evaluation.evaluate(any())).thenThrow(BusinessException.notFound("Config"));
        mvc.perform(post("/api/v1/evaluate").contentType("application/json").content(REQUEST))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("resource_not_found"));
    }
    @Test void policyResponseAndOperatorForwarded() throws Exception {
        when(control.updatePolicy(eq("shop"), eq("prod"), eq("pay"), any(), eq("alice")))
                .thenReturn(new PolicyResponse(1, new EvaluationPolicy(null, null)));
        mvc.perform(put(POLICY).header("X-Operator", "alice").contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
        verify(control).updatePolicy("shop", "prod", "pay", new UpdatePolicy(0L, null, null), "alice");
    }
    @Test void policyRequiresOperator() throws Exception {
        mvc.perform(put(POLICY).contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest()); verifyNoInteractions(control);
    }
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"expectedVersion\":-1}", "{\"expectedVersion\":0.5}", "{\"expectedVersion\":\"0\"}",
            "{\"expectedVersion\":0,\"rollout\":[{\"variantKey\":\"new\",\"weight\":1000.5}]}",
            "{\"expectedVersion\":0,\"rollout\":[{\"variantKey\":\"new\",\"weight\":\"10000\"}]}",
            "{\"expectedVersion\":0,\"rules\":[{\"match\":0}]}",
            "{\"expectedVersion\":0,\"rules\":[{\"match\":\"ALL\",\"conditions\":[{\"operator\":\"EVAL\"}]}]}"})
    void invalidPolicyShapeIs400(String body) throws Exception {
        mvc.perform(put(POLICY).header("X-Operator", "alice").contentType("application/json").content(body))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
        verifyNoInteractions(control);
    }
    @Test void stalePolicyIs409() throws Exception {
        when(control.updatePolicy(anyString(), anyString(), anyString(), any(), anyString())).thenThrow(new OptimisticLockConflictException());
        mvc.perform(put(POLICY).header("X-Operator", "alice").contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("optimistic_lock_conflict"));
    }
    @Test void invalidPolicySemanticsIs400() throws Exception {
        when(control.updatePolicy(anyString(), anyString(), anyString(), any(), anyString())).thenThrow(BusinessException.validation("Invalid weights"));
        mvc.perform(put(POLICY).header("X-Operator", "alice").contentType("application/json").content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
    }
}
