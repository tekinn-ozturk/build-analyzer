package com.company.buildanalyzer.infrastructure.jenkins;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Binds the {@code jenkins.*} configuration block.
 */
@Component
@Getter
@Setter
@ConfigurationProperties(prefix = "jenkins")
public class JenkinsProperties {

    /** Base URL of the Jenkins instance, e.g. http://localhost:8080 */
    private String url;

    /** Jenkins user name for HTTP Basic authentication. */
    private String username;

    /** Jenkins API token used as the Basic-auth password (bound from {@code jenkins.api-token}). */
    private String apiToken;
}
