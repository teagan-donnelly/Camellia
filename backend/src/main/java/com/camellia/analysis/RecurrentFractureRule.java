package com.camellia.analysis;

import com.camellia.config.CamelliaProperties;
import com.camellia.model.Encounter;
import com.camellia.model.RecordType;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Flags N or more fracture-related encounters within the lookback window (any organization). */
@Component
public class RecurrentFractureRule implements ScreeningRule {

    public static final String ID = "RECURRENT_FRACTURE";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private final CamelliaProperties.CountRule cfg;

    public RecurrentFractureRule(CamelliaProperties props) {
        this.cfg = props.getRules().getRecurrentFracture();
    }

    @Override public String id() { return ID; }
    @Override public String version() { return "1.0"; }

    @Override
    public RuleDescriptor describe() {
        return new RuleDescriptor(ID, version(), "Recurrent fracture-related encounters",
                "Suggests review when several separate encounters document a fracture, which can be hard to notice when the visits happened at different organizations.",
                List.of("At least " + cfg.getMinEncounters() + " fracture-related encounters",
                        "Within the last " + cfg.getLookbackDays() + " days",
                        "Encounter reasons and diagnoses are matched on the term 'fracture' during normalization"),
                "Clinical review queue (no coordination alert)");
    }

    @Override
    public List<CandidateFlag> evaluate(PatientSnapshot s) {
        List<Encounter> fx = s.encounters().stream()
                .filter(Encounter::fractureRelated)
                .filter(e -> e.startTime() != null && !e.startTime().isBefore(s.now().minusDays(cfg.getLookbackDays())))
                .sorted(Comparator.comparing(Encounter::startTime))
                .toList();
        if (fx.size() < cfg.getMinEncounters()) return List.of();

        Set<String> encounterIds = fx.stream().map(Encounter::encounterId).collect(Collectors.toSet());
        List<EvidenceItem> evidence = new ArrayList<>();
        for (Encounter e : fx) {
            evidence.add(new EvidenceItem(e.recordId(), EvidenceItem.TRIGGER, "%s on %s at %s: %s".formatted(
                    e.encounterType(), e.startTime().format(DAY), e.orgId(), e.reason())));
        }
        s.records().stream()
                .filter(r -> r.encounterId() != null && encounterIds.contains(r.encounterId()))
                .filter(r -> r.recordType() == RecordType.NOTE || r.recordType() == RecordType.IMAGING)
                .forEach(r -> evidence.add(new EvidenceItem(r.recordId(), EvidenceItem.SUPPORTING,
                        r.title() + " (" + r.author() + ") linked to a fracture-related encounter.")));

        long orgCount = fx.stream().map(Encounter::orgId).distinct().count();
        String dates = fx.stream().map(e -> e.startTime().format(DAY)).collect(Collectors.joining(", "));
        String reason = "%d fracture-related encounters documented across %d organization(s) in the last %d days (%s). Prototype screening rule — suggests review of the longitudinal history only; not a diagnosis."
                .formatted(fx.size(), orgCount, cfg.getLookbackDays(), dates);
        return List.of(new CandidateFlag(ID, version(), s.patientId(), "Recurrent fracture history — review suggested", reason,
                Priority.STANDARD, ID + "|" + s.patientId(), evidence, null));
    }

}
