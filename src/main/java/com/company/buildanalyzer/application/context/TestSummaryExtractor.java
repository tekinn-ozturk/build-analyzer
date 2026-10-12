package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.TestSummary;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the test counts from the test runner's own summary line. One rule per summary format; the
 * first format found in the log wins. Surefire/Failsafe totals come first: in a Maven build they count
 * every test of the module — the scenarios of a Cucumber JUnit runner and plain JUnit/TestNG tests alike —
 * while Cucumber's own line counts only its scenarios. Several lines of the winning format (one per
 * module / runner) are added up. A count the format does not print stays {@code null} — nothing is
 * estimated. Formats that leave zero counts out (Cucumber, pytest, Jest, Gradle's "skipped") are read as
 * 0 for a missing word.
 */
final class TestSummaryExtractor {

    private record Format(Pattern line, Function<Matcher, TestSummary> read) {
    }

    private static final List<Format> FORMATS = List.of(
            // Surefire / Failsafe totals: "Tests run: 30, Failures: 3, Errors: 7, Skipped: 1"
            // (the per-class lines also carry "Time elapsed" and are left out, so nothing is counted twice)
            new Format(Pattern.compile(
                    "^(?!.*Time elapsed).*Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)"),
                    m -> {
                        int run = Integer.parseInt(m.group(1));
                        int failed = Integer.parseInt(m.group(2)) + Integer.parseInt(m.group(3));
                        int skipped = Integer.parseInt(m.group(4));
                        return new TestSummary(run, run - failed - skipped, failed, skipped);
                    }),
            // Cucumber: "30 Scenarios (10 failed, 20 passed)"
            new Format(Pattern.compile("^\\s*(\\d+) Scenarios? \\(([^)]*)\\)"), m -> new TestSummary(
                    Integer.valueOf(m.group(1)),
                    count(m.group(2), "passed"), count(m.group(2), "failed"), count(m.group(2), "skipped"))),
            // Gradle: "30 tests completed, 10 failed, 2 skipped"
            new Format(Pattern.compile("(\\d+) tests? completed, (\\d+) failed(?:, (\\d+) skipped)?"), m -> {
                int total = Integer.parseInt(m.group(1));
                int failed = Integer.parseInt(m.group(2));
                int skipped = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
                return new TestSummary(total, total - failed - skipped, failed, skipped);
            }),
            // pytest: "===== 10 failed, 20 passed, 1 skipped in 12.30s =====" (no total printed)
            new Format(Pattern.compile("^=+ (.*\\d+ (?:passed|failed).*?) in [\\d.]+s\\b.*=+\\s*$"), m -> new TestSummary(
                    null,
                    count(m.group(1), "passed"), count(m.group(1), "failed") + count(m.group(1), "errors?"),
                    count(m.group(1), "skipped"))),
            // Jest: "Tests:       2 failed, 28 passed, 30 total"
            new Format(Pattern.compile("^\\s*Tests:\\s+(.*\\d+ total)"), m -> new TestSummary(
                    count(m.group(1), "total"),
                    count(m.group(1), "passed"), count(m.group(1), "failed"), count(m.group(1), "skipped"))));

    private TestSummaryExtractor() {
    }

    /** @return the counts of the first summary format found, or {@code null} if the log has none */
    static TestSummary extract(List<String> lines) {
        List<String> plain = lines.stream()
                .map(line -> line.indexOf('\u001B') < 0 ? line : ScenarioExtractor.ANSI_ESCAPE.matcher(line).replaceAll(""))
                .toList();
        for (Format format : FORMATS) {
            TestSummary total = null;
            for (String line : plain) {
                Matcher matcher = format.line().matcher(line);
                if (matcher.find()) {
                    total = add(total, format.read().apply(matcher));
                }
            }
            if (total != null) {
                return total;
            }
        }
        return null;
    }

    /** {@code "10 failed, 20 passed"}, {@code "failed"} → 10; 0 when the word is not there. */
    private static int count(String counts, String word) {
        Matcher matcher = Pattern.compile("(\\d+) " + word + "\\b").matcher(counts);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }

    private static TestSummary add(TestSummary sum, TestSummary next) {
        if (sum == null) {
            return next;
        }
        return new TestSummary(plus(sum.total(), next.total()), plus(sum.passed(), next.passed()),
                plus(sum.failed(), next.failed()), plus(sum.skipped(), next.skipped()));
    }

    private static Integer plus(Integer a, Integer b) {
        return a == null || b == null ? null : a + b;
    }
}
