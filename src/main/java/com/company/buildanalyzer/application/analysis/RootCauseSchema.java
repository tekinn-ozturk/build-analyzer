package com.company.buildanalyzer.application.analysis;

import com.company.buildanalyzer.application.port.out.LlmResponseSchema;

import java.util.List;
import java.util.Map;

/**
 * JSON schema of the model's answer, parsed by {@link RootCauseParser}:
 * <pre>
 * {"rootCause": "...", "file": "...", "line": 28, "method": "...", "actions": ["..."]}
 * </pre>
 * Every field is required and unknown ones are rejected (what strict structured-output
 * modes need); a location the evidence does not show is {@code null}, never invented.
 * The action limit is also enforced by the parser, because not every provider supports
 * {@code maxItems}.
 */
public final class RootCauseSchema {

    public static final LlmResponseSchema ROOT_CAUSE = new LlmResponseSchema("root_cause_analysis", Map.of(
            "type", "object",
            "additionalProperties", false,
            "required", List.of("rootCause", "file", "line", "method", "actions"),
            "properties", Map.of(
                    "rootCause", Map.of("type", "string"),
                    "file", Map.of("type", List.of("string", "null")),
                    "line", Map.of("type", List.of("integer", "null")),
                    "method", Map.of("type", List.of("string", "null")),
                    "actions", Map.of("type", "array", "items", Map.of("type", "string")))));

    private RootCauseSchema() {
    }
}
