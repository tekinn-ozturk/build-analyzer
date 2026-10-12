package com.company.buildanalyzer.application.prompt;

import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureGroup;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.TestFailure;
import com.company.buildanalyzer.domain.model.TestSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Turns a {@link BuildAnalysisContext} into the prompt sent to the LLM. It only
 * assembles text — it performs no extraction, classification or model call, and
 * knows nothing about the provider.
 *
 * <p>Short and directive. Structure:
 * <ol>
 *   <li>role (decision support, not a report), source priority and rules (no speculation, no tutorials);</li>
 *   <li><b>Structured Evidence</b> — the parser's facts + full stack trace: primary source;</li>
 *   <li><b>Relevant Log Snippet</b> — filtered log lines: secondary source;</li>
 *   <li><b>Jenkins log, last 200 lines</b> — supporting context only, so nothing the parser
 *       missed is lost;</li>
 *   <li>the JSON answer format ({@code RootCauseSchema}), parsed and rendered by the backend.</li>
 * </ol>
 * The prompt states that the structured evidence wins on conflict. Only fields the parser
 * actually found are listed; nothing is sent as "unknown". Written in Turkish (the app
 * serves Turkish users); technical identifiers are passed through unchanged.
 *
 * <p>A build with several failed tests gets {@link #buildGroupPrompts group prompts} instead: the build
 * summary, an index of all failure groups, then per group its counted test list, ONE representative
 * stack trace and the lines logged right after it — never the passed tests, never every copy of a repeated
 * stack trace, and no build-wide log snippet or raw tail (in a big run those are mostly passed scenarios).
 * All groups normally fit into one prompt; otherwise they are split into at most
 * {@link AnalysisLimits#getMaxLlmCalls()} prompts.
 *
 * <p>Credentials that may appear in a log (password=..., tokens, Basic/Bearer headers, user:password@ in URLs)
 * are masked in every prompt.
 */
@Service
@RequiredArgsConstructor
public class PromptBuilder {

    static final String EVIDENCE_LABEL = "1. YAPISAL KANIT (Structured Evidence)";
    static final String SNIPPET_LABEL = "2. İLGİLİ LOG KESİTİ (Relevant Log Snippet)";
    static final String TAIL_LABEL = "3. JENKINS LOG — SON 200 SATIR";

    static final String BUILD_LABEL = "BUILD ÖZETİ";
    static final String INDEX_LABEL = "TÜM HATA GRUPLARI";

    /** Very long raw lines (base64 screenshots, capability dumps) are clipped in the tail only. */
    static final int MAX_TAIL_LINE_LENGTH = 500;

    /** Per group: test names listed, stack-trace lines and index lines sent; the counts are always complete. */
    static final int MAX_TESTS_PER_GROUP = 10;
    static final int MAX_GROUP_TRACE_LINES = 40;
    static final int MAX_INDEX_GROUPS = 30;
    static final int MAX_INDEX_LINE_LENGTH = 300;

    private static final String RULES = """
            Kurallar:
            - Yalnızca kanıtta geçenleri yaz. Kanıtta geçmeyen olasılık ve tahmin yazma \
            (ör. "iframe olabilir", "uygulama değişmiş olabilir", "cache sorunu olabilir", "wait eklenmeli").
            - Teknoloji veya framework tahmini yapma. Root cause uydurma.
            - Teknik açıklama, eğitim metni veya "Selenium/API/timeout nedir" gibi öğretici cümleler yazma.
            - Root cause spesifik olsun: locator, değer, exception, dosya adı ver. \
            Kötü: "Element bulunamadı." İyi: "id=login-btn locator'ı bulunamadı; NoSuchElementException oluştu.\"""";

    private static final String INSTRUCTIONS = """
            Sen bir Jenkins build hatası karar destek sistemisin. Rapor yazma; QA ve geliştiriciye \
            "Neden patladı? Nerede patladı? Ne yapmalıyım?" sorularını 10 saniyede okunacak kadar kısa cevapla.
            Metinleri Türkçe yaz; exception, sınıf, metot, dosya adı ve locator gibi teknik ifadeleri aynen koru.

            Kaynak önceliği:
            1. YAPISAL KANIT birincil kaynaktır.
            2. İLGİLİ LOG KESİTİ ikincil kaynaktır.
            3. SON 200 SATIR yalnızca destekleyici bağlamdır.
            Çelişki varsa YAPISAL KANIT'a güven.

            """ + RULES;

    private static final String ANSWER_FORMAT = """
            Yanıtı yalnızca şu JSON nesnesi olarak ver, başka metin ekleme:
            {"rootCause": "...", "file": "...", "line": 0, "method": "...", "actions": ["..."]}
            - rootCause ve actions Türkçe yazılır (teknik ifadeler aynen kalır).
            - rootCause: en fazla 2 kısa cümle.
            - file, line, method: hatanın oluştuğu test/uygulama kodu (YAPISAL KANIT'taki Hata Dosyası:Satırı \
            ve Step Definition). file yalnızca dosya adı, yol/paket/satır olmadan (ör. "LoginSteps.java"); \
            line yalnızca sayı; method yalnızca metot adı (ör. "submitLogin"). Kanıtta yoksa null.
            - actions: en fazla 3 kısa madde, emir kipinde, doğrudan kanıttan türetilmiş: neyin nerede nasıl \
            düzeltileceğini söyle (hangi dosya/satır/locator/değer). Tek başına "incele", "doğrula", \
            "tekrar çalıştır" gibi genel maddeler yazma; 3'ü doldurmak zorunda değilsin.""";

    private static final String GROUP_INSTRUCTIONS = """
            Sen bir Jenkins build hatası karar destek sistemisin. Bu build'de birden fazla test başarısız oldu. \
            Başarısız testler uygulama tarafından sayıldı ve aynı hata imzasına (exception türü, mesaj, hata konumu) \
            göre HATA GRUPLARI'na ayrıldı; bu sayıları ve test listelerini değiştirme. Rapor yazma; QA ve \
            geliştiriciye her grup için "Ne oldu? Hangi koşulda? Hangi kanıt gösteriyor? Ne yapmalıyım?" sorularını kısa cevapla.
            Metinleri Türkçe yaz; exception, sınıf, metot, dosya adı ve locator gibi teknik ifadeleri aynen koru.

            Kaynak: HATA GRUBU bölümlerindeki kanıt (temsilî stack trace ve log kesiti). TÜM HATA GRUPLARI \
            listesi yalnızca build geneli özet içindir.

            """ + RULES + "\n" + """
            - Aynı exception türü aynı kök neden demek değildir. Gruplar arasında ortak bir nedeni yalnızca kanıt \
            gösteriyorsa yaz; gösterilmiyorsa ortak nedenin kanıtlanamadığını söyle.
            - Bir grupta stack trace veya mesaj yoksa neden uydurma; kanıtın eksik olduğunu yaz.""";

    private static final String GROUP_ANSWER_FORMAT = """
            Yanıtı yalnızca şu JSON nesnesi olarak ver, başka metin ekleme:
            {"summary": "...", "groups": [{"id": "G1", "rootCause": "...", "actions": ["..."]}]}
            - summary, rootCause ve actions Türkçe yazılır (teknik ifadeler aynen kalır).
            - summary: build geneli, en fazla 3 kısa cümle: hangi hata grupları kaç testi etkiliyor; gruplar arasında \
            ortak bir neden ancak kanıtla gösterilebiliyorsa yaz, gösterilemiyorsa bunu söyle.
            - groups: bu istekte ayrıntısı verilen her HATA GRUBU için bir öğe; id aynen kullanılır (ör. "G1").
            - rootCause: en fazla 3 kısa cümle: ne oldu, hangi koşulda (adım, locator, değer, endpoint) ve hangi \
            kanıta dayanarak.
            - actions: en fazla 3 kısa madde, öncelik sırasıyla, emir kipinde, doğrudan kanıttan türetilmiş: neyin \
            nerede nasıl düzeltileceğini söyle (hangi dosya/satır/locator/değer). Tek başına "incele", "doğrula", \
            "tekrar çalıştır" gibi genel maddeler yazma; 3'ü doldurmak zorunda değilsin.""";

    /** Source-file extension → language, taken from evidence (not guessed) so code matches the project. */
    private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.of(
            "java", "Java", "kt", "Kotlin", "groovy", "Groovy", "scala", "Scala",
            "cs", "C#", "py", "Python", "js", "JavaScript", "ts", "TypeScript", "rb", "Ruby");

    private record Secret(Pattern pattern, String replacement) {
    }

    private static final String SECRET_NAME = "(?:password|passwd|passphrase|pwd|secret(?:[_-]?key)?|private[_-]?key"
            + "|token|api[_-]?key|access[_-]?key|client[_-]?secret)";

    /**
     * Credentials a log may carry; they never leave the application. A value is masked only when it is there
     * (an empty password stays visibly empty), never across a line break, and {@code name:value} without a space
     * after the colon is not a credential ({@code LoginTest.invalidPassword:57} keeps its line number). A JSON field
     * named like a credential is masked even when it holds a validation message — the price of not leaking one.
     */
    private static final List<Secret> SECRETS = List.of(
            // JSON, also escaped inside a string: "password":"..." / \"password\":\"...\"
            new Secret(Pattern.compile("(?i)(\\\\?\"" + SECRET_NAME + "\\\\?\"[ \\t]*:[ \\t]*\\\\?\")[^\"\\\\]+"), "$1***"),
            // password=..., -Dtoken: ..., DB_PASSWORD=..., x-api-key: ...
            new Secret(Pattern.compile("(?i)(" + SECRET_NAME + "[\"']?[ \\t]*(?:=|:(?=[\\s\"']))[ \\t]*[\"']?)[^\\s\"',;&\\])]+"), "$1***"),
            // a Gherkin key/value table row: | password | S3cret |
            new Secret(Pattern.compile("(?i)(\\|[ \\t]*" + SECRET_NAME + "[ \\t]*\\|[ \\t]*)[^|\\s][^|]*?([ \\t]*\\|)"), "$1***$2"),
            // Authorization: Basic ... / Authorization=Bearer ... / "Authorization": "Basic ..."
            new Secret(Pattern.compile("(?i)(authorization[\"']?[ \\t]*[=:][ \\t]*[\"']?(?:bearer|basic)[ \\t]+)[^\\s\"']+"), "$1***"),
            new Secret(Pattern.compile("(?i)(\\bbearer[ \\t]+)[\\w.~+/-]{20,}=*"), "$1***"),
            // https://user:password@host — the password may itself contain '@'
            new Secret(Pattern.compile("(://[^/\\s:@]+:)[^/\\s]*@"), "$1***@"),
            // curl -u user:password
            new Secret(Pattern.compile("(?i)((?:^|\\s)(?:-u|--user)[ \\t]+[^\\s:]+:)\\S+"), "$1***"));

    /** One LLM request of a multi-failure analysis: the prompt and the groups it covers in detail. */
    public record GroupPrompt(String prompt, List<FailureGroup> groups) {
    }

    private final AnalysisLimits limits;

    public String build(BuildAnalysisContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(INSTRUCTIONS).append("\n\n");

        String evidence = String.join("\n", evidenceFacts(context));
        if (hasText(context.stackTrace())) {
            evidence += "\nStack Trace:\n" + context.stackTrace().strip();
        }
        appendSection(prompt, EVIDENCE_LABEL, evidence);
        appendSection(prompt, SNIPPET_LABEL, context.relevantLogSnippet());
        appendSection(prompt, TAIL_LABEL, clipLines(context.last200Lines()));

        prompt.append(ANSWER_FORMAT);
        return maskSecrets(prompt.toString());
    }

    /**
     * The prompts for a build with several failed tests, largest groups first. Groups are packed into one
     * prompt while it stays within {@link AnalysisLimits#getMaxPromptChars()} and
     * {@link AnalysisLimits#getMaxGroupsPerCall()}; at most {@link AnalysisLimits#getMaxLlmCalls()} prompts are
     * made. Groups in none of them are over the limit: the caller marks them as not analysed.
     */
    public List<GroupPrompt> buildGroupPrompts(BuildAnalysisContext context) {
        List<FailureGroup> groups = context.failureGroups();
        String index = groupIndex(groups);
        int fixedChars = GROUP_INSTRUCTIONS.length() + buildFacts(context, groups).length() + index.length()
                + GROUP_ANSWER_FORMAT.length() + 100;   // + section frames and the "groups in this request" line

        List<List<FailureGroup>> batches = new ArrayList<>();
        List<FailureGroup> batch = new ArrayList<>();
        int batchChars = 0;
        for (FailureGroup group : groups) {
            int groupChars = groupDetails(group).length() + group.id().length() + 2;   // its details + its id in that line
            boolean full = batch.size() == limits.getMaxGroupsPerCall()
                    || (!batch.isEmpty() && fixedChars + batchChars + groupChars > limits.getMaxPromptChars());
            if (full) {
                batches.add(batch);
                if (batches.size() == limits.getMaxLlmCalls()) {
                    batch = List.of();
                    break;
                }
                batch = new ArrayList<>();
                batchChars = 0;
            }
            batch.add(group);
            batchChars += groupChars;
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }

        List<GroupPrompt> prompts = new ArrayList<>();
        for (int i = 0; i < batches.size(); i++) {
            List<FailureGroup> inThisPrompt = batches.get(i);
            StringBuilder prompt = new StringBuilder();
            prompt.append(GROUP_INSTRUCTIONS).append("\n\n");
            String facts = buildFacts(context, groups) + "\n- Bu İstekte Ayrıntısı Verilen Gruplar: "
                    + inThisPrompt.stream().map(FailureGroup::id).collect(Collectors.joining(", "))
                    + (batches.size() > 1 ? " (istek " + (i + 1) + "/" + batches.size() + ")" : "");
            appendSection(prompt, BUILD_LABEL, facts);
            prompt.append(index);
            inThisPrompt.forEach(group -> prompt.append(groupDetails(group)));
            prompt.append(GROUP_ANSWER_FORMAT);
            prompts.add(new GroupPrompt(maskSecrets(prompt.toString()), inThisPrompt));
        }
        return prompts;
    }

    /** One "- Etiket: değer" line per fact the parser found, most decisive first. */
    private List<String> evidenceFacts(BuildAnalysisContext context) {
        List<String> facts = new ArrayList<>();
        addFact(facts, "Build Durumu", context.buildStatus());
        addFact(facts, "Hata Kategorisi", context.errorCategory() == null ? null : context.errorCategory().name());
        addFact(facts, "Test Özeti", summaryText(context.testSummary()));
        failureFacts(facts, context.failedScenario(), context.failedStep(), context.failureLocation(),
                context.exceptionType(), context.failedInteraction());
        return facts;
    }

    /** The facts of one failure: where it failed (scenario, step, code) and with what (exception, UI interaction). */
    private void failureFacts(List<String> facts, FailedScenario scenario, FailedStep step, FailureLocation location,
                              String exceptionType, FailedInteraction interaction) {
        if (scenario != null) {
            addFact(facts, "Senaryo", scenario.name() + position(scenario.featureFile(), scenario.featureLine()));
        }
        if (step != null) {
            addFact(facts, "Başarısız Adım", step.text() + position(step.featureFile(), step.featureLine()));
        }
        if (location != null) {
            addFact(facts, "Step Definition", location.stepDefinition());
            if (location.file() != null) {
                addFact(facts, "Hata Dosyası:Satırı",
                        location.line() == null ? location.file() : location.file() + ":" + location.line());
            }
        }
        addFact(facts, "Exception", exceptionType);

        if (interaction != null) {
            addFact(facts, "Otomasyon Aracı", interaction.tool());
            addFact(facts, "Başarısız Komut", interaction.command());
            addFact(facts, "Locator Tipi", interaction.locatorType());
            addFact(facts, "Locator Değeri", interaction.locatorValue());
        }
        if (location != null) {
            addFact(facts, "Kod Dili", languageOf(location.file()));
        }
    }

    private String buildFacts(BuildAnalysisContext context, List<FailureGroup> groups) {
        List<String> facts = new ArrayList<>();
        addFact(facts, "Build Durumu", context.buildStatus());
        addFact(facts, "Hata Kategorisi", context.errorCategory() == null ? null : context.errorCategory().name());
        addFact(facts, "Test Özeti", summaryText(context.testSummary()));
        addFact(facts, "Logda Bulunan Başarısız Test", context.failureCount() + " (" + groups.size() + " hata grubu)");
        return String.join("\n", facts);
    }

    /** One line per group (id, size, signature), so the build summary can speak about every group. */
    private String groupIndex(List<FailureGroup> groups) {
        List<String> lines = new ArrayList<>();
        groups.stream().limit(MAX_INDEX_GROUPS).forEach(group -> lines.add(clip(
                "- " + group.id() + ": " + group.failures().size() + " test — " + group.signature(), MAX_INDEX_LINE_LENGTH)));
        if (groups.size() > MAX_INDEX_GROUPS) {
            List<FailureGroup> rest = groups.subList(MAX_INDEX_GROUPS, groups.size());
            lines.add("- ... ve " + rest.size() + " grup daha ("
                    + rest.stream().mapToInt(group -> group.failures().size()).sum() + " test)");
        }
        return "=== " + INDEX_LABEL + " ===\n" + String.join("\n", lines) + "\n\n";
    }

    /** One group in detail: signature, the representative's facts, every affected test (counted), one stack trace. */
    private String groupDetails(FailureGroup group) {
        TestFailure representative = group.representative();
        List<String> facts = new ArrayList<>();
        addFact(facts, "Hata İmzası", group.signature());
        failureFacts(facts, null, representative.step(), representative.failureLocation(),
                representative.exceptionType(), representative.failedInteraction());
        facts.replaceAll(fact -> clip(fact, MAX_TAIL_LINE_LENGTH));      // a huge step argument or locator stays bounded

        List<TestFailure> failures = group.failures();
        facts.add("- Etkilenen Testler (" + failures.size() + "):");
        failures.stream().limit(MAX_TESTS_PER_GROUP).forEach(failure -> facts.add("  - " + testName(failure)));
        if (failures.size() > MAX_TESTS_PER_GROUP) {
            facts.add("  - ... ve " + (failures.size() - MAX_TESTS_PER_GROUP) + " test daha");
        }

        String details = String.join("\n", facts);
        if (hasText(representative.stackTrace())) {
            details += "\nStack Trace (temsilî):\n" + clipTrace(representative.stackTrace());
        }
        if (hasText(representative.logExcerpt())) {
            details += "\nLog Kesiti (temsilî):\n" + clipLines(representative.logExcerpt());
        }
        return "=== HATA GRUBU " + group.id() + " — " + failures.size() + " başarısız test ===\n" + details + "\n\n";
    }

    /** {@code Open Google (src/.../Example.feature:4)}; a test known only by its location is shown by it. */
    private String testName(TestFailure failure) {
        FailedScenario scenario = failure.scenario();
        String name = scenario.name() != null ? scenario.name() : "(adı logda yok)";
        return clip(name + position(scenario.featureFile(), scenario.featureLine()), MAX_INDEX_LINE_LENGTH);
    }

    /** {@code toplam 30, başarılı 20, başarısız 10, atlanan 0} — only the counts the log printed. */
    private static String summaryText(TestSummary summary) {
        if (summary == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (summary.total() != null) {
            parts.add("toplam " + summary.total());
        }
        if (summary.passed() != null) {
            parts.add("başarılı " + summary.passed());
        }
        if (summary.failed() != null) {
            parts.add("başarısız " + summary.failed());
        }
        if (summary.skipped() != null) {
            parts.add("atlanan " + summary.skipped());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private String languageOf(String file) {
        if (file == null || file.lastIndexOf('.') < 0) {
            return null;
        }
        return LANGUAGE_BY_EXTENSION.get(file.substring(file.lastIndexOf('.') + 1).toLowerCase());
    }

    private void addFact(List<String> facts, String label, String value) {
        if (hasText(value)) {
            facts.add("- " + label + ": " + value.strip());
        }
    }

    private String position(String file, Integer line) {
        if (file == null) {
            return "";
        }
        return line == null ? " (" + file + ")" : " (" + file + ":" + line + ")";
    }

    private static String clipLines(String text) {
        if (text == null) {
            return null;
        }
        return text.lines()
                .map(line -> clip(line, MAX_TAIL_LINE_LENGTH))
                .collect(Collectors.joining("\n"));
    }

    /** The first lines of a stack trace (header, message, top frames), long lines clipped. */
    private static String clipTrace(String stackTrace) {
        List<String> lines = stackTrace.strip().lines().toList();
        String kept = clipLines(String.join("\n", lines.subList(0, Math.min(lines.size(), MAX_GROUP_TRACE_LINES))));
        return lines.size() <= MAX_GROUP_TRACE_LINES ? kept
                : kept + "\n... (" + (lines.size() - MAX_GROUP_TRACE_LINES) + " satır daha)";
    }

    private static String clip(String line, int maxLength) {
        return line.length() <= maxLength ? line : line.substring(0, maxLength) + "…";
    }

    private static String maskSecrets(String prompt) {
        String masked = prompt;
        for (Secret secret : SECRETS) {
            masked = secret.pattern().matcher(masked).replaceAll(secret.replacement());
        }
        return masked;
    }

    private void appendSection(StringBuilder prompt, String label, String value) {
        if (!hasText(value)) {
            return;
        }
        prompt.append("=== ").append(label).append(" ===\n").append(value.strip()).append("\n\n");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
