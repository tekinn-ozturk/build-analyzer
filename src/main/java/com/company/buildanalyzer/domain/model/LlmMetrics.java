package com.company.buildanalyzer.domain.model;

import java.math.BigDecimal;

/**
 * Cost and speed of the LLM call behind an analysis. Token fields are {@code null}
 * when the provider did not report them.
 *
 * @param provider         provider that answered, e.g. {@code OPENAI}
 * @param model            model that answered, e.g. {@code gpt-5-mini}
 * @param promptTokens     input tokens
 * @param completionTokens output tokens (reasoning tokens included)
 * @param totalTokens      prompt + completion tokens
 * @param responseTimeMs   wall-clock time of the LLM call in milliseconds
 * @param estimatedCostUsd estimated cost of the call in US dollars; {@code null} if unknown
 */
public record LlmMetrics(
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        long responseTimeMs,
        BigDecimal estimatedCostUsd
) {
}
