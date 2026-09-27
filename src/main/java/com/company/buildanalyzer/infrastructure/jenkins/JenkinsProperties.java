package com.company.buildanalyzer.infrastructure.jenkins;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * Binds the {@code jenkins.*} configuration block. Credentials are supplied via
 * {@code JENKINS_USERNAME} / {@code JENKINS_API_TOKEN}; nothing secret lives in
 * {@code application.yml}.
 */
@Component
@Validated
@Getter
@Setter
@ConfigurationProperties(prefix = "jenkins")
public class JenkinsProperties {

    /** Base URL of the Jenkins instance, e.g. http://localhost:8080 */
    @NotBlank
    private String url;

    /** Jenkins user name for HTTP Basic authentication; blank = anonymous access. */
    private String username;

    /** Jenkins API token used as the Basic-auth password (bound from {@code jenkins.api-token}). */
    private String apiToken;

    /** Charset the console log is decoded with. */
    @NotNull
    private Charset logCharset = StandardCharsets.UTF_8;

    /**
     * Charset used for log lines that are not valid in {@link #logCharset} — typically
     * output of a Windows JVM running in the platform code page (windows-1254 for Turkish).
     * {@code null} = no fallback (invalid bytes become U+FFFD).
     */
    private Charset logFallbackCharset;
}
