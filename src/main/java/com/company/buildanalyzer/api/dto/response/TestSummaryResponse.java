package com.company.buildanalyzer.api.dto.response;

/**
 * Test counts printed by the test runner; a count the log did not show is {@code null}.
 *
 * @param total   tests / scenarios that ran
 * @param passed  passed ones
 * @param failed  failed ones
 * @param skipped skipped ones
 */
public record TestSummaryResponse(
        Integer total,
        Integer passed,
        Integer failed,
        Integer skipped
) {
}
