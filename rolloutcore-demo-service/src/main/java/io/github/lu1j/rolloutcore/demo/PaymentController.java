package io.github.lu1j.rolloutcore.demo;

import io.github.lu1j.rolloutcore.sdk.EvaluationContext;
import io.github.lu1j.rolloutcore.sdk.RolloutCoreClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
public class PaymentController {
    private final RolloutCoreClient client;
    private final String project;
    private final String environment;
    public PaymentController(RolloutCoreClient client,
            @Value("${demo.project-key:sdk-demo}") String project,
            @Value("${demo.environment-key:prod}") String environment) {
        this.client = client;
        this.project = project;
        this.environment = environment;
    }
    @GetMapping("/demo/payment")
    public Payment payment(@RequestParam String userId) {
        var result = client.booleanFlag(project, environment, "new-payment-flow", new EvaluationContext(userId), false);
        return new Payment(userId, result.value() ? "new" : "old", result.source().name(),
                result.reason(), result.variant(), result.error().name());
    }
    public record Payment(String userId, String flow, String source, String reason, String variant, String error) {}
}
