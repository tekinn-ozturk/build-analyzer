package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailureLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds the primary exception in a console log and cuts out its whole block:
 * <pre>
 * org.openqa.selenium.NoSuchElementException: no such element ...   ← header
 *   (Session info: chrome=153.0.8010.48)                             ← message lines
 * Driver info: ... / Command: ... / Session ID: ...                  ← (kept)
 * 	at org.openqa.selenium.remote.RemoteWebDriver.findElement(...)   ← frames
 * 	at stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)  ← first application frame
 * </pre>
 * The block ends at a blank line, at a new log record ({@code [INFO]}, {@code [ERROR]},
 * {@code [Pipeline]}, ...) or at the first non-frame line once frames have started.
 * The block is kept <b>complete</b>: every message line (Build info, Driver info, Command,
 * Capabilities, Session ID, ...) and every frame, unclipped — it is the primary evidence
 * for the LLM. Only the number of message lines is capped, as a guard against runaway output.
 */
final class ExceptionBlockExtractor {

    /** Result of the extraction; every field may be {@code null} and {@code headerIndex} -1 when no exception was found. */
    record ExceptionBlock(int headerIndex, String exceptionType, String stackTrace, FailureLocation failureLocation) {
        static final ExceptionBlock NONE = new ExceptionBlock(-1, null, null, null);
    }

    static final int MAX_MESSAGE_LINES = 30;

    /** A fully-qualified Java throwable, e.g. {@code org.openqa.selenium.NoSuchElementException}. */
    private static final Pattern EXCEPTION_TYPE =
            Pattern.compile("((?:[a-zA-Z_$][\\w$]*\\.)+[A-Z][\\w$]*(?:Exception|Error))");

    /** Any stack-trace line: a frame, "... N more", "Caused by:" or "Suppressed:". */
    private static final Pattern TRACE_LINE =
            Pattern.compile("^\\s*(at\\s+.+|\\.{3}\\s+\\d+\\s+more.*|Caused by:.*|Suppressed:.*)$");

    /** {@code at [module/]pkg.Class.method(Source)} */
    private static final Pattern FRAME = Pattern.compile("^\\s*at\\s+(?:[\\w.$-]+/)?([\\w.$<>]+)\\((.*)\\)\\s*$");

    /** {@code ExampleSteps.java:28} inside a frame's parentheses. */
    private static final Pattern SOURCE_AND_LINE = Pattern.compile("^([\\w$-]+\\.(?:java|kt|groovy|scala)):(\\d+)$");

    /** Cucumber pretty step line: {@code * Arama kutusuna tıkla. # stepdefinitions.ExampleSteps.clickSearchBox()}. */
    private static final Pattern STEP_DEFINITION_COMMENT = Pattern.compile("#\\s*([\\w$.]+)\\([^)]*\\)\\s*$");

    /** A new log record, which ends an exception block. */
    private static final Pattern LOG_RECORD =
            Pattern.compile("^\\s*\\[(INFO|ERROR|WARNING|WARN|DEBUG|Pipeline|CucumberReport)]");

    /** Frames from these packages are framework code, never the test project's own step. */
    private static final List<String> FRAMEWORK_PACKAGES = List.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.openqa.", "io.cucumber.", "cucumber.", "org.junit.", "junit.", "org.testng.",
            "org.apache.maven.", "org.springframework.", "net.bytebuddy.", "org.gradle.",
            "kotlin.", "groovy.", "org.codehaus.groovy.");

    private ExceptionBlockExtractor() {
    }

    static ExceptionBlock extract(List<String> lines) {
        int header = findHeader(lines);
        if (header < 0) {
            return ExceptionBlock.NONE;
        }
        Matcher type = EXCEPTION_TYPE.matcher(lines.get(header));
        String exceptionType = type.find() ? type.group(1) : null;

        List<String> block = new ArrayList<>();
        block.add(lines.get(header).strip());

        FailureLocation applicationFrame = null;
        boolean inFrames = false;
        int messageLines = 0;

        for (int i = header + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                break;
            }
            String stripped = line.strip();

            if (TRACE_LINE.matcher(line).matches()) {
                if (stripped.startsWith("Caused by:") || stripped.startsWith("Suppressed:")) {
                    inFrames = false;         // a nested exception may carry its own message lines
                    messageLines = 0;
                    block.add(stripped);
                    continue;
                }
                inFrames = true;
                if (applicationFrame == null) {
                    applicationFrame = parseApplicationFrame(line);
                }
                block.add(stripped);
                continue;
            }

            if (inFrames || LOG_RECORD.matcher(line).find() || ++messageLines > MAX_MESSAGE_LINES) {
                break;
            }
            block.add(stripped);
        }

        if (applicationFrame == null) {
            applicationFrame = stepDefinitionFromCucumberStep(lines, header);
        }
        return new ExceptionBlock(header, exceptionType, String.join(System.lineSeparator(), block), applicationFrame);
    }

    private static int findHeader(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            if (EXCEPTION_TYPE.matcher(lines.get(i)).find()) {
                return i;
            }
        }
        return -1;
    }

    /** @return the location if {@code line} is a frame of the test project's own code, else {@code null}. */
    private static FailureLocation parseApplicationFrame(String line) {
        Matcher frame = FRAME.matcher(line);
        if (!frame.matches()) {
            return null;
        }
        String method = frame.group(1);
        if (FRAMEWORK_PACKAGES.stream().anyMatch(method::startsWith)) {
            return null;
        }
        Matcher source = SOURCE_AND_LINE.matcher(frame.group(2).strip());
        if (!source.matches()) {
            return null;              // e.g. Cucumber's "?.step text(file:///...feature:6)" pseudo-frame
        }
        return new FailureLocation(method, source.group(1), Integer.valueOf(source.group(2)));
    }

    /**
     * Fallback when no application frame exists: Cucumber's pretty formatter prints the
     * failing step with its step-definition method right above the exception.
     */
    private static FailureLocation stepDefinitionFromCucumberStep(List<String> lines, int header) {
        if (header == 0) {
            return null;
        }
        Matcher step = STEP_DEFINITION_COMMENT.matcher(lines.get(header - 1));
        return step.find() ? new FailureLocation(step.group(1), null, null) : null;
    }
}
