package com.company.buildanalyzer.application.prompt;

import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder();

    @Test
    void includesEverySectionAndTheTaskListForAFullContext() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE",
                "Open Google",
                "org.openqa.selenium.NoSuchElementException",
                ErrorCategory.SELENIUM,
                "org.openqa.selenium.NoSuchElementException: no such element\n\tat ...",
                "line 1\nline 2"
        );

        String prompt = promptBuilder.build(context);

        // persona
        assertThat(prompt).contains("QA Automation Engineer").contains("CI/CD");
        // sections with values
        assertThat(prompt).contains("Build Status:\nFAILURE");
        assertThat(prompt).contains("Failed Scenario:\nOpen Google");
        assertThat(prompt).contains("Error Category:\nSELENIUM");
        assertThat(prompt).contains("Exception Type:\norg.openqa.selenium.NoSuchElementException");
        assertThat(prompt).contains("Stack Trace:");
        assertThat(prompt).contains("Last 500 Log Lines:");
        // task list
        assertThat(prompt)
                .contains("Root Cause Analysis")
                .contains("Technical Explanation")
                .contains("QA Recommendations")
                .contains("Developer Recommendations")
                .contains("Confidence Level (Low / Medium / High)");
    }

    @Test
    void omitsSectionsWhoseValueIsNullOrBlank() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE",
                null,               // no failed scenario
                null,               // no exception type
                ErrorCategory.MAVEN,
                null,               // no stack trace
                "   "               // blank tail
        );

        String prompt = promptBuilder.build(context);

        assertThat(prompt).contains("Build Status:\nFAILURE");
        assertThat(prompt).contains("Error Category:\nMAVEN");
        assertThat(prompt).doesNotContain("Failed Scenario:");
        assertThat(prompt).doesNotContain("Exception Type:");
        assertThat(prompt).doesNotContain("Stack Trace:");
        assertThat(prompt).doesNotContain("Last 500 Log Lines:");
        // tasks are always present
        assertThat(prompt).contains("Root Cause Analysis");
    }
}
