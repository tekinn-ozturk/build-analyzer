package com.company.buildanalyzer.application.usecase;

import com.company.buildanalyzer.application.analysis.RootCauseFormatter;
import com.company.buildanalyzer.application.analysis.RootCauseParser;
import com.company.buildanalyzer.application.analysis.RootCauseParser.GroupsAnswer;
import com.company.buildanalyzer.application.analysis.RootCauseSchema;
import com.company.buildanalyzer.application.context.BuildContextBuilder;
import com.company.buildanalyzer.application.port.out.BuildSourcePort;
import com.company.buildanalyzer.application.port.out.LlmAnalysisException;
import com.company.buildanalyzer.application.port.out.LlmCompletion;
import com.company.buildanalyzer.application.port.out.LlmProvider;
import com.company.buildanalyzer.application.port.out.LlmRequest;
import com.company.buildanalyzer.application.prompt.PromptBuilder;
import com.company.buildanalyzer.application.prompt.PromptBuilder.GroupPrompt;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
import com.company.buildanalyzer.domain.model.FailureGroupAnalysis.Status;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Orchestrates the build-analysis flow: pull the raw console log through the
 * source port, distill it into a {@link BuildAnalysisContext}, turn that into
 * a prompt, ask the model through the {@link LlmProvider} port for a JSON answer
 * ({@link RootCauseSchema}), parse it and render the short user-facing text. A SUCCESS
 * build short-circuits with a fixed message, so no prompt is built and the LLM is never
 * called. It knows only the ports — never where the log comes from or which provider/model
 * answers — and returns a domain model, not a transport DTO.
 * <p>
 * One build is one analysis, however many tests failed. With at most one failed test the single-failure
 * flow runs (one call, {@link RootCauseSchema#ROOT_CAUSE}). With several, the failures are already grouped
 * by the context builder and the groups are sent in as few calls as fit — normally one, never more than
 * the configured limit ({@link RootCauseSchema#FAILURE_GROUPS}). A failed call only loses its own groups:
 * they are marked {@link Status#FAILED} with the reason and the others are kept; only when no call gets
 * through does the analysis fail as a whole. Groups over the call limit are kept and marked {@link Status#SKIPPED}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyzeBuildService implements AnalyzeBuildUseCase {

    static final String SUCCESS_ANALYSIS_MESSAGE =
            "Build başarıyla tamamlandı. Analiz gerektiren bir hata tespit edilmedi.";
    static final String UNREADABLE_ANSWER_NOTE = "AI yanıtı okunamadı.";
    static final String NO_ANSWER_NOTE = "AI bu grup için yanıt vermedi.";
    static final String CALL_FAILED_NOTE = "AI çağrısı başarısız: ";
    static final String OTHER_EXCEPTION_NOTE =
            "AI, logdaki ilk exception'ı analiz etti; bu testin hatası prompt'a gönderilmedi.";

    private final BuildSourcePort buildSourcePort;
    private final BuildContextBuilder buildContextBuilder;
    private final PromptBuilder promptBuilder;
    private final LlmProvider llmProvider;
    private final RootCauseParser rootCauseParser;
    private final RootCauseFormatter rootCauseFormatter;

    /** The model's text and the cost/speed of one call. */
    private record Call(String text, LlmMetrics metrics) {
    }

    @Override
    public BuildAnalysisResult analyze(String jobName, int buildNumber) {
        String rawLog = buildSourcePort.fetchConsoleLog(jobName, buildNumber);
        BuildAnalysisContext context = buildContextBuilder.build(rawLog);

        // Nothing to diagnose: skip prompt building and the (paid) LLM call entirely.
        if (context.isSuccessful()) {
            log.info("Build succeeded, skipping AI analysis: job={}, build={}", jobName, buildNumber);
            return new BuildAnalysisResult(context, null, SUCCESS_ANALYSIS_MESSAGE, null, null, null, List.of());
        }
        return context.hasMultipleFailures()
                ? analyzeGroups(jobName, buildNumber, context)
                : analyzeSingleFailure(jobName, buildNumber, context);
    }

    private BuildAnalysisResult analyzeSingleFailure(String jobName, int buildNumber, BuildAnalysisContext context) {
        String prompt = promptBuilder.build(context);

        log.info("Requesting AI analysis: job={}, build={}, category={}, promptChars={}",
                jobName, buildNumber, context.errorCategory(), prompt.length());
        Call call = call(new LlmRequest(prompt, RootCauseSchema.ROOT_CAUSE));

        // A missing location is filled from the parser's evidence; an unparseable answer is shown as-is.
        RootCauseAnalysis analysis = rootCauseParser.parse(call.text())
                .map(parsed -> parsed.withFallbackLocation(context.failureLocation()))
                .orElse(null);
        String aiAnalysis = analysis == null ? call.text() : rootCauseFormatter.format(analysis);

        // The build's only failure group (if one was found) carries the same answer — when the prompt showed its
        // evidence: the prompt describes the log's first exception, which may be another one (e.g. a logged retry).
        List<FailureGroupAnalysis> groupAnalyses = context.failureGroups().stream()
                .map(group -> analysis == null
                        ? FailureGroupAnalysis.notAnalyzed(group.id(), Status.FAILED, UNREADABLE_ANSWER_NOTE)
                        : Objects.equals(group.representative().stackTrace(), context.stackTrace())
                        ? FailureGroupAnalysis.analyzed(group.id(), analysis.rootCause(), analysis.actions())
                        : FailureGroupAnalysis.notAnalyzed(group.id(), Status.FAILED, OTHER_EXCEPTION_NOTE))
                .toList();
        return new BuildAnalysisResult(context, prompt, aiAnalysis, analysis, call.metrics(), null, groupAnalyses);
    }

    private BuildAnalysisResult analyzeGroups(String jobName, int buildNumber, BuildAnalysisContext context) {
        List<FailureGroup> groups = context.failureGroups();
        List<GroupPrompt> prompts = promptBuilder.buildGroupPrompts(context);
        log.info("Requesting AI analysis of {} failed tests in {} groups with {} call(s): job={}, build={}, category={}",
                context.failureCount(), groups.size(), prompts.size(), jobName, buildNumber, context.errorCategory());

        Map<String, FailureGroupAnalysis> answers = new HashMap<>();
        List<LlmMetrics> calls = new ArrayList<>();
        String summary = null;
        String unreadableAnswer = null;
        boolean anyAnswer = false;
        LlmAnalysisException lastFailure = null;
        for (GroupPrompt prompt : prompts) {
            Call call;
            try {
                call = call(new LlmRequest(prompt.prompt(), RootCauseSchema.FAILURE_GROUPS));
            } catch (LlmAnalysisException ex) {
                log.warn("AI analysis call for groups {} failed: {}", ids(prompt.groups()), ex.getMessage());
                lastFailure = ex;
                prompt.groups().forEach(group -> answers.put(group.id(),
                        FailureGroupAnalysis.notAnalyzed(group.id(), Status.FAILED, CALL_FAILED_NOTE + ex.getMessage())));
                continue;
            }
            calls.add(call.metrics());

            Optional<GroupsAnswer> answer = rootCauseParser.parseGroups(call.text());
            if (answer.isEmpty()) {
                unreadableAnswer = unreadableAnswer != null ? unreadableAnswer : call.text();
                prompt.groups().forEach(group -> answers.put(group.id(),
                        FailureGroupAnalysis.notAnalyzed(group.id(), Status.FAILED, UNREADABLE_ANSWER_NOTE)));
                continue;
            }
            anyAnswer = true;
            summary = summary != null ? summary : answer.get().summary();
            prompt.groups().forEach(group -> answers.put(group.id(), answer.get().groups().getOrDefault(group.id(),
                    FailureGroupAnalysis.notAnalyzed(group.id(), Status.FAILED, NO_ANSWER_NOTE))));
        }
        if (calls.isEmpty()) {
            throw lastFailure;        // no call got through: fail like a single-failure analysis (nothing is stored)
        }

        String skippedNote = "AI çağrı sınırına (" + prompts.size() + " çağrı) ulaşıldığı için ayrıntılı analiz edilmedi.";
        List<FailureGroupAnalysis> groupAnalyses = groups.stream()
                .map(group -> answers.getOrDefault(group.id(),
                        FailureGroupAnalysis.notAnalyzed(group.id(), Status.SKIPPED, skippedNote)))
                .toList();
        String aiAnalysis = anyAnswer ? rootCauseFormatter.formatGroups(summary, groups, groupAnalyses) : unreadableAnswer;
        return new BuildAnalysisResult(context, joinPrompts(prompts), aiAnalysis, null, total(calls), summary, groupAnalyses);
    }

    private Call call(LlmRequest request) {
        long startedAt = System.nanoTime();
        LlmCompletion completion = llmProvider.complete(request);
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
        return new Call(completion.text(), metrics);
    }

    /**
     * All answered calls of one analysis as one: tokens, time and cost added up; a value unknown for any call is
     * unknown. A call that failed reports no usage, so it is not in the totals (OpenAI may still have billed it).
     */
    private static LlmMetrics total(List<LlmMetrics> calls) {
        LlmMetrics first = calls.get(0);
        if (calls.size() == 1) {
            return first;
        }
        BigDecimal cost = calls.stream().anyMatch(call -> call.estimatedCostUsd() == null) ? null
                : calls.stream().map(LlmMetrics::estimatedCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new LlmMetrics(first.provider(), first.model(),
                sum(calls, LlmMetrics::promptTokens), sum(calls, LlmMetrics::completionTokens), sum(calls, LlmMetrics::totalTokens),
                calls.stream().mapToLong(LlmMetrics::responseTimeMs).sum(), cost);
    }

    private static Integer sum(List<LlmMetrics> calls, Function<LlmMetrics, Integer> tokens) {
        return calls.stream().anyMatch(call -> tokens.apply(call) == null) ? null
                : calls.stream().mapToInt(tokens::apply).sum();
    }

    private static String joinPrompts(List<GroupPrompt> prompts) {
        if (prompts.size() == 1) {
            return prompts.get(0).prompt();
        }
        List<String> parts = new ArrayList<>();
        for (int i = 0; i < prompts.size(); i++) {
            parts.add("===== AI ÇAĞRISI " + (i + 1) + "/" + prompts.size() + " =====\n" + prompts.get(i).prompt());
        }
        return String.join("\n\n", parts);
    }

    private static String ids(List<FailureGroup> groups) {
        return groups.stream().map(FailureGroup::id).collect(Collectors.joining(","));
    }
}
