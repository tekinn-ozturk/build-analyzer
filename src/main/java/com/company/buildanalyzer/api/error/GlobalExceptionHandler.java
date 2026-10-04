package com.company.buildanalyzer.api.error;

import com.company.buildanalyzer.application.port.out.LlmAnalysisException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getDefaultMessage())
                .orElse("Validation failed");

        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    /** Jenkins responded with a non-2xx status (e.g. 404 when the build does not exist). */
    @ExceptionHandler(RestClientResponseException.class)
    public ResponseEntity<Map<String, String>> handleJenkinsResponse(RestClientResponseException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", "Jenkins request failed: " + ex.getStatusText()));
    }

    /** Jenkins could not be reached (connection refused, timeout, unknown host). */
    @ExceptionHandler(ResourceAccessException.class)
    public ResponseEntity<Map<String, String>> handleJenkinsUnreachable(ResourceAccessException ex) {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Jenkins is unreachable: " + ex.getMessage()));
    }

    /** Our own "not found / not allowed" answers, e.g. an analysis the current user may not see (404). */
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode())
                .body(Map.of("error", ex.getReason() == null ? ex.getStatusCode().toString() : ex.getReason()));
    }

    /** The LLM provider was unreachable, timed out, rejected the request or returned no answer. */
    @ExceptionHandler(LlmAnalysisException.class)
    public ResponseEntity<Map<String, String>> handleLlmFailure(LlmAnalysisException ex) {
        log.warn("AI analysis failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "AI analysis failed: " + ex.getMessage()));
    }
}
