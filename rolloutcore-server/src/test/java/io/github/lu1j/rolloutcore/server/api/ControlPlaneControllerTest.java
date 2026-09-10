package io.github.lu1j.rolloutcore.server.api;

import io.github.lu1j.rolloutcore.domain.*;
import io.github.lu1j.rolloutcore.server.service.*;
import io.github.lu1j.rolloutcore.server.service.Commands.*;
import org.junit.jupiter.api.*;
import org.springframework.dao.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ControlPlaneControllerTest {
    private ControlPlaneService service;
    private MockMvc mvc;
    private static final String CONFIG = "/api/v1/projects/shop/environments/prod/flags/pay/config";
    private static final String SWITCH = "/api/v1/projects/shop/environments/prod/flags/pay/";

    @BeforeEach void setup() {
        service = mock(ControlPlaneService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ControlPlaneController(service, JsonMapper.builder().build()))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    private ResultActions createProject(String body, boolean operator) throws Exception {
        var request = post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content(body);
        if (operator) request.header("X-Operator", "alice");
        return mvc.perform(request);
    }

    @Test void projectCreationReturns201AndForwardsOperator() throws Exception {
        Project project = new Project(); project.setId(1L); project.setProjectKey("shop");
        when(service.createProject(any(), eq("alice"))).thenReturn(project);
        createProject("{\"projectKey\":\"shop\",\"name\":\"Shop\"}", true)
                .andExpect(status().isCreated()).andExpect(jsonPath("$.projectKey").value("shop"));
        verify(service).createProject(new CreateProject("shop", "Shop"), "alice");
    }
    @Test void missingOperatorReturnsProblem400() throws Exception {
        createProject("{\"projectKey\":\"shop\",\"name\":\"Shop\"}", false)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
        verifyNoInteractions(service);
    }
    @Test void uppercaseKeyReturns400() throws Exception {
        createProject("{\"projectKey\":\"SHOP\",\"name\":\"Shop\"}", true)
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
        verifyNoInteractions(service);
    }
    @Test void blankNameReturns400() throws Exception {
        createProject("{\"projectKey\":\"shop\",\"name\":\" \"}", true).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void malformedJsonReturns400() throws Exception {
        createProject("{", true).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
    }
    @Test void missingProjectReturns404() throws Exception {
        when(service.getProject("missing")).thenThrow(BusinessException.notFound("Project"));
        mvc.perform(get("/api/v1/projects/missing")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("resource_not_found"));
    }
    @Test void duplicateConstraintReturns409WithoutSql() throws Exception {
        when(service.createProject(any(), anyString())).thenThrow(new DuplicateKeyException("SECRET INSERT SQL"));
        createProject("{\"projectKey\":\"shop\",\"name\":\"Shop\"}", true)
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("duplicate_resource"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
    }
    @Test void optimisticConflictReturns409() throws Exception {
        when(service.updateConfig(anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new OptimisticLockConflictException());
        mvc.perform(put(CONFIG).header("X-Operator", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"defaultVariantKey\":\"old\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("optimistic_lock_conflict"));
    }
    @Test void configUpdateRequiresExpectedVersion() throws Exception {
        mvc.perform(put(CONFIG).header("X-Operator", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true,\"defaultVariantKey\":\"old\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void configCreationRequiresEnabled() throws Exception {
        mvc.perform(post(CONFIG).header("X-Operator", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"defaultVariantKey\":\"old\"}")).andExpect(status().isBadRequest());
    }
    @Test void enableForwardsExpectedVersion() throws Exception {
        mvc.perform(post(SWITCH + "enable").header("X-Operator", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}")).andExpect(status().isOk());
        verify(service).setEnabled("shop", "prod", "pay", new SwitchFlag(0L), true, "alice");
    }
    @Test void disableForwardsExpectedVersion() throws Exception {
        mvc.perform(post(SWITCH + "disable").header("X-Operator", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1}")).andExpect(status().isOk());
        verify(service).setEnabled("shop", "prod", "pay", new SwitchFlag(1L), false, "alice");
    }
    @Test void switchRejectsNegativeVersion() throws Exception {
        mvc.perform(post(SWITCH + "enable").header("X-Operator", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":-1}")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void switchRequiresVersion() throws Exception {
        mvc.perform(post(SWITCH + "disable").header("X-Operator", "alice").contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest());
    }
    @Test void unknownFlagTypeReturns400() throws Exception {
        mvc.perform(post("/api/v1/projects/shop/flags").header("X-Operator", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flagKey\":\"pay\",\"name\":\"Pay\",\"valueType\":\"INTEGER\"}"))
                .andExpect(status().isBadRequest());
    }
    @Test void invalidNestedVariantReturns400() throws Exception {
        mvc.perform(post("/api/v1/projects/shop/flags").header("X-Operator", "alice")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"flagKey\":\"pay\",\"name\":\"Pay\",\"valueType\":\"BOOLEAN\",\"variants\":[{\"variantKey\":\"BAD\",\"value\":true}]}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void wrongValueTypeBusinessErrorReturns400() throws Exception {
        when(service.createVariant(anyString(), anyString(), any(), anyString()))
                .thenThrow(BusinessException.validation("Variant value must match BOOLEAN"));
        mvc.perform(post("/api/v1/projects/shop/flags/pay/variants").header("X-Operator", "alice")
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantKey\":\"old\",\"value\":\"true\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
    }
    @Test void variantResponseContainsJsonValueNotEscapedString() throws Exception {
        FlagVariant variant = new FlagVariant(); variant.setValueJson("{\"enabled\":true}"); variant.setVariantKey("new");
        when(service.createVariant(anyString(), anyString(), any(), anyString())).thenReturn(variant);
        mvc.perform(post("/api/v1/projects/shop/flags/pay/variants").header("X-Operator", "alice")
                .contentType(MediaType.APPLICATION_JSON).content("{\"variantKey\":\"new\",\"value\":{\"enabled\":true}}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.value.enabled").value(true));
    }
    @Test void auditQueryRequiresProjectKey() throws Exception {
        mvc.perform(get("/api/v1/audits")).andExpect(status().isBadRequest());
    }
    @Test void auditQueryRejectsNonNumericLimit() throws Exception {
        mvc.perform(get("/api/v1/audits").param("projectKey", "shop").param("limit", "abc"))
                .andExpect(status().isBadRequest());
    }
    @Test void auditQueryReturnsParsedSnapshots() throws Exception {
        AuditLog audit = new AuditLog(); audit.setOperation("FLAG_ENABLED");
        audit.setBeforeJson("{\"enabled\":false}"); audit.setAfterJson("{\"enabled\":true}");
        when(service.listAudits("shop", 50, 0)).thenReturn(List.of(audit));
        mvc.perform(get("/api/v1/audits").param("projectKey", "shop"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].before.enabled").value(false))
                .andExpect(jsonPath("$[0].after.enabled").value(true));
    }
    @Test void unexpectedDatabaseFailureDoesNotLeakDetails() throws Exception {
        when(service.getProject("shop")).thenThrow(new DataAccessResourceFailureException("SECRET password sql"));
        mvc.perform(get("/api/v1/projects/shop")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("SECRET"))));
    }
}
