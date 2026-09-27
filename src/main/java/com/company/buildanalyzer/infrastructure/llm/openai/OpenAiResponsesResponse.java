package com.company.buildanalyzer.infrastructure.llm.openai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The fields read from OpenAI's {@code /responses} reply; everything else is ignored.
 * {@code output} is a list of typed items: GPT-5 returns a {@code reasoning} item
 * (no readable text) followed by a {@code message} item whose {@code content} holds
 * {@code output_text} and/or {@code refusal} parts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OpenAiResponsesResponse(
        String model,
        String status,
        List<OutputItem> output,
        @JsonProperty("incomplete_details") IncompleteDetails incompleteDetails,
        Error error,
        Usage usage
) {

    static final String STATUS_INCOMPLETE = "incomplete";
    static final String STATUS_FAILED = "failed";

    /** Concatenated {@code output_text} of all message items; empty string if there is none. */
    public String outputText() {
        return contents("output_text", Content::text);
    }

    /** Concatenated {@code refusal} parts; empty string if the model did not refuse. */
    public String refusal() {
        return contents("refusal", Content::refusal);
    }

    private String contents(String type, Function<Content, String> value) {
        if (output == null) {
            return "";
        }
        return output.stream()
                .filter(item -> "message".equals(item.type()) && item.content() != null)
                .flatMap(item -> item.content().stream())
                .filter(content -> type.equals(content.type()))
                .map(value)
                .filter(Objects::nonNull)
                .collect(Collectors.joining());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record OutputItem(String type, List<Content> content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Content(String type, String text, String refusal) {
    }

    /** Why the answer stopped early, e.g. {@code max_output_tokens}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record IncompleteDetails(String reason) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Error(String code, String message) {
    }

    /** {@code output_tokens} includes the hidden reasoning tokens (billed as output). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Usage(
            @JsonProperty("input_tokens") Integer inputTokens,
            @JsonProperty("output_tokens") Integer outputTokens,
            @JsonProperty("total_tokens") Integer totalTokens
    ) {
    }
}
