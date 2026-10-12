package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.TestFailure;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Groups failed tests that share one error signature, so ten scenarios broken by the same missing
 * locator are analysed once. The signature is deterministic:
 * <pre>exception type + ": " + first message line (run-specific values masked) + " @ " + failure location</pre>
 * The location is the first application frame (else the failing step), so the same exception thrown
 * at two different places stays two groups — the same exception type alone is never taken as the same
 * cause. Masked are only values that differ between two runs of the same error (session ids, UUIDs,
 * timestamps, ports, object hashes, long generated numbers); locators, status codes and expected/actual
 * values are kept. Groups are numbered G1, G2, ... largest first, then in order of first appearance.
 */
final class FailureGrouper {

    static final String NO_EXCEPTION_SIGNATURE = "Logda exception / stack trace yok";

    private record Mask(Pattern pattern, String replacement) {
    }

    private static final List<Mask> RUN_SPECIFIC_VALUES = List.of(
            new Mask(Pattern.compile("\\b[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\b"), "<uuid>"),
            new Mask(Pattern.compile("\\b(?=[0-9a-fA-F]*\\d)(?=[0-9a-fA-F]*[a-fA-F])[0-9a-fA-F]{16,}\\b"), "<id>"),
            new Mask(Pattern.compile("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(?::\\d{2}(?:[.,]\\d+)?)?(?:Z|[+-]\\d{2}:?\\d{2})?"), "<time>"),
            new Mask(Pattern.compile("\\b\\d{1,2}:\\d{2}:\\d{2}(?:[.,]\\d+)?\\b"), "<time>"),
            new Mask(Pattern.compile("(?i)(localhost|127\\.0\\.0\\.1|://[\\w.-]+):\\d{2,5}\\b"), "$1:<port>"),
            new Mask(Pattern.compile("@[0-9a-fA-F]{5,}\\b"), "@<hash>"),
            new Mask(Pattern.compile("\\b\\d{10,}\\b"), "<number>"),
            new Mask(Pattern.compile("\\s+"), " "));

    private FailureGrouper() {
    }

    static List<FailureGroup> group(List<TestFailure> failures) {
        Map<String, List<TestFailure>> bySignature = new LinkedHashMap<>();
        failures.forEach(failure -> bySignature.computeIfAbsent(signature(failure), key -> new ArrayList<>()).add(failure));

        List<Map.Entry<String, List<TestFailure>>> largestFirst = new ArrayList<>(bySignature.entrySet());
        largestFirst.sort(Comparator.comparingInt(entry -> -entry.getValue().size()));   // stable: ties keep log order

        List<FailureGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<TestFailure>> entry : largestFirst) {
            groups.add(new FailureGroup("G" + (groups.size() + 1), entry.getKey(), entry.getValue()));
        }
        return groups;
    }

    static String signature(TestFailure failure) {
        if (failure.exceptionType() == null) {
            return NO_EXCEPTION_SIGNATURE;
        }
        StringBuilder signature = new StringBuilder(failure.exceptionType());
        if (failure.message() != null) {
            signature.append(": ").append(maskRunSpecificValues(failure.message()));
        }
        String where = where(failure);
        if (where != null) {
            signature.append(" @ ").append(where);
        }
        return signature.toString();
    }

    static String maskRunSpecificValues(String text) {
        String masked = text;
        for (Mask mask : RUN_SPECIFIC_VALUES) {
            masked = mask.pattern().matcher(masked).replaceAll(mask.replacement());
        }
        return masked.strip();
    }

    /** {@code stepdefinitions.LoginSteps.submit(LoginSteps.java:42)}, else the failing step's text. */
    private static String where(TestFailure failure) {
        FailureLocation location = failure.failureLocation();
        if (location != null) {
            if (location.file() == null) {
                return location.stepDefinition();
            }
            return location.stepDefinition() + "(" + location.file() + (location.line() == null ? "" : ":" + location.line()) + ")";
        }
        return failure.step() == null ? null : failure.step().text();
    }
}
