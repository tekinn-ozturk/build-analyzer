package com.company.buildanalyzer.infrastructure.llm;

import com.company.buildanalyzer.application.port.out.LlmProvider;
import com.company.buildanalyzer.infrastructure.llm.openai.OpenAiConfiguration;
import com.company.buildanalyzer.infrastructure.llm.openai.OpenAiProperties;
import com.company.buildanalyzer.infrastructure.llm.openai.OpenAiProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider abstraction: {@code llm.provider} decides which adapter backs the
 * {@link LlmProvider} port, and an inactive provider's settings are not required.
 */
class LlmProviderSelectionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ValidationAutoConfiguration.class))
            .withUserConfiguration(OpenAiConfiguration.class)
            .withPropertyValues(
                    "openai.url=https://api.openai.com/v1",
                    "openai.model=gpt-5-mini",
                    "openai.timeout-seconds=120",
                    "openai.connect-timeout-seconds=10",
                    "openai.max-output-tokens=4000");

    @Test
    void openAiIsTheDefaultProviderWhenNoneIsConfigured() {
        runner.withPropertyValues("openai.api-key=test-key").run(context -> {
            assertThat(context).hasSingleBean(LlmProvider.class);
            assertThat(context.getBean(LlmProvider.class)).isInstanceOf(OpenAiProvider.class);
        });
    }

    @Test
    void providerNameIsCaseInsensitive() {
        runner.withPropertyValues("llm.provider=openai", "openai.api-key=test-key")
                .run(context -> assertThat(context.getBean(LlmProvider.class)).isInstanceOf(OpenAiProvider.class));
    }

    @Test
    void anotherProviderDisablesOpenAiAndDoesNotRequireItsApiKey() {
        runner.withPropertyValues("llm.provider=ANTHROPIC").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LlmProvider.class);
            assertThat(context).doesNotHaveBean(OpenAiProperties.class);
        });
    }

    @Test
    void missingOpenAiApiKeyFailsStartup() {
        // explicit empty value: overrides an OPENAI_API_KEY that may exist on the machine running the test
        runner.withPropertyValues("openai.api-key=").run(context -> assertThat(context).hasFailed()
                .getFailure().rootCause().hasMessageContaining("OPENAI_API_KEY"));
    }
}
