package com.company.buildanalyzer.domain.model;

/**
 * Where in the test project's own code the failure surfaced: the first stack
 * frame that is not framework code (JDK, Selenium, Cucumber, JUnit, ...).
 *
 * @param stepDefinition fully-qualified method, e.g. {@code stepdefinitions.ExampleSteps.clickSearchBox}
 * @param file           source file name, e.g. {@code ExampleSteps.java}; {@code null} if unknown
 * @param line           line number in that file, e.g. {@code 28}; {@code null} if unknown
 */
public record FailureLocation(
        String stepDefinition,
        String file,
        Integer line
) {
}
