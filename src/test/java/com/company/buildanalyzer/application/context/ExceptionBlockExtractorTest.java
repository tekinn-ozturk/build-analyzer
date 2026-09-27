package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.application.context.ExceptionBlockExtractor.ExceptionBlock;
import com.company.buildanalyzer.domain.model.FailureLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionBlockExtractorTest {

    @Test
    void keepsNestedCauseAndMoreMarkerAndStopsAtBlankLine() {
        List<String> lines = List.of(
                "org.openqa.selenium.SessionNotCreatedException: session not created",
                "\tat org.openqa.selenium.remote.ProtocolHandshake.createSession(ProtocolHandshake.java:130)",
                "Caused by: java.lang.IllegalStateException: driver/browser version mismatch",
                "\t... 15 more",
                "",
                "[INFO] BUILD FAILURE");

        ExceptionBlock block = ExceptionBlockExtractor.extract(lines);

        assertThat(block.headerIndex()).isZero();
        assertThat(block.exceptionType()).isEqualTo("org.openqa.selenium.SessionNotCreatedException");
        assertThat(block.stackTrace())
                .contains("Caused by: java.lang.IllegalStateException")
                .contains("... 15 more")
                .doesNotContain("BUILD FAILURE");
        assertThat(block.failureLocation()).as("only framework frames").isNull();
    }

    @Test
    void fallsBackToCucumberStepCommentWhenThereIsNoApplicationFrame() {
        List<String> lines = List.of(
                "  * Arama kutusuna tıkla. # stepdefinitions.ExampleSteps.clickSearchBox()",
                "      org.openqa.selenium.NoSuchElementException: no such element",
                "      \tat org.openqa.selenium.remote.RemoteWebDriver.findElement(RemoteWebDriver.java:361)");

        FailureLocation location = ExceptionBlockExtractor.extract(lines).failureLocation();

        assertThat(location).isEqualTo(new FailureLocation("stepdefinitions.ExampleSteps.clickSearchBox", null, null));
    }

    @Test
    void keepsTheFullStackTraceWithEveryFrameAndUnclippedLines() {
        String longMessage = "java.lang.IllegalStateException: " + "x".repeat(1_000);
        List<String> lines = new ArrayList<>();
        lines.add(longMessage);
        for (int i = 0; i < 60; i++) {
            lines.add("\tat org.openqa.selenium.Frame" + i + ".call(Frame" + i + ".java:" + i + ")");
        }
        lines.add("\tat com.acme.steps.LoginSteps.submit(LoginSteps.java:42)");
        lines.add("\tat org.junit.runners.ParentRunner.run(ParentRunner.java:413)");

        ExceptionBlock block = ExceptionBlockExtractor.extract(lines);

        assertThat(block.failureLocation())
                .isEqualTo(new FailureLocation("com.acme.steps.LoginSteps.submit", "LoginSteps.java", 42));
        assertThat(block.stackTrace().lines()).hasSize(63);
        assertThat(block.stackTrace())
                .startsWith(longMessage)
                .contains("at org.openqa.selenium.Frame45.call(Frame45.java:45)")
                .contains("at com.acme.steps.LoginSteps.submit(LoginSteps.java:42)")
                .endsWith("at org.junit.runners.ParentRunner.run(ParentRunner.java:413)")
                .doesNotContain("omitted")
                .doesNotContain("…");
    }

    @Test
    void keepsSeleniumBuildDriverCommandAndSessionInfo() {
        String capabilities = "Capabilities {acceptInsecureCerts: false, browserName: chrome, " + "k: v, ".repeat(150) + "}";
        List<String> lines = List.of(
                "      org.openqa.selenium.NoSuchElementException: no such element: Unable to locate element",
                "        (Session info: chrome=153.0.8010.48)",
                "      Build info: version: '4.34.0', revision: '707dcb4246*'",
                "      System info: os.name: 'Windows 10', os.arch: 'amd64'",
                "      Driver info: org.openqa.selenium.chrome.ChromeDriver",
                "      Command: [b9cc3243, findElement {using=name, value=qqqqqqqq}]",
                "      " + capabilities,
                "      Session ID: b9cc3243",
                "      \tat org.openqa.selenium.remote.RemoteWebDriver.findElement(RemoteWebDriver.java:361)",
                "      \tat stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)",
                "",
                "[INFO] Results:");

        String stackTrace = ExceptionBlockExtractor.extract(lines).stackTrace();

        assertThat(stackTrace.lines()).containsExactly(
                "org.openqa.selenium.NoSuchElementException: no such element: Unable to locate element",
                "(Session info: chrome=153.0.8010.48)",
                "Build info: version: '4.34.0', revision: '707dcb4246*'",
                "System info: os.name: 'Windows 10', os.arch: 'amd64'",
                "Driver info: org.openqa.selenium.chrome.ChromeDriver",
                "Command: [b9cc3243, findElement {using=name, value=qqqqqqqq}]",
                capabilities,
                "Session ID: b9cc3243",
                "at org.openqa.selenium.remote.RemoteWebDriver.findElement(RemoteWebDriver.java:361)",
                "at stepdefinitions.ExampleSteps.clickSearchBox(ExampleSteps.java:28)");
    }

    @Test
    void returnsNoneWhenTheLogHasNoException() {
        ExceptionBlock block = ExceptionBlockExtractor.extract(List.of("[INFO] BUILD FAILURE", "Finished: FAILURE"));

        assertThat(block).isEqualTo(ExceptionBlock.NONE);
    }
}
