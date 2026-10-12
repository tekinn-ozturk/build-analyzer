package com.company.buildanalyzer.application.analysis;

import com.company.buildanalyzer.domain.model.FailureGroupAnalysis;
import com.company.buildanalyzer.domain.model.RootCauseAnalysis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RootCauseParserTest {

    private final RootCauseParser parser = new RootCauseParser();

    @Test
    void parsesTheStructuredAnswer() {
        String answer = """
                {"rootCause":"name=qqqqqqqq locator'ı bulunamadı. NoSuchElementException oluştu.",
                 "file":"ExampleSteps.java","line":28,"method":"clickSearchBox",
                 "actions":["Locator'ı doğrula.","Sayfadaki gerçek name değerini kontrol et."]}""";

        assertThat(parser.parse(answer)).contains(new RootCauseAnalysis(
                "name=qqqqqqqq locator'ı bulunamadı. NoSuchElementException oluştu.",
                "ExampleSteps.java", 28, "clickSearchBox",
                List.of("Locator'ı doğrula.", "Sayfadaki gerçek name değerini kontrol et.")));
    }

    @Test
    void acceptsAJsonFenceOrTextAroundTheObject() {
        String answer = """
                İşte analiz:
                ```json
                {"rootCause":"x","file":null,"line":null,"method":null,"actions":[]}
                ```""";

        assertThat(parser.parse(answer)).map(RootCauseAnalysis::rootCause).contains("x");
    }

    @Test
    void normalisesValues() {
        String answer = """
                {"rootCause":"  satır 1\\nsatır 2  ","file":"  ","line":0,"method":"null",
                 "actions":["- birinci","2) ikinci","","üçüncü","dördüncü"]}""";

        RootCauseAnalysis analysis = parser.parse(answer).orElseThrow();

        assertThat(analysis.rootCause()).isEqualTo("satır 1 satır 2");
        assertThat(analysis.file()).isNull();
        assertThat(analysis.line()).as("0 means unknown").isNull();
        assertThat(analysis.method()).isNull();
        assertThat(analysis.actions()).containsExactly("birinci", "ikinci", "üçüncü");
    }

    /** Real gpt-5-mini answers seen on the sample builds. */
    @Test
    void reducesFileToItsNameAndMethodToItsSimpleName() {
        RootCauseAnalysis packaged = parser.parse("""
                {"rootCause":"x","file":"com.acme.orders.steps.OrderSteps.java:64","line":64,
                 "method":"com.acme.orders.steps.OrderSteps.statusShouldBe()","actions":[]}""").orElseThrow();
        assertThat(packaged.file()).isEqualTo("OrderSteps.java");
        assertThat(packaged.line()).isEqualTo(64);
        assertThat(packaged.method()).isEqualTo("statusShouldBe");

        RootCauseAnalysis pathed = parser.parse("""
                {"rootCause":"x",
                 "file":"/C:/ProgramData/Jenkins/.jenkins/workspace/Checkout-API-Tests/src/test/java/com/acme/checkout/steps/PaymentSteps.java",
                 "line":47,"method":null,"actions":[]}""").orElseThrow();
        assertThat(pathed.file()).isEqualTo("PaymentSteps.java");

        RootCauseAnalysis lineInFile = parser.parse("""
                {"rootCause":"x","file":"C:\\\\ws\\\\pom.xml:12:5","line":null,"method":"m(int)","actions":[]}""")
                .orElseThrow();
        assertThat(lineInFile.file()).isEqualTo("pom.xml");
        assertThat(lineInFile.line()).as("taken from the file suffix").isEqualTo(12);
        assertThat(lineInFile.method()).as("a signature is kept").isEqualTo("m(int)");
    }

    @Test
    void acceptsTheLineAsAString() {
        assertThat(parser.parse("{\"rootCause\":\"x\",\"line\":\"28\"}").orElseThrow().line()).isEqualTo(28);
    }

    @Test
    void missingFieldsAreNullAndActionsEmpty() {
        RootCauseAnalysis analysis = parser.parse("{\"rootCause\":\"x\"}").orElseThrow();

        assertThat(analysis.file()).isNull();
        assertThat(analysis.line()).isNull();
        assertThat(analysis.method()).isNull();
        assertThat(analysis.actions()).isEmpty();
    }

    @Test
    void returnsEmptyForUnusableAnswers() {
        assertThat(parser.parse(null)).isEmpty();
        assertThat(parser.parse("KÖK NEDEN\nlocator bulunamadı")).isEmpty();
        assertThat(parser.parse("{\"rootCause\": broken")).isEmpty();
        assertThat(parser.parse("{\"rootCause\":\"  \",\"actions\":[\"a\"]}")).isEmpty();
    }

    @Test
    void parsesTheFailureGroupsAnswer() {
        RootCauseParser.GroupsAnswer answer = parser.parseGroups("""
                ```json
                {"summary": "10 testin 7'si G1 yüzünden düştü.\\nOrtak neden kanıtlanamadı.",
                 "groups": [
                   {"id": "G1", "rootCause": "#login-btn bulunamadı.", "actions": ["1. LoginSteps.java:31'deki locator'ı düzelt.", "b", "c", "d"]},
                   {"id": "G2", "rootCause": "  ", "actions": []},
                   {"id": null, "rootCause": "kimliksiz", "actions": []},
                   {"id": "G1", "rootCause": "tekrar", "actions": []}
                 ]}
                ```""").orElseThrow();

        assertThat(answer.summary()).isEqualTo("10 testin 7'si G1 yüzünden düştü. Ortak neden kanıtlanamadı.");
        // a group without id or root cause is left out (reported as unanswered); the first answer of an id counts
        assertThat(answer.groups()).containsOnlyKeys("G1");
        assertThat(answer.groups().get("G1")).isEqualTo(FailureGroupAnalysis.analyzed("G1", "#login-btn bulunamadı.",
                List.of("LoginSteps.java:31'deki locator'ı düzelt.", "b", "c")));
    }

    @Test
    void groupsAnswerWithoutAGroupsArrayIsUnusable() {
        assertThat(parser.parseGroups(null)).isEmpty();
        assertThat(parser.parseGroups("Gruplar analiz edilemedi.")).isEmpty();
        assertThat(parser.parseGroups("{\"summary\": \"x\"}")).isEmpty();
        assertThat(parser.parseGroups("{\"summary\": \"x\", \"groups\": []}")).hasValueSatisfying(answer ->
                assertThat(answer.groups()).isEmpty());
    }
}
