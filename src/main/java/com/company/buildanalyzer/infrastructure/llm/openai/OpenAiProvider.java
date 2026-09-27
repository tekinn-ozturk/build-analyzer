package com.company.buildanalyzer.infrastructure.llm.openai;

import com.company.buildanalyzer.application.port.out.LlmAnalysisException;
import com.company.buildanalyzer.application.port.out.LlmCompletion;
import com.company.buildanalyzer.application.port.out.LlmProvider;
import com.company.buildanalyzer.application.port.out.LlmRequest;
import com.company.buildanalyzer.infrastructure.llm.openai.OpenAiResponsesResponse.Usage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.net.http.HttpClient;

/**
 * OpenAI-backed implementation of {@link LlmProvider}, using only the <b>Responses API</b>
 * ({@code POST /responses}). This is the only class that knows the OpenAI wire format and
 * the API key; transport errors are translated into {@link LlmAnalysisException} so no
 * HTTP-client type leaks out — in particular no {@link RestClientResponseException}, which
 * the API layer maps to Jenkins errors.
 */
@Slf4j
public class OpenAiProvider implements LlmProvider {

    public static final String NAME = "OPENAI";

    private static final String RESPONSES_PATH = "/responses";
    private static final String MAX_OUTPUT_TOKENS_REASON = "max_output_tokens";
    private static final int MAX_ERROR_BODY_CHARS = 500;

    private final RestClient restClient;
    private final OpenAiProperties properties;

    public OpenAiProvider(OpenAiProperties properties) {
        this.properties = properties;

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build());
        requestFactory.setReadTimeout(properties.timeout());

        this.restClient = RestClient.builder()
                .baseUrl(properties.getUrl())
                .requestFactory(requestFactory)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getApiKey())
                .build();

        log.info("OpenAI Responses API client configured: url={}, model={}, timeout={}, maxOutputTokens={}, reasoningEffort={}, verbosity={}",
                properties.getUrl(), properties.getModel(), properties.timeout(),
                properties.getMaxOutputTokens(), properties.getReasoningEffort(), properties.getVerbosity());
    }

    @Override
    public LlmCompletion complete(LlmRequest llmRequest) {
        OpenAiResponsesRequest request = OpenAiResponsesRequest.of(properties.getModel(), llmRequest.prompt(),
                properties.getMaxOutputTokens(), properties.getReasoningEffort(), properties.getVerbosity(),
                llmRequest.responseSchema());

        OpenAiResponsesResponse response;
        try {
            response = restClient.post()
                    .uri(RESPONSES_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve()
                    .body(OpenAiResponsesResponse.class);
        } catch (RestClientResponseException ex) {
            throw new LlmAnalysisException("OpenAI returned HTTP %d for model '%s': %s".formatted(
                    ex.getStatusCode().value(), properties.getModel(), abbreviate(ex.getResponseBodyAsString())), ex);
        } catch (ResourceAccessException ex) {
            throw new LlmAnalysisException("OpenAI request to %s failed (unreachable or no answer within %s): %s".formatted(
                    properties.getUrl(), properties.timeout(), describe(ex.getMostSpecificCause())), ex);
        } catch (RestClientException ex) {
            throw new LlmAnalysisException("OpenAI reply could not be read: " + describe(ex.getMostSpecificCause()), ex);
        }

        if (response == null) {
            throw new LlmAnalysisException("OpenAI returned an empty reply for model '%s'".formatted(properties.getModel()));
        }
        if (OpenAiResponsesResponse.STATUS_FAILED.equals(response.status())) {
            String reason = response.error() == null ? "no details" : response.error().code() + ": " + response.error().message();
            throw new LlmAnalysisException("OpenAI response failed for model '%s': %s".formatted(properties.getModel(), reason));
        }

        String answer = response.outputText();
        if (!StringUtils.hasText(answer)) {
            throw new LlmAnalysisException(emptyAnswerReason(response));
        }
        if (stoppedAtTokenLimit(response)) {
            log.warn("OpenAI stopped at max_output_tokens={}; the analysis may be cut off", properties.getMaxOutputTokens());
        }

        Usage usage = response.usage() == null ? new Usage(null, null, null) : response.usage();
        return new LlmCompletion(
                answer.strip(),
                NAME,
                StringUtils.hasText(response.model()) ? response.model() : properties.getModel(),
                usage.inputTokens(),
                usage.outputTokens(),
                usage.totalTokens(),
                properties.tokenPricing().estimateUsd(usage.inputTokens(), usage.outputTokens()));
    }

    private String emptyAnswerReason(OpenAiResponsesResponse response) {
        if (StringUtils.hasText(response.refusal())) {
            return "OpenAI refused to answer: " + response.refusal();
        }
        if (stoppedAtTokenLimit(response)) {
            return "OpenAI used all %d output tokens without producing an answer (raise OPENAI_MAX_OUTPUT_TOKENS)"
                    .formatted(properties.getMaxOutputTokens());
        }
        return "OpenAI returned an empty analysis for model '%s' (status=%s)".formatted(properties.getModel(), response.status());
    }

    private static boolean stoppedAtTokenLimit(OpenAiResponsesResponse response) {
        return OpenAiResponsesResponse.STATUS_INCOMPLETE.equals(response.status())
                && response.incompleteDetails() != null
                && MAX_OUTPUT_TOKENS_REASON.equals(response.incompleteDetails().reason());
    }

    private static String describe(Throwable cause) {
        String message = cause.getMessage();
        return StringUtils.hasText(message) ? message : cause.getClass().getSimpleName();
    }

    private static String abbreviate(String text) {
        if (!StringUtils.hasText(text)) {
            return "<empty body>";
        }
        String stripped = text.strip();
        return stripped.length() <= MAX_ERROR_BODY_CHARS
                ? stripped
                : stripped.substring(0, MAX_ERROR_BODY_CHARS) + "...";
    }
}
