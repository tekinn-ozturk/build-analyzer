package com.company.buildanalyzer.domain.model;

/**
 * Test counts as printed by the test runner's own summary line (Cucumber, Surefire/Failsafe, Gradle,
 * pytest, Jest). A count the log does not show is {@code null}, never estimated.
 *
 * @param total   tests / scenarios that ran
 * @param passed  passed ones
 * @param failed  failed ones (assertion failures and errors)
 * @param skipped skipped ones
 */
public record TestSummary(
        Integer total,
        Integer passed,
        Integer failed,
        Integer skipped
) {
}
