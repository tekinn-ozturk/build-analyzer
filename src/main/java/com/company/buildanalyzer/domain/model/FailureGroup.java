package com.company.buildanalyzer.domain.model;

import java.util.List;

/**
 * Failed tests that share one error signature, so they are analysed — and paid for — once.
 *
 * @param id        short id within the build, e.g. {@code G1}; groups are numbered largest first
 * @param signature the deterministic grouping key, readable, e.g.
 *                  {@code org.openqa.selenium.NoSuchElementException: no such element: ... @ LoginSteps.java:28}
 * @param failures  the failed tests of the group in log order; never empty. The first one is the
 *                  representative whose evidence is sent to the LLM.
 */
public record FailureGroup(
        String id,
        String signature,
        List<TestFailure> failures
) {

    public FailureGroup {
        failures = List.copyOf(failures);
    }

    public TestFailure representative() {
        return failures.get(0);
    }
}
