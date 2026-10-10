package com.camellia.ai;

import com.camellia.ingestion.ClinicalTagger;
import com.camellia.model.ClinicalRecord;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Retrieval and context assembly: ranks a patient's records for a question and packs only the most relevant ones
 * into a bounded character budget. The whole database is never sent to the model.
 */
public final class EvidencePacker {

    static final int MAX_RECORD_CHARS = 1400;
    static final int MAX_RECORDS = 30;

    private EvidencePacker() {}

    public record Packed(String text, List<ClinicalRecord> included, int omitted) {}

    public static String format(ClinicalRecord r, String orgName, String role) {
        String content = r.contentText() == null ? "" : r.contentText().replaceAll("\\s+", " ").trim();
        if (content.length() > MAX_RECORD_CHARS) content = content.substring(0, MAX_RECORD_CHARS) + " …[truncated]";
        return "[%s]%s\n  date: %s | type: %s | source: %s (%s) | specialty: %s | author: %s\n  title: %s\n  content: %s\n".formatted(
                r.recordId(), role == null ? "" : " (" + role + ")", r.recordDate() == null ? "unknown" : r.recordDate().toLocalDate(),
                r.recordType(), orgName, r.sourceFormat(), r.specialty() == null ? "-" : r.specialty(),
                r.author() == null ? "-" : r.author(), r.title(), content);
    }

    /**
     * @param priorityRoles records that must come first (e.g. flag evidence), with their evidence role
     */
    public static Packed pack(List<ClinicalRecord> candidates, String question, Map<String, String> priorityRoles,
                              Map<String, String> orgNames, int budgetChars, LocalDateTime now) {
        Set<String> qTokens = tokens(question);
        Set<String> qConcepts = ClinicalTagger.concepts(question == null ? "" : question);
        List<ClinicalRecord> ranked = new ArrayList<>(candidates);
        Map<String, Double> score = new HashMap<>();
        for (ClinicalRecord r : candidates) score.put(r.recordId(), score(r, qTokens, qConcepts, priorityRoles, now));
        ranked.sort(Comparator.comparing((ClinicalRecord r) -> score.get(r.recordId())).reversed());

        StringBuilder sb = new StringBuilder();
        List<ClinicalRecord> included = new ArrayList<>();
        for (ClinicalRecord r : ranked) {
            if (included.size() >= MAX_RECORDS) break;
            String block = format(r, orgNames.getOrDefault(r.orgId(), r.orgId()), priorityRoles.get(r.recordId()));
            if (sb.length() + block.length() > budgetChars && !included.isEmpty()) continue;
            sb.append(block).append('\n');
            included.add(r);
        }
        return new Packed(sb.toString(), included, candidates.size() - included.size());
    }

    static double score(ClinicalRecord r, Set<String> qTokens, Set<String> qConcepts, Map<String, String> priority, LocalDateTime now) {
        double s = 0;
        if (priority.containsKey(r.recordId())) s += 100;
        String hay = ((r.title() == null ? "" : r.title()) + " " + (r.contentText() == null ? "" : r.contentText())).toLowerCase(Locale.ROOT);
        for (String t : qTokens) if (hay.contains(t)) s += 4;
        for (String c : qConcepts) if (r.hasTag(c)) s += 6;
        if (r.recordDate() != null) {
            long days = Math.abs(java.time.Duration.between(r.recordDate(), now).toDays());
            s += Math.max(0, 3 - days / 365.0);   // mild recency preference
        }
        switch (r.recordType()) {
            case NOTE, LAB_RESULT, CONDITION -> s += 1;
            default -> { }
        }
        return s;
    }

    static Set<String> tokens(String q) {
        Set<String> out = new HashSet<>();
        if (q == null) return out;
        for (String w : q.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (w.length() >= 4 && !Set.of("what", "which", "where", "when", "with", "that", "this", "have", "there", "patient",
                    "were", "been", "does", "show", "find", "about", "flagged", "should").contains(w)) out.add(w);
        }
        return out;
    }
}
