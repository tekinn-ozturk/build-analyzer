package com.company.buildanalyzer.application.context;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Builds a realistic Jenkins console log of a Maven + Cucumber (JUnit runner) + Selenium run with any number of
 * passed and failed scenarios, shaped like the real build #7 log: the pretty formatter prints every scenario (a
 * failed step is followed by its exception), Cucumber prints its summary, then Surefire prints every failure
 * <b>again</b> ({@code [ERROR] Feature.Scenario -- Time elapsed ... <<< ERROR!} + the same exception), then its totals.
 * Every failure gets its own Selenium session id, as in reality.
 */
public final class SyntheticConsoleLog {

    /** One kind of failure: the step it breaks, its exception block and the step definition it is thrown from. */
    public record Failure(String step, String stepDefinition, String header, List<String> messageLines, List<String> frames) {

        public static Failure noSuchElement(String locator) {
            return new Failure("Giriş butonuna tıkla.", "stepdefinitions.LoginSteps.clickLogin()",
                    "org.openqa.selenium.NoSuchElementException: no such element: Unable to locate element: "
                            + "{\"method\":\"css selector\",\"selector\":\"#" + locator + "\"}",
                    List.of("  (Session info: chrome=153.0.8010.48)",
                            "Driver info: org.openqa.selenium.chrome.ChromeDriver",
                            "Command: [{session}, findElement {using=id, value=" + locator + "}]",
                            "Session ID: {session}"),
                    List.of("at org.openqa.selenium.remote.RemoteWebDriver.findElement(RemoteWebDriver.java:361)",
                            "at stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)"));
        }

        public static Failure timeout(String locator) {
            return new Failure("Ödeme butonuna tıkla.", "stepdefinitions.CheckoutSteps.clickPay()",
                    "org.openqa.selenium.TimeoutException: Expected condition failed: waiting for element to be clickable: "
                            + "By.id: " + locator + " (tried for 10 second(s) with 500 milliseconds interval)",
                    List.of("Driver info: org.openqa.selenium.chrome.ChromeDriver", "Session ID: {session}"),
                    List.of("at org.openqa.selenium.support.ui.WebDriverWait.timeoutException(WebDriverWait.java:84)",
                            "at pages.CheckoutPage.clickPayNow(CheckoutPage.java:41)",
                            "at stepdefinitions.CheckoutSteps.clickPay(CheckoutSteps.java:33)"));
        }

        public static Failure assertion(String expected, String actual) {
            return new Failure("Sepet toplamını kontrol et.", "stepdefinitions.CartSteps.totalShouldBe()",
                    "java.lang.AssertionError: expected [" + expected + "] but found [" + actual + "]",
                    List.of(),
                    List.of("at org.testng.Assert.fail(Assert.java:110)",
                            "at stepdefinitions.CartSteps.totalShouldBe(CartSteps.java:57)"));
        }

        /** The same exception, thrown from a numbered place: N distinct failure groups for limit tests. */
        public static Failure distinct(int number) {
            return new Failure("Adım " + number + " çalıştır.", "stepdefinitions.ManySteps.step" + number + "()",
                    "java.lang.IllegalStateException: Durum " + number + " beklenmiyordu",
                    List.of(),
                    List.of("at stepdefinitions.ManySteps.step" + number + "(ManySteps.java:" + (100 + number) + ")"));
        }
    }

    private static final String FEATURE = "src/test/resources/features/Shop.feature";

    private final List<String> pretty = new ArrayList<>();
    private final List<String> surefire = new ArrayList<>();
    private int passed;
    private int failed;
    private int featureLine = 3;

    public SyntheticConsoleLog passed(String scenario) {
        scenarioHeader(scenario);
        pretty.add("  * Sepete git.                 # stepdefinitions.CartSteps.openCart()");
        pretty.add("  * Sepet sayfası açılır.       # stepdefinitions.CartSteps.cartIsOpen()");
        pretty.add("");
        passed++;
        return this;
    }

    public SyntheticConsoleLog failed(String scenario, Failure failure) {
        int scenarioLine = scenarioHeader(scenario);
        String session = UUID.nameUUIDFromBytes(scenario.getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        List<String> block = new ArrayList<>();
        block.add(failure.header());
        failure.messageLines().forEach(line -> block.add(line.replace("{session}", session)));
        failure.frames().forEach(frame -> block.add("\t" + frame));
        block.add("\tat ✽." + failure.step() + "(file:///C:/ProgramData/Jenkins/.jenkins/workspace/Shop-UI/" + FEATURE
                + ":" + (scenarioLine + 2) + ")");

        pretty.add("  * Sepete git.                 # stepdefinitions.CartSteps.openCart()");
        pretty.add("  * " + failure.step() + "     # " + failure.stepDefinition());
        block.forEach(line -> pretty.add("      " + line));
        pretty.add("");

        surefire.add("[ERROR] Shop." + scenario + " -- Time elapsed: 1.234 s <<< ERROR!");
        surefire.addAll(block);
        surefire.add("");
        failed++;
        return this;
    }

    public String build() {
        int total = passed + failed;
        List<String> log = new ArrayList<>(List.of(
                "Started by user qa-lead",
                "[Pipeline] Start of Pipeline",
                "[Pipeline] node",
                "Running on Jenkins in C:\\ProgramData\\Jenkins\\.jenkins\\workspace\\Shop-UI",
                "[Pipeline] { (Build and Test)",
                "[Pipeline] bat",
                "[INFO] --- surefire:3.5.3:test (default-test) @ shop-ui-tests ---",
                "[INFO] Running runners.TestRunner",
                ""));
        log.addAll(pretty);
        log.add(total + " Scenarios (" + (failed > 0 ? failed + " failed, " : "") + passed + " passed)");
        log.add("0m42.123s");
        log.add("");
        log.add("[ERROR] Tests run: " + total + ", Failures: 0, Errors: " + failed
                + ", Skipped: 0, Time elapsed: 42.5 s <<< FAILURE! -- in runners.TestRunner");
        log.addAll(surefire);
        log.addAll(List.of(
                "[INFO] ",
                "[INFO] Results:",
                "[INFO] ",
                "[ERROR] Errors: ",
                "[ERROR]   (" + failed + " errors listed above)",
                "[INFO] ",
                "[ERROR] Tests run: " + total + ", Failures: 0, Errors: " + failed + ", Skipped: 0",
                "[INFO] ",
                "[INFO] BUILD " + (failed > 0 ? "FAILURE" : "SUCCESS"),
                "[Pipeline] }",
                "[Pipeline] End of Pipeline",
                failed > 0 ? "ERROR: script returned exit code 1" : "",
                "Finished: " + (failed > 0 ? "FAILURE" : "SUCCESS")));
        return String.join("\n", log) + "\n";
    }

    private int scenarioHeader(String scenario) {
        int line = featureLine;
        featureLine += 5;
        pretty.add("@regression");
        pretty.add("Scenario: " + scenario + "     # " + FEATURE + ":" + line);
        return line;
    }
}
