package com.camellia.v31;

import com.camellia.TestSessions;
import com.camellia.ingestion.DemoDataService;
import com.camellia.repository.TraitRepository;
import com.camellia.security.DemoDirectory;
import com.camellia.security.DemoUser;
import com.camellia.workforce.ShiftScheduleService;
import jakarta.servlet.http.Cookie;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Obesity traits, uploaded PDF/Word documents, and the dashboard shift schedule. No network calls (Gemini disabled). */
@SpringBootTest(properties = {
        "camellia.database.mode=local",
        "camellia.database.local-url=jdbc:h2:mem:v31-test;DB_CLOSE_DELAY=-1",
        "camellia.snowflake.account=",
        "camellia.gemini.api-key=",
        "camellia.elevenlabs.api-key=",
        "camellia.demo.anchor-date=",
        "camellia.documents.storage-dir=target/test-documents",
        "camellia.documents.max-upload-mb=1"
})
@AutoConfigureMockMvc
class CamelliaV31IntegrationTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    static final String NURSE = "jordan.lee";                // network scope, may upload
    static final String COORDINATOR = "riley.okafor";        // administrative: may upload, no clinical traits
    static final String ANESTHESIOLOGIST = "daniel.brooks";  // may view, may not upload
    static final String PRAC_A_PHYSICIAN = "alex.kim";       // practice scope that includes the trait patients
    static final String PRAC_B_PHYSICIAN = "sarah.mitchell"; // practice scope with none of them

    @Autowired MockMvc mvc;
    @Autowired DemoDataService demoData;
    @Autowired TraitRepository traitRepository;
    @Autowired ShiftScheduleService schedule;

    @BeforeAll
    static void cleanStorage() throws Exception {
        Path dir = Path.of("target/test-documents");
        if (Files.isDirectory(dir)) {
            try (Stream<Path> files = Files.list(dir)) {
                for (Path f : files.toList()) Files.deleteIfExists(f);
            }
        }
    }

    // ------------------------------------------------------------------ traits

    @Test
    void traitsComeFromCodedDiagnosesKeepTheirEncounterAndAreNotDuplicated() throws Exception {
        JsonNode dolores = json(call(NURSE, get("/api/patients/CAM-P-1004/traits")).andExpect(status().isOk()));
        assertThat(dolores.size()).isEqualTo(1);
        assertThat(dolores.get(0).path("traitCode").asString()).isEqualTo("MORBID_OBESITY");
        assertThat(dolores.get(0).path("label").asString()).isEqualTo("Morbid (severe) obesity");
        assertThat(dolores.get(0).path("code").asString()).isEqualTo("E66.01");
        assertThat(dolores.get(0).path("encounterId").asString()).isEqualTo("ENC-LRMC-E-94310");

        JsonNode frances = json(call(NURSE, get("/api/patients/CAM-P-1006/traits")));
        assertThat(frances.get(0).path("traitCode").asString()).isEqualTo("OBESITY");
        assertThat(frances.get(0).path("encounterId").asString()).isEqualTo("ENC-LRMC-E-94355");
        JsonNode caleb = json(call(NURSE, get("/api/patients/CAM-P-1003/traits")));
        assertThat(caleb.get(0).path("traitCode").asString()).isEqualTo("OBESITY");
        assertThat(caleb.get(0).path("encounterId").isMissingNode() || caleb.get(0).path("encounterId").isNull()).isTrue();
        assertThat(json(call(NURSE, get("/api/patients/CAM-P-1001/traits"))).size()).isZero();

        int before = traitRepository.all().size();
        assertThat(before).isEqualTo(4);
        demoData.seed();
        demoData.seed();
        assertThat(traitRepository.all()).hasSize(before);
    }

    @Test
    void traitSearchIsExactPerTraitAndKeywordsFindBoth() throws Exception {
        assertThat(patientsOf(json(call(NURSE, get("/api/search").param("trait", "MORBID_OBESITY")))))
                .containsExactlyInAnyOrder("CAM-P-1004", "CAM-P-1008");
        assertThat(patientsOf(json(call(NURSE, get("/api/search").param("trait", "OBESITY")))))
                .containsExactlyInAnyOrder("CAM-P-1003", "CAM-P-1006");
        assertThat(patientsOf(json(call(NURSE, get("/api/search").param("q", "obesity")))))
                .contains("CAM-P-1003", "CAM-P-1004", "CAM-P-1006", "CAM-P-1008");
        assertThat(patientsOf(json(call(NURSE, get("/api/search").param("q", "morbid obesity")))))
                .containsExactlyInAnyOrder("CAM-P-1004", "CAM-P-1008");

        // Natural language (rule-based here, Gemini is disabled) maps to the structured traits.
        JsonNode morbid = json(call(NURSE, post("/api/search/interpret").contentType("application/json")
                .content("{\"query\":\"Find patients with morbid obesity\"}")));
        assertThat(morbid.path("criteria").path("traits").toString()).isEqualTo("[\"MORBID_OBESITY\"]");
        JsonNode any = json(call(NURSE, post("/api/search/interpret").contentType("application/json")
                .content("{\"query\":\"Which patients have obesity?\"}")));
        assertThat(any.path("criteria").path("traits").toString()).isEqualTo("[\"OBESITY\",\"MORBID_OBESITY\"]");
        assertThat(patientsOf(json(call(NURSE, post("/api/search/execute").contentType("application/json")
                .content(any.path("criteria").toString())))))
                .containsExactlyInAnyOrder("CAM-P-1003", "CAM-P-1004", "CAM-P-1006", "CAM-P-1008");

        // Unknown trait values are dropped, not passed to SQL.
        JsonNode bad = json(call(NURSE, get("/api/search").param("q", "obesity").param("trait", "BMI_OVER_9000")));
        assertThat(bad.path("warnings").toString()).contains("Ignored unknown patient trait");
    }

    @Test
    void traitsAreLimitedToClinicalRolesAndScopeAndNeverBecomeNursingWork() throws Exception {
        call(COORDINATOR, get("/api/patients/CAM-P-1004/traits")).andExpect(status().isForbidden());
        // Practice-scoped physicians see traits only for patients registered to their practice.
        for (String pid : List.of("CAM-P-1004", "CAM-P-1008", "CAM-P-1003", "CAM-P-1006")) {
            call(PRAC_A_PHYSICIAN, get("/api/patients/" + pid + "/traits")).andExpect(status().isOk());
            call(PRAC_B_PHYSICIAN, get("/api/patients/" + pid + "/traits")).andExpect(status().isForbidden());
        }
        assertThat(patientsOf(json(call(PRAC_A_PHYSICIAN, get("/api/search").param("trait", "MORBID_OBESITY")))))
                .containsExactlyInAnyOrder("CAM-P-1004", "CAM-P-1008");
        assertThat(patientsOf(json(call(PRAC_B_PHYSICIAN, get("/api/search").param("q", "obesity"))))).isEmpty();
        assertThat(patientsOf(json(call(PRAC_B_PHYSICIAN, get("/api/search").param("trait", "MORBID_OBESITY"))))).isEmpty();

        String queue = call(NURSE, get("/api/monitoring/alerts")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(queue.toLowerCase(Locale.ROOT)).doesNotContain("obes");
    }

    // ------------------------------------------------------------------ documents

    @Test
    void pdfAndWordUploadsAreValidatedStoredSearchableAndAuthorized() throws Exception {
        byte[] pdf = pdf("Bariatric surgery consultation. Synthetic document for testing.");
        JsonNode up = json(upload(NURSE, "CAM-P-1004", "consult.pdf", pdf, "ENC-LRMC-E-94310").andExpect(status().isOk()));
        String pdfId = up.path("documentId").asString();
        assertThat(up.path("fileType").asString()).isEqualTo("PDF");
        assertThat(up.path("encounterId").asString()).isEqualTo("ENC-LRMC-E-94310");
        assertThat(up.path("textStatus").asString()).isEqualTo("EXTRACTED");
        assertThat(up.path("uploadedBy").asString()).isEqualTo("Jordan Lee, RN");

        JsonNode docx = json(upload(COORDINATOR, "CAM-P-1004", "discharge-plan.docx", docx("Discharge planning summary: walker at home."), null)
                .andExpect(status().isOk()));
        assertThat(docx.path("fileType").asString()).isEqualTo("DOCX");
        assertThat(docx.path("textStatus").asString()).isEqualTo("EXTRACTED");

        JsonNode list = json(call(ANESTHESIOLOGIST, get("/api/patients/CAM-P-1004/documents")));
        assertThat(list.size()).isGreaterThanOrEqualTo(2);

        call(NURSE, get("/api/documents/" + pdfId + "/file")).andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        call(NURSE, get("/api/documents/" + pdfId + "/file").param("download", "true"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
        call(NURSE, get("/api/documents/" + docx.path("documentId").asString() + "/file"))
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));

        // Extracted text is searchable for authorized clinicians, in the separate document results.
        JsonNode hits = json(call(NURSE, get("/api/search").param("q", "bariatric")));
        assertThat(hits.path("documents").toString()).contains(pdfId);

        // Outside the caller's scope: the file URL, the list and the search hit are all unavailable.
        call(PRAC_B_PHYSICIAN, get("/api/documents/" + pdfId + "/file")).andExpect(status().isForbidden());
        call(PRAC_B_PHYSICIAN, get("/api/patients/CAM-P-1004/documents")).andExpect(status().isForbidden());
        assertThat(json(call(PRAC_B_PHYSICIAN, get("/api/search").param("q", "bariatric"))).path("documents").toString())
                .doesNotContain(pdfId);
        upload(PRAC_B_PHYSICIAN, "CAM-P-1004", "x.pdf", pdf, null).andExpect(status().isForbidden());
        call(PRAC_B_PHYSICIAN, get("/api/documents/DOC-DOESNOTEXIST/file")).andExpect(status().isForbidden());
        // Viewing is not uploading.
        upload(ANESTHESIOLOGIST, "CAM-P-1004", "x.pdf", pdf, null).andExpect(status().isForbidden());
    }

    @Test
    void uploadsRejectMismatchedOversizedAndForeignFilesAndNeverOverwrite() throws Exception {
        byte[] pdf = pdf("Repeat upload test.");
        upload(NURSE, "CAM-P-1006", "notes.pdf", docx("Not a PDF"), null).andExpect(status().isBadRequest());
        upload(NURSE, "CAM-P-1006", "notes.docx", pdf, null).andExpect(status().isBadRequest());
        upload(NURSE, "CAM-P-1006", "script.exe", pdf, null).andExpect(status().isBadRequest());
        upload(NURSE, "CAM-P-1006", "fake.pdf", "just some text".getBytes(StandardCharsets.UTF_8), null).andExpect(status().isBadRequest());
        upload(NURSE, "CAM-P-1006", "empty.pdf", new byte[0], null).andExpect(status().isBadRequest());
        // An encounter of another patient cannot be used.
        upload(NURSE, "CAM-P-1006", "stay.pdf", pdf, "ENC-LRMC-E-94310").andExpect(status().isBadRequest());
        // Over the (test) 1 MB limit.
        byte[] big = new byte[1_200_000];
        System.arraycopy("%PDF-1.7".getBytes(StandardCharsets.ISO_8859_1), 0, big, 0, 8);
        upload(NURSE, "CAM-P-1006", "big.pdf", big, null).andExpect(status().is(413));

        JsonNode first = json(upload(NURSE, "CAM-P-1006", "same.pdf", pdf, null).andExpect(status().isOk()));
        JsonNode second = json(upload(NURSE, "CAM-P-1006", "same.pdf", pdf, null).andExpect(status().isOk()));
        assertThat(second.path("documentId").asString()).isNotEqualTo(first.path("documentId").asString());
        assertThat(second.path("duplicateOf").asString()).isEqualTo(first.path("documentId").asString());
        call(NURSE, get("/api/documents/" + first.path("documentId").asString() + "/file")).andExpect(status().isOk());
    }

    // ------------------------------------------------------------------ shift schedule

    @Test
    void scheduleShowsOwnShiftsOpenShiftsInOwnUnitsAndCoverage() throws Exception {
        JsonNode jordan = json(call(NURSE, get("/api/shifts/schedule")).andExpect(status().isOk()));
        assertThat(jordan.path("myShifts").size()).isEqualTo(5);
        assertThat(jordan.path("openShifts").size()).isEqualTo(2);
        jordan.path("openShifts").forEach(s -> assertThat(s.path("department").asString()).isEqualTo("Medical-Surgical Nursing"));
        Map<String, String> coverage = new HashMap<>();
        jordan.path("myShifts").forEach(s -> coverage.put(s.path("scheduleId").asString(), s.path("coverage").asString()));
        assertThat(coverage).containsEntry("USS-JLEE-D0", "FULL").containsEntry("USS-JLEE-D1", "SHORT")
                .containsEntry("USS-JLEE-D4", "UNDERSTAFFED");

        JsonNode taylor = json(call("taylor.morgan", get("/api/shifts/schedule")));
        assertThat(taylor.path("myShifts").size()).isEqualTo(4);
        taylor.path("openShifts").forEach(s -> assertThat(s.path("department").asString()).isEqualTo("Labor & Delivery"));

        JsonNode physician = json(call("alex.kim", get("/api/shifts/schedule")));
        assertThat(physician.path("myShifts").size()).isZero();
        assertThat(physician.path("openShifts").size()).isZero();

        // Timing relative to "now": during the first day shift it is current and the next one follows.
        DemoUser nurse = DemoDirectory.find(NURSE).orElseThrow();
        LocalDateTime during = demoData.currentAnchor().atTime(8, 0);
        var s = schedule.forUser(nurse);
        assertThat(s.timeZone()).isEqualTo("America/New_York");
        var at8 = scheduleAt(nurse, during);
        assertThat(at8.current().scheduleId()).isEqualTo("USS-JLEE-D0");
        assertThat(at8.next().scheduleId()).isEqualTo("USS-JLEE-D1");
        assertThat(at8.current().briefingAvailable()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private ShiftScheduleService.Schedule scheduleAt(DemoUser user, LocalDateTime now) throws Exception {
        var m = ShiftScheduleService.class.getDeclaredMethod("forUser", DemoUser.class, LocalDateTime.class);
        m.setAccessible(true);
        return (ShiftScheduleService.Schedule) m.invoke(schedule, user, now);
    }

    private ResultActions upload(String user, String patientId, String name, byte[] bytes, String encounterId) throws Exception {
        var req = multipart("/api/patients/" + patientId + "/documents")
                .file(new MockMultipartFile("file", name, "application/octet-stream", bytes));
        if (encounterId != null) req.param("encounterId", encounterId);
        return mvc.perform(req.cookie(cookie(user)));
    }

    private ResultActions call(String user, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req) throws Exception {
        return mvc.perform(req.cookie(cookie(user)));
    }

    private Cookie cookie(String user) {
        return TestSessions.as(mvc, user);
    }

    private static JsonNode json(ResultActions r) throws Exception {
        return JSON.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static Set<String> patientsOf(JsonNode searchResult) {
        Set<String> out = new HashSet<>();
        searchResult.path("patients").forEach(p -> out.add(p.path("patientId").asString()));
        return out;
    }

    static byte[] pdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(72, 700);
                cs.showText(text);
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    static byte[] docx(String text) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText(text);
            doc.write(out);
            return out.toByteArray();
        }
    }
}
