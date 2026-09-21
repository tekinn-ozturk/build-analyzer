package com.company.buildanalyzer.application.classifier;

import com.company.buildanalyzer.domain.model.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorClassifierTest {

    private final ErrorClassifier classifier = new ErrorClassifier();

    @ParameterizedTest(name = "[{index}] \"{0}\" -> {1}")
    @CsvSource({
            "NoSuchElementException,SELENIUM",
            "TimeoutException,SELENIUM",
            "SessionNotCreatedException,SELENIUM",
            "StaleElementReferenceException,SELENIUM",
            "DependencyResolutionException,MAVEN",
            "Failed scenarios:,CUCUMBER",
            "Agent offline,JENKINS",
            "No node available,JENKINS",
            "Connection refused,INFRA",
            "Connection timed out,INFRA",
            "SomethingCompletelyUnrelated,UNKNOWN"
    })
    void classifiesByKeyword(String logFragment, ErrorCategory expected) {
        assertThat(classifier.classify(logFragment, null)).isEqualTo(expected);
    }

    @Test
    void bareBuildFailureIsMaven() {
        assertThat(classifier.classify("[INFO] BUILD FAILURE", null)).isEqualTo(ErrorCategory.MAVEN);
    }

    @Test
    void usesExceptionTypeArgumentAsSignal() {
        ErrorCategory category = classifier.classify(
                "[INFO] BUILD FAILURE\n[ERROR] Tests failed",
                "org.openqa.selenium.SessionNotCreatedException");
        assertThat(category).isEqualTo(ErrorCategory.SELENIUM);
    }

    @Test
    void specificSignalWinsOverGenericBuildFailure() {
        // A real Selenium failure also prints "BUILD FAILURE" and "Failed scenarios";
        // the root-cause signal must win over the generic Maven marker.
        String log = """
                Failed scenarios:
                login.feature:12 # Scenario: Invalid login
                org.openqa.selenium.TimeoutException: wait timed out
                [INFO] BUILD FAILURE
                """;
        assertThat(classifier.classify(log, "org.openqa.selenium.TimeoutException"))
                .isEqualTo(ErrorCategory.SELENIUM);
    }

    @Test
    void cucumberWinsOverGenericBuildFailureWhenNoException() {
        String log = """
                Failed scenarios:
                login.feature:12 # Scenario: Invalid login
                [INFO] BUILD FAILURE
                """;
        assertThat(classifier.classify(log, null)).isEqualTo(ErrorCategory.CUCUMBER);
    }

    @Test
    void nullAndBlankInputIsUnknown() {
        assertThat(classifier.classify(null, null)).isEqualTo(ErrorCategory.UNKNOWN);
        assertThat(classifier.classify("   ", "  ")).isEqualTo(ErrorCategory.UNKNOWN);
    }
}
