package com.company.buildanalyzer.infrastructure.llm.openai;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/** In-process stand-in for OpenAI's {@code /v1/responses}: records the request, returns a canned reply. */
final class StubOpenAiServer {

    final AtomicReference<String> receivedPath = new AtomicReference<>();
    final AtomicReference<String> receivedBody = new AtomicReference<>();
    final AtomicReference<String> receivedAuthorization = new AtomicReference<>();
    volatile int replyStatus = 200;
    volatile String replyBody = "{}";
    volatile long replyDelayMillis = 0;

    private final HttpServer server;

    private StubOpenAiServer(HttpServer server) {
        this.server = server;
    }

    static StubOpenAiServer start() throws IOException {
        StubOpenAiServer stub = new StubOpenAiServer(HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0));
        stub.server.createContext("/v1/", exchange -> {
            stub.receivedPath.set(exchange.getRequestURI().getPath());
            stub.receivedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            stub.receivedAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            sleep(stub.replyDelayMillis);
            byte[] bytes = stub.replyBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(stub.replyStatus, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        stub.server.start();
        return stub;
    }

    void stop() {
        server.stop(0);
    }

    /** Settings as in application.yml, pointed at this stub. */
    OpenAiProperties properties() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        properties.setApiKey("test-key");
        properties.setModel("gpt-5-mini");
        properties.setTimeoutSeconds(5);
        properties.setConnectTimeoutSeconds(2);
        properties.setMaxOutputTokens(4000);
        properties.setReasoningEffort("low");
        properties.setVerbosity("low");
        properties.getPricing().setInputUsdPerMillion(new BigDecimal("0.25"));
        properties.getPricing().setOutputUsdPerMillion(new BigDecimal("2.00"));
        return properties;
    }

    /** A realistic GPT-5 Responses API reply: a reasoning item, then the assistant message. */
    static String completedReply(String model, String text, int inputTokens, int outputTokens, int totalTokens) {
        return """
                {"id":"resp_1","object":"response","status":"completed","model":"%s",
                 "output":[
                   {"id":"rs_1","type":"reasoning","summary":[]},
                   {"id":"msg_1","type":"message","status":"completed","role":"assistant",
                    "content":[{"type":"output_text","text":%s,"annotations":[]}]}],
                 "incomplete_details":null,"error":null,
                 "usage":{"input_tokens":%d,"input_tokens_details":{"cached_tokens":0},
                          "output_tokens":%d,"output_tokens_details":{"reasoning_tokens":192},
                          "total_tokens":%d}}
                """.formatted(model, json(text), inputTokens, outputTokens, totalTokens);
    }

    private static String json(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
