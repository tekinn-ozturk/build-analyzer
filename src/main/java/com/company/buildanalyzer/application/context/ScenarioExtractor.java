package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailedScenario;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the failing Cucumber scenario and splits it into name, feature file and line.
 * Two console formats are understood:
 * <ul>
 *   <li>summary list: {@code Failed scenarios:} followed by
 *       {@code src/test/resources/features/login.feature:12 # Scenario: Login with invalid credentials}</li>
 *   <li>pretty formatter: {@code Scenario: Open Google     # src/test/resources/features/Example.feature:4}</li>
 * </ul>
 * Without a summary list, the last scenario printed before the exception is the one
 * that failed (correct for multi-scenario runs); failing that, the first scenario.
 * English and Turkish ({@code # language: tr}) keywords are accepted.
 */
final class ScenarioExtractor {

    private static final String KEYWORD = "(?:Scenario(?: Outline| Template)?|Senaryo(?: [Tt]aslağı)?)";

    /** {@code <feature>:<line> [# [Scenario:] <name>]} under "Failed scenarios:". */
    private static final Pattern FAILED_LIST_ENTRY = Pattern.compile(
            "^\\s*(\\S+\\.feature):(\\d+)\\s*(?:#\\s*(?:" + KEYWORD + ":\\s*)?(.*))?$");

    /** {@code Scenario: <name> [# <feature>:<line>]}; the feature path may contain spaces or a URI scheme. */
    private static final Pattern SCENARIO_LINE = Pattern.compile(
            "^\\s*" + KEYWORD + ":\\s*(.*?)\\s*(?:#\\s*(\\S.*?\\.feature):(\\d+))?\\s*$");

    /** ANSI colour codes of Cucumber's coloured (non-monochrome) pretty output, e.g. {@code ESC[90m}. */
    private static final Pattern ANSI_ESCAPE = Pattern.compile("\\u001B\\[[0-9;]*[A-Za-z]");

    /** A location comment left on a name, e.g. {@code Open Google     # src/.../Example.feature:4}. */
    private static final Pattern TRAILING_LOCATION_COMMENT = Pattern.compile("\\s+#\\s*\\S.*\\.feature(?::\\d+)?\\s*$");

    private ScenarioExtractor() {
    }

    /**
     * @param failureIndex line index of the exception header, or -1 if unknown
     */
    static FailedScenario extract(List<String> lines, int failureIndex) {
        FailedScenario fromSummary = fromFailedScenariosList(lines);
        if (fromSummary != null) {
            return fromSummary;
        }
        if (failureIndex > 0) {
            for (int i = failureIndex - 1; i >= 0; i--) {
                FailedScenario scenario = parseScenarioLine(lines.get(i));
                if (scenario != null) {
                    return scenario;
                }
            }
        }
        for (String line : lines) {
            FailedScenario scenario = parseScenarioLine(line);
            if (scenario != null) {
                return scenario;
            }
        }
        return null;
    }

    private static FailedScenario fromFailedScenariosList(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).contains("Failed scenarios:")) {
                continue;
            }
            for (int j = i + 1; j < lines.size(); j++) {
                if (lines.get(j).isBlank()) {
                    continue;
                }
                Matcher entry = FAILED_LIST_ENTRY.matcher(ANSI_ESCAPE.matcher(lines.get(j)).replaceAll(""));
                if (!entry.matches()) {
                    return null;      // the failure list has ended
                }
                String file = entry.group(1);
                Integer line = Integer.valueOf(entry.group(2));
                String name = cleanName(entry.group(3));
                // Some Cucumber versions print only "<feature>:<line>": look the name up.
                return new FailedScenario(name != null ? name : nameAt(lines, file, line), file, line);
            }
        }
        return null;
    }

    private static String nameAt(List<String> lines, String file, int line) {
        for (String candidate : lines) {
            FailedScenario scenario = parseScenarioLine(candidate);
            if (scenario != null && file.equals(scenario.featureFile())
                    && Integer.valueOf(line).equals(scenario.featureLine())) {
                return scenario.name();
            }
        }
        return null;
    }

    private static FailedScenario parseScenarioLine(String line) {
        Matcher scenario = SCENARIO_LINE.matcher(ANSI_ESCAPE.matcher(line).replaceAll(""));
        if (!scenario.matches()) {
            return null;
        }
        String name = cleanName(scenario.group(1));
        if (name == null) {
            return null;
        }
        String file = scenario.group(2);
        Integer featureLine = scenario.group(3) == null ? null : Integer.valueOf(scenario.group(3));
        return new FailedScenario(name, file, featureLine);
    }

    /**
     * The scenario name only: no colour codes, no {@code # <feature>:<line>} comment,
     * no padding — {@code "Open Google     # src/.../Example.feature:4"} → {@code "Open Google"}.
     * A {@code #} inside the name itself (e.g. {@code Order #12}) is kept.
     */
    static String cleanName(String value) {
        if (value == null) {
            return null;
        }
        String name = ANSI_ESCAPE.matcher(value).replaceAll("");
        name = TRAILING_LOCATION_COMMENT.matcher(name).replaceFirst("").strip();
        return name.isEmpty() ? null : name;
    }
}
