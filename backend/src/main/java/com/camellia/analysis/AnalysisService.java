package com.camellia.analysis;

import com.camellia.model.*;
import com.camellia.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Runs every screening rule over every patient and persists the results idempotently.
 * <ul>
 *   <li>Each candidate has a stable DEDUP_KEY (rule + patient [+ test / symptom]); repeat runs never duplicate flags.</li>
 *   <li>An evidence fingerprint (hash of cited record IDs and roles) detects changed evidence; the flag and its alert are
 *       refreshed while the human workflow status is preserved, and a system note records the change.</li>
 * </ul>
 */
@Service
public class AnalysisService {

    private static final Logger log = LoggerFactory.getLogger(AnalysisService.class);

    private final List<ScreeningRule> rules;
    private final PatientRepository patients;
    private final ClinicalDataRepository clinical;
    private final FlagRepository flags;
    private final AlertRepository alerts;
    private final SupportRepository support;

    public AnalysisService(List<ScreeningRule> rules, PatientRepository patients, ClinicalDataRepository clinical,
                           FlagRepository flags, AlertRepository alerts, SupportRepository support) {
        this.rules = rules.stream().sorted(Comparator.comparing(ScreeningRule::id)).toList();
        this.patients = patients;
        this.clinical = clinical;
        this.flags = flags;
        this.alerts = alerts;
        this.support = support;
    }

    public record Outcome(String patientId, String patientName, String ruleId, String flagId, String alertId, String result) {}

    public record RunResult(String runId, LocalDateTime startedAt, LocalDateTime finishedAt, int patientsEvaluated,
                            int flagsCreated, int flagsUpdated, int flagsUnchanged, int alertsCreated, List<Outcome> outcomes) {}

    public List<ScreeningRule.RuleDescriptor> describeRules() {
        return rules.stream().map(ScreeningRule::describe).toList();
    }

    /** Builds snapshots for all patients from bulk queries (one query per table). */
    public List<PatientSnapshot> snapshots(LocalDateTime now) {
        Map<String, List<ClinicalRecord>> records = group(clinical.allRecords(), ClinicalRecord::patientId);
        Map<String, List<Encounter>> encounters = group(clinical.allEncounters(), Encounter::patientId);
        Map<String, List<LabResult>> labs = group(clinical.allLabs(), LabResult::patientId);
        Map<String, List<Condition>> conditions = group(clinical.allConditions(), Condition::patientId);
        Map<String, List<Medication>> meds = group(clinical.allMedications(), Medication::patientId);
        Map<String, List<Appointment>> appts = group(clinical.allAppointments(), Appointment::patientId);
        return patients.findAll().stream().map(p -> new PatientSnapshot(p,
                records.getOrDefault(p.patientId(), List.of()), encounters.getOrDefault(p.patientId(), List.of()),
                labs.getOrDefault(p.patientId(), List.of()), conditions.getOrDefault(p.patientId(), List.of()),
                meds.getOrDefault(p.patientId(), List.of()), appts.getOrDefault(p.patientId(), List.of()), now)).toList();
    }

    public List<CandidateFlag> evaluate(PatientSnapshot snapshot) {
        List<CandidateFlag> out = new ArrayList<>();
        for (ScreeningRule rule : rules) out.addAll(rule.evaluate(snapshot));
        return out;
    }

    public synchronized RunResult run(String actor) {
        LocalDateTime started = LocalDateTime.now();
        String runId = SupportRepository.newId("RUN");
        List<PatientSnapshot> snaps = snapshots(started);
        Map<String, ClinicalFlag> existingByKey = flags.find(null, null, null).stream()
                .collect(Collectors.toMap(ClinicalFlag::dedupKey, Function.identity(), (a, b) -> a));
        Map<String, Patient> patientById = snaps.stream().collect(Collectors.toMap(PatientSnapshot::patientId, PatientSnapshot::patient));

        int created = 0, updated = 0, unchanged = 0, alertsCreated = 0;
        List<Outcome> outcomes = new ArrayList<>();
        for (PatientSnapshot snap : snaps) {
            for (CandidateFlag c : evaluate(snap)) {
                String name = patientById.get(c.patientId()).fullName();
                String fingerprint = fingerprint(c.evidence());
                ClinicalFlag existing = existingByKey.get(c.dedupKey());
                if (existing == null) {
                    ClinicalFlag f = createFlag(c, fingerprint, started, actor);
                    String alertId = null;
                    if (c.alert() != null) {
                        alertId = createAlert(f, c, started, actor);
                        alertsCreated++;
                    }
                    created++;
                    outcomes.add(new Outcome(c.patientId(), name, c.ruleId(), f.flagId(), alertId, "CREATED"));
                } else if (fingerprint.equals(existing.evidenceFingerprint())) {
                    flags.markEvaluated(existing.flagId(), started);
                    unchanged++;
                    outcomes.add(new Outcome(c.patientId(), name, c.ruleId(), existing.flagId(),
                            alerts.findByFlagId(existing.flagId()).map(CoordinationAlert::alertId).orElse(null), "UNCHANGED (duplicate prevented)"));
                } else {
                    String alertId = refresh(existing, c, fingerprint, started, actor);
                    if (alertId != null && alertId.startsWith("NEW:")) {
                        alertId = alertId.substring(4);
                        alertsCreated++;
                    }
                    updated++;
                    outcomes.add(new Outcome(c.patientId(), name, c.ruleId(), existing.flagId(), alertId, "EVIDENCE_UPDATED"));
                }
            }
        }
        LocalDateTime finished = LocalDateTime.now();
        support.insertRun(runId, started, finished, snaps.size(), created, updated, unchanged, alertsCreated, actor);
        support.logActivity("ANALYSIS", runId, null, "ANALYSIS_RUN",
                "Screening run: %d patients evaluated, %d flags created, %d updated, %d unchanged (duplicates prevented), %d alerts routed."
                        .formatted(snaps.size(), created, updated, unchanged, alertsCreated), actor);
        log.info("Analysis run {}: {} patients, {} created, {} updated, {} unchanged, {} alerts", runId, snaps.size(), created, updated, unchanged, alertsCreated);
        return new RunResult(runId, started, finished, snaps.size(), created, updated, unchanged, alertsCreated, outcomes);
    }

    private ClinicalFlag createFlag(CandidateFlag c, String fingerprint, LocalDateTime now, String actor) {
        ClinicalFlag f = new ClinicalFlag(SupportRepository.newId("FLG"), c.patientId(), c.ruleId(), c.ruleVersion(), c.title(),
                c.detectionReason(), c.reviewPriority(), "UNREVIEWED", null, c.dedupKey(), fingerprint, now, now,
                "Screening engine", now, null, null, null, null);
        flags.insert(f);
        flags.replaceEvidence(f.flagId(), links(c.evidence(), now));
        support.logActivity("FLAG", f.flagId(), c.patientId(), "FLAG_CREATED", c.title(), actor);
        return f;
    }

    private String createAlert(ClinicalFlag f, CandidateFlag c, LocalDateTime now, String actor) {
        AlertSpec a = c.alert();
        CoordinationAlert alert = new CoordinationAlert(SupportRepository.newId("ALR"), f.flagId(), c.patientId(), a.alertType(),
                a.title(), a.assignedTeam(), "NEW", c.reviewPriority(), a.appointmentId(), a.encounterDate(), a.reviewDueDate(),
                null, null, "NOT_STARTED", false, false, null, a.concernSummary(), a.involvedSpecialties(), now, now,
                "Screening engine", null);
        alerts.insert(alert);
        alerts.replaceEvidence(alert.alertId(), links(c.evidence(), now));
        support.logActivity("ALERT", alert.alertId(), c.patientId(), "ALERT_ROUTED",
                "%s routed to %s (in-app queue only; no external notification sent)".formatted(a.title(), a.assignedTeam()), actor);
        return alert.alertId();
    }

    /** Returns the alert id ("NEW:" prefix when an alert had to be created). */
    private String refresh(ClinicalFlag existing, CandidateFlag c, String fingerprint, LocalDateTime now, String actor) {
        Set<String> before = flags.evidence(existing.flagId()).stream().map(EvidenceLink::recordId).collect(Collectors.toSet());
        List<String> added = c.evidence().stream().map(EvidenceItem::recordId).filter(id -> !before.contains(id)).distinct().toList();
        flags.updateEvidence(existing.flagId(), c.title(), c.detectionReason(), c.reviewPriority(), fingerprint, now);
        flags.replaceEvidence(existing.flagId(), links(c.evidence(), now));
        Map<String, String> titles = clinical.findRecords(added).stream()
                .collect(Collectors.toMap(ClinicalRecord::recordId, r -> r.title() + (r.recordDate() == null ? "" : " (" + r.recordDate().toLocalDate() + ")")));
        String change = added.isEmpty() ? "Evidence set changed on re-evaluation."
                : "Re-evaluation added new evidence: " + added.stream().map(id -> titles.getOrDefault(id, id) + " [" + id + "]").collect(Collectors.joining("; ")) + ".";
        support.logActivity("FLAG", existing.flagId(), c.patientId(), "EVIDENCE_UPDATED", change, actor);

        if (c.alert() == null) return null;
        Optional<CoordinationAlert> alert = alerts.findByFlagId(existing.flagId());
        if (alert.isEmpty()) {
            return "NEW:" + createAlert(existing, c, now, actor);
        }
        AlertSpec a = c.alert();
        String alertId = alert.get().alertId();
        alerts.updateEvidenceSnapshot(alertId, a.title(), a.alertType(), c.reviewPriority(), a.concernSummary(),
                a.involvedSpecialties(), a.appointmentId(), a.encounterDate(), a.reviewDueDate(), now);
        alerts.replaceEvidence(alertId, links(c.evidence(), now));
        alerts.insertNote(new CoordinationNote(SupportRepository.newId("NTE"), alertId, c.patientId(), "Camellia screening engine",
                "System", "SYSTEM", change + " Workflow status was not changed; please review the updated evidence.", now));
        support.logActivity("ALERT", alertId, c.patientId(), "EVIDENCE_UPDATED", change, actor);
        return alertId;
    }

    private static List<EvidenceLink> links(List<EvidenceItem> items, LocalDateTime now) {
        Map<String, EvidenceItem> unique = new LinkedHashMap<>();
        for (EvidenceItem i : items) unique.putIfAbsent(i.recordId(), i);
        return unique.values().stream().map(i -> new EvidenceLink(null, i.recordId(), i.role(), i.description(), now)).toList();
    }

    static String fingerprint(List<EvidenceItem> evidence) {
        String joined = evidence.stream().map(e -> e.recordId() + ":" + e.role()).sorted().distinct().collect(Collectors.joining(","));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(joined.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static <T> Map<String, List<T>> group(List<T> items, Function<T, String> key) {
        return items.stream().collect(Collectors.groupingBy(key));
    }
}
