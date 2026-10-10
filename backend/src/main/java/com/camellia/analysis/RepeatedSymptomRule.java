package com.camellia.analysis;

import com.camellia.config.CamelliaProperties;
import com.camellia.model.Encounter;
import com.camellia.model.RecordType;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Flags N or more encounters in the same symptom category within the lookback window. */
@Component
public class RepeatedSymptomRule implements ScreeningRule {

    public static final String ID = "REPEATED_SYMPTOM";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private final CamelliaProperties.CountRule cfg;

    public RepeatedSymptomRule(CamelliaProperties props) {
        this.cfg = props.getRules().getRepeatedSymptom();
    }

    @Override public String id() { return ID; }
    @Override public String version() { return "1.0"; }

    @Override
    public RuleDescriptor describe() {
        return new RuleDescriptor(ID, version(), "Repeated encounters for the same symptom",
                "Suggests longitudinal review when several encounters (any organization) share a symptom category derived from the documented reason for visit.",
                List.of("At least " + cfg.getMinEncounters() + " encounters in the same symptom category",
                        "Within the last " + cfg.getLookbackDays() + " days",
                        "Symptom categories: headache, abdominal pain, chest pain, syncope, palpitations, dyspnea, back pain, dizziness"),
                "Clinical review queue (no coordination alert)");
    }

    @Override
    public List<CandidateFlag> evaluate(PatientSnapshot s) {
        Map<String, List<Encounter>> byCategory = s.encounters().stream()
                .filter(e -> e.symptomCategory() != null && e.startTime() != null
                        && !e.startTime().isBefore(s.now().minusDays(cfg.getLookbackDays())))
                .collect(Collectors.groupingBy(Encounter::symptomCategory, TreeMap::new, Collectors.toList()));
        List<CandidateFlag> out = new ArrayList<>();
        byCategory.forEach((category, encounters) -> {
            if (encounters.size() < cfg.getMinEncounters()) return;
            List<Encounter> sorted = encounters.stream().sorted(Comparator.comparing(Encounter::startTime)).toList();
            Set<String> encIds = sorted.stream().map(Encounter::encounterId).collect(Collectors.toSet());
            List<EvidenceItem> evidence = new ArrayList<>();
            for (Encounter e : sorted) {
                evidence.add(new EvidenceItem(e.recordId(), EvidenceItem.TRIGGER, "%s on %s (%s): %s".formatted(
                        e.encounterType(), e.startTime().format(DAY), e.orgId(), e.reason())));
            }
            s.records().stream()
                    .filter(r -> r.encounterId() != null && encIds.contains(r.encounterId()))
                    .filter(r -> r.recordType() == RecordType.NOTE || r.recordType() == RecordType.IMAGING)
                    .forEach(r -> evidence.add(new EvidenceItem(r.recordId(), EvidenceItem.SUPPORTING,
                            r.title() + " (" + r.author() + ") documents one of these encounters.")));
            String label = category.toLowerCase(Locale.ROOT).replace('_', ' ');
            long emergency = sorted.stream().filter(e -> "Emergency".equals(e.encounterType())).count();
            long orgs = sorted.stream().map(Encounter::orgId).distinct().count();
            String reason = "%d encounters for %s in the last %d days (%d emergency department visits, %d organization(s)): %s. Prototype screening rule — suggests longitudinal review only; not a diagnosis."
                    .formatted(sorted.size(), label, cfg.getLookbackDays(), emergency, orgs,
                            sorted.stream().map(e -> e.startTime().format(DAY)).collect(Collectors.joining(", ")));
            out.add(new CandidateFlag(ID, version(), s.patientId(), "Repeated encounters for " + label + " — longitudinal review suggested",
                    reason, Priority.STANDARD, ID + "|" + s.patientId() + "|" + category, evidence, null));
        });
        return out;
    }
}
