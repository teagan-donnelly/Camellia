package com.camellia.ai;

import com.camellia.ai.AiDtos.AiAnswer;
import com.camellia.ai.AiDtos.ChatRequest;
import com.camellia.integration.gemini.GeminiGateway;
import com.camellia.model.CoordinationAlert;
import com.camellia.repository.AlertRepository;
import com.camellia.repository.FlagRepository;
import com.camellia.security.DemoDirectory;
import com.camellia.security.DemoUser;
import com.camellia.web.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the Gemini pipeline (retrieval → prompt → parse → citation validation) with a scripted gateway, so the
 * grounding guarantees are tested deterministically. Live API calls are verified separately via /api/integrations/gemini/check.
 */
@SpringBootTest(properties = {
        "camellia.database.mode=local",
        "camellia.database.local-url=jdbc:h2:mem:grounding-test;DB_CLOSE_DELAY=-1",
        "camellia.snowflake.account=",
        "camellia.gemini.api-key="
})
class GeminiGroundingIntegrationTest {

    static final List<GeminiGateway.Request> REQUESTS = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class FakeGemini {
        @Bean
        @Primary
        GeminiGateway scriptedGateway() {
            return new GeminiGateway() {
                public boolean isConfigured() { return true; }
                public String model() { return "scripted-test-model"; }
                public Reply generateJson(Request r) {
                    REQUESTS.add(r);
                    String text = """
                            {"answer":"Flagged because platelets were 86 [REC-NDL-ACC-55012-1] before an induction [REC-LRMC-A-90311]; see also [REC-MADE-UP-1].",
                             "documentedFacts":[{"statement":"Platelet count 86","sourceRecordIds":["REC-NDL-ACC-55012-1"]},
                                                {"statement":"Invented history","sourceRecordIds":["REC-MADE-UP-2"]}],
                             "interpretation":[{"statement":"May warrant advance anesthesia review.","sourceRecordIds":[]}],
                             "missingInformation":["No hematology consult note in the available records."],
                             "suggestedFollowUpQuestions":["Where did you find that information?"]}""";
                    return new Reply(text, "scripted-test-model", 100, 50, 5);
                }
            };
        }
    }

    static final DemoUser MAYA = DemoDirectory.require("maya.ellison");

    @Autowired ClinicalAiService ai;
    @Autowired AlertRepository alerts;
    @Autowired FlagRepository flags;

    @Test
    void chatSendsOnlyThePatientsRecordsAndStripsFabricatedCitations() {
        CoordinationAlert ava = alerts.find(null, null, "CAM-P-1001").getFirst();
        AiAnswer a = ai.chat(new ChatRequest(null, "ALERT", ava.alertId(), null, "Why was this patient flagged?"), MAYA);

        assertThat(a.mode()).isEqualTo("GEMINI");
        assertThat(a.aiGenerated()).isTrue();
        assertThat(a.answer()).doesNotContain("REC-MADE-UP-1").contains("REC-NDL-ACC-55012-1");
        assertThat(a.documentedFacts()).hasSize(1);
        assertThat(a.validation().citationsRemoved()).isEqualTo(2);
        assertThat(a.validation().uncitedFactsRemoved()).isEqualTo(1);
        assertThat(a.citations()).extracting(AiDtos.Citation::recordId).containsExactlyInAnyOrder("REC-NDL-ACC-55012-1", "REC-LRMC-A-90311");

        String prompt = REQUESTS.getLast().turns().getLast().text();
        assertThat(prompt).contains("REC-NDL-ACC-55012-1").contains("Ava Marlowe");
        assertThat(prompt).doesNotContain("Caleb").doesNotContain("REC-CHCH-ENC-3920");   // no other patients' data
        assertThat(REQUESTS.getLast().systemInstruction()).contains("Do not diagnose");

        // Conversation is bound to its context: reusing it for another patient is refused.
        CoordinationAlert beatriz = alerts.find(null, null, "CAM-P-1002").getFirst();
        assertThatThrownBy(() -> ai.chat(new ChatRequest(a.conversationId(), "ALERT", beatriz.alertId(), null, "And this one?"), MAYA))
                .isInstanceOf(ApiException.class).hasMessageContaining("different patient");

        // Follow-up in the same context carries history.
        ai.chat(new ChatRequest(a.conversationId(), "ALERT", ava.alertId(), null, "Where did you find that information?"), MAYA);
        assertThat(REQUESTS.getLast().turns()).hasSize(3);
    }

    @Test
    void flagExplanationIsValidatedAndCached() {
        String flagId = flags.find(null, "OB_ANESTHESIA_REVIEW", "CAM-P-1001").getFirst().flagId();
        int before = REQUESTS.size();
        AiAnswer first = ai.explainFlag(flagId, false, MAYA);
        AiAnswer cached = ai.explainFlag(flagId, false, MAYA);
        assertThat(REQUESTS).hasSize(before + 1);
        assertThat(cached.answer()).isEqualTo(first.answer());
        assertThat(flags.findById(flagId).orElseThrow().aiExplanationModel()).isEqualTo("scripted-test-model");
    }
}
