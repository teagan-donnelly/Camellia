package com.camellia.search;

import com.camellia.ingestion.ClinicalTagger;
import com.camellia.model.Patient;

import java.util.*;

/**
 * Deterministic natural-language → {@link SearchCriteria} translation. Used ONLY when Gemini is not configured
 * or fails; the UI labels its output "Rule-based interpretation (not Gemini)".
 */
public final class RuleBasedQueryInterpreter {

    private static final Set<String> STOPWORDS = Set.of("a", "an", "the", "show", "me", "find", "list", "every", "all",
            "patient", "patients", "with", "who", "whose", "which", "what", "has", "have", "had", "is", "are", "was",
            "were", "of", "in", "on", "for", "to", "and", "or", "any", "documented", "their", "this", "that", "there",
            "be", "been", "do", "does", "did", "from", "by", "across", "history", "records", "record", "may", "need",
            "needs", "about", "them", "they", "know", "before", "more", "than", "one", "same", "repeated", "multiple",
            "recurring", "recurrent", "several", "visits", "visit", "upcoming", "pregnant", "obstetric", "results",
            "result", "tests", "test", "concerns", "concern", "related", "involving", "hospital", "hospitals");

    private RuleBasedQueryInterpreter() {}

    public static SearchCriteria interpret(String query, List<Patient> patients, String scopedPatientId) {
        String q = query == null ? "" : query.toLowerCase(Locale.ROOT);
        Set<String> concepts = new LinkedHashSet<>(ClinicalTagger.concepts(q));
        concepts.remove("OBSTETRIC");
        concepts.remove("DELIVERY");
        List<String> recordTypes = new ArrayList<>();

        if (q.contains("low platelet") || q.contains("thrombocytopenia")) {
            concepts.remove("PLATELET");
            concepts.remove("HEMATOLOGY");
            concepts.add("LOW_PLATELETS");
        }
        if (q.contains("abnormal") || q.contains("out of range") || q.contains("out-of-range")) concepts.add("ABNORMAL_LAB");
        if (q.contains("medication") || q.contains("taking") || q.contains("prescri")) recordTypes.add("MEDICATION");
        if (q.contains("lab")) recordTypes.add("LAB_RESULT");
        if (q.contains("note")) recordTypes.add("NOTE");
        for (Map.Entry<String, List<String>> e : ClinicalTagger.SYMPTOM_TERMS.entrySet()) {
            if (e.getValue().stream().anyMatch(q::contains)) concepts.add("SYMPTOM_" + e.getKey());
        }
        if (concepts.contains("SYMPTOM_HEADACHE")) concepts.remove("NEUROLOGY");

        boolean obstetric = q.contains("pregnan") || q.contains("obstetric") || q.contains(" ob ");
        boolean upcomingDelivery = q.contains("deliver") || q.contains("cesarean") || (q.contains("upcoming") && obstetric);
        boolean multiOrg = q.contains("more than one") || q.contains("multiple hospital") || q.contains("multiple organization")
                || q.contains("different hospital") || q.contains("several hospital");
        int min = (q.contains("repeat") || q.contains("recurr") || q.contains("multiple") || q.contains("several")) && !multiOrg ? 2 : 1;

        String patientId = scopedPatientId;
        for (Patient p : patients) {
            if (q.contains(p.lastName().toLowerCase(Locale.ROOT)) || q.contains(p.fullName().toLowerCase(Locale.ROOT))) {
                patientId = p.patientId();
            }
        }

        List<String> keywords = new ArrayList<>();
        if (concepts.isEmpty() && recordTypes.isEmpty()) {
            for (String w : q.replaceAll("[^a-z0-9 \\-]", " ").split("\\s+")) {
                if (w.length() >= 3 && !STOPWORDS.contains(w) && patients.stream().noneMatch(p -> p.lastName().equalsIgnoreCase(w)
                        || p.firstName().equalsIgnoreCase(w))) {
                    keywords.add(w);
                }
            }
        }
        boolean anyFilter = !concepts.isEmpty() || !recordTypes.isEmpty() || !keywords.isEmpty() || obstetric || upcomingDelivery || multiOrg;
        if (!anyFilter && patientId == null) keywords.add(q.trim());
        return new SearchCriteria(query, keywords, new ArrayList<>(concepts), recordTypes, List.of(), patientId, null, null,
                obstetric, upcomingDelivery, multiOrg, false, min, "ANY");
    }
}
