package com.camellia.ai;

import com.camellia.ai.AiDtos.RawAnswer;
import com.camellia.ai.AiDtos.RawStatement;
import com.camellia.ai.AiDtos.Statement;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enforces that AI output only cites records that were actually retrieved and sent to the model.
 * <ul>
 *   <li>Unknown IDs are removed from statements and replaced in the answer text.</li>
 *   <li>A "documented fact" left with no valid citation is dropped (it cannot be verified).</li>
 *   <li>Interpretation statements may be uncited, but any IDs they carry are validated the same way.</li>
 * </ul>
 */
public final class CitationValidator {

    static final Pattern RECORD_ID = Pattern.compile("REC-[A-Z0-9]+(?:-[A-Z0-9]+)*");
    static final String REMOVED = "[unverified reference removed]";

    private CitationValidator() {}

    public record Result(String answer, List<Statement> documentedFacts, List<Statement> interpretation,
                         List<String> missingInformation, List<String> followUps, Set<String> citedIds,
                         int citationsRemoved, int uncitedFactsRemoved) {}

    public static Result validate(RawAnswer raw, Set<String> allowedIds) {
        int[] removed = {0};
        Set<String> cited = new LinkedHashSet<>();

        String answer = raw.answer() == null ? "" : raw.answer();
        answer = cleanText(answer, allowedIds, cited, removed);

        List<Statement> facts = new ArrayList<>();
        int uncited = 0;
        for (RawStatement s : nz(raw.documentedFacts())) {
            if (s == null || s.statement() == null || s.statement().isBlank()) continue;
            List<String> ids = filterIds(s.sourceRecordIds(), allowedIds, removed);
            Set<String> inline = new LinkedHashSet<>();
            String text = cleanText(s.statement(), allowedIds, inline, removed);
            ids = merge(ids, inline);
            if (ids.isEmpty()) {
                uncited++;
                continue;
            }
            cited.addAll(ids);
            facts.add(new Statement(text, ids));
        }
        List<Statement> interp = new ArrayList<>();
        for (RawStatement s : nz(raw.interpretation())) {
            if (s == null || s.statement() == null || s.statement().isBlank()) continue;
            List<String> ids = filterIds(s.sourceRecordIds(), allowedIds, removed);
            Set<String> inline = new LinkedHashSet<>();
            String text = cleanText(s.statement(), allowedIds, inline, removed);
            ids = merge(ids, inline);
            cited.addAll(ids);
            interp.add(new Statement(text, ids));
        }
        List<String> missing = nz(raw.missingInformation()).stream().filter(Objects::nonNull)
                .map(m -> cleanText(m, allowedIds, cited, removed)).filter(m -> !m.isBlank()).toList();
        List<String> followUps = nz(raw.suggestedFollowUpQuestions()).stream().filter(q -> q != null && !q.isBlank()).limit(3).toList();
        return new Result(answer, facts, interp, missing, followUps, cited, removed[0], uncited);
    }

    /** Keeps valid record IDs in text; replaces unknown ones. Collects valid IDs into {@code cited}. */
    static String cleanText(String text, Set<String> allowed, Set<String> cited, int[] removed) {
        Matcher m = RECORD_ID.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String id = m.group();
            if (allowed.contains(id)) {
                cited.add(id);
                m.appendReplacement(sb, Matcher.quoteReplacement(id));
            } else {
                removed[0]++;
                m.appendReplacement(sb, Matcher.quoteReplacement(REMOVED));
            }
        }
        m.appendTail(sb);
        return sb.toString().replace("[" + REMOVED + "]", REMOVED);
    }

    private static List<String> filterIds(List<String> ids, Set<String> allowed, int[] removed) {
        List<String> out = new ArrayList<>();
        for (String raw : nz(ids)) {
            if (raw == null) continue;
            String id = raw.trim().replaceAll("^\\[|]$", "");
            if (allowed.contains(id)) {
                if (!out.contains(id)) out.add(id);
            } else {
                removed[0]++;
            }
        }
        return out;
    }

    private static List<String> merge(List<String> a, Set<String> b) {
        LinkedHashSet<String> s = new LinkedHashSet<>(a);
        s.addAll(b);
        return new ArrayList<>(s);
    }

    private static <T> List<T> nz(List<T> l) {
        return l == null ? List.of() : l;
    }
}
