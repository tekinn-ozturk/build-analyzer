package com.company.buildanalyzer.application.port.out;

import java.util.Map;

/**
 * A JSON schema for the model's answer, as a plain nested map (standard JSON Schema
 * keywords), so the application can demand structured output without knowing a provider.
 *
 * @param name   short identifier of the schema, e.g. {@code root_cause_analysis}
 * @param schema the JSON schema object
 */
public record LlmResponseSchema(String name, Map<String, Object> schema) {
}
