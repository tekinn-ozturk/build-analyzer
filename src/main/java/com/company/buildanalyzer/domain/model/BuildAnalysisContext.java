package com.company.buildanalyzer.domain.model;

/**
 * Minimal, structured context distilled from a raw Jenkins console log.
 * This is the payload that will later be sent to the LLM — small enough to fit
 * a small model's context window, yet carrying the signal needed to explain a
 * failure.
 *
 * @param buildStatus    overall build result, e.g. SUCCESS / FAILURE / UNSTABLE / UNKNOWN
 * @param failedScenario name of the first failing Cucumber scenario, if any
 * @param exceptionType  fully-qualified type of the primary exception, if any
 * @param errorCategory  coarse failure family inferred from the log
 * @param stackTrace     the extracted stack-trace block for that exception, if any
 * @param last500Lines   the tail of the log (up to the last 500 lines)
 */
public record BuildAnalysisContext(
        String buildStatus,
        String failedScenario,
        String exceptionType,
        ErrorCategory errorCategory,
        String stackTrace,
        String last500Lines
) {
}
