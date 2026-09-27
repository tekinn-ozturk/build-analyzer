package com.company.buildanalyzer.domain.model;

/**
 * The Cucumber scenario a failed build broke on.
 *
 * @param name        clean scenario name, e.g. {@code Open Google}
 * @param featureFile feature file path as printed by Cucumber, e.g.
 *                    {@code src/test/resources/features/Example.feature}; {@code null} if not printed
 * @param featureLine line of the scenario in the feature file; {@code null} if not printed
 */
public record FailedScenario(
        String name,
        String featureFile,
        Integer featureLine
) {
}
