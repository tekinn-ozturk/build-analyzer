package com.company.buildanalyzer.application.analysis;

import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link RootCauseAnalysis} as the short text shown to the user (the API's
 * {@code aiAnalysis}), readable in about ten seconds and at most {@value #MAX_LINES} lines:
 * <pre>
 * 🚨 KÖK NEDEN
 * name=qqqqqqqq locator'ı bulunamadı. NoSuchElementException oluştu.
 * 📍 KONUM
 * ExampleSteps.java:28
 * clickSearchBox()
 * ✅ AKSİYON
 * - Locator'ı doğrula.
 * </pre>
 * A section with nothing proven (no location, no action) is left out rather than filled
 * with a placeholder.
 */
@Component
public class RootCauseFormatter {

    static final int MAX_LINES = 10;

    static final String ROOT_CAUSE_HEADING = "🚨 KÖK NEDEN";
    static final String LOCATION_HEADING = "📍 KONUM";
    static final String ACTION_HEADING = "✅ AKSİYON";

    public String format(RootCauseAnalysis analysis) {
        List<String> lines = new ArrayList<>();
        lines.add(ROOT_CAUSE_HEADING);
        lines.add(analysis.rootCause());

        List<String> location = location(analysis);
        if (!location.isEmpty()) {
            lines.add(LOCATION_HEADING);
            lines.addAll(location);
        }
        if (!analysis.actions().isEmpty()) {
            lines.add(ACTION_HEADING);
            analysis.actions().forEach(action -> lines.add("- " + action));
        }
        return String.join("\n", lines);
    }

    /** {@code File.java:28} then {@code method()}; a bare line number only if there is no file. */
    private static List<String> location(RootCauseAnalysis analysis) {
        List<String> location = new ArrayList<>();
        if (analysis.file() != null) {
            location.add(analysis.line() == null ? analysis.file() : analysis.file() + ":" + analysis.line());
        } else if (analysis.line() != null) {
            location.add("Satır " + analysis.line());
        }
        if (analysis.method() != null) {
            location.add(analysis.method().endsWith(")") ? analysis.method() : analysis.method() + "()");
        }
        return location;
    }
}
