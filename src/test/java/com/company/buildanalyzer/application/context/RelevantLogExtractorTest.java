package com.company.buildanalyzer.application.context;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RelevantLogExtractorTest {

    private final RelevantLogExtractor extractor = new RelevantLogExtractor();

    private String extract(String log) {
        return extractor.extract(log.lines().toList(), List.of());
    }

    @Test
    void dropsBoilerplateEvenWhenItContainsSignalWords() {
        String log = """
                [Pipeline] { (Build and Test)
                 > git.exe fetch --tags --force --progress -- https://example.test/repo.git # timeout=10
                Downloading from central: https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.0/x.pom
                Progress (1): 3.6 kB
                [INFO] Compiling 2 source files with javac [debug target 21] to target\\test-classes
                [INFO] ------------------------------------------------------------------------
                [ERROR] Re-run Maven using the -X switch to enable full debug logging.
                [ERROR] -> [Help 1]
                java.lang.IllegalStateException: boom
                """;

        String snippet = extract(log);

        assertThat(snippet).isEqualTo("java.lang.IllegalStateException: boom");
    }

    @Test
    void keepsTheLinesFollowingASignalUntilABlankLine() {
        String log = """
                unrelated line
                org.openqa.selenium.NoSuchElementException: no such element
                Build info: version: '4.34.0'
                Driver info: org.openqa.selenium.chrome.ChromeDriver
                Command: [abc, findElement {using=name, value=q}]
                Session ID: abc

                trailing unrelated line
                """;

        assertThat(extract(log)).isEqualTo(String.join(System.lineSeparator(),
                "unrelated line",   // 1 line of context before the signal
                "org.openqa.selenium.NoSuchElementException: no such element",
                "Build info: version: '4.34.0'",
                "Driver info: org.openqa.selenium.chrome.ChromeDriver",
                "Command: [abc, findElement {using=name, value=q}]",
                "Session ID: abc"));
    }

    @Test
    void recognisesCamelCaseExceptionAndErrorTypesAsSignals() {
        String log = """
                chatter 1
                chatter 2
                chatter 3

                java.lang.IllegalStateException: boom

                chatter 4
                """;

        // a signal exists, so the tail fallback must not kick in
        assertThat(extract(log)).isEqualTo("java.lang.IllegalStateException: boom");
        assertThat(extract("x\n\nAssertionError: expected 1\n\ny")).isEqualTo("AssertionError: expected 1");
    }

    @Test
    void warningsDoNotTriggerInclusionOnTheirOwn() {
        String log = """
                WARNING: Unable to find CDP implementation matching 153
                x
                x
                x
                [ERROR] Tests run: 1, Errors: 1
                """;

        assertThat(extract(log)).doesNotContain("CDP").contains("[ERROR] Tests run: 1, Errors: 1");
    }

    @Test
    void indentedContinuationOfAWarningDoesNotTriggerButAStrongSignalStillDoes() {
        String javacWarning = """
                [WARNING] location of system modules is not set in conjunction with -source 21
                  not setting the location of system modules may lead to class files that cannot run on JDK 21
                    --release 21 is recommended instead of -source 21 -target 21
                [INFO] Copying 1 resource
                """;
        // real build #7 shape: a warning, then the (indented) failing steps and exception
        String cdpWarningThenFailure = """
                WARNING: Unable to find version of CDP to use for 153.0.8010.48.
                  * Google'a git.         # stepdefinitions.ExampleSteps.userOpensGoogle()
                  * Arama kutusuna tıkla. # stepdefinitions.ExampleSteps.clickSearchBox()
                      org.openqa.selenium.NoSuchElementException: no such element
                """;

        assertThat(extract(javacWarning + "java.lang.IllegalStateException: boom"))
                .isEqualTo("java.lang.IllegalStateException: boom");
        assertThat(extract(cdpWarningThenFailure))
                .contains("* Arama kutusuna tıkla.")
                .contains("org.openqa.selenium.NoSuchElementException: no such element");
    }

    @Test
    void markersAreKeptWithoutTheirContext() {
        String log = """
                Scenario: Open Google   # Example.feature:4
                SLF4J(W): No SLF4J providers were found.
                some chatter
                java.lang.AssertionError: expected true
                """;

        assertThat(extract(log)).isEqualTo(String.join(System.lineSeparator(),
                "Scenario: Open Google   # Example.feature:4",
                "SLF4J(W): No SLF4J providers were found.",     // context *before* the signal
                "some chatter",
                "java.lang.AssertionError: expected true"));
        assertThat(extract("Scenario: A  # a.feature:1\nchatter 1\nchatter 2\nchatter 3\nFinished: FAILURE"))
                .doesNotContain("chatter 1");
    }

    @Test
    void doesNotRepeatDuplicatesOrLinesAlreadySentElsewhere() {
        List<String> lines = List.of(
                "      org.openqa.selenium.NoSuchElementException: no such element",
                "      \tat stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)",
                "[ERROR] Tests run: 1, Errors: 1",
                "",
                "[ERROR]   no such element",
                "no such element",
                "\tat stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)");
        List<String> stackTrace = List.of(
                "org.openqa.selenium.NoSuchElementException: no such element",
                "at stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)");

        String snippet = extractor.extract(lines, stackTrace);

        assertThat(snippet).isEqualTo(String.join(System.lineSeparator(),
                "[ERROR] Tests run: 1, Errors: 1",
                RelevantLogExtractor.GAP,
                "[ERROR]   no such element"));
    }

    @Test
    void capsLongSnippetsKeepingHeadAndTail() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            lines.add("ERROR number " + i);
        }

        List<String> snippet = extractor.extract(lines, List.of()).lines().toList();

        assertThat(snippet).hasSize(RelevantLogExtractor.MAX_LINES);
        assertThat(snippet.get(0)).isEqualTo("ERROR number 0");
        assertThat(snippet).contains("... (181 lines omitted)");
        assertThat(snippet.get(snippet.size() - 1)).isEqualTo("ERROR number 299");
    }

    @Test
    void fallsBackToTheTailWhenNothingLooksLikeAFailure() {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            lines.add("plain line " + i);
        }
        lines.add("[Pipeline] End of Pipeline");

        List<String> snippet = extractor.extract(lines, List.of()).lines().toList();

        assertThat(snippet).hasSize(RelevantLogExtractor.FALLBACK_TAIL_LINES)
                .startsWith("plain line 61")
                .endsWith("plain line 100");
    }

    @Test
    void clipsVeryLongLinesAndHandlesEmptyInput() {
        String snippet = extract("Capabilities {" + "x".repeat(2_000) + "} caused by nothing");

        assertThat(snippet).hasSize(RelevantLogExtractor.MAX_LINE_LENGTH + 1).endsWith("…");
        assertThat(extractor.extract(List.of(), List.of())).isEmpty();
        assertThat(extractor.extract(null, null)).isEmpty();
    }

    /** The same rules must work for other stacks — no technology-specific regex involved. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("otherTechnologies")
    void worksAcrossTestTechnologies(String technology, String log, List<String> mustKeep, List<String> mustDrop) {
        String snippet = extract(log);

        assertThat(snippet).contains(mustKeep);
        mustDrop.forEach(dropped -> assertThat(snippet).doesNotContain(dropped));
    }

    static Stream<Arguments> otherTechnologies() {
        return Stream.of(
                Arguments.of("Cypress", """
                                npm WARN deprecated har-validator@5.1.5
                                Running:  login.cy.js
                                  Login
                                    1) should log in
                                  0 passing (4s)
                                  1 failing

                                  1) Login
                                       should log in:
                                     AssertionError: Timed out retrying after 4000ms: Expected to find element: `#submit`, but never found it.
                                      at Context.eval (webpack:///./cypress/e2e/login.cy.js:12:8)
                                """,
                        List.of("1 failing", "AssertionError: Timed out retrying", "at Context.eval (webpack:///./cypress/e2e/login.cy.js:12:8)"),
                        List.of("npm WARN deprecated")),
                Arguments.of("Playwright", """
                                Running 3 tests using 1 worker
                                  ✓  1 [chromium] › home.spec.ts:3:5 › has title (1.2s)
                                  ✘  2 [chromium] › login.spec.ts:8:5 › login works (5.0s)

                                  1) [chromium] › login.spec.ts:8:5 › login works
                                    Error: expect(locator).toBeVisible() failed
                                    Locator: getByRole('button', { name: 'Submit' })
                                    Expected: visible
                                    Received: <element(s) not found>

                                  1 failed
                                  2 passed (8.1s)
                                """,
                        List.of("✘  2 [chromium] › login.spec.ts:8:5 › login works", "Error: expect(locator).toBeVisible() failed",
                                "Locator: getByRole('button', { name: 'Submit' })", "1 failed", "2 passed (8.1s)"),
                        List.of()),   // the passing "✓" line right above the failure is kept as context
                Arguments.of("pytest", """
                                ============================= test session starts ==============================
                                collected 2 items
                                tests/test_login.py .F                                                   [100%]

                                    def test_login(driver):
                                >       assert driver.title == "Home"
                                E       AssertionError: assert 'Login' == 'Home'
                                tests/test_login.py:12: AssertionError
                                ========================= 1 failed, 1 passed in 3.21s ==========================
                                """,
                        List.of("assert driver.title == \"Home\"", "E       AssertionError: assert 'Login' == 'Home'",
                                "tests/test_login.py:12: AssertionError", "1 failed, 1 passed in 3.21s"),
                        List.of("collected 2 items")),
                Arguments.of("NUnit", """
                                Starting test execution, please wait...
                                  Failed LoginTests.ShouldLogin [2 s]
                                  Error Message:
                                   Expected: "Home"
                                  But was:  "Login"
                                  Stack Trace:
                                     at Tests.LoginTests.ShouldLogin() in C:\\src\\LoginTests.cs:line 21

                                Failed!  - Failed:     1, Passed:     4, Skipped:     0, Total:     5
                                """,
                        List.of("Failed LoginTests.ShouldLogin [2 s]", "Expected: \"Home\"", "But was:  \"Login\"",
                                "at Tests.LoginTests.ShouldLogin() in C:\\src\\LoginTests.cs:line 21", "Failed!  - Failed:     1"),
                        List.of()),
                Arguments.of("Karate", """
                                karate.env system property was: null
                                >> login.feature:10 - match failed: EQUALS
                                  $ | actual does not contain expected
                                scenarios:  3 | passed:  2 | failed:  1 | time: 2.1
                                """,
                        List.of(">> login.feature:10 - match failed: EQUALS", "actual does not contain expected",
                                "failed:  1"),
                        List.of())
        );
    }
}
