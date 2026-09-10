package io.github.lu1j.rolloutcore.server.api;

import io.github.lu1j.rolloutcore.server.service.BusinessException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.ErrorResponse;

@RestControllerAdvice
public class ApiExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ProblemDetail> business(BusinessException exception) {
        return problem(exception.getStatus(), exception.getCode(), exception.getMessage());
    }

    @ExceptionHandler(DuplicateKeyException.class)
    public ResponseEntity<ProblemDetail> duplicate(DuplicateKeyException exception) {
        return problem(409, "duplicate_resource", "A resource with this key or config already exists");
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class, MissingServletRequestParameterException.class,
            ConstraintViolationException.class, HandlerMethodValidationException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ProblemDetail> validation(Exception exception) {
        return problem(400, "validation_error", "Invalid request, path, query parameter or missing X-Operator");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> unexpected(Exception exception) {
        if (exception instanceof ErrorResponse error) {
            int status = error.getStatusCode().value();
            return problem(status, status == 404 ? "resource_not_found" : "http_error",
                    HttpStatus.valueOf(status).getReasonPhrase());
        }
        LOG.error("Unhandled control plane failure", exception);
        return problem(500, "internal_error", "An internal error occurred");
    }

    private ResponseEntity<ProblemDetail> problem(int status, String code, String detail) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(status), detail);
        body.setTitle(code);
        body.setProperty("code", code);
        return ResponseEntity.status(status).body(body);
    }
}
