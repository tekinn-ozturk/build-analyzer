package com.company.buildanalyzer.infrastructure;

import com.company.buildanalyzer.infrastructure.jenkins.JenkinsProperties;
import com.company.buildanalyzer.infrastructure.llm.openai.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link OpenAiProperties} and {@link JenkinsProperties} from the real
 * {@code application.yml} against a simulated OS environment, proving that the
 * yml defaults apply, that environment variables override them, and that no
 * credential is baked into the yml.
 */
class EnvironmentConfigBindingTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({OpenAiProperties.class, JenkinsProperties.class})
    static class Config {
    }

    private ApplicationContextRunner runnerWithEnv(Map<String, Object> env) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
                .withUserConfiguration(Config.class)
                .withInitializer(context -> {
                    ConfigurableEnvironment environment = context.getEnvironment();
                    // Replace the machine's real environment with a controlled one.
                    environment.getPropertySources()
                            .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
                    environment.getPropertySources().addFirst(new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, env));
                    applicationYml().forEach(environment.getPropertySources()::addLast);
                });
    }

    @Test
    void openAiUsesApplicationYmlDefaultsAndTheKeyFromTheEnvironment() {
        runnerWithEnv(Map.of("OPENAI_API_KEY", "sk-test-from-env")).run(context -> {
            assertThat(context.getEnvironment().getProperty("llm.provider")).isEqualTo("OPENAI");
            OpenAiProperties openai = context.getBean(OpenAiProperties.class);
            assertThat(openai.getApiKey()).isEqualTo("sk-test-from-env");
            assertThat(openai.getUrl()).isEqualTo("https://api.openai.com/v1");
            assertThat(openai.getModel()).isEqualTo("gpt-5-mini");
            assertThat(openai.timeout()).hasSeconds(120);
            assertThat(openai.connectTimeout()).hasSeconds(10);
            assertThat(openai.getMaxOutputTokens()).isEqualTo(4000);
            assertThat(openai.getReasoningEffort()).isEqualTo("low");
            assertThat(openai.getVerbosity()).isEqualTo("low");
            assertThat(openai.getPricing().getInputUsdPerMillion()).isEqualByComparingTo("0.25");
            assertThat(openai.getPricing().getOutputUsdPerMillion()).isEqualByComparingTo("2.00");
        });
    }

    @Test
    void openAiEnvironmentVariablesOverrideApplicationYml() {
        runnerWithEnv(Map.of(
                "OPENAI_API_KEY", "sk-test-from-env",
                "OPENAI_URL", "https://proxy.example.test/v1",
                "OPENAI_MODEL", "gpt-5",
                "OPENAI_TIMEOUT_SECONDS", "60",
                "OPENAI_MAX_OUTPUT_TOKENS", "2000",
                "OPENAI_REASONING_EFFORT", "minimal",
                "OPENAI_VERBOSITY", ""
        )).run(context -> {
            OpenAiProperties openai = context.getBean(OpenAiProperties.class);
            assertThat(openai.getUrl()).isEqualTo("https://proxy.example.test/v1");
            assertThat(openai.getModel()).isEqualTo("gpt-5");
            assertThat(openai.timeout()).hasSeconds(60);
            assertThat(openai.getMaxOutputTokens()).isEqualTo(2000);
            assertThat(openai.getReasoningEffort()).isEqualTo("minimal");
            assertThat(openai.getVerbosity()).as("empty = not sent").isEmpty();
        });
    }

    @Test
    void theApiKeyIsNotInApplicationYmlSoStartupFailsWithoutTheEnvironmentVariable() {
        runnerWithEnv(Map.of()).run(context -> assertThat(context).hasFailed());
    }

    @Test
    void invalidOpenAiTimeoutFailsStartup() {
        runnerWithEnv(Map.of("OPENAI_API_KEY", "sk-test", "OPENAI_TIMEOUT_SECONDS", "0"))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void jenkinsHasNoCredentialsWithoutEnvironmentVariables() {
        runnerWithEnv(Map.of("OPENAI_API_KEY", "sk-test")).run(context -> {
            JenkinsProperties jenkins = context.getBean(JenkinsProperties.class);
            assertThat(jenkins.getUrl()).isEqualTo("http://localhost:8080");
            assertThat(jenkins.getUsername()).isEmpty();
            assertThat(jenkins.getApiToken()).isEmpty();
            assertThat(jenkins.getLogCharset()).isEqualTo(StandardCharsets.UTF_8);
            assertThat(jenkins.getLogFallbackCharset()).isEqualTo(Charset.forName("windows-1254"));
        });
    }

    @Test
    void jenkinsCredentialsAndUrlComeFromEnvironmentVariables() {
        runnerWithEnv(Map.of(
                "OPENAI_API_KEY", "sk-test",
                "JENKINS_URL", "https://jenkins.example.test",
                "JENKINS_USERNAME", "qa-bot",
                "JENKINS_API_TOKEN", "test-token-from-env"
        )).run(context -> {
            JenkinsProperties jenkins = context.getBean(JenkinsProperties.class);
            assertThat(jenkins.getUrl()).isEqualTo("https://jenkins.example.test");
            assertThat(jenkins.getUsername()).isEqualTo("qa-bot");
            assertThat(jenkins.getApiToken()).isEqualTo("test-token-from-env");
        });
    }

    private static List<PropertySource<?>> applicationYml() {
        try {
            return new YamlPropertySourceLoader().load("application.yml", new ClassPathResource("application.yml"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
