package com.company.buildanalyzer.application.prompt;

import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.application.context.BuildContextBuilder;
import com.company.buildanalyzer.application.context.RelevantLogExtractor;
import com.company.buildanalyzer.application.context.SeleniumInteractionExtractor;
import com.company.buildanalyzer.application.context.SyntheticConsoleLog;
import com.company.buildanalyzer.application.context.SyntheticConsoleLog.Failure;
import com.company.buildanalyzer.application.prompt.PromptBuilder.GroupPrompt;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.TestFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The prompt(s) of a build with several failed tests: counted groups, one stack trace each, bounded number of calls. */
class GroupPromptTest {

    private final BuildContextBuilder contextBuilder = new BuildContextBuilder(
            new ErrorClassifier(), new RelevantLogExtractor(), List.of(new SeleniumInteractionExtractor()));

    private static BuildAnalysisContext thirtyScenariosTenFailures(BuildContextBuilder builder) {
        SyntheticConsoleLog log = new SyntheticConsoleLog();
        for (int i = 1; i <= 30; i++) {
            switch (i % 6) {
                case 0 -> log.failed("Failing login " + i, Failure.noSuchElement("login-btn"));
                case 3 -> log.failed("Failing payment " + i, i == 3 ? Failure.assertion("120.00", "100.00") : Failure.timeout("pay-now-btn"));
                default -> log.passed("Passing scenario " + i);
            }
        }
        return builder.build(log.build());
    }

    @Test
    void sendsCountedGroupsWithOneRepresentativeStackTraceInOnePrompt() {
        BuildAnalysisContext context = thirtyScenariosTenFailures(contextBuilder);

        List<GroupPrompt> prompts = new PromptBuilder(new AnalysisLimits()).buildGroupPrompts(context);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0).groups()).extracting(FailureGroup::id).containsExactly("G1", "G2", "G3");
        String prompt = prompts.get(0).prompt();
        assertThat(prompt)
                .contains("Bu build'de birden fazla test başarısız oldu.")
                .contains("Aynı exception türü aynı kök neden demek değildir.")
                .contains("=== BUILD ÖZETİ ===\n- Build Durumu: FAILURE\n- Hata Kategorisi: SELENIUM\n"
                        + "- Test Özeti: toplam 30, başarılı 20, başarısız 10, atlanan 0\n"
                        + "- Logda Bulunan Başarısız Test: 10 (3 hata grubu)\n"
                        + "- Bu İstekte Ayrıntısı Verilen Gruplar: G1, G2, G3")
                .contains("=== TÜM HATA GRUPLARI ===\n- G1: 5 test — org.openqa.selenium.NoSuchElementException")
                .contains("=== HATA GRUBU G1 — 5 başarısız test ===")
                .contains("- Step Definition: stepdefinitions.LoginSteps.clickLogin")
                .contains("- Hata Dosyası:Satırı: LoginSteps.java:31")
                .contains("- Locator Değeri: login-btn")
                .contains("- Etkilenen Testler (5):\n  - Failing login 6 (src/test/resources/features/Shop.feature:28)")
                .contains("=== HATA GRUBU G2 — 4 başarısız test ===")
                .contains("=== HATA GRUBU G3 — 1 başarısız test ===")
                .contains("- Exception: java.lang.AssertionError")
                .contains("{\"summary\": \"...\", \"groups\": [{\"id\": \"G1\", \"rootCause\": \"...\", \"actions\": [\"...\"]}]}")
                .doesNotContain("Passing scenario")                      // passed tests are only counted
                .doesNotContain("=== 3. JENKINS LOG");                    // no raw tail in the group prompt
        // one stack trace per group (the representative), not one per failed test
        assertThat(occurrences(prompt, "Stack Trace (temsilî):")).isEqualTo(3);
        assertThat(occurrences(prompt, "Session ID:")).isEqualTo(2);
        assertThat(prompt).endsWith("3'ü doldurmak zorunda değilsin.");
    }

    @Test
    void namesAtMostTenTestsPerGroupButKeepsTheFullCount() {
        BuildAnalysisContext context = contextWithGroups(1, 25, 0);

        String prompt = new PromptBuilder(new AnalysisLimits()).buildGroupPrompts(context).get(0).prompt();

        assertThat(prompt)
                .contains("- Etkilenen Testler (25):")
                .contains("  - Scenario G1-10 (features/shop.feature:10)")
                .doesNotContain("Scenario G1-11 ")
                .contains("  - ... ve 15 test daha");
    }

    @Test
    void neverMakesMorePromptsThanTheCallLimitAndListsEveryGroupInTheIndex() {
        BuildAnalysisContext context = contextWithGroups(20, 1, 0);

        List<GroupPrompt> prompts = new PromptBuilder(new AnalysisLimits()).buildGroupPrompts(context);   // 6 groups × 3 calls

        assertThat(prompts).hasSize(3);
        assertThat(prompts).extracting(prompt -> prompt.groups().size()).containsExactly(6, 6, 6);
        assertThat(prompts.stream().flatMap(prompt -> prompt.groups().stream()).map(FailureGroup::id))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 18).mapToObj(i -> "G" + i).toList());
        assertThat(prompts.get(1).prompt())
                .contains("- Bu İstekte Ayrıntısı Verilen Gruplar: G7, G8, G9, G10, G11, G12 (istek 2/3)")
                .contains("- G20: 1 test")                                  // the index still shows every group
                .doesNotContain("=== HATA GRUBU G1 —")
                .doesNotContain("=== HATA GRUBU G19 —");
    }

    /**
     * Split over calls, each call still asks for a summary of the whole build and leaves the counting to the app:
     * build #14 of Mini-UI-Automation (7 groups, 2 calls) once got "6 hata grubu toplam 9 testi" from its first call.
     */
    @Test
    void asksEveryCallForAWholeBuildSummaryWithoutCounts() {
        BuildAnalysisContext context = contextWithGroups(7, 1, 0);

        List<GroupPrompt> prompts = new PromptBuilder(new AnalysisLimits()).buildGroupPrompts(context);   // 6 + 1 groups

        assertThat(prompts).hasSize(2);
        assertThat(prompts).allSatisfy(prompt -> assertThat(prompt.prompt())
                .contains("- summary: TÜM build için, en fazla 3 kısa cümle. TÜM HATA GRUPLARI'ndaki her grubu hesaba kat; "
                        + "ayrıntısı bu istekte olmayan gruplar için oradaki satır yeterli.")
                .contains("Test ya da grup sayısı yazma, kendin sayma veya toplama yapma; sayıları uygulama gösteriyor.")
                .contains("- G7: 1 test"));                                // the index the summary is written from
    }

    @Test
    void splitsByPromptSizeAndKeepsEachPromptWithinTheLimit() {
        AnalysisLimits limits = new AnalysisLimits();
        limits.setMaxPromptChars(12_000);
        limits.setMaxGroupsPerCall(100);
        limits.setMaxLlmCalls(100);
        BuildAnalysisContext context = contextWithGroups(10, 1, 3_000);

        List<GroupPrompt> prompts = new PromptBuilder(limits).buildGroupPrompts(context);

        assertThat(prompts).hasSizeGreaterThan(1);
        assertThat(prompts).allSatisfy(prompt -> assertThat(prompt.prompt().length()).isLessThanOrEqualTo(12_000));
        assertThat(prompts.stream().flatMap(prompt -> prompt.groups().stream()).map(FailureGroup::id))
                .containsExactlyElementsOf(IntStream.rangeClosed(1, 10).mapToObj(i -> "G" + i).toList());
    }

    /** A huge step argument, locator, step definition or log line cannot blow a prompt past the limit. */
    @Test
    void keepsAPromptSmallEvenWhenAFieldIsHuge() {
        String huge = "z".repeat(200_000);
        TestFailure giant = new TestFailure(new FailedScenario("S1", "f.feature", 1), new FailedStep("Adım " + huge, "f.feature", 2),
                "java.lang.IllegalStateException", huge, "java.lang.IllegalStateException: " + huge,
                new FailureLocation("steps.S." + huge, "S.java", 3),
                new FailedInteraction("Selenium", "findElement", "css selector", huge), huge);
        BuildAnalysisContext context = new BuildAnalysisContext("FAILURE", null, null, null, ErrorCategory.UNKNOWN,
                null, null, null, null, "tail", null, List.of(
                new FailureGroup("G1", "java.lang.IllegalStateException: " + huge, List.of(giant)),
                new FailureGroup("G2", "java.lang.IllegalStateException: small", List.of(giant))));

        List<GroupPrompt> prompts = new PromptBuilder(new AnalysisLimits()).buildGroupPrompts(context);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0).prompt().length()).isLessThan(15_000);
    }

    /** Many small groups in one call: the list of group ids is counted too, so the prompt stays within the limit. */
    @Test
    void countsTheGroupIdsAgainstTheLimit() {
        AnalysisLimits limits = new AnalysisLimits();
        limits.setMaxGroupsPerCall(100);
        limits.setMaxLlmCalls(1);
        BuildAnalysisContext context = contextWithGroups(100, 1, 600);

        List<GroupPrompt> prompts = new PromptBuilder(limits).buildGroupPrompts(context);

        assertThat(prompts).hasSize(1);
        assertThat(prompts.get(0).prompt().length()).isLessThanOrEqualTo(limits.getMaxPromptChars());
    }

    @Test
    void sendsOnlyTheFirstLinesOfAVeryLongStackTrace() {
        BuildAnalysisContext context = contextWithGroups(2, 1, 20_000);

        String prompt = new PromptBuilder(new AnalysisLimits()).buildGroupPrompts(context).get(0).prompt();

        assertThat(prompt).contains("... (" + (stackTraceLines(20_000) - PromptBuilder.MAX_GROUP_TRACE_LINES) + " satır daha)");
        assertThat(prompt.length()).isLessThan(new AnalysisLimits().getMaxPromptChars());
    }

    /** {@code groups} groups of {@code perGroup} failures each; every representative has a stack trace of ~{@code traceChars}. */
    private static BuildAnalysisContext contextWithGroups(int groups, int perGroup, int traceChars) {
        List<FailureGroup> failureGroups = new ArrayList<>();
        for (int g = 1; g <= groups; g++) {
            List<TestFailure> failures = new ArrayList<>();
            for (int f = 1; f <= perGroup; f++) {
                failures.add(new TestFailure(
                        new FailedScenario("Scenario G" + g + "-" + f, "features/shop.feature", f),
                        null, "java.lang.IllegalStateException", "state " + g, stackTrace(g, traceChars),
                        new FailureLocation("steps.ShopSteps.step" + g, "ShopSteps.java", g), null, null));
            }
            failureGroups.add(new FailureGroup("G" + g, "java.lang.IllegalStateException: state " + g
                    + " @ steps.ShopSteps.step" + g + "(ShopSteps.java:" + g + ")", failures));
        }
        return new BuildAnalysisContext("FAILURE", null, null, "java.lang.IllegalStateException", ErrorCategory.UNKNOWN,
                null, null, null, null, "tail", null, failureGroups);
    }

    private static String stackTrace(int group, int chars) {
        if (chars == 0) {
            return null;
        }
        List<String> lines = new ArrayList<>(List.of("java.lang.IllegalStateException: state " + group));
        for (int i = 0; i < stackTraceLines(chars) - 1; i++) {
            lines.add("at com.acme.framework.Layer" + i + ".invoke(Layer" + i + ".java:" + (i + 10) + ") // padding padding padding");
        }
        return String.join("\n", lines);
    }

    private static int stackTraceLines(int chars) {
        return Math.max(2, chars / 75);
    }

    private static int occurrences(String text, String part) {
        return text.split(java.util.regex.Pattern.quote(part), -1).length - 1;
    }
}
