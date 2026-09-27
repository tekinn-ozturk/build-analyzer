package com.company.buildanalyzer.domain.model;

/**
 * Outcome of a full build analysis: the context distilled from the log, the
 * prompt built from it, the model's answer and the cost/speed of that call.
 *
 * @param context           structured context extracted from the console log
 * @param generatedPrompt   the prompt that was sent to the LLM; {@code null} when no LLM call was needed
 * @param aiAnalysis        the short, user-facing analysis text (rendered from {@code rootCauseAnalysis});
 *                          the model's raw answer if it could not be parsed; a fixed message for a SUCCESS build
 * @param rootCauseAnalysis the model's structured answer; {@code null} for a SUCCESS build or an unparseable answer
 * @param llmMetrics        provider, model, token usage, duration and cost of the LLM call;
 *                          {@code null} when no LLM call was needed
 */
public record BuildAnalysisResult(
        BuildAnalysisContext context,
        String generatedPrompt,
        String aiAnalysis,
        RootCauseAnalysis rootCauseAnalysis,
        LlmMetrics llmMetrics
) {
}
