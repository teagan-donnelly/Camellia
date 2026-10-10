package com.camellia.analysis;

import com.camellia.config.CamelliaProperties;
import com.camellia.model.LabResult;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/** Flags a laboratory test that was outside the reporting lab's reference range N or more times. */
@Component
public class RepeatedAbnormalLabRule implements ScreeningRule {

    public static final String ID = "REPEATED_ABNORMAL_LAB";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy");

    private final CamelliaProperties.CountRule cfg;

    public RepeatedAbnormalLabRule(CamelliaProperties props) {
        this.cfg = props.getRules().getRepeatedAbnormalLab();
    }

    @Override public String id() { return ID; }
    @Override public String version() { return "1.0"; }

    @Override
    public RuleDescriptor describe() {
        return new RuleDescriptor(ID, version(), "Repeated out-of-range laboratory results",
                "Suggests review when the same test (matched by LOINC code across organizations) falls outside the reporting laboratory's own reference range repeatedly.",
                List.of("At least " + cfg.getMinResults() + " out-of-range results for the same test",
                        "Within the last " + cfg.getLookbackDays() + " days",
                        "Reference ranges come from each source laboratory; no external thresholds are applied"),
                "Clinical review queue (no coordination alert)");
    }

    @Override
    public List<CandidateFlag> evaluate(PatientSnapshot s) {
        Map<String, List<LabResult>> byTest = s.labs().stream()
                .filter(l -> l.testCode() != null && l.collectedAt() != null
                        && !l.collectedAt().isBefore(s.now().minusDays(cfg.getLookbackDays())))
                .collect(Collectors.groupingBy(LabResult::testCode, TreeMap::new, Collectors.toList()));
        List<CandidateFlag> out = new ArrayList<>();
        byTest.forEach((code, results) -> {
            List<LabResult> sorted = results.stream().sorted(Comparator.comparing(LabResult::collectedAt)).toList();
            List<LabResult> abnormal = sorted.stream().filter(LabResult::isOutOfReference).toList();
            if (abnormal.size() < cfg.getMinResults()) return;
            String name = abnormal.getFirst().testName();
            boolean high = abnormal.stream().allMatch(LabResult::isAboveReference);
            boolean low = abnormal.stream().allMatch(LabResult::isBelowReference);
            String direction = high ? "above" : low ? "below" : "outside";
            List<EvidenceItem> evidence = new ArrayList<>();
            for (LabResult l : sorted) {
                String v = ObAnesthesiaReviewRule.LabTextFmt.num(l.valueNum()) + " " + l.unit();
                evidence.add(l.isOutOfReference()
                        ? new EvidenceItem(l.recordId(), EvidenceItem.TRIGGER, "%s %s on %s (%s; reference %s, lab flag %s).".formatted(
                                name, v, l.collectedAt().format(DAY), l.orgId(), l.refText(), l.abnormalFlag()))
                        : new EvidenceItem(l.recordId(), EvidenceItem.SUPPORTING, "%s %s on %s within the reference range (context).".formatted(
                                name, v, l.collectedAt().format(DAY))));
            }
            String values = abnormal.stream().map(l -> ObAnesthesiaReviewRule.LabTextFmt.num(l.valueNum())).collect(Collectors.joining(", "));
            long orgs = abnormal.stream().map(LabResult::orgId).distinct().count();
            String reason = "%d %s results %s the reporting laboratory's reference range in the last %d days (values %s %s, from %d organization(s)). Prototype screening rule — suggests review only; not a diagnosis."
                    .formatted(abnormal.size(), name, direction, cfg.getLookbackDays(), values, abnormal.getFirst().unit(), orgs);
            out.add(new CandidateFlag(ID, version(), s.patientId(), "Repeated out-of-range results: " + name, reason,
                    Priority.STANDARD, ID + "|" + s.patientId() + "|" + code, evidence, null));
        });
        return out;
    }
}
