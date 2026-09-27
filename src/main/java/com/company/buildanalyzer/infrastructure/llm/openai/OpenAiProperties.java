package com.company.buildanalyzer.infrastructure.llm.openai;

import com.company.buildanalyzer.infrastructure.llm.TokenPricing;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Binds the {@code openai.*} configuration block. There are deliberately no
 * defaults here: every value comes from {@code application.yml} (which lets the
 * environment override it), and a missing/invalid value fails startup. The API
 * key is read only from the {@code OPENAI_API_KEY} environment variable.
 */
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "openai")
public class OpenAiProperties {

    /** Base URL of the OpenAI API, e.g. https://api.openai.com/v1 */
    @NotBlank
    private String url;

    /** Secret API key; comes only from the environment. */
    @NotBlank(message = "OPENAI_API_KEY environment variable is not set")
    private String apiKey;

    /** Model to run, e.g. gpt-5-mini */
    @NotBlank
    private String model;

    /** Max seconds to wait for the full (non-streamed) answer. */
    @NotNull
    @Positive
    private Integer timeoutSeconds;

    /** Max seconds to establish the TCP connection. */
    @NotNull
    @Positive
    private Integer connectTimeoutSeconds;

    /** Upper bound for output tokens, reasoning tokens included ({@code max_output_tokens}). */
    @NotNull
    @Positive
    private Integer maxOutputTokens;

    /** {@code reasoning.effort}: minimal / low / medium / high; unset = model default. */
    private String reasoningEffort;

    /** {@code text.verbosity}: low / medium / high; unset = model default. */
    private String verbosity;

    @Valid
    @NotNull
    private Pricing pricing = new Pricing();

    public Duration timeout() {
        return Duration.ofSeconds(timeoutSeconds);
    }

    public Duration connectTimeout() {
        return Duration.ofSeconds(connectTimeoutSeconds);
    }

    public TokenPricing tokenPricing() {
        return new TokenPricing(pricing.getInputUsdPerMillion(), pricing.getOutputUsdPerMillion());
    }

    /** List prices in USD per one million tokens, used only for {@code estimatedCostUsd}. */
    @Getter
    @Setter
    public static class Pricing {
        private BigDecimal inputUsdPerMillion;
        private BigDecimal outputUsdPerMillion;
    }
}
