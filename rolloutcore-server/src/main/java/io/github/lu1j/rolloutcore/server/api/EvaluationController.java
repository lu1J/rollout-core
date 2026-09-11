package io.github.lu1j.rolloutcore.server.api;

import io.github.lu1j.rolloutcore.server.evaluation.EvaluationService;
import io.github.lu1j.rolloutcore.server.evaluation.EvaluationCommands.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class EvaluationController {
    private final EvaluationService service;
    public EvaluationController(EvaluationService service) { this.service = service; }

    @PostMapping("/evaluate")
    public EvaluationResponse evaluate(@Valid @RequestBody EvaluateRequest request) {
        return service.evaluate(request);
    }
}
