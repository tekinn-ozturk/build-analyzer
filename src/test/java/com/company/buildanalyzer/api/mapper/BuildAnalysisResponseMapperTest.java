package com.company.buildanalyzer.api.mapper;

import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BuildAnalysisResponseMapperTest {

    private final BuildAnalysisResponseMapper mapper = new BuildAnalysisResponseMapper();

    @Test
    void mapsEveryFieldFromContextToResponse() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE",
                "Login with invalid credentials",
                "org.openqa.selenium.SessionNotCreatedException",
                ErrorCategory.SELENIUM,
                "org.openqa.selenium.SessionNotCreatedException: session not created",
                "line 1\nline 2"
        );

        AnalyzeBuildResponse response = mapper.toResponse(context, "GENERATED_PROMPT");

        assertThat(response.buildStatus()).isEqualTo("FAILURE");
        assertThat(response.failedScenario()).isEqualTo("Login with invalid credentials");
        assertThat(response.exceptionType()).isEqualTo("org.openqa.selenium.SessionNotCreatedException");
        assertThat(response.errorCategory()).isEqualTo("SELENIUM");
        assertThat(response.stackTrace()).isEqualTo("org.openqa.selenium.SessionNotCreatedException: session not created");
        assertThat(response.last500Lines()).isEqualTo("line 1\nline 2");
        assertThat(response.generatedPrompt()).isEqualTo("GENERATED_PROMPT");
    }
}
