package com.company.buildanalyzer.application.port.out;

/**
 * Outbound port for sending a prompt to a large language model.
 * The application core depends only on this contract and never knows which
 * provider answers. Each provider (OpenAI today; Anthropic, Ollama, ... later)
 * is one adapter in the infrastructure layer — adding a provider means adding
 * an implementation, nothing in application, domain or api changes.
 */
public interface LlmProvider {
    /**
     * @return the model's answer together with the provider's usage statistics
     * @throws LlmAnalysisException if the model cannot be reached or returns no usable answer
     */
    LlmCompletion complete(LlmRequest request);

    /** Free-text shortcut for {@link #complete(LlmRequest)}. */
    default LlmCompletion complete(String prompt) {
        return complete(LlmRequest.of(prompt));
    }
}
