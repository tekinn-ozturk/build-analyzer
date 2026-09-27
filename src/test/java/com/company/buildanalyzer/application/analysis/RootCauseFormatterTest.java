package com.company.buildanalyzer.application.analysis;

import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import org.junit.jupiter.api.Test;

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
    void showsWhatIsKnownOfTheLocation() {
        assertThat(formatter.format(new RootCauseAnalysis("x", "pom.xml", null, null, List.of())))
                .endsWith("📍 KONUM\npom.xml");
        assertThat(formatter.format(new RootCauseAnalysis("x", null, 12, "login", List.of())))
                .endsWith("📍 KONUM\nSatır 12\nlogin()");
    }
}
