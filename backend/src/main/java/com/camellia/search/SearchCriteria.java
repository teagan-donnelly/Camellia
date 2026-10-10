package com.camellia.search;

import com.camellia.ingestion.ClinicalTagger;
import com.camellia.model.RecordType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.LocalDate;
import java.util.*;

/**
 * Structured, allow-listed search request. Natural-language queries (via Gemini or the rule-based fallback)
 * are translated into this object; only {@link SearchService} turns it into parameterized SQL.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SearchCriteria(
        String text,
        List<String> keywords,
        List<String> concepts,
        List<String> recordTypes,
        List<String> organizationIds,
        String patientId,
        String dateFrom,
        String dateTo,
        Boolean obstetricOnly,
        Boolean upcomingDeliveryOnly,
        Boolean multipleOrganizationsOnly,
        Boolean openFlagsOnly,
        Integer minMatchesPerPatient,
        String matchMode) {

    /** Concepts the search engine understands (tags assigned deterministically during ingestion). */
    public static final List<String> CONCEPTS;

    static {
        Set<String> c = new LinkedHashSet<>(ClinicalTagger.CONCEPT_TERMS.keySet());
        c.addAll(List.of("LOW_PLATELETS", "ABNORMAL_LAB", "LAB", "MEDICATION"));
        ClinicalTagger.SYMPTOM_TERMS.keySet().forEach(s -> c.add("SYMPTOM_" + s));
        CONCEPTS = List.copyOf(c);
    }

    public static final List<String> RECORD_TYPES = Arrays.stream(RecordType.values()).map(Enum::name).toList();

    public static SearchCriteria empty() {
        return new SearchCriteria(null, List.of(), List.of(), List.of(), List.of(), null, null, null, false, false, false, false, 1, "ALL");
    }

    /**
     * Returns a copy containing only allow-listed values: keywords are reduced to letters, digits, spaces and
     * hyphens (so they cannot carry SQL LIKE wildcards), unknown concepts/types/orgs are dropped and recorded.
     */
    public SearchCriteria sanitize(Set<String> knownOrgIds, Set<String> knownPatientIds, List<String> warnings) {
        List<String> kw = new ArrayList<>();
        for (String k : nz(keywords)) {
            String clean = k == null ? "" : k.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 \\-]", " ").replaceAll("\\s+", " ").trim();
            if (clean.length() >= 2 && clean.length() <= 40 && kw.size() < 8 && !kw.contains(clean)) kw.add(clean);
        }
        List<String> cs = new ArrayList<>();
        for (String c : nz(concepts)) {
            String u = c == null ? "" : c.trim().toUpperCase(Locale.ROOT);
            if (CONCEPTS.contains(u)) { if (!cs.contains(u)) cs.add(u); } else if (!u.isEmpty()) warnings.add("Ignored unknown concept '" + c + "'");
        }
        List<String> rt = new ArrayList<>();
        for (String t : nz(recordTypes)) {
            String u = t == null ? "" : t.trim().toUpperCase(Locale.ROOT);
            if (RECORD_TYPES.contains(u)) { if (!rt.contains(u)) rt.add(u); } else if (!u.isEmpty()) warnings.add("Ignored unknown record type '" + t + "'");
        }
        List<String> orgs = new ArrayList<>();
        for (String o : nz(organizationIds)) {
            String u = o == null ? "" : o.trim().toUpperCase(Locale.ROOT);
            if (knownOrgIds.contains(u)) { if (!orgs.contains(u)) orgs.add(u); } else if (!u.isEmpty()) warnings.add("Ignored unknown organization '" + o + "'");
        }
        String pid = patientId == null || patientId.isBlank() ? null : patientId.trim();
        if (pid != null && !knownPatientIds.contains(pid)) {
            warnings.add("Ignored unknown patient id '" + pid + "'");
            pid = null;
        }
        String from = validDate(dateFrom, warnings);
        String to = validDate(dateTo, warnings);
        int min = minMatchesPerPatient == null ? 1 : Math.max(1, Math.min(10, minMatchesPerPatient));
        String mode = "ANY".equalsIgnoreCase(matchMode) ? "ANY" : "ALL";
        String t = text == null ? null : (text.length() > 500 ? text.substring(0, 500) : text);
        return new SearchCriteria(t, kw, cs, rt, orgs, pid, from, to, Boolean.TRUE.equals(obstetricOnly),
                Boolean.TRUE.equals(upcomingDeliveryOnly), Boolean.TRUE.equals(multipleOrganizationsOnly),
                Boolean.TRUE.equals(openFlagsOnly), min, mode);
    }

    public boolean hasAnyFilter() {
        return !nz(keywords).isEmpty() || !nz(concepts).isEmpty() || !nz(recordTypes).isEmpty() || !nz(organizationIds).isEmpty()
                || patientId != null || dateFrom != null || dateTo != null || Boolean.TRUE.equals(obstetricOnly)
                || Boolean.TRUE.equals(upcomingDeliveryOnly) || Boolean.TRUE.equals(multipleOrganizationsOnly)
                || Boolean.TRUE.equals(openFlagsOnly);
    }

    private static String validDate(String d, List<String> warnings) {
        if (d == null || d.isBlank()) return null;
        try {
            return LocalDate.parse(d.trim()).toString();
        } catch (RuntimeException e) {
            warnings.add("Ignored invalid date '" + d + "'");
            return null;
        }
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
