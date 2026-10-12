package com.company.buildanalyzer.api.dto.response;

import java.util.List;

/**
 * Failed tests that share one error signature, with the AI's answer for them. The evidence fields
 * describe the group's representative (its first failed test); the full stack trace is kept only once.
 *
 * @param id            e.g. {@code G1}; groups are numbered largest first
 * @param signature     the grouping key: exception type, message (run-specific values masked) and failure location
 * @param failureCount  number of failed tests in the group
 * @param exceptionType fully-qualified exception type; {@code null} when the log shows none
 * @param message       first line of the exception message
 * @param exceptionFile source file of the first application stack frame
 * @param exceptionLine line in that file
 * @param stackTrace    the representative's full exception block
 * @param logExcerpt    lines printed right after the representative's exception (e.g. a response body)
 * @param aiStatus      {@code ANALYZED}; {@code SKIPPED} (over the AI call limit, not sent);
 *                      {@code FAILED} (the call failed or had no usable answer for this group)
 * @param rootCause     AI: what happened, under which condition, on which evidence; {@code null} unless ANALYZED
 * @param actions       AI: at most three prioritised actions; empty unless ANALYZED
 * @param aiNote        why there is no AI answer; {@code null} when ANALYZED
 * @param failures      every failed test of the group, in log order
 */
public record FailureGroupResponse(
        String id,
        String signature,
        Integer failureCount,
        String exceptionType,
        String message,
        String exceptionFile,
        Integer exceptionLine,
        String stackTrace,
        String logExcerpt,
        String aiStatus,
        String rootCause,
        List<String> actions,
        String aiNote,
        List<TestFailureResponse> failures
) {
}
