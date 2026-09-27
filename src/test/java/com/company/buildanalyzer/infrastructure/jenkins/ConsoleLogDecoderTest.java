package com.company.buildanalyzer.infrastructure.jenkins;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleLogDecoderTest {

    private static final Charset WINDOWS_1254 = Charset.forName("windows-1254");

    private final ConsoleLogDecoder decoder = new ConsoleLogDecoder(StandardCharsets.UTF_8, WINDOWS_1254);

    @Test
    void decodesValidUtf8AsIs() {
        String log = "Başlatan kullanıcı: Tekin Öztürk\nFinished: SUCCESS\n";

        assertThat(decoder.decode(log.getBytes(StandardCharsets.UTF_8))).isEqualTo(log);
    }

    @Test
    void decodesWindows1254LinesWithoutReplacementCharacters() {
        // What a Turkish Windows JVM writes without -Dfile.encoding=UTF-8
        String line = "Eyl 20, 2026 11:49:46 ÖÖ org.openqa.selenium.devtools.CdpVersionFinder\n";

        String decoded = decoder.decode(line.getBytes(WINDOWS_1254));

        assertThat(decoded).isEqualTo(line).doesNotContain("�");
    }

    @Test
    void decodesMixedLogLineByLine() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write("Başlatan: Tekin Öztürk\n".getBytes(StandardCharsets.UTF_8));          // Jenkins (UTF-8)
        bytes.write("Eyl 20, 2026 11:49:46 ÖÖ uyarı: şğı\n".getBytes(WINDOWS_1254));      // test JVM (cp1254)
        bytes.write("Finished: SUCCESS".getBytes(StandardCharsets.UTF_8));                // no trailing newline

        String decoded = decoder.decode(bytes.toByteArray());

        assertThat(decoded).isEqualTo("Başlatan: Tekin Öztürk\nEyl 20, 2026 11:49:46 ÖÖ uyarı: şğı\nFinished: SUCCESS");
    }

    @Test
    void withoutFallbackInvalidBytesBecomeReplacementCharacters() {
        ConsoleLogDecoder utf8Only = new ConsoleLogDecoder(StandardCharsets.UTF_8, null);

        assertThat(utf8Only.decode("ÖÖ".getBytes(WINDOWS_1254))).isEqualTo("��");
    }

    @Test
    void nullOrEmptyBodyIsEmptyText() {
        assertThat(decoder.decode(null)).isEmpty();
        assertThat(decoder.decode(new byte[0])).isEmpty();
    }
}
