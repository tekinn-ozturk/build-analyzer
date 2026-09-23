package com.company.buildanalyzer.api.controller;

import com.company.buildanalyzer.api.dto.request.AnalyzeBuildRequest;
import com.company.buildanalyzer.api.dto.response.AnalyzeBuildResponse;
import com.company.buildanalyzer.api.mapper.BuildAnalysisResponseMapper;
import com.company.buildanalyzer.application.prompt.PromptBuilder;
import com.company.buildanalyzer.application.usecase.AnalyzeBuildUseCase;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/analysis")
@RequiredArgsConstructor
public class BuildAnalysisController {

    private final AnalyzeBuildUseCase analyzeBuildUseCase;
    private final PromptBuilder promptBuilder;
    private final BuildAnalysisResponseMapper responseMapper;

    @PostMapping("/build")
    public AnalyzeBuildResponse analyzeBuild(@Valid @RequestBody AnalyzeBuildRequest request) {
        BuildAnalysisContext context = analyzeBuildUseCase.analyze(request.jobName(), request.buildNumber());
        String generatedPrompt = promptBuilder.build(context);
        return responseMapper.toResponse(context, generatedPrompt);
    }
}
