package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.application.context.ExceptionBlockExtractor.ExceptionBlock;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.TestFailure;
import com.company.buildanalyzer.domain.model.TestSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Distills a raw Jenkins console log into a small {@link BuildAnalysisContext}
 * for the LLM. Extraction is string/regex matching over well-known Jenkins /
 * Maven / BDD / stack-trace patterns and is best-effort — any field that cannot
 * be found is left {@code null}, never guessed.
 * <p>
 * The build status is evaluated first: a SUCCESS build gets {@link ErrorCategory#NONE}
 * and no error fields. Non-successful builds are parsed for the exception block
 * ({@link ExceptionBlockExtractor}), failing scenario ({@link ScenarioExtractor}) and step
 * ({@link StepExtractor}), the failed UI interaction (first matching tool-specific
 * {@link InteractionExtractor}), handed to the {@link ErrorClassifier}, and reduced to a
 * relevant log snippet for the LLM ({@link RelevantLogExtractor}). The raw tail (last 200
 * lines) is kept alongside as supporting context.
 * <p>
 * Beyond that primary failure, every failed test is collected ({@link FailureExtractor}) and grouped by
 * error signature ({@link FailureGrouper}), and the runner's test counts are read
 * ({@link TestSummaryExtractor}, also for a SUCCESS build). The build-level category stays one value;
 * the exception types of the single failures live in their groups.
 * <p>
 * The primary failure is the log's first exception — unless that exception belongs to no failed test (a retry
 * a passing scenario logged, a setup warning) while a failed test printed one: then it is the first failed test.
 */
@Service
@RequiredArgsConstructor
public class BuildContextBuilder {

    private static final int TAIL_LINE_COUNT = 200;

    private final ErrorClassifier errorClassifier;
    private final RelevantLogExtractor relevantLogExtractor;
    /** All tool-specific strategies (Selenium, ...); the first that recognises the exception wins. */
    private final List<InteractionExtractor> interactionExtractors;

    /** Jenkins pipeline epilogue: "Finished: FAILURE". */
    private static final Pattern JENKINS_STATUS = Pattern.compile("Finished:\\s*(\\w+)");
    /** Maven epilogue: "BUILD FAILURE" / "BUILD SUCCESS". */
    private static final Pattern MAVEN_STATUS = Pattern.compile("BUILD\\s+(SUCCESS|FAILURE|ERROR)");

    public BuildAnalysisContext build(String rawLog) {
        if (rawLog == null || rawLog.isBlank()) {
            return new BuildAnalysisContext(
                    "UNKNOWN", null, null, null, ErrorCategory.UNKNOWN, null, null, null, "", "", null, List.of());
        }

        List<String> lines = rawLog.lines().toList();
        String buildStatus = extractBuildStatus(rawLog);
        TestSummary testSummary = TestSummaryExtractor.extract(lines);

        // A successful build needs no error analysis: harmless warnings in its log
        // (e.g. Selenium's "Unable to find CDP implementation") must not be classified.
        if (BuildAnalysisContext.isSuccessStatus(buildStatus)) {
            return new BuildAnalysisContext(buildStatus, null, null, null, ErrorCategory.NONE, null, null, null,
                    null, extractLastLines(lines), testSummary, List.of());
        }

        ExceptionBlock exception = ExceptionBlockExtractor.extract(lines);
        List<TestFailure> failures = FailureExtractor.extract(lines, this::extractInteraction);
        List<FailureGroup> groups = FailureGrouper.group(failures);

        TestFailure failedTest = failedTestInsteadOf(exception, failures);
        if (failedTest != null) {
            // The log's first exception is none of the failed tests' own (e.g. a retry a passing scenario logged):
            // the primary failure is the first failed test's error instead.
            return new BuildAnalysisContext(
                    buildStatus,
                    failedTest.scenario(),
                    failedTest.step(),
                    failedTest.exceptionType(),
                    errorClassifier.classify(rawLog, failedTest.exceptionType()),
                    failedTest.stackTrace(),
                    failedTest.failureLocation(),
                    failedTest.failedInteraction(),
                    relevantLogExtractor.extract(lines, failedTest.stackTrace().lines().toList()),
                    extractLastLines(lines),
                    testSummary,
                    groups);
        }

        // The stack trace is its own prompt section; don't send its lines twice.
        List<String> stackTraceLines = exception.stackTrace() == null ? List.of() : exception.stackTrace().lines().toList();
        return new BuildAnalysisContext(
                buildStatus,
                ScenarioExtractor.extract(lines, exception.headerIndex()),
                StepExtractor.extract(lines, exception.headerIndex(), exception.stackTrace()),
                exception.exceptionType(),
                errorClassifier.classify(rawLog, exception.exceptionType()),
                exception.stackTrace(),
                exception.failureLocation(),
                extractInteraction(exception.stackTrace()),
                relevantLogExtractor.extract(lines, stackTraceLines),
                extractLastLines(lines),
                testSummary,
                groups
        );
    }

    /** The first failed test with an exception — when the log's first exception belongs to no failed test. */
    private static TestFailure failedTestInsteadOf(ExceptionBlock firstException, List<TestFailure> failures) {
        if (failures.stream().anyMatch(failure -> Objects.equals(failure.stackTrace(), firstException.stackTrace()))) {
            return null;
        }
        return failures.stream().filter(failure -> failure.stackTrace() != null).findFirst().orElse(null);
    }

    private FailedInteraction extractInteraction(String exceptionBlock) {
        if (exceptionBlock == null) {
            return null;
        }
        return interactionExtractors.stream()
                .map(extractor -> extractor.extract(exceptionBlock))
                .flatMap(Optional::stream)
                .findFirst()
                .orElse(null);
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

    private String extractLastLines(List<String> lines) {
        int from = Math.max(0, lines.size() - TAIL_LINE_COUNT);
        return String.join(System.lineSeparator(), lines.subList(from, lines.size()));
    }
}
