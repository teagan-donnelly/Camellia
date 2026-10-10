package com.camellia.ai;

import com.camellia.ai.AiDtos.RawAnswer;
import com.camellia.ai.AiDtos.RawStatement;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CitationValidatorTest {

    static final Set<String> ALLOWED = Set.of("REC-NDL-ACC-55012-1", "REC-LRMC-A-90311");

    @Test
    void keepsValidCitationsAndRemovesFabricatedOnes() {
        RawAnswer raw = new RawAnswer(
                "Platelets were 86 [REC-NDL-ACC-55012-1] and a delivery is scheduled [REC-LRMC-A-90311]. Also see [REC-FAKE-999].",
                List.of(new RawStatement("Platelet count 86 x10^3/uL", List.of("REC-NDL-ACC-55012-1", "REC-INVENTED-1")),
                        new RawStatement("A fabricated fact", List.of("REC-NOT-REAL")),
                        new RawStatement("Induction scheduled", List.of())),
                List.of(new RawStatement("May warrant review [REC-LRMC-A-90311]", List.of())),
                List.of("No hematology consult note"), List.of("Q1", "Q2", "Q3", "Q4"));
        CitationValidator.Result r = CitationValidator.validate(raw, ALLOWED);

        assertThat(r.answer()).contains("REC-NDL-ACC-55012-1").contains("REC-LRMC-A-90311")
                .doesNotContain("REC-FAKE-999").contains(CitationValidator.REMOVED);
        assertThat(r.documentedFacts()).hasSize(1);
        assertThat(r.documentedFacts().getFirst().sourceRecordIds()).containsExactly("REC-NDL-ACC-55012-1");
        assertThat(r.uncitedFactsRemoved()).isEqualTo(2);           // fabricated-only + uncited
        assertThat(r.citationsRemoved()).isEqualTo(3);              // REC-FAKE-999, REC-INVENTED-1, REC-NOT-REAL
        assertThat(r.interpretation().getFirst().sourceRecordIds()).containsExactly("REC-LRMC-A-90311");
        assertThat(r.citedIds()).containsExactlyInAnyOrder("REC-NDL-ACC-55012-1", "REC-LRMC-A-90311");
        assertThat(r.followUps()).hasSize(3);
    }

    @Test
    void handlesNullFieldsFromModel() {
        CitationValidator.Result r = CitationValidator.validate(new RawAnswer(null, null, null, null, null), ALLOWED);
        assertThat(r.answer()).isEmpty();
        assertThat(r.documentedFacts()).isEmpty();
        assertThat(r.citedIds()).isEmpty();
    }
}
