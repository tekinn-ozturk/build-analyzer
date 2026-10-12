package com.company.buildanalyzer.application.usecase;

import com.company.buildanalyzer.application.analysis.RootCauseFormatter;
import com.company.buildanalyzer.application.analysis.RootCauseParser;
import com.company.buildanalyzer.application.analysis.RootCauseSchema;
import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.application.context.BuildContextBuilder;
import com.company.buildanalyzer.application.context.RelevantLogExtractor;
import com.company.buildanalyzer.application.context.SeleniumInteractionExtractor;
import com.company.buildanalyzer.application.context.SyntheticConsoleLog;
import com.company.buildanalyzer.application.context.SyntheticConsoleLog.Failure;
import com.company.buildanalyzer.application.port.out.LlmAnalysisException;
import com.company.buildanalyzer.application.port.out.LlmCompletion;
import com.company.buildanalyzer.application.port.out.LlmProvider;
import com.company.buildanalyzer.application.port.out.LlmRequest;
import com.company.buildanalyzer.application.prompt.AnalysisLimits;
import com.company.buildanalyzer.application.prompt.PromptBuilder;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis.Status;
import com.company.buildanalyzer.domain.model.TestSummary;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A build with several failed tests is one analysis with a bounded number of LLM calls — never one call per
 * test — and no failure group is lost or shown as analysed when it was not. The LLM is a mock.
 */
class MultiFailureAnalysisTest {

    private static final Pattern GROUP_DETAILS = Pattern.compile("=== HATA GRUBU (G\\d+) —");

    private final LlmProvider llmProvider = mock(LlmProvider.class);

    private BuildAnalysisResult analyze(String consoleLog) {
        AnalyzeBuildService service = new AnalyzeBuildService(
                (job, build) -> consoleLog,
                new BuildContextBuilder(new ErrorClassifier(), new RelevantLogExtractor(),
                        List.of(new SeleniumInteractionExtractor())),
                new PromptBuilder(new AnalysisLimits()),
                llmProvider, new RootCauseParser(), new RootCauseFormatter());
        return service.analyze("Team/Shop-UI", 125);
    }

    private static LlmCompletion completion(String text) {
        return new LlmCompletion(text, "OPENAI", "gpt-5-mini", 3000, 400, 3400, new BigDecimal("0.00155"));
    }

    /** The model answers for exactly the groups detailed in the prompt it gets. */
    private static Answer<LlmCompletion> answersEveryGroup() {
        return invocation -> {
            LlmRequest request = invocation.getArgument(0);
            String groups = groupIds(request.prompt()).stream()
                    .map(id -> "{\"id\":\"" + id + "\",\"rootCause\":\"" + id + " nedeni\",\"actions\":[\"" + id + " aksiyonu\"]}")
                    .collect(Collectors.joining(","));
            return completion("{\"summary\":\"Build özeti\",\"groups\":[" + groups + "]}");
        };
    }

    private static List<String> groupIds(String prompt) {
        List<String> ids = new ArrayList<>();
        Matcher matcher = GROUP_DETAILS.matcher(prompt);
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    private static String thirtyScenariosTenFailures() {
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 30; i++) {
            switch (i % 6) {
                case 0 -> log.failed("Failing login " + i, Failure.noSuchElement("login-btn"));
                case 3 -> log.failed("Failing payment " + i, i == 3 ? Failure.assertion("120.00", "100.00") : Failure.timeout("pay-now-btn"));
                default -> log.passed("Passing scenario " + i);
            }
        }
        return log.build();
    }

    /** Test 2: 30 scenarios / 10 failures → one analysis, one LLM call, every group answered. */
    @Test
    void thirtyScenariosWithTenFailuresAreAnalysedWithOneCall() {
        when(llmProvider.complete(any(LlmRequest.class))).thenAnswer(answersEveryGroup());

        BuildAnalysisResult result = analyze(thirtyScenariosTenFailures());

        ArgumentCaptor<LlmRequest> sent = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmProvider, times(1)).complete(sent.capture());
        assertThat(sent.getValue().responseSchema()).isEqualTo(RootCauseSchema.FAILURE_GROUPS);
        assertThat(sent.getValue().prompt()).isEqualTo(result.generatedPrompt());

        assertThat(result.context().failureCount()).isEqualTo(10);
        assertThat(result.context().testSummary()).isEqualTo(new TestSummary(30, 20, 10, 0));
        assertThat(result.groupAnalyses()).extracting(FailureGroupAnalysis::groupId).containsExactly("G1", "G2", "G3");
        assertThat(result.groupAnalyses()).allSatisfy(analysis -> assertThat(analysis.status()).isEqualTo(Status.ANALYZED));
        assertThat(result.groupAnalyses().get(0)).isEqualTo(
                FailureGroupAnalysis.analyzed("G1", "G1 nedeni", List.of("G1 aksiyonu")));
        assertThat(result.aiSummary()).isEqualTo("Build özeti");
        assertThat(result.rootCauseAnalysis()).as("no single root cause for ten failures").isNull();
        assertThat(result.aiAnalysis()).startsWith("🚨 BUILD ÖZETİ\nBuild özeti\n🧩 HATA GRUPLARI\n- G1 (5 test): G1 nedeni");
        assertThat(result.llmMetrics().totalTokens()).isEqualTo(3400);
    }

    /** Test 3: ten failures of one error → one group of ten in one call. */
    @Test
    void tenRepeatsOfOneErrorAreOneGroup() {
        when(llmProvider.complete(any(LlmRequest.class))).thenAnswer(answersEveryGroup());
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 10; i++) {
            log.passed("Passing " + i).failed("Failing " + i, Failure.timeout("pay-now-btn"));
        }

        BuildAnalysisResult result = analyze(log.build());

        verify(llmProvider, times(1)).complete(any(LlmRequest.class));
        assertThat(result.context().failureGroups()).hasSize(1);
        assertThat(result.context().failureGroups().get(0).failures()).hasSize(10);
        assertThat(result.generatedPrompt()).contains("- Etkilenen Testler (10):").contains("=== HATA GRUBU G1 — 10 başarısız test ===");
    }

    /** Test 5: 1500 scenarios, 5 failures → one call; the passed scenarios are counted, never listed. */
    @Test
    void fifteenHundredScenariosAreOneCallWithoutThePassedOnes() {
        when(llmProvider.complete(any(LlmRequest.class))).thenAnswer(answersEveryGroup());
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 1500; i++) {
            if (i % 300 == 0) {
                log.failed("Failing scenario " + i, i == 1500 ? Failure.assertion("1", "2") : Failure.noSuchElement("login-btn"));
            } else {
                log.passed("Passing scenario " + i);
            }
        }
        String consoleLog = log.build();

        BuildAnalysisResult result = analyze(consoleLog);

        verify(llmProvider, times(1)).complete(any(LlmRequest.class));
        assertThat(result.context().failureCount()).isEqualTo(5);
        assertThat(result.generatedPrompt())
                .contains("- Test Özeti: toplam 1500, başarılı 1495, başarısız 5, atlanan 0")
                .doesNotContain("Passing scenario");
        assertThat(result.generatedPrompt().length())
                .isLessThan(new AnalysisLimits().getMaxPromptChars())
                .isLessThan(consoleLog.length() / 20);
    }

    /** Test 6: 25 different groups → at most 3 calls (6 groups each); the 7 left over are kept and marked. */
    @Test
    void manyGroupsNeedAtMostTheConfiguredCallsAndNoGroupIsLost() {
        when(llmProvider.complete(any(LlmRequest.class))).thenAnswer(answersEveryGroup());
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 25; i++) {
            log.failed("Failing " + i, Failure.distinct(i));
        }

        BuildAnalysisResult result = analyze(log.build());

        verify(llmProvider, times(3)).complete(any(LlmRequest.class));
        assertThat(result.context().failureGroups()).hasSize(25);
        assertThat(result.groupAnalyses()).hasSize(25);
        assertThat(result.groupAnalyses().subList(0, 18))
                .allSatisfy(analysis -> assertThat(analysis.status()).isEqualTo(Status.ANALYZED));
        assertThat(result.groupAnalyses().subList(18, 25)).allSatisfy(analysis -> {
            assertThat(analysis.status()).isEqualTo(Status.SKIPPED);
            assertThat(analysis.rootCause()).isNull();
            assertThat(analysis.note()).contains("3 çağrı");
        });
        // the three calls are reported as one analysis
        assertThat(result.llmMetrics().promptTokens()).isEqualTo(9000);
        assertThat(result.llmMetrics().totalTokens()).isEqualTo(10_200);
        assertThat(result.llmMetrics().estimatedCostUsd()).isEqualByComparingTo("0.00465");
        assertThat(result.generatedPrompt()).contains("===== AI ÇAĞRISI 1/3 =====").contains("===== AI ÇAĞRISI 3/3 =====");
    }

    /** Test 6: a failed call loses only its own groups, which say why; the other calls' answers are kept. */
    @Test
    void aFailedCallMarksOnlyItsOwnGroups() {
        AtomicInteger calls = new AtomicInteger();
        Answer<LlmCompletion> answers = answersEveryGroup();
        when(llmProvider.complete(any(LlmRequest.class))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new LlmAnalysisException("OpenAI returned HTTP 429 for model 'gpt-5-mini'");
            }
            return answers.answer(invocation);
        });
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 15; i++) {
            log.failed("Failing " + i, Failure.distinct(i));
        }

        BuildAnalysisResult result = analyze(log.build());

        verify(llmProvider, times(3)).complete(any(LlmRequest.class));
        assertThat(result.groupAnalyses()).extracting(FailureGroupAnalysis::status).containsExactly(
                Status.ANALYZED, Status.ANALYZED, Status.ANALYZED, Status.ANALYZED, Status.ANALYZED, Status.ANALYZED,
                Status.FAILED, Status.FAILED, Status.FAILED, Status.FAILED, Status.FAILED, Status.FAILED,
                Status.ANALYZED, Status.ANALYZED, Status.ANALYZED);
        assertThat(result.groupAnalyses().get(6).note())
                .isEqualTo("AI çağrısı başarısız: OpenAI returned HTTP 429 for model 'gpt-5-mini'");
        assertThat(result.llmMetrics().totalTokens()).as("only the two answered calls").isEqualTo(6800);
        assertThat(result.aiSummary()).isEqualTo("Build özeti");
    }

    @Test
    void whenNoCallGetsThroughTheAnalysisFails() {
        when(llmProvider.complete(any(LlmRequest.class))).thenThrow(new LlmAnalysisException("OpenAI down"));

        assertThatThrownBy(() -> analyze(thirtyScenariosTenFailures()))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessage("OpenAI down");
    }

    @Test
    void aGroupTheModelLeftOutIsReportedAsUnanswered() {
        when(llmProvider.complete(any(LlmRequest.class))).thenReturn(completion("""
                {"summary": "Özet", "groups": [{"id": "G1", "rootCause": "neden", "actions": []}]}"""));

        BuildAnalysisResult result = analyze(thirtyScenariosTenFailures());

        assertThat(result.groupAnalyses()).extracting(FailureGroupAnalysis::status)
                .containsExactly(Status.ANALYZED, Status.FAILED, Status.FAILED);
        assertThat(result.groupAnalyses().get(1).note()).isEqualTo(AnalyzeBuildService.NO_ANSWER_NOTE);
    }

    @Test
    void anUnreadableAnswerIsShownAsItIsAndNoGroupCountsAsAnalysed() {
        when(llmProvider.complete(any(LlmRequest.class))).thenReturn(completion("Gruplar: hepsi locator sorunu."));

        BuildAnalysisResult result = analyze(thirtyScenariosTenFailures());

        assertThat(result.aiAnalysis()).isEqualTo("Gruplar: hepsi locator sorunu.");
        assertThat(result.aiSummary()).isNull();
        assertThat(result.groupAnalyses()).allSatisfy(analysis -> {
            assertThat(analysis.status()).isEqualTo(Status.FAILED);
            assertThat(analysis.note()).isEqualTo(AnalyzeBuildService.UNREADABLE_ANSWER_NOTE);
        });
    }
}
