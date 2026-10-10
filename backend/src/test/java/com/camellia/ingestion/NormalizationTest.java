package com.camellia.ingestion;

import com.camellia.model.LabResult;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class NormalizationTest {

    static final LocalDate ANCHOR = LocalDate.of(2026, 10, 10);

    @Test
    void relativeDateTokensRenderPerFormat() {
        assertThat(RelativeDates.render("x @D-10 y @D+2T07:30", ANCHOR, false)).isEqualTo("x 2026-09-30 y 2026-10-12T07:30");
        assertThat(RelativeDates.render("@D-43T0815", ANCHOR, true)).isEqualTo("202608280815");
        assertThat(RelativeDates.dateTime("202608280815", ANCHOR)).isEqualTo(LocalDateTime.of(2026, 8, 28, 8, 15));
    }

    @Test
    void hl7ResultsAreNormalizedWithReferenceRangesAndComments() {
        String feed = RelativeDates.render("""
                MSH|^~\\&|NORTHGATE_LIS|NDL|X|Y|@D-1T1630||ORU^R01|M1|P|2.5.1
                PID|1||NDL-1^^^NDL^MR||DOE^JANE||19950614|F
                OBR|1||ACC-1|58410-2^CBC panel^LN|||@D-1T0800|||||||||4401^VOSS^HELENA|||||||F
                OBX|1|NM|777-3^Platelets^LN||79|10*3/uL|150-400|L|||F|||@D-1T0800
                NTE|1||Large platelets present.
                OBX|2|NM|718-7^Hemoglobin^LN||11.5|g/dL|11.5-15.5|N|||F|||@D-1T0800
                """, ANCHOR, true);
        SourceContext ctx = new SourceContext(ANCHOR, Map.of(SourceContext.indexKey("NDL", "NDL-1"), "P1"), LocalDateTime.now());
        NormalizedBatch b = new Hl7v2LabNormalizer().normalize(feed, "NDL", ctx);

        assertThat(b.labs).hasSize(2);
        LabResult plt = b.labs.getFirst();
        assertThat(plt.testCode()).isEqualTo("777-3");
        assertThat(plt.valueNum()).isEqualTo(79.0);
        assertThat(plt.refLow()).isEqualTo(150.0);
        assertThat(plt.isBelowReference()).isTrue();
        assertThat(b.records.getFirst().recordId()).isEqualTo("REC-NDL-ACC-1-1");
        assertThat(b.records.getFirst().contentText()).contains("Large platelets present").contains("Platelets");
        assertThat(b.records.getFirst().categoryTags()).contains("|LOW_PLATELETS|").contains("|ABNORMAL_LAB|");
        assertThat(b.records.getFirst().author()).isEqualTo("Ordered by Dr. Helena Voss");
        assertThat(b.records.get(1).categoryTags()).doesNotContain("ABNORMAL_LAB");
    }

    @Test
    void unmatchedIdentitiesAreSkippedWithWarning() {
        String feed = "MSH|^~\\&|A|NDL\nPID|1||UNKNOWN^^^NDL^MR\nOBX|1|NM|777-3^Platelets^LN||79|10*3/uL|150-400|L\n";
        SourceContext ctx = new SourceContext(ANCHOR, Map.of(), LocalDateTime.now());
        NormalizedBatch b = new Hl7v2LabNormalizer().normalize(feed, "NDL", ctx);
        assertThat(b.labs).isEmpty();
        assertThat(b.warnings).anyMatch(w -> w.contains("Unmatched"));
    }

    @Test
    void plainTextNotesBecomeEncounterNoteAndCondition() {
        String text = """
                === RIVERBEND URGENT CARE ===
                NOTE-ID: RUC-1
                MRN: RUC-9
                DATE: 2026-01-01T10:00
                PROVIDER: PA Test
                CHIEF-COMPLAINT: Left wrist pain after fall
                DIAGNOSIS: S52.502A | Closed fracture of left distal radius
                ---
                Body text here.
                === END ===
                """;
        SourceContext ctx = new SourceContext(ANCHOR, Map.of(SourceContext.indexKey("RUC", "RUC-9"), "P9"), LocalDateTime.now());
        NormalizedBatch b = new PlainTextNoteNormalizer().normalize(text, "RUC", ctx);
        assertThat(b.encounters).hasSize(1);
        assertThat(b.encounters.getFirst().fractureRelated()).isTrue();
        assertThat(b.conditions).hasSize(1);
        assertThat(b.records).hasSize(3);
    }

    @Test
    void taggerMatchesWordStartsOnly() {
        assertThat(ClinicalTagger.concepts("Laboratory results")).doesNotContain("OBSTETRIC");
        assertThat(ClinicalTagger.concepts("Spontaneous labor at 39 weeks")).contains("OBSTETRIC");
        assertThat(ClinicalTagger.symptomCategory("Severe headache with photophobia")).isEqualTo("HEADACHE");
        assertThat(ClinicalTagger.fractureRelated("Right chest wall pain; fractures of ribs")).isTrue();
    }
}
