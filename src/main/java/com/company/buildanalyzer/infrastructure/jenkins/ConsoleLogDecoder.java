package com.company.buildanalyzer.infrastructure.jenkins;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;

/**
 * Turns the raw bytes of a Jenkins console log into text.
 * <p>
 * Jenkins stores console output as the bytes the build process wrote. On a Windows
 * agent a JVM without {@code -Dfile.encoding=UTF-8} writes in the platform code page
 * (windows-1254 for Turkish), while Jenkins' own lines are UTF-8. Decoding such a log
 * as plain UTF-8 turns every Turkish letter into U+FFFD ("Eyl 20, 2026 11:49:46 ��").
 * <p>
 * Strategy: decode the whole log with the primary charset (strict). If that fails,
 * decode line by line — each line strictly with the primary charset, falling back to
 * the fallback charset only for lines that are not valid in the primary one.
 * Stateless and thread-safe (a fresh {@link java.nio.charset.CharsetDecoder} per call).
 */
final class ConsoleLogDecoder {

    private final Charset primary;
    private final Charset fallback;

    ConsoleLogDecoder(Charset primary, Charset fallback) {
        this.primary = primary;
        this.fallback = fallback;
    }

    String decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return "";
        }
        String whole = decodeStrict(bytes, 0, bytes.length);
        if (whole != null) {
            return whole;
        }

        StringBuilder text = new StringBuilder(bytes.length);
        int lineStart = 0;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '\n') {   // '\n' is the same byte in UTF-8 and single-byte code pages
                text.append(decodeLine(bytes, lineStart, i + 1 - lineStart));
                lineStart = i + 1;
            }
        }
        if (lineStart < bytes.length) {
            text.append(decodeLine(bytes, lineStart, bytes.length - lineStart));
        }
        return text.toString();
    }

    private String decodeLine(byte[] bytes, int offset, int length) {
        String strict = decodeStrict(bytes, offset, length);
        if (strict != null) {
            return strict;
        }
        Charset lenient = fallback != null ? fallback : primary;
        return new String(bytes, offset, length, lenient);
    }

    /** @return the decoded text, or {@code null} if the bytes are not valid in the primary charset. */
    private String decodeStrict(byte[] bytes, int offset, int length) {
        try {
            return primary.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
