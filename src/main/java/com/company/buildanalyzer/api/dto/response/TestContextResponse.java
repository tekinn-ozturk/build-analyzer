package com.company.buildanalyzer.api.dto.response;

/**
 * Where a test failed — present only when the failure comes from a test (any framework), {@code null}
 * for Maven / Jenkins / pipeline / infrastructure failures. The frontend decides its view on this alone.
 * Every field may be {@code null}.
 *
 * @param scenario    failing scenario, e.g. {@code Open Google}
 * @param step        failing step as written in the feature file, e.g. {@code Arama kutusuna tıkla.}
 * @param featureFile feature file, e.g. {@code src/test/resources/features/Example.feature}
 * @param featureLine line of the failing step in that file
 */
public record TestContextResponse(
        String scenario,
        String step,
        String featureFile,
        Integer featureLine
) {
}
