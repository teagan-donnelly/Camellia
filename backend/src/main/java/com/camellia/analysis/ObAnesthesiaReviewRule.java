package com.camellia.analysis;

import com.camellia.config.CamelliaProperties;
import com.camellia.ingestion.ClinicalTagger;
import com.camellia.model.*;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Anesthesia OB review rule (the primary workflow from the clinician interview).
 *
 * Candidate when ALL of:
 *  1. a booked, delivery-related encounter (cesarean, induction, L&D admission) is scheduled within the lookahead window
 *     — an estimated due date alone is NOT treated as a confirmed admission; and
 *  2. at least one documented trigger exists:
 *     a. a platelet result below the reporting laboratory's own reference range within the lab lookback window,
 *     b. an active documented condition matching a configured anesthesia-relevant term list, or
 *     c. an active anticoagulant medication matching the configured term list.
 *
 * All terms and windows are configurable PROTOTYPE HEURISTICS (application.yml: camellia.rules.ob-anesthesia).
 */
@Component
public class ObAnesthesiaReviewRule implements ScreeningRule {

    public static final String ID = "OB_ANESTHESIA_REVIEW";
    public static final String TEAM = "Anesthesia OB Coordination";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d, yyyy");
    private static final Set<String> NON_CONSULTING = Set.of("obstetrics", "laboratory", "anesthesiology", "radiology",
            "emergency medicine", "primary care", "urgent care");

    private final CamelliaProperties.ObAnesthesia cfg;

    public ObAnesthesiaReviewRule(CamelliaProperties props) {
        this.cfg = props.getRules().getObAnesthesia();
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String version() {
        return "1.0";
    }

    @Override
    public RuleDescriptor describe() {
        List<String> params = new ArrayList<>();
        params.add("Delivery-related encounter booked within " + cfg.getLookaheadDays() + " days");
        params.add("Platelet results (LOINC " + String.join(", ", cfg.getPlateletTestCodes())
                + ") below the reporting lab's reference range within " + cfg.getLabLookbackDays() + " days");
        params.add("Explanation highlights platelet values below " + LabTextFmt.num(cfg.getPlateletHighlightBelow())
                + " x10^3/uL (prototype heuristic, not a validated threshold)");
        cfg.getRelevantConditionTerms().forEach((cat, terms) -> params.add("Condition terms — " + cat + ": " + String.join(", ", terms)));
        params.add("Anticoagulant terms: " + String.join(", ", cfg.getAnticoagulantTerms()));
        params.add("Expedited ordering when the encounter is within " + cfg.getExpeditedWithinDays() + " days; review due "
                + cfg.getReviewLeadDays() + " days before the encounter");
        return new RuleDescriptor(ID, version(), "Advance anesthesia planning review (obstetrics)",
                "Suggests advance review when an upcoming delivery-related encounter coincides with documented findings that "
                        + "may matter for anesthesia planning. Suggests review only; it does not assess clinical risk.",
                params, TEAM);
    }

    @Override
    public List<CandidateFlag> evaluate(PatientSnapshot s) {
        Map<String, ClinicalRecord> byId = s.recordsById();
        LocalDate today = s.now().toLocalDate();

        Optional<Appointment> upcoming = s.appointments().stream()
                .filter(a -> a.scheduledStart() != null && !a.scheduledStart().isBefore(s.now())
                        && !a.scheduledStart().isAfter(s.now().plusDays(cfg.getLookaheadDays())))
                .filter(a -> a.status() == null || !a.status().equalsIgnoreCase("cancelled"))
                .filter(a -> byId.containsKey(a.recordId()) && byId.get(a.recordId()).hasTag("DELIVERY"))
                .min(Comparator.comparing(Appointment::scheduledStart));
        if (upcoming.isEmpty()) return List.of();
        Appointment appt = upcoming.get();

        List<EvidenceItem> evidence = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        Set<String> categories = new LinkedHashSet<>();
        Set<String> matchedTerms = new LinkedHashSet<>();
        Set<String> triggerIds = new HashSet<>();

        // (a) platelet results below the lab's own reference range
        List<LabResult> platelets = s.labs().stream()
                .filter(l -> cfg.getPlateletTestCodes().contains(l.testCode()) && l.collectedAt() != null
                        && !l.collectedAt().isBefore(s.now().minusDays(cfg.getLabLookbackDays())))
                .sorted(Comparator.comparing(LabResult::collectedAt))
                .toList();
        List<LabResult> low = platelets.stream().filter(LabResult::isBelowReference).toList();
        if (!low.isEmpty()) {
            categories.add("Hematologic");
            matchedTerms.addAll(List.of("platelet", "thrombocytopenia"));
            for (LabResult l : low) {
                evidence.add(new EvidenceItem(l.recordId(), EvidenceItem.TRIGGER, "Platelet count %s %s on %s is below the reporting laboratory's reference range (%s)."
                        .formatted(LabTextFmt.num(l.valueNum()), l.unit(), l.collectedAt().format(DAY), l.refText())));
                triggerIds.add(l.recordId());
            }
            for (LabResult l : platelets) {
                if (!l.isBelowReference()) {
                    evidence.add(new EvidenceItem(l.recordId(), EvidenceItem.SUPPORTING, "Earlier platelet count %s %s on %s, within the reference range (trend context)."
                            .formatted(LabTextFmt.num(l.valueNum()), l.unit(), l.collectedAt().format(DAY))));
                }
            }
            LabResult latest = low.getLast();
            String trend = platelets.stream().map(l -> LabTextFmt.num(l.valueNum())).collect(Collectors.joining(" → "));
            String finding = "%d platelet result(s) below the laboratory reference range (most recent %s %s on %s; trend %s)"
                    .formatted(low.size(), LabTextFmt.num(latest.valueNum()), latest.unit(), latest.collectedAt().format(DAY), trend);
            if (latest.valueNum() != null && latest.valueNum() < cfg.getPlateletHighlightBelow()) {
                finding += ", below the configurable prototype review heuristic of " + LabTextFmt.num(cfg.getPlateletHighlightBelow());
            }
            findings.add(finding);
        }

        // (b) documented anesthesia-relevant conditions
        for (Condition c : s.conditions()) {
            if (!c.isActive() || c.display() == null) continue;
            String lower = c.display().toLowerCase(Locale.ROOT);
            for (Map.Entry<String, List<String>> e : cfg.getRelevantConditionTerms().entrySet()) {
                Optional<String> term = e.getValue().stream().filter(t -> ClinicalTagger.containsTerm(lower, t.toLowerCase(Locale.ROOT))).findFirst();
                if (term.isPresent() && triggerIds.add(c.recordId())) {
                    categories.add(e.getKey());
                    matchedTerms.add(term.get().toLowerCase(Locale.ROOT));
                    evidence.add(new EvidenceItem(c.recordId(), EvidenceItem.TRIGGER, "Documented condition (%s): %s — recorded by %s."
                            .formatted(e.getKey(), c.display(), c.recordedBy())));
                    findings.add("documented " + e.getKey().toLowerCase(Locale.ROOT) + " condition: " + c.display());
                }
            }
        }

        // (c) active anticoagulant medications
        for (Medication m : s.medications()) {
            if (!m.isActive() || m.name() == null) continue;
            String lower = m.name().toLowerCase(Locale.ROOT);
            Optional<String> term = cfg.getAnticoagulantTerms().stream().filter(t -> ClinicalTagger.containsTerm(lower, t.toLowerCase(Locale.ROOT))).findFirst();
            if (term.isPresent() && triggerIds.add(m.recordId())) {
                categories.add("Anticoagulation");
                matchedTerms.add(term.get().toLowerCase(Locale.ROOT));
                evidence.add(new EvidenceItem(m.recordId(), EvidenceItem.TRIGGER, "Active anticoagulant medication documented: %s (%s), prescribed by %s."
                        .formatted(m.name(), m.dose(), m.prescriber())));
                findings.add("active anticoagulant medication: " + m.name());
            }
        }

        if (triggerIds.isEmpty()) return List.of();

        // Supporting specialist notes that mention a matched term (lab-related terms limited to the lab window).
        Set<String> labTerms = Set.of("platelet", "thrombocytopenia");
        s.records().stream()
                .filter(r -> (r.recordType() == RecordType.NOTE || r.recordType() == RecordType.IMAGING) && r.contentText() != null)
                .filter(r -> !triggerIds.contains(r.recordId()))
                .filter(r -> {
                    String lower = (r.title() + " " + r.contentText()).toLowerCase(Locale.ROOT);
                    return matchedTerms.stream().anyMatch(t -> ClinicalTagger.containsTerm(lower, t)
                            && (!labTerms.contains(t) || (r.recordDate() != null && !r.recordDate().isBefore(s.now().minusDays(cfg.getLabLookbackDays())))));
                })
                .sorted(Comparator.comparing(ClinicalRecord::recordDate, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(6)
                .forEach(r -> evidence.add(new EvidenceItem(r.recordId(), EvidenceItem.SUPPORTING, "%s by %s (%s) on %s references the documented concern."
                        .formatted(r.title(), r.author(), r.specialty() == null ? "unspecified specialty" : r.specialty(),
                                r.recordDate() == null ? "unknown date" : r.recordDate().format(DAY)))));

        long days = ChronoUnit.DAYS.between(today, appt.scheduledStart().toLocalDate());
        evidence.add(new EvidenceItem(appt.recordId(), EvidenceItem.UPCOMING_ENCOUNTER, "Upcoming encounter: %s on %s (%d days away) — %s."
                .formatted(appt.appointmentType(), appt.scheduledStart().format(DAY), days, appt.department())));

        Set<String> specialties = new TreeSet<>();
        Set<String> consulting = new TreeSet<>();
        for (EvidenceItem e : evidence) {
            ClinicalRecord r = byId.get(e.recordId());
            if (r == null || r.specialty() == null) continue;
            specialties.add(r.specialty());
            if (!NON_CONSULTING.contains(r.specialty().toLowerCase(Locale.ROOT))) consulting.add(r.specialty());
        }
        boolean multidisciplinary = consulting.size() >= 2;
        String alertType = multidisciplinary ? "MULTIDISCIPLINARY_OB" : "ANESTHESIA_OB";
        String title = multidisciplinary ? "Multidisciplinary Coordination Review Suggested" : "Advance Anesthesia Planning Review Suggested";
        String priority = days <= cfg.getExpeditedWithinDays() ? Priority.EXPEDITED : Priority.STANDARD;
        LocalDate due = appt.scheduledStart().toLocalDate().minusDays(cfg.getReviewLeadDays());
        if (due.isBefore(today)) due = today;

        String reason = "Upcoming delivery-related encounter (%s on %s, %d days away) with documented findings in the available records: %s. Categories: %s. Prototype screening rule — suggests advance review only; not a clinical determination."
                .formatted(appt.appointmentType(), appt.scheduledStart().format(DAY), days, String.join("; ", findings),
                        String.join(", ", categories));
        String concern = String.join(" · ", categories) + " — " + String.join("; ", findings);

        AlertSpec alert = new AlertSpec(alertType, title, TEAM, appt.appointmentId(), appt.scheduledStart(), due, concern,
                String.join(", ", specialties));
        return List.of(new CandidateFlag(ID, version(), s.patientId(), title, reason, priority, ID + "|" + s.patientId(),
                evidence, alert));
    }

    /** Number formatting shared by rules. */
    static final class LabTextFmt {
        static String num(Double d) {
            if (d == null) return "?";
            return d == Math.floor(d) ? String.valueOf(d.longValue()) : String.valueOf(d);
        }
    }
}
