package com.company.buildanalyzer.application.usecase;

import com.company.buildanalyzer.application.analysis.RootCauseFormatter;
import com.company.buildanalyzer.application.analysis.RootCauseParser;
import com.company.buildanalyzer.application.analysis.RootCauseSchema;
import com.company.buildanalyzer.application.context.BuildContextBuilder;
import com.company.buildanalyzer.application.port.out.BuildSourcePort;
import com.company.buildanalyzer.application.port.out.LlmCompletion;
import com.company.buildanalyzer.application.port.out.LlmProvider;
import com.company.buildanalyzer.application.port.out.LlmRequest;
import com.company.buildanalyzer.application.prompt.PromptBuilder;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Orchestrates the build-analysis flow: pull the raw console log through the
 * source port, distill it into a {@link BuildAnalysisContext}, turn that into
 * a prompt, ask the model through the {@link LlmProvider} port for a JSON answer
 * ({@link RootCauseSchema}), parse it and render the short user-facing text. A SUCCESS
 * build short-circuits with a fixed message, so no prompt is built and the LLM is never
 * called. It knows only the ports — never where the log comes from or which provider/model
 * answers — and returns a domain model, not a transport DTO.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeBuildService implements AnalyzeBuildUseCase {

    static final String SUCCESS_ANALYSIS_MESSAGE =
            "Build başarıyla tamamlandı. Analiz gerektiren bir hata tespit edilmedi.";

    private final BuildSourcePort buildSourcePort;
    private final BuildContextBuilder buildContextBuilder;
    private final PromptBuilder promptBuilder;
    private final LlmProvider llmProvider;
    private final RootCauseParser rootCauseParser;
    private final RootCauseFormatter rootCauseFormatter;

    @Override
    public BuildAnalysisResult analyze(String jobName, int buildNumber) {
        String rawLog = buildSourcePort.fetchConsoleLog(jobName, buildNumber);
        BuildAnalysisContext context = buildContextBuilder.build(rawLog);

        // Nothing to diagnose: skip prompt building and the (paid) LLM call entirely.
        if (context.isSuccessful()) {
            log.info("Build succeeded, skipping AI analysis: job={}, build={}", jobName, buildNumber);
            return new BuildAnalysisResult(context, null, SUCCESS_ANALYSIS_MESSAGE, null, null);
        }

        String prompt = promptBuilder.build(context);

        log.info("Requesting AI analysis: job={}, build={}, category={}, promptChars={}",
                jobName, buildNumber, context.errorCategory(), prompt.length());
        long startedAt = System.nanoTime();
        LlmCompletion completion = llmProvider.complete(new LlmRequest(prompt, RootCauseSchema.ROOT_CAUSE));
        long responseTimeMs = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        LlmMetrics metrics = new LlmMetrics(
                completion.provider(),
                completion.model(),
                completion.promptTokens(),
                completion.completionTokens(),
                completion.totalTokens(),
                responseTimeMs,
                completion.estimatedCostUsd());
        log.info("AI analysis done: provider={}, model={}, tokens={}/{}, took={}ms, costUsd={}",
                metrics.provider(), metrics.model(), metrics.promptTokens(), metrics.completionTokens(),
                responseTimeMs, metrics.estimatedCostUsd());

        // A missing location is filled from the parser's evidence; an unparseable answer is shown as-is.
        RootCauseAnalysis analysis = rootCauseParser.parse(completion.text())
                .map(parsed -> parsed.withFallbackLocation(context.failureLocation()))
                .orElse(null);
        String aiAnalysis = analysis == null ? completion.text() : rootCauseFormatter.format(analysis);

        return new BuildAnalysisResult(context, prompt, aiAnalysis, analysis, metrics);
    }
}
