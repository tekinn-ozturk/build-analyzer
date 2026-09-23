package com.company.buildanalyzer.application.prompt;

import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import org.springframework.stereotype.Service;

/**
 * Turns a {@link BuildAnalysisContext} into the natural-language prompt that
 * will be sent to the LLM. It only assembles text — it performs no extraction,
 * classification or model call. Sections whose value is {@code null}/blank are
 * omitted so the model is never fed empty headings.
 */
@Service
public class PromptBuilder {

    private static final String PERSONA = """
            You are a professional QA Automation Engineer and CI/CD expert.
            Analyze the following Jenkins pipeline build failure and produce a precise, \
            actionable diagnosis based only on the evidence provided.""";

    private static final String TASKS = """
            Based on the information above, respond with the following sections:

            1. Root Cause Analysis
            2. Technical Explanation
            3. QA Recommendations
            4. Developer Recommendations
            5. Confidence Level (Low / Medium / High)""";

    public String build(BuildAnalysisContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(PERSONA).append("\n\n");

        // Ordered as requested; each section is skipped when its value is absent.
        appendSection(prompt, "Build Status", context.buildStatus());
        appendSection(prompt, "Failed Scenario", context.failedScenario());
        appendSection(prompt, "Error Category",
                context.errorCategory() == null ? null : context.errorCategory().name());
        appendSection(prompt, "Exception Type", context.exceptionType());
        appendSection(prompt, "Stack Trace", context.stackTrace());
        appendSection(prompt, "Last 500 Log Lines", context.last500Lines());

        prompt.append(TASKS);
        return prompt.toString();
    }

    private void appendSection(StringBuilder prompt, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        prompt.append(label).append(":\n").append(value.strip()).append("\n\n");
    }
}
