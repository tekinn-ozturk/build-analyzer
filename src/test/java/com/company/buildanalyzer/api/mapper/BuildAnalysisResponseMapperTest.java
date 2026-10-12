package com.company.buildanalyzer.api.mapper;

import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.api.dto.response.FailureGroupResponse;
import com.company.buildanalyzer.api.dto.response.RootCauseAnalysisResponse;
import com.company.buildanalyzer.api.dto.response.TestContextResponse;
import com.company.buildanalyzer.api.dto.response.TestFailureResponse;
import com.company.buildanalyzer.api.dto.response.TestSummaryResponse;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
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
            "line 1\nline 2",
            null,
            List.of()
    );

    private static final BuildAnalysisResult SELENIUM_RESULT = new BuildAnalysisResult(
            SELENIUM_FAILURE, "GENERATED_PROMPT", "AI_ANALYSIS",
            new RootCauseAnalysis("name=qqqqqqqq locator'ı bulunamadı.", "ExampleSteps.java", 28,
                    "clickSearchBox", List.of("Locator'ı doğrula.")),
            new LlmMetrics("OPENAI", "gpt-5-mini", 3000, 400, 3400, 1234L, new BigDecimal("0.00155")), null, List.of());

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
                "SUCCESS", null, null, null, ErrorCategory.NONE, null, null, null, null, "tail", null, List.of());

        AnalyzeBuildResponse response = mapper.toResponse(new BuildAnalysisResult(context, null, "OK", null, null, null, List.of()), null);

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
                "SUCCESS", null, null, null, ErrorCategory.NONE, null, null, null, null, "Finished: SUCCESS", null, List.of());

        Analysis entity = mapper.toEntity(JenkinsBuildUrl.parse("http://localhost:8080/job/Mini-UI-Automation/8/"),
                new BuildAnalysisResult(success, null, "Build başarıyla tamamlandı.", null, null, null, List.of()));

        assertThat(entity.getAnalysisStatus()).isEqualTo("SKIPPED");
        assertThat(entity.getHeadline()).isEqualTo("Build başarılı");
        assertThat(entity.getBuildUrl()).isEqualTo("http://localhost:8080/job/Mini-UI-Automation/8/");
        assertThat(entity.getModel()).isNull();
    }

    @Test
    void mapsEveryFailureGroupWithItsTestsAndTheAiAnswerOrWhyThereIsNone() {
        AnalyzeBuildResponse response = mapper.toResponse(MultiFailureResult.RESULT, null);

        assertThat(response.testSummary()).isEqualTo(new TestSummaryResponse(30, 27, 3, 0));
        assertThat(response.aiSummary()).isEqualTo("3 testin 2'si #login-btn yüzünden düştü; G2 için ortak neden kanıtlanamadı.");
        assertThat(response.rootCauseAnalysis()).isNull();
        assertThat(response.failureGroups()).hasSize(2);

        FailureGroupResponse g1 = response.failureGroups().get(0);
        assertThat(g1.id()).isEqualTo("G1");
        assertThat(g1.failureCount()).isEqualTo(2);
        assertThat(g1.signature()).endsWith("@ stepdefinitions.LoginSteps.clickLogin(LoginSteps.java:31)");
        assertThat(g1.exceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");
        assertThat(g1.message()).isEqualTo("no such element: Unable to locate element: #login-btn");
        assertThat(g1.exceptionFile()).isEqualTo("LoginSteps.java");
        assertThat(g1.exceptionLine()).isEqualTo(31);
        assertThat(g1.stackTrace()).contains("Session ID: 1a2b3c4d5e6f7a8b9c0d");      // the representative's, once
        assertThat(g1.aiStatus()).isEqualTo("ANALYZED");
        assertThat(g1.rootCause()).isEqualTo("#login-btn locator'ı bulunamadı.");
        assertThat(g1.actions()).containsExactly("LoginSteps.java:31'deki locator'ı düzelt.");
        assertThat(g1.aiNote()).isNull();
        assertThat(g1.failures()).containsExactly(
                new TestFailureResponse(new TestContextResponse("Login 1", "Giriş butonuna tıkla.",
                        "src/test/resources/features/Login.feature", 5),
                        "org.openqa.selenium.NoSuchElementException", "no such element: Unable to locate element: #login-btn",
                        "LoginSteps.java", 31),
                new TestFailureResponse(new TestContextResponse("Login 2", "Giriş butonuna tıkla.",
                        "src/test/resources/features/Login.feature", 11),
                        "org.openqa.selenium.NoSuchElementException", "no such element: Unable to locate element: #login-btn",
                        "LoginSteps.java", 31));

        FailureGroupResponse g2 = response.failureGroups().get(1);
        assertThat(g2.aiStatus()).isEqualTo("SKIPPED");
        assertThat(g2.rootCause()).isNull();
        assertThat(g2.actions()).isEmpty();
        assertThat(g2.aiNote()).isEqualTo(MultiFailureResult.SKIPPED_NOTE);
        assertThat(g2.logExcerpt()).isEqualTo("Response body: {\"status\":500}");
        // a plain test: its name, nothing invented for the feature file, step or code location
        assertThat(g2.failures()).containsExactly(new TestFailureResponse(
                new TestContextResponse("com.acme.OrderApiTest.createOrder", null, null, null),
                "java.lang.AssertionError", "expected [201] but found [500]", null, null));
        assertThat(g2.exceptionFile()).isNull();
        assertThat(g2.exceptionLine()).isNull();
    }

    /** Test 10 (without a database): one build is one row, and the stored row reads back as the same response. */
    @Test
    void storesAMultiFailureBuildAsOneRowAndReadsItBackUnchanged() {
        Analysis entity = mapper.toEntity(
                JenkinsBuildUrl.parse("https://jenkins.company.com/job/Team/job/UI-Test/125/"), MultiFailureResult.RESULT);
        entity.setId(43L);

        assertThat(entity.getAnalysisStatus()).as("G2 has no AI answer").isEqualTo("PARTIAL");
        assertThat(entity.getHeadline()).isEqualTo("3 başarısız test, 2 hata grubu");
        assertThat(entity.getErrorReason()).isEqualTo(MultiFailureResult.RESULT.aiSummary());
        assertThat(entity.getExceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");
        assertThat(entity.getResultJson())
                .contains("\"failureGroups\"", "\"testSummary\"", "Login 2", "com.acme.OrderApiTest.createOrder")
                .doesNotContain("THE GROUP PROMPT", "RELEVANT_SNIPPET");

        AnalyzeBuildResponse fresh = mapper.toResponse(MultiFailureResult.RESULT, entity);
        AnalyzeBuildResponse stored = mapper.toResponse(entity);

        assertThat(stored.generatedPrompt()).isNull();
        assertThat(stored).usingRecursiveComparison().ignoringFields("generatedPrompt").isEqualTo(fresh);
    }

    @Test
    void isCompletedWhenEveryGroupWasAnswered() {
        BuildAnalysisResult allAnswered = new BuildAnalysisResult(MultiFailureResult.CONTEXT, "P", "A", null,
                MultiFailureResult.RESULT.llmMetrics(), "Özet", List.of(
                FailureGroupAnalysis.analyzed("G1", "x", List.of()), FailureGroupAnalysis.analyzed("G2", "y", List.of())));

        assertThat(mapper.toEntity(JenkinsBuildUrl.parse("http://localhost:8080/job/X/1/"), allAnswered).getAnalysisStatus())
                .isEqualTo("COMPLETED");
    }

    @Test
    void readsAnAnalysisStoredBeforeMultiFailureSupport() {
        Analysis old = new Analysis();
        old.setId(5L);
        old.setJobPath("Mini-UI-Automation");
        old.setBuildNumber(7);
        old.setBuildUrl("http://localhost:8080/job/Mini-UI-Automation/7/");
        old.setAnalyzedAt(java.time.Instant.parse("2026-10-04T09:42:00Z"));
        old.setResultJson("""
                {"buildStatus":"FAILURE","failedScenario":"Open Google","exceptionType":"org.openqa.selenium.NoSuchElementException",
                 "errorCategory":"SELENIUM","aiAnalysis":"🚨 KÖK NEDEN","rootCauseAnalysis":{"rootCause":"x","actions":[]},
                 "testContext":{"scenario":"Open Google","step":"Arama kutusuna tıkla.","featureFile":"Example.feature","featureLine":6}}""");

        AnalyzeBuildResponse response = mapper.toResponse(old);

        assertThat(response.failureGroups()).isEmpty();
        assertThat(response.testSummary()).isNull();
        assertThat(response.aiSummary()).isNull();
        assertThat(response.testContext().scenario()).isEqualTo("Open Google");
    }
}
