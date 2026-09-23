package com.company.buildanalyzer.api.controller;

import com.company.buildanalyzer.api.mapper.BuildAnalysisResponseMapper;
import com.company.buildanalyzer.application.prompt.PromptBuilder;
import com.company.buildanalyzer.application.usecase.AnalyzeBuildUseCase;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(BuildAnalysisController.class)
@Import({BuildAnalysisResponseMapper.class, PromptBuilder.class})
class BuildAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AnalyzeBuildUseCase analyzeBuildUseCase;

    @Test
    void returnsStructuredContextAndNotRawLog() throws Exception {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE",
                "Login with invalid credentials",
                "org.openqa.selenium.SessionNotCreatedException",
                ErrorCategory.SELENIUM,
                "org.openqa.selenium.SessionNotCreatedException: session not created",
                "line 1\nline 2"
        );
        when(analyzeBuildUseCase.analyze(eq("Mini-UI-Automation"), anyInt())).thenReturn(context);

        mockMvc.perform(post("/api/v1/analysis/build")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"jobName":"Mini-UI-Automation","buildNumber":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.buildStatus").value("FAILURE"))
                .andExpect(jsonPath("$.failedScenario").value("Login with invalid credentials"))
                .andExpect(jsonPath("$.exceptionType").value("org.openqa.selenium.SessionNotCreatedException"))
                .andExpect(jsonPath("$.errorCategory").value("SELENIUM"))
                .andExpect(jsonPath("$.stackTrace").exists())
                .andExpect(jsonPath("$.last500Lines").exists())
                .andExpect(jsonPath("$.generatedPrompt").exists())
                .andExpect(jsonPath("$.generatedPrompt").value(org.hamcrest.Matchers.containsString("Root Cause Analysis")))
                .andExpect(jsonPath("$.consoleLog").doesNotExist());
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
