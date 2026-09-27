package com.company.buildanalyzer.infrastructure.llm.openai;

import com.company.buildanalyzer.application.analysis.RootCauseFormatter;
import com.company.buildanalyzer.application.analysis.RootCauseParser;
import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.application.context.BuildContextBuilder;
import com.company.buildanalyzer.application.context.RelevantLogExtractor;
import com.company.buildanalyzer.application.context.SeleniumInteractionExtractor;
import com.company.buildanalyzer.application.prompt.PromptBuilder;
import com.company.buildanalyzer.application.usecase.AnalyzeBuildService;
import com.company.buildanalyzer.domain.model.BuildAnalysisResult;
import com.company.buildanalyzer.domain.model.LlmMetrics;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Build #7 (real Selenium failure) through the whole pipeline — fixture log → context →
 * prompt → {@link OpenAiProvider} over HTTP (strict JSON schema) → parse → render → metrics —
 * with only OpenAI replaced by a stub.
 */
class Build7OpenAiEndToEndTest {

    /** What the model returns under the strict schema (string-escaped JSON inside output_text). */
    private static final String MODEL_JSON = """
            {"rootCause":"name=qqqqqqqq locator'ı bulunamadı. NoSuchElementException oluştu.",\
            "file":"ExampleSteps.java","line":28,"method":"clickSearchBox",\
            "actions":["ExampleSteps.java:28'deki name=qqqqqqqq locator'ını düzelt.",\
            "Arama kutusunun sayfadaki gerçek name değerini kontrol et."]}""";

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
    void build7IsAnalysedThroughTheResponsesApiIntoTheShortFormat() throws IOException {
        server.replyBody = StubOpenAiServer.completedReply("gpt-5-mini-2025-08-07", MODEL_JSON, 6_123, 812, 6_935);
        String build7Log = fixture("logs/build-7-selenium-failure.log");
        AnalyzeBuildService service = new AnalyzeBuildService(
                (job, build) -> build7Log,
                new BuildContextBuilder(new ErrorClassifier(), new RelevantLogExtractor(),
                        List.of(new SeleniumInteractionExtractor())),
                new PromptBuilder(),
                new OpenAiProvider(server.properties()),
                new RootCauseParser(),
                new RootCauseFormatter());

        BuildAnalysisResult result = service.analyze("Mini-UI-Automation", 7);

        // the Responses API received the build-7 prompt and the strict answer schema
        JsonNode request = new ObjectMapper().readTree(server.receivedBody.get());
        assertThat(server.receivedPath.get()).isEqualTo("/v1/responses");
        assertThat(request.get("input").asText()).isEqualTo(result.generatedPrompt());
        assertThat(request.get("store").asBoolean()).isFalse();
        assertThat(request.at("/text/format/type").asText()).isEqualTo("json_schema");
        assertThat(request.at("/text/format/strict").asBoolean()).isTrue();
        assertThat(result.generatedPrompt())
                .contains("- Hata Dosyası:Satırı: ExampleSteps.java:28")
                .contains("- Locator Değeri: qqqqqqqq");

        // parsed and rendered for a ten-second read
        assertThat(result.rootCauseAnalysis()).isEqualTo(new RootCauseAnalysis(
                "name=qqqqqqqq locator'ı bulunamadı. NoSuchElementException oluştu.",
                "ExampleSteps.java", 28, "clickSearchBox",
                List.of("ExampleSteps.java:28'deki name=qqqqqqqq locator'ını düzelt.",
                        "Arama kutusunun sayfadaki gerçek name değerini kontrol et.")));
        assertThat(result.aiAnalysis()).isEqualTo("""
                🚨 KÖK NEDEN
                name=qqqqqqqq locator'ı bulunamadı. NoSuchElementException oluştu.
                📍 KONUM
                ExampleSteps.java:28
                clickSearchBox()
                ✅ AKSİYON
                - ExampleSteps.java:28'deki name=qqqqqqqq locator'ını düzelt.
                - Arama kutusunun sayfadaki gerçek name değerini kontrol et.""");
        assertThat(result.aiAnalysis().lines()).hasSizeLessThanOrEqualTo(10);

        // metrics unchanged by the new output format
        LlmMetrics metrics = result.llmMetrics();
        assertThat(metrics.provider()).isEqualTo("OPENAI");
        assertThat(metrics.model()).isEqualTo("gpt-5-mini-2025-08-07");
        assertThat(metrics.promptTokens()).isEqualTo(6_123);
        assertThat(metrics.completionTokens()).isEqualTo(812);
        assertThat(metrics.totalTokens()).isEqualTo(6_935);
        // 6123 × 0.25 / 1M = 0.00153075 ; 812 × 2.00 / 1M = 0.001624 → 0.00315475 → 6 decimals
        assertThat(metrics.estimatedCostUsd()).isEqualByComparingTo("0.003155");
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = Build7OpenAiEndToEndTest.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("test resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
