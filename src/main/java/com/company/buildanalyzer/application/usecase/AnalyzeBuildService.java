package com.company.buildanalyzer.application.usecase;

import com.company.buildanalyzer.application.context.BuildContextBuilder;
import com.company.buildanalyzer.application.port.out.BuildSourcePort;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Orchestrates the build-analysis flow: pull the raw console log through the
 * outbound port, then distill it into a {@link BuildAnalysisContext}. It stays
 * unaware of where the log comes from (port) and returns a domain model, not a
 * transport DTO.
 */
@Service
@RequiredArgsConstructor
public class AnalyzeBuildService implements AnalyzeBuildUseCase {

    private final BuildSourcePort buildSourcePort;
    private final BuildContextBuilder buildContextBuilder;

    @Override
    public BuildAnalysisContext analyze(String jobName, int buildNumber) {
        String rawLog = buildSourcePort.fetchConsoleLog(jobName, buildNumber);
        return buildContextBuilder.build(rawLog);
    }
}
