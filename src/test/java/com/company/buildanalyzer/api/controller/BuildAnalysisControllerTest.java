package com.company.buildanalyzer.api.controller;

import com.company.buildanalyzer.api.mapper.BuildAnalysisResponseMapper;
import com.company.buildanalyzer.application.port.out.LlmAnalysisException;
import com.company.buildanalyzer.application.usecase.AnalyzeBuildUseCase;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BuildAnalysisController.class)
@Import(BuildAnalysisResponseMapper.class)
class BuildAnalysisControllerTest {

    private static final String REQUEST_BODY = """
            {"jobName":"Mini-UI-Automation","buildNumber":5}
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyzeBuildUseCase analyzeBuildUseCase;

    @Test
    void returnsStructuredContextPromptAiAnalysisAndUsageMetrics() throws Exception {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE",
                new FailedScenario("Login with invalid credentials", "src/test/resources/features/login.feature", 12),
                new FailedStep("the user submits the form", "login.feature", 15),
                "org.openqa.selenium.SessionNotCreatedException",
                ErrorCategory.SELENIUM,
                "org.openqa.selenium.SessionNotCreatedException: session not created",
                new FailureLocation("stepdefinitions.LoginSteps.submit", "LoginSteps.java", 42),
                new FailedInteraction("Selenium", "newSession", null, null),
                "[ERROR] Tests run: 1, Errors: 1",
                "line 1\nline 2"
        );
        when(analyzeBuildUseCase.analyze(eq("Mini-UI-Automation"), anyInt())).thenReturn(
                new BuildAnalysisResult(context, "... rootCause ...", "🚨 KÖK NEDEN\nChromeDriver uyumsuz.",
                        new RootCauseAnalysis("ChromeDriver uyumsuz.", "LoginSteps.java", 42, "submit",
                                List.of("ChromeDriver sürümünü kontrol et.")),
                        new LlmMetrics("OPENAI", "gpt-5-mini", 3000, 400, 3400, 2150L, new BigDecimal("0.00155"))));

        mockMvc.perform(post("/api/v1/analysis/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildStatus").value("FAILURE"))
                .andExpect(jsonPath("$.failedScenario").value("Login with invalid credentials"))
                .andExpect(jsonPath("$.featureFile").value("src/test/resources/features/login.feature"))
                .andExpect(jsonPath("$.featureLine").value(12))
                .andExpect(jsonPath("$.exceptionFile").value("LoginSteps.java"))
                .andExpect(jsonPath("$.exceptionLine").value(42))
                .andExpect(jsonPath("$.failedStepDefinition").value("stepdefinitions.LoginSteps.submit"))
                .andExpect(jsonPath("$.failedStepText").value("the user submits the form"))
                .andExpect(jsonPath("$.failedStepLine").value(15))
                .andExpect(jsonPath("$.automationTool").value("Selenium"))
                .andExpect(jsonPath("$.failedCommand").value("newSession"))
                .andExpect(jsonPath("$.locatorType").doesNotExist())
                .andExpect(jsonPath("$.exceptionType").value("org.openqa.selenium.SessionNotCreatedException"))
                .andExpect(jsonPath("$.errorCategory").value("SELENIUM"))
                .andExpect(jsonPath("$.stackTrace").exists())
                .andExpect(jsonPath("$.relevantLogSnippet").value("[ERROR] Tests run: 1, Errors: 1"))
                .andExpect(jsonPath("$.last200Lines").exists())
                .andExpect(jsonPath("$.generatedPrompt").value(containsString("rootCause")))
                .andExpect(jsonPath("$.aiAnalysis").value("🚨 KÖK NEDEN\nChromeDriver uyumsuz."))
                .andExpect(jsonPath("$.rootCauseAnalysis.rootCause").value("ChromeDriver uyumsuz."))
                .andExpect(jsonPath("$.rootCauseAnalysis.file").value("LoginSteps.java"))
                .andExpect(jsonPath("$.rootCauseAnalysis.line").value(42))
                .andExpect(jsonPath("$.rootCauseAnalysis.method").value("submit"))
                .andExpect(jsonPath("$.rootCauseAnalysis.actions[0]").value("ChromeDriver sürümünü kontrol et."))
                .andExpect(jsonPath("$.rootCauseAnalysis.confidence").doesNotExist())
                .andExpect(jsonPath("$.provider").value("OPENAI"))
                .andExpect(jsonPath("$.model").value("gpt-5-mini"))
                .andExpect(jsonPath("$.promptTokens").value(3000))
                .andExpect(jsonPath("$.completionTokens").value(400))
                .andExpect(jsonPath("$.totalTokens").value(3400))
                .andExpect(jsonPath("$.responseTimeMs").value(2150))
                .andExpect(jsonPath("$.estimatedCostUsd").value(0.00155))
                .andExpect(jsonPath("$.consoleLog").doesNotExist());
    }

    @Test
    void mapsLlmFailureTo502() throws Exception {
        when(analyzeBuildUseCase.analyze(eq("Mini-UI-Automation"), anyInt()))
                .thenThrow(new LlmAnalysisException("OpenAI returned HTTP 401"));

        mockMvc.perform(post("/api/v1/analysis/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("AI analysis failed: OpenAI returned HTTP 401"));
    }

    @Test
    void rejectsMissingBuildNumberWith400() throws Exception {
        mockMvc.perform(post("/api/v1/analysis/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobName":"Mini-UI-Automation"}
                                """))
                .andExpect(status().isBadRequest());
    }
}
