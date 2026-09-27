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
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>Live, opt-in</b> check against the real OpenAI Responses API with real/sample build logs.
 * Costs a fraction of a cent per log and sends the logs to OpenAI, so it only runs when asked:
 * <pre>mvn test -Dtest=OpenAiLiveBuild7Test -Dopenai.live=true</pre>
 * (needs {@code OPENAI_API_KEY} in the environment). Each answer is appended, UTF-8, to
 * {@code target/live-analyses.txt} for reading. Verifies the structured answer (location from
 * the evidence, at most 3 actions, at most 10 lines) and the usage/cost numbers.
 */
@EnabledIfSystemProperty(named = "openai.live", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
class OpenAiLiveBuild7Test {

    private static final BigDecimal INPUT_USD_PER_MILLION = new BigDecimal("0.25");
    private static final BigDecimal OUTPUT_USD_PER_MILLION = new BigDecimal("2.00");
    private static final Path REPORT = Path.of("target/live-analyses.txt");

    @ParameterizedTest(name = "{0}")
    @CsvSource(nullValues = "-", value = {
            // log                                     expected file           line  root cause must mention
            "logs/build-7-selenium-failure.log,        ExampleSteps.java,      28,   qqqqqqqq",
            "logs/samples/selenium-timeout.log,        CheckoutPage.java,      41,   pay-now-btn",
            "logs/samples/api-assertion-failure.log,   OrderSteps.java,        64,   500",
            "logs/samples/maven-compile-error.log,     PaymentSteps.java,      47,   withCurrency",
    })
    void analysesTheLogIntoTheShortFormat(String log, String expectedFile, Integer expectedLine, String mustMention)
            throws IOException {
        String consoleLog = fixture(log);
        AnalyzeBuildService service = new AnalyzeBuildService(
                (job, build) -> consoleLog,
                new BuildContextBuilder(new ErrorClassifier(), new RelevantLogExtractor(),
                        List.of(new SeleniumInteractionExtractor())),
                new PromptBuilder(),
                new OpenAiProvider(liveProperties()),
                new RootCauseParser(),
                new RootCauseFormatter());

        BuildAnalysisResult result = service.analyze("live", 1);
        report(log, result);

        RootCauseAnalysis analysis = result.rootCauseAnalysis();
        assertThat(analysis).as("model answered with parseable JSON").isNotNull();
        assertThat(analysis.rootCause()).contains(mustMention);
        assertThat(analysis.file()).isEqualTo(expectedFile);
        assertThat(analysis.line()).isEqualTo(expectedLine);
        assertThat(analysis.actions()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(result.aiAnalysis().lines()).hasSizeLessThanOrEqualTo(10);
        assertThat(result.aiAnalysis()).doesNotContain("GÜVEN").doesNotContain("TEKNİK AÇIKLAMA");

        LlmMetrics metrics = result.llmMetrics();
        assertThat(metrics.provider()).isEqualTo("OPENAI");
        assertThat(metrics.model()).startsWith("gpt-5-mini");
        assertThat(metrics.totalTokens()).isEqualTo(metrics.promptTokens() + metrics.completionTokens());
        BigDecimal expectedCost = INPUT_USD_PER_MILLION.multiply(BigDecimal.valueOf(metrics.promptTokens()))
                .add(OUTPUT_USD_PER_MILLION.multiply(BigDecimal.valueOf(metrics.completionTokens())))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        assertThat(metrics.estimatedCostUsd()).isEqualByComparingTo(expectedCost);
    }

    private static void report(String log, BuildAnalysisResult result) throws IOException {
        String entry = "##### %s%n%s%n--- %s%n%n".formatted(log, result.aiAnalysis(), result.llmMetrics());
        Files.createDirectories(REPORT.getParent());
        Files.writeString(REPORT, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        new PrintStream(System.out, true, StandardCharsets.UTF_8).print(entry);
    }

    private static OpenAiProperties liveProperties() {
        OpenAiProperties properties = new OpenAiProperties();
        properties.setUrl("https://api.openai.com/v1");
        properties.setApiKey(System.getenv("OPENAI_API_KEY"));
        properties.setModel("gpt-5-mini");
        properties.setTimeoutSeconds(120);
        properties.setConnectTimeoutSeconds(10);
        properties.setMaxOutputTokens(4000);
        properties.setReasoningEffort("low");
        properties.setVerbosity("low");
        properties.getPricing().setInputUsdPerMillion(INPUT_USD_PER_MILLION);
        properties.getPricing().setOutputUsdPerMillion(OUTPUT_USD_PER_MILLION);
        return properties;
    }

    private static String fixture(String path) throws IOException {
        try (InputStream in = OpenAiLiveBuild7Test.class.getClassLoader().getResourceAsStream(path)) {
            assertThat(in).as("test resource %s", path).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
