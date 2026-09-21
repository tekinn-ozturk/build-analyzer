package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distills a raw Jenkins console log into a small {@link BuildAnalysisContext}
 * for the LLM. First-pass extraction is deliberately simple: string/regex
 * matching over well-known Jenkins / Maven / Cucumber / Java patterns. It is
 * best-effort — any field that cannot be found is left {@code null}. The error
 * family is inferred by the {@link ErrorClassifier}.
 */
@Service
@RequiredArgsConstructor
public class BuildContextBuilder {

    private static final int TAIL_LINE_COUNT = 500;

    private final ErrorClassifier errorClassifier;

    /** Jenkins pipeline epilogue: "Finished: FAILURE". */
    private static final Pattern JENKINS_STATUS = Pattern.compile("Finished:\\s*(\\w+)");
    /** Maven epilogue: "BUILD FAILURE" / "BUILD SUCCESS". */
    private static final Pattern MAVEN_STATUS = Pattern.compile("BUILD\\s+(SUCCESS|FAILURE|ERROR)");

    /** Fallback: any "Scenario: <name>" occurrence. */
    private static final Pattern ANY_SCENARIO = Pattern.compile("Scenario:\\s*(.+)");

    /** A fully-qualified Java throwable, optionally introduced by "Caused by:". */
    private static final Pattern EXCEPTION_TYPE =
            Pattern.compile("((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error))");

    private static final Pattern STACK_FRAME = Pattern.compile("^\\s*(at\\s+.+|\\.{3}\\s+\\d+\\s+more|Caused by:.*)$");

    public BuildAnalysisContext build(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return new BuildAnalysisContext("UNKNOWN", null, null, ErrorCategory.UNKNOWN, null, "");
        }

        List<String> lines = rawLog.lines().toList();
        String exceptionType = extractExceptionType(rawLog);

        return new BuildAnalysisContext(
                extractBuildStatus(rawLog),
                extractFailedScenario(lines),
                exceptionType,
                errorClassifier.classify(rawLog, exceptionType),
                extractStackTrace(lines),
                extractLastLines(lines)
        );
    }

    private String extractBuildStatus(String log) {
        Matcher jenkins = JENKINS_STATUS.matcher(log);
        String status = null;
        while (jenkins.find()) {          // keep the last occurrence (the final verdict)
            status = jenkins.group(1).toUpperCase();
        }
        if (status != null) {
            return status;
        }
        Matcher maven = MAVEN_STATUS.matcher(log);
        if (maven.find()) {
            return maven.group(1).toUpperCase();
        }
        return "UNKNOWN";
    }

    /**
     * Cucumber prints failures under a "Failed scenarios:" header, each as
     * {@code <feature>:<line> # [Scenario:] <name>}. Take the first such entry;
     * fall back to any "Scenario:" line if the header is absent.
     */
    private String extractFailedScenario(List<String> lines) {
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("Failed scenarios:")) {
                header = i;
                break;
            }
        }
        if (header >= 0) {
            for (int i = header + 1; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.isBlank()) {
                    continue;
                }
                int hash = line.indexOf('#');
                if (hash >= 0) {
                    return normalizeScenario(line.substring(hash + 1));
                }
                break; // the failure list has ended
            }
        }
        for (String line : lines) {
            Matcher any = ANY_SCENARIO.matcher(line);
            if (any.find()) {
                return normalizeScenario(any.group(1));
            }
        }
        return null;
    }

    private String normalizeScenario(String raw) {
        // The captured text may still carry a leading "Scenario:" label and CR.
        String value = raw.replaceFirst("^\\s*Scenario:\\s*", "").trim();
        return value.isBlank() ? null : value;
    }

    private String extractExceptionType(String log) {
        Matcher matcher = EXCEPTION_TYPE.matcher(log);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * Grabs the first exception header line and the contiguous stack-frame lines
     * that follow it ("at ...", "Caused by: ...", "... N more").
     */
    private String extractStackTrace(List<String> lines) {
        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (EXCEPTION_TYPE.matcher(lines.get(i)).find()) {
                start = i;
                break;
            }
        }
        if (start < 0) {
            return null;
        }

        StringBuilder trace = new StringBuilder(lines.get(start).strip());
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (STACK_FRAME.matcher(line).matches()) {
                trace.append(System.lineSeparator()).append(line.strip());
            } else if (!line.isBlank()) {
                break; // first non-frame, non-blank line ends the trace
            }
        }
        return trace.toString();
    }

    private String extractLastLines(List<String> lines) {
        int from = Math.max(0, lines.size() - TAIL_LINE_COUNT);
        return String.join(System.lineSeparator(), lines.subList(from, lines.size()));
    }
}
