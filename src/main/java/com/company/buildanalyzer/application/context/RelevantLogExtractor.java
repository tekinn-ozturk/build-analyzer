package com.company.buildanalyzer.application.context;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Cuts a console log down to the lines that matter for a root-cause analysis, so
 * the LLM gets a small, dense snippet instead of the raw tail.
 * <p>
 * The rules are deliberately <b>technology-agnostic</b> (generic failure vocabulary,
 * stack-frame shapes, test-result summaries) so the same extractor works for Selenium,
 * Cypress, Playwright, Karate, JUnit, NUnit, pytest... Tool-specific detail such as
 * Selenium's {@code Driver info} / {@code Session ID} is not matched by its own regex:
 * it follows the exception line and is kept as that line's context.
 * <ol>
 *   <li><b>Noise</b> (known boilerplate: dependency downloads, progress bars, resource
 *       copy/compile, git checkout, {@code [Pipeline]} markers, Maven help footer) is
 *       always dropped — even if it contains a signal word.</li>
 *   <li><b>Signal</b> lines (failure vocabulary, stack frames) are kept, plus
 *       {@value #CONTEXT_BEFORE} lines before and up to {@value #CONTEXT_AFTER} lines after
 *       (until a blank line). Warning-level lines are never signals on their own.</li>
 *   <li><b>Marker</b> lines (scenario/feature, test-run summary, final verdict) are kept
 *       alone, without context.</li>
 *   <li>Lines already seen — or already sent to the LLM elsewhere, e.g. in the
 *       extracted stack trace — are dropped; log-level prefixes are ignored when comparing.</li>
 *   <li>The result is capped at {@value #MAX_LINES} lines (head of the first failure +
 *       tail with the final verdict); gaps are marked with {@value #GAP}.</li>
 *   <li>If no signal exists at all, the last {@value #FALLBACK_TAIL_LINES} non-noise
 *       lines are returned, so the log is never dropped entirely.</li>
 * </ol>
 */
@Service
public class RelevantLogExtractor {

    static final int CONTEXT_BEFORE = 2;
    static final int CONTEXT_AFTER = 12;
    static final int MAX_LINES = 120;
    static final int FALLBACK_TAIL_LINES = 40;
    static final int MAX_LINE_LENGTH = 300;
    static final String GAP = "...";

    /** Always dropped; checked before the signal rules. */
    private static final List<Pattern> NOISE = patterns(
            "^\\s*(\\[\\w+]\\s*)?(Downloading|Downloaded|Uploading|Uploaded) (from|to)\\b",
            "^\\s*Progress \\(\\d+\\)",
            "^\\s*(\\[\\w+]\\s*)?(Copying|Compiling) \\d+ ",
            "^\\s*(\\[\\w+]\\s*)?(skip non existing resourceDirectory|Recompiling the module|Nothing to compile)",
            "^\\s*>\\s*git(\\.exe)?\\s",
            "^\\s*(Fetching (changes|upstream)|Checking out Revision|Obtained Jenkinsfile|Selected Git installation|The recommended git tool|No credentials specified|Commit message:)",
            "^\\s*\\[Pipeline]",
            "^\\s*\\[(INFO|ERROR|WARNING|WARN|DEBUG)]\\s*[-=]*\\s*$",   // prefix-only lines and separators
            "Re-run Maven using the -X switch|re-run Maven with the -e switch|\\[Help \\d+]|For more information about the errors and possible solutions|See dump files \\(if any exist\\)|for the individual test results"
    );

    /** Technology-neutral failure vocabulary and stack-frame shapes; kept together with their context. */
    private static final List<Pattern> SIGNALS = patterns(
            "(error|exception)s?\\b",                                   // also CamelCase: NoSuchElementException, AssertionError
            "\\b(fail(s|ed|ing|ures?)?|fatal|panic|traceback|unstable|aborted|crash(ed)?)\\b",
            "caused by|assert|expected|but was|timed? ?out|not found|refused|denied|unable to|cannot|could not",
            "\\b(hata|başarısız)",                                       // Turkish test messages
            "^\\s*at\\s+\\S",                                            // Java / JS / C# frames
            "^\\s*\\.{3}\\s*\\d+\\s+more",
            "^\\s*File \".+\", line \\d+",                               // Python frames
            "[✘✖×❌]"
    );

    /** Orientation lines (what ran, final verdict); kept on their own, without context. */
    private static final List<Pattern> MARKERS = patterns(
            "^\\s*(scenario|scenario outline|scenario template|feature|senaryo)\\b[^:]*:",   // BDD identity
            "finished:\\s*\\w+|build (success|failure)|tests? run:|\\b\\d+\\s+(passed|failed|failing|skipped|broken)\\b"
    );

    /**
     * Warning-level lines never trigger inclusion on their own (a green build is full of
     * harmless "WARNING: Unable to ..." lines); they are still kept as context of a real signal.
     */
    private static final Pattern WARNING_LEVEL = Pattern.compile("^\\s*\\[?(WARN|WARNING)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * Indented lines right after a warning are its continuation (javac's
     * "  not setting ... cannot run on JDK 21") and inherit its "no trigger" status —
     * unless they carry a strong signal (an exception/error type, a failure word, a
     * stack frame, a failure symbol), which ends the warning block. Weak vocabulary
     * ("cannot", "unable to", "expected") alone does not.
     */
    private static final Pattern STRONG_SIGNAL = Pattern.compile(
            "(error|exception)s?\\b|\\bfail(s|ed|ing|ures?)?\\b|^\\s*at\\s+\\S|[✘✖×❌]",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern LEVEL_PREFIX = Pattern.compile("^\\[(INFO|ERROR|WARNING|WARN|DEBUG)]\\s*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * @param lines           the raw log lines
     * @param alreadyIncluded lines the LLM already receives elsewhere (e.g. the stack trace);
     *                        they are not repeated in the snippet
     * @return the relevant snippet, or an empty string for an empty log
     */
    public String extract(List<String> lines, Collection<String> alreadyIncluded) {
        if (lines == null || lines.isEmpty()) {
            return "";
        }
        boolean[] keep = select(lines);

        Set<String> seen = new HashSet<>();
        if (alreadyIncluded != null) {
            alreadyIncluded.forEach(line -> seen.add(normalize(clip(line.strip()))));
        }

        List<String> snippet = new ArrayList<>();
        boolean gap = false;
        for (int i = 0; i < lines.size(); i++) {
            if (!keep[i]) {
                gap = true;
                continue;
            }
            String line = clip(lines.get(i).strip());
            if (!seen.add(normalize(line))) {
                gap = true;               // duplicate: treat like a skipped line
                continue;
            }
            if (gap && !snippet.isEmpty()) {
                snippet.add(GAP);
            }
            snippet.add(line);
            gap = false;
        }
        return String.join(System.lineSeparator(), cap(snippet));
    }

    private boolean[] select(List<String> lines) {
        int size = lines.size();
        boolean[] noise = new boolean[size];
        boolean[] keep = new boolean[size];
        boolean anySignal = false;

        for (int i = 0; i < size; i++) {
            noise[i] = lines.get(i).isBlank() || matchesAny(NOISE, lines.get(i));
        }
        boolean inWarning = false;
        for (int i = 0; i < size; i++) {
            String line = lines.get(i);
            if (WARNING_LEVEL.matcher(line).find()) {
                inWarning = true;
                continue;
            }
            if (inWarning) {
                boolean continuation = !line.isEmpty() && Character.isWhitespace(line.charAt(0));
                if (continuation && !STRONG_SIGNAL.matcher(line).find()) {
                    continue;
                }
                inWarning = false;
            }
            if (noise[i]) {
                continue;
            }
            if (!matchesAny(SIGNALS, line)) {
                if (matchesAny(MARKERS, line)) {
                    keep[i] = true;   // orientation only: no context, and does not count as a signal
                }
                continue;
            }
            anySignal = true;
            keep[i] = true;
            for (int b = i - 1; b >= Math.max(0, i - CONTEXT_BEFORE) && !noise[b]; b--) {
                keep[b] = true;
            }
            for (int a = i + 1; a <= Math.min(size - 1, i + CONTEXT_AFTER) && !noise[a]; a++) {
                keep[a] = true;
            }
        }

        if (!anySignal) {             // unknown format: never drop the log entirely
            int kept = 0;
            for (int i = size - 1; i >= 0 && kept < FALLBACK_TAIL_LINES; i--) {
                if (!noise[i]) {
                    keep[i] = true;
                    kept++;
                }
            }
        }
        return keep;
    }

    /** Keeps the first failure (head) and the final verdict (tail) when the snippet is too long. */
    private static List<String> cap(List<String> snippet) {
        if (snippet.size() <= MAX_LINES) {
            return snippet;
        }
        int head = MAX_LINES / 3;
        int tail = MAX_LINES - head - 1;
        List<String> capped = new ArrayList<>(snippet.subList(0, head));
        capped.add("... (" + (snippet.size() - head - tail) + " lines omitted)");
        capped.addAll(snippet.subList(snippet.size() - tail, snippet.size()));
        return capped;
    }

    private static String normalize(String line) {
        return WHITESPACE.matcher(LEVEL_PREFIX.matcher(line).replaceFirst("")).replaceAll(" ").strip();
    }

    private static String clip(String line) {
        return line.length() <= MAX_LINE_LENGTH ? line : line.substring(0, MAX_LINE_LENGTH) + "…";
    }

    private static boolean matchesAny(List<Pattern> rules, String line) {
        for (Pattern rule : rules) {
            if (rule.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    private static List<Pattern> patterns(String... regexes) {
        return Arrays.stream(regexes)
                .map(regex -> Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE))
                .toList();
    }
}
