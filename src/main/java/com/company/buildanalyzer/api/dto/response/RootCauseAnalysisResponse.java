package com.company.buildanalyzer.api.dto.response;

import java.util.List;

/**
 * The model's structured answer, for clients that render it themselves
 * ({@code aiAnalysis} carries the same content as ready-to-show text).
 *
 * @param rootCause specific root cause, at most two sentences
 * @param file      source file of the failure, e.g. {@code ExampleSteps.java}; {@code null} if unknown
 * @param line      line in that file, e.g. {@code 28}; {@code null} if unknown
 * @param method    method name, e.g. {@code clickSearchBox}; {@code null} if unknown
 * @param actions   at most three concrete actions
 */
public record RootCauseAnalysisResponse(
        String rootCause,
        String file,
        Integer line,
        String method,
        List<String> actions
) {
}
