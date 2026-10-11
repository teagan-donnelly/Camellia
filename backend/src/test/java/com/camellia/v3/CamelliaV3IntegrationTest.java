package com.camellia.v3;

import com.camellia.TestSessions;
import com.camellia.config.CamelliaProperties;
import com.camellia.ingestion.DemoDataService;
import com.camellia.repository.BriefingRepository;
import com.camellia.integration.elevenlabs.ElevenLabsClient;
import com.camellia.integration.gemini.GeminiGateway;
import com.camellia.repository.WorkforceRepository;
import com.camellia.security.DemoDirectory;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Camellia V3: expanded workforce, patient import, Camellia Briefing (with scripted Gemini and a fake ElevenLabs
 * client, so no network calls), encounter-scoped wearable monitoring and nursing review rules. Live Gemini and
 * ElevenLabs requests are verified separately against the real APIs.
 */
@SpringBootTest(properties = {
        "camellia.database.mode=local",
        "camellia.database.local-url=jdbc:h2:mem:v3-test;DB_CLOSE_DELAY=-1",
        "camellia.snowflake.account=",
        "camellia.gemini.api-key=",
        "camellia.elevenlabs.api-key=",
        "camellia.demo.anchor-date=",
        "camellia.briefing.audio-dir=target/test-briefing-audio"
})
@AutoConfigureMockMvc
class CamelliaV3IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final List<String> PROMPTS = new CopyOnWriteArrayList<>();
    /** When set, the scripted model names this patient (who is never assigned) to prove validation rejects it. */
    static volatile String injectName;
    /** Durations (seconds) returned by the fake TTS, in order; afterwards ~1 s per 15 characters. */
    static final Deque<Double> FORCED_DURATIONS = new ArrayDeque<>();
    static final AtomicInteger TTS_CALLS = new AtomicInteger();

    @TestConfiguration
    static class Fakes {
        @Bean
        @Primary
        GeminiGateway scriptedGemini() {
            return new GeminiGateway() {
                public boolean isConfigured() { return true; }
                public String model() { return "scripted-test-model"; }
                public Reply generateJson(Request r) {
                    String prompt = r.turns().getLast().text();
                    PROMPTS.add(prompt);
                    if (!r.operation().equals("briefing")) {
                        return new Reply("{\"answer\":\"ok\",\"documentedFacts\":[],\"interpretation\":[],\"missingInformation\":[],\"suggestedFollowUpQuestions\":[]}",
                                "scripted-test-model", 1, 1, 1);
                    }
                    return new Reply(scriptFrom(prompt), "scripted-test-model", 100, 100, 5);
                }
            };
        }

        @Bean
        @Primary
        ElevenLabsClient fakeTts(CamelliaProperties props) {
            return new ElevenLabsClient(props) {
                @Override public boolean isConfigured() { return true; }
                @Override public String voiceId() { return "test-voice"; }
                @Override public Speech synthesize(String text) {
                    TTS_CALLS.incrementAndGet();
                    Double forced = FORCED_DURATIONS.pollFirst();
                    double seconds = forced != null ? forced : text.length() / 15.0;
                    List<Double> ends = new ArrayList<>();
                    for (int i = 1; i <= text.length(); i++) ends.add(seconds * i / text.length());
                    return new Speech(("FAKE-MP3:" + text.hashCode()).getBytes(StandardCharsets.UTF_8), "audio/mpeg", seconds,
                            "test-voice", "eleven_multilingual_v2", text.length(), ends);
                }
            };
        }
    }

    /** Builds a valid script from the prompt: one segment per patient, citing every source listed for that patient. */
    static String scriptFrom(String prompt) {
        Matcher pm = Pattern.compile("PATIENT \\d+ — patientId (CAM-P-\\d+): ([^(]+) \\(").matcher(prompt);
        List<String[]> patients = new ArrayList<>();
        while (pm.find()) patients.add(new String[]{pm.group(1), pm.group(2).trim(), String.valueOf(pm.end())});
        StringBuilder segs = new StringBuilder();
        for (int i = 0; i < patients.size(); i++) {
            int end = i + 1 < patients.size() ? Integer.parseInt(patients.get(i + 1)[2]) : prompt.length();
            String block = prompt.substring(Integer.parseInt(patients.get(i)[2]), end);
            Matcher sm = Pattern.compile("\\[(RECORD|FLAG|ALERT|APPOINTMENT|MONITORING_ALERT) ([A-Z0-9-]+)]").matcher(block);
            List<String> sources = new ArrayList<>();
            while (sm.find()) sources.add("{\"type\":\"" + sm.group(1) + "\",\"id\":\"" + sm.group(2) + "\"}");
            if (segs.length() > 0) segs.append(',');
            // Like a real model, a revision request (REVISION NOTE) produces a shorter script.
            String narration = (prompt.contains("REVISION NOTE") ? patients.get(i)[1] + ": review open items."
                    : patients.get(i)[1] + " has items to review before the shift.") + (injectName != null && i == 0 ? " Also " + injectName + "." : "");
            segs.append("{\"heading\":\"").append(patients.get(i)[1]).append("\",\"patientIds\":[\"").append(patients.get(i)[0])
                    .append("\"],\"narration\":\"").append(narration).append("\",\"sources\":[").append(String.join(",", sources)).append("]}");
        }
        return "{\"title\":\"Test briefing\",\"opening\":\"This is a Camellia demo briefing using synthetic patient information.\","
                + "\"segments\":[" + segs + "],\"closing\":\"Review the open items first.\"}";
    }

    @Autowired MockMvc mvc;
    @Autowired WorkforceRepository workforce;
    @Autowired DemoDataService demoData;
    @Autowired BriefingRepository briefingRepo;

    @org.junit.jupiter.api.BeforeAll
    static void cleanAudioCache() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("target/test-briefing-audio");
        if (java.nio.file.Files.exists(dir)) {
            try (var files = java.nio.file.Files.list(dir)) {
                for (var f : files.toList()) java.nio.file.Files.delete(f);
            }
        }
    }

    @BeforeEach
    void reset() {
        injectName = null;
        FORCED_DURATIONS.clear();
    }

    ResultActions as(String user, MockHttpServletRequestBuilder req) throws Exception {
        Cookie c = TestSessions.as(mvc, user);
        return mvc.perform(req.cookie(c));
    }

    JsonNode json(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    ResultActions sim(String user, String pid, String eid, String action, Integer minutes) throws Exception {
        return as(user, post("/api/monitoring/simulator/events").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("patientId", pid, "encounterId", eid, "action", action, "minutes", minutes == null ? 0 : minutes)
                        .entrySet().stream().filter(e -> !(e.getValue() instanceof Integer i && i == 0))
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))));
    }

    // ============================================================================================ workforce

    @Test
    void oneHundredClinicianssAreSeededOnceAndOnlyDemoAccountsAreOffered() throws Exception {
        Map<String, Long> counts = workforce.clinicianCountsByRole();
        assertThat(counts).containsEntry("PHYSICIAN", 40L).containsEntry("REGISTERED_NURSE", 30L).containsEntry("LD_NURSE", 15L)
                .containsEntry("CRNA", 10L).containsEntry("ANESTHESIOLOGIST", 5L).containsEntry("DATA_COORDINATOR", 1L);
        demoData.seed();   // re-running seed initialization must not duplicate anyone
        assertThat(workforce.clinicianCountsByRole()).isEqualTo(counts);
        assertThat(DemoDirectory.ALL).hasSize(101);
        assertThat(DemoDirectory.USERS).hasSize(7);
        // A non-demo clinician is still a valid identity with the role's permissions (practice-scoped physician).
        DemoDirectory.ALL.stream().filter(u -> u.role().name().equals("PHYSICIAN") && u.practiceIds().contains("PRAC-C")).findFirst()
                .ifPresent(u -> assertThat(u.practiceIds()).containsExactly("PRAC-C"));
    }

    // ============================================================================================ monitoring

    static final String DOLORES = "CAM-P-1004", DOLORES_NOW = "ENC-LRMC-E-94310", DOLORES_JAN = "ENC-LRMC-E-94010";
    static final String HENRY = "CAM-P-1008", HENRY_NOW = "ENC-CHCH-E-77420";

    @Test
    void observationsStayWithTheirEncounter() throws Exception {
        JsonNode encs = json(as("jordan.lee", get("/api/patients/" + DOLORES + "/encounters")).andExpect(status().isOk()));
        List<String> monitored = new ArrayList<>();
        encs.forEach(e -> { if (e.hasNonNull("monitoring")) monitored.add(e.get("encounterId").asString()); });
        assertThat(monitored).containsExactly(DOLORES_NOW);

        JsonNode jan = json(as("jordan.lee", get("/api/patients/" + DOLORES + "/encounters/" + DOLORES_JAN + "/monitoring/observations?range=stay")));
        jan.get("series").forEach(series -> assertThat(series.size()).isZero());
        JsonNode now = json(as("jordan.lee", get("/api/patients/" + DOLORES + "/encounters/" + DOLORES_NOW + "/monitoring/observations?range=7d")));
        assertThat(now.get("series").get("HEART_RATE").size()).isGreaterThan(50);
        // the 7-day window is clipped to the start of this stay
        assertThat(now.get("from").asString()).isGreaterThanOrEqualTo("2026");

        as("jordan.lee", get("/api/patients/" + HENRY + "/encounters/" + DOLORES_NOW + "/monitoring")).andExpect(status().isForbidden());
        as("sarah.mitchell", get("/api/patients/" + DOLORES + "/encounters/" + DOLORES_NOW + "/monitoring")).andExpect(status().isForbidden());
        as("alex.kim", get("/api/patients/" + DOLORES + "/encounters/" + DOLORES_NOW + "/monitoring")).andExpect(status().isOk());
        as("riley.okafor", get("/api/patients/" + DOLORES + "/encounters/" + DOLORES_NOW + "/monitoring")).andExpect(status().isForbidden());
    }

    @Test
    void mobilityReviewFiresOnceAndADisconnectedDeviceIsNotTreatedAsInactivity() throws Exception {
        sim("jordan.lee", DOLORES, DOLORES_NOW, "RESET", null).andExpect(status().isOk());
        JsonNode d = json(sim("jordan.lee", DOLORES, DOLORES_NOW, "SCENARIO_INACTIVITY", null).andExpect(status().isOk()));
        assertThat(open(d, "MOBILITY_REVIEW")).isEqualTo(1);
        d = json(sim("jordan.lee", DOLORES, DOLORES_NOW, "ADVANCE_TIME", 120));
        assertThat(open(d, "MOBILITY_REVIEW")).as("no duplicate").isEqualTo(1);
        d = json(sim("jordan.lee", DOLORES, DOLORES_NOW, "WALK", 6));
        assertThat(open(d, "MOBILITY_REVIEW")).as("walking resolves it").isZero();

        sim("jordan.lee", DOLORES, DOLORES_NOW, "DISCONNECT", null).andExpect(status().isOk());
        d = json(sim("jordan.lee", DOLORES, DOLORES_NOW, "ADVANCE_TIME", 600));
        assertThat(open(d, "DEVICE_CONNECTIVITY")).isEqualTo(1);
        assertThat(open(d, "MOBILITY_REVIEW")).as("missing readings are not inactivity").isZero();
        sim("jordan.lee", DOLORES, DOLORES_NOW, "WALK", 5).andExpect(status().isBadRequest());   // no readings while disconnected
        d = json(sim("jordan.lee", DOLORES, DOLORES_NOW, "RECONNECT", null));
        assertThat(open(d, "DEVICE_CONNECTIVITY")).isZero();

        as("alex.kim", post("/api/monitoring/simulator/events").contentType(MediaType.APPLICATION_JSON)
                .content("{\"patientId\":\"" + DOLORES + "\",\"encounterId\":\"" + DOLORES_NOW + "\",\"action\":\"VITALS\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void repositioningCountsOnlyConfirmedNursingDocumentation() throws Exception {
        sim("jordan.lee", HENRY, HENRY_NOW, "RESET", null).andExpect(status().isOk());
        sim("jordan.lee", HENRY, HENRY_NOW, "POSITION_CHANGE", null).andExpect(status().isOk());
        JsonNode d = json(sim("jordan.lee", HENRY, HENRY_NOW, "SCENARIO_REPOSITIONING_DUE", null));
        assertThat(open(d, "REPOSITIONING_REVIEW")).as("wrist movement is not repositioning").isEqualTo(1);

        String body = "{\"patientId\":\"" + HENRY + "\",\"encounterId\":\"" + HENRY_NOW + "\",\"note\":\"Log-rolled\"}";
        as("alex.kim", post("/api/monitoring/interventions/repositioning").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        d = json(as("jordan.lee", post("/api/monitoring/interventions/repositioning").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()));
        assertThat(open(d, "REPOSITIONING_REVIEW")).isZero();
        JsonNode events = json(as("jordan.lee", get("/api/patients/" + HENRY + "/encounters/" + HENRY_NOW + "/monitoring/events")));
        boolean documented = false;
        for (JsonNode e : events) documented |= e.get("type").asString().equals("REPOSITIONING_DOCUMENTED") && "Jordan Lee, RN".equals(e.path("by").asString());
        assertThat(documented).isTrue();
        // nursing queue is scoped to nursing roles
        as("jordan.lee", get("/api/monitoring/alerts")).andExpect(status().isOk()).andExpect(jsonPath("$.patients.length()").value(3));
        as("maya.ellison", get("/api/monitoring/alerts")).andExpect(status().isForbidden());
    }

    static int open(JsonNode detail, String type) {
        int n = 0;
        for (JsonNode a : detail.get("alerts")) if (a.get("alertType").asString().equals(type) && !a.get("status").asString().equals("RESOLVED")) n++;
        return n;
    }

    // ============================================================================================ import

    String sample(String name) throws Exception {
        return new ClassPathResource("import-samples/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    JsonNode preview(String name) throws Exception {
        return json(as("riley.okafor", post("/api/import/preview").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("fileName", name, "content", sample(name))))).andExpect(status().isOk()));
    }

    ResultActions confirm(String name, String sha, String decision, String patientId) throws Exception {
        Map<String, Object> body = new HashMap<>(Map.of("fileName", name, "content", sample(name), "fileSha256", sha, "decision", decision));
        if (patientId != null) body.put("patientId", patientId);
        return as("riley.okafor", post("/api/import/confirm").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    @Test
    void importsCreateAttachDeduplicateValidateAndScreen() throws Exception {
        as("maya.ellison", post("/api/import/preview").contentType(MediaType.APPLICATION_JSON).content("{\"fileName\":\"x\",\"content\":\"{}\"}"))
                .andExpect(status().isForbidden());

        JsonNode bad = preview("05-invalid-file.json");
        assertThat(bad.get("valid").asBoolean()).isFalse();
        assertThat(bad.get("errors").toString()).contains("Claim").contains("MRN").contains("2026-13-45");

        JsonNode p1 = preview("01-new-patient-camellia.json");
        assertThat(p1.get("matches").size()).isZero();
        JsonNode r1 = json(confirm("01-new-patient-camellia.json", p1.get("fileSha256").asString(), "CREATE_NEW", null).andExpect(status().isOk()));
        assertThat(r1.get("recordsInserted").asInt()).isEqualTo(p1.get("totalRecords").asInt()).isGreaterThan(0);
        String newPid = r1.get("patientId").asString();
        assertThat(r1.get("displayId").asString()).startsWith("SYN-");
        JsonNode again = preview("01-new-patient-camellia.json");
        assertThat(again.get("duplicateRecords").asInt()).isEqualTo(again.get("totalRecords").asInt());
        confirm("01-new-patient-camellia.json", again.get("fileSha256").asString(), "CREATE_NEW", null).andExpect(status().isConflict());
        confirm("01-new-patient-camellia.json", again.get("fileSha256").asString(), "ATTACH_EXISTING", newPid)
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordsInserted").value(0));
        confirm("01-new-patient-camellia.json", "tampered", "CREATE_NEW", null).andExpect(status().isConflict());

        JsonNode p2 = preview("02-existing-patient-labs.csv");
        assertThat(p2.get("suggestedPatientId").asString()).isEqualTo("CAM-P-1003");
        confirm("02-existing-patient-labs.csv", p2.get("fileSha256").asString(), "ATTACH_EXISTING", "CAM-P-1003")
                .andExpect(status().isOk()).andExpect(jsonPath("$.recordsInserted").value(4));

        JsonNode p3 = preview("03-second-hospital-fhir.json");
        assertThat(p3.get("suggestedDecision").asString()).as("demographics alone never auto-attach").isEqualTo("CREATE_NEW");
        assertThat(p3.get("matches").get(0).get("matchType").asString()).isEqualTo("NAME_AND_BIRTH_DATE");

        JsonNode p4 = preview("04-new-ob-patient-low-platelets-fhir.json");
        JsonNode r4 = json(confirm("04-new-ob-patient-low-platelets-fhir.json", p4.get("fileSha256").asString(), "CREATE_NEW", null).andExpect(status().isOk()));
        assertThat(r4.get("screeningOutcomes").toString()).contains("OB_ANESTHESIA_REVIEW: CREATED");
        String obPid = r4.get("patientId").asString();
        as("maya.ellison", get("/api/search?q=platelet")).andExpect(status().isOk())
                .andExpect(jsonPath("$.patients[*].patientId", org.hamcrest.Matchers.hasItem(obPid)));
        as("maya.ellison", get("/api/coordination-alerts")).andExpect(jsonPath("$[*].patientId", org.hamcrest.Matchers.hasItem(obPid)));
        as("alex.kim", get("/api/patients/" + obPid)).andExpect(status().isForbidden());   // not registered to Practice A
    }

    // ============================================================================================ briefing

    JsonNode generateAndWait(String user, String shift, String mode, boolean regenerate) throws Exception {
        JsonNode b = json(as(user, post("/api/briefings/generate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"shiftId\":\"" + shift + "\",\"mode\":\"" + mode + "\",\"regenerate\":" + regenerate + "}")).andExpect(status().isOk()));
        for (int i = 0; i < 100 && Set.of("QUEUED", "SCRIPTING", "SYNTHESIZING").contains(b.get("status").asString()); i++) {
            Thread.sleep(100);
            b = json(as(user, get("/api/briefings/" + b.get("briefingId").asString())));
        }
        return b;
    }

    @Test
    void briefingIsGroundedScopedAudibleAndPrivate() throws Exception {
        PROMPTS.clear();
        JsonNode kim = generateAndWait("alex.kim", "SH-AKIM-D0", "FULL", true);
        assertThat(kim.get("status").asString()).isEqualTo("READY");
        assertThat(kim.get("excludedPatients").asInt()).isEqualTo(1);
        assertThat(PROMPTS.getLast()).as("an out-of-scope assignment never reaches Gemini").doesNotContain("Ava Marlowe").doesNotContain("CAM-P-1001");
        assertThat(kim.get("durationSeconds").asDouble()).isBetween(1.0, 480.0);
        assertThat(kim.get("segments").size()).isEqualTo(5);
        kim.get("segments").forEach(s -> assertThat(s.get("sources").size()).isGreaterThan(0));

        String id = kim.get("briefingId").asString();
        byte[] audio = as("alex.kim", get("/api/briefings/" + id + "/audio")).andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(audio, StandardCharsets.UTF_8)).startsWith("FAKE-MP3:");
        as("sarah.mitchell", get("/api/briefings/" + id + "/audio")).andExpect(status().isForbidden());
        as("sarah.mitchell", get("/api/briefings/" + id + "/transcript")).andExpect(status().isForbidden());
        as("jordan.lee", post("/api/briefings/generate").contentType(MediaType.APPLICATION_JSON).content("{\"shiftId\":\"SH-AKIM-D0\"}"))
                .andExpect(status().isForbidden());

        // Unchanged sources → the same briefing is reused, and identical text is never re-synthesized.
        int calls = TTS_CALLS.get();
        JsonNode reused = json(as("alex.kim", post("/api/briefings/generate").contentType(MediaType.APPLICATION_JSON)
                .content("{\"shiftId\":\"SH-AKIM-D0\",\"mode\":\"FULL\"}")));
        assertThat(reused.get("reused").asBoolean()).isTrue();
        assertThat(reused.get("briefingId").asString()).isEqualTo(id);
        assertThat(TTS_CALLS.get()).isEqualTo(calls);

        // Audio no longer on this server (made elsewhere, or temporary storage cleared): a clear 410, and a new briefing
        // is generated instead of reusing one that cannot play.
        Files.delete(Path.of(briefingRepo.find(id).orElseThrow().audioPath()));
        as("alex.kim", get("/api/briefings/" + id + "/audio")).andExpect(status().isGone());
        JsonNode fresh = generateAndWait("alex.kim", "SH-AKIM-D0", "FULL", false);
        assertThat(fresh.get("reused").asBoolean()).isFalse();
        assertThat(fresh.get("briefingId").asString()).isNotEqualTo(id);
        as("alex.kim", get("/api/briefings/" + fresh.get("briefingId").asString() + "/audio")).andExpect(status().isOk());
    }

    @Test
    void overlongAudioIsShortenedAndRegeneratedNeverTruncated() throws Exception {
        FORCED_DURATIONS.add(530.0);   // first synthesis runs over 8 minutes
        JsonNode b = generateAndWait("maya.ellison", "SH-MELL-D1", "FULL", true);
        assertThat(b.get("status").asString()).isEqualTo("READY");
        assertThat(b.get("durationSeconds").asDouble()).isLessThanOrEqualTo(480.0);
        assertThat(PROMPTS.getLast()).contains("REVISION NOTE").contains("over the 480-second limit");
    }

    @Test
    void scriptsNamingUnassignedPatientsAreRejected() throws Exception {
        injectName = "Grace Thornbury";   // a real synthetic patient who is not on Jordan's shift
        JsonNode b = generateAndWait("jordan.lee", "SH-JLEE-D0", "PRIORITY", true);
        assertThat(b.get("status").asString()).isEqualTo("FAILED");
        assertThat(b.get("statusDetail").asString()).contains("not an authorized assignment");
    }

    // ============================================================================================ status

    @Test
    void databaseStatusExplainsWhyTheLocalFallbackIsActive() throws Exception {
        as("maya.ellison", get("/api/integrations/status")).andExpect(status().isOk())
                .andExpect(jsonPath("$.database.state").value("LOCAL_FALLBACK_SELECTED"))
                .andExpect(jsonPath("$.database.isSnowflake").value(false))
                .andExpect(jsonPath("$.database.dataNote").exists())
                .andExpect(jsonPath("$.elevenlabs.configured").value(true));
    }
}
