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

    public JenkinsHttpClient(JenkinsProperties properties) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(properties.getUrl());

        // Send HTTP Basic auth (username + API token) only when configured;
        // otherwise fall back to anonymous access.
        if (StringUtils.hasText(properties.getUsername())) {
            builder.requestInterceptor(new BasicAuthenticationInterceptor(
                    properties.getUsername(), properties.getApiToken()));
            log.info("Jenkins client configured with Basic authentication for user '{}'", properties.getUsername());
        } else {
            log.info("Jenkins client configured for anonymous access (no username set)");
        }

        this.restClient = builder.build();
    }

    /**
     * Calls {@code /job/{jobName}/{buildNumber}/consoleText} and returns the body.
     */
    public String getConsoleText(String jobName, int buildNumber) {
        log.debug("Fetching Jenkins console log: job={}, build={}", jobName, buildNumber);
        return restClient.get()
                .uri("/job/{jobName}/{buildNumber}/consoleText", jobName, buildNumber)
                .retrieve()
                .body(String.class);
    }
}
