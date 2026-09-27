package com.company.buildanalyzer.api.mapper;

import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.api.dto.response.RootCauseAnalysisResponse;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Maps the {@link BuildAnalysisResult} domain model to the transport DTO,
 * keeping domain types out of the API layer's public contract. The nested
 * domain value objects are flattened into plain response fields.
 */
@Component
public class BuildAnalysisResponseMapper {

    public AnalyzeBuildResponse toResponse(BuildAnalysisResult result) {
        BuildAnalysisContext context = result.context();
        Optional<FailedScenario> scenario = Optional.ofNullable(context.failedScenario());
        Optional<FailedStep> step = Optional.ofNullable(context.failedStep());
        Optional<FailureLocation> location = Optional.ofNullable(context.failureLocation());
        Optional<FailedInteraction> interaction = Optional.ofNullable(context.failedInteraction());
        Optional<LlmMetrics> metrics = Optional.ofNullable(result.llmMetrics());
        return new AnalyzeBuildResponse(
                context.buildStatus(),
                scenario.map(FailedScenario::name).orElse(null),
                scenario.map(FailedScenario::featureFile).orElse(null),
                scenario.map(FailedScenario::featureLine).orElse(null),
                step.map(FailedStep::text).orElse(null),
                step.map(FailedStep::featureLine).orElse(null),
                context.exceptionType(),
                context.errorCategory() == null ? null : context.errorCategory().name(),
                location.map(FailureLocation::file).orElse(null),
                location.map(FailureLocation::line).orElse(null),
                location.map(FailureLocation::stepDefinition).orElse(null),
                interaction.map(FailedInteraction::tool).orElse(null),
                interaction.map(FailedInteraction::command).orElse(null),
                interaction.map(FailedInteraction::locatorType).orElse(null),
                interaction.map(FailedInteraction::locatorValue).orElse(null),
                context.stackTrace(),
                context.relevantLogSnippet(),
                context.last200Lines(),
                result.generatedPrompt(),
                result.aiAnalysis(),
                Optional.ofNullable(result.rootCauseAnalysis()).map(BuildAnalysisResponseMapper::toResponse).orElse(null),
                metrics.map(LlmMetrics::provider).orElse(null),
                metrics.map(LlmMetrics::model).orElse(null),
                metrics.map(LlmMetrics::promptTokens).orElse(null),
                metrics.map(LlmMetrics::completionTokens).orElse(null),
                metrics.map(LlmMetrics::totalTokens).orElse(null),
                metrics.map(LlmMetrics::responseTimeMs).orElse(null),
                metrics.map(LlmMetrics::estimatedCostUsd).orElse(null)
        );
    }

    private static RootCauseAnalysisResponse toResponse(RootCauseAnalysis analysis) {
        return new RootCauseAnalysisResponse(
                analysis.rootCause(), analysis.file(), analysis.line(), analysis.method(), analysis.actions());
    }
}
