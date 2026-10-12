package com.company.buildanalyzer.api.mapper;

import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.TestFailure;
import com.company.buildanalyzer.domain.model.TestSummary;

import java.math.BigDecimal;
import java.util.List;

/**
 * A finished analysis of a build with three failed tests in two groups: G1 (two scenarios, analysed),
 * G2 (one plain JUnit test without a feature file, over the call limit and therefore not analysed).
 */
public final class MultiFailureResult {

    public static final TestFailure LOGIN_1 = new TestFailure(
            new FailedScenario("Login 1", "src/test/resources/features/Login.feature", 3),
            new FailedStep("Giriş butonuna tıkla.", "Login.feature", 5),
            "org.openqa.selenium.NoSuchElementException",
            "no such element: Unable to locate element: #login-btn",
            "org.openqa.selenium.NoSuchElementException: no such element: Unable to locate element: #login-btn\n"
                    + "Session ID: 1a2b3c4d5e6f7a8b9c0d\n"
                    + "at stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)",
            new FailureLocation("stepdefinitions.LoginSteps.clickLogin", "LoginSteps.java", 31),
            new FailedInteraction("Selenium", "findElement", "id", "login-btn"),
            null);

    public static final TestFailure LOGIN_2 = new TestFailure(
            new FailedScenario("Login 2", "src/test/resources/features/Login.feature", 9),
            new FailedStep("Giriş butonuna tıkla.", "Login.feature", 11),
            "org.openqa.selenium.NoSuchElementException",
            "no such element: Unable to locate element: #login-btn",
            "org.openqa.selenium.NoSuchElementException: no such element: Unable to locate element: #login-btn\n"
                    + "Session ID: 9f8e7d6c5b4a39281706\n"
                    + "at stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)",
            new FailureLocation("stepdefinitions.LoginSteps.clickLogin", "LoginSteps.java", 31),
            new FailedInteraction("Selenium", "findElement", "id", "login-btn"),
            null);

    /** A plain JUnit test: no feature file, no step, no application frame. */
    public static final TestFailure ORDER_API = new TestFailure(
            new FailedScenario("com.acme.OrderApiTest.createOrder", null, null),
            null,
            "java.lang.AssertionError",
            "expected [201] but found [500]",
            "java.lang.AssertionError: expected [201] but found [500]",
            null,
            null,
            "Response body: {\"status\":500}");

    public static final BuildAnalysisContext CONTEXT = new BuildAnalysisContext(
            "FAILURE",
            LOGIN_1.scenario(), LOGIN_1.step(), LOGIN_1.exceptionType(), ErrorCategory.SELENIUM, LOGIN_1.stackTrace(),
            LOGIN_1.failureLocation(), LOGIN_1.failedInteraction(),
            "RELEVANT_SNIPPET",
            "Finished: FAILURE",
            new TestSummary(30, 27, 3, 0),
            List.of(
                    new FailureGroup("G1", "org.openqa.selenium.NoSuchElementException: no such element: Unable to locate element: "
                            + "#login-btn @ stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)", List.of(LOGIN_1, LOGIN_2)),
                    new FailureGroup("G2", "java.lang.AssertionError: expected [201] but found [500]", List.of(ORDER_API))));

    public static final String SKIPPED_NOTE = "AI çağrı sınırına (1 çağrı) ulaşıldığı için ayrıntılı analiz edilmedi.";

    public static final BuildAnalysisResult RESULT = new BuildAnalysisResult(
            CONTEXT,
            "THE GROUP PROMPT",
            "🚨 BUILD ÖZETİ\n3 testin 2'si #login-btn yüzünden düştü.",
            null,
            new LlmMetrics("OPENAI", "gpt-5-mini", 4100, 700, 4800, 7000L, new BigDecimal("0.00242")),
            "3 testin 2'si #login-btn yüzünden düştü; G2 için ortak neden kanıtlanamadı.",
            List.of(
                    FailureGroupAnalysis.analyzed("G1", "#login-btn locator'ı bulunamadı.",
                            List.of("LoginSteps.java:31'deki locator'ı düzelt.")),
                    FailureGroupAnalysis.notAnalyzed("G2", FailureGroupAnalysis.Status.SKIPPED, SKIPPED_NOTE)));

    private MultiFailureResult() {
    }
}
