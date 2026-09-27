package com.company.buildanalyzer.domain.model;

/**
 * Minimal, structured context distilled from a raw Jenkins console log.
 * This is the payload that will later be sent to the LLM — small enough to fit
 * a small model's context window, yet carrying the signal needed to explain a
 * failure. Every field is best-effort: {@code null} means "not found", never a guess.
 *
 * @param buildStatus        overall build result, e.g. SUCCESS / FAILURE / UNSTABLE / UNKNOWN
 * @param failedScenario     the failing BDD scenario (name + feature file/line), if any
 * @param failedStep         the failing BDD step (text + feature file/line), if any
 * @param exceptionType      fully-qualified type of the primary exception, if any
 * @param errorCategory      coarse failure family inferred from the log ({@link ErrorCategory#NONE} on success)
 * @param stackTrace         the extracted exception block (message lines + stack frames), if any
 * @param failureLocation    first application (non-framework) stack frame of that exception, if any
 * @param failedInteraction  the UI-automation command/locator that failed (tool-neutral), if any
 * @param relevantLogSnippet the log lines that matter for the analysis (noise removed, stack trace
 *                           not repeated) — the LLM's secondary source
 * @param last200Lines       the raw tail of the log (up to the last 200 lines) — supporting context for the LLM
 */
public record BuildAnalysisContext(
        String buildStatus,
        FailedScenario failedScenario,
        FailedStep failedStep,
        String exceptionType,
        ErrorCategory errorCategory,
        String stackTrace,
        FailureLocation failureLocation,
        FailedInteraction failedInteraction,
        String relevantLogSnippet,
        String last200Lines
) {

    public static final String SUCCESS_STATUS = "SUCCESS";

    /** Single source of truth for "this build needs no error analysis". */
    public static boolean isSuccessStatus(String buildStatus) {
        return SUCCESS_STATUS.equalsIgnoreCase(buildStatus);
    }

    public boolean isSuccessful() {
        return isSuccessStatus(buildStatus);
    }
}
