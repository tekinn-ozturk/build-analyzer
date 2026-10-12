package com.company.buildanalyzer.domain.model;

/**
 * One failed test of a build. Only {@code scenario} is always present; every other field is
 * {@code null} when the log does not show it.
 *
 * @param scenario          the failed scenario; for a non-BDD test the test name as the runner printed it
 *                          (e.g. {@code LoginTest.invalidPassword}), without feature file/line
 * @param step              the failing BDD step
 * @param exceptionType     fully-qualified type of the exception
 * @param message           first line of the exception message, e.g. {@code no such element: ...}
 * @param stackTrace        the exception block (message lines + frames)
 * @param failureLocation   first application (non-framework) stack frame
 * @param failedInteraction the UI-automation command/locator that failed
 * @param logExcerpt        a few log lines printed right after the exception (e.g. a response body)
 */
public record TestFailure(
        FailedScenario scenario,
        FailedStep step,
        String exceptionType,
        String message,
        String stackTrace,
        FailureLocation failureLocation,
        FailedInteraction failedInteraction,
        String logExcerpt
) {
}
