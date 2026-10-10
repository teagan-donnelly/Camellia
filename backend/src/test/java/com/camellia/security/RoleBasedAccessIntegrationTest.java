package com.camellia.security;

import com.camellia.TestSessions;
import com.camellia.ingestion.Json;
import com.camellia.integration.gemini.GeminiGateway;
import com.camellia.model.ClinicalFlag;
import com.camellia.model.CoordinationAlert;
import com.camellia.repository.AlertRepository;
import com.camellia.repository.FlagRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Role-based access across every patient-specific path: directory, profile, records, search, flag search, alerts,
 * coordination, dashboard and Gemini retrieval. Uses a scripted Gemini gateway so we can prove which data would have
 * been sent to the model (and that nothing is sent when access is denied).
 */
@SpringBootTest(properties = {
        "camellia.database.mode=local",
        "camellia.database.local-url=jdbc:h2:mem:rbac-test;DB_CLOSE_DELAY=-1",
        "camellia.snowflake.account=",
        "camellia.gemini.api-key=",
        "camellia.demo.anchor-date="
})
@AutoConfigureMockMvc
class RoleBasedAccessIntegrationTest {

    static final List<GeminiGateway.Request> REQUESTS = new CopyOnWriteArrayList<>();

    static final List<String> PRACTICE_A = List.of("CAM-P-1003", "CAM-P-1004", "CAM-P-1005", "CAM-P-1006", "CAM-P-1008");
    static final List<String> PRACTICE_B_NAMES = List.of("Ava Marlowe", "Beatriz Okonkwo-Hale", "Grace Thornbury", "Imani Brooks");

    /** A model that (deliberately) asks for the restricted anesthesia category, to prove the backend still filters it. */
    static final String INTERPRET_REPLY = """
            {"keywords":[],"concepts":[],"recordTypes":[],"organizationIds":[],"patientId":"","dateFrom":"","dateTo":"",
             "obstetricOnly":false,"upcomingDeliveryOnly":false,"multipleOrganizationsOnly":false,"openFlagsOnly":true,
             "minMatchesPerPatient":1,"matchMode":"ANY","searchTarget":"FLAGS","flagCategories":["OB_ANESTHESIA_REVIEW"],
             "flagStatusGroup":"OPEN","flagPriorities":[],"flagSpecialties":[],"explanation":"Open obstetric anesthesia flags."}""";

    static final String ANSWER_REPLY = """
            {"answer":"Documented findings are listed [REC-CHCH-ENC-3920].","documentedFacts":[],"interpretation":[],
             "missingInformation":[],"suggestedFollowUpQuestions":[]}""";

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
                    String text = r.operation().equals("interpret-search") ? INTERPRET_REPLY : ANSWER_REPLY;
                    return new Reply(text, "scripted-test-model", 100, 50, 5);
                }
            };
        }
    }

    @Autowired MockMvc mvc;
    @Autowired FlagRepository flags;
    @Autowired AlertRepository alerts;

    @BeforeEach
    void clearRequests() {
        REQUESTS.clear();
    }

    ResultActions as(String userId, MockHttpServletRequestBuilder request) throws Exception {
        Cookie session = TestSessions.as(mvc, userId);
        return mvc.perform(request.cookie(session));
    }

    static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b, String body) {
        return b.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    String obFlagId(String patientId) {
        return flags.find(null, "OB_ANESTHESIA_REVIEW", patientId).getFirst().flagId();
    }

    String alertId(String patientId) {
        return alerts.find(null, null, patientId).getFirst().alertId();
    }

    // ------------------------------------------------------------------ identity

    @Test
    void requestsWithoutOrWithTamperedSessionAreRejected() throws Exception {
        mvc.perform(get("/api/patients")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("DEMO_SESSION_REQUIRED"));
        String valid = TestSessions.as(mvc, "alex.kim").getValue();
        String tampered = valid.substring(0, valid.length() - 1) + (valid.endsWith("A") ? "B" : "A");
        mvc.perform(get("/api/patients").cookie(new Cookie(DemoSessionService.COOKIE, tampered)))
                .andExpect(status().isUnauthorized());
        // The legacy header is no longer trusted.
        mvc.perform(get("/api/patients").header("X-Camellia-User", "maya.ellison")).andExpect(status().isUnauthorized());
        mvc.perform(json(post("/api/session"), "{\"userId\":\"nobody\"}")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/session/users")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(6));
    }

    // ------------------------------------------------------------------ A, B: practice-scoped physician

    @Test
    void physicianSeesOnlyTheirPracticesPatients() throws Exception {
        as("alex.kim", get("/api/patients")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].patientId", containsInAnyOrder(PRACTICE_A.toArray())));
        as("sarah.mitchell", get("/api/patients")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].patientId", containsInAnyOrder("CAM-P-1001", "CAM-P-1002", "CAM-P-1007", "CAM-P-1009")));
    }

    @Test
    void physicianIsDeniedOtherPracticesPatientsWithAGenericError() throws Exception {
        String restricted = as("alex.kim", get("/api/patients/CAM-P-1001")).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCESS_DENIED")).andReturn().getResponse().getContentAsString();
        as("alex.kim", get("/api/patients/CAM-P-1001/records")).andExpect(status().isForbidden());
        as("alex.kim", get("/api/patients/CAM-P-1001/appointments")).andExpect(status().isForbidden());
        as("alex.kim", get("/api/records/REC-NDL-ACC-55012-1")).andExpect(status().isForbidden());
        as("alex.kim", post("/api/patients/CAM-P-1001/ai-extraction")).andExpect(status().isForbidden());
        // A non-existent patient is indistinguishable from a restricted one.
        String missing = as("alex.kim", get("/api/patients/CAM-P-9999")).andExpect(status().isForbidden())
                .andReturn().getResponse().getContentAsString();
        assertThat(stripVolatile(missing)).isEqualTo(stripVolatile(restricted));
        as("alex.kim", get("/api/records/REC-DOES-NOT-EXIST")).andExpect(status().isForbidden());
        // Own patient works.
        as("alex.kim", get("/api/patients/CAM-P-1003")).andExpect(status().isOk())
                .andExpect(jsonPath("$.patient.name").value("Caleb Wren"));
        assertThat(REQUESTS).isEmpty();
    }

    private static String stripVolatile(String body) {
        return body.replaceAll("\"(timestamp|path)\":\"[^\"]*\",?", "");
    }

    // ------------------------------------------------------------------ C: registered nurse

    @Test
    void registeredNurseSeesAllPatientsButNoAnesthesiaWorkflow() throws Exception {
        as("jordan.lee", get("/api/patients")).andExpect(jsonPath("$.length()").value(9));
        as("jordan.lee", get("/api/coordination-alerts")).andExpect(status().isForbidden());
        as("jordan.lee", get("/api/flags")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ruleId", not(hasItem("OB_ANESTHESIA_REVIEW"))))
                .andExpect(jsonPath("$[*].ruleId", hasItem("RECURRENT_FRACTURE")));
        as("jordan.lee", get("/api/flags/" + obFlagId("CAM-P-1001"))).andExpect(status().isForbidden());
        as("jordan.lee", get("/api/analysis/rules")).andExpect(jsonPath("$[*].id", not(hasItem("OB_ANESTHESIA_REVIEW"))));
        as("jordan.lee", get("/api/flags/facets")).andExpect(jsonPath("$.categories.OB_ANESTHESIA_REVIEW").doesNotExist());
        // Registered nurses cannot change flag review status.
        String caleb = flags.find(null, "RECURRENT_FRACTURE", "CAM-P-1003").getFirst().flagId();
        as("jordan.lee", json(patch("/api/flags/" + caleb + "/status"), "{\"status\":\"IN_REVIEW\"}"))
                .andExpect(status().isForbidden());
        // The patient profile does not leak the anesthesia flag or alert either.
        as("jordan.lee", get("/api/patients/CAM-P-1001")).andExpect(status().isOk())
                .andExpect(jsonPath("$..ruleId", not(hasItem("OB_ANESTHESIA_REVIEW"))))
                .andExpect(jsonPath("$..alertId", empty()));
    }

    // ------------------------------------------------------------------ D: anesthesiologist

    @Test
    void anesthesiologistHasFullCoordinationWorkflow() throws Exception {
        as("daniel.brooks", get("/api/session")).andExpect(jsonPath("$.navigation", hasItem("ANESTHESIA_OB_ALERTS")))
                .andExpect(jsonPath("$.simulated").value(true));
        as("daniel.brooks", get("/api/coordination-alerts")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        String beatriz = alertId("CAM-P-1002");
        as("daniel.brooks", json(patch("/api/coordination-alerts/" + beatriz + "/status"), "{\"acknowledge\":true}"))
                .andExpect(status().isOk());
        as("daniel.brooks", json(patch("/api/coordination-alerts/" + beatriz + "/status"), "{\"status\":\"COORDINATION_NEEDED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$..status", hasItem("COORDINATION_NEEDED")));
        as("daniel.brooks", json(post("/api/coordination-alerts/" + beatriz + "/notes"), "{\"noteType\":\"GENERAL\",\"body\":\"Reviewed.\"}"))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ E: physicians and L&D nurse on the anesthesia workflow

    @Test
    void physiciansCannotReachAnesthesiaAlerts() throws Exception {
        String alert = alertId("CAM-P-1001");
        String obFlag = obFlagId("CAM-P-1001");
        for (String user : List.of("alex.kim", "sarah.mitchell")) {
            as(user, get("/api/session")).andExpect(jsonPath("$.navigation", not(hasItem("ANESTHESIA_OB_ALERTS"))));
            as(user, get("/api/coordination-alerts")).andExpect(status().isForbidden());
            as(user, get("/api/coordination-alerts/" + alert)).andExpect(status().isForbidden());
            as(user, json(patch("/api/coordination-alerts/" + alert + "/status"), "{\"acknowledge\":true}")).andExpect(status().isForbidden());
            as(user, json(post("/api/coordination-alerts/" + alert + "/notes"), "{\"noteType\":\"GENERAL\",\"body\":\"x\"}"))
                    .andExpect(status().isForbidden());
            as(user, get("/api/flags/" + obFlag)).andExpect(status().isForbidden());
        }
        // Ava is Dr. Mitchell's patient, but her anesthesia flag belongs to the restricted workflow.
        as("sarah.mitchell", get("/api/flags")).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].ruleId", not(hasItem("OB_ANESTHESIA_REVIEW"))));
        as("sarah.mitchell", get("/api/patients/CAM-P-1001")).andExpect(status().isOk());
        as("sarah.mitchell", get("/api/records/REC-NDL-ACC-55012-1")).andExpect(status().isOk());
    }

    @Test
    void laborAndDeliveryNurseCanViewAndAnnotateButNotChangeStatus() throws Exception {
        String imani = alertId("CAM-P-1009");
        as("taylor.morgan", get("/api/coordination-alerts")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        as("taylor.morgan", get("/api/coordination-alerts/" + imani)).andExpect(status().isOk());
        as("taylor.morgan", json(post("/api/coordination-alerts/" + imani + "/notes"), "{\"noteType\":\"GENERAL\",\"body\":\"L&D aware.\"}"))
                .andExpect(status().isOk());
        as("taylor.morgan", json(patch("/api/coordination-alerts/" + imani + "/status"), "{\"status\":\"UNDER_REVIEW\"}"))
                .andExpect(status().isForbidden());
        as("taylor.morgan", json(patch("/api/coordination-alerts/" + imani + "/status"), "{\"acknowledge\":true}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ F, G: flag search

    @Test
    void flagSearchFindsFlaggedPatientsWithinScope() throws Exception {
        as("jordan.lee", get("/api/flags/search?category=RECURRENT_FRACTURE")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].patientId", contains("CAM-P-1003")));
        as("jordan.lee", get("/api/flags/search?concept=CALCIUM")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].patientId", hasItem("CAM-P-1004")));
        as("maya.ellison", get("/api/flags/search?concept=LOW_PLATELETS&statusGroup=OPEN")).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].patientId", hasItem("CAM-P-1001")))
                .andExpect(jsonPath("$.results[?(@.patientId == 'CAM-P-1001')].link", everyItem(startsWith("/alerts/"))));
    }

    @Test
    void flagSearchNeverWidensScope() throws Exception {
        as("alex.kim", get("/api/flags/search?q=marlowe")).andExpect(jsonPath("$.results", empty()));
        as("alex.kim", get("/api/flags/search?category=OB_ANESTHESIA_REVIEW")).andExpect(jsonPath("$.results", empty()))
                .andExpect(jsonPath("$.notice", notNullValue()));
        as("alex.kim", get("/api/flags/search?patientId=CAM-P-1001")).andExpect(jsonPath("$.results", empty()));
        as("sarah.mitchell", get("/api/flags/search?category=OB_ANESTHESIA_REVIEW")).andExpect(jsonPath("$.results", empty()))
                .andExpect(jsonPath("$.notice", notNullValue()));
        as("sarah.mitchell", get("/api/flags/search")).andExpect(jsonPath("$.results[*].ruleId", not(hasItem("OB_ANESTHESIA_REVIEW"))));

        // Natural language: even when the model asks for the restricted category, executing the criteria finds nothing restricted.
        String interp = as("sarah.mitchell", json(post("/api/search/interpret"), "{\"query\":\"show unresolved obstetric anesthesia alerts\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.target").value("FLAGS"))
                .andReturn().getResponse().getContentAsString();
        Map<String, Object> criteria = JsonPath.read(interp, "$.flagCriteria");
        as("sarah.mitchell", json(post("/api/flags/search"), Json.write(criteria))).andExpect(status().isOk())
                .andExpect(jsonPath("$.results[*].ruleId", not(hasItem("OB_ANESTHESIA_REVIEW"))))
                .andExpect(jsonPath("$.notice", notNullValue()));
    }

    // ------------------------------------------------------------------ H: Gemini retrieval is authorized first

    @Test
    void geminiNeverReceivesDataOutsideTheUsersScope() throws Exception {
        String ava = "CAM-P-1001";
        as("alex.kim", json(post("/api/ai/chat"), "{\"contextType\":\"PATIENT\",\"contextId\":\"" + ava + "\",\"message\":\"Summarize\"}"))
                .andExpect(status().isForbidden());
        as("alex.kim", json(post("/api/ai/chat"), "{\"contextType\":\"FLAG\",\"contextId\":\"" + obFlagId(ava) + "\",\"message\":\"Why?\"}"))
                .andExpect(status().isForbidden());
        as("alex.kim", json(post("/api/ai/chat"), "{\"contextType\":\"ALERT\",\"contextId\":\"" + alertId(ava) + "\",\"message\":\"Why?\"}"))
                .andExpect(status().isForbidden());
        as("alex.kim", json(post("/api/ai/chat"), "{\"contextType\":\"SEARCH\",\"contextId\":\"x\",\"recordIds\":[\"REC-NDL-ACC-55012-1\"],\"message\":\"Summarize\"}"))
                .andExpect(status().isForbidden());
        as("alex.kim", json(post("/api/ai/search-summary"), "{\"query\":\"platelets\",\"recordIds\":[\"REC-NDL-ACC-55012-1\"]}"))
                .andExpect(status().isForbidden());
        as("alex.kim", json(post("/api/ai/explain-flag"), "{\"flagId\":\"" + obFlagId(ava) + "\"}")).andExpect(status().isForbidden());
        assertThat(REQUESTS).as("no Gemini request for denied contexts").isEmpty();

        as("alex.kim", json(post("/api/search/interpret"), "{\"query\":\"patients with low platelets\"}")).andExpect(status().isOk());
        GeminiGateway.Request interpret = REQUESTS.getLast();
        String interpretPrompt = interpret.turns().getLast().text();
        assertThat(interpretPrompt).doesNotContain("Marlowe").doesNotContain(ava).contains("Caleb Wren");
        assertThat(Json.write(interpret.jsonSchema())).doesNotContain("OB_ANESTHESIA_REVIEW").doesNotContain(ava);

        as("alex.kim", json(post("/api/ai/chat"), "{\"contextType\":\"PATIENT\",\"contextId\":\"CAM-P-1003\",\"message\":\"Summarize the fractures\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("GEMINI"));
        String chatPrompt = REQUESTS.getLast().turns().getLast().text();
        assertThat(chatPrompt).contains("Caleb");
        PRACTICE_B_NAMES.forEach(name -> assertThat(chatPrompt).doesNotContain(name));
    }

    // ------------------------------------------------------------------ I: same endpoint, different users, different data

    @Test
    void sameEndpointsReturnDifferentResultsPerUser() throws Exception {
        as("alex.kim", get("/api/dashboard")).andExpect(jsonPath("$.totalPatients").value(5))
                .andExpect(jsonPath("$.anesthesiaAccess").value(false))
                .andExpect(jsonPath("$.unreviewedAnesthesiaAlerts").value(0))
                .andExpect(jsonPath("$.upcomingComplexEncounterCount").value(0))
                .andExpect(jsonPath("$.lastAnalysisRun").doesNotExist());
        as("sarah.mitchell", get("/api/dashboard")).andExpect(jsonPath("$.totalPatients").value(4))
                .andExpect(jsonPath("$.unreviewedAnesthesiaAlerts").value(0))
                .andExpect(jsonPath("$.upcomingComplexEncounterCount").value(0))
                .andExpect(jsonPath("$.patientsRequiringReview[*].patientId", everyItem(in(List.of("CAM-P-1001", "CAM-P-1002", "CAM-P-1007", "CAM-P-1009")))));
        as("jordan.lee", get("/api/dashboard")).andExpect(jsonPath("$.totalPatients").value(9))
                .andExpect(jsonPath("$.unreviewedAnesthesiaAlerts").value(0));
        as("maya.ellison", get("/api/dashboard")).andExpect(jsonPath("$.anesthesiaAccess").value(true))
                .andExpect(jsonPath("$.upcomingComplexEncounterCount", greaterThan(0)));

        as("jordan.lee", get("/api/search?q=platelet")).andExpect(status().isOk())
                .andExpect(jsonPath("$.patients[*].patientId", hasItem("CAM-P-1001")));
        as("alex.kim", get("/api/search?q=platelet")).andExpect(status().isOk())
                .andExpect(jsonPath("$.patients[*].patientId", not(hasItem("CAM-P-1001"))));

        as("alex.kim", get("/api/session")).andExpect(jsonPath("$.navigation", contains("DASHBOARD", "PATIENTS", "CLINICAL_SEARCH", "CLINICAL_ALERTS")));
        as("taylor.morgan", get("/api/session")).andExpect(jsonPath("$.navigation", hasItem("ANESTHESIA_OB_ALERTS")))
                .andExpect(jsonPath("$.permissions", not(hasItem("UPDATE_COORDINATION"))));
    }

    @Test
    void analysisRunOutcomesAreScoped() throws Exception {
        as("alex.kim", post("/api/analysis/run")).andExpect(status().isOk())
                .andExpect(jsonPath("$.patientsEvaluated").value(5))
                .andExpect(jsonPath("$.outcomes[*].patientId", everyItem(in(PRACTICE_A))))
                .andExpect(jsonPath("$.outcomes[*].ruleId", not(hasItem("OB_ANESTHESIA_REVIEW"))));
        List<ClinicalFlag> all = flags.find(null, null, null);
        List<CoordinationAlert> allAlerts = alerts.find(null, null, null);
        assertThat(all).isNotEmpty();
        assertThat(allAlerts).hasSize(3);
    }
}
