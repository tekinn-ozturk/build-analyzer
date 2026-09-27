package com.company.buildanalyzer.infrastructure.jenkins;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.support.BasicAuthenticationInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

/**
 * Thin HTTP client around the Jenkins REST API. This is the only class that
 * knows the Jenkins wire format and holds the auth details; the port and the
 * application layer stay unaware of how the log is fetched or authenticated.
 */
@Slf4j
@Component
public class JenkinsHttpClient {

    private final RestClient restClient;
    private final ConsoleLogDecoder logDecoder;

    public JenkinsHttpClient(JenkinsProperties properties) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getUrl());

        // Send HTTP Basic auth (username + API token) only when configured;
        // otherwise fall back to anonymous access.
        if (StringUtils.hasText(properties.getUsername())) {
            if (!StringUtils.hasText(properties.getApiToken())) {
                log.warn("JENKINS_USERNAME is set but JENKINS_API_TOKEN is empty; Jenkins will likely answer 401");
            }
            builder.requestInterceptor(new BasicAuthenticationInterceptor(
                    properties.getUsername(), properties.getApiToken() == null ? "" : properties.getApiToken()));
            log.info("Jenkins client configured with Basic authentication for user '{}'", properties.getUsername());
        } else {
            log.info("Jenkins client configured for anonymous access (no username set)");
        }

        this.restClient = builder.build();
        this.logDecoder = new ConsoleLogDecoder(properties.getLogCharset(), properties.getLogFallbackCharset());
        log.info("Jenkins console log charset: {} (fallback: {})",
                properties.getLogCharset(), properties.getLogFallbackCharset());
    }

    /**
     * Calls {@code /job/{jobName}/{buildNumber}/consoleText} and returns the body.
     * The body is read as raw bytes and decoded by {@link ConsoleLogDecoder}, not
     * by the response's Content-Type charset, which Jenkins does not reliably set
     * to match what the build process actually wrote.
     */
    public String getConsoleText(String jobName, int buildNumber) {
        log.debug("Fetching Jenkins console log: job={}, build={}", jobName, buildNumber);
        byte[] body = restClient.get()
                .uri("/job/{jobName}/{buildNumber}/consoleText", jobName, buildNumber)
                .retrieve()
                .body(byte[].class);
        return logDecoder.decode(body);
    }
}
