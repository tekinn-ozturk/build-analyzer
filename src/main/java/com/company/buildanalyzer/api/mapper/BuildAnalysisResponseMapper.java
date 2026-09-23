package com.company.buildanalyzer.api.mapper;

import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import org.springframework.stereotype.Component;

/**
 * Maps the {@link BuildAnalysisContext} domain model to the transport DTO,
 * keeping the domain type out of the API layer's public contract.
 */
@Component
public class BuildAnalysisResponseMapper {

    public AnalyzeBuildResponse toResponse(BuildAnalysisContext context, String generatedPrompt) {
        return new AnalyzeBuildResponse(
                context.buildStatus(),
                context.failedScenario(),
                context.exceptionType(),
                context.errorCategory() == null ? null : context.errorCategory().name(),
                context.stackTrace(),
                context.last500Lines(),
                generatedPrompt
        );
    }
}
