package com.company.buildanalyzer.application.analysis;

import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the model's JSON answer ({@link RootCauseSchema}) into a {@link RootCauseAnalysis}.
 * Tolerant of what models do even when asked for pure JSON: a {@code ```json} fence or text
 * around the object. Normalises the values: blanks → {@code null}, {@code line <= 0} → {@code null},
 * {@code file} reduced to the file name (path, package and {@code :line} suffix removed), {@code method}
 * reduced to the simple method name,
 * list markers stripped from actions, at most {@link RootCauseAnalysis#MAX_ACTIONS} actions.
 * Returns empty when there is no usable JSON or no root cause — the caller then shows the raw text.
 */
@Slf4j
@Component
public class RootCauseParser {

    private static final Pattern LIST_MARKER = Pattern.compile("^\\s*(?:[-*•]|\\d+[.)])\\s+");
    private static final Pattern LINE_BREAKS = Pattern.compile("\\s*\\R\\s*");
    private static final Pattern FILE_WITH_LINE = Pattern.compile("(.+?):(\\d+)(?::\\d+)?");
    /** A package-qualified source file: {@code com.acme.OrderSteps.java} → group 1 {@code OrderSteps.java}. */
    private static final Pattern QUALIFIED_SOURCE_FILE =
            Pattern.compile("(?:[a-z_$][\\w$]*\\.)+([A-Z][\\w$]*\\.(?:java|kt|groovy|scala|cs))");

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Optional<RootCauseAnalysis> parse(String answer) {
        JsonNode root = readJson(answer);
        if (root == null) {
            return Optional.empty();
        }
        String rootCause = text(root.get("rootCause"));
        if (rootCause == null) {
            log.warn("AI answer has no rootCause; showing it as plain text");
            return Optional.empty();
        }
        String file = text(root.get("file"));
        Integer line = line(root.get("line"));
        Matcher fileWithLine = file == null ? null : FILE_WITH_LINE.matcher(file);
        if (fileWithLine != null && fileWithLine.matches()) {          // "OrderSteps.java:64"
            file = fileWithLine.group(1);
            line = line != null ? line : Integer.valueOf(fileWithLine.group(2));
        }
        return Optional.of(new RootCauseAnalysis(
                rootCause,
                fileName(file),
                line,
                methodName(text(root.get("method"))),
                actions(root.get("actions"))));
    }

    /** The model's answer for a build with several failed tests ({@link RootCauseSchema#FAILURE_GROUPS}). */
    public record GroupsAnswer(String summary, Map<String, FailureGroupAnalysis> groups) {
    }

    /**
     * Parses a {@link RootCauseSchema#FAILURE_GROUPS} answer. Groups without an id or a root cause are left out
     * (the caller reports them as unanswered); actions are normalised as for a single failure.
     * Empty when there is no usable JSON at all.
     */
    public Optional<GroupsAnswer> parseGroups(String answer) {
        JsonNode root = readJson(answer);
        if (root == null) {
            return Optional.empty();
        }
        if (!root.path("groups").isArray()) {
            log.warn("AI answer has no groups array; showing it as plain text");
            return Optional.empty();
        }
        Map<String, FailureGroupAnalysis> groups = new LinkedHashMap<>();
        for (JsonNode group : root.get("groups")) {
            String id = text(group.get("id"));
            String rootCause = text(group.get("rootCause"));
            if (id != null && rootCause != null) {
                groups.putIfAbsent(id, FailureGroupAnalysis.analyzed(id, rootCause, actions(group.get("actions"))));
            }
        }
        return Optional.of(new GroupsAnswer(text(root.get("summary")), groups));
    }

    private JsonNode readJson(String answer) {
        String json = jsonObject(answer);
        if (json == null) {
            log.warn("AI answer contains no JSON object; showing it as plain text");
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            log.warn("AI answer is not valid JSON ({}); showing it as plain text", ex.getOriginalMessage());
            return null;
        }
    }

    /**
     * Just the file name: {@code /C:/.../steps/PaymentSteps.java} or
     * {@code com.acme.orders.steps.OrderSteps.java} → {@code PaymentSteps.java} / {@code OrderSteps.java}.
     */
    private static String fileName(String file) {
        if (file == null) {
            return null;
        }
        String name = file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\')) + 1);
        Matcher qualified = QUALIFIED_SOURCE_FILE.matcher(name);
        return qualified.matches() ? qualified.group(1) : name;
    }

    /** {@code com.acme.OrderSteps.statusShouldBe()} → {@code statusShouldBe} ({@code ()} is added when rendering). */
    private static String methodName(String method) {
        if (method == null) {
            return null;
        }
        String name = method.endsWith("()") ? method.substring(0, method.length() - 2) : method;
        return name.contains("(") ? method : name.substring(name.lastIndexOf('.') + 1);
    }

    /** The outermost {...} of the answer, or {@code null}. */
    private static String jsonObject(String answer) {
        if (answer == null) {
            return null;
        }
        int start = answer.indexOf('{');
        int end = answer.lastIndexOf('}');
        return start >= 0 && end > start ? answer.substring(start, end + 1) : null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return null;
        }
        // one line per value keeps the rendered answer within its line budget
        String value = LINE_BREAKS.matcher(node.asText()).replaceAll(" ").strip();
        return value.isEmpty() || value.equalsIgnoreCase("null") ? null : value;
    }

    private static Integer line(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        int value = node.isNumber() ? node.asInt() : node.asInt(-1);   // "28" is accepted too
        return value > 0 ? value : null;
    }

    private static List<String> actions(JsonNode node) {
        List<String> actions = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return actions;
        }
        for (JsonNode item : node) {
            String action = text(item);
            if (action != null) {
                action = LIST_MARKER.matcher(action).replaceFirst("").strip();
            }
            if (action != null && !action.isEmpty() && actions.size() < RootCauseAnalysis.MAX_ACTIONS) {
                actions.add(action);
            }
        }
        return actions;
    }
}
