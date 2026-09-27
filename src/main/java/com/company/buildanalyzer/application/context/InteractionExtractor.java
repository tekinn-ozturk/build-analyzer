package com.company.buildanalyzer.application.context;

import com.company.buildanalyzer.domain.model.FailedInteraction;

import java.util.Optional;

/**
 * Tool-specific strategy that reads the failing UI-automation command/locator out of an
 * exception block. One implementation per tool (Selenium, Playwright, Cypress, ...);
 * {@link BuildContextBuilder} receives all of them and uses the first that recognises the block.
 * Adding a tool means adding an implementation — the domain model, the prompt and the API
 * stay unchanged.
 */
public interface InteractionExtractor {

    /**
     * @param exceptionBlock the extracted exception block (header, message lines, frames); never {@code null}
     * @return the failed interaction, or empty if this block is not from this extractor's tool
     */
    Optional<FailedInteraction> extract(String exceptionBlock);
}
