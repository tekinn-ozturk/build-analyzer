package com.company.buildanalyzer.infrastructure.llm;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Per-token list prices of a model, used by any provider adapter to estimate the
 * cost of a call. Prices are USD per one million tokens; a {@code null} price
 * means "unknown" and yields no estimate.
 *
 * @param inputUsdPerMillion  price of prompt (input) tokens
 * @param outputUsdPerMillion price of completion (output) tokens
 */
public record TokenPricing(BigDecimal inputUsdPerMillion, BigDecimal outputUsdPerMillion) {

    private static final BigDecimal ONE_MILLION = BigDecimal.valueOf(1_000_000);
    private static final int COST_SCALE = 6;

    /** @return the estimated cost in USD, or {@code null} if a price or a token count is unknown */
    public BigDecimal estimateUsd(Integer promptTokens, Integer completionTokens) {
        if (inputUsdPerMillion == null || outputUsdPerMillion == null
                || promptTokens == null || completionTokens == null) {
            return null;
        }
        BigDecimal input = inputUsdPerMillion.multiply(BigDecimal.valueOf(promptTokens));
        BigDecimal output = outputUsdPerMillion.multiply(BigDecimal.valueOf(completionTokens));
        return input.add(output).divide(ONE_MILLION, COST_SCALE, RoundingMode.HALF_UP);
    }
}
