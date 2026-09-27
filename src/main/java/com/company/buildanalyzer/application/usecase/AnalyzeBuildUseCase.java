package com.company.buildanalyzer.application.usecase;

import com.company.buildanalyzer.domain.model.BuildAnalysisResult;

/**
 * Inbound port: fetch a build's console log, distill it into a context, build
 * the LLM prompt and return the model's analysis (plus its usage metrics) alongside them.
 */
public interface AnalyzeBuildUseCase {

    BuildAnalysisResult analyze(String jobName, int buildNumber);
}
