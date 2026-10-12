package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.application.context.SyntheticConsoleLog.Failure;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.TestFailure;
import com.company.buildanalyzer.domain.model.TestSummary;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Every failed test of a build is found, counted once and grouped by error signature; nothing is invented. */
class MultiFailureContextTest {

    private final BuildContextBuilder builder = new BuildContextBuilder(
            new ErrorClassifier(), new RelevantLogExtractor(), List.of(new SeleniumInteractionExtractor()));

    /** Test 2: 30 scenarios, 20 passed, 10 failed — the pretty output and Surefire's report print each failure twice. */
    @Test
    void findsAllTenFailuresOfThirtyScenariosOnceEach() {
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 30; i++) {
            if (i % 3 == 0) {
                log.failed("Failing scenario " + i, Failure.noSuchElement("login-btn"));
            } else {
                log.passed("Passing scenario " + i);
            }
        }

        BuildAnalysisContext ctx = builder.build(log.build());

        assertThat(ctx.buildStatus()).isEqualTo("FAILURE");
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(30, 20, 10, 0));
        assertThat(ctx.failureCount()).isEqualTo(10);
        assertThat(ctx.hasMultipleFailures()).isTrue();
        assertThat(allFailures(ctx)).extracting(failure -> failure.scenario().name())
                .containsExactly("Failing scenario 3", "Failing scenario 6", "Failing scenario 9", "Failing scenario 12",
                        "Failing scenario 15", "Failing scenario 18", "Failing scenario 21", "Failing scenario 24",
                        "Failing scenario 27", "Failing scenario 30");

        TestFailure first = allFailures(ctx).get(0);
        assertThat(first.scenario()).isEqualTo(new FailedScenario("Failing scenario 3", "src/test/resources/features/Shop.feature", 13));
        assertThat(first.step()).isEqualTo(new FailedStep("Giriş butonuna tıkla.", "Shop.feature", 15));
        assertThat(first.exceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");
        assertThat(first.message()).startsWith("no such element: Unable to locate element:").contains("#login-btn");
        assertThat(first.failureLocation())
                .isEqualTo(new FailureLocation("stepdefinitions.LoginSteps.clickLogin", "LoginSteps.java", 31));
        assertThat(first.failedInteraction()).isEqualTo(new FailedInteraction("Selenium", "findElement", "id", "login-btn"));
        assertThat(first.stackTrace()).contains("Session ID:").contains("LoginSteps.java:31");
    }

    /** Test 3: ten failures with the same error (different Selenium sessions) are one group of ten. */
    @Test
    void groupsTenRepeatsOfTheSameErrorIntoOneGroup() {
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 10; i++) {
            log.passed("Passing scenario " + i).failed("Failing scenario " + i, Failure.noSuchElement("login-btn"));
        }

        BuildAnalysisContext ctx = builder.build(log.build());

        assertThat(ctx.failureGroups()).hasSize(1);
        FailureGroup group = ctx.failureGroups().get(0);
        assertThat(group.id()).isEqualTo("G1");
        assertThat(group.failures()).hasSize(10);
        assertThat(group.signature())
                .startsWith("org.openqa.selenium.NoSuchElementException: no such element")
                .endsWith(" @ stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)");
        // every failure keeps its own evidence (here: its own session), only the group is shared
        assertThat(group.failures()).extracting(TestFailure::stackTrace).doesNotHaveDuplicates();
    }

    /** Test 4: three error types stay apart and are counted per type, largest group first. */
    @Test
    void keepsDifferentErrorTypesInSeparateGroups() {
        SyntheticConsoleLog log = new SyntheticConsoleLog()
                .failed("Login 1", Failure.noSuchElement("login-btn"))
                .failed("Pay 1", Failure.timeout("pay-now-btn"))
                .passed("Browse")
                .failed("Login 2", Failure.noSuchElement("login-btn"))
                .failed("Cart total", Failure.assertion("120.00", "100.00"))
                .failed("Pay 2", Failure.timeout("pay-now-btn"))
                .failed("Login 3", Failure.noSuchElement("login-btn"));

        BuildAnalysisContext ctx = builder.build(log.build());

        assertThat(ctx.failureGroups()).extracting(FailureGroup::id).containsExactly("G1", "G2", "G3");
        assertThat(ctx.failureGroups()).extracting(group -> group.representative().exceptionType()).containsExactly(
                "org.openqa.selenium.NoSuchElementException",
                "org.openqa.selenium.TimeoutException",
                "java.lang.AssertionError");
        assertThat(ctx.failureGroups()).extracting(group -> group.failures().size()).containsExactly(3, 2, 1);
        assertThat(ctx.failureGroups().get(1).failures()).extracting(failure -> failure.scenario().name())
                .containsExactly("Pay 1", "Pay 2");
        assertThat(ctx.failureGroups().get(2).representative().message()).isEqualTo("expected [120.00] but found [100.00]");
        // the build-level category stays one value; the per-failure types live in the groups
        assertThat(ctx.errorCategory()).isEqualTo(ErrorCategory.SELENIUM);
        assertThat(ctx.exceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");
    }

    /** Test 5: 1500 scenarios, 5 failures — only the 5 are extracted, quickly. */
    @Test
    void findsTheFewFailuresOfFifteenHundredScenarios() {
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 1500; i++) {
            if (i % 300 == 0) {
                log.failed("Failing scenario " + i, i == 1500 ? Failure.assertion("1", "2") : Failure.timeout("pay-now-btn"));
            } else {
                log.passed("Passing scenario " + i);
            }
        }
        String consoleLog = log.build();

        long startedAt = System.nanoTime();
        BuildAnalysisContext ctx = builder.build(consoleLog);
        long tookMs = (System.nanoTime() - startedAt) / 1_000_000;

        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(1500, 1495, 5, 0));
        assertThat(ctx.failureCount()).isEqualTo(5);
        assertThat(ctx.failureGroups()).extracting(group -> group.failures().size()).containsExactly(4, 1);
        assertThat(consoleLog.lines().count()).isGreaterThan(7_000);
        assertThat(tookMs).as("parsing time in ms").isLessThan(5_000);
    }

    /** Test 7: a listed failed scenario without any printed exception is kept — with nothing invented. */
    @Test
    void keepsAFailureWithoutStackTraceAndInventsNothing() {
        String log = """
                Scenario: Login works          # features/login.feature:3
                  Given the login page is open # steps.LoginSteps.open()
                Scenario: Checkout pays        # features/checkout.feature:7
                  Given a full cart            # steps.CartSteps.fullCart()
                      java.lang.IllegalStateException: cart service returned 503
                      \tat steps.CartSteps.fullCart(CartSteps.java:20)

                Failed scenarios:
                features/login.feature:3 # Login works
                features/checkout.feature:7 # Checkout pays

                2 Scenarios (2 failed)
                Finished: FAILURE
                """;

        BuildAnalysisContext ctx = builder.build(log);

        assertThat(ctx.failureCount()).isEqualTo(2);
        TestFailure withoutEvidence = allFailures(ctx).stream()
                .filter(failure -> failure.scenario().name().equals("Login works")).findFirst().orElseThrow();
        assertThat(withoutEvidence.scenario()).isEqualTo(new FailedScenario("Login works", "features/login.feature", 3));
        assertThat(withoutEvidence.step()).isNull();
        assertThat(withoutEvidence.exceptionType()).isNull();
        assertThat(withoutEvidence.message()).isNull();
        assertThat(withoutEvidence.stackTrace()).isNull();
        assertThat(withoutEvidence.failureLocation()).isNull();
        assertThat(ctx.failureGroups()).extracting(FailureGroup::signature)
                .contains(FailureGrouper.NO_EXCEPTION_SIGNATURE);
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(2, 0, 2, 0));
    }

    /** Test 7: an exception without frames gives no file/line — the location is not guessed. */
    @Test
    void anExceptionWithoutFramesHasNoLocation() {
        String log = """
                [ERROR] LoginTest.invalidPassword -- Time elapsed: 0.2 s <<< FAILURE!
                java.lang.AssertionError: expected 401 but was 200
                [ERROR] LoginTest.lockedUser -- Time elapsed: 0.1 s <<< FAILURE!
                java.lang.AssertionError: expected 423 but was 200
                [ERROR] Tests run: 5, Failures: 2, Errors: 0, Skipped: 0
                Finished: FAILURE
                """;

        BuildAnalysisContext ctx = builder.build(log);

        assertThat(ctx.failureCount()).isEqualTo(2);
        assertThat(allFailures(ctx)).allSatisfy(failure -> {
            assertThat(failure.failureLocation()).isNull();
            assertThat(failure.step()).isNull();
            assertThat(failure.scenario().featureFile()).isNull();
            assertThat(failure.scenario().featureLine()).isNull();
        });
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(5, 3, 2, 0));
    }

    /** Plain JUnit/TestNG through Surefire (no BDD output): the runner's report headers name the tests. */
    @Test
    void findsPlainJUnitFailuresFromSurefireReports() {
        String log = """
                [INFO] Running com.acme.LoginTest
                [ERROR] Tests run: 3, Failures: 1, Errors: 1, Skipped: 0, Time elapsed: 0.5 s <<< FAILURE! -- in com.acme.LoginTest
                [ERROR] com.acme.LoginTest.invalidPassword -- Time elapsed: 0.2 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <401> but was: <200>
                \tat org.junit.jupiter.api.AssertionUtils.fail(AssertionUtils.java:55)
                \tat com.acme.LoginTest.invalidPassword(LoginTest.java:42)

                [ERROR] com.acme.LoginTest.serverDown -- Time elapsed: 0.1 s <<< ERROR!
                java.net.ConnectException: Connection refused: localhost/127.0.0.1:54321
                \tat java.base/sun.nio.ch.Net.connect0(Native Method)
                \tat com.acme.LoginTest.serverDown(LoginTest.java:58)

                [INFO] Results:
                [ERROR] Tests run: 3, Failures: 1, Errors: 1, Skipped: 0
                [INFO] BUILD FAILURE
                Finished: FAILURE
                """;

        BuildAnalysisContext ctx = builder.build(log);

        assertThat(allFailures(ctx)).extracting(failure -> failure.scenario().name())
                .containsExactly("com.acme.LoginTest.invalidPassword", "com.acme.LoginTest.serverDown");
        assertThat(allFailures(ctx)).extracting(TestFailure::failureLocation).containsExactly(
                new FailureLocation("com.acme.LoginTest.invalidPassword", "LoginTest.java", 42),
                new FailureLocation("com.acme.LoginTest.serverDown", "LoginTest.java", 58));
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(3, 1, 2, 0));
        assertThat(ctx.failureGroups()).hasSize(2);
    }

    /** JUnit 4 (Surefire 2.x style headers): ComparisonFailure is a failure type too, so no failed test is lost. */
    @Test
    void findsJUnit4ComparisonFailures() {
        String log = """
                [ERROR] testName(com.acme.NameTest)  Time elapsed: 0.01 s  <<< FAILURE!
                org.junit.ComparisonFailure: expected:<[foo]> but was:<[bar]>
                \tat org.junit.Assert.assertEquals(Assert.java:117)
                \tat com.acme.NameTest.testName(NameTest.java:14)

                [ERROR] other(com.acme.NameTest)  Time elapsed: 0.01 s  <<< ERROR!
                java.lang.NullPointerException
                \tat com.acme.NameTest.other(NameTest.java:20)

                [ERROR] Tests run: 2, Failures: 1, Errors: 1, Skipped: 0
                Finished: FAILURE
                """;

        BuildAnalysisContext ctx = builder.build(log);

        assertThat(allFailures(ctx)).extracting(TestFailure::exceptionType)
                .containsExactly("org.junit.ComparisonFailure", "java.lang.NullPointerException");
        assertThat(allFailures(ctx)).extracting(TestFailure::message).containsExactly("expected:<[foo]> but was:<[bar]>", null);
        assertThat(allFailures(ctx).get(0).scenario().name()).isEqualTo("testName(com.acme.NameTest)");
    }

    /** Scenario-outline rows share a name: two failed rows printed twice (pretty + Surefire) are two failures. */
    @Test
    void countsScenarioOutlineRowsWithTheSameNameSeparatelyAlsoWithWindowsLineEndings() {
        String log = String.join("\r\n",
                "Scenario Outline: Login as <user>   # features/login.feature:10",
                "  * Giriş yap.   # steps.L.login()",
                "      java.lang.AssertionError: user admin rejected",
                "      \tat steps.L.login(L.java:5)",
                "",
                "Scenario Outline: Login as <user>   # features/login.feature:11",
                "  * Giriş yap.   # steps.L.login()",
                "      java.lang.AssertionError: user guest rejected",
                "      \tat steps.L.login(L.java:5)",
                "",
                "2 Scenarios (2 failed)",
                "[ERROR] Tests run: 2, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 1 s <<< FAILURE! -- in R",
                "[ERROR] Login.Login as <user> -- Time elapsed: 0.1 s <<< FAILURE!",
                "java.lang.AssertionError: user admin rejected",
                "\tat steps.L.login(L.java:5)",
                "",
                "[ERROR] Login.Login as <user> -- Time elapsed: 0.1 s <<< FAILURE!",
                "java.lang.AssertionError: user guest rejected",
                "\tat steps.L.login(L.java:5)",
                "",
                "Finished: FAILURE");

        BuildAnalysisContext ctx = builder.build(log);

        assertThat(ctx.failureCount()).isEqualTo(2);
        assertThat(allFailures(ctx)).extracting(failure -> failure.scenario().featureLine()).containsExactly(10, 11);
    }

    /** Build #7 prints its one failure three times (pretty, Surefire report, Surefire summary): still one failure. */
    @Test
    void build7HasExactlyOneFailure() throws IOException {
        BuildAnalysisContext ctx = builder.build(fixture("logs/build-7-selenium-failure.log"));

        assertThat(ctx.failureCount()).isEqualTo(1);
        assertThat(ctx.hasMultipleFailures()).isFalse();
        TestFailure failure = ctx.failureGroups().get(0).representative();
        assertThat(failure.scenario()).isEqualTo(new FailedScenario("Open Google", "src/test/resources/features/Example.feature", 4));
        assertThat(failure.step()).isEqualTo(new FailedStep("Arama kutusuna tıkla.", "Example.feature", 6));
        assertThat(failure.failureLocation())
                .isEqualTo(new FailureLocation("stepdefinitions.ExampleSteps.clickSearchBox", "ExampleSteps.java", 28));
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(1, 0, 1, 0));
    }

    /** The API sample: the listed scenario and its pretty failure are one, and the response body is kept as evidence. */
    @Test
    void keepsTheLinesPrintedAfterTheExceptionAsEvidence() throws IOException {
        BuildAnalysisContext ctx = builder.build(fixture("logs/samples/api-assertion-failure.log"));

        assertThat(ctx.failureCount()).isEqualTo(1);
        TestFailure failure = ctx.failureGroups().get(0).representative();
        assertThat(failure.scenario().name()).isEqualTo("Create order with valid basket");
        assertThat(failure.exceptionType()).isEqualTo("java.lang.AssertionError");
        assertThat(failure.logExcerpt())
                .contains("Response body:")
                .contains("constraint [fk_order_customer]")
                .doesNotContain("at io.restassured")
                .doesNotContain("And the order id should be returned");
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(1, 0, 1, 0));
    }

    /** Test 8: Maven, Jenkins and infrastructure failures outside any test are not test failures. */
    @Test
    void nonTestFailuresHaveNoFailureGroups() throws IOException {
        BuildAnalysisContext maven = builder.build(fixture("logs/samples/maven-compile-error.log"));
        assertThat(maven.failureGroups()).isEmpty();
        assertThat(maven.testSummary()).isNull();
        assertThat(maven.errorCategory()).isEqualTo(ErrorCategory.MAVEN);

        BuildAnalysisContext pipeline = builder.build("""
                [Pipeline] node
                Still waiting to schedule task
                Agent offline
                hudson.AbortException: No node available for label 'windows'
                \tat org.jenkinsci.plugins.workflow.steps.ExecutorStep.run(ExecutorStep.java:120)
                Finished: FAILURE
                """);
        assertThat(pipeline.failureGroups()).isEmpty();
        assertThat(pipeline.exceptionType()).isEqualTo("hudson.AbortException");

        BuildAnalysisContext afterTheTests = builder.build("""
                Scenario: Login works      # features/login.feature:3
                  Given the login page is open # steps.LoginSteps.open()
                1 Scenarios (1 passed)
                [INFO] BUILD SUCCESS
                [Pipeline] sh
                java.io.IOException: Unable to upload report to s3://reports
                \tat com.acme.ci.Upload.run(Upload.java:12)
                Finished: FAILURE
                """);
        assertThat(afterTheTests.failureGroups()).as("an exception after the test run is not a test failure").isEmpty();
        assertThat(afterTheTests.testSummary()).isEqualTo(new TestSummary(1, 1, 0, 0));
    }

    /** A scenario that only logged an exception is not a failure when Cucumber's failed list says otherwise. */
    @Test
    void theFailedScenariosListDecidesWhichScenariosFailed() {
        String log = """
                Scenario: Retry works       # features/retry.feature:3
                  Given the service retries # steps.RetrySteps.retries()
                      java.net.SocketTimeoutException: Read timed out (retried, test continues)
                      \tat steps.RetrySteps.retries(RetrySteps.java:15)
                Scenario: Login works       # features/login.feature:3
                  Given the login page is open # steps.LoginSteps.open()
                      java.lang.AssertionError: title was "Error"
                      \tat steps.LoginSteps.open(LoginSteps.java:9)

                Failed scenarios:
                features/login.feature:3 # Login works

                2 Scenarios (1 failed, 1 passed)
                Finished: FAILURE
                """;

        BuildAnalysisContext ctx = builder.build(log);

        assertThat(allFailures(ctx)).extracting(failure -> failure.scenario().name()).containsExactly("Login works");
    }

    /** Test 9: a green build: no failures, its test counts are still read. */
    @Test
    void successfulBuildHasCountsButNoFailures() {
        SyntheticConsoleLog log = new SyntheticConsoleLog().passed("A").passed("B").passed("C");

        BuildAnalysisContext ctx = builder.build(log.build());

        assertThat(ctx.isSuccessful()).isTrue();
        assertThat(ctx.failureGroups()).isEmpty();
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(3, 3, 0, 0));
    }

    private static List<TestFailure> allFailures(BuildAnalysisContext ctx) {
        return ctx.failureGroups().stream().flatMap(group -> group.failures().stream())
                .sorted((a, b) -> Integer.compare(order(a), order(b)))
                .toList();
    }

    /** Log order of a synthetic failure (its feature line), so the assertions do not depend on group order. */
    private static int order(TestFailure failure) {
        return failure.scenario().featureLine() == null ? Integer.MAX_VALUE : failure.scenario().featureLine();
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = MultiFailureContextTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("test resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
