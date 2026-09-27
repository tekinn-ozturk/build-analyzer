package com.company.buildanalyzer.application.prompt;

import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureLocation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 */
@Service
public class PromptBuilder {

    static final String EVIDENCE_LABEL = "1. YAPISAL KANIT (Structured Evidence)";
    static final String SNIPPET_LABEL = "2. İLGİLİ LOG KESİTİ (Relevant Log Snippet)";
    static final String TAIL_LABEL = "3. JENKINS LOG — SON 200 SATIR";

    /** Very long raw lines (base64 screenshots, capability dumps) are clipped in the tail only. */
    static final int MAX_TAIL_LINE_LENGTH = 500;

    private static final String INSTRUCTIONS = """
            Sen bir Jenkins build hatası karar destek sistemisin. Rapor yazma; QA ve geliştiriciye \
            "Neden patladı? Nerede patladı? Ne yapmalıyım?" sorularını 10 saniyede okunacak kadar kısa cevapla.
            Metinleri Türkçe yaz; exception, sınıf, metot, dosya adı ve locator gibi teknik ifadeleri aynen koru.

            Kaynak önceliği:
            1. YAPISAL KANIT birincil kaynaktır.
            2. İLGİLİ LOG KESİTİ ikincil kaynaktır.
            3. SON 200 SATIR yalnızca destekleyici bağlamdır.
            Çelişki varsa YAPISAL KANIT'a güven.

            Kurallar:
            - Yalnızca kanıtta geçenleri yaz. Kanıtta geçmeyen olasılık ve tahmin yazma \
            (ör. "iframe olabilir", "uygulama değişmiş olabilir", "cache sorunu olabilir", "wait eklenmeli").
            - Teknoloji veya framework tahmini yapma. Root cause uydurma.
            - Teknik açıklama, eğitim metni veya "Selenium/API/timeout nedir" gibi öğretici cümleler yazma.
            - Root cause spesifik olsun: locator, değer, exception, dosya adı ver. \
            Kötü: "Element bulunamadı." İyi: "id=login-btn locator'ı bulunamadı; NoSuchElementException oluştu.\"""";

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

    /** Source-file extension → language, taken from evidence (not guessed) so code matches the project. */
    private static final Map<String, String> LANGUAGE_BY_EXTENSION = Map.of(
            "java", "Java", "kt", "Kotlin", "groovy", "Groovy", "scala", "Scala",
            "cs", "C#", "py", "Python", "js", "JavaScript", "ts", "TypeScript", "rb", "Ruby");

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
        return prompt.toString();
    }

    /** One "- Etiket: değer" line per fact the parser found, most decisive first. */
    private List<String> evidenceFacts(BuildAnalysisContext context) {
        List<String> facts = new ArrayList<>();
        addFact(facts, "Build Durumu", context.buildStatus());
        addFact(facts, "Hata Kategorisi", context.errorCategory() == null ? null : context.errorCategory().name());

        FailedScenario scenario = context.failedScenario();
        if (scenario != null) {
            addFact(facts, "Senaryo", scenario.name() + position(scenario.featureFile(), scenario.featureLine()));
        }
        FailedStep step = context.failedStep();
        if (step != null) {
            addFact(facts, "Başarısız Adım", step.text() + position(step.featureFile(), step.featureLine()));
        }
        FailureLocation location = context.failureLocation();
        if (location != null) {
            addFact(facts, "Step Definition", location.stepDefinition());
            if (location.file() != null) {
                addFact(facts, "Hata Dosyası:Satırı",
                        location.line() == null ? location.file() : location.file() + ":" + location.line());
            }
        }
        addFact(facts, "Exception", context.exceptionType());

        FailedInteraction interaction = context.failedInteraction();
        if (interaction != null) {
            addFact(facts, "Otomasyon Aracı", interaction.tool());
            addFact(facts, "Başarısız Komut", interaction.command());
            addFact(facts, "Locator Tipi", interaction.locatorType());
            addFact(facts, "Locator Değeri", interaction.locatorValue());
        }
        if (location != null) {
            addFact(facts, "Kod Dili", languageOf(location.file()));
        }
        return facts;
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
                .map(line -> line.length() <= MAX_TAIL_LINE_LENGTH ? line : line.substring(0, MAX_TAIL_LINE_LENGTH) + "…")
                .collect(Collectors.joining("\n"));
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
