package com.company.buildanalyzer.application.prompt;

import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Limits for a build with several failed tests (the {@code analysis.*} block). The failure groups
 * normally go to the model in ONE call; only when they do not fit are they split into more calls —
 * never more than {@link #maxLlmCalls}, however many tests failed. Groups beyond that are still
 * listed (in the prompt's group index and in the result) but marked as not analysed.
 */
@Component
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "analysis")
public class AnalysisLimits {

    /** Target size of one prompt in characters; the group details are split into calls by it. */
    @Positive
    private int maxPromptChars = 60_000;

    /** Failure groups per call; keeps the answer (a cause and up to 3 actions per group) within the output limit. */
    @Positive
    private int maxGroupsPerCall = 6;

    /** LLM calls per build, at most. */
    @Positive
    private int maxLlmCalls = 3;
}
