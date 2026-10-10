package com.camellia.search;

import com.camellia.security.AuthorizationService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.*;

/**
 * Allow-listed criteria for searching clinical flags/alerts. Built from the Clinical Alerts filter bar or from a
 * Gemini interpretation; only {@link FlagSearchService} turns it into parameterized SQL.
 *
 * @param statusGroup OPEN (unresolved), CLOSED, or ANY. For flags with a coordination alert, the alert's workflow
 *                    status decides; otherwise the flag's review status.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record FlagSearchCriteria(String text, List<String> categories, String statusGroup, List<String> statuses,
                                 List<String> priorities, List<String> specialties, List<String> concepts, String patientId) {

    public static final Map<String, String> CATEGORY_LABELS;

    static {
        Map<String, String> m = new LinkedHashMap<>();
        m.put(AuthorizationService.ANESTHESIA_RULE, "Obstetric anesthesia planning");
        m.put("RECURRENT_FRACTURE", "Recurrent fractures");
        m.put("REPEATED_ABNORMAL_LAB", "Repeated abnormal labs");
        m.put("REPEATED_SYMPTOM", "Repeated symptoms");
        CATEGORY_LABELS = Collections.unmodifiableMap(m);
    }

    public static final List<String> STATUSES = List.of("UNREVIEWED", "IN_REVIEW", "REVIEWED", "DISMISSED");
    public static final List<String> PRIORITIES = List.of("EXPEDITED", "STANDARD");
    public static final List<String> STATUS_GROUPS = List.of("OPEN", "CLOSED", "ANY");

    public static List<String> categoriesFor(boolean anesthesia) {
        return CATEGORY_LABELS.keySet().stream().filter(c -> anesthesia || !c.equals(AuthorizationService.ANESTHESIA_RULE)).toList();
    }

    /** Result of sanitizing: the clean criteria, whether a requested category was outside the user's permissions, and warnings. */
    public record Sanitized(FlagSearchCriteria criteria, boolean restrictedCategoryRequested, List<String> warnings) {}

    public Sanitized sanitize(boolean anesthesia, Set<String> allowedPatientIds) {
        List<String> warnings = new ArrayList<>();
        List<String> allowedCats = categoriesFor(anesthesia);
        boolean restricted = false;
        List<String> cats = new ArrayList<>();
        for (String c : nz(categories)) {
            String u = c == null ? "" : c.trim().toUpperCase(Locale.ROOT);
            if (allowedCats.contains(u)) { if (!cats.contains(u)) cats.add(u); }
            else if (CATEGORY_LABELS.containsKey(u)) restricted = true;
            else if (!u.isEmpty()) warnings.add("Ignored unknown flag category '" + c + "'");
        }
        String group = statusGroup == null ? "ANY" : statusGroup.trim().toUpperCase(Locale.ROOT);
        if (!STATUS_GROUPS.contains(group)) group = "ANY";
        List<String> st = clean(statuses, STATUSES);
        List<String> pr = clean(priorities, PRIORITIES);
        List<String> sp = new ArrayList<>();
        for (String s : nz(specialties)) {
            String v = s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z &\\-]", "");
            if (!v.isBlank() && v.length() <= 60 && !sp.contains(v)) sp.add(v);
        }
        List<String> cs = clean(concepts, SearchCriteria.CONCEPTS);
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9 \\-]", " ").replaceAll("\\s+", " ").trim();
        if (t.length() > 80) t = t.substring(0, 80);
        String pid = patientId == null || patientId.isBlank() ? null : patientId.trim();
        if (pid != null && !allowedPatientIds.contains(pid)) {
            warnings.add("Ignored patient filter outside your access scope");
            pid = "__none__";   // forces an empty result rather than widening the search
        }
        return new Sanitized(new FlagSearchCriteria(t.isEmpty() ? null : t, cats, group, st, pr, sp, cs, pid), restricted, warnings);
    }

    private static List<String> clean(List<String> values, List<String> allowed) {
        List<String> out = new ArrayList<>();
        for (String v : nz(values)) {
            String u = v == null ? "" : v.trim().toUpperCase(Locale.ROOT);
            if (allowed.contains(u) && !out.contains(u)) out.add(u);
        }
        return out;
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
