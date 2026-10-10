package com.camellia.search;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SearchCriteriaTest {

    @Test
    void sanitizeDropsUnknownValuesAndStripsSqlMetacharacters() {
        List<String> warnings = new ArrayList<>();
        SearchCriteria raw = new SearchCriteria("q", List.of("platelet%'; DROP TABLE PATIENTS;--", "fx_"), List.of("FRACTURE", "MADE_UP"),
                List.of("LAB_RESULT", "SQL"), List.of("NDL", "EVIL"), "CAM-P-404", "2026-13-40", "2026-01-01",
                true, null, null, null, 99, "weird");
        SearchCriteria c = raw.sanitize(Set.of("NDL", "LRMC"), Set.of("CAM-P-1001"), warnings);

        assertThat(c.keywords()).allMatch(k -> k.matches("[a-z0-9 \\-]+"));
        assertThat(c.keywords().getFirst()).doesNotContain("'").doesNotContain("%").doesNotContain(";");
        assertThat(c.concepts()).containsExactly("FRACTURE");
        assertThat(c.recordTypes()).containsExactly("LAB_RESULT");
        assertThat(c.organizationIds()).containsExactly("NDL");
        assertThat(c.patientId()).isNull();
        assertThat(c.dateFrom()).isNull();
        assertThat(c.dateTo()).isEqualTo("2026-01-01");
        assertThat(c.minMatchesPerPatient()).isEqualTo(10);
        assertThat(c.matchMode()).isEqualTo("ALL");
        assertThat(warnings).hasSizeGreaterThanOrEqualTo(5);
    }

    @Test
    void queryBuilderUsesOnlyBoundParameters() {
        List<String> warnings = new ArrayList<>();
        SearchCriteria c = new SearchCriteria(null, List.of("o'brien"), List.of("LOW_PLATELETS"), List.of(), List.of(), null, null, null,
                true, true, true, false, 1, "ANY").sanitize(Set.of(), Set.of(), warnings);
        SearchService.Query q = new SearchService(null, null, null).buildQuery(c, LocalDateTime.of(2026, 10, 10, 0, 0));
        assertThat(q.sql()).doesNotContain("brien").doesNotContain("LOW_PLATELETS");
        assertThat(q.sql()).contains("LIKE ?").contains("IS_OBSTETRIC = TRUE").contains("COUNT(DISTINCT m.ORG_ID) > 1");
        assertThat(q.params()).contains("%|LOW_PLATELETS|%", "%|DELIVERY|%");
    }

    @Test
    void ruleBasedInterpreterMapsCommonRequests() {
        SearchCriteria c = RuleBasedQueryInterpreter.interpret("Show me every patient with a documented low platelet count", List.of(), null);
        assertThat(c.concepts()).containsExactly("LOW_PLATELETS");
        SearchCriteria multi = RuleBasedQueryInterpreter.interpret("Which patients have records from more than one hospital?", List.of(), null);
        assertThat(multi.multipleOrganizationsOnly()).isTrue();
        SearchCriteria fx = RuleBasedQueryInterpreter.interpret("Find every patient with repeated fractures", List.of(), null);
        assertThat(fx.concepts()).contains("FRACTURE");
        assertThat(fx.minMatchesPerPatient()).isEqualTo(2);
    }
}
