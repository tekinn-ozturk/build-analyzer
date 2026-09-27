package com.company.buildanalyzer.application.port.out;

/**
 * Provider-neutral request to an {@link LlmProvider}.
 *
 * @param prompt         the full prompt text
 * @param responseSchema JSON schema the answer must follow; {@code null} = free text.
 *                       Each adapter enforces it with its provider's own mechanism
 *                       (OpenAI: structured outputs; others: JSON mode / tool schema).
 */
public record LlmRequest(String prompt, LlmResponseSchema responseSchema) {

    /** A free-text request without a response schema. */
    public static LlmRequest of(String prompt) {
        return new LlmRequest(prompt, null);
    }
}
