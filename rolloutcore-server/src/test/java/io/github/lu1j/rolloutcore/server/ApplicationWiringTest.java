package io.github.lu1j.rolloutcore.server;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.LivenessState;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.mockito.Mockito.mock;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Loads production wiring and XML. Only external DB access and DB health are disabled here. */
class ApplicationWiringTest {
    // The managed SpringExtension requires JUnit 6. The context runner lets this
    // project keep the requested JUnit 5 while still loading production Boot wiring.
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(RolloutCoreApplication.class)
            .withBean(DataSource.class, () -> mock(DataSource.class))
            .withPropertyValues("spring.flyway.enabled=false", "management.health.db.enabled=false",
                    "ROLLOUTCORE_DB_PASSWORD=test-only", "rolloutcore.outbox.enabled=false");

    @Test void healthIsExposed() throws Exception {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            // Context Runner does not publish SpringApplication's ready lifecycle events.
            AvailabilityChangeEvent.publish(context, LivenessState.CORRECT);
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
            MockMvcBuilders.webAppContextSetup(context).build().perform(get("/actuator/health"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"))
                    .andExpect(jsonPath("$.components").doesNotExist());
        });
    }
    @Test void healthReportsUnavailableBeforeApplicationReady() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            MockMvcBuilders.webAppContextSetup(context).build().perform(get("/actuator/health"))
                    .andExpect(status().isServiceUnavailable());
        });
    }
    @Test void otherActuatorEndpointsAreNotExposed() throws Exception {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            MockMvcBuilders.webAppContextSetup(context).build().perform(get("/actuator/env"))
                    .andExpect(status().isNotFound());
        });
    }
    @Test void prometheusExportsExistingCacheAndJvmMetrics() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            MockMvcBuilders.webAppContextSetup(context).build().perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("rolloutcore_cache_l1_hit_total")))
                    .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
        });
    }
    @ParameterizedTest
    @ValueSource(strings = {"{\"expectedVersion\":0.5}", "{\"expectedVersion\":\"0\"}", "{\"expectedVersion\":true}"})
    void versionDoesNotAcceptCoercedScalarTypes(String body) {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(post("/api/v1/projects/shop/environments/prod/flags/pay/enable")
                            .header("X-Operator", "alice").contentType("application/json").content(body))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
        });
    }
    @Test void flagTypeDoesNotAcceptEnumOrdinal() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            MockMvcBuilders.webAppContextSetup(context).build()
                    .perform(post("/api/v1/projects/shop/flags").header("X-Operator", "alice")
                            .contentType("application/json")
                            .content("{\"flagKey\":\"pay\",\"name\":\"Pay\",\"valueType\":0}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
        });
    }

    @Test void policyUsesProductionStrictJsonAndProblemDetailConfiguration() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(
                            "/api/v1/projects/shop/environments/prod/flags/pay/evaluation-policy")
                    .header("X-Operator", "alice").contentType("application/json")
                    .content("{\"expectedVersion\":0,\"rollout\":[{\"variantKey\":\"old\",\"weight\":10000.5}]}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
            mvc.perform(post("/api/v1/evaluate").contentType("application/json")
                    .content("{\"projectKey\":\"shop\",\"environmentKey\":\"prod\",\"flagKey\":\"pay\",\"context\":{\"userId\":\"u\",\"vipLevel\":\"5\"}}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_error"));
        });
    }
}
