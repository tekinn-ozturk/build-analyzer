package com.company.buildanalyzer.application.usecase;

import com.company.buildanalyzer.domain.model.BuildAnalysisContext;

/**
 * Inbound port: fetch a build's console log and distill it into a
 * {@link BuildAnalysisContext} (the payload later handed to the LLM).
 */
public interface AnalyzeBuildUseCase {

    BuildAnalysisContext analyze(String jobName, int buildNumber);
}
