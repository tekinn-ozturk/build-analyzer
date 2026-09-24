package com.company.buildanalyzer.application.prompt;

import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import org.springframework.stereotype.Service;

/**
 * Turns a {@link BuildAnalysisContext} into the natural-language prompt that
 * will be sent to the LLM. It only assembles text — it performs no extraction,
 * classification or model call. Sections whose value is {@code null}/blank are
 * omitted so the model is never fed empty headings.
 *
 * <p>The prompt is written in Turkish (the app serves Turkish users) and
 * explicitly asks the model to answer in Turkish. Raw values such as build
 * status, error category and exception names are passed through unchanged.
 */
@Service
public class PromptBuilder {

    private static final String PERSONA = """
            Sen profesyonel bir QA Otomasyon Mühendisi ve CI/CD uzmanısın.
            Aşağıdaki Jenkins pipeline build hatasını analiz et ve yalnızca sağlanan \
            kanıtlara dayanarak net ve uygulanabilir bir teşhis üret.
            Yanıtını tamamen Türkçe yaz; exception, sınıf, metot, dosya adları ve \
            komutlar gibi teknik ifadeleri orijinal halleriyle koru.""";

    private static final String TASKS = """
            Yukarıdaki bilgilere dayanarak yanıtını aşağıdaki başlıklar altında ver:

            1. Kök Neden Analizi
            2. Teknik Açıklama
            3. QA Önerileri
            4. Geliştirici Önerileri
            5. Güven Seviyesi (Düşük / Orta / Yüksek)""";

    public String build(BuildAnalysisContext context) {
        StringBuilder prompt = new StringBuilder();
        prompt.append(PERSONA).append("\n\n");

        // Ordered as requested; each section is skipped when its value is absent.
        appendSection(prompt, "Build Durumu", context.buildStatus());
        appendSection(prompt, "Başarısız Senaryo", context.failedScenario());
        appendSection(prompt, "Hata Kategorisi",
                context.errorCategory() == null ? null : context.errorCategory().name());
        appendSection(prompt, "Exception Tipi", context.exceptionType());
        appendSection(prompt, "Stack Trace", context.stackTrace());
        appendSection(prompt, "Son 500 Log Satırı", context.last500Lines());

        prompt.append(TASKS);
        return prompt.toString();
    }

    private void appendSection(StringBuilder prompt, String label, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        prompt.append(label).append(":\n").append(value.strip()).append("\n\n");
    }
}
