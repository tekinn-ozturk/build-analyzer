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
import com.company.buildanalyzer.application.prompt.PromptBuilder;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.ErrorCategory;
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
    private final PromptBuilder promptBuilder = spy(new PromptBuilder());
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

    @Test
    void propagatesLlmFailure() {
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn(FAILED_LOG);
        when(llmProvider.complete(any(LlmRequest.class))).thenThrow(new LlmAnalysisException("OpenAI down"));

        assertThatThrownBy(() -> service.analyze("Mini-UI-Automation", 5))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessage("OpenAI down");
    }
}
