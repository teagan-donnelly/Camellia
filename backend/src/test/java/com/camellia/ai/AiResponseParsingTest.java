package com.camellia.ai;

import com.camellia.ai.AiDtos.RawAnswer;
import com.camellia.ai.AiDtos.RawObservation;
import com.camellia.integration.gemini.GeminiException;
import com.camellia.model.ClinicalRecord;
import com.camellia.model.RecordType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AiResponseParsingTest {

    @Test
    void parsesStructuredAnswerIncludingCodeFencesAndUnknownFields() {
        String json = """
                ```json
                {"answer":"ok [REC-A]","documentedFacts":[{"statement":"s","sourceRecordIds":["REC-A"]}],
                 "interpretation":[],"missingInformation":["m"],"suggestedFollowUpQuestions":[],"extraField":true}
                ```""";
        RawAnswer a = ClinicalAiService.parse(json, RawAnswer.class);
        assertThat(a.answer()).isEqualTo("ok [REC-A]");
        assertThat(a.documentedFacts().getFirst().sourceRecordIds()).containsExactly("REC-A");
        assertThat(a.missingInformation()).containsExactly("m");
    }

    @Test
    void malformedModelOutputBecomesBadGatewayError() {
        assertThatThrownBy(() -> ClinicalAiService.parse("not json at all", RawAnswer.class))
                .isInstanceOf(GeminiException.class)
                .satisfies(e -> assertThat(((GeminiException) e).code()).isEqualTo("GEMINI_UNPARSEABLE"));
    }

    @Test
    void extractedObservationsMustQuoteTheirSourceVerbatim() {
        ClinicalRecord r = new ClinicalRecord("REC-1", "P1", "ORG", null, RecordType.NOTE, "Prenatal note", LocalDateTime.now(),
                "Dr. A", "Obstetrics", "Repeat platelet count 86 x10^3/uL (Northgate). Hematology referral placed.", "LEGACY_JSON",
                "1", "", "|", LocalDateTime.now());
        Map<String, ClinicalRecord> sent = Map.of("REC-1", r);
        assertThat(ClinicalAiService.validateObservation(
                new RawObservation("LAB_FINDING", "Platelets 86", "REC-1", "repeat platelet count 86  x10^3/uL"), sent)).isNull();
        assertThat(ClinicalAiService.validateObservation(
                new RawObservation("LAB_FINDING", "Platelets 50", "REC-1", "platelet count 50"), sent)).contains("Quote not found");
        assertThat(ClinicalAiService.validateObservation(
                new RawObservation("LAB_FINDING", "x", "REC-OTHER", "Hematology referral placed"), sent)).contains("not among the provided records");
        assertThat(ClinicalAiService.validateObservation(
                new RawObservation("DIAGNOSIS", "x", "REC-1", "Hematology referral placed"), sent)).contains("Unknown category");
    }
}
