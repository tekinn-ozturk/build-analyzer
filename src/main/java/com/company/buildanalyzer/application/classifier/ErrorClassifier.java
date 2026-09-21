package com.company.buildanalyzer.application.classifier;

import com.company.buildanalyzer.domain.model.ErrorCategory;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Rule-based classifier that maps a build failure to an {@link ErrorCategory}.
 * <p>
 * Instead of a long if-else chain, classification is driven by an ordered list
 * of {@link Rule}s. Each rule owns a set of keyword patterns; the first rule
 * whose pattern is found in {@code exceptionType + rawLog} wins. Order encodes
 * priority: specific/root-cause signals (a Selenium exception, an infra error)
 * are checked before generic ones (a bare "BUILD FAILURE"), because almost every
 * failed build ends with "BUILD FAILURE".
 */
@Service
public class ErrorClassifier {

    private record Rule(ErrorCategory category, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
            rule(ErrorCategory.SELENIUM,
                    "NoSuchElementException", "TimeoutException", "SessionNotCreatedException",
                    "StaleElementReferenceException", "ElementClickInterceptedException",
                    "WebDriverException", "org.openqa.selenium"),
            rule(ErrorCategory.INFRA,
                    "Connection refused", "Connection timed out", "ConnectException", "UnknownHostException"),
            rule(ErrorCategory.JENKINS,
                    "Agent offline", "No node available", "node is offline"),
            rule(ErrorCategory.MAVEN,
                    "DependencyResolutionException", "Could not resolve dependencies", "ArtifactResolutionException"),
            rule(ErrorCategory.CUCUMBER,
                    "Failed scenarios", "Undefined step"),
            rule(ErrorCategory.MAVEN,
                    "BUILD FAILURE")
    );

    public ErrorCategory classify(String rawLog, String exceptionType) {
        String haystack = safe(exceptionType) + "\n" + safe(rawLog);
        if (haystack.isBlank()) {
            return ErrorCategory.UNKNOWN;
        }
        return RULES.stream()
                .filter(rule -> rule.pattern().matcher(haystack).find())
                .map(Rule::category)
                .findFirst()
                .orElse(ErrorCategory.UNKNOWN);
    }

    private static Rule rule(ErrorCategory category, String... keywords) {
        String regex = Arrays.stream(keywords)
                .map(Pattern::quote)
                .reduce((a, b) -> a + "|" + b)
                .orElseThrow(() -> new IllegalArgumentException("a rule needs at least one keyword"));
        return new Rule(category, Pattern.compile(regex, Pattern.CASE_INSENSITIVE));
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
