package com.company.buildanalyzer.infrastructure.llm.openai;

import com.company.buildanalyzer.application.port.out.LlmResponseSchema;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Body of OpenAI's {@code POST /responses} (Responses API). Optional fields are
 * omitted from the JSON when {@code null}. GPT-5 models accept no custom
 * {@code temperature}, so none is sent. {@code store=false}: build logs are not
 * kept on OpenAI's side for later retrieval. A response schema becomes a strict
 * {@code text.format} of type {@code json_schema} (structured outputs), so the
 * answer is guaranteed to match it.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record OpenAiResponsesRequest(
        String model,
        String input,
        @JsonProperty("max_output_tokens") Integer maxOutputTokens,
        Reasoning reasoning,
        Text text,
        boolean store
) {

    /**
     * @param responseSchema JSON schema the answer must follow; {@code null} = free text
     */
    public static OpenAiResponsesRequest of(String model, String prompt, Integer maxOutputTokens,
                                            String reasoningEffort, String verbosity,
                                            LlmResponseSchema responseSchema) {
        String effort = blankToNull(reasoningEffort);
        String textVerbosity = blankToNull(verbosity);
        Format format = responseSchema == null ? null : Format.strictJsonSchema(responseSchema);
        return new OpenAiResponsesRequest(model, prompt, maxOutputTokens,
                effort == null ? null : new Reasoning(effort),
                textVerbosity == null && format == null ? null : new Text(textVerbosity, format),
                false);
    }

    /** {@code reasoning.effort}: minimal / low / medium / high. */
    public record Reasoning(String effort) {
    }

    /** {@code text.verbosity}: low / medium / high; {@code text.format}: structured output. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Text(String verbosity, Format format) {
    }

    /** {@code {"type":"json_schema","name":...,"schema":{...},"strict":true}} */
    public record Format(String type, String name, Map<String, Object> schema, boolean strict) {

        static Format strictJsonSchema(LlmResponseSchema responseSchema) {
            return new Format("json_schema", responseSchema.name(), responseSchema.schema(), true);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
