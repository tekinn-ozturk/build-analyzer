package com.company.buildanalyzer.domain.model;

import java.util.List;

/**
 * Outcome of a full build analysis: the context distilled from the log, the
 * prompt built from it, the model's answer and the cost/speed of that call.
 *
 * @param context           structured context extracted from the console log
 * @param generatedPrompt   the prompt that was sent to the LLM (all prompts, if there were several calls);
 *                          {@code null} when no LLM call was needed
 * @param aiAnalysis        the short, user-facing analysis text (rendered from the structured answer);
 *                          the model's raw answer if it could not be parsed; a fixed message for a SUCCESS build
 * @param rootCauseAnalysis the model's structured answer for a single failure; {@code null} for a SUCCESS build,
 *                          an unparseable answer or a build with several failed tests
 * @param llmMetrics        provider, model, token usage, duration and cost of the LLM call(s), summed;
 *                          {@code null} when no LLM call was needed
 * @param aiSummary         build-level summary of a build with several failed tests; {@code null} otherwise
 * @param groupAnalyses     the AI's answer per failure group, in the order of {@code context.failureGroups()}
 */
public record BuildAnalysisResult(
        BuildAnalysisContext context,
        String generatedPrompt,
        String aiAnalysis,
        RootCauseAnalysis rootCauseAnalysis,
        LlmMetrics llmMetrics,
        String aiSummary,
        List<FailureGroupAnalysis> groupAnalyses
) {

    public BuildAnalysisResult {
        groupAnalyses = groupAnalyses == null ? List.of() : List.copyOf(groupAnalyses);
    }
}
