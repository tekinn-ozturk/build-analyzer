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
import com.company.buildanalyzer.domain.model.JenkinsBuildUrl;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import com.company.buildanalyzer.infrastructure.persistence.Analysis;
import com.company.buildanalyzer.infrastructure.persistence.AnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.projection.SpelAwareProxyProjectionFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BuildAnalysisController.class)
@Import(BuildAnalysisResponseMapper.class)
class BuildAnalysisControllerTest {

    private static final String REQUEST_BODY = """
            {"buildUrl":"http://localhost:8080/job/Team/job/Mini-UI-Automation/5/console"}
            """;

    private static final BuildAnalysisResult RESULT = new BuildAnalysisResult(
            new BuildAnalysisContext(
                    "FAILURE",
                    new FailedScenario("Login with invalid credentials", "src/test/resources/features/login.feature", 12),
                    new FailedStep("the user submits the form", "login.feature", 15),
                    "org.openqa.selenium.SessionNotCreatedException",
                    ErrorCategory.SELENIUM,
                    "org.openqa.selenium.SessionNotCreatedException: session not created",
                    new FailureLocation("stepdefinitions.LoginSteps.submit", "LoginSteps.java", 42),
                    new FailedInteraction("Selenium", "newSession", null, null),
                    "[ERROR] Tests run: 1, Errors: 1",
                    "line 1\nline 2"),
            "... rootCause ...", "🚨 KÖK NEDEN\nChromeDriver uyumsuz.",
            new RootCauseAnalysis("ChromeDriver uyumsuz.", "LoginSteps.java", 42, "submit",
                    List.of("ChromeDriver sürümünü kontrol et.")),
            new LlmMetrics("OPENAI", "gpt-5-mini", 3000, 400, 3400, 2150L, new BigDecimal("0.00155")));

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BuildAnalysisResponseMapper mapper;

    @MockBean
    private AnalyzeBuildUseCase analyzeBuildUseCase;

    @MockBean
    private AnalysisRepository analysisRepository;

    /** Saving assigns id 7, as the database would. */
    @BeforeEach
    void saveAssignsAnId() {
        when(analysisRepository.save(any())).thenAnswer(invocation -> {
            Analysis analysis = invocation.getArgument(0);
            analysis.setId(7L);
            return analysis;
        });
    }

    @Test
    void analyzesTheBuildBehindTheUrlStoresItAndReturnsEverythingIncludingThePrompt() throws Exception {
        when(analyzeBuildUseCase.analyze("Team/Mini-UI-Automation", 5)).thenReturn(RESULT);

        mockMvc.perform(post("/api/v1/analysis/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.jobName").value("Team/Mini-UI-Automation"))
                .andExpect(jsonPath("$.buildNumber").value(5))
                .andExpect(jsonPath("$.buildUrl").value("http://localhost:8080/job/Team/job/Mini-UI-Automation/5/"))
                .andExpect(jsonPath("$.analyzedAt").exists())
                .andExpect(jsonPath("$.testContext.scenario").value("Login with invalid credentials"))
                .andExpect(jsonPath("$.testContext.step").value("the user submits the form"))
                .andExpect(jsonPath("$.testContext.featureLine").value(15))
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

        verify(analysisRepository).save(any());
    }

    @Test
    void returnsAStoredAnalysisWithoutThePrompt() throws Exception {
        Analysis stored = mapper.toEntity(
                JenkinsBuildUrl.parse("http://localhost:8080/job/Team/job/Mini-UI-Automation/5/"), RESULT);
        stored.setId(7L);
        when(analysisRepository.findById(7L)).thenReturn(Optional.of(stored));

        mockMvc.perform(get("/api/v1/analyses/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.jobName").value("Team/Mini-UI-Automation"))
                .andExpect(jsonPath("$.rootCauseAnalysis.rootCause").value("ChromeDriver uyumsuz."))
                .andExpect(jsonPath("$.testContext.scenario").value("Login with invalid credentials"))
                .andExpect(jsonPath("$.relevantLogSnippet").value("[ERROR] Tests run: 1, Errors: 1"))
                .andExpect(jsonPath("$.last200Lines").value("line 1\nline 2"))
                .andExpect(jsonPath("$", not(hasKey("generatedPrompt"))));
    }

    @Test
    void listsTheHistoryNewestFirst() throws Exception {
        AnalysisRepository.Summary row = new SpelAwareProxyProjectionFactory().createProjection(
                AnalysisRepository.Summary.class,
                Map.of("id", 7L, "jobName", "Mini-UI-Automation", "jobPath", "Team/Mini-UI-Automation",
                        "buildNumber", 5, "buildStatus", "FAILURE", "analyzedAt", Instant.parse("2026-10-04T09:42:00Z")));
        when(analysisRepository.findAllByOrderByAnalyzedAtDesc()).thenReturn(List.of(row));

        mockMvc.perform(get("/api/v1/analyses"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(7))
                .andExpect(jsonPath("$[0].jobPath").value("Team/Mini-UI-Automation"))
                .andExpect(jsonPath("$[0].buildStatus").value("FAILURE"))
                .andExpect(jsonPath("$[0].analyzedAt").value("2026-10-04T09:42:00Z"))
                .andExpect(jsonPath("$[0].generatedPrompt").doesNotExist());
    }

    @Test
    void answers404ForAnUnknownAnalysis() throws Exception {
        when(analysisRepository.findById(99L)).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/analyses/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("Analysis 99 not found"));
    }

    @Test
    void rejectsAUrlThatIsNotAJenkinsBuildUrlWithoutAnalyzing() throws Exception {
        mockMvc.perform(post("/api/v1/analysis/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"buildUrl":"https://jenkins.company.com/job/UI-Test/"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Not a Jenkins build URL")));
        verify(analyzeBuildUseCase, never()).analyze(anyString(), anyInt());
    }

    @Test
    void rejectsAMissingBuildUrlWith400() throws Exception {
        mockMvc.perform(post("/api/v1/analysis/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void mapsLlmFailureTo502AndStoresNothing() throws Exception {
        when(analyzeBuildUseCase.analyze(eq("Team/Mini-UI-Automation"), anyInt()))
                .thenThrow(new LlmAnalysisException("OpenAI returned HTTP 401"));

        mockMvc.perform(post("/api/v1/analysis/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("AI analysis failed: OpenAI returned HTTP 401"));
        verify(analysisRepository, never()).save(any());
    }
}
