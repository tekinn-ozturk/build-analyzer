package com.company.buildanalyzer.domain.model;

import java.util.List;

/**
 * The AI's answer for one {@link FailureGroup} — or why there is none, so a group the model never
 * saw is never shown as analysed.
 *
 * @param groupId   id of the group, e.g. {@code G1}
 * @param status    {@link Status#ANALYZED}, {@link Status#SKIPPED} or {@link Status#FAILED}
 * @param rootCause what happened, under which condition and on which evidence; {@code null} unless ANALYZED
 * @param actions   at most {@link RootCauseAnalysis#MAX_ACTIONS} prioritised actions; empty unless ANALYZED
 * @param note      why there is no answer (call limit, failed call, unusable answer); {@code null} when ANALYZED
 */
public record FailureGroupAnalysis(
        String groupId,
        Status status,
        String rootCause,
        List<String> actions,
        String note
) {

    public enum Status {
        /** The model answered for this group. */
        ANALYZED,
        /** Not sent to the model: beyond the configured number of LLM calls. */
        SKIPPED,
        /** Sent, but the call failed or the answer had nothing usable for this group. */
        FAILED
    }

    public FailureGroupAnalysis {
        actions = actions == null ? List.of() : List.copyOf(actions);
    }

    public static FailureGroupAnalysis analyzed(String groupId, String rootCause, List<String> actions) {
        return new FailureGroupAnalysis(groupId, Status.ANALYZED, rootCause, actions, null);
    }

    public static FailureGroupAnalysis notAnalyzed(String groupId, Status status, String note) {
        return new FailureGroupAnalysis(groupId, status, null, List.of(), note);
    }
}
