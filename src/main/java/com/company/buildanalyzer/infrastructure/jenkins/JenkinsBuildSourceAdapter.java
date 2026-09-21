package com.company.buildanalyzer.infrastructure.jenkins;

import com.company.buildanalyzer.application.port.out.BuildSourcePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Jenkins-backed implementation of {@link BuildSourcePort}. Adapts the port
 * contract to the concrete {@link JenkinsHttpClient} call.
 */
@Component
@RequiredArgsConstructor
public class JenkinsBuildSourceAdapter implements BuildSourcePort {

    private final JenkinsHttpClient jenkinsHttpClient;

    @Override
    public String fetchConsoleLog(String jobName, int buildNumber) {
        return jenkinsHttpClient.getConsoleText(jobName, buildNumber);
    }
}
