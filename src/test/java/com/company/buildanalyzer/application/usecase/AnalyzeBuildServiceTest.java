package com.company.buildanalyzer.application.usecase;

import com.company.buildanalyzer.application.classifier.ErrorClassifier;
import com.company.buildanalyzer.application.context.BuildContextBuilder;
import com.company.buildanalyzer.application.port.out.BuildSourcePort;
import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AnalyzeBuildServiceTest {

    private final BuildSourcePort buildSourcePort = mock(BuildSourcePort.class);
    // Use the real builder so the test also proves fetch -> extraction wiring.
    private final AnalyzeBuildService service =
            new AnalyzeBuildService(buildSourcePort, new BuildContextBuilder(new ErrorClassifier()));

    @Test
    void fetchesLogThroughPortAndReturnsExtractedContext() {
        String rawLog = """
                org.openqa.selenium.SessionNotCreatedException: session not created
                \tat org.openqa.selenium.remote.ProtocolHandshake.createSession(ProtocolHandshake.java:130)
                Finished: FAILURE
                """;
        when(buildSourcePort.fetchConsoleLog("Mini-UI-Automation", 5)).thenReturn(rawLog);

        BuildAnalysisContext context = service.analyze("Mini-UI-Automation", 5);

        verify(buildSourcePort).fetchConsoleLog("Mini-UI-Automation", 5);
        assertThat(context.buildStatus()).isEqualTo("FAILURE");
        assertThat(context.exceptionType()).isEqualTo("org.openqa.selenium.SessionNotCreatedException");
        assertThat(context.errorCategory()).isEqualTo(ErrorCategory.SELENIUM);
        assertThat(context.stackTrace()).contains("ProtocolHandshake.createSession");
    }
}
