package com.company.buildanalyzer.domain.model;

/**
 * The BDD (Gherkin) step that failed — Cucumber, Karate, SpecFlow, Behave all share this shape.
 *
 * @param text        step text exactly as in the feature file, without the Given/When/Then/* keyword,
 *                    e.g. {@code Arama kutusuna tıkla.}
 * @param featureFile feature file name, e.g. {@code Example.feature}; {@code null} if not printed
 * @param featureLine line of the step in the feature file, e.g. {@code 6}; {@code null} if not printed
 */
public record FailedStep(
        String text,
        String featureFile,
        Integer featureLine
) {
}
