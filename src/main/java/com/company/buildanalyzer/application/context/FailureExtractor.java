package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.application.context.ExceptionBlockExtractor.ExceptionBlock;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.TestFailure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds <b>every</b> failed test of a console log, not just the first one. A failed test is an
 * exception block printed inside a test's own output; a test's output starts at one of the shapes
 * JVM test runs print, whatever the test framework:
 * <ul>
 *   <li>a Gherkin scenario line, {@code Scenario: Open Google  # src/test/resources/features/Example.feature:4}
 *       (Cucumber, or any BDD tool's pretty output);</li>
 *   <li>a runner's per-test result header: Surefire/Failsafe
 *       {@code [ERROR] Example.Open Google -- Time elapsed: 4.5 s <<< ERROR!} (JUnit, TestNG, Cucumber runners)
 *       or Gradle {@code LoginTest > invalidPassword() FAILED}.</li>
 * </ul>
 * It ends at the next test or at a run summary ({@code Tests run:}, {@code 30 Scenarios (...)}, {@code BUILD ...}).
 * Exceptions outside any test (Maven, Jenkins, infrastructure) are not test failures.
 * <p>
 * A scenario's failure is the error Cucumber prints indented right under the failed step; an exception the
 * application merely logged during the scenario does not replace it. A scenario without such an error (a hook
 * failure, or a passing scenario that logged a handled exception) counts only when something confirms it
 * failed: Cucumber's {@code Failed scenarios:} list or the runner's own report — unless the log has neither.
 * <p>
 * The same failure is often printed twice — by the pretty formatter and again by the runner's report. A
 * report is that scenario again when its name is the scenario's name ({@code Example.Open Google},
 * {@code Login.Login with user #2}, Surefire 2.x {@code Open Google(Example)}) or, failing that, when it
 * carries the same error; one report per scenario, so outline rows with one name are still counted apart.
 * A test printed again by a rerun (same scenario location, or same report name) counts once — the last run;
 * a test Surefire lists under {@code Flakes:} (failed, then passed on rerun) does not count.
 * When the {@code Failed scenarios:} list exists it decides which scenarios failed, and a listed scenario
 * without a printed exception is kept without one — its missing fields stay {@code null}.
 * The result is in log order.
 */
final class FailureExtractor {

    /** Surefire/Failsafe per-test header; the per-class "Tests run: ..." line is a summary, checked first. */
    private static final Pattern SUREFIRE_TEST =
            Pattern.compile("^\\s*+(?:\\[\\w+]\\s++)?(\\S.*?)\\s+(?:--\\s+)?Time elapsed:.*<<<\\s*(?:FAILURE|ERROR)!");

    /** Gradle per-test header: {@code LoginTest > invalidPassword() FAILED}. */
    private static final Pattern GRADLE_TEST = Pattern.compile("^\\s*+(\\S.*? > .+?)\\s+FAILED\\s*$");

    /** Lines after which no test's own output continues: run summaries and the start of the next test class. */
    private static final Pattern END_OF_TEST = Pattern.compile(
            "Tests run:|Failed scenarios:|BUILD (?:SUCCESS|FAILURE)|\\d+ tests? completed"
                    + "|^\\s*\\d+ Scenarios? \\(|^\\s*(?:\\[\\w+]\\s+)?Results\\s*:\\s*$|^\\s*(?:\\[INFO]\\s+)?Running [\\w.$]+\\s*$");

    /** A pretty-printed step: indented Gherkin keyword, step text, {@code # step definition}. */
    private static final Pattern STEP_LINE = Pattern.compile("^\\s+" + StepExtractor.KEYWORDS + "\\s+.*#\\s*\\S");

    /** The next scenario's tags or a new feature: a log excerpt ends there. */
    private static final Pattern NEXT_SCENARIO = Pattern.compile("^\\s*(?:@\\S|Feature:|Özellik:)");

    /** A log-level prefix with nothing after it, e.g. {@code [INFO] }. */
    private static final Pattern EMPTY_LOG_RECORD = Pattern.compile("^\\s*\\[(?:INFO|ERROR|WARNING|WARN|DEBUG)]\\s*$");

    private static final Pattern PIPELINE_MARKER = Pattern.compile("^\\s*\\[Pipeline]");

    /** REST-assured's first message line only counts the failed expectations; the next line names them. */
    private static final Pattern EXPECTATIONS_FAILED = Pattern.compile("^\\d+ expectations? failed\\.?$");

    /** How a runner decorates a scenario's name: {@code Login with user #2} (repeated names), {@code Open Google(Example)}. */
    private static final Pattern RUNNER_NAME_DECORATION = Pattern.compile("\\s+#\\d+(?:\\.\\d+)*$|\\([^()]*\\)$");

    /**
     * Surefire's rerun summary ({@code rerunFailingTestsCount}): under {@code Flakes:} one line per test that failed
     * and then passed, each followed by its {@code Run N:} lines.
     */
    private static final Pattern FLAKES_HEADER = Pattern.compile("^\\s*(?:\\[\\w+]\\s+)?Flakes:\\s*$");
    private static final Pattern LEVEL_PREFIX = Pattern.compile("^\\s*(?:\\[\\w+]\\s*)?");
    private static final Pattern RERUN_LINE = Pattern.compile("^Run \\d+:");

    static final int MAX_EXCERPT_LINES = 8;
    static final int MAX_EXCERPT_LINE_LENGTH = 300;
    private static final int EXCERPT_SCAN_LINES = 40;

    /** Where a test's output starts. */
    private record TestStart(int index, FailedScenario scenario, boolean runnerReport) {
    }

    /** A failure found in the log; {@code underStep}: the error Cucumber printed right under a failed step. */
    private record Found(TestFailure failure, boolean runnerReport, boolean underStep, int index) {

        String name() {
            return failure.scenario().name();
        }

        /** {@code Example.feature:4}, or {@code null} for a test known only by name. */
        String location() {
            FailedScenario scenario = failure.scenario();
            return scenario.featureFile() == null || scenario.featureLine() == null
                    ? null : FailureExtractor.location(scenario.featureFile(), scenario.featureLine());
        }
    }

    private FailureExtractor() {
    }

    /**
     * @param lines         raw log lines
     * @param interactionOf reads the failed UI interaction out of an exception block ({@code null} if none)
     * @return the failed tests in log order; empty when no test failed (or none could be recognised)
     */
    static List<TestFailure> extract(List<String> lines, Function<String, FailedInteraction> interactionOf) {
        List<Found> found = new ArrayList<>();
        Map<String, String> scenarioNames = new HashMap<>();   // "Example.feature:4" → "Open Google"
        TestStart test = null;
        Found current = null;                                   // the current test's failure so far
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean endOfTests = END_OF_TEST.matcher(line).find();
            TestStart start = endOfTests ? null : testStart(line, i);
            if (endOfTests || start != null) {
                if (current != null) {
                    found.add(current);
                    current = null;
                }
                test = start;
                if (start != null && !start.runnerReport() && start.scenario().featureFile() != null
                        && start.scenario().featureLine() != null) {
                    scenarioNames.putIfAbsent(location(start.scenario().featureFile(), start.scenario().featureLine()),
                            start.scenario().name());
                }
                continue;
            }
            // checked only after the test-start check, so a test header is never read as an exception header
            if (!ExceptionBlockExtractor.isHeader(line)) {
                continue;
            }
            ExceptionBlock block = ExceptionBlockExtractor.extractAt(lines, i);
            if (test != null) {
                boolean underStep = !test.runnerReport() && printedUnderStep(lines, i);
                // A runner report has one throwable. A scenario's failure is the error under its failed step;
                // without one, its first exception (a hook failure — or one it only logged, see merge()).
                if (current == null || (underStep && !current.underStep())) {
                    current = new Found(failure(lines, test, block, interactionOf), test.runnerReport(), underStep, i);
                }
            }
            i = block.endIndex() - 1;
        }
        if (current != null) {
            found.add(current);
        }
        return merge(found, ScenarioExtractor.failedScenariosList(lines), scenarioNames, flakyTests(lines));
    }

    /** Tests Surefire lists under {@code Flakes:}: they failed, were rerun and passed — not failed tests. */
    private static Set<String> flakyTests(List<String> lines) {
        Set<String> flaky = new HashSet<>();
        for (int i = 0; i < lines.size(); i++) {
            if (!FLAKES_HEADER.matcher(lines.get(i)).matches()) {
                continue;
            }
            for (int j = i + 1; j < lines.size(); j++) {
                String entry = LEVEL_PREFIX.matcher(lines.get(j)).replaceFirst("").strip();
                if (RERUN_LINE.matcher(entry).find()) {
                    continue;
                }
                if (entry.isEmpty() || entry.contains(" ")) {
                    break;                      // the section has ended
                }
                flaky.add(entry);
            }
        }
        return flaky;
    }

    private static TestStart testStart(String line, int index) {
        FailedScenario scenario = ScenarioExtractor.parseScenarioLine(line);
        if (scenario != null) {
            return new TestStart(index, scenario, false);
        }
        if (line.contains("Time elapsed")) {
            Matcher report = SUREFIRE_TEST.matcher(line);
            if (report.find()) {
                return new TestStart(index, new FailedScenario(report.group(1).strip(), null, null), true);
            }
        }
        if (line.contains(" > ") && line.stripTrailing().endsWith("FAILED")) {
            Matcher report = GRADLE_TEST.matcher(line);
            if (report.find()) {
                return new TestStart(index, new FailedScenario(report.group(1).strip(), null, null), true);
            }
        }
        return null;
    }

    /** Cucumber's pretty formatter prints a failed step's error indented right under the step line. */
    private static boolean printedUnderStep(List<String> lines, int header) {
        String error = ExceptionBlockExtractor.plain(lines.get(header));
        return header > 0 && !error.isEmpty() && Character.isWhitespace(error.charAt(0))
                && STEP_LINE.matcher(ExceptionBlockExtractor.plain(lines.get(header - 1))).find();
    }

    private static TestFailure failure(List<String> lines, TestStart test, ExceptionBlock block,
                                       Function<String, FailedInteraction> interactionOf) {
        return new TestFailure(
                test.scenario(),
                StepExtractor.extract(lines, block.headerIndex(), block.stackTrace(), test.index()),
                block.exceptionType(),
                message(block),
                block.stackTrace(),
                block.failureLocation(),
                interactionOf.apply(block.stackTrace()),
                excerpt(lines, block.endIndex()));
    }

    /** The scenario failures that count, the runner reports that are not one of them again, and listed scenarios without an exception. */
    private static List<TestFailure> merge(List<Found> found, List<FailedScenario> listed, Map<String, String> scenarioNames,
                                           Set<String> flaky) {
        Set<String> listedLocations = new HashSet<>();
        Set<String> listedNames = new HashSet<>();
        listed.forEach(entry -> {
            listedLocations.add(location(entry.featureFile(), entry.featureLine()));
            if (entry.name() != null) {
                listedNames.add(entry.name());
            }
        });

        // A scenario printed twice at one location ran twice (a rerun): the last run counts.
        Map<String, Found> lastRun = new LinkedHashMap<>();
        for (Found scenario : found) {
            if (!scenario.runnerReport()) {
                String location = scenario.location();
                lastRun.put(location != null ? location : "#" + scenario.index(), scenario);
            }
        }
        List<Found> scenarios = lastRun.values().stream()
                .filter(scenario -> listed.isEmpty() || (scenario.location() != null
                        ? listedLocations.contains(scenario.location()) : listedNames.contains(scenario.name())))
                .toList();

        boolean[] twinned = new boolean[scenarios.size()];
        boolean[] passedOnRerun = new boolean[scenarios.size()];
        Set<String> twinnedReportNames = new HashSet<>();
        Map<String, Found> unmatchedReports = new LinkedHashMap<>();
        boolean anyReport = false;
        int firstFree = 0;
        for (Found report : found) {
            if (!report.runnerReport()) {
                continue;
            }
            anyReport = true;
            while (firstFree < scenarios.size() && twinned[firstFree]) {
                firstFree++;
            }
            int twin = twin(report, scenarios, twinned, firstFree);
            if (twin >= 0) {
                twinned[twin] = true;
                passedOnRerun[twin] = flaky.contains(report.name());
                twinnedReportNames.add(report.name());
            } else {
                unmatchedReports.put(report.name(), report);   // a name reported again is a rerun: the last run counts
            }
        }

        List<Found> kept = new ArrayList<>();
        for (int s = 0; s < scenarios.size(); s++) {
            Found scenario = scenarios.get(s);
            // an exception that is not a failed step's error counts only when something confirms the scenario failed
            if ((!listed.isEmpty() || scenario.underStep() || twinned[s] || !anyReport) && !passedOnRerun[s]) {
                kept.add(scenario);
            }
        }
        unmatchedReports.values().stream()
                .filter(report -> !twinnedReportNames.contains(report.name()) && !flaky.contains(report.name()))
                .forEach(kept::add);
        kept.sort(Comparator.comparingInt(Found::index));

        List<TestFailure> failures = new ArrayList<>(kept.stream().map(Found::failure).toList());
        Set<String> knownLocations = new HashSet<>();
        List<String> knownByNameOnly = new ArrayList<>();
        kept.forEach(failure -> {
            if (failure.location() != null) {
                knownLocations.add(failure.location());
            } else {
                knownByNameOnly.add(failure.name());
            }
        });
        for (FailedScenario entry : listed) {
            String location = location(entry.featureFile(), entry.featureLine());
            boolean known = knownLocations.contains(location) || (entry.name() != null
                    && knownByNameOnly.stream().anyMatch(name -> sameTest(name, entry.name())));
            if (!known && knownLocations.add(location)) {       // add(): a scenario listed by two runs is added once
                String name = entry.name() != null ? entry.name() : scenarioNames.get(location);
                failures.add(new TestFailure(new FailedScenario(name, entry.featureFile(), entry.featureLine()),
                        null, null, null, null, null, null, null));
            }
        }
        return failures;
    }

    /** The scenario a runner report repeats: by name, else — a runner naming its tests its own way — by the same error. */
    private static int twin(Found report, List<Found> scenarios, boolean[] twinned, int firstFree) {
        for (int s = firstFree; s < scenarios.size(); s++) {
            if (!twinned[s] && sameTest(report.name(), scenarios.get(s).name())) {
                return s;
            }
        }
        for (int s = firstFree; s < scenarios.size(); s++) {
            if (!twinned[s] && sameError(report.failure(), scenarios.get(s).failure())) {
                return s;
            }
        }
        return -1;
    }

    /**
     * {@code Example.Open Google}, {@code Example > Open Google}, {@code Example.Open Google #2} and
     * {@code Open Google(Example)} are the runner's names of scenario {@code Open Google}.
     */
    private static boolean sameTest(String reportName, String scenarioName) {
        return scenarioName != null && (endsWithName(reportName, scenarioName)
                || endsWithName(RUNNER_NAME_DECORATION.matcher(reportName).replaceFirst(""), scenarioName));
    }

    private static boolean endsWithName(String reportName, String scenarioName) {
        return reportName.equals(scenarioName) || reportName.endsWith("." + scenarioName) || reportName.endsWith(" " + scenarioName);
    }

    private static boolean sameError(TestFailure a, TestFailure b) {
        return a.exceptionType() != null && a.exceptionType().equals(b.exceptionType()) && Objects.equals(a.message(), b.message());
    }

    /** {@code src/test/resources/features/Example.feature}, 4 → {@code Example.feature:4} */
    private static String location(String featureFile, Integer line) {
        return featureFile.substring(Math.max(featureFile.lastIndexOf('/'), featureFile.lastIndexOf('\\')) + 1) + ":" + line;
    }

    /**
     * First line of the exception message: after {@code Type:} on the header, or the next line when the header ends
     * there (Surefire). REST-assured's "1 expectation failed." gets the next line too, which says what was expected.
     */
    private static String message(ExceptionBlock block) {
        List<String> blockLines = block.stackTrace().lines().toList();
        String header = blockLines.get(0);
        String message = header.substring(header.indexOf(block.exceptionType()) + block.exceptionType().length())
                .replaceFirst("^:\\s*", "").strip();
        int next = 1;
        if (message.isEmpty() && isMessageLine(blockLines, next)) {
            message = blockLines.get(next++).strip();
        }
        if (EXPECTATIONS_FAILED.matcher(message).matches() && isMessageLine(blockLines, next)) {
            message += " " + blockLines.get(next).strip();
        }
        return message.isEmpty() ? null : message;
    }

    private static boolean isMessageLine(List<String> blockLines, int index) {
        return index < blockLines.size() && !ExceptionBlockExtractor.TRACE_LINE.matcher(blockLines.get(index)).matches();
    }

    /**
     * A few lines the test printed right after the exception — e.g. {@code Response body: {...}} — up to the next
     * test, the next scenario's tags or a run summary. Stack frames, skipped steps, bare log-level prefixes and
     * pipeline markers are left out.
     */
    private static String excerpt(List<String> lines, int from) {
        List<String> excerpt = new ArrayList<>();
        for (int i = from; i < Math.min(lines.size(), from + EXCERPT_SCAN_LINES) && excerpt.size() < MAX_EXCERPT_LINES; i++) {
            String line = ExceptionBlockExtractor.plain(lines.get(i));
            if (END_OF_TEST.matcher(line).find() || NEXT_SCENARIO.matcher(line).find() || testStart(line, i) != null) {
                break;
            }
            if (line.isBlank() || EMPTY_LOG_RECORD.matcher(line).matches() || ExceptionBlockExtractor.TRACE_LINE.matcher(line).matches()
                    || StepExtractor.PRETTY_STEP.matcher(line).matches() || PIPELINE_MARKER.matcher(line).find()) {
                continue;
            }
            String stripped = line.strip();
            excerpt.add(stripped.length() <= MAX_EXCERPT_LINE_LENGTH ? stripped : stripped.substring(0, MAX_EXCERPT_LINE_LENGTH) + "…");
        }
        return excerpt.isEmpty() ? null : String.join("\n", excerpt);
    }
}
