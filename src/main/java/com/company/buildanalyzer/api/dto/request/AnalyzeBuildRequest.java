package com.company.buildanalyzer.api.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Request for POST /api/v1/analysis/build.
 *
 * @param buildUrl Jenkins build URL, e.g. {@code https://jenkins.company.com/job/Team/job/UI-Test/125/}
 */
public record AnalyzeBuildRequest(

        @NotBlank(message = "buildUrl must not be blank")
        String buildUrl
) {
}
