package com.company.buildanalyzer.api.dto.response;

/**
 * Response for POST /api/v1/analysis/build. Carries the structured context
 * distilled from the console log (the payload prepared for the LLM). The raw
 * log is no longer returned.
 */
public record AnalyzeBuildResponse(
        String buildStatus,
        String failedScenario,
        String exceptionType,
        String errorCategory,
        String stackTrace,
        String last500Lines,
        String generatedPrompt
) {
}
