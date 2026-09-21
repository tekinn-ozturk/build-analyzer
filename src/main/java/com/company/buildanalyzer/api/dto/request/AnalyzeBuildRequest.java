package com.company.buildanalyzer.api.dto.request;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request for POST /api/v1/analysis/build.
 */
public record AnalyzeBuildRequest(

        @NotBlank(message = "jobName must not be blank")
        String jobName,

        @NotNull(message = "buildNumber must not be null")
        @Min(value = 1, message = "buildNumber must be positive")
        Integer buildNumber
) {
}
