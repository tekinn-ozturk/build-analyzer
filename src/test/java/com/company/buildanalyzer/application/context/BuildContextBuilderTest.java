package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildContextBuilderTest {

    private final BuildContextBuilder builder = new BuildContextBuilder(new ErrorClassifier());

    private static final String SAMPLE_LOG = """
            Started by user Tekin Ozturk
            [Pipeline] { (Build and Test)
            [INFO] Scanning for projects...
            [INFO] Building selenium-cucumber-demo 1.0-SNAPSHOT
            Scenario: Invalid login   # login.feature:12
            [ERROR] Tests run: 3, Failures: 0, Errors: 1
            org.openqa.selenium.SessionNotCreatedException: session not created: This version of ChromeDriver only supports Chrome version 123
            \tat org.openqa.selenium.remote.ProtocolHandshake.createSession(ProtocolHandshake.java:130)
            \tat org.openqa.selenium.remote.RemoteWebDriver.startSession(RemoteWebDriver.java:271)
            Caused by: java.lang.IllegalStateException: driver/browser version mismatch
            \t... 15 more

            Failed scenarios:
            src/test/resources/features/login.feature:12 # Scenario: Login with invalid credentials

            1 Scenarios (1 failed)
            [INFO] BUILD FAILURE
            [Pipeline] End of Pipeline
            Finished: FAILURE
            """;

    @Test
    void extractsAllFieldsFromAFailedSeleniumBuild() {
        BuildAnalysisContext ctx = builder.build(SAMPLE_LOG);

        assertThat(ctx.buildStatus()).isEqualTo("FAILURE");
        assertThat(ctx.failedScenario()).isEqualTo("Login with invalid credentials");
        assertThat(ctx.exceptionType()).isEqualTo("org.openqa.selenium.SessionNotCreatedException");
        assertThat(ctx.errorCategory()).isEqualTo(ErrorCategory.SELENIUM);
        assertThat(ctx.stackTrace())
                .startsWith("org.openqa.selenium.SessionNotCreatedException:")
                .contains("at org.openqa.selenium.remote.ProtocolHandshake.createSession")
                .contains("Caused by: java.lang.IllegalStateException")
                .contains("... 15 more");
        // stack trace must stop before unrelated log lines
        assertThat(ctx.stackTrace()).doesNotContain("BUILD FAILURE");
    }

    @Test
    void tailIsCappedAt500Lines() {
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 700; i++) {
            big.append("line ").append(i).append('\n');
        }
        BuildAnalysisContext ctx = builder.build(big.toString());

        long lineCount = ctx.last500Lines().lines().count();
        assertThat(lineCount).isEqualTo(500);
        assertThat(ctx.last500Lines()).startsWith("line 201").endsWith("line 700");
    }

    @Test
    void handlesNullAndBlankLogGracefully() {
        BuildAnalysisContext ctx = builder.build("   ");

        assertThat(ctx.buildStatus()).isEqualTo("UNKNOWN");
        assertThat(ctx.failedScenario()).isNull();
        assertThat(ctx.exceptionType()).isNull();
        assertThat(ctx.errorCategory()).isEqualTo(ErrorCategory.UNKNOWN);
        assertThat(ctx.stackTrace()).isNull();
        assertThat(ctx.last500Lines()).isEmpty();
    }
}
