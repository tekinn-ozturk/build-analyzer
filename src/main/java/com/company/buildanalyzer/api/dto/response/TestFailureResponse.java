package com.company.buildanalyzer.api.dto.response;

/**
 * One failed test of a failure group. Every field may be {@code null} when the log does not show it.
 *
 * @param testContext   where the test failed (scenario, step, feature file and line) — the same generic shape
 *                      as the single-failure {@code testContext}
 * @param exceptionType fully-qualified exception type
 * @param message       first line of the exception message
 * @param exceptionFile source file of the first application stack frame, e.g. {@code ExampleSteps.java}
 * @param exceptionLine line in that file
 */
public record TestFailureResponse(
        TestContextResponse testContext,
        String exceptionType,
        String message,
        String exceptionFile,
        Integer exceptionLine
) {
}
