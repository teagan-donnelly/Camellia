package com.camellia.search;

import com.camellia.model.ClinicalRecord;
import com.camellia.model.EvidenceLink;
import com.camellia.model.Organization;
import com.camellia.repository.ClinicalDataRepository;
import com.camellia.repository.FlagRepository;
import com.camellia.repository.OrganizationRepository;
import com.camellia.repository.PatientRepository;
import com.camellia.security.AuthorizationService;
import com.camellia.security.AuthorizationService.AccessScope;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Searches clinical flags (and their coordination alerts) with fixed SQL fragments and bound parameters only.
 * The user's patient scope and anesthesia permission are applied inside the SQL, so restricted flags are never read.
 */
@Service
public class FlagSearchService {

    static final int MAX_ROWS = 200;

    private final JdbcTemplate jdbc;
    private final FlagRepository flags;
    private final ClinicalDataRepository clinical;
    private final OrganizationRepository organizations;
    private final PatientRepository patients;

    public FlagSearchService(JdbcTemplate jdbc, FlagRepository flags, ClinicalDataRepository clinical,
                             OrganizationRepository organizations, PatientRepository patients) {
        this.jdbc = jdbc;
        this.flags = flags;
        this.clinical = clinical;
        this.organizations = organizations;
        this.patients = patients;
    }

    public record SourceRef(String recordId, String title, String orgName, LocalDateTime recordDate, String role) {}

    public record FlagHit(String flagId, String patientId, String patientName, String displayId, String title, String ruleId,
                          String category, String status, String alertId, String alertStatus, boolean unresolved,
                          String reviewPriority, int evidenceCount, List<String> specialties, List<SourceRef> sourceRecords,
                          String detectionReason, LocalDateTime updatedAt, String link) {}

    public record FlagSearchResult(FlagSearchCriteria criteria, List<FlagHit> results, int totalFlags, int totalPatients,
                                   List<String> warnings, String notice) {}

    public record Facets(Map<String, String> categories, List<String> statuses, List<String> priorities, List<String> specialties) {}

    public FlagSearchResult search(FlagSearchCriteria raw, AccessScope scope) {
        Set<String> allowed = patients.existingIds().stream().filter(scope::allowsPatient).collect(Collectors.toSet());
        FlagSearchCriteria.Sanitized s = (raw == null ? new FlagSearchCriteria(null, null, "ANY", null, null, null, null, null) : raw)
                .sanitize(scope.anesthesia(), allowed);
        FlagSearchCriteria c = s.criteria();
        if (s.restrictedCategoryRequested() && c.categories().isEmpty()) {
            return new FlagSearchResult(c, List.of(), 0, 0, s.warnings(),
                    "Anesthesia OB Alerts are not available to your role, so they were not searched.");
        }
        List<Object> params = new ArrayList<>();
        String sql = buildSql(c, scope, params);
        List<Row> rows = jdbc.query(sql, (rs, i) -> new Row(rs.getString("FLAG_ID"), rs.getString("PATIENT_ID"),
                rs.getString("FIRST_NAME") + " " + rs.getString("LAST_NAME"), rs.getString("DISPLAY_ID"), rs.getString("TITLE"),
                rs.getString("RULE_ID"), rs.getString("STATUS"), rs.getString("ALERT_ID"), rs.getString("ALERT_STATUS"),
                rs.getString("REVIEW_PRIORITY"), rs.getInt("EVIDENCE_COUNT"), rs.getString("DETECTION_REASON"),
                toLdt(rs.getTimestamp("UPDATED_AT"))), params.toArray());

        Map<String, List<EvidenceLink>> evidence = flags.evidenceFor(rows.stream().map(Row::flagId).toList()).stream()
                .collect(Collectors.groupingBy(EvidenceLink::ownerId));
        Map<String, ClinicalRecord> records = clinical.findRecords(evidence.values().stream().flatMap(List::stream)
                        .map(EvidenceLink::recordId).distinct().toList()).stream()
                .collect(Collectors.toMap(ClinicalRecord::recordId, Function.identity()));
        Map<String, String> orgNames = organizations.findAll().stream().collect(Collectors.toMap(Organization::orgId, Organization::name));

        List<FlagHit> hits = rows.stream().map(r -> {
            List<EvidenceLink> ev = evidence.getOrDefault(r.flagId(), List.of());
            List<String> specs = ev.stream().map(e -> records.get(e.recordId())).filter(Objects::nonNull)
                    .map(ClinicalRecord::specialty).filter(Objects::nonNull).distinct().sorted().toList();
            List<SourceRef> sources = ev.stream().filter(e -> "TRIGGER".equals(e.role())).limit(4)
                    .map(e -> records.get(e.recordId())).filter(Objects::nonNull)
                    .map(rec -> new SourceRef(rec.recordId(), rec.title(), orgNames.getOrDefault(rec.orgId(), rec.orgId()), rec.recordDate(), "TRIGGER"))
                    .toList();
            boolean unresolved = r.alertId() != null ? !Set.of("RESOLVED", "DISMISSED").contains(r.alertStatus())
                    : Set.of("UNREVIEWED", "IN_REVIEW").contains(r.status());
            String link = r.alertId() != null ? "/alerts/" + r.alertId() : "/flags/" + r.flagId();
            return new FlagHit(r.flagId(), r.patientId(), r.patientName(), r.displayId(), r.title(), r.ruleId(),
                    FlagSearchCriteria.CATEGORY_LABELS.getOrDefault(r.ruleId(), r.ruleId()), r.status(), r.alertId(), r.alertStatus(),
                    unresolved, r.priority(), r.evidenceCount(), specs, sources, r.reason(), r.updatedAt(), link);
        }).toList();
        int patientsCount = (int) hits.stream().map(FlagHit::patientId).distinct().count();
        return new FlagSearchResult(c, hits, hits.size(), patientsCount, s.warnings(),
                s.restrictedCategoryRequested() ? "Some requested categories are not available to your role and were excluded." : null);
    }

    /** Filter options limited to what the user may see. */
    public Facets facets(AccessScope scope) {
        Map<String, String> cats = new LinkedHashMap<>();
        FlagSearchCriteria.categoriesFor(scope.anesthesia()).forEach(c -> cats.put(c, FlagSearchCriteria.CATEGORY_LABELS.get(c)));
        List<String> visibleFlagIds = flags.find(null, null, null).stream().filter(scope::allowsFlag).map(f -> f.flagId()).toList();
        List<String> specialties = clinical.findRecords(flags.evidenceFor(visibleFlagIds).stream().map(EvidenceLink::recordId).distinct().toList())
                .stream().map(ClinicalRecord::specialty).filter(Objects::nonNull).distinct().sorted().toList();
        return new Facets(cats, FlagSearchCriteria.STATUSES, FlagSearchCriteria.PRIORITIES, specialties);
    }

    record Row(String flagId, String patientId, String patientName, String displayId, String title, String ruleId, String status,
               String alertId, String alertStatus, String priority, int evidenceCount, String reason, LocalDateTime updatedAt) {}

    /** Package-visible for tests. */
    String buildSql(FlagSearchCriteria c, AccessScope scope, List<Object> params) {
        StringBuilder sql = new StringBuilder("""
                SELECT f.FLAG_ID, f.PATIENT_ID, f.TITLE, f.RULE_ID, f.STATUS, f.REVIEW_PRIORITY, f.DETECTION_REASON, f.UPDATED_AT,
                       p.FIRST_NAME, p.LAST_NAME, p.DISPLAY_ID, a.ALERT_ID, a.STATUS AS ALERT_STATUS,
                       (SELECT COUNT(*) FROM FLAG_EVIDENCE fe WHERE fe.FLAG_ID = f.FLAG_ID) AS EVIDENCE_COUNT
                FROM CLINICAL_FLAGS f
                JOIN PATIENTS p ON p.PATIENT_ID = f.PATIENT_ID
                LEFT JOIN COORDINATION_ALERTS a ON a.FLAG_ID = f.FLAG_ID
                WHERE 1=1""");
        // Authorization first: patient scope and anesthesia workflow restriction.
        if (!scope.networkWide()) {
            if (scope.patientIds().isEmpty()) sql.append(" AND 1=0");
            else {
                sql.append(" AND f.PATIENT_ID IN (").append(ph(scope.patientIds().size())).append(")");
                params.addAll(scope.patientIds().stream().sorted().toList());
            }
        }
        if (!scope.anesthesia()) {
            sql.append(" AND f.RULE_ID <> ?");
            params.add(AuthorizationService.ANESTHESIA_RULE);
        }
        if (c.patientId() != null) {
            sql.append(" AND f.PATIENT_ID = ?");
            params.add(c.patientId());
        }
        if (!c.categories().isEmpty()) {
            sql.append(" AND f.RULE_ID IN (").append(ph(c.categories().size())).append(")");
            params.addAll(c.categories());
        }
        String openExpr = "((a.ALERT_ID IS NULL AND f.STATUS IN ('UNREVIEWED', 'IN_REVIEW')) OR (a.ALERT_ID IS NOT NULL AND a.STATUS NOT IN ('RESOLVED', 'DISMISSED')))";
        if ("OPEN".equals(c.statusGroup())) sql.append(" AND ").append(openExpr);
        if ("CLOSED".equals(c.statusGroup())) sql.append(" AND NOT ").append(openExpr);
        if (!c.statuses().isEmpty()) {
            sql.append(" AND f.STATUS IN (").append(ph(c.statuses().size())).append(")");
            params.addAll(c.statuses());
        }
        if (!c.priorities().isEmpty()) {
            sql.append(" AND f.REVIEW_PRIORITY IN (").append(ph(c.priorities().size())).append(")");
            params.addAll(c.priorities());
        }
        if (c.text() != null) {
            for (String word : c.text().split(" ")) {
                if (word.length() < 2) continue;
                sql.append(" AND (LOWER(f.TITLE) LIKE ? OR LOWER(f.DETECTION_REASON) LIKE ? OR LOWER(p.FIRST_NAME || ' ' || p.LAST_NAME) LIKE ?)");
                String like = "%" + word + "%";
                params.addAll(List.of(like, like, like));
            }
        }
        if (!c.specialties().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM FLAG_EVIDENCE se JOIN CLINICAL_RECORDS sr ON sr.RECORD_ID = se.RECORD_ID")
               .append(" WHERE se.FLAG_ID = f.FLAG_ID AND LOWER(sr.SPECIALTY) IN (").append(ph(c.specialties().size())).append("))");
            params.addAll(c.specialties());
        }
        if (!c.concepts().isEmpty()) {
            sql.append(" AND EXISTS (SELECT 1 FROM FLAG_EVIDENCE ce JOIN CLINICAL_RECORDS cr ON cr.RECORD_ID = ce.RECORD_ID")
               .append(" WHERE ce.FLAG_ID = f.FLAG_ID AND (")
               .append(String.join(" OR ", Collections.nCopies(c.concepts().size(), "cr.CATEGORY_TAGS LIKE ?"))).append("))");
            c.concepts().forEach(x -> params.add("%|" + x + "|%"));
        }
        sql.append(" ORDER BY CASE WHEN f.REVIEW_PRIORITY = 'EXPEDITED' THEN 0 ELSE 1 END, f.UPDATED_AT DESC LIMIT ").append(MAX_ROWS);
        return sql.toString();
    }

    private static String ph(int n) {
        return String.join(", ", Collections.nCopies(n, "?"));
    }

    private static LocalDateTime toLdt(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }
}
