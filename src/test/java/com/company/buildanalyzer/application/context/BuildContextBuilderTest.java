package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureLocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuildContextBuilderTest {

    private final BuildContextBuilder builder = new BuildContextBuilder(
            new ErrorClassifier(), new RelevantLogExtractor(), List.of(new SeleniumInteractionExtractor()));

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
        assertThat(ctx.failedScenario()).isEqualTo(new FailedScenario(
                "Login with invalid credentials", "src/test/resources/features/login.feature", 12));
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

    /** Real console output of Mini-UI-Automation #7 (Cucumber pretty formatter, JUnit4 runner). */
    @Test
    void parsesTheRealSeleniumFailureOfBuild7() throws IOException {
        BuildAnalysisContext ctx = builder.build(fixture("logs/build-7-selenium-failure.log"));

        assertThat(ctx.buildStatus()).isEqualTo("FAILURE");
        assertThat(ctx.errorCategory()).isEqualTo(ErrorCategory.SELENIUM);
        assertThat(ctx.exceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");

        // scenario: clean name, no "# feature:line" suffix
        assertThat(ctx.failedScenario()).isEqualTo(
                new FailedScenario("Open Google", "src/test/resources/features/Example.feature", 4));

        // file / line / step definition from the first application frame
        assertThat(ctx.failureLocation()).isEqualTo(
                new FailureLocation("stepdefinitions.ExampleSteps.clickSearchBox", "ExampleSteps.java", 28));

        // the failing step is the one with the error — not "Google'a git." printed just before it
        assertThat(ctx.failedStep()).isEqualTo(new FailedStep("Arama kutusuna tıkla.", "Example.feature", 6));

        // Selenium's command/locator, from "Command: [..., findElement {using=name, value=qqqqqqqq}]"
        assertThat(ctx.failedInteraction())
                .isEqualTo(new FailedInteraction("Selenium", "findElement", "name", "qqqqqqqq"));

        // the whole Selenium exception block, not just its first line
        assertThat(ctx.stackTrace())
                .startsWith("org.openqa.selenium.NoSuchElementException: no such element")
                .contains("(Session info: chrome=153.0.8010.48)")
                .contains("Build info: version: '4.34.0'")
                .contains("System info: os.name: 'Windows 10'")
                .contains("Driver info: org.openqa.selenium.chrome.ChromeDriver")
                .contains("Command: [b9cc324322adeb37cb93984b824b8efd, findElement {using=name, value=qqqqqqqq}]")
                .contains("Capabilities {acceptInsecureCerts: false")
                .contains("Session ID: b9cc324322adeb37cb93984b824b8efd")
                .contains("at org.openqa.selenium.remote.RemoteWebDriver.findElement(RemoteWebDriver.java:361)")
                .contains("at stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)")
                .contains("Example.feature:6)")
                // ends with the block: the Surefire summary that follows is not part of it
                .doesNotContain("Tests run:")
                .doesNotContain("BUILD FAILURE");
        // the full stack trace: Selenium's long Capabilities dump is not clipped, every frame is kept
        assertThat(ctx.stackTrace()).doesNotContain("…").doesNotContain("omitted");
        assertThat(ctx.stackTrace().lines().filter(line -> line.startsWith("Capabilities {")).findFirst().orElseThrow())
                .hasSizeGreaterThan(800);
        assertThat(ctx.stackTrace().lines().filter(line -> line.startsWith("at "))).hasSize(16);
    }

    @Test
    void relevantSnippetOfBuild7KeepsTheRootCauseAndDropsTheNoise() throws IOException {
        BuildAnalysisContext ctx = builder.build(fixture("logs/build-7-selenium-failure.log"));
        String snippet = ctx.relevantLogSnippet();

        // what the LLM needs beyond the stack-trace section
        assertThat(snippet)
                .contains("Scenario: Open Google")
                .contains("* Arama kutusuna tıkla. # stepdefinitions.ExampleSteps.clickSearchBox()")
                .contains("<<< ERROR!")
                .contains("*[name='qqqqqqqq']")
                .contains("[INFO] BUILD FAILURE")
                .contains("Found 1 failed steps")
                .contains("ERROR: script returned exit code 1")
                .contains("Finished: FAILURE");
        // noise is gone
        assertThat(snippet)
                .doesNotContain("Downloading from")
                .doesNotContain("Progress (")
                .doesNotContain("[Pipeline]")
                .doesNotContain("git.exe")
                .doesNotContain("Compiling")
                .doesNotContain("Re-run Maven")
                .doesNotContain("Unable to find CDP");   // harmless warning, not a failure signal
        // the stack trace is its own prompt section: its frames are not repeated here
        assertThat(snippet).doesNotContain("at org.openqa.selenium.remote.RemoteWebDriver.findElement");
        // the raw tail is untouched and much bigger
        assertThat(ctx.last200Lines()).contains("Downloading from").contains("[Pipeline]");
        assertThat(snippet.length()).isLessThan(ctx.last200Lines().length() / 5);
    }

    @Test
    void successfulBuildIsNotClassifiedEvenWithSeleniumWarningsInTheLog() {
        String log = """
                Eyl 20, 2026 11:49:46 ÖÖ org.openqa.selenium.devtools.CdpVersionFinder findNearestMatch
                WARNING: Unable to find CDP implementation matching 153
                Scenario: Open Google   # src/test/resources/features/Example.feature:4
                1 Scenarios (1 passed)
                [INFO] BUILD SUCCESS
                Finished: SUCCESS
                """;

        BuildAnalysisContext ctx = builder.build(log);

        assertThat(ctx.buildStatus()).isEqualTo("SUCCESS");
        assertThat(ctx.errorCategory()).isEqualTo(ErrorCategory.NONE);
        assertThat(ctx.isSuccessful()).isTrue();
        assertThat(ctx.failedScenario()).isNull();
        assertThat(ctx.exceptionType()).isNull();
        assertThat(ctx.stackTrace()).isNull();
        assertThat(ctx.failureLocation()).isNull();
        assertThat(ctx.failedStep()).isNull();
        assertThat(ctx.failedInteraction()).isNull();
        assertThat(ctx.last200Lines()).contains("Finished: SUCCESS");
    }

    @Test
    void jenkinsFinalVerdictDecidesSuccessEvenIfAnEarlierMavenStepFailed() {
        String log = """
                [INFO] BUILD FAILURE
                org.openqa.selenium.TimeoutException: timed out
                Finished: FAILURE
                """;

        BuildAnalysisContext ctx = builder.build(log);

        assertThat(ctx.isSuccessful()).isFalse();
        assertThat(ctx.errorCategory()).isEqualTo(ErrorCategory.SELENIUM);
    }

    @Test
    void tailIsCappedAt200Lines() {
        StringBuilder big = new StringBuilder();
        for (int i = 1; i <= 700; i++) {
            big.append("line ").append(i).append('\n');
        }
        BuildAnalysisContext ctx = builder.build(big.toString());

        long lineCount = ctx.last200Lines().lines().count();
        assertThat(lineCount).isEqualTo(200);
        assertThat(ctx.last200Lines()).startsWith("line 501").endsWith("line 700");
    }

    @Test
    void handlesNullAndBlankLogGracefully() {
        BuildAnalysisContext ctx = builder.build("   ");

        assertThat(ctx.buildStatus()).isEqualTo("UNKNOWN");
        assertThat(ctx.failedScenario()).isNull();
        assertThat(ctx.exceptionType()).isNull();
        assertThat(ctx.errorCategory()).isEqualTo(ErrorCategory.UNKNOWN);
        assertThat(ctx.stackTrace()).isNull();
        assertThat(ctx.failureLocation()).isNull();
        assertThat(ctx.last200Lines()).isEmpty();
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = BuildContextBuilderTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("test resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
