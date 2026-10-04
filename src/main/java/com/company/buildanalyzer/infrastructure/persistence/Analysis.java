package com.company.buildanalyzer.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One analysed Jenkins build. The columns are what the history list needs; everything else
 * the detail screen shows (AI answer, suggestions, test context, evidence) is in {@link #resultJson}.
 */
@Entity
@Table(name = "analyses")
@Getter
@Setter
public class Analysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Jenkins
    private String buildUrl;
    private String jobName;
    private String jobPath;
    private Integer buildNumber;
    private String buildStatus;

    // Analysis
    private String analysisStatus;
    private String errorCategory;
    private String exceptionType;
    private String headline;

    // AI / result
    private String errorReason;
    /** The analysis response as JSON (Jackson), without the prompt and the logs; see BuildAnalysisResponseMapper. */
    private String resultJson;
    private String relevantLogs;
    @Column(name = "last_200_lines")
    private String last200Lines;

    // LLM metrics
    private String model;
    private Integer totalTokens;
    private BigDecimal estimatedCostUsd;

    private Instant createdAt = Instant.now();
    private Instant analyzedAt;
}
