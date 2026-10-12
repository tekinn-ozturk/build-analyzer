package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.TestSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestSummaryExtractorTest {

    private static TestSummary extract(String log) {
        return TestSummaryExtractor.extract(log.lines().toList());
    }

    @Test
    void readsCucumbersScenarioCountsWhereZeroCountsAreLeftOut() {
        assertThat(extract("30 Scenarios (10 failed, 2 skipped, 18 passed)\n90 Steps (10 failed, 80 passed)"))
                .isEqualTo(new TestSummary(30, 18, 10, 2));
        assertThat(extract("1 Scenarios (1 passed)")).isEqualTo(new TestSummary(1, 1, 0, 0));
        assertThat(extract("\u001B[31m3 Scenarios (\u001B[31m1 failed\u001B[0m, \u001B[32m2 passed\u001B[0m)"))
                .isEqualTo(new TestSummary(3, 2, 1, 0));
    }

    @Test
    void readsSurefireTotalsButNotThePerClassLinesAndAddsUpModules() {
        String log = """
                [ERROR] Tests run: 20, Failures: 2, Errors: 1, Skipped: 1, Time elapsed: 3.2 s <<< FAILURE! -- in com.acme.ATest
                [INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1.1 s -- in com.acme.BTest
                [ERROR] Tests run: 30, Failures: 2, Errors: 1, Skipped: 1
                [WARNING] Tests run: 5, Failures: 0, Errors: 0, Skipped: 2
                """;

        assertThat(extract(log)).isEqualTo(new TestSummary(35, 29, 3, 3));
    }

    /** A module with a Cucumber runner next to plain JUnit tests: Surefire's totals count both, Cucumber only its scenarios. */
    @Test
    void prefersSurefiresTotalsOverCucumbersScenarioCounts() {
        assertThat(extract("""
                5 Scenarios (1 failed, 4 passed)
                [ERROR] Tests run: 125, Failures: 3, Errors: 0, Skipped: 0"""))
                .isEqualTo(new TestSummary(125, 122, 3, 0));
    }

    @Test
    void readsGradlePytestAndJest() {
        assertThat(extract("30 tests completed, 10 failed, 2 skipped")).isEqualTo(new TestSummary(30, 18, 10, 2));
        assertThat(extract("4 tests completed, 1 failed")).isEqualTo(new TestSummary(4, 3, 1, 0));
        // pytest prints no total: it stays unknown
        assertThat(extract("========= 3 failed, 20 passed, 1 skipped, 1 error in 12.30s =========="))
                .isEqualTo(new TestSummary(null, 20, 4, 1));
        assertThat(extract("Tests:       2 failed, 28 passed, 30 total")).isEqualTo(new TestSummary(30, 28, 2, 0));
    }

    @Test
    void isUnknownWithoutASummaryLine() {
        assertThat(extract("[INFO] BUILD FAILURE\nFinished: FAILURE")).isNull();
        assertThat(TestSummaryExtractor.extract(List.of())).isNull();
    }
}
