package com.company.buildanalyzer.application.port.out;

/**
 * Raised by {@link LlmProvider} implementations when the model cannot produce an
 * analysis (provider unreachable, timeout, non-2xx reply, empty answer).
 * Adapters wrap their transport-specific errors in this type so that the
 * application and API layers never depend on an HTTP client library.
 */
public class LlmAnalysisException extends RuntimeException {

    public LlmAnalysisException(String message) {
        super(message);
    }

    public LlmAnalysisException(String message, Throwable cause) {
        super(message, cause);
    }
}
