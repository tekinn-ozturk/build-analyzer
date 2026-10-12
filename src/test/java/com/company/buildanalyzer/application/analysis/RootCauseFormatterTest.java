package com.company.buildanalyzer.application.analysis;

import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import com.company.buildanalyzer.domain.model.TestFailure;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RootCauseFormatterTest {

    private final RootCauseFormatter formatter = new RootCauseFormatter();

    @Test
    void rendersTheShortDecisionSupportFormat() {
        RootCauseAnalysis analysis = new RootCauseAnalysis(
                "name=qqqqqqqq locator'ı bulunamadı. NoSuchElementException oluştu.",
                "ExampleSteps.java", 28, "clickSearchBox",
                List.of("Locator'ı doğrula.", "Sayfadaki gerçek attribute değerini kontrol et."));

        assertThat(formatter.format(analysis)).isEqualTo("""
                🚨 KÖK NEDEN
                name=qqqqqqqq locator'ı bulunamadı. NoSuchElementException oluştu.
                📍 KONUM
                ExampleSteps.java:28
                clickSearchBox()
                ✅ AKSİYON
                - Locator'ı doğrula.
                - Sayfadaki gerçek attribute değerini kontrol et.""");
    }

    @Test
    void neverExceedsTenLinesAndHasNoConfidenceOrExplanationSection() {
        RootCauseAnalysis fullest = new RootCauseAnalysis(
                "x", "A.java", 1, "m()", List.of("a", "b", "c"));

        String text = formatter.format(fullest);

        assertThat(text.lines()).hasSizeLessThanOrEqualTo(RootCauseFormatter.MAX_LINES);
        assertThat(text).contains("m()").doesNotContain("m()()")
                .doesNotContain("GÜVEN").doesNotContain("TEKNİK AÇIKLAMA");
    }

    @Test
    void leavesOutSectionsWithoutEvidence() {
        String text = formatter.format(new RootCauseAnalysis("Maven derlemesi başarısız.", null, null, null, List.of()));

        assertThat(text).isEqualTo("🚨 KÖK NEDEN\nMaven derlemesi başarısız.");
    }

    @Test
    void rendersABuildWithSeveralFailureGroupsWithinTenLinesAndSaysWhichGroupsHaveNoAnswer() {
        List<FailureGroup> groups = new ArrayList<>();
        List<FailureGroupAnalysis> analyses = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            groups.add(new FailureGroup("G" + i, "sig" + i, List.of(
                    new TestFailure(new FailedScenario("S" + i, null, null), null, null, null, null, null, null, null))));
            analyses.add(i == 2
                    ? FailureGroupAnalysis.notAnalyzed("G2", FailureGroupAnalysis.Status.FAILED, "AI çağrısı başarısız: timeout")
                    : FailureGroupAnalysis.analyzed("G" + i, "neden " + i, List.of("aksiyon")));
        }

        String text = formatter.formatGroups("8 grup var.", groups, analyses);

        assertThat(text).startsWith("""
                🚨 BUILD ÖZETİ
                8 grup var.
                🧩 HATA GRUPLARI
                - G1 (1 test): neden 1
                - G2 (1 test): AI analizi yok (AI çağrısı başarısız: timeout)
                - G3 (1 test): neden 3""");
        assertThat(text).endsWith("- G6 (1 test): neden 6\n- ... ve 2 grup daha");
        assertThat(text.lines()).hasSizeLessThanOrEqualTo(RootCauseFormatter.MAX_LINES);
    }

    @Test
    void showsWhatIsKnownOfTheLocation() {
        assertThat(formatter.format(new RootCauseAnalysis("x", "pom.xml", null, null, List.of())))
                .endsWith("📍 KONUM\npom.xml");
        assertThat(formatter.format(new RootCauseAnalysis("x", null, 12, "login", List.of())))
                .endsWith("📍 KONUM\nSatır 12\nlogin()");
    }
}
