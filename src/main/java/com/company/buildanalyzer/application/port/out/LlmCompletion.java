package com.company.buildanalyzer.application.port.out;

import java.math.BigDecimal;

/**
 * Provider-neutral answer of an {@link LlmProvider}. Usage fields are {@code null}
 * when the provider does not report them.
 *
 * @param text             the model's answer, trimmed, never blank
 * @param provider         provider that answered, e.g. {@code OPENAI}
 * @param model            model that answered, as reported by the provider, e.g. {@code gpt-5-mini}
 * @param promptTokens     input tokens billed for the prompt
 * @param completionTokens output tokens billed for the answer (reasoning tokens included)
 * @param totalTokens      prompt + completion tokens
 * @param estimatedCostUsd cost estimated from the configured per-token prices; {@code null} if unknown
 */
public record LlmCompletion(
        String text,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        BigDecimal estimatedCostUsd
) {
}
