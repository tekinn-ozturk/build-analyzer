package com.company.buildanalyzer.domain.model;

/**
 * The UI-automation action that failed: "a command against a target". The shape is
 * shared by every UI test tool, so it stays tool-neutral:
 * <ul>
 *   <li>Selenium:   {@code findElement} + {@code name} / {@code q}</li>
 *   <li>Playwright: {@code toBeVisible} + {@code getByRole} / {@code 'button', { name: 'Submit' }}</li>
 *   <li>Cypress:    {@code get} + {@code css} / {@code #submit}</li>
 * </ul>
 *
 * @param tool         automation tool that reported it, e.g. {@code Selenium}
 * @param command      the failing command, e.g. {@code findElement}; {@code null} if unknown
 * @param locatorType  locator strategy, e.g. {@code name}, {@code css selector}; {@code null} if unknown
 * @param locatorValue locator value, e.g. {@code qqqqqqqq}; {@code null} if unknown
 */
public record FailedInteraction(
        String tool,
        String command,
        String locatorType,
        String locatorValue
) {
}
