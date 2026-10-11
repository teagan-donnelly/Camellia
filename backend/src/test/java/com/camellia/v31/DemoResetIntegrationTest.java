package com.camellia.v31;

import com.camellia.TestSessions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** "Reset demo data" restores only the seeded demo dataset and is limited to the data coordinator role. */
@SpringBootTest(properties = {
        "camellia.database.mode=local",
        "camellia.database.local-url=jdbc:h2:mem:reset-test;DB_CLOSE_DELAY=-1",
        "camellia.snowflake.account=",
        "camellia.gemini.api-key=",
        "camellia.elevenlabs.api-key=",
        "camellia.demo.anchor-date=",
        "camellia.documents.storage-dir=target/test-documents-reset"
})
@AutoConfigureMockMvc
class DemoResetIntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @Test
    void resetRestoresSeedDataButKeepsImportsAndUploads() throws Exception {
        // Data people add: a new imported patient, labs attached to a seeded patient, and uploaded documents.
        JsonNode created = json(confirm("01-new-patient-camellia.json", "CREATE_NEW", null).andExpect(status().isOk()));
        String importedPatient = created.get("patientId").asString();
        confirm("02-existing-patient-labs.csv", "ATTACH_EXISTING", "CAM-P-1003").andExpect(status().isOk());
        String seedDoc = upload("CAM-P-1004", "consult.pdf");
        String importedDoc = upload(importedPatient, "summary.pdf");
        int labRecords = json(as("maya.ellison", post("/api/admin/simulate-lab-result")).andExpect(status().isOk()))
                .get("ingest").get("recordsInserted").asInt();
        assertThat(labRecords).isPositive();

        int seedRecords = count("SELECT COUNT(*) FROM CLINICAL_RECORDS WHERE RECORD_ID NOT IN (SELECT RECORD_ID FROM CLINICAL_RECORDS WHERE PATIENT_ID = ?)", importedPatient);
        int importedRecords = count("SELECT COUNT(*) FROM CLINICAL_RECORDS WHERE PATIENT_ID = ?", importedPatient);
        int calebRecords = count("SELECT COUNT(*) FROM CLINICAL_RECORDS WHERE PATIENT_ID = 'CAM-P-1003'");

        // Only the data coordinator may reset; other demo users get a 403 and nothing changes.
        for (String user : new String[]{"maya.ellison", "jordan.lee", "alex.kim"}) {
            as(user, post("/api/admin/reset-demo")).andExpect(status().isForbidden());
        }
        assertThat(count("SELECT COUNT(*) FROM CLINICAL_RECORDS WHERE PATIENT_ID = ?", importedPatient)).isEqualTo(importedRecords);

        as("riley.okafor", post("/api/admin/reset-demo")).andExpect(status().isOk());
        as("riley.okafor", post("/api/admin/reset-demo")).andExpect(status().isOk());

        // Imported data is kept.
        assertThat(count("SELECT COUNT(*) FROM PATIENTS WHERE PATIENT_ID = ?", importedPatient)).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM CLINICAL_RECORDS WHERE PATIENT_ID = ?", importedPatient)).isEqualTo(importedRecords);
        assertThat(count("SELECT COUNT(*) FROM IMPORT_BATCHES")).isEqualTo(2);
        // Seeded data is restored once (no duplicates); the simulated lab result is removed so the demo can show it again;
        // the labs imported for a seeded patient survive.
        assertThat(count("SELECT COUNT(*) FROM CLINICAL_RECORDS WHERE RECORD_ID NOT IN (SELECT RECORD_ID FROM CLINICAL_RECORDS WHERE PATIENT_ID = ?)", importedPatient))
                .isEqualTo(seedRecords - labRecords);
        assertThat(count("SELECT COUNT(*) FROM CLINICAL_RECORDS WHERE PATIENT_ID = 'CAM-P-1003'")).isEqualTo(calebRecords);
        assertThat(count("SELECT COUNT(*) FROM PATIENTS")).isEqualTo(10);
        assertThat(count("SELECT COUNT(*) FROM PATIENT_TRAITS")).isEqualTo(4);
        assertThat(count("SELECT COUNT(*) FROM MONITORING_SESSIONS")).isEqualTo(3);
        // Uploaded documents keep their metadata and files.
        for (String[] d : new String[][]{{"CAM-P-1004", seedDoc}, {importedPatient, importedDoc}}) {
            assertThat(json(as("jordan.lee", get("/api/patients/" + d[0] + "/documents"))).toString()).contains(d[1]);
            as("jordan.lee", get("/api/documents/" + d[1] + "/file")).andExpect(status().isOk());
        }
    }

    private String upload(String patientId, String name) throws Exception {
        byte[] pdf = CamelliaV31IntegrationTest.pdf("Synthetic document for the reset test.");
        return json(mvc.perform(multipart("/api/patients/" + patientId + "/documents")
                        .file(new MockMultipartFile("file", name, "application/pdf", pdf))
                        .cookie(TestSessions.as(mvc, "jordan.lee"))).andExpect(status().isOk()))
                .get("documentId").asString();
    }

    private ResultActions confirm(String sample, String decision, String patientId) throws Exception {
        String content = new ClassPathResource("import-samples/" + sample).getContentAsString(StandardCharsets.UTF_8);
        JsonNode preview = json(as("riley.okafor", post("/api/import/preview").contentType(MediaType.APPLICATION_JSON)
                .content(JSON.writeValueAsString(Map.of("fileName", sample, "content", content)))).andExpect(status().isOk()));
        Map<String, Object> body = new HashMap<>(Map.of("fileName", sample, "content", content,
                "fileSha256", preview.get("fileSha256").asString(), "decision", decision));
        if (patientId != null) body.put("patientId", patientId);
        return as("riley.okafor", post("/api/import/confirm").contentType(MediaType.APPLICATION_JSON).content(JSON.writeValueAsString(body)));
    }

    private ResultActions as(String user, MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.cookie(TestSessions.as(mvc, user)));
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    private static JsonNode json(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }
}
