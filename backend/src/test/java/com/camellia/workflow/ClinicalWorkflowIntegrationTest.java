package com.camellia.workflow;

import com.camellia.analysis.AnalysisService;
import com.camellia.config.DemoUsers;
import com.camellia.coordination.CoordinationService;
import com.camellia.coordination.CoordinationService.AlertUpdate;
import com.camellia.ingestion.DemoDataService;
import com.camellia.model.ClinicalFlag;
import com.camellia.model.CoordinationAlert;
import com.camellia.model.EvidenceLink;
import com.camellia.repository.AlertRepository;
import com.camellia.repository.FlagRepository;
import com.camellia.repository.PatientRepository;
import com.camellia.search.SearchCriteria;
import com.camellia.search.SearchService;
import com.camellia.web.ApiException;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end backend workflow on the local fallback database (the same SQL and services used with Snowflake):
 * seeding → screening → alerts → coordination → persistence, plus search and missing-Gemini behavior.
 */
@SpringBootTest(properties = {
        "camellia.database.mode=local",
        "camellia.database.local-url=jdbc:h2:mem:workflow-test;DB_CLOSE_DELAY=-1",
        "camellia.snowflake.account=",
        "camellia.gemini.api-key=",
        "camellia.demo.anchor-date="
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ClinicalWorkflowIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired PatientRepository patients;
    @Autowired FlagRepository flags;
    @Autowired AlertRepository alerts;
    @Autowired AnalysisService analysis;
    @Autowired CoordinationService coordination;
    @Autowired DemoDataService demoData;
    @Autowired SearchService search;

    static final DemoUsers.DemoUser MAYA = DemoUsers.resolve("maya.ellison");
    static final DemoUsers.DemoUser JORDAN = DemoUsers.resolve("jordan.lee");

    Map<String, CoordinationAlert> alertsByPatient() {
        return alerts.find(null, null, null).stream().collect(Collectors.toMap(CoordinationAlert::patientId, a -> a));
    }

    @Test @Order(1)
    void patientRetrievalAcrossOrganizations() throws Exception {
        mvc.perform(get("/api/patients")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(9));
        mvc.perform(get("/api/patients/CAM-P-1001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.patient.name").value("Ava Marlowe"))
                .andExpect(jsonPath("$.identifiers.length()").value(3))
                .andExpect(jsonPath("$.labs.length()").value(6));
        mvc.perform(get("/api/patients/CAM-P-9999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/records/REC-NDL-ACC-55012-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.record.orgName").value("Northgate Diagnostic Laboratories"))
                .andExpect(jsonPath("$.rawContent").value(org.hamcrest.Matchers.containsString("OBX|1|NM|777-3")));
    }

    @Test @Order(2)
    void screeningGeneratesExpectedFlagsAndRoutesObAlerts() {
        List<ClinicalFlag> all = flags.find(null, null, null);
        Map<String, List<String>> rulesByPatient = all.stream().collect(Collectors.groupingBy(ClinicalFlag::patientId,
                Collectors.mapping(ClinicalFlag::ruleId, Collectors.toList())));
        assertThat(rulesByPatient).containsEntry("CAM-P-1001", List.of("OB_ANESTHESIA_REVIEW"));   // A: low platelets
        assertThat(rulesByPatient).containsEntry("CAM-P-1002", List.of("OB_ANESTHESIA_REVIEW"));   // B: complex history
        assertThat(rulesByPatient).containsEntry("CAM-P-1003", List.of("RECURRENT_FRACTURE"));     // C
        assertThat(rulesByPatient).containsEntry("CAM-P-1004", List.of("REPEATED_ABNORMAL_LAB"));  // D
        assertThat(rulesByPatient).containsEntry("CAM-P-1005", List.of("REPEATED_SYMPTOM"));       // E
        assertThat(rulesByPatient).containsEntry("CAM-P-1009", List.of("OB_ANESTHESIA_REVIEW"));   // I: prior failed epidural
        assertThat(rulesByPatient).doesNotContainKeys("CAM-P-1006", "CAM-P-1007", "CAM-P-1008");    // F, G, H: no unnecessary flags

        Map<String, CoordinationAlert> byPatient = alertsByPatient();
        assertThat(byPatient).containsOnlyKeys("CAM-P-1001", "CAM-P-1002", "CAM-P-1009");
        assertThat(byPatient.get("CAM-P-1001").alertType()).isEqualTo("ANESTHESIA_OB");
        assertThat(byPatient.get("CAM-P-1002").alertType()).isEqualTo("MULTIDISCIPLINARY_OB");
        assertThat(byPatient.values()).allMatch(a -> a.assignedTeam().equals("Anesthesia OB Coordination") && a.status().equals("NEW"));

        // Every evidence link points at an existing source record.
        for (ClinicalFlag f : all) {
            for (EvidenceLink e : flags.evidence(f.flagId())) {
                assertThat(demoDataRecordExists(e.recordId())).as("evidence %s exists", e.recordId()).isTrue();
            }
        }
    }

    @Autowired com.camellia.repository.ClinicalDataRepository clinical;

    private boolean demoDataRecordExists(String recordId) {
        return clinical.findRecord(recordId).isPresent();
    }

    @Test @Order(3)
    void repeatedAnalysisDoesNotDuplicateFlagsOrAlerts() {
        int flagsBefore = flags.find(null, null, null).size();
        int alertsBefore = alerts.find(null, null, null).size();
        AnalysisService.RunResult r1 = analysis.run("test");
        AnalysisService.RunResult r2 = analysis.run("test");
        assertThat(r1.flagsCreated()).isZero();
        assertThat(r2.flagsCreated()).isZero();
        assertThat(r2.flagsUnchanged()).isEqualTo(flagsBefore);
        assertThat(flags.find(null, null, null)).hasSize(flagsBefore);
        assertThat(alerts.find(null, null, null)).hasSize(alertsBefore);
        // Seeding again is idempotent too.
        DemoDataService.SeedResult seed = demoData.seed();
        assertThat(seed.recordsInserted()).isZero();
        assertThat(seed.patientsInserted()).isZero();
    }

    @Test @Order(4)
    void coordinationStatusNotesAndAccessControlPersist() {
        CoordinationAlert imani = alertsByPatient().get("CAM-P-1009");

        coordination.updateAlert(imani.alertId(), new AlertUpdate(true, null, null, null, null, null, null), MAYA);
        coordination.addNote(imani.alertId(), "CONTACT", "Requested Cedar Hollow spine imaging and anesthesia record.", MAYA);
        coordination.updateAlert(imani.alertId(), new AlertUpdate(null, "COORDINATION_NEEDED", "PLANNED", true, true, null, null), MAYA);

        CoordinationAlert reloaded = alerts.findById(imani.alertId()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo("COORDINATION_NEEDED");
        assertThat(reloaded.acknowledgedBy()).isEqualTo(MAYA.name());
        assertThat(reloaded.contactStatus()).isEqualTo("PLANNED");
        assertThat(reloaded.planningDiscussionOccurred()).isTrue();
        assertThat(reloaded.infoNeeded()).isTrue();
        assertThat(alerts.notes(imani.alertId())).anyMatch(n -> n.noteType().equals("CONTACT") && n.body().contains("spine imaging"));

        // Invalid transition, missing dismissal reason, missing plan summary, and wrong team are rejected.
        assertThatThrownBy(() -> coordination.updateAlert(imani.alertId(), new AlertUpdate(null, "RESOLVED", null, null, null, null, null), MAYA))
                .isInstanceOf(ApiException.class).hasMessageContaining("not allowed");
        assertThatThrownBy(() -> coordination.updateAlert(imani.alertId(), new AlertUpdate(null, "DISMISSED", null, null, null, " ", null), MAYA))
                .isInstanceOf(ApiException.class).hasMessageContaining("reason");
        assertThatThrownBy(() -> coordination.updateAlert(imani.alertId(), new AlertUpdate(null, "PLAN_DOCUMENTED", null, null, null, null, null), MAYA))
                .isInstanceOf(ApiException.class).hasMessageContaining("plan");
        assertThatThrownBy(() -> coordination.updateAlert(imani.alertId(), new AlertUpdate(null, "UNDER_REVIEW", null, null, null, null, null), JORDAN))
                .isInstanceOf(ApiException.class).hasMessageContaining("Simulated access control");
        // Other teams can still add notes.
        coordination.addNote(imani.alertId(), "GENERAL", "L&D aware.", JORDAN);

        coordination.updateAlert(imani.alertId(), new AlertUpdate(null, "PLAN_DOCUMENTED", "COMPLETED", null, false, null,
                "Anesthesia consult completed; plan documented by the obstetric anesthesia team."), MAYA);
        coordination.updateAlert(imani.alertId(), new AlertUpdate(null, "RESOLVED", null, null, null, null, null), MAYA);
        assertThat(alerts.findById(imani.alertId()).orElseThrow().status()).isEqualTo("RESOLVED");
        assertThat(alerts.notes(imani.alertId())).anyMatch(n -> n.noteType().equals("PLAN_SUMMARY"));
    }

    @Test @Order(5)
    void flagStatusTransitionsPersist() {
        ClinicalFlag d = flags.find(null, "REPEATED_ABNORMAL_LAB", "CAM-P-1004").getFirst();
        coordination.updateFlagStatus(d.flagId(), "IN_REVIEW", null, MAYA);
        assertThatThrownBy(() -> coordination.updateFlagStatus(d.flagId(), "DISMISSED", "", MAYA)).isInstanceOf(ApiException.class);
        coordination.updateFlagStatus(d.flagId(), "REVIEWED", "Reviewed with primary care", MAYA);
        ClinicalFlag reloaded = flags.findById(d.flagId()).orElseThrow();
        assertThat(reloaded.status()).isEqualTo("REVIEWED");
        assertThat(reloaded.updatedBy()).isEqualTo(MAYA.name());
        // A repeat screening run keeps the human review status.
        analysis.run("test");
        assertThat(flags.findById(d.flagId()).orElseThrow().status()).isEqualTo("REVIEWED");
    }

    @Test @Order(6)
    void newEvidenceUpdatesExistingAlertWithoutDuplicating() {
        CoordinationAlert ava = alertsByPatient().get("CAM-P-1001");
        coordination.updateAlert(ava.alertId(), new AlertUpdate(true, null, null, null, null, null, null), MAYA);
        DemoDataService.IngestResult ingest = demoData.ingestIncomingLabResult();
        assertThat(ingest.recordsInserted()).isEqualTo(2);
        AnalysisService.RunResult run = analysis.run("test");
        assertThat(run.outcomes()).anyMatch(o -> o.patientId().equals("CAM-P-1001") && o.ruleId().equals("OB_ANESTHESIA_REVIEW")
                && o.result().equals("EVIDENCE_UPDATED"));
        assertThat(run.alertsCreated()).isZero();

        CoordinationAlert after = alerts.findById(ava.alertId()).orElseThrow();
        assertThat(after.status()).isEqualTo("UNDER_REVIEW");          // human workflow preserved
        assertThat(after.evidenceUpdatedAt()).isNotNull();
        assertThat(alerts.evidence(ava.alertId())).extracting(EvidenceLink::recordId).contains("REC-NDL-ACC-55988-1");
        assertThat(alerts.notes(ava.alertId())).anyMatch(n -> n.noteType().equals("SYSTEM") && n.body().contains("REC-NDL-ACC-55988-1"));
        assertThat(alertsByPatient()).hasSize(3);

        // Re-ingesting the same message is a no-op.
        assertThat(demoData.ingestIncomingLabResult().recordsInserted()).isZero();
    }

    @Test @Order(7)
    void searchFiltersByKeywordConceptOrganizationTypeAndDate() {
        var fx = search.search(new SearchCriteria(null, List.of("fracture"), List.of(), List.of(), List.of(), null, null, null,
                false, false, false, false, 1, "ALL"));
        assertThat(fx.patients()).extracting(SearchService.PatientGroup::patientId).containsExactly("CAM-P-1003");
        assertThat(fx.patients().getFirst().organizations()).hasSize(3);

        var low = search.search(new SearchCriteria(null, List.of(), List.of("LOW_PLATELETS"), List.of("LAB_RESULT"), List.of(), null, null, null,
                false, false, false, false, 1, "ANY"));
        assertThat(low.patients()).extracting(SearchService.PatientGroup::patientId).containsExactly("CAM-P-1001");

        var calciumNdl = search.search(new SearchCriteria(null, List.of("calcium"), List.of(), List.of("LAB_RESULT"), List.of("CHCH"), "CAM-P-1004",
                null, null, false, false, false, false, 1, "ALL"));
        assertThat(calciumNdl.totalRecords()).isEqualTo(1);

        var obUpcoming = search.search(new SearchCriteria(null, List.of(), List.of("HEMATOLOGY"), List.of(), List.of(), null, null, null,
                true, true, false, false, 1, "ANY"));
        assertThat(obUpcoming.patients()).extracting(SearchService.PatientGroup::patientId)
                .contains("CAM-P-1001", "CAM-P-1002").doesNotContain("CAM-P-1003", "CAM-P-1006");

        var future = search.search(new SearchCriteria(null, List.of("calcium"), List.of(), List.of(), List.of(), null, "2999-01-01", null,
                false, false, false, false, 1, "ALL"));
        assertThat(future.totalRecords()).isZero();

        // Hostile input is neutralized and the database is untouched.
        var hostile = search.search(new SearchCriteria(null, List.of("'; DROP TABLE PATIENTS; --"), List.of(), List.of(), List.of(), null, null, null,
                false, false, false, false, 1, "ALL"));
        assertThat(hostile.totalRecords()).isZero();
        assertThat(patients.count()).isEqualTo(9);
    }

    @Test @Order(8)
    void aiEndpointsDegradeSafelyWithoutGemini() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contextType\":\"PATIENT\",\"contextId\":\"CAM-P-1001\",\"message\":\"Why was this patient flagged?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mode").value("RETRIEVAL_ONLY"))
                .andExpect(jsonPath("$.aiGenerated").value(false))
                .andExpect(jsonPath("$.retrievedRecords.length()").value(org.hamcrest.Matchers.greaterThan(0)));
        mvc.perform(post("/api/search/interpret").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"patients with low platelet counts\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source").value("RULE_BASED"))
                .andExpect(jsonPath("$.notice").value(org.hamcrest.Matchers.containsString("Gemini is not configured")));
        mvc.perform(post("/api/integrations/gemini/check"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GEMINI_NOT_CONFIGURED"));
        mvc.perform(post("/api/patients/CAM-P-1001/ai-extraction"))
                .andExpect(status().isServiceUnavailable());
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contextType\":\"NOPE\",\"contextId\":\"x\",\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test @Order(9)
    void integrationStatusIsHonestAboutFallbackAndDbCheckReadsAndWrites() throws Exception {
        mvc.perform(get("/api/integrations/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.database.mode").value("LOCAL_FALLBACK"))
                .andExpect(jsonPath("$.database.isSnowflake").value(false))
                .andExpect(jsonPath("$.gemini.configured").value(false));
        mvc.perform(post("/api/integrations/database/check"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.writeCheck.verified").value(true));
    }
}
