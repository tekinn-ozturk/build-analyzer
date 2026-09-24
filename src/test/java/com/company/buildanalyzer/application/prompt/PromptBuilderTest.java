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

        // persona + Turkish answer instruction
        assertThat(prompt).contains("QA Otomasyon Mühendisi").contains("CI/CD").contains("Türkçe");
        // sections with values
        assertThat(prompt).contains("Build Durumu:\nFAILURE");
        assertThat(prompt).contains("Başarısız Senaryo:\nOpen Google");
        assertThat(prompt).contains("Hata Kategorisi:\nSELENIUM");
        assertThat(prompt).contains("Exception Tipi:\norg.openqa.selenium.NoSuchElementException");
        assertThat(prompt).contains("Stack Trace:");
        assertThat(prompt).contains("Son 500 Log Satırı:");
        // task list
        assertThat(prompt)
                .contains("Kök Neden Analizi")
                .contains("Teknik Açıklama")
                .contains("QA Önerileri")
                .contains("Geliştirici Önerileri")
                .contains("Güven Seviyesi (Düşük / Orta / Yüksek)");
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

        assertThat(prompt).contains("Build Durumu:\nFAILURE");
        assertThat(prompt).contains("Hata Kategorisi:\nMAVEN");
        assertThat(prompt).doesNotContain("Başarısız Senaryo:");
        assertThat(prompt).doesNotContain("Exception Tipi:");
        assertThat(prompt).doesNotContain("Stack Trace:");
        assertThat(prompt).doesNotContain("Son 500 Log Satırı:");
        // tasks are always present
        assertThat(prompt).contains("Kök Neden Analizi");
    }
}
