package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailedInteraction;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads Selenium's failing command and locator from a WebDriver exception block:
 * <ul>
 *   <li>primary: {@code Command: [<session>, findElement {using=name, value=qqqqqqqq}]}</li>
 *   <li>fallback (no Command line): the message's
 *       {@code {"method":"css selector","selector":"*[name='q']"}} — locator only</li>
 * </ul>
 * Only applies to blocks that come from Selenium ({@code org.openqa.selenium}).
 */
@Component
public class SeleniumInteractionExtractor implements InteractionExtractor {

    static final String TOOL = "Selenium";

    private static final String SELENIUM_PACKAGE = "org.openqa.selenium";

    /** {@code Command: [<session>, <command> {<params>}]} — params optional. */
    private static final Pattern COMMAND =
            Pattern.compile("Command:\\s*\\[[^,\\]]*,\\s*(\\w+)\\s*(?:\\{(.*)})?\\s*]");

    /** {@code using=<type>, value=<value>} — the value may itself contain commas (CSS selector lists). */
    private static final Pattern USING_VALUE = Pattern.compile("using=([^,]+),\\s*value=(.*)$");

    /** {@code {"method":"css selector","selector":"*[name='q']"}} in the exception message. */
    private static final Pattern MESSAGE_LOCATOR =
            Pattern.compile("\"method\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"selector\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Override
    public Optional<FailedInteraction> extract(String exceptionBlock) {
        if (exceptionBlock == null || !exceptionBlock.contains(SELENIUM_PACKAGE)) {
            return Optional.empty();
        }

        Matcher command = COMMAND.matcher(exceptionBlock);
        if (command.find()) {
            String name = command.group(1);
            String params = command.group(2);
            Matcher locator = params == null ? null : USING_VALUE.matcher(params.strip());
            if (locator != null && locator.find()) {
                return Optional.of(new FailedInteraction(TOOL, name, locator.group(1).strip(), locator.group(2).strip()));
            }
            return Optional.of(new FailedInteraction(TOOL, name, null, null));
        }

        Matcher fromMessage = MESSAGE_LOCATOR.matcher(exceptionBlock);
        if (fromMessage.find()) {
            return Optional.of(new FailedInteraction(TOOL, null, fromMessage.group(1), fromMessage.group(2)));
        }
        return Optional.empty();
    }
}
