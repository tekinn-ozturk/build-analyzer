package com.company.buildanalyzer.application.usecase;

import com.company.buildanalyzer.application.analysis.RootCauseFormatter;
import com.company.buildanalyzer.application.analysis.RootCauseParser;
import com.company.buildanalyzer.application.analysis.RootCauseSchema;
import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.application.context.BuildContextBuilder;
import com.company.buildanalyzer.application.context.RelevantLogExtractor;
import com.company.buildanalyzer.application.context.SeleniumInteractionExtractor;
import com.company.buildanalyzer.application.port.out.BuildSourcePort;
import com.company.buildanalyzer.application.port.out.LlmAnalysisException;
import com.company.buildanalyzer.application.port.out.LlmCompletion;
import com.company.buildanalyzer.application.port.out.LlmProvider;
import com.company.buildanalyzer.application.port.out.LlmRequest;
import com.company.buildanalyzer.application.prompt.AnalysisLimits;
import com.company.buildanalyzer.application.prompt.PromptBuilder;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AnalyzeBuildServiceTest {

    private static final String FAILED_LOG = """
            org.openqa.selenium.SessionNotCreatedException: session not created
            \tat org.openqa.selenium.remote.ProtocolHandshake.createSession(ProtocolHandshake.java:130)
            \tat com.acme.steps.BrowserSteps.openBrowser(BrowserSteps.java:17)
            Finished: FAILURE
            """;

    /** Real-world case: a green build whose log carries a harmless Selenium warning. */
    private static final String SUCCESS_LOG = """
            Eyl 20, 2026 11:49:46 ÖÖ org.openqa.selenium.devtools.CdpVersionFinder findNearestMatch
            WARNING: Unable to find CDP implementation matching 153
            1 Scenarios (1 passed)
            [INFO] BUILD SUCCESS
            Finished: SUCCESS
            """;

    private static final String JSON_ANSWER = """
            {"rootCause":"SessionNotCreatedException: tarayıcı oturumu açılamadı.",
             "file":null,"line":null,"method":null,
             "actions":["BrowserSteps.java:17'deki driver oluşturma adımını kontrol et."]}""";

    private final BuildSourcePort buildSourcePort = mock(BuildSourcePort.class);
    private final LlmProvider llmProvider = mock(LlmProvider.class);
    // Real builders (spied) so the test also proves fetch -> extraction -> prompt -> parse wiring.
    private final PromptBuilder promptBuilder = spy(new PromptBuilder(new AnalysisLimits()));
    private final AnalyzeBuildService service = new AnalyzeBuildService(
            buildSourcePort,
            new BuildContextBuilder(new ErrorClassifier(), new RelevantLogExtractor(),
                    List.of(new SeleniumInteractionExtractor())),
            promptBuilder, llmProvider, new RootCauseParser(), new RootCauseFormatter());

    private static LlmCompletion completion(String text) {
        return new LlmCompletion(text, "OPENAI", "gpt-5-mini-2025-08-07", 3000, 400, 3400, new BigDecimal("0.00155"));
    }

    @Test
    void asksForTheRootCauseJsonAndReturnsItParsedAndRendered() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn(FAILED_LOG);
        when(llmProvider.complete(any(LlmRequest.class))).thenReturn(completion(JSON_ANSWER));

        BuildAnalysisResult result = service.analyze("Mini-UI-Automation", 5);

        BuildAnalysisContext context = result.context();
        assertThat(context.buildStatus()).isEqualTo("FAILURE");
        assertThat(context.errorCategory()).isEqualTo(ErrorCategory.SELENIUM);

        // exactly the generated prompt reaches the LLM port, together with the answer schema
        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmProvider).complete(sent.capture());
        assertThat(sent.getValue().prompt())
                .isEqualTo(result.generatedPrompt())
                .contains("- Hata Kategorisi: SELENIUM");
        assertThat(sent.getValue().responseSchema()).isEqualTo(RootCauseSchema.ROOT_CAUSE);

        // the location the model left null is filled from the parser's evidence
        assertThat(result.rootCauseAnalysis()).isEqualTo(new RootCauseAnalysis(
                "SessionNotCreatedException: tarayıcı oturumu açılamadı.",
                "BrowserSteps.java", 17, "openBrowser",
                List.of("BrowserSteps.java:17'deki driver oluşturma adımını kontrol et.")));
        assertThat(result.aiAnalysis()).isEqualTo("""
                🚨 KÖK NEDEN
                SessionNotCreatedException: tarayıcı oturumu açılamadı.
                📍 KONUM
                BrowserSteps.java:17
                openBrowser()
                ✅ AKSİYON
                - BrowserSteps.java:17'deki driver oluşturma adımını kontrol et.""");
    }

    @Test
    void anUnparseableAnswerIsShownAsItIs() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn(FAILED_LOG);
        when(llmProvider.complete(any(LlmRequest.class))).thenReturn(completion("Oturum açılamadı."));

        BuildAnalysisResult result = service.analyze("Mini-UI-Automation", 5);

        assertThat(result.aiAnalysis()).isEqualTo("Oturum açılamadı.");
        assertThat(result.rootCauseAnalysis()).isNull();
        assertThat(result.llmMetrics()).isNotNull();
    }

    @Test
    void returnsTheProvidersUsageStatisticsAndMeasuresTheCallDuration() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn(FAILED_LOG);
        when(llmProvider.complete(any(LlmRequest.class))).thenAnswer(invocation -> {
            Thread.sleep(50);
            return completion(JSON_ANSWER);
        });

        LlmMetrics metrics = service.analyze("Mini-UI-Automation", 5).llmMetrics();

        assertThat(metrics.provider()).isEqualTo("OPENAI");
        assertThat(metrics.model()).isEqualTo("gpt-5-mini-2025-08-07");
        assertThat(metrics.promptTokens()).isEqualTo(3000);
        assertThat(metrics.completionTokens()).isEqualTo(400);
        assertThat(metrics.totalTokens()).isEqualTo(3400);
        assertThat(metrics.estimatedCostUsd()).isEqualByComparingTo("0.00155");
        assertThat(metrics.responseTimeMs()).isBetween(50L, 5_000L);
    }

    @Test
    void successfulBuildSkipsPromptAndLlmAndReturnsFixedMessage() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn(SUCCESS_LOG);

        BuildAnalysisResult result = service.analyze("Mini-UI-Automation", 5);

        verifyNoInteractions(llmProvider);
        verifyNoInteractions(promptBuilder);
        assertThat(result.generatedPrompt()).isNull();
        assertThat(result.llmMetrics()).as("no LLM call, no metrics").isNull();
        assertThat(result.rootCauseAnalysis()).isNull();
        assertThat(result.aiAnalysis())
                .isEqualTo("Build başarıyla tamamlandı. Analiz gerektiren bir hata tespit edilmedi.");
        assertThat(result.context().buildStatus()).isEqualTo("SUCCESS");
        assertThat(result.context().errorCategory()).isEqualTo(ErrorCategory.NONE);
    }

    /** Test 1: one failed scenario keeps the single-failure flow; its only group carries the same answer. */
    @Test
    void aSingleFailedTestKeepsTheSingleFailureFlow() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn("""
                Scenario: Pay with saved card     # src/test/resources/features/Checkout.feature:9
                  * Ödeme butonuna tıkla.         # stepdefinitions.CheckoutSteps.clickPay()
                      org.openqa.selenium.TimeoutException: Expected condition failed: waiting for element to be clickable: By.id: pay-now-btn
                      \tat stepdefinitions.CheckoutSteps.clickPay(CheckoutSteps.java:33)

                1 Scenarios (1 failed)
                Finished: FAILURE
                """);
        when(llmProvider.complete(any(LlmRequest.class))).thenReturn(completion("""
                {"rootCause":"pay-now-btn tıklanabilir olmadı.","file":null,"line":null,"method":null,
                 "actions":["CheckoutSteps.java:33'teki bekleme koşulunu düzelt."]}"""));

        BuildAnalysisResult result = service.analyze("Mini-UI-Automation", 5);

        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmProvider).complete(sent.capture());
        assertThat(sent.getValue().responseSchema()).isEqualTo(RootCauseSchema.ROOT_CAUSE);
        assertThat(sent.getValue().prompt()).contains("=== 1. YAPISAL KANIT").contains("- Test Özeti: toplam 1, başarılı 0, başarısız 1");
        assertThat(result.rootCauseAnalysis().rootCause()).isEqualTo("pay-now-btn tıklanabilir olmadı.");
        assertThat(result.aiAnalysis()).startsWith("🚨 KÖK NEDEN");
        assertThat(result.aiSummary()).isNull();
        assertThat(result.context().failureCount()).isEqualTo(1);
        assertThat(result.groupAnalyses()).containsExactly(FailureGroupAnalysis.analyzed("G1",
                "pay-now-btn tıklanabilir olmadı.", List.of("CheckoutSteps.java:33'teki bekleme koşulunu düzelt.")));
    }

    /**
     * A passing scenario logged a retried stale element before the only failed test: the prompt's evidence is the
     * failed test's error, not the log's first exception, and the test's group carries the answer.
     */
    @Test
    void theEvidenceIsTheFailedTestsErrorNotAnExceptionAPassingScenarioLogged() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn("""
                Scenario: Sepet açılır     # src/test/resources/features/Shop.feature:3
                  * Sepete git.                 # stepdefinitions.CartSteps.openCart()
                Retrying click after stale element:
                org.openqa.selenium.StaleElementReferenceException: stale element reference: stale element not found
                \tat pages.CartPage.open(CartPage.java:20)

                Scenario: Giriş yapılır     # src/test/resources/features/Shop.feature:8
                  * Giriş butonuna tıkla.     # stepdefinitions.LoginSteps.clickLogin()
                      org.openqa.selenium.NoSuchElementException: no such element: #login-btn
                      \tat stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)

                Failed scenarios:
                src/test/resources/features/Shop.feature:8 # Giriş yapılır

                2 Scenarios (1 failed, 1 passed)
                Finished: FAILURE
                """);
        when(llmProvider.complete(any(LlmRequest.class))).thenReturn(completion("""
                {"rootCause":"#login-btn bulunamadı.","file":null,"line":null,"method":null,"actions":[]}"""));

        BuildAnalysisResult result = service.analyze("Mini-UI-Automation", 5);

        String prompt = result.generatedPrompt();
        String evidence = prompt.substring(prompt.indexOf("=== 1."), prompt.indexOf("=== 2."));
        assertThat(evidence)
                .contains("- Senaryo: Giriş yapılır (src/test/resources/features/Shop.feature:8)")
                .contains("- Başarısız Adım: Giriş butonuna tıkla.")
                .contains("- Exception: org.openqa.selenium.NoSuchElementException")
                .doesNotContain("StaleElementReferenceException");
        assertThat(result.groupAnalyses()).singleElement()
                .isEqualTo(FailureGroupAnalysis.analyzed("G1", "#login-btn bulunamadı.", List.of()));
    }

    /** The failed test printed no exception: the answer is about the log's first exception, so its group says so. */
    @Test
    void aFailedTestWithoutItsOwnErrorIsNotShownAsAnalysed() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn("""
                Scenario: Invalid login   # login.feature:12
                [ERROR] Tests run: 1, Failures: 0, Errors: 1
                org.openqa.selenium.SessionNotCreatedException: session not created
                \tat org.openqa.selenium.remote.ProtocolHandshake.createSession(ProtocolHandshake.java:130)

                Failed scenarios:
                login.feature:12 # Invalid login

                1 Scenarios (1 failed)
                Finished: FAILURE
                """);
        when(llmProvider.complete(any(LlmRequest.class))).thenReturn(completion("""
                {"rootCause":"Tarayıcı oturumu açılamadı.","file":null,"line":null,"method":null,"actions":[]}"""));

        BuildAnalysisResult result = service.analyze("Mini-UI-Automation", 5);

        assertThat(result.rootCauseAnalysis().rootCause()).isEqualTo("Tarayıcı oturumu açılamadı.");
        assertThat(result.groupAnalyses()).singleElement().satisfies(group -> {
            assertThat(group.status()).isEqualTo(FailureGroupAnalysis.Status.FAILED);
            assertThat(group.rootCause()).isNull();
            assertThat(group.note()).isEqualTo(AnalyzeBuildService.OTHER_EXCEPTION_NOTE);
        });
    }

    @Test
    void propagatesLlmFailure() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn(FAILED_LOG);
        when(llmProvider.complete(any(LlmRequest.class))).thenThrow(new LlmAnalysisException("OpenAI down"));

        assertThatThrownBy(() -> service.analyze("Mini-UI-Automation", 5))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessage("OpenAI down");
    }
}
