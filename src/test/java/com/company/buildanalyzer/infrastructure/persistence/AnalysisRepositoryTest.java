package com.company.buildanalyzer.infrastructure.persistence;

import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.api.mapper.BuildAnalysisResponseMapper;
import com.company.buildanalyzer.api.mapper.MultiFailureResult;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Storing and reading analyses in the real local PostgreSQL (schema by Flyway, checked by Hibernate's
 * {@code validate}). Each test is rolled back, so nothing stays in the database. Runs only when DB_PASSWORD is set.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@EnabledIfEnvironmentVariable(named = "DB_PASSWORD", matches = ".+")
class AnalysisRepositoryTest {

    private static final BuildAnalysisResult SELENIUM_FAILURE = new BuildAnalysisResult(
            new BuildAnalysisContext(
                    "FAILURE",
                    new FailedScenario("Open Google", "src/test/resources/features/Example.feature", 4),
                    new FailedStep("Arama kutusuna tıkla.", "Example.feature", 6),
                    "org.openqa.selenium.NoSuchElementException",
                    ErrorCategory.SELENIUM,
                    "org.openqa.selenium.NoSuchElementException: no such element",
                    new FailureLocation("stepdefinitions.ExampleSteps.clickSearchBox", "ExampleSteps.java", 28),
                    new FailedInteraction("Selenium", "findElement", "name", "qqqqqqqq"),
                    "[ERROR] BUILD FAILURE",
                    "Finished: FAILURE", null, List.of()),
            "THE PROMPT", "🚨 KÖK NEDEN ...",
            new RootCauseAnalysis("name=qqqqqqqq locator'ı bulunamadı.", "ExampleSteps.java", 28, "clickSearchBox",
                    List.of("Locator değerini düzelt.")),
            new LlmMetrics("OPENAI", "gpt-5-mini-2025-08-07", 5880, 574, 6454, 5968L, new BigDecimal("0.002618")), null, List.of());

    @Autowired private AnalysisRepository analyses;
    @Autowired private EntityManager entityManager;

    private final BuildAnalysisResponseMapper mapper = new BuildAnalysisResponseMapper(new ObjectMapper().findAndRegisterModules());

    @Test
    void storesAnAnalysisWithTheRightColumns() {
        Long id = save("http://localhost:8080/job/Team/job/Mini-UI-Automation/7/").getId();

        Analysis stored = analyses.findById(id).orElseThrow();

        assertThat(stored.getBuildUrl()).isEqualTo("http://localhost:8080/job/Team/job/Mini-UI-Automation/7/");
        assertThat(stored.getJobName()).isEqualTo("Mini-UI-Automation");
        assertThat(stored.getJobPath()).isEqualTo("Team/Mini-UI-Automation");
        assertThat(stored.getBuildNumber()).isEqualTo(7);
        assertThat(stored.getBuildStatus()).isEqualTo("FAILURE");
        assertThat(stored.getAnalysisStatus()).isEqualTo("COMPLETED");
        assertThat(stored.getErrorCategory()).isEqualTo("SELENIUM");
        assertThat(stored.getExceptionType()).isEqualTo("org.openqa.selenium.NoSuchElementException");
        assertThat(stored.getHeadline()).isEqualTo("NoSuchElementException");
        assertThat(stored.getErrorReason()).isEqualTo("name=qqqqqqqq locator'ı bulunamadı.");
        assertThat(stored.getResultJson()).contains("rootCauseAnalysis", "testContext").doesNotContain("THE PROMPT");
        assertThat(stored.getRelevantLogs()).isEqualTo("[ERROR] BUILD FAILURE");
        assertThat(stored.getLast200Lines()).isEqualTo("Finished: FAILURE");
        assertThat(stored.getModel()).isEqualTo("gpt-5-mini-2025-08-07");
        assertThat(stored.getTotalTokens()).isEqualTo(6454);
        assertThat(stored.getEstimatedCostUsd()).isEqualByComparingTo("0.002618");
        assertThat(stored.getCreatedAt()).isNotNull();
        assertThat(stored.getAnalyzedAt()).isNotNull();
    }

    @Test
    void readsTheHistoryNewestFirst() {
        Long older = save("http://localhost:8080/job/Mini-UI-Automation/7/").getId();
        Long newer = save("http://localhost:8080/job/Mini-UI-Automation/8/").getId();

        List<AnalysisRepository.Summary> history = analyses.findAllByOrderByAnalyzedAtDesc();

        assertThat(history).extracting(AnalysisRepository.Summary::getId).containsSubsequence(newer, older);
        AnalysisRepository.Summary row = history.stream().filter(r -> r.getId().equals(newer)).findFirst().orElseThrow();
        assertThat(row.getJobPath()).isEqualTo("Mini-UI-Automation");
        assertThat(row.getBuildNumber()).isEqualTo(8);
        assertThat(row.getBuildStatus()).isEqualTo("FAILURE");
        assertThat(row.getHeadline()).isEqualTo("NoSuchElementException");
    }

    @Test
    void readsOneAnalysisBackInTheResponseShapeWithoutThePrompt() {
        Long id = save("http://localhost:8080/job/Mini-UI-Automation/7/").getId();

        AnalyzeBuildResponse response = mapper.toResponse(analyses.findById(id).orElseThrow());

        assertThat(response.id()).isEqualTo(id);
        assertThat(response.rootCauseAnalysis().rootCause()).isEqualTo("name=qqqqqqqq locator'ı bulunamadı.");
        assertThat(response.testContext().step()).isEqualTo("Arama kutusuna tıkla.");
        assertThat(response.locatorValue()).isEqualTo("qqqqqqqq");
        assertThat(response.relevantLogSnippet()).isEqualTo("[ERROR] BUILD FAILURE");
        assertThat(response.generatedPrompt()).isNull();
    }

    /** Test 10: a build with several failed tests is ONE row, read back with every group and failed test. */
    @Test
    void storesABuildWithSeveralFailuresAsOneRowAndReadsItBack() {
        long before = analyses.count();

        Analysis saved = analyses.save(mapper.toEntity(
                JenkinsBuildUrl.parse("https://jenkins.company.com/job/Team/job/UI-Test/125/"), MultiFailureResult.RESULT));
        entityManager.flush();
        entityManager.clear();

        assertThat(analyses.count()).isEqualTo(before + 1);
        Analysis stored = analyses.findById(saved.getId()).orElseThrow();
        assertThat(stored.getAnalysisStatus()).isEqualTo("PARTIAL");
        assertThat(stored.getHeadline()).isEqualTo("3 başarısız test, 2 hata grubu");

        AnalyzeBuildResponse response = mapper.toResponse(stored);
        assertThat(response.testSummary().failed()).isEqualTo(3);
        assertThat(response.aiSummary()).isEqualTo(MultiFailureResult.RESULT.aiSummary());
        assertThat(response.failureGroups()).hasSize(2);
        assertThat(response.failureGroups().get(0).failures())
                .extracting(failure -> failure.testContext().scenario()).containsExactly("Login 1", "Login 2");
        assertThat(response.failureGroups().get(1).aiStatus()).isEqualTo("SKIPPED");
        assertThat(response.generatedPrompt()).isNull();
        assertThat(response).usingRecursiveComparison().ignoringFields("generatedPrompt")
                .isEqualTo(mapper.toResponse(MultiFailureResult.RESULT, stored));
    }

    /** Saves like the POST endpoint does, then forgets the cached entity so reads come from PostgreSQL. */
    private Analysis save(String buildUrl) {
        Analysis saved = analyses.save(mapper.toEntity(JenkinsBuildUrl.parse(buildUrl), SELENIUM_FAILURE));
        entityManager.flush();
        entityManager.clear();
        return saved;
    }
}
