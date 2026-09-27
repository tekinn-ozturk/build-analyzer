package com.company.buildanalyzer.infrastructure.llm.openai;

import com.company.buildanalyzer.application.port.out.LlmAnalysisException;
import com.company.buildanalyzer.application.port.out.LlmCompletion;
import com.company.buildanalyzer.application.port.out.LlmRequest;
import com.company.buildanalyzer.application.analysis.RootCauseSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the adapter over real HTTP against an in-process stub of OpenAI's
 * {@code /v1/responses}, so the JSON wire format, output/usage mapping, cost
 * estimate and error translation are covered without a network or an API key.
 */
class OpenAiProviderTest {

    private static final String ANALYSIS = """
            KÖK NEDEN
            name=qqqqqqqq locator'ı sayfada yok.

            GÜVEN SEVİYESİ
            Yüksek""";

    private StubOpenAiServer server;

    @BeforeEach
    void startStubOpenAi() throws IOException {
        server = StubOpenAiServer.start();
    }

    @AfterEach
    void stopStubOpenAi() {
        server.stop();
    }

    @Test
    void sendsResponsesRequestWithBearerKeyAndMapsAnswerUsageAndCost() {
        server.replyBody = StubOpenAiServer.completedReply("gpt-5-mini-2025-08-07", ANALYSIS, 4000, 500, 4500);

        LlmCompletion completion = provider(server.properties()).complete("PROMPT_TEXT");

        assertThat(server.receivedPath.get()).isEqualTo("/v1/responses");
        assertThat(server.receivedAuthorization.get()).isEqualTo("Bearer test-key");
        assertThat(server.receivedBody.get()).isEqualTo("""
                {"model":"gpt-5-mini","input":"PROMPT_TEXT","reasoning":{"effort":"low"},\
                "text":{"verbosity":"low"},"store":false,"max_output_tokens":4000}""");

        assertThat(completion.text()).isEqualTo(ANALYSIS);
        assertThat(completion.provider()).isEqualTo("OPENAI");
        assertThat(completion.model()).isEqualTo("gpt-5-mini-2025-08-07");
        assertThat(completion.promptTokens()).isEqualTo(4000);
        assertThat(completion.completionTokens()).isEqualTo(500);
        assertThat(completion.totalTokens()).isEqualTo(4500);
        // 4000 × 0.25 / 1M + 500 × 2.00 / 1M = 0.001 + 0.001
        assertThat(completion.estimatedCostUsd()).isEqualByComparingTo("0.002");
    }

    @Test
    void aResponseSchemaBecomesAStrictJsonSchemaTextFormat() throws Exception {
        server.replyBody = StubOpenAiServer.completedReply("gpt-5-mini", "{\"rootCause\":\"x\"}", 1, 1, 2);

        provider(server.properties()).complete(new LlmRequest("P", RootCauseSchema.ROOT_CAUSE));

        JsonNode text = new ObjectMapper().readTree(server.receivedBody.get()).get("text");
        assertThat(text.get("verbosity").asText()).isEqualTo("low");
        JsonNode format = text.get("format");
        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("name").asText()).isEqualTo("root_cause_analysis");
        assertThat(format.get("strict").asBoolean()).isTrue();
        assertThat(format.get("schema").get("additionalProperties").asBoolean()).isFalse();
        assertThat(format.get("schema").get("required"))
                .extracting(JsonNode::asText)
                .containsExactlyInAnyOrder("rootCause", "file", "line", "method", "actions");
    }

    @Test
    void freeTextRequestsCarryNoFormat() {
        server.replyBody = StubOpenAiServer.completedReply("gpt-5-mini", "ok", 1, 1, 2);

        provider(server.properties()).complete("P");

        assertThat(server.receivedBody.get()).doesNotContain("format");
    }

    @Test
    void skipsTheReasoningItemAndJoinsAllOutputTextParts() {
        server.replyBody = """
                {"model":"gpt-5-mini","status":"completed","output":[
                  {"type":"reasoning","id":"rs_1","summary":[]},
                  {"type":"message","role":"assistant","content":[
                    {"type":"output_text","text":"KÖK NEDEN\\n","annotations":[]},
                    {"type":"output_text","text":"locator yanlış.","annotations":[]}]}],
                 "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}""";

        assertThat(provider(server.properties()).complete("P").text()).isEqualTo("KÖK NEDEN\nlocator yanlış.");
    }

    @Test
    void omitsUnsetOptionalParametersAndNeverSendsTemperature() {
        server.replyBody = StubOpenAiServer.completedReply("gpt-5-mini", "ok", 1, 1, 2);
        OpenAiProperties properties = server.properties();
        properties.setReasoningEffort("");
        properties.setVerbosity(null);

        provider(properties).complete("P");

        assertThat(server.receivedBody.get())
                .doesNotContain("reasoning")
                .doesNotContain("verbosity")
                .doesNotContain("temperature")
                .doesNotContain("messages");
    }

    @Test
    void missingUsageOrPricingLeavesMetricsNull() {
        server.replyBody = """
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""";
        OpenAiProperties properties = server.properties();
        properties.getPricing().setInputUsdPerMillion(null);

        LlmCompletion completion = provider(properties).complete("P");

        assertThat(completion.model()).as("falls back to the configured model").isEqualTo("gpt-5-mini");
        assertThat(completion.promptTokens()).isNull();
        assertThat(completion.totalTokens()).isNull();
        assertThat(completion.estimatedCostUsd()).isNull();
    }

    @Test
    void translatesNon2xxReplyIntoLlmAnalysisException() {
        server.replyStatus = 401;
        server.replyBody = "{\"error\":{\"message\":\"Incorrect API key provided\",\"type\":\"invalid_request_error\"}}";

        assertThatThrownBy(() -> provider(server.properties()).complete("P"))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessageContaining("HTTP 401")
                .hasMessageContaining("Incorrect API key");
    }

    @Test
    void reportsAFailedResponse() {
        server.replyBody = """
                {"status":"failed","output":[],"error":{"code":"server_error","message":"The model failed"}}""";

        assertThatThrownBy(() -> provider(server.properties()).complete("P"))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessageContaining("server_error")
                .hasMessageContaining("The model failed");
    }

    @Test
    void rejectsEmptyAnswer() {
        server.replyBody = StubOpenAiServer.completedReply("gpt-5-mini", "   ", 10, 0, 10);

        assertThatThrownBy(() -> provider(server.properties()).complete("P"))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessageContaining("empty analysis");
    }

    @Test
    void explainsWhenTheTokenBudgetWasUsedUpBeforeAnyAnswer() {
        server.replyBody = """
                {"status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"reasoning","summary":[]}],
                 "usage":{"input_tokens":10,"output_tokens":4000,"total_tokens":4010}}""";

        assertThatThrownBy(() -> provider(server.properties()).complete("P"))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessageContaining("OPENAI_MAX_OUTPUT_TOKENS");
    }

    @Test
    void returnsATruncatedAnswerWithItsUsage() {
        server.replyBody = """
                {"model":"gpt-5-mini","status":"incomplete","incomplete_details":{"reason":"max_output_tokens"},
                 "output":[{"type":"message","content":[{"type":"output_text","text":"KÖK NEDEN\\nlocator"}]}],
                 "usage":{"input_tokens":100,"output_tokens":4000,"total_tokens":4100}}""";

        LlmCompletion completion = provider(server.properties()).complete("P");

        assertThat(completion.text()).isEqualTo("KÖK NEDEN\nlocator");
        assertThat(completion.completionTokens()).isEqualTo(4000);
    }

    @Test
    void reportsRefusal() {
        server.replyBody = """
                {"status":"completed","output":[{"type":"message","content":[
                  {"type":"refusal","refusal":"I can't help with that."}]}]}""";

        assertThatThrownBy(() -> provider(server.properties()).complete("P"))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessageContaining("refused");
    }

    @Test
    void failsWhenOpenAiDoesNotAnswerInTime() {
        server.replyDelayMillis = 3_000;
        OpenAiProperties properties = server.properties();
        properties.setTimeoutSeconds(1);

        assertThatThrownBy(() -> provider(properties).complete("P"))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessageContaining("failed");
    }

    @Test
    void failsWhenOpenAiIsUnreachable() throws IOException {
        OpenAiProperties properties = server.properties();
        properties.setUrl("http://127.0.0.1:" + freePort() + "/v1");

        assertThatThrownBy(() -> provider(properties).complete("P"))
                .isInstanceOf(LlmAnalysisException.class)
                .hasMessageContaining("failed");
    }

    private OpenAiProvider provider(OpenAiProperties properties) {
        return new OpenAiProvider(properties);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
