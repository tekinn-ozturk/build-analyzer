package com.company.buildanalyzer.domain.model;

/**
 * Coarse family of a build failure. First version only covers the most common
 * families a QA pipeline hits; anything unrecognized is {@link #UNKNOWN}.
 */
public enum ErrorCategory {
    SELENIUM,
    MAVEN,
    CUCUMBER,
    JENKINS,
    INFRA,
    UNKNOWN
}
