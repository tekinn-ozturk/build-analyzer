package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.TestFailure;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FailureGrouperTest {

    private static final FailureLocation LOGIN_STEP = new FailureLocation("steps.LoginSteps.submit", "LoginSteps.java", 42);
    private static final FailureLocation ORDER_STEP = new FailureLocation("steps.OrderSteps.submit", "OrderSteps.java", 17);

    private static TestFailure failure(String scenario, String type, String message, FailureLocation location) {
        return new TestFailure(new FailedScenario(scenario, null, null), null, type, message, null, location, null, null);
    }

    @Test
    void masksOnlyRunSpecificValues() {
        assertThat(FailureGrouper.maskRunSpecificValues(
                "session b9cc324322adeb37cb93984b824b8efd at 2026-10-11T10:15:30.123Z (10:15:31) on http://grid:4444/wd "
                        + "id=3f2b1c9e-8a7d-4e6f-9b0a-1c2d3e4f5a6b obj@1a2b3c4 order 1728641730123"))
                .isEqualTo("session <id> at <time> (<time>) on http://grid:<port>/wd id=<uuid> obj@<hash> order <number>");
        // what tells two errors apart is kept: locators, status codes, expected/actual values, wait times
        assertThat(FailureGrouper.maskRunSpecificValues(
                "Expected status code <201> but was <500>. By.id: pay-now-btn (tried for 10 second(s))"))
                .isEqualTo("Expected status code <201> but was <500>. By.id: pay-now-btn (tried for 10 second(s))");
    }

    @Test
    void sameErrorFromDifferentSessionsIsOneGroup() {
        List<FailureGroup> groups = FailureGrouper.group(List.of(
                failure("A", "org.openqa.selenium.NoSuchElementException", "no such element: session 1a2b3c4d5e6f7a8b9c0d", LOGIN_STEP),
                failure("B", "org.openqa.selenium.NoSuchElementException", "no such element: session 9f8e7d6c5b4a39281706", LOGIN_STEP)));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).signature()).isEqualTo(
                "org.openqa.selenium.NoSuchElementException: no such element: session <id> @ steps.LoginSteps.submit(LoginSteps.java:42)");
    }

    @Test
    void theSameExceptionTypeAloneDoesNotMergeFailures() {
        List<FailureGroup> groups = FailureGrouper.group(List.of(
                failure("A", "java.lang.AssertionError", "expected [200] but found [500]", LOGIN_STEP),
                failure("B", "java.lang.AssertionError", "expected [200] but found [404]", LOGIN_STEP),
                failure("C", "java.lang.AssertionError", "expected [200] but found [500]", ORDER_STEP)));

        assertThat(groups).hasSize(3);
    }

    @Test
    void withoutAFrameTheFailingStepSeparatesTheGroups() {
        TestFailure first = new TestFailure(new FailedScenario("A", null, null), new FailedStep("sepeti aç", null, null),
                "java.lang.AssertionError", "boom", null, null, null, null);
        TestFailure second = new TestFailure(new FailedScenario("B", null, null), new FailedStep("ödeme yap", null, null),
                "java.lang.AssertionError", "boom", null, null, null, null);

        assertThat(FailureGrouper.group(List.of(first, second))).hasSize(2);
    }

    @Test
    void numbersGroupsLargestFirstAndTiesInLogOrder() {
        List<FailureGroup> groups = FailureGrouper.group(List.of(
                failure("A", "x.OneException", "one", LOGIN_STEP),
                failure("B", "x.TwoException", "two", LOGIN_STEP),
                failure("C", "x.ThreeException", "three", LOGIN_STEP),
                failure("D", "x.TwoException", "two", LOGIN_STEP)));

        assertThat(groups).extracting(FailureGroup::id).containsExactly("G1", "G2", "G3");
        assertThat(groups).extracting(group -> group.representative().exceptionType())
                .containsExactly("x.TwoException", "x.OneException", "x.ThreeException");
        assertThat(groups.get(0).failures()).extracting(failure -> failure.scenario().name()).containsExactly("B", "D");
    }

    @Test
    void failuresWithoutAnExceptionShareOneHonestlyNamedGroup() {
        List<FailureGroup> groups = FailureGrouper.group(List.of(
                new TestFailure(new FailedScenario("A", "a.feature", 3), null, null, null, null, null, null, null),
                new TestFailure(new FailedScenario("B", "b.feature", 9), null, null, null, null, null, null, null)));

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).signature()).isEqualTo(FailureGrouper.NO_EXCEPTION_SIGNATURE);
    }
}
