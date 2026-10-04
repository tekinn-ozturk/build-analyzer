package com.company.buildanalyzer.api.controller;

import com.company.buildanalyzer.api.dto.request.AnalyzeBuildRequest;
import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.api.mapper.BuildAnalysisResponseMapper;
import com.company.buildanalyzer.application.usecase.AnalyzeBuildUseCase;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.JenkinsBuildUrl;
import com.company.buildanalyzer.infrastructure.persistence.Analysis;
import com.company.buildanalyzer.infrastructure.persistence.AnalysisRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class BuildAnalysisController {

    private final AnalyzeBuildUseCase analyzeBuildUseCase;
    private final AnalysisRepository analysisRepository;
    private final BuildAnalysisResponseMapper responseMapper;

    /** Analyzes the build behind a Jenkins build URL and stores the result. */
    @PostMapping("/analysis/build")
    public AnalyzeBuildResponse analyzeBuild(@Valid @RequestBody AnalyzeBuildRequest request) {
        JenkinsBuildUrl build = parse(request.buildUrl());

        BuildAnalysisResult result = analyzeBuildUseCase.analyze(build.jobPath(), build.buildNumber());

        Analysis saved = analysisRepository.save(responseMapper.toEntity(build, result));
        return responseMapper.toResponse(result, saved);
    }

    /** The analysis history, newest first (list columns only, no logs). */
    @GetMapping("/analyses")
    public List<AnalysisRepository.Summary> listAnalyses() {
        return analysisRepository.findAllByOrderByAnalyzedAtDesc();
    }

    /** One stored analysis; same shape as the POST response, without generatedPrompt (not stored). */
    @GetMapping("/analyses/{id}")
    public AnalyzeBuildResponse getAnalysis(@PathVariable Long id) {
        return analysisRepository.findById(id)
                .map(responseMapper::toResponse)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Analysis " + id + " not found"));
    }

    private static JenkinsBuildUrl parse(String buildUrl) {
        try {
            return JenkinsBuildUrl.parse(buildUrl);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
