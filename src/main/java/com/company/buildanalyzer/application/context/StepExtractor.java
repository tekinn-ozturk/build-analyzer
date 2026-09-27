package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailedStep;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the BDD step that failed. Two sources, most reliable first:
 * <ol>
 *   <li>Cucumber-JVM's pseudo stack frame for the step:
 *       {@code at ✽.Arama kutusuna tıkla.(file:///.../Example.feature:6)} — the {@code ✽}
 *       often arrives mis-encoded as {@code ?}. Gives text, feature file and line.</li>
 *   <li>The pretty formatter's step line, printed right above the step's error:
 *       {@code * Arama kutusuna tıkla. # stepdefinitions.ExampleSteps.clickSearchBox()}.
 *       The nearest step line above the exception header is the failing one. Gives text only.</li>
 * </ol>
 * No BDD output at all (plain JUnit/NUnit/pytest) → {@code null}; nothing is guessed.
 */
final class StepExtractor {

    /** {@code at ✽.<step text>(<uri>/<file>.feature:<line>)} — greedy text: the URI is the last parenthesis. */
    private static final Pattern STEP_FRAME = Pattern.compile(
            "^\\s*at\\s+[✽?*]\\.(.+)\\((?:[^()]*[/\\\\])?([^/\\\\()]+\\.feature):(\\d+)\\)\\s*$");

    /** Gherkin keywords (English + Turkish) of a pretty-printed step followed by its step-definition comment. */
    private static final Pattern PRETTY_STEP = Pattern.compile(
            "^\\s*(?:\\*|Given|When|Then|And|But|Diyelim ki|Eğer ki|O zaman|Ve|Fakat|Ama)\\s+(.+?)\\s+#\\s*\\S+\\s*$");

    /** A scenario header ends the upward search for the pretty step. */
    private static final Pattern SCENARIO_HEADER =
            Pattern.compile("^\\s*(?:Scenario|Scenario Outline|Scenario Template|Senaryo)\\b[^:]*:");

    private StepExtractor() {
    }

    /**
     * @param lines       raw log lines
     * @param headerIndex line index of the exception header, or -1
     * @param stackTrace  the extracted exception block, or {@code null}
     */
    static FailedStep extract(List<String> lines, int headerIndex, String stackTrace) {
        if (stackTrace != null) {
            for (String line : stackTrace.lines().toList()) {
                Matcher frame = STEP_FRAME.matcher(line);
                if (frame.matches()) {
                    return new FailedStep(frame.group(1).strip(), frame.group(2), Integer.valueOf(frame.group(3)));
                }
            }
        }
        for (int i = headerIndex - 1; i >= 0; i--) {
            String line = lines.get(i);
            Matcher step = PRETTY_STEP.matcher(line);
            if (step.matches()) {
                return new FailedStep(step.group(1).strip(), null, null);
            }
            if (SCENARIO_HEADER.matcher(line).find()) {
                break;               // left the failing scenario without meeting a step
            }
        }
        return null;
    }
}
