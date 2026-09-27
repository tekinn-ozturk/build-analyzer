package com.company.buildanalyzer.infrastructure.llm.openai;

import com.company.buildanalyzer.application.port.out.LlmProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Activates the OpenAI adapter when {@code llm.provider=OPENAI} (also the default when
 * the property is missing). Every provider gets such a configuration, gated on its own
 * {@code llm.provider} value, so exactly one {@link LlmProvider} bean exists and the
 * other providers' settings (API keys) are neither bound nor required.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "llm", name = "provider", havingValue = OpenAiProvider.NAME, matchIfMissing = true)
@EnableConfigurationProperties(OpenAiProperties.class)
public class OpenAiConfiguration {

    @Bean
    LlmProvider openAiProvider(OpenAiProperties properties) {
        return new OpenAiProvider(properties);
    }
}
