package com.company.buildanalyzer.application.analysis;

import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
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
 * with a placeholder. A build with several failed tests is rendered by {@link #formatGroups}.
 */
@Component
public class RootCauseFormatter {

    static final int MAX_LINES = 10;
    static final int MAX_GROUP_LINES = 6;

    static final String ROOT_CAUSE_HEADING = "🚨 KÖK NEDEN";
    static final String LOCATION_HEADING = "📍 KONUM";
    static final String ACTION_HEADING = "✅ AKSİYON";
    static final String SUMMARY_HEADING = "🚨 BUILD ÖZETİ";
    static final String GROUPS_HEADING = "🧩 HATA GRUPLARI";

    /**
     * A build with several failed tests, within {@value #MAX_LINES} lines:
     * <pre>
     * 🚨 BUILD ÖZETİ
     * 10 testin 7'si aynı locator yüzünden düştü; ...
     * 🧩 HATA GRUPLARI
     * - G1 (7 test): id=pay-now-btn 10 sn içinde tıklanabilir olmadı.
     * - G2 (3 test): AI analizi yok (Çağrı sınırı ...)
     * </pre>
     *
     * @param analyses one per group, in the order of {@code groups}
     */
    public String formatGroups(String summary, List<FailureGroup> groups, List<FailureGroupAnalysis> analyses) {
        List<String> lines = new ArrayList<>();
        lines.add(SUMMARY_HEADING);
        lines.add(summary != null ? summary : "AI build özeti vermedi.");
        lines.add(GROUPS_HEADING);
        for (int i = 0; i < groups.size() && i < MAX_GROUP_LINES; i++) {
            FailureGroupAnalysis analysis = analyses.get(i);
            lines.add("- " + groups.get(i).id() + " (" + groups.get(i).failures().size() + " test): "
                    + (analysis.status() == FailureGroupAnalysis.Status.ANALYZED
                    ? analysis.rootCause() : "AI analizi yok (" + analysis.note() + ")"));
        }
        if (groups.size() > MAX_GROUP_LINES) {
            lines.add("- ... ve " + (groups.size() - MAX_GROUP_LINES) + " grup daha");
        }
        return String.join("\n", lines);
    }

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
