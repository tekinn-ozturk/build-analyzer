package com.company.buildanalyzer.domain.model;

/**
 * Coarse family of a build failure. First version only covers the most common
 * families a QA pipeline hits; anything unrecognized is {@link #UNKNOWN}.
 * {@link #NONE} marks a successful build, for which no error analysis is done.
 */
public enum ErrorCategory {
    SELENIUM,
    MAVEN,
    CUCUMBER,
    JENKINS,
    INFRA,
    UNKNOWN,
    NONE
}
