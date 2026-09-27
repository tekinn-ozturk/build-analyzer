package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailedStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StepExtractorTest {

    /** Cucumber-JVM pretty output of build #7, around the failure. */
    private static final List<String> PRETTY_LOG = List.of(
            "Scenario: Open Google     # src/test/resources/features/Example.feature:4",
            "  * Google'a git.         # stepdefinitions.ExampleSteps.userOpensGoogle()",
            "  * Arama kutusuna tıkla. # stepdefinitions.ExampleSteps.clickSearchBox()",
            "      org.openqa.selenium.NoSuchElementException: no such element");

    @Test
    void prefersCucumberStepFrameWithTextFileAndLine() {
        String stackTrace = String.join("\n",
                "org.openqa.selenium.NoSuchElementException: no such element",
                "at stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)",
                "at ?.Arama kutusuna tıkla.(file:///C:/ws/src/test/resources/features/Example.feature:6)");

        FailedStep step = StepExtractor.extract(PRETTY_LOG, 3, stackTrace);

        assertThat(step).isEqualTo(new FailedStep("Arama kutusuna tıkla.", "Example.feature", 6));
    }

    @Test
    void acceptsTheProperStepSymbolAndParenthesesInsideTheStepText() {
        String stackTrace = "at ✽.the user (admin) logs in(classpath:features/login.feature:12)";

        assertThat(StepExtractor.extract(List.of(), -1, stackTrace))
                .isEqualTo(new FailedStep("the user (admin) logs in", "login.feature", 12));
    }

    @Test
    void fallsBackToTheNearestPrettyStepAboveTheException() {
        FailedStep step = StepExtractor.extract(PRETTY_LOG, 3, "org.openqa.selenium.NoSuchElementException: x");

        // the step right above the error — not the previous, passing one
        assertThat(step).isEqualTo(new FailedStep("Arama kutusuna tıkla.", null, null));
    }

    @Test
    void understandsGherkinKeywordsInEnglishAndTurkish() {
        assertThat(StepExtractor.extract(List.of("    When the user clicks submit # steps.Login.submit()", "X"), 1, null).text())
                .isEqualTo("the user clicks submit");
        assertThat(StepExtractor.extract(List.of("    Eğer ki kullanıcı giriş yapar # adimlar.Giris.yap()", "X"), 1, null).text())
                .isEqualTo("kullanıcı giriş yapar");
    }

    @Test
    void doesNotCrossIntoThePreviousScenarioOrGuessWithoutBddOutput() {
        List<String> lines = List.of(
                "  * an old step # steps.Old.step()",
                "Scenario: Second # b.feature:9",
                "java.lang.IllegalStateException: boom");

        assertThat(StepExtractor.extract(lines, 2, null)).isNull();
        assertThat(StepExtractor.extract(List.of("[ERROR] Tests run: 1", "java.lang.AssertionError: x"), 1,
                "java.lang.AssertionError: x\nat com.acme.LoginTest.login(LoginTest.java:14)")).isNull();
    }
}
