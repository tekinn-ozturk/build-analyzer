package com.company.buildanalyzer.api.mapper;

import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.api.dto.response.RootCauseAnalysisResponse;
import com.company.buildanalyzer.api.dto.response.TestContextResponse;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.JenkinsBuildUrl;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import com.company.buildanalyzer.infrastructure.persistence.Analysis;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Maps the {@link BuildAnalysisResult} domain model to the transport DTO, keeping domain types out of
 * the API layer's public contract (the nested domain value objects are flattened into plain fields),
 * and converts between that DTO and the stored {@link Analysis} row.
 */
@Component
@RequiredArgsConstructor
public class BuildAnalysisResponseMapper {

    /** Not in result_json: the prompt is not stored; the logs and these header fields have their own columns. */
    private static final List<String> NOT_IN_RESULT_JSON = List.of(
            "id", "jobName", "buildNumber", "buildUrl", "analyzedAt",
            "generatedPrompt", "relevantLogSnippet", "last200Lines");

    private final ObjectMapper objectMapper;

    /** Response of a fresh analysis; {@code saved} supplies id, job and time ({@code null} before it is saved). */
    public AnalyzeBuildResponse toResponse(BuildAnalysisResult result, Analysis saved) {
        BuildAnalysisContext context = result.context();
        Optional<Analysis> stored = Optional.ofNullable(saved);
        Optional<FailedScenario> scenario = Optional.ofNullable(context.failedScenario());
        Optional<FailedStep> step = Optional.ofNullable(context.failedStep());
        Optional<FailureLocation> location = Optional.ofNullable(context.failureLocation());
        Optional<FailedInteraction> interaction = Optional.ofNullable(context.failedInteraction());
        Optional<LlmMetrics> metrics = Optional.ofNullable(result.llmMetrics());
        return new AnalyzeBuildResponse(
                stored.map(Analysis::getId).orElse(null),
                stored.map(Analysis::getJobPath).orElse(null),
                stored.map(Analysis::getBuildNumber).orElse(null),
                stored.map(Analysis::getBuildUrl).orElse(null),
                stored.map(Analysis::getAnalyzedAt).orElse(null),
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
                testContext(scenario, step),
                metrics.map(LlmMetrics::provider).orElse(null),
                metrics.map(LlmMetrics::model).orElse(null),
                metrics.map(LlmMetrics::promptTokens).orElse(null),
                metrics.map(LlmMetrics::completionTokens).orElse(null),
                metrics.map(LlmMetrics::totalTokens).orElse(null),
                metrics.map(LlmMetrics::responseTimeMs).orElse(null),
                metrics.map(LlmMetrics::estimatedCostUsd).orElse(null)
        );
    }

    /** A stored analysis in the same shape: result_json plus the columns kept outside it (no generatedPrompt). */
    public AnalyzeBuildResponse toResponse(Analysis analysis) {
        try {
            ObjectNode node = (ObjectNode) objectMapper.readTree(analysis.getResultJson());
            node.put("id", analysis.getId());
            node.put("jobName", analysis.getJobPath());
            node.put("buildNumber", analysis.getBuildNumber());
            node.put("buildUrl", analysis.getBuildUrl());
            node.put("analyzedAt", analysis.getAnalyzedAt().toString());
            node.put("relevantLogSnippet", analysis.getRelevantLogs());
            node.put("last200Lines", analysis.getLast200Lines());
            return objectMapper.treeToValue(node, AnalyzeBuildResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored result_json of analysis " + analysis.getId() + " is not readable", e);
        }
    }

    /** The row to store for a finished analysis of {@code build}. */
    public Analysis toEntity(JenkinsBuildUrl build, BuildAnalysisResult result) {
        AnalyzeBuildResponse response = toResponse(result, null);
        RootCauseAnalysisResponse rootCause = response.rootCauseAnalysis();

        Analysis analysis = new Analysis();
        analysis.setBuildUrl(build.url());
        analysis.setJobName(build.jobPath().substring(build.jobPath().lastIndexOf('/') + 1));
        analysis.setJobPath(build.jobPath());
        analysis.setBuildNumber(build.buildNumber());
        analysis.setBuildStatus(response.buildStatus());
        // SKIPPED: successful build, no LLM call. UNSTRUCTURED: the model's answer could not be parsed.
        analysis.setAnalysisStatus(result.llmMetrics() == null ? "SKIPPED" : rootCause == null ? "UNSTRUCTURED" : "COMPLETED");
        analysis.setErrorCategory(response.errorCategory());
        analysis.setExceptionType(response.exceptionType());
        analysis.setHeadline(headline(response));
        analysis.setErrorReason(rootCause != null ? rootCause.rootCause() : response.aiAnalysis());
        analysis.setResultJson(resultJson(response));
        analysis.setRelevantLogs(response.relevantLogSnippet());
        analysis.setLast200Lines(response.last200Lines());
        analysis.setModel(response.model());
        analysis.setTotalTokens(response.totalTokens());
        analysis.setEstimatedCostUsd(response.estimatedCostUsd());
        analysis.setAnalyzedAt(Instant.now());
        return analysis;
    }

    /** Test failures get a test context; Maven / Jenkins / infrastructure failures (no scenario, no step) do not. */
    private static TestContextResponse testContext(Optional<FailedScenario> scenario, Optional<FailedStep> step) {
        if (scenario.isEmpty() && step.isEmpty()) {
            return null;
        }
        return new TestContextResponse(
                scenario.map(FailedScenario::name).orElse(null),
                step.map(FailedStep::text).orElse(null),
                scenario.map(FailedScenario::featureFile).or(() -> step.map(FailedStep::featureFile)).orElse(null),
                step.map(FailedStep::featureLine).orElse(null));
    }

    /** Short label for the history list, e.g. "NoSuchElementException". */
    private static String headline(AnalyzeBuildResponse response) {
        if ("SUCCESS".equalsIgnoreCase(response.buildStatus())) {
            return "Build başarılı";
        }
        if (response.exceptionType() != null) {
            return response.exceptionType().substring(response.exceptionType().lastIndexOf('.') + 1);
        }
        return response.errorCategory();
    }

    private String resultJson(AnalyzeBuildResponse response) {
        ObjectNode node = objectMapper.valueToTree(response);
        node.remove(NOT_IN_RESULT_JSON);
        return node.toString();
    }

    private static RootCauseAnalysisResponse toResponse(RootCauseAnalysis analysis) {
        return new RootCauseAnalysisResponse(
                analysis.rootCause(), analysis.file(), analysis.line(), analysis.method(), analysis.actions());
    }
}
