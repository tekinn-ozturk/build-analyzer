package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailedInteraction;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SeleniumInteractionExtractorTest {

    private final SeleniumInteractionExtractor extractor = new SeleniumInteractionExtractor();

    @Test
    void readsCommandAndLocatorFromTheCommandLine() {
        String block = """
                org.openqa.selenium.NoSuchElementException: no such element: Unable to locate element: {"method":"css selector","selector":"*[name='qqqqqqqq']"}
                Driver info: org.openqa.selenium.chrome.ChromeDriver
                Command: [b9cc324322adeb37cb93984b824b8efd, findElement {using=name, value=qqqqqqqq}]
                Session ID: b9cc324322adeb37cb93984b824b8efd
                """;

        assertThat(extractor.extract(block))
                .contains(new FailedInteraction("Selenium", "findElement", "name", "qqqqqqqq"));
    }

    @Test
    void keepsCommasInsideTheLocatorValue() {
        String block = "org.openqa.selenium.TimeoutException: x\n"
                + "Command: [s1, findElements {using=css selector, value=#a, .b > span}]";

        assertThat(extractor.extract(block))
                .contains(new FailedInteraction("Selenium", "findElements", "css selector", "#a, .b > span"));
    }

    @Test
    void commandWithoutLocatorKeepsOnlyTheCommand() {
        String block = "org.openqa.selenium.ElementClickInterceptedException: x\nCommand: [s1, clickElement {id=f.1}]";

        assertThat(extractor.extract(block)).contains(new FailedInteraction("Selenium", "clickElement", null, null));
    }

    @Test
    void fallsBackToTheLocatorInTheExceptionMessage() {
        String block = "org.openqa.selenium.NoSuchElementException: Unable to locate element: "
                + "{\"method\":\"css selector\",\"selector\":\"*[name='q']\"}";

        assertThat(extractor.extract(block)).contains(new FailedInteraction("Selenium", null, "css selector", "*[name='q']"));
    }

    @Test
    void ignoresBlocksFromOtherTools() {
        assertThat(extractor.extract("java.lang.AssertionError: expected 1\nCommand: [x, findElement {using=id, value=a}]"))
                .isEmpty();
        assertThat(extractor.extract(null)).isEmpty();
    }
}
