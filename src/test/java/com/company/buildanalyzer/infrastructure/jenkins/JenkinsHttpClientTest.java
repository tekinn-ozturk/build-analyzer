package com.company.buildanalyzer.infrastructure.jenkins;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks the console-log URL sent to an in-process stand-in for Jenkins. */
class JenkinsHttpClientTest {

    private final AtomicReference<String> receivedRawPath = new AtomicReference<>();
    private HttpServer server;
    private JenkinsHttpClient client;

    @BeforeEach
    void startJenkinsStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            receivedRawPath.set(exchange.getRequestURI().getRawPath());
            byte[] bytes = "Finished: SUCCESS".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
        server.start();

        JenkinsProperties properties = new JenkinsProperties();
        properties.setUrl("http://127.0.0.1:" + server.getAddress().getPort());
        client = new JenkinsHttpClient(properties);
    }

    @AfterEach
    void stopJenkinsStub() {
        server.stop(0);
    }

    @Test
    void fetchesTopLevelJob() {
        assertThat(client.getConsoleText("UI-Test", 125)).isEqualTo("Finished: SUCCESS");
        assertThat(receivedRawPath.get()).isEqualTo("/job/UI-Test/125/consoleText");
    }

    @Test
    void fetchesJobInsideNestedFolders() {
        client.getConsoleText("Team/UI/Regression", 125);

        assertThat(receivedRawPath.get()).isEqualTo("/job/Team/job/UI/job/Regression/125/consoleText");
    }

    @Test
    void encodesEachJobNameSegment() {
        client.getConsoleText("My Team/UI Tests", 7);

        assertThat(receivedRawPath.get()).isEqualTo("/job/My%20Team/job/UI%20Tests/7/consoleText");
    }
}
