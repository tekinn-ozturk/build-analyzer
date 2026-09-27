package com.company.buildanalyzer.api.dto.response;

import java.math.BigDecimal;

/**
 * Response for POST /api/v1/analysis/build. Carries the structured context
 * distilled from the console log, the prompt sent to the LLM, the model's
 * analysis and the usage statistics of that call. Field names are tool-neutral
 * ({@code failedCommand}, not "seleniumCommand") and provider-neutral
 * ({@code provider}, {@code model}) so other test tools and LLM providers fit the same contract.
 *
 * @param failedScenario       clean scenario name, e.g. {@code Open Google}
 * @param featureFile          feature file of that scenario, e.g. {@code src/test/resources/features/Example.feature}
 * @param featureLine          line of the scenario in the feature file
 * @param failedStepText       failing BDD step as written in the feature file, e.g. {@code Arama kutusuna tıkla.}
 * @param failedStepLine       line of that step in the feature file, e.g. {@code 6}
 * @param exceptionFile        source file of the first application stack frame, e.g. {@code ExampleSteps.java}
 * @param exceptionLine        line in that file, e.g. {@code 28}
 * @param failedStepDefinition failing step-definition method, e.g. {@code stepdefinitions.ExampleSteps.clickSearchBox}
 * @param automationTool       UI automation tool that reported the failure, e.g. {@code Selenium}
 * @param failedCommand        the failing tool command, e.g. {@code findElement}
 * @param locatorType          locator strategy, e.g. {@code name}
 * @param locatorValue         locator value, e.g. {@code qqqqqqqq}
 * @param stackTrace           the full exception block (message lines + all frames)
 * @param relevantLogSnippet   the filtered log lines (the LLM's secondary source)
 * @param last200Lines         raw log tail (the LLM's supporting context)
 * @param aiAnalysis           short, ready-to-show analysis (🚨 KÖK NEDEN / 📍 KONUM / ✅ AKSİYON, max 10 lines)
 * @param rootCauseAnalysis    the same analysis as structured fields; {@code null} for a SUCCESS build or an
 *                             unparseable model answer
 * @param provider             LLM provider that answered, e.g. {@code OPENAI}; {@code null} if no LLM call was made
 * @param model                model that answered, e.g. {@code gpt-5-mini}
 * @param promptTokens         input tokens of the LLM call
 * @param completionTokens     output tokens of the LLM call (reasoning tokens included)
 * @param totalTokens          prompt + completion tokens
 * @param responseTimeMs       duration of the LLM call in milliseconds
 * @param estimatedCostUsd     estimated cost of the LLM call in US dollars
 */
public record AnalyzeBuildResponse(
        String buildStatus,
        String failedScenario,
        String featureFile,
        Integer featureLine,
        String failedStepText,
        Integer failedStepLine,
        String exceptionType,
        String errorCategory,
        String exceptionFile,
        Integer exceptionLine,
        String failedStepDefinition,
        String automationTool,
        String failedCommand,
        String locatorType,
        String locatorValue,
        String stackTrace,
        String relevantLogSnippet,
        String last200Lines,
        String generatedPrompt,
        String aiAnalysis,
        RootCauseAnalysisResponse rootCauseAnalysis,
        String provider,
        String model,
        Integer promptTokens,
        Integer completionTokens,
        Integer totalTokens,
        Long responseTimeMs,
        BigDecimal estimatedCostUsd
) {
}
