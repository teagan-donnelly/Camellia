package com.camellia.analysis;

import com.camellia.config.CamelliaProperties;
import com.camellia.ingestion.ClinicalTagger;
import com.camellia.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic screening rules evaluated on hand-built snapshots (no database). */
class ScreeningRulesTest {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 10, 12, 0);
    CamelliaProperties props;

    @BeforeEach
    void setUp() {
        props = new CamelliaProperties();
        CamelliaProperties.ObAnesthesia ob = props.getRules().getObAnesthesia();
        ob.setRelevantConditionTerms(new LinkedHashMap<>(Map.of(
                "Hematologic", List.of("thrombocytopenia"),
                "Cardiac", List.of("cardiomyopathy"),
                "Oncology", List.of("lymphoma"))));
        ob.setAnticoagulantTerms(List.of("enoxaparin", "heparin"));
    }

    // ------------------------------------------------------------------ builders

    static final Patient OB_PATIENT = new Patient("P1", "SYN-1", "Test", "Patient", LocalDate.of(1995, 1, 1), "F", true,
            NOW.toLocalDate().plusDays(20), "G1P0", "ORG", null, null, null);

    static ClinicalRecord rec(String id, RecordType type, String title, LocalDateTime when, String specialty, String content, String... tags) {
        Set<String> t = new TreeSet<>(ClinicalTagger.concepts(title + " " + content));
        t.addAll(List.of(tags));
        return new ClinicalRecord(id, "P1", "ORG", null, type, title, when, "Dr. Test", specialty, content, "FHIR_R4_JSON", id,
                "{}", ClinicalTagger.encode(t), NOW);
    }

    static LabResult platelet(String recId, double value, LocalDateTime when) {
        return new LabResult("L-" + recId, recId, "P1", "LAB", "777-3", "Platelets", value, null, "10*3/uL", 150.0, 400.0,
                "150-400", value < 150 ? "L" : "N", when);
    }

    static Appointment delivery(String recId, LocalDateTime when) {
        return new Appointment("A-" + recId, recId, "P1", "ORG", "Scheduled induction of labor", "Labor & Delivery", when,
                "booked", "Dr. OB", null, true);
    }

    PatientSnapshot snapshot(List<ClinicalRecord> recs, List<LabResult> labs, List<Condition> conds, List<Medication> meds,
                             List<Appointment> appts, List<Encounter> encs) {
        return new PatientSnapshot(OB_PATIENT, recs, encs, labs, conds, meds, appts, NOW);
    }

    // ------------------------------------------------------------------ OB anesthesia rule

    @Test
    void obRuleFlagsLowPlateletWithUpcomingDelivery() {
        var appt = delivery("R-APPT", NOW.plusDays(12));
        var recs = List.of(
                rec("R-APPT", RecordType.APPOINTMENT, "Scheduled induction of labor", appt.scheduledStart(), "Obstetrics", "Labor & Delivery"),
                rec("R-L1", RecordType.LAB_RESULT, "Platelets", NOW.minusDays(40), "Laboratory", "Platelets 86"),
                rec("R-L0", RecordType.LAB_RESULT, "Platelets", NOW.minusDays(150), "Laboratory", "Platelets 170"));
        var labs = List.of(platelet("R-L1", 86, NOW.minusDays(40)), platelet("R-L0", 170, NOW.minusDays(150)));
        List<CandidateFlag> out = new ObAnesthesiaReviewRule(props).evaluate(snapshot(recs, labs, List.of(), List.of(), List.of(appt), List.of()));

        assertThat(out).hasSize(1);
        CandidateFlag f = out.getFirst();
        assertThat(f.dedupKey()).isEqualTo("OB_ANESTHESIA_REVIEW|P1");
        assertThat(f.reviewPriority()).isEqualTo(Priority.EXPEDITED);
        assertThat(f.alert()).isNotNull();
        assertThat(f.alert().alertType()).isEqualTo("ANESTHESIA_OB");
        assertThat(f.evidence()).extracting(EvidenceItem::recordId).contains("R-L1", "R-APPT", "R-L0");
        assertThat(f.evidence()).filteredOn(e -> e.role().equals(EvidenceItem.TRIGGER)).extracting(EvidenceItem::recordId).containsExactly("R-L1");
        assertThat(f.detectionReason()).contains("below the laboratory reference range").contains("prototype");
    }

    @Test
    void obRuleRequiresScheduledDeliveryNotJustDueDate() {
        var recs = List.of(rec("R-L1", RecordType.LAB_RESULT, "Platelets", NOW.minusDays(40), "Laboratory", "Platelets 86"));
        var labs = List.of(platelet("R-L1", 86, NOW.minusDays(40)));
        // Patient has an estimated due date but no booked delivery-related encounter.
        assertThat(new ObAnesthesiaReviewRule(props).evaluate(snapshot(recs, labs, List.of(), List.of(), List.of(), List.of()))).isEmpty();
    }

    @Test
    void obRuleDoesNotFlagNormalResults() {
        var appt = delivery("R-APPT", NOW.plusDays(30));
        var recs = List.of(rec("R-APPT", RecordType.APPOINTMENT, "Scheduled induction of labor", appt.scheduledStart(), "Obstetrics", "Labor & Delivery"),
                rec("R-L1", RecordType.LAB_RESULT, "Platelets", NOW.minusDays(20), "Laboratory", "Platelets 240"));
        var labs = List.of(platelet("R-L1", 240, NOW.minusDays(20)));
        assertThat(new ObAnesthesiaReviewRule(props).evaluate(snapshot(recs, labs, List.of(), List.of(), List.of(appt), List.of()))).isEmpty();
    }

    @Test
    void obRuleIgnoresDeliveriesOutsideLookahead() {
        var appt = delivery("R-APPT", NOW.plusDays(200));
        var recs = List.of(rec("R-APPT", RecordType.APPOINTMENT, "Scheduled induction of labor", appt.scheduledStart(), "Obstetrics", "Labor & Delivery"),
                rec("R-L1", RecordType.LAB_RESULT, "Platelets", NOW.minusDays(20), "Laboratory", "Platelets 80"));
        var labs = List.of(platelet("R-L1", 80, NOW.minusDays(20)));
        assertThat(new ObAnesthesiaReviewRule(props).evaluate(snapshot(recs, labs, List.of(), List.of(), List.of(appt), List.of()))).isEmpty();
    }

    @Test
    void obRuleMarksMultidisciplinaryWhenSeveralConsultingSpecialtiesDocumentConcerns() {
        var appt = delivery("R-APPT", NOW.plusDays(40));
        var cardio = new Condition("C1", "R-C1", "P1", "ORG", "I42.7", "ICD-10-CM", "Cardiomyopathy, LVEF 45%", "active", null, "Dr. Heart");
        var onc = new Condition("C2", "R-C2", "P1", "ORG", "C81", "ICD-10-CM", "Hodgkin lymphoma in remission", "remission", null, "Dr. Onc");
        var med = new Medication("M1", "R-M1", "P1", "ORG", "Enoxaparin 40 mg", "40 mg daily", "SC", null, "active", null, null, "Dr. MFM");
        var recs = List.of(
                rec("R-APPT", RecordType.APPOINTMENT, "Scheduled cesarean delivery", appt.scheduledStart(), "Obstetrics", "cesarean"),
                rec("R-C1", RecordType.CONDITION, "Cardiomyopathy", NOW.minusDays(100), "Cardiology", "Cardiomyopathy"),
                rec("R-C2", RecordType.CONDITION, "Hodgkin lymphoma", NOW.minusDays(3000), "Oncology", "lymphoma"),
                rec("R-M1", RecordType.MEDICATION, "Enoxaparin", NOW.minusDays(100), null, "Enoxaparin"));
        var out = new ObAnesthesiaReviewRule(props).evaluate(snapshot(recs, List.of(), List.of(cardio, onc), List.of(med), List.of(appt), List.of()));
        assertThat(out).hasSize(1);
        assertThat(out.getFirst().alert().alertType()).isEqualTo("MULTIDISCIPLINARY_OB");
        assertThat(out.getFirst().reviewPriority()).isEqualTo(Priority.STANDARD);
        assertThat(out.getFirst().evidence()).filteredOn(e -> e.role().equals(EvidenceItem.TRIGGER)).hasSize(3);
    }

    // ------------------------------------------------------------------ pattern rules

    static Encounter enc(String id, int daysAgo, String org, String reason) {
        return new Encounter("E-" + id, "R-" + id, "P1", org, "Emergency", "ED", "Emergency Medicine", "Dr. ED",
                NOW.minusDays(daysAgo), reason, ClinicalTagger.symptomCategory(reason), ClinicalTagger.fractureRelated(reason));
    }

    @Test
    void recurrentFractureRuleUsesConfiguredThreshold() {
        var three = List.of(enc("1", 1500, "A", "wrist fracture"), enc("2", 900, "B", "rib fractures"), enc("3", 100, "C", "humerus fracture"));
        var rule = new RecurrentFractureRule(props);
        var out = rule.evaluate(snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), three));
        assertThat(out).hasSize(1);
        assertThat(out.getFirst().detectionReason()).contains("3 fracture-related encounters").contains("3 organization(s)");
        assertThat(rule.evaluate(snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), three.subList(0, 2)))).isEmpty();
        // Encounters outside the lookback window do not count.
        var old = List.of(enc("1", 4000, "A", "fracture"), enc("2", 3000, "B", "fracture"), enc("3", 100, "C", "fracture"));
        assertThat(rule.evaluate(snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), old))).isEmpty();
    }

    @Test
    void repeatedAbnormalLabRuleGroupsByTestCodeAcrossOrganizations() {
        List<LabResult> labs = List.of(
                new LabResult("L1", "R1", "P1", "NDL", "17861-6", "Calcium", 10.9, null, "mg/dL", 8.6, 10.3, "8.6-10.3", "H", NOW.minusDays(400)),
                new LabResult("L2", "R2", "P1", "CHCH", "17861-6", "Calcium", 11.2, null, "mg/dL", 8.6, 10.3, "8.6-10.3", "H", NOW.minusDays(200)),
                new LabResult("L3", "R3", "P1", "NDL", "17861-6", "Calcium", 11.4, null, "mg/dL", 8.6, 10.3, "8.6-10.3", "H", NOW.minusDays(60)),
                new LabResult("L4", "R4", "P1", "NDL", "17861-6", "Calcium", 9.5, null, "mg/dL", 8.6, 10.3, "8.6-10.3", "N", NOW.minusDays(30)),
                new LabResult("L5", "R5", "P1", "NDL", "4548-4", "A1c", 6.6, null, "%", 4.0, 5.6, "4.0-5.6", "H", NOW.minusDays(30)));
        var out = new RepeatedAbnormalLabRule(props).evaluate(snapshot(List.of(), labs, List.of(), List.of(), List.of(), List.of()));
        assertThat(out).hasSize(1);
        CandidateFlag f = out.getFirst();
        assertThat(f.dedupKey()).isEqualTo("REPEATED_ABNORMAL_LAB|P1|17861-6");
        assertThat(f.detectionReason()).contains("3 Calcium results above").contains("2 organization(s)");
        assertThat(f.evidence()).filteredOn(e -> e.role().equals(EvidenceItem.TRIGGER)).hasSize(3);
        assertThat(f.evidence()).filteredOn(e -> e.role().equals(EvidenceItem.SUPPORTING)).extracting(EvidenceItem::recordId).containsExactly("R4");
    }

    @Test
    void repeatedSymptomRuleCountsEncountersPerCategory() {
        var encs = List.of(enc("1", 300, "RUC", "Headache for two days"), enc("2", 200, "CHCH", "Severe headache"),
                enc("3", 50, "CHCH", "Migraine-like headache"), enc("4", 40, "CHCH", "Back pain after lifting"));
        var out = new RepeatedSymptomRule(props).evaluate(snapshot(List.of(), List.of(), List.of(), List.of(), List.of(), encs));
        assertThat(out).hasSize(1);
        assertThat(out.getFirst().dedupKey()).isEqualTo("REPEATED_SYMPTOM|P1|HEADACHE");
    }

    @Test
    void fingerprintIsOrderIndependentAndChangesWithEvidence() {
        var a = List.of(new EvidenceItem("R1", "TRIGGER", "x"), new EvidenceItem("R2", "SUPPORTING", "y"));
        var b = List.of(new EvidenceItem("R2", "SUPPORTING", "different text"), new EvidenceItem("R1", "TRIGGER", "z"));
        var c = List.of(new EvidenceItem("R1", "TRIGGER", "x"), new EvidenceItem("R3", "SUPPORTING", "y"));
        assertThat(AnalysisService.fingerprint(a)).isEqualTo(AnalysisService.fingerprint(b));
        assertThat(AnalysisService.fingerprint(a)).isNotEqualTo(AnalysisService.fingerprint(c));
    }
}
