package com.company.buildanalyzer.api.mapper;

import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.api.dto.response.RootCauseAnalysisResponse;
import com.company.buildanalyzer.api.dto.response.TestContextResponse;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuildAnalysisResponseMapperTest {

    private static final BuildAnalysisContext SELENIUM_FAILURE = new BuildAnalysisContext(
            "FAILURE",
            new FailedScenario("Open Google", "src/test/resources/features/Example.feature", 4),
            new FailedStep("Arama kutusuna tıkla.", "Example.feature", 6),
            "org.openqa.selenium.NoSuchElementException",
            ErrorCategory.SELENIUM,
            "org.openqa.selenium.NoSuchElementException: no such element",
            new FailureLocation("stepdefinitions.ExampleSteps.clickSearchBox", "ExampleSteps.java", 28),
            new FailedInteraction("Selenium", "findElement", "name", "qqqqqqqq"),
            "RELEVANT_SNIPPET",
            "line 1\nline 2"
    );

    private static final BuildAnalysisResult SELENIUM_RESULT = new BuildAnalysisResult(
            SELENIUM_FAILURE, "GENERATED_PROMPT", "AI_ANALYSIS",
            new RootCauseAnalysis("name=qqqqqqqq locator'ı bulunamadı.", "ExampleSteps.java", 28,
                    "clickSearchBox", List.of("Locator'ı doğrula.")),
            new LlmMetrics("OPENAI", "gpt-5-mini", 3000, 400, 3400, 1234L, new BigDecimal("0.00155")));

    private final BuildAnalysisResponseMapper mapper = new BuildAnalysisResponseMapper(new ObjectMapper().findAndRegisterModules());

    @Test
    void mapsAndFlattensEveryFieldFromResultToResponse() {
        AnalyzeBuildResponse response = mapper.toResponse(SELENIUM_RESULT, null);

        assertThat(response.id()).isNull();
        assertThat(response.buildStatus()).isEqualTo("FAILURE");
        assertThat(response.failedScenario()).isEqualTo("Open Google");
        assertThat(response.featureFile()).isEqualTo("src/test/resources/features/Example.feature");
        assertThat(response.featureLine()).isEqualTo(4);
        assertThat(response.failedStepText()).isEqualTo("Arama kutusuna tıkla.");
        assertThat(response.failedStepLine()).isEqualTo(6);
        assertThat(response.exceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");
        assertThat(response.errorCategory()).isEqualTo("SELENIUM");
        assertThat(response.exceptionFile()).isEqualTo("ExampleSteps.java");
        assertThat(response.exceptionLine()).isEqualTo(28);
        assertThat(response.failedStepDefinition()).isEqualTo("stepdefinitions.ExampleSteps.clickSearchBox");
        assertThat(response.automationTool()).isEqualTo("Selenium");
        assertThat(response.failedCommand()).isEqualTo("findElement");
        assertThat(response.locatorType()).isEqualTo("name");
        assertThat(response.locatorValue()).isEqualTo("qqqqqqqq");
        assertThat(response.stackTrace()).isEqualTo("org.openqa.selenium.NoSuchElementException: no such element");
        assertThat(response.relevantLogSnippet()).isEqualTo("RELEVANT_SNIPPET");
        assertThat(response.last200Lines()).isEqualTo("line 1\nline 2");
        assertThat(response.generatedPrompt()).isEqualTo("GENERATED_PROMPT");
        assertThat(response.aiAnalysis()).isEqualTo("AI_ANALYSIS");
        assertThat(response.rootCauseAnalysis()).isEqualTo(new RootCauseAnalysisResponse(
                "name=qqqqqqqq locator'ı bulunamadı.", "ExampleSteps.java", 28, "clickSearchBox", List.of("Locator'ı doğrula.")));
        assertThat(response.testContext()).isEqualTo(new TestContextResponse(
                "Open Google", "Arama kutusuna tıkla.", "src/test/resources/features/Example.feature", 6));
        assertThat(response.provider()).isEqualTo("OPENAI");
        assertThat(response.model()).isEqualTo("gpt-5-mini");
        assertThat(response.promptTokens()).isEqualTo(3000);
        assertThat(response.completionTokens()).isEqualTo(400);
        assertThat(response.totalTokens()).isEqualTo(3400);
        assertThat(response.responseTimeMs()).isEqualTo(1234L);
        assertThat(response.estimatedCostUsd()).isEqualByComparingTo("0.00155");
    }

    @Test
    void leavesLocationFieldsAndTestContextNullWhenTheyWereNotFound() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "SUCCESS", null, null, null, ErrorCategory.NONE, null, null, null, null, "tail");

        AnalyzeBuildResponse response = mapper.toResponse(new BuildAnalysisResult(context, null, "OK", null, null), null);

        assertThat(response.errorCategory()).isEqualTo("NONE");
        assertThat(response.failedScenario()).isNull();
        assertThat(response.featureFile()).isNull();
        assertThat(response.featureLine()).isNull();
        assertThat(response.failedStepText()).isNull();
        assertThat(response.failedStepLine()).isNull();
        assertThat(response.exceptionFile()).isNull();
        assertThat(response.exceptionLine()).isNull();
        assertThat(response.failedStepDefinition()).isNull();
        assertThat(response.automationTool()).isNull();
        assertThat(response.failedCommand()).isNull();
        assertThat(response.locatorType()).isNull();
        assertThat(response.locatorValue()).isNull();
        assertThat(response.generatedPrompt()).isNull();
        assertThat(response.rootCauseAnalysis()).isNull();
        assertThat(response.testContext()).isNull();
        assertThat(response.provider()).isNull();
        assertThat(response.model()).isNull();
        assertThat(response.promptTokens()).isNull();
        assertThat(response.responseTimeMs()).isNull();
        assertThat(response.estimatedCostUsd()).isNull();
    }

    @Test
    void storesAnAnalysisAndReadsItBackInTheSameShapeWithoutThePrompt() {
        Analysis entity = mapper.toEntity(
                JenkinsBuildUrl.parse("https://jenkins.company.com/job/Team/job/UI%20Tests/125/console"), SELENIUM_RESULT);
        entity.setId(42L);

        assertThat(entity.getBuildUrl()).isEqualTo("https://jenkins.company.com/job/Team/job/UI%20Tests/125/");
        assertThat(entity.getJobName()).isEqualTo("UI Tests");
        assertThat(entity.getJobPath()).isEqualTo("Team/UI Tests");
        assertThat(entity.getAnalysisStatus()).isEqualTo("COMPLETED");
        assertThat(entity.getHeadline()).isEqualTo("NoSuchElementException");
        assertThat(entity.getErrorReason()).isEqualTo("name=qqqqqqqq locator'ı bulunamadı.");
        assertThat(entity.getResultJson()).doesNotContain("GENERATED_PROMPT", "RELEVANT_SNIPPET");
        assertThat(entity.getTotalTokens()).isEqualTo(3400);

        AnalyzeBuildResponse fresh = mapper.toResponse(SELENIUM_RESULT, entity);
        AnalyzeBuildResponse stored = mapper.toResponse(entity);

        assertThat(stored.id()).isEqualTo(42L);
        assertThat(stored.jobName()).isEqualTo("Team/UI Tests");
        assertThat(stored.analyzedAt()).isEqualTo(entity.getAnalyzedAt());
        assertThat(stored.relevantLogSnippet()).isEqualTo("RELEVANT_SNIPPET");
        assertThat(stored.generatedPrompt()).isNull();
        assertThat(stored).usingRecursiveComparison().ignoringFields("generatedPrompt").isEqualTo(fresh);
    }

    @Test
    void marksASuccessfulBuildAsSkippedWithoutTestContext() {
        BuildAnalysisContext success = new BuildAnalysisContext(
                "SUCCESS", null, null, null, ErrorCategory.NONE, null, null, null, null, "Finished: SUCCESS");

        Analysis entity = mapper.toEntity(JenkinsBuildUrl.parse("http://localhost:8080/job/Mini-UI-Automation/8/"),
                new BuildAnalysisResult(success, null, "Build başarıyla tamamlandı.", null, null));

        assertThat(entity.getAnalysisStatus()).isEqualTo("SKIPPED");
        assertThat(entity.getHeadline()).isEqualTo("Build başarılı");
        assertThat(entity.getBuildUrl()).isEqualTo("http://localhost:8080/job/Mini-UI-Automation/8/");
        assertThat(entity.getModel()).isNull();
    }
}
