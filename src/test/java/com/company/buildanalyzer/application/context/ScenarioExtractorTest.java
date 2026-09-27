package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailedScenario;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ScenarioExtractorTest {

    @Test
    void splitsPrettyFormatterLineIntoNameFileAndLine() {
        List<String> lines = List.of(
                "@exampleScenario",
                "Scenario: Open Google     # src/test/resources/features/Example.feature:4",
                "  * Google'a git.         # stepdefinitions.ExampleSteps.userOpensGoogle()");

        FailedScenario scenario = ScenarioExtractor.extract(lines, -1);

        assertThat(scenario).isEqualTo(
                new FailedScenario("Open Google", "src/test/resources/features/Example.feature", 4));
    }

    @Test
    void prefersFailedScenariosSummaryList() {
        List<String> lines = List.of(
                "Scenario: Valid login     # src/test/resources/features/login.feature:5",
                "Scenario: Invalid login   # src/test/resources/features/login.feature:12",
                "",
                "Failed scenarios:",
                "src/test/resources/features/login.feature:12 # Scenario: Login with invalid credentials");

        FailedScenario scenario = ScenarioExtractor.extract(lines, -1);

        assertThat(scenario).isEqualTo(new FailedScenario(
                "Login with invalid credentials", "src/test/resources/features/login.feature", 12));
    }

    @Test
    void summaryEntryWithoutNameIsResolvedFromTheScenarioLine() {
        List<String> lines = List.of(
                "Scenario: Invalid login   # src/test/resources/features/login.feature:12",
                "Failed scenarios:",
                "src/test/resources/features/login.feature:12");

        assertThat(ScenarioExtractor.extract(lines, -1).name()).isEqualTo("Invalid login");
    }

    @Test
    void picksTheLastScenarioBeforeTheExceptionInMultiScenarioRuns() {
        List<String> lines = List.of(
                "Scenario: Passing one   # a.feature:3",
                "Scenario: Failing one   # a.feature:9",
                "org.openqa.selenium.TimeoutException: timed out",
                "Scenario: Later one     # a.feature:15");

        FailedScenario scenario = ScenarioExtractor.extract(lines, 2);

        assertThat(scenario).isEqualTo(new FailedScenario("Failing one", "a.feature", 9));
    }

    @Test
    void acceptsTurkishKeywordAndLinesWithoutLocation() {
        assertThat(ScenarioExtractor.extract(List.of("Senaryo: Hatalı giriş   # giris.feature:7"), -1))
                .isEqualTo(new FailedScenario("Hatalı giriş", "giris.feature", 7));
        assertThat(ScenarioExtractor.extract(List.of("Scenario Outline: Search for <term>"), -1))
                .isEqualTo(new FailedScenario("Search for <term>", null, null));
    }

    @Test
    void stripsCucumberColourCodesSoTheLocationCommentDoesNotLeakIntoTheName() {
        List<String> lines = List.of(
                "\u001B[1mScenario: Open Google\u001B[0m     \u001B[90m# src/test/resources/features/Example.feature:4\u001B[0m");

        assertThat(ScenarioExtractor.extract(lines, -1)).isEqualTo(
                new FailedScenario("Open Google", "src/test/resources/features/Example.feature", 4));
    }

    @Test
    void acceptsFeaturePathsWithSpacesOrAUriScheme() {
        assertThat(ScenarioExtractor.extract(
                List.of("Scenario: Open Google   # C:\\Jenkins\\my workspace\\features\\Example.feature:4"), -1))
                .isEqualTo(new FailedScenario("Open Google", "C:\\Jenkins\\my workspace\\features\\Example.feature", 4));
        assertThat(ScenarioExtractor.extract(
                List.of("Scenario: Open Google   # classpath:features/Example.feature:4"), -1))
                .isEqualTo(new FailedScenario("Open Google", "classpath:features/Example.feature", 4));
    }

    @Test
    void cleanNameRemovesTheLocationCommentAndPaddingButKeepsAHashInTheName() {
        assertThat(ScenarioExtractor.cleanName("Open Google     # src/test/resources/features/Example.feature:4"))
                .isEqualTo("Open Google");
        assertThat(ScenarioExtractor.cleanName("  Open Google  ")).isEqualTo("Open Google");
        assertThat(ScenarioExtractor.cleanName("Order #12 is shipped")).isEqualTo("Order #12 is shipped");
        assertThat(ScenarioExtractor.cleanName("   ")).isNull();
        assertThat(ScenarioExtractor.cleanName(null)).isNull();
    }

    @Test
    void returnsNullWhenNoScenarioIsPrinted() {
        assertThat(ScenarioExtractor.extract(List.of("[INFO] BUILD FAILURE"), -1)).isNull();
    }
}
