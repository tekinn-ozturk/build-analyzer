package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.TestFailure;
import com.company.buildanalyzer.domain.model.TestSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real-world console-log shapes that once lost, doubled or invented failures: runner naming variants, logged
 * exceptions, class names containing "Exception", reruns, Gradle, coloured output, encoded paths, REST-assured.
 * Every failed test is counted once, with its own error, and nothing the log does not show is filled in.
 */
class FailureExtractorTest {

    private final BuildContextBuilder builder = new BuildContextBuilder(
            new ErrorClassifier(), new RelevantLogExtractor(), List.of(new SeleniumInteractionExtractor()));

    private static final String ESC = "\u001B";

    private List<TestFailure> failures(String log) {
        return builder.build(log).failureGroups().stream().flatMap(group -> group.failures().stream()).toList();
    }

    /** cucumber-junit (JUnit 4 runner) names repeated scenario names "Name #1", "Name #2" in Surefire's report. */
    @Test
    void outlineRowsWithOneNameAreMatchedToTheirRunnerReports() {
        List<TestFailure> failures = failures("""
                Scenario Outline: Login with user     # src/test/resources/features/Login.feature:10
                  * Kullanici adi girilir.     # stepdefinitions.LoginSteps.enterUser(java.lang.String)
                      java.lang.AssertionError: expected [Welcome] but found [Error]
                      \tat stepdefinitions.LoginSteps.enterUser(LoginSteps.java:20)

                Scenario Outline: Login with user     # src/test/resources/features/Login.feature:11
                  * Kullanici adi girilir.     # stepdefinitions.LoginSteps.enterUser(java.lang.String)
                      java.lang.AssertionError: expected [Welcome] but found [Error]
                      \tat stepdefinitions.LoginSteps.enterUser(LoginSteps.java:20)

                [ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 5.3 s <<< FAILURE! -- in runners.TestRunner
                [ERROR] Login.Login with user #1 -- Time elapsed: 1.2 s <<< FAILURE!
                java.lang.AssertionError: expected [Welcome] but found [Error]
                \tat stepdefinitions.LoginSteps.enterUser(LoginSteps.java:20)

                [ERROR] Login.Login with user #2 -- Time elapsed: 1.1 s <<< FAILURE!
                java.lang.AssertionError: expected [Welcome] but found [Error]
                \tat stepdefinitions.LoginSteps.enterUser(LoginSteps.java:20)

                [ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0
                Finished: FAILURE
                """);

        assertThat(failures).extracting(failure -> failure.scenario().featureLine()).containsExactly(10, 11);
    }

    /** Surefire 2.x prints "scenario(feature)": still the pretty scenario again, not a second failure. */
    @Test
    void surefire2NamesAreMatchedToTheirScenario() {
        List<TestFailure> failures = failures("""
                Scenario: Open Google     # src/test/resources/features/Example.feature:4
                  * Arama kutusuna tikla. # stepdefinitions.ExampleSteps.clickSearchBox()
                      org.openqa.selenium.NoSuchElementException: no such element: #q
                      \tat stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)

                Tests run: 1, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 5.3 sec <<< FAILURE! - in runners.TestRunner
                Open Google(Example)  Time elapsed: 4.573 sec  <<< ERROR!
                org.openqa.selenium.NoSuchElementException: no such element: #q
                \tat stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)

                Tests run: 1, Failures: 0, Errors: 1, Skipped: 0
                Finished: FAILURE
                """);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).scenario().name()).isEqualTo("Open Google");
    }

    /** Build #7 shape (pretty + Surefire reports, no failed list): an exception a passing scenario only logged is no failure. */
    @Test
    void anExceptionAPassingScenarioOnlyLoggedIsNotAFailure() {
        BuildAnalysisContext ctx = builder.build("""
                Scenario: Search products     # src/test/resources/features/Shop.feature:3
                Eki 11, 2026 10:49:50 ÖS com.acme.http.RetryingClient send
                WARNING: request failed, retrying
                java.net.SocketTimeoutException: Read timed out
                \tat com.acme.http.RetryingClient.send(RetryingClient.java:41)
                  * Urun ara.                  # stepdefinitions.ShopSteps.search()

                Scenario: Pay with card     # src/test/resources/features/Shop.feature:8
                  * Odeme butonuna tikla.     # stepdefinitions.ShopSteps.pay()
                      java.lang.AssertionError: expected [PAID] but found [DECLINED]
                      \tat stepdefinitions.ShopSteps.pay(ShopSteps.java:57)
                [ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 5.3 s <<< FAILURE! -- in runners.TestRunner
                [ERROR] Shop.Pay with card -- Time elapsed: 1.2 s <<< FAILURE!
                java.lang.AssertionError: expected [PAID] but found [DECLINED]
                \tat stepdefinitions.ShopSteps.pay(ShopSteps.java:57)

                [ERROR] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
                Finished: FAILURE
                """);

        assertThat(ctx.failureCount()).isEqualTo(1);
        assertThat(ctx.hasMultipleFailures()).isFalse();
        assertThat(ctx.failureGroups().get(0).representative().scenario().name()).isEqualTo("Pay with card");
    }

    /** A scenario that first logs a retried exception and then fails a step: its failure is the step's error. */
    @Test
    void aScenariosFailureIsTheErrorUnderItsFailedStepNotAnExceptionItLoggedBefore() {
        List<TestFailure> failures = failures("""
                Scenario: Pay with card     # src/test/resources/features/Shop.feature:8
                WARNING: request failed, retrying
                java.net.SocketTimeoutException: Read timed out
                \tat com.acme.http.RetryingClient.send(RetryingClient.java:41)
                  * Sepete git.                # stepdefinitions.ShopSteps.openCart()
                  * Odeme butonuna tikla.     # stepdefinitions.ShopSteps.pay()
                      java.lang.AssertionError: expected [PAID] but found [DECLINED]
                      \tat stepdefinitions.ShopSteps.pay(ShopSteps.java:57)

                1 Scenarios (1 failed)
                Finished: FAILURE
                """);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).exceptionType()).isEqualTo("java.lang.AssertionError");
        assertThat(failures.get(0).step()).isEqualTo(new FailedStep("Odeme butonuna tikla.", null, null));
    }

    /** A test class or step class whose name contains "Exception"/"Error" is not an exception header. */
    @Test
    void classNamesContainingExceptionOrErrorAreNotExceptions() {
        assertThat(failures("""
                [INFO] Running com.acme.api.GlobalExceptionHandlerTest
                [ERROR] Tests run: 4, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 0.5 s <<< FAILURE! -- in com.acme.api.GlobalExceptionHandlerTest
                [ERROR] com.acme.api.GlobalExceptionHandlerTest.mapsNotFoundTo404 -- Time elapsed: 0.2 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <404> but was: <500>
                \tat com.acme.api.GlobalExceptionHandlerTest.mapsNotFoundTo404(GlobalExceptionHandlerTest.java:31)

                [ERROR] com.acme.api.GlobalExceptionHandlerTest.mapsConflictTo409 -- Time elapsed: 0.1 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <409> but was: <500>
                \tat com.acme.api.GlobalExceptionHandlerTest.mapsConflictTo409(GlobalExceptionHandlerTest.java:48)

                [ERROR] Tests run: 4, Failures: 2, Errors: 0, Skipped: 0
                Finished: FAILURE
                """)).extracting(TestFailure::message).containsExactly("expected: <404> but was: <500>", "expected: <409> but was: <500>");

        List<TestFailure> bdd = failures("""
                Scenario: Wrong password shows a message     # src/test/resources/features/Login.feature:3
                  * Hata mesaji gorunur.                      # stepdefinitions.LoginErrorSteps.errorShown()

                Scenario: Locked user sees a message     # src/test/resources/features/Login.feature:9
                  * Kilit mesaji gorunur.                     # stepdefinitions.LoginErrorSteps.lockedShown()
                      java.lang.AssertionError: expected [Hesap kilitli] but found []
                      \tat stepdefinitions.LoginErrorSteps.lockedShown(LoginErrorSteps.java:40)

                2 Scenarios (1 failed, 1 passed)
                Finished: FAILURE
                """);
        assertThat(bdd).hasSize(1);
        assertThat(bdd.get(0).scenario().name()).isEqualTo("Locked user sees a message");
        assertThat(bdd.get(0).exceptionType()).isEqualTo("java.lang.AssertionError");
        assertThat(bdd.get(0).message()).isEqualTo("expected [Hesap kilitli] but found []");
    }

    /** rerunFailingTestsCount: a test failing in both runs counts once; a test that passed on rerun (Flakes) not at all. */
    @Test
    void surefireRerunsCountATestOnceAndFlakyTestsNotAtAll() {
        BuildAnalysisContext ctx = builder.build("""
                [INFO] Running com.acme.LoginTest
                [ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0, Flakes: 1, Time elapsed: 0.5 s <<< FAILURE! -- in com.acme.LoginTest
                [ERROR] com.acme.LoginTest.invalidPassword -- Time elapsed: 0.2 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <401> but was: <200>
                \tat com.acme.LoginTest.invalidPassword(LoginTest.java:42)

                [ERROR] com.acme.LoginTest.invalidPassword -- Time elapsed: 0.1 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <401> but was: <200>
                \tat com.acme.LoginTest.invalidPassword(LoginTest.java:42)

                [ERROR] com.acme.LoginTest.flaky -- Time elapsed: 0.1 s <<< ERROR!
                java.net.ConnectException: Connection refused
                \tat com.acme.LoginTest.flaky(LoginTest.java:60)

                [INFO] Results:
                [ERROR] Failures:\s
                [ERROR] com.acme.LoginTest.invalidPassword
                [ERROR]   Run 1: LoginTest.invalidPassword:42 expected: <401> but was: <200>
                [ERROR]   Run 2: LoginTest.invalidPassword:42 expected: <401> but was: <200>
                [WARNING] Flakes:\s
                [WARNING] com.acme.LoginTest.flaky
                [ERROR]   Run 1: LoginTest.flaky:60 » Connect Connection refused
                [INFO]   Run 2: PASS
                [ERROR] Tests run: 4, Failures: 1, Errors: 0, Skipped: 0, Flakes: 1
                Finished: FAILURE
                """);

        assertThat(ctx.failureGroups().stream().flatMap(group -> group.failures().stream()).map(f -> f.scenario().name()))
                .containsExactly("com.acme.LoginTest.invalidPassword");
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(4, 3, 1, 0));
    }

    /** Gradle's FULL format prints frames as "at app//pkg.Class.method(...)": the location is read, so the groups stay apart. */
    @Test
    void gradleFramesWithAClassLoaderPrefixGiveTheLocation() {
        BuildAnalysisContext ctx = builder.build("""
                > Task :test

                LoginTest > invalidPassword() FAILED
                    org.opentest4j.AssertionFailedError: expected: <401> but was: <200>
                        at app//org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:150)
                        at app//com.acme.LoginTest.invalidPassword(LoginTest.java:42)

                OrderTest > create() FAILED
                    org.opentest4j.AssertionFailedError: expected: <401> but was: <200>
                        at app//com.acme.OrderTest.create(OrderTest.java:77)

                com.acme.api.GlobalExceptionHandlerTest > mapsNotFoundTo404() FAILED
                    org.opentest4j.AssertionFailedError at GlobalExceptionHandlerTest.java:31

                12 tests completed, 3 failed
                Finished: FAILURE
                """);

        assertThat(ctx.failureCount()).isEqualTo(3);
        assertThat(ctx.failureGroups()).hasSize(3);
        assertThat(ctx.failureGroups().stream().map(group -> group.representative().failureLocation()).limit(2))
                .containsExactly(new FailureLocation("com.acme.LoginTest.invalidPassword", "LoginTest.java", 42),
                        new FailureLocation("com.acme.OrderTest.create", "OrderTest.java", 77));
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(12, 9, 3, 0));
    }

    /** Gherkin's "Example:" (and Turkish "Örnek:") is a scenario too. */
    @Test
    void exampleIsAScenarioKeyword() {
        List<TestFailure> failures = failures("""
                Scenario: Open home page     # src/test/resources/features/Shop.feature:3
                  * Ana sayfaya git.         # stepdefinitions.ShopSteps.home()

                Example: Pay with card     # src/test/resources/features/Shop.feature:8
                  * Odeme butonuna tikla.     # stepdefinitions.ShopSteps.pay()
                      java.lang.AssertionError: expected [PAID] but found [DECLINED]
                      \tat stepdefinitions.ShopSteps.pay(ShopSteps.java:57)

                Örnek: Kuponla öde     # src/test/resources/features/Odeme.feature:5
                  * Kupon uygula.     # stepdefinitions.ShopSteps.coupon()
                      java.lang.AssertionError: expected [OK] but found [INVALID]
                      \tat stepdefinitions.ShopSteps.coupon(ShopSteps.java:70)
                [ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 5.3 s <<< FAILURE! -- in runners.TestRunner
                [ERROR] Shop.Pay with card -- Time elapsed: 1.2 s <<< FAILURE!
                java.lang.AssertionError: expected [PAID] but found [DECLINED]
                \tat stepdefinitions.ShopSteps.pay(ShopSteps.java:57)

                [ERROR] Odeme.Kuponla öde -- Time elapsed: 1.2 s <<< FAILURE!
                java.lang.AssertionError: expected [OK] but found [INVALID]
                \tat stepdefinitions.ShopSteps.coupon(ShopSteps.java:70)

                [ERROR] Tests run: 3, Failures: 2, Errors: 0, Skipped: 0
                Finished: FAILURE
                """);

        assertThat(failures).extracting(failure -> failure.scenario().name()).containsExactly("Pay with card", "Kuponla öde");
    }

    /** Cucumber's failed list prints the feature as a URI ("Odeme%20akisi.feature"), pretty as a path. */
    @Test
    void aPercentEncodedFailedListEntryIsTheSameScenario() {
        List<TestFailure> failures = failures("""
                Scenario: Pay with card   # src/test/resources/features/Odeme akisi.feature:5
                  * Ode.                  # steps.PaySteps.pay()
                      java.lang.IllegalStateException: payment service returned 503
                      \tat steps.PaySteps.pay(PaySteps.java:20)

                Failed scenarios:
                file:///C:/ws/Shop/src/test/resources/features/Odeme%20akisi.feature:5 # Pay with card

                1 Scenarios (1 failed)
                Finished: FAILURE
                """);

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0).exceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(failures.get(0).scenario().featureFile()).isEqualTo("src/test/resources/features/Odeme akisi.feature");
    }

    /** A multi-module build: Surefire's totals count both modules; the failures stay in log order. */
    @Test
    void mixedModulesKeepLogOrderAndSurefireTotals() {
        BuildAnalysisContext ctx = builder.build("""
                [INFO] Running com.acme.api.OrderApiTest
                [ERROR] Tests run: 120, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 3.1 s <<< FAILURE! -- in com.acme.api.OrderApiTest
                [ERROR] com.acme.api.OrderApiTest.createOrder -- Time elapsed: 0.2 s <<< FAILURE!
                org.opentest4j.AssertionFailedError: expected: <201> but was: <500>
                \tat com.acme.api.OrderApiTest.createOrder(OrderApiTest.java:42)

                [INFO] Results:
                [ERROR] Tests run: 120, Failures: 1, Errors: 0, Skipped: 0
                [INFO] Running runners.TestRunner

                Scenario: Open Google     # src/test/resources/features/Example.feature:4
                  * Arama kutusuna tikla. # stepdefinitions.ExampleSteps.clickSearchBox()
                      org.openqa.selenium.NoSuchElementException: no such element: #q
                      \tat stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)

                5 Scenarios (1 failed, 4 passed)
                [ERROR] Tests run: 5, Failures: 0, Errors: 1, Skipped: 0, Time elapsed: 5.3 s <<< FAILURE! -- in runners.TestRunner
                [ERROR] Example.Open Google -- Time elapsed: 4.5 s <<< ERROR!
                org.openqa.selenium.NoSuchElementException: no such element: #q
                \tat stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)

                [ERROR] Tests run: 5, Failures: 0, Errors: 1, Skipped: 0
                Finished: FAILURE
                """);

        assertThat(ctx.failureGroups()).extracting(group -> group.representative().scenario().name())
                .containsExactly("com.acme.api.OrderApiTest.createOrder", "Open Google");
        assertThat(ctx.testSummary()).isEqualTo(new TestSummary(125, 123, 2, 0));
    }

    /** Coloured pretty output: no "morg.openqa..." type, the step is found, and colour codes are no evidence. */
    @Test
    void colouredOutputGivesTheRealExceptionType() {
        BuildAnalysisContext ctx = builder.build(String.join("\r\n",
                ESC + "[37mScenario: Open Google" + ESC + "[0m     " + ESC + "[90m# src/test/resources/features/Example.feature:4" + ESC + "[0m",
                "  " + ESC + "[31m* " + ESC + "[0m" + ESC + "[31mArama kutusuna tikla." + ESC + "[0m " + ESC
                        + "[90m# stepdefinitions.ExampleSteps.clickSearchBox()" + ESC + "[0m",
                "      " + ESC + "[31morg.openqa.selenium.NoSuchElementException: no such element: #q",
                "      \tat stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)" + ESC + "[0m",
                "  " + ESC + "[36m* " + ESC + "[0m" + ESC + "[36mSonuclar gorunur." + ESC + "[0m " + ESC
                        + "[90m# stepdefinitions.ExampleSteps.resultsShown()" + ESC + "[0m",
                "",
                "1 Scenarios (1 failed)",
                "Finished: FAILURE"));

        TestFailure failure = ctx.failureGroups().get(0).representative();
        assertThat(failure.exceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");
        assertThat(ctx.exceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");
        assertThat(failure.logExcerpt()).isNull();
    }

    /** The log excerpt ends before the next scenario's tags and skips bare "[INFO]" lines. */
    @Test
    void theLogExcerptEndsAtTheNextScenario() {
        List<TestFailure> failures = failures("""
                Scenario: Open cart     # features/Cart.feature:4
                  * Sepete git.   # steps.CartSteps.open()
                      java.lang.AssertionError: cart empty
                      \tat steps.CartSteps.open(CartSteps.java:9)
                [INFO]\s
                Cart service answered 503

                @smoke @JIRA-1234
                Scenario: Pay     # features/Cart.feature:9
                  * Ode.   # steps.CartSteps.pay()
                2 Scenarios (1 failed, 1 passed)
                Finished: FAILURE
                """);

        assertThat(failures.get(0).logExcerpt()).isEqualTo("Cart service answered 503");
    }

    /** REST-assured's first line is only "1 expectation failed."; the status codes keep two failures apart. */
    @Test
    void restAssuredFailuresWithDifferentStatusCodesAreDifferentGroups() {
        BuildAnalysisContext ctx = builder.build("""
                Scenario: Register with empty password    # features/users.feature:12
                  Then the response status should be 201    # com.acme.steps.UserSteps.statusShouldBe(int)
                      java.lang.AssertionError: 1 expectation failed.
                      Expected status code <201> but was <500>.

                      \tat com.acme.steps.UserSteps.statusShouldBe(UserSteps.java:64)

                Scenario: Register with short password    # features/users.feature:20
                  Then the response status should be 201    # com.acme.steps.UserSteps.statusShouldBe(int)
                      java.lang.AssertionError: 1 expectation failed.
                      Expected status code <200> but was <404>.

                Failed scenarios:
                features/users.feature:12 # Register with empty password
                features/users.feature:20 # Register with short password

                2 Scenarios (2 failed)
                Finished: FAILURE
                """);

        assertThat(ctx.failureGroups()).hasSize(2);
        assertThat(ctx.failureGroups()).extracting(group -> group.representative().message()).containsExactly(
                "1 expectation failed. Expected status code <201> but was <500>.",
                "1 expectation failed. Expected status code <200> but was <404>.");
    }

    /** A line of a few thousand spaces (cleared progress output) does not stall the analysis. */
    @Test
    void longWhitespaceLinesAreParsedQuickly() {
        String padding = " ".repeat(5_000);
        String log = String.join("\n", padding + "y", padding, "\t".repeat(5_000) + "Time elapsed: x",
                "Scenario: A # a.feature:1", "Finished: FAILURE");

        long startedAt = System.nanoTime();
        builder.build(log);

        assertThat((System.nanoTime() - startedAt) / 1_000_000).as("ms").isLessThan(2_000);
    }
}
