package com.company.buildanalyzer.domain.model;

import java.util.List;

/**
 * The model's decision-support answer: why it broke, where, and what to do.
 * Deliberately small so it can be read in about ten seconds.
 *
 * @param rootCause specific root cause, at most two sentences, e.g. {@code name=qqqqqqqq locator'ı bulunamadı.}
 * @param file      source file of the failure, e.g. {@code ExampleSteps.java}; {@code null} if not in the evidence
 * @param line      line in that file, e.g. {@code 28}; {@code null} if unknown
 * @param method    method name, e.g. {@code clickSearchBox}; {@code null} if unknown
 * @param actions   at most {@link #MAX_ACTIONS} concrete actions derived from the evidence; never {@code null}
 */
public record RootCauseAnalysis(
        String rootCause,
        String file,
        Integer line,
        String method,
        List<String> actions
) {

    public static final int MAX_ACTIONS = 3;

    public RootCauseAnalysis {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    /**
     * Fills a missing file/line/method from the parser's failure location (the structured
     * evidence), so the location is shown whenever the log proved one. Values the model gave
     * are kept.
     */
    public RootCauseAnalysis withFallbackLocation(FailureLocation location) {
        if (location == null) {
            return this;
        }
        return new RootCauseAnalysis(
                rootCause,
                file != null ? file : location.file(),
                line != null ? line : location.line(),
                method != null ? method : methodName(location.stepDefinition()),
                actions);
    }

    /** {@code stepdefinitions.ExampleSteps.clickSearchBox} → {@code clickSearchBox} */
    private static String methodName(String qualifiedMethod) {
        if (qualifiedMethod == null || qualifiedMethod.isBlank()) {
            return null;
        }
        return qualifiedMethod.substring(qualifiedMethod.lastIndexOf('.') + 1);
    }
}
