package com.camellia.search;

import com.camellia.ingestion.ClinicalTagger;
import com.camellia.model.ClinicalRecord;
import com.camellia.model.Organization;
import com.camellia.model.Patient;
import com.camellia.repository.ClinicalDataRepository;
import com.camellia.repository.DocumentRepository;
import com.camellia.repository.OrganizationRepository;
import com.camellia.repository.PatientRepository;
import com.camellia.security.AuthorizationService;
import com.camellia.security.AuthorizationService.AccessScope;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Executes {@link SearchCriteria} against the clinical record index. SQL is assembled only from fixed fragments;
 * every user- or AI-derived value is a bound parameter.
 */
@Service
public class SearchService {

    static final int MAX_ROWS = 400;

    private final ClinicalDataRepository clinical;
    private final PatientRepository patients;
    private final OrganizationRepository organizations;
    private final DocumentRepository documents;

    public SearchService(ClinicalDataRepository clinical, PatientRepository patients, OrganizationRepository organizations,
                         DocumentRepository documents) {
        this.documents = documents;
        this.clinical = clinical;
        this.patients = patients;
        this.organizations = organizations;
    }

    public record RecordHit(String recordId, String patientId, String patientName, String patientDisplayId, String orgId,
                            String orgName, String recordType, String title, LocalDateTime recordDate, String author,
                            String specialty, String snippet, List<String> tags, String sourceFormat) {}

    public record PatientGroup(String patientId, String patientName, String displayId, int matchCount, int distinctDates,
                               List<String> organizations, List<RecordHit> records) {}

    /** A keyword match in an uploaded document (PDF / Word). Kept apart from records: never part of a Gemini context. */
    public record DocumentHit(String documentId, String patientId, String patientName, String patientDisplayId, String title,
                              String fileName, String fileType, LocalDateTime uploadedAt, String uploadedBy,
                              String encounterId, String snippet) {}

    public record SearchResult(SearchCriteria criteria, List<String> warnings, int totalRecords, int totalPatients,
                               List<PatientGroup> patients, boolean truncated, List<DocumentHit> documents) {}

    static final int MAX_DOCUMENT_HITS = 25;

    public Set<String> knownOrgIds() {
        return organizations.findAll().stream().map(Organization::orgId).collect(Collectors.toSet());
    }

    public Set<String> knownPatientIds() {
        return patients.existingIds();
    }

    /** Searches only records of patients inside the caller's access scope (enforced in SQL, not by post-filtering). */
    public SearchResult search(SearchCriteria raw, AccessScope scope) {
        List<String> warnings = new ArrayList<>();
        Set<String> allowedPatients = knownPatientIds().stream().filter(scope::allowsPatient).collect(Collectors.toSet());
        SearchCriteria c = (raw == null ? SearchCriteria.empty() : raw).sanitize(knownOrgIds(), allowedPatients, warnings);
        Query q = buildQuery(c, LocalDateTime.now(), scope);
        List<ClinicalRecord> rows = clinical.queryRecords(q.sql(), q.params());
        boolean truncated = rows.size() >= MAX_ROWS;

        Map<String, Patient> pById = patients.findAll().stream().filter(p -> scope.allowsPatient(p.patientId()))
                .collect(Collectors.toMap(Patient::patientId, Function.identity()));
        Map<String, String> orgNames = organizations.findAll().stream().collect(Collectors.toMap(Organization::orgId, Organization::name));

        Map<String, List<ClinicalRecord>> byPatient = rows.stream()
                .collect(Collectors.groupingBy(ClinicalRecord::patientId, LinkedHashMap::new, Collectors.toList()));
        List<PatientGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<ClinicalRecord>> e : byPatient.entrySet()) {
            List<ClinicalRecord> recs = e.getValue();
            int distinctDates = (int) recs.stream().map(r -> r.recordDate() == null ? LocalDate.MIN : r.recordDate().toLocalDate()).distinct().count();
            if (distinctDates < c.minMatchesPerPatient()) continue;
            Patient p = pById.get(e.getKey());
            List<RecordHit> hits = recs.stream().map(r -> hit(r, p, orgNames, c)).toList();
            groups.add(new PatientGroup(e.getKey(), p == null ? e.getKey() : p.fullName(), p == null ? null : p.displayId(),
                    recs.size(), distinctDates, recs.stream().map(r -> orgNames.getOrDefault(r.orgId(), r.orgId())).distinct().sorted().toList(),
                    hits));
        }
        groups.sort(Comparator.comparingInt(PatientGroup::matchCount).reversed());
        int total = groups.stream().mapToInt(PatientGroup::matchCount).sum();
        return new SearchResult(c, warnings, total, groups.size(), groups, truncated, documentHits(c, scope, pById));
    }

    /**
     * Plain keyword search over uploaded documents, inside the same patient scope. Record-specific filters (record type,
     * organization, dates, populations) do not apply to documents, so documents are searched only without them.
     */
    private List<DocumentHit> documentHits(SearchCriteria c, AccessScope scope, Map<String, Patient> pById) {
        boolean recordOnlyFilters = !c.recordTypes().isEmpty() || !c.organizationIds().isEmpty() || c.dateFrom() != null
                || c.dateTo() != null || Boolean.TRUE.equals(c.obstetricOnly()) || Boolean.TRUE.equals(c.upcomingDeliveryOnly())
                || Boolean.TRUE.equals(c.multipleOrganizationsOnly()) || Boolean.TRUE.equals(c.openFlagsOnly());
        if (c.keywords().isEmpty() || recordOnlyFilters) return List.of();
        Collection<String> ids = scope.networkWide() ? null : scope.patientIds();
        return documents.search(c.keywords(), "ALL".equals(c.matchMode()), ids, c.patientId(), c.traits(), MAX_DOCUMENT_HITS)
                .stream()
                .filter(m -> scope.allowsPatient(m.meta().patientId()))
                .map(m -> {
                    var d = m.meta();
                    Patient p = pById.get(d.patientId());
                    return new DocumentHit(d.documentId(), d.patientId(), p == null ? d.patientId() : p.fullName(),
                            p == null ? null : p.displayId(), d.title(), d.originalFilename(), d.fileType(), d.uploadedAt(),
                            d.uploadedBy(), d.encounterId(), snippet(m.text(), c));
                })
                .toList();
    }

    record Query(String sql, List<Object> params) {}

    /** Package-visible for tests: builds the parameterized SQL for sanitized criteria. */
    Query buildQuery(SearchCriteria c, LocalDateTime now) {
        return buildQuery(c, now, new AccessScope("system", null, true));
    }

    Query buildQuery(SearchCriteria c, LocalDateTime now, AccessScope scope) {
        StringBuilder sql = new StringBuilder("""
                SELECT r.* FROM CLINICAL_RECORDS r JOIN PATIENTS p ON p.PATIENT_ID = r.PATIENT_ID WHERE 1=1""");
        List<Object> params = new ArrayList<>();
        if (!scope.networkWide()) {
            if (scope.patientIds().isEmpty()) {
                sql.append(" AND 1=0");
            } else {
                sql.append(" AND r.PATIENT_ID IN (").append(placeholders(scope.patientIds().size())).append(")");
                params.addAll(scope.patientIds().stream().sorted().toList());
            }
        }

        List<String> termClauses = new ArrayList<>();
        List<Object> termParams = new ArrayList<>();
        for (String k : c.keywords()) {
            termClauses.add("(LOWER(r.CONTENT_TEXT) LIKE ? OR LOWER(r.TITLE) LIKE ? OR LOWER(p.FIRST_NAME || ' ' || p.LAST_NAME) LIKE ?)");
            String like = "%" + k + "%";
            termParams.addAll(List.of(like, like, like));
        }
        for (String concept : c.concepts()) {
            termClauses.add("r.CATEGORY_TAGS LIKE ?");
            termParams.add("%|" + concept + "|%");
        }
        if (!termClauses.isEmpty()) {
            String joiner = "ANY".equals(c.matchMode()) ? " OR " : " AND ";
            sql.append(" AND (").append(String.join(joiner, termClauses)).append(")");
            params.addAll(termParams);
        }
        List<String> traits = c.traits() == null ? List.of() : c.traits();
        if (!traits.isEmpty()) {
            // Patient-level filter on structured traits (derived from coded diagnoses, never from free text).
            String in = placeholders(traits.size());
            sql.append(" AND r.PATIENT_ID IN (SELECT t.PATIENT_ID FROM PATIENT_TRAITS t WHERE t.STATUS = 'ACTIVE' AND t.TRAIT_CODE IN (")
               .append(in).append("))");
            params.addAll(traits);
            if (termClauses.isEmpty() && c.recordTypes().isEmpty()) {
                // Trait-only search: list the diagnoses that establish the trait, not every record of those patients.
                sql.append(" AND r.RECORD_ID IN (SELECT t2.SOURCE_RECORD_ID FROM PATIENT_TRAITS t2 WHERE t2.STATUS = 'ACTIVE' AND t2.TRAIT_CODE IN (")
                   .append(in).append("))");
                params.addAll(traits);
            }
        }
        if (!c.recordTypes().isEmpty()) {
            sql.append(" AND r.RECORD_TYPE IN (").append(placeholders(c.recordTypes().size())).append(")");
            params.addAll(c.recordTypes());
        }
        if (!c.organizationIds().isEmpty()) {
            sql.append(" AND r.ORG_ID IN (").append(placeholders(c.organizationIds().size())).append(")");
            params.addAll(c.organizationIds());
        }
        if (c.patientId() != null) {
            sql.append(" AND r.PATIENT_ID = ?");
            params.add(c.patientId());
        }
        if (c.dateFrom() != null) {
            sql.append(" AND r.RECORD_DATE >= ?");
            params.add(Timestamp.valueOf(LocalDate.parse(c.dateFrom()).atStartOfDay()));
        }
        if (c.dateTo() != null) {
            sql.append(" AND r.RECORD_DATE < ?");
            params.add(Timestamp.valueOf(LocalDate.parse(c.dateTo()).plusDays(1).atStartOfDay()));
        }
        if (Boolean.TRUE.equals(c.obstetricOnly())) {
            sql.append(" AND p.IS_OBSTETRIC = TRUE");
        }
        if (Boolean.TRUE.equals(c.upcomingDeliveryOnly())) {
            sql.append(" AND r.PATIENT_ID IN (SELECT a.PATIENT_ID FROM APPOINTMENTS a JOIN CLINICAL_RECORDS ar ON ar.RECORD_ID = a.RECORD_ID")
               .append(" WHERE a.SCHEDULED_START >= ? AND a.SCHEDULED_START <= ? AND ar.CATEGORY_TAGS LIKE ?)");
            params.add(Timestamp.valueOf(now));
            params.add(Timestamp.valueOf(now.plusDays(120)));
            params.add("%|DELIVERY|%");
        }
        if (Boolean.TRUE.equals(c.multipleOrganizationsOnly())) {
            sql.append(" AND r.PATIENT_ID IN (SELECT m.PATIENT_ID FROM CLINICAL_RECORDS m GROUP BY m.PATIENT_ID HAVING COUNT(DISTINCT m.ORG_ID) > 1)");
        }
        if (Boolean.TRUE.equals(c.openFlagsOnly())) {
            sql.append(" AND r.PATIENT_ID IN (SELECT f.PATIENT_ID FROM CLINICAL_FLAGS f WHERE f.STATUS IN ('UNREVIEWED', 'IN_REVIEW')");
            if (!scope.anesthesia()) {
                sql.append(" AND f.RULE_ID <> ?");
                params.add(AuthorizationService.ANESTHESIA_RULE);
            }
            sql.append(")");
        }
        sql.append(" ORDER BY r.RECORD_DATE DESC LIMIT ").append(MAX_ROWS);
        return new Query(sql.toString(), params);
    }

    private RecordHit hit(ClinicalRecord r, Patient p, Map<String, String> orgNames, SearchCriteria c) {
        List<String> tags = r.categoryTags() == null ? List.of()
                : Arrays.stream(r.categoryTags().split("\\|")).filter(s -> !s.isBlank()).toList();
        return new RecordHit(r.recordId(), r.patientId(), p == null ? null : p.fullName(), p == null ? null : p.displayId(),
                r.orgId(), orgNames.getOrDefault(r.orgId(), r.orgId()), r.recordType().name(), r.title(), r.recordDate(),
                r.author(), r.specialty(), snippet(r.contentText(), c), tags, r.sourceFormat());
    }

    /** ~240 characters around the first keyword or concept term match. */
    static String snippet(String content, SearchCriteria c) {
        if (content == null) return "";
        String lower = content.toLowerCase(Locale.ROOT);
        int idx = -1;
        List<String> needles = new ArrayList<>(c.keywords());
        for (String concept : c.concepts()) needles.addAll(ClinicalTagger.CONCEPT_TERMS.getOrDefault(concept, List.of()));
        for (String n : needles) {
            int i = lower.indexOf(n.toLowerCase(Locale.ROOT).trim());
            if (i >= 0 && (idx < 0 || i < idx)) idx = i;
        }
        int start = Math.max(0, idx < 0 ? 0 : idx - 80);
        int end = Math.min(content.length(), start + 240);
        return (start > 0 ? "…" : "") + content.substring(start, end).trim() + (end < content.length() ? "…" : "");
    }

    private static String placeholders(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }
}
