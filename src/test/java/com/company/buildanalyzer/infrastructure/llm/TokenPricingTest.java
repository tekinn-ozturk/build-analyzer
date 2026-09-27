package com.company.buildanalyzer.infrastructure.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class TokenPricingTest {

    private final TokenPricing gpt5Mini = new TokenPricing(new BigDecimal("0.25"), new BigDecimal("2.00"));

    @Test
    void estimatesCostFromPromptAndCompletionTokens() {
        // 6_000 × 0.25 / 1M = 0.0015 ; 800 × 2.00 / 1M = 0.0016
        assertThat(gpt5Mini.estimateUsd(6_000, 800)).isEqualByComparingTo("0.0031");
        assertThat(gpt5Mini.estimateUsd(0, 0)).isEqualByComparingTo("0");
    }

    @Test
    void roundsToSixDecimals() {
        assertThat(gpt5Mini.estimateUsd(1, 1)).isEqualTo(new BigDecimal("0.000002"));   // 0.00000225
    }

    @Test
    void unknownPriceOrTokenCountGivesNoEstimate() {
        assertThat(gpt5Mini.estimateUsd(null, 10)).isNull();
        assertThat(gpt5Mini.estimateUsd(10, null)).isNull();
        assertThat(new TokenPricing(null, BigDecimal.ONE).estimateUsd(10, 10)).isNull();
    }
}
