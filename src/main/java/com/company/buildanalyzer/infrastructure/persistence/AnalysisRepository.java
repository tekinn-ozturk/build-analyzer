package com.company.buildanalyzer.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface AnalysisRepository extends JpaRepository<Analysis, Long> {

    /** One row of the history list. Only these columns are read: the logs and result_json stay in the database. */
    interface Summary {
        Long getId();
        String getJobName();
        String getJobPath();
        Integer getBuildNumber();
        String getBuildUrl();
        String getBuildStatus();
        String getAnalysisStatus();
        String getErrorCategory();
        String getHeadline();
        Instant getAnalyzedAt();
    }

    /** The history list, newest first. */
    List<Summary> findAllByOrderByAnalyzedAtDesc();
}
