package com.company.buildanalyzer;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the provider-independent architecture: the application core and the
 * domain never see a concrete LLM provider or the infrastructure layer, so
 * adding a provider stays "write one adapter".
 */
class ArchitectureBoundaryTest {

    private static final Path MAIN = Path.of("src/main/java/com/company/buildanalyzer");

    @Test
    void applicationAndDomainDoNotDependOnInfrastructureOrAConcreteProvider() throws IOException {
        for (String layer : List.of("application", "domain")) {
            try (Stream<Path> files = Files.walk(MAIN.resolve(layer))) {
                files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                    String source = read(file);
                    assertThat(source)
                            .as(file.toString())
                            .doesNotContain("buildanalyzer.infrastructure")
                            .doesNotContain("import com.openai")
                            .doesNotContain("OpenAi");
                });
            }
        }
    }

    @Test
    void noOllamaAdapterOrConfigurationIsLeftInTheProduct() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN)) {
            assertThat(files.map(Path::toString)).noneMatch(path -> path.toLowerCase().contains("ollama"));
        }
        assertThat(read(Path.of("src/main/resources/application.yml")))
                .doesNotContain("ollama:")
                .doesNotContain("OLLAMA_");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
