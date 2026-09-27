package com.company.buildanalyzer.domain.model;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RootCauseAnalysisTest {

    private static final FailureLocation EVIDENCE =
            new FailureLocation("stepdefinitions.ExampleSteps.clickSearchBox", "ExampleSteps.java", 28);

    @Test
    void fillsAMissingLocationFromTheParserEvidence() {
        RootCauseAnalysis analysis = new RootCauseAnalysis("x", null, null, null, List.of())
                .withFallbackLocation(EVIDENCE);

        assertThat(analysis.file()).isEqualTo("ExampleSteps.java");
        assertThat(analysis.line()).isEqualTo(28);
        assertThat(analysis.method()).as("simple method name").isEqualTo("clickSearchBox");
    }

    @Test
    void keepsWhatTheModelGaveAndToleratesNoEvidence() {
        RootCauseAnalysis analysis = new RootCauseAnalysis("x", "LoginPage.java", 40, "submit", List.of());

        assertThat(analysis.withFallbackLocation(EVIDENCE)).isEqualTo(analysis);
        assertThat(analysis.withFallbackLocation(null)).isEqualTo(analysis);
    }

    @Test
    void actionsAreNeverNullAndImmutable() {
        List<String> source = new ArrayList<>(List.of("a"));
        RootCauseAnalysis analysis = new RootCauseAnalysis("x", null, null, null, source);
        source.add("b");

        assertThat(analysis.actions()).containsExactly("a");
        assertThat(new RootCauseAnalysis("x", null, null, null, null).actions()).isEmpty();
    }
}
