package com.company.buildanalyzer.application.prompt;

import com.company.buildanalyzer.domain.model.BuildAnalysisContext;
import com.company.buildanalyzer.domain.model.ErrorCategory;
import com.company.buildanalyzer.domain.model.FailedInteraction;
import com.company.buildanalyzer.domain.model.FailedScenario;
import com.company.buildanalyzer.domain.model.FailedStep;
import com.company.buildanalyzer.domain.model.FailureLocation;
import com.company.buildanalyzer.domain.model.TestSummary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PromptBuilderTest {

    private final PromptBuilder promptBuilder = new PromptBuilder(new AnalysisLimits());

    private static BuildAnalysisContext fullContext() {
        return new BuildAnalysisContext(
                "FAILURE",
                new FailedScenario("Open Google", "src/test/resources/features/Example.feature", 4),
                new FailedStep("Arama kutusuna tıkla.", "Example.feature", 6),
                "org.openqa.selenium.NoSuchElementException",
                ErrorCategory.SELENIUM,
                "org.openqa.selenium.NoSuchElementException: no such element\nSession ID: abc\n\tat x.Y.z(Y.java:1)",
                new FailureLocation("stepdefinitions.ExampleSteps.clickSearchBox", "ExampleSteps.java", 28),
                new FailedInteraction("Selenium", "findElement", "name", "qqqqqqqq"),
                "[ERROR] Tests run: 1, Errors: 1\nFinished: FAILURE",
                "RAW TAIL line 1\nRAW TAIL line 2",
                null,
                List.of()
        );
    }

    @Test
    void ordersEvidenceThenSnippetThenTailAndDeclaresTheirPriority() {
        String prompt = promptBuilder.build(fullContext());

        int evidence = prompt.indexOf("=== 1. YAPISAL KANIT (Structured Evidence) ===");
        int snippet = prompt.indexOf("=== 2. İLGİLİ LOG KESİTİ (Relevant Log Snippet) ===");
        int tail = prompt.indexOf("=== 3. JENKINS LOG — SON 200 SATIR ===");
        assertThat(evidence).isPositive();
        assertThat(snippet).isGreaterThan(evidence);
        assertThat(tail).isGreaterThan(snippet);

        assertThat(prompt)
                .contains("YAPISAL KANIT birincil kaynaktır.")
                .contains("İLGİLİ LOG KESİTİ ikincil kaynaktır.")
                .contains("SON 200 SATIR yalnızca destekleyici bağlamdır.")
                .contains("Çelişki varsa YAPISAL KANIT'a güven.");
        // the raw tail is included (nothing the parser missed is lost), after the snippet
        assertThat(prompt.substring(snippet, tail)).contains("[ERROR] Tests run: 1, Errors: 1");
        assertThat(prompt.substring(tail)).contains("RAW TAIL line 1\nRAW TAIL line 2");
    }

    @Test
    void structuredEvidenceListsEveryParserFactAndTheFullStackTrace() {
        String prompt = promptBuilder.build(fullContext());
        String evidence = prompt.substring(
                prompt.indexOf("=== 1. YAPISAL KANIT"), prompt.indexOf("=== 2. İLGİLİ LOG KESİTİ"));

        assertThat(evidence)
                .contains("- Build Durumu: FAILURE")
                .contains("- Hata Kategorisi: SELENIUM")
                .contains("- Senaryo: Open Google (src/test/resources/features/Example.feature:4)")
                .contains("- Başarısız Adım: Arama kutusuna tıkla. (Example.feature:6)")
                .contains("- Step Definition: stepdefinitions.ExampleSteps.clickSearchBox")
                .contains("- Hata Dosyası:Satırı: ExampleSteps.java:28")
                .contains("- Exception: org.openqa.selenium.NoSuchElementException")
                .contains("- Otomasyon Aracı: Selenium")
                .contains("- Başarısız Komut: findElement")
                .contains("- Locator Tipi: name")
                .contains("- Locator Değeri: qqqqqqqq")
                .contains("- Kod Dili: Java")
                .contains("Stack Trace:\norg.openqa.selenium.NoSuchElementException: no such element\nSession ID: abc\n\tat x.Y.z(Y.java:1)");
    }

    @Test
    void actsAsDecisionSupportAndForbidsSpeculationAndTutorials() {
        assertThat(promptBuilder.build(fullContext()))
                .contains("karar destek sistemisin. Rapor yazma")
                .contains("Neden patladı? Nerede patladı? Ne yapmalıyım?")
                .contains("Yalnızca kanıtta geçenleri yaz.")
                .contains("\"iframe olabilir\"")
                .contains("\"cache sorunu olabilir\"")
                .contains("\"wait eklenmeli\"")
                .contains("Teknoloji veya framework tahmini yapma. Root cause uydurma.")
                .contains("\"Selenium/API/timeout nedir\"")
                .contains("Kötü: \"Element bulunamadı.\"")
                .contains("Türkçe");
    }

    @Test
    void asksForTheRootCauseJsonAsTheLastThing() {
        String prompt = promptBuilder.build(fullContext());
        String format = prompt.substring(prompt.indexOf("Yanıtı yalnızca şu JSON nesnesi olarak ver"));

        assertThat(format)
                .contains("{\"rootCause\": \"...\", \"file\": \"...\", \"line\": 0, \"method\": \"...\", \"actions\": [\"...\"]}")
                .contains("rootCause: en fazla 2 kısa cümle")
                .contains("Kanıtta yoksa null")
                .contains("file yalnızca dosya adı, yol/paket/satır olmadan")
                .contains("Tek başına \"incele\", \"doğrula\", \"tekrar çalıştır\" gibi genel maddeler yazma")
                .contains("actions: en fazla 3 kısa madde")
                .doesNotContain("GÜVEN")
                .doesNotContain("TEKNİK AÇIKLAMA");
        // the answer format is the last thing the model reads
        assertThat(prompt.indexOf("=== 3. JENKINS LOG")).isLessThan(prompt.indexOf("Yanıtı yalnızca şu JSON"));
        assertThat(prompt).endsWith("3'ü doldurmak zorunda değilsin.");
    }

    @Test
    void listsOnlyTheFactsThatWereFound() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE", null, null, "java.lang.AssertionError", ErrorCategory.UNKNOWN, "java.lang.AssertionError: x",
                new FailureLocation("com.acme.LoginTest.login", "LoginTest.kt", 14), null, "snippet", "tail", null, List.of());

        String prompt = promptBuilder.build(context);

        assertThat(prompt)
                .contains("- Step Definition: com.acme.LoginTest.login")
                .contains("- Hata Dosyası:Satırı: LoginTest.kt:14")
                .contains("- Kod Dili: Kotlin")
                .doesNotContain("Başarısız Adım")
                .doesNotContain("Locator")
                .doesNotContain("Otomasyon Aracı")
                .doesNotContain("Senaryo:");
        // no absent fact is sent as "null" (the answer format itself does mention null)
        assertThat(prompt.substring(prompt.indexOf("=== 1."), prompt.indexOf("Yanıtı yalnızca şu JSON")))
                .doesNotContain("null");
    }

    @Test
    void skipsEmptySectionsButKeepsTheEvidenceAndFormat() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE", null, null, null, ErrorCategory.MAVEN, null, null, null, null, "   ", null, List.of());

        String prompt = promptBuilder.build(context);

        assertThat(prompt)
                .contains("=== 1. YAPISAL KANIT (Structured Evidence) ===\n- Build Durumu: FAILURE\n- Hata Kategorisi: MAVEN")
                .doesNotContain("Stack Trace:")
                .doesNotContain("=== 2.")
                .doesNotContain("=== 3.")
                .contains("\"rootCause\"");
    }

    @Test
    void clipsOnlyVeryLongLinesOfTheRawTail() {
        String longLine = "data:image/png;base64," + "A".repeat(2_000);
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE", null, null, null, ErrorCategory.UNKNOWN, null, null, null, null, "short line\n" + longLine,
                null, List.of());

        String prompt = promptBuilder.build(context);

        assertThat(prompt)
                .contains("short line\n" + longLine.substring(0, PromptBuilder.MAX_TAIL_LINE_LENGTH) + "…")
                .doesNotContain(longLine);
    }

    @Test
    void listsTheTestCountsTheLogPrinted() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE", null, null, null, ErrorCategory.UNKNOWN, null, null, null, null, "tail",
                new TestSummary(null, 20, 3, 1), List.of());

        assertThat(promptBuilder.build(context))
                .contains("- Hata Kategorisi: UNKNOWN\n- Test Özeti: başarılı 20, başarısız 3, atlanan 1\n");
    }

    @Test
    void masksCredentialsBeforeTheLogLeavesTheApplication() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE", null, null, null, ErrorCategory.MAVEN, null, null, null,
                """
                        + mvn deploy -Dpassword=hunter2 -Dapi_key=sk-live-123 -Dtoken: abc.def
                        Authorization: Basic dXNlcjpwYXNz
                        curl -H "Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig"
                        Cloning https://ci-bot:gh_s3cr3t@github.com/acme/shop.git
                        {"user":"qa","password":"P@ss w0rd"}""",
                "tail with secret=xyz", null, List.of());

        String prompt = promptBuilder.build(context);

        assertThat(prompt)
                .contains("-Dpassword=*** -Dapi_key=*** -Dtoken: ***")
                .contains("Authorization: Basic ***")
                .contains("Authorization: Bearer ***")
                .contains("https://ci-bot:***@github.com/acme/shop.git")
                .contains("{\"user\":\"qa\",\"password\":\"***\"}")
                .contains("tail with secret=***")
                .doesNotContain("hunter2", "sk-live-123", "abc.def", "dXNlcjpwYXNz", "eyJhbGciOiJIUzI1NiJ9", "gh_s3cr3t",
                        "w0rd", "xyz");
    }

    /** REST-assured request logs, escaped JSON, key/value tables, curl -u and a password containing '@'. */
    @Test
    void masksTheCredentialFormatsOfTestLogs() {
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE", null, null, null, ErrorCategory.UNKNOWN, null, null, null,
                """
                        Headers:\t\tAuthorization=Basic cWEtYm90OlMzY3JldCFQYXNz
                        {"Authorization": "Bearer abc.def.ghi"}
                        Body: {\\"username\\":\\"qa\\",\\"password\\":\\"S3cret!Pass\\"}
                        aws.secretKey=wJalrXUtnFEMI SECRET_KEY=K7MDENG privateKey=MIIEvQ passphrase=hunter2
                        curl -u admin:S3cretPass https://jenkins.local/api
                        https://qa:p@ss@hub.local/wd/hub
                              | password | S3cretPass |""",
                "tail", null, List.of());

        String prompt = promptBuilder.build(context);

        assertThat(prompt)
                .contains("Authorization=Basic ***")
                .contains("{\"Authorization\": \"Bearer ***\"}")
                .contains("\\\"password\\\":\\\"***\\\"")
                .contains("aws.secretKey=*** SECRET_KEY=*** privateKey=*** passphrase=***")
                .contains("curl -u admin:*** https://jenkins.local/api")
                .contains("https://qa:***@hub.local/wd/hub")
                .contains("| password | *** |")
                .doesNotContain("cWEtYm90", "abc.def.ghi", "S3cret!Pass", "wJalr", "K7MDENG", "MIIEvQ", "hunter2", "S3cretPass", "ss@hub");
    }

    /** What only looks like a credential stays: line numbers, an empty value, the next line, CSS selectors, brackets. */
    @Test
    void keepsEvidenceThatIsNotACredential() {
        String evidence = """
                [ERROR]   LoginTest.invalidPassword:57 expected [Hoş geldiniz] but found [Hatalı şifre]
                {"username":"qa","password":""}
                Kullanıcı şifresini girer, password:
                \tat stepdefinitions.LoginSteps.enterPassword(LoginSteps.java:40)
                By.cssSelector: #password:focus""";
        BuildAnalysisContext context = new BuildAnalysisContext(
                "FAILURE", null, null, null, ErrorCategory.UNKNOWN, null, null, null,
                evidence + "\njava.lang.AssertionError: expected [token: valid] but found [token: expired]", "tail", null, List.of());

        String prompt = promptBuilder.build(context);

        assertThat(prompt)
                .contains(evidence)
                .contains("expected [token: ***] but found [token: ***]");
    }
}
