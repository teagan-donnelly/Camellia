package com.camellia.service;

import com.camellia.ai.AiDtos.AiAnswer;
import com.camellia.dto.Dtos.*;
import com.camellia.ingestion.Json;
import com.camellia.model.*;
import com.camellia.repository.*;
import com.camellia.web.ApiException;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Assembles read models (patients, flags, alerts, dashboard). Each request bulk-loads what it needs once
 * ({@link Lookup}) instead of issuing per-row queries — important with a remote warehouse like Snowflake.
 */
@Service
public class ClinicalViewService {

    static final Set<String> OPEN_FLAG = Set.of("UNREVIEWED", "IN_REVIEW");
    static final Set<String> CLOSED_ALERT = Set.of("RESOLVED", "DISMISSED");

    private final PatientRepository patients;
    private final ClinicalDataRepository clinical;
    private final OrganizationRepository organizations;
    private final FlagRepository flags;
    private final AlertRepository alerts;
    private final SupportRepository support;

    public ClinicalViewService(PatientRepository patients, ClinicalDataRepository clinical, OrganizationRepository organizations,
                               FlagRepository flags, AlertRepository alerts, SupportRepository support) {
        this.patients = patients;
        this.clinical = clinical;
        this.organizations = organizations;
        this.flags = flags;
        this.alerts = alerts;
        this.support = support;
    }

    /** Per-request bulk-loaded reference data. */
    final class Lookup {
        final Map<String, String> orgNames = orgNames();
        final Map<String, Patient> patientsById = patients.findAll().stream().collect(Collectors.toMap(Patient::patientId, Function.identity()));
        final List<ClinicalFlag> allFlags = flags.find(null, null, null);
        final List<CoordinationAlert> allAlerts = alerts.find(null, null, null);
        final Map<String, CoordinationAlert> alertByFlag = allAlerts.stream().collect(Collectors.toMap(CoordinationAlert::flagId, Function.identity(), (a, b) -> a));
        final Map<String, List<EvidenceLink>> flagEvidence = flags.allEvidence().stream().collect(Collectors.groupingBy(EvidenceLink::ownerId));
        final Map<String, List<EvidenceLink>> alertEvidence = alerts.allEvidence().stream().collect(Collectors.groupingBy(EvidenceLink::ownerId));
        final Map<String, Integer> noteCounts = alerts.noteCounts();
        final Map<String, Appointment> appointments = clinical.allAppointments().stream().collect(Collectors.toMap(Appointment::appointmentId, Function.identity()));
        final Map<String, ClinicalRecord> records;

        Lookup() {
            Set<String> ids = new HashSet<>();
            flagEvidence.values().forEach(l -> l.forEach(e -> ids.add(e.recordId())));
            alertEvidence.values().forEach(l -> l.forEach(e -> ids.add(e.recordId())));
            appointments.values().forEach(a -> ids.add(a.recordId()));
            records = new HashMap<>();
            clinical.findRecords(ids).forEach(r -> records.put(r.recordId(), r));
        }
    }

    public Map<String, String> orgNames() {
        return organizations.findAll().stream().collect(Collectors.toMap(Organization::orgId, Organization::name));
    }

    // ------------------------------------------------------------------ records

    public RecordDto record(ClinicalRecord r, Map<String, String> orgNames) {
        List<String> tags = r.categoryTags() == null ? List.of()
                : Arrays.stream(r.categoryTags().split("\\|")).filter(s -> !s.isBlank()).toList();
        return new RecordDto(r.recordId(), r.patientId(), r.orgId(), orgNames.getOrDefault(r.orgId(), r.orgId()), r.encounterId(),
                r.recordType().name(), r.title(), r.recordDate(), r.author(), r.specialty(), r.contentText(), r.sourceFormat(),
                r.sourceRecordId(), tags);
    }

    public RecordDetailDto recordDetail(String recordId) {
        ClinicalRecord r = clinical.findRecord(recordId).orElseThrow(() -> ApiException.notFound("Record", recordId));
        Map<String, String> names = orgNames();
        String sourceSystem = organizations.findAll().stream().filter(o -> o.orgId().equals(r.orgId()))
                .map(Organization::sourceSystem).findFirst().orElse(null);
        Patient p = patients.findById(r.patientId()).orElse(null);
        List<ReferencedBy> refs = new ArrayList<>();
        Map<String, ClinicalFlag> pFlags = flags.find(null, null, r.patientId()).stream()
                .collect(Collectors.toMap(ClinicalFlag::flagId, Function.identity()));
        flags.allEvidence().stream().filter(e -> e.recordId().equals(recordId) && pFlags.containsKey(e.ownerId()))
                .forEach(e -> refs.add(new ReferencedBy("FLAG", e.ownerId(), pFlags.get(e.ownerId()).title(), e.role())));
        Map<String, CoordinationAlert> pAlerts = alerts.find(null, null, r.patientId()).stream()
                .collect(Collectors.toMap(CoordinationAlert::alertId, Function.identity()));
        alerts.allEvidence().stream().filter(e -> e.recordId().equals(recordId) && pAlerts.containsKey(e.ownerId()))
                .forEach(e -> refs.add(new ReferencedBy("ALERT", e.ownerId(), pAlerts.get(e.ownerId()).title(), e.role())));
        return new RecordDetailDto(record(r, names), r.rawContent(), sourceSystem, r.ingestedAt(),
                p == null ? null : p.fullName(), p == null ? null : p.displayId(), refs);
    }

    // ------------------------------------------------------------------ patients

    public List<PatientSummaryDto> patientDirectory() {
        Map<String, String> names = orgNames();
        Map<String, Set<String>> orgsByPatient = clinical.organizationsByPatient();
        Map<String, List<Condition>> conds = clinical.allConditions().stream().collect(Collectors.groupingBy(Condition::patientId));
        Map<String, List<Appointment>> appts = clinical.allAppointments().stream().collect(Collectors.groupingBy(Appointment::patientId));
        Map<String, ClinicalRecord> apptRecords = clinical.recordsOfType(RecordType.APPOINTMENT).stream()
                .collect(Collectors.toMap(ClinicalRecord::recordId, Function.identity()));
        Map<String, Integer> recordCounts = clinical.recordCounts();
        Map<String, Long> openFlags = flags.find(null, null, null).stream().filter(f -> OPEN_FLAG.contains(f.status()))
                .collect(Collectors.groupingBy(ClinicalFlag::patientId, Collectors.counting()));
        Map<String, Long> openAlerts = alerts.find(null, null, null).stream().filter(a -> !CLOSED_ALERT.contains(a.status()))
                .collect(Collectors.groupingBy(CoordinationAlert::patientId, Collectors.counting()));
        return patients.findAll().stream().map(p -> summary(p, names, orgsByPatient.getOrDefault(p.patientId(), Set.of()),
                conds.getOrDefault(p.patientId(), List.of()), appts.getOrDefault(p.patientId(), List.of()), apptRecords,
                openFlags.getOrDefault(p.patientId(), 0L).intValue(), openAlerts.getOrDefault(p.patientId(), 0L).intValue(),
                recordCounts.getOrDefault(p.patientId(), 0))).toList();
    }

    public PatientProfileDto patientProfile(String patientId) {
        Patient p = patients.findById(patientId).orElseThrow(() -> ApiException.notFound("Patient", patientId));
        Lookup lk = new Lookup();
        List<ClinicalRecord> records = clinical.recordsForPatient(patientId);
        Map<String, ClinicalRecord> recById = records.stream().collect(Collectors.toMap(ClinicalRecord::recordId, Function.identity()));
        List<Condition> conds = clinical.conditionsForPatient(patientId);
        List<Appointment> appts = clinical.appointmentsForPatient(patientId);
        List<ClinicalFlag> pFlags = lk.allFlags.stream().filter(f -> f.patientId().equals(patientId)).toList();
        List<CoordinationAlert> pAlerts = lk.allAlerts.stream().filter(a -> a.patientId().equals(patientId)).toList();
        Set<String> orgIds = records.stream().map(ClinicalRecord::orgId).collect(Collectors.toCollection(TreeSet::new));
        PatientSummaryDto summary = summary(p, lk.orgNames, orgIds, conds, appts, recById,
                (int) pFlags.stream().filter(f -> OPEN_FLAG.contains(f.status())).count(),
                (int) pAlerts.stream().filter(a -> !CLOSED_ALERT.contains(a.status())).count(), records.size());
        List<IdentifierDto> ids = patients.findIdentifiers(patientId).stream()
                .map(i -> new IdentifierDto(i.orgId(), lk.orgNames.getOrDefault(i.orgId(), i.orgId()), i.sourceMrn())).toList();
        return new PatientProfileDto(summary, ids, conds, clinical.medicationsForPatient(patientId), clinical.labsForPatient(patientId),
                clinical.encountersForPatient(patientId),
                appts.stream().map(a -> appointment(a, lk.orgNames, recById)).toList(),
                records.stream().map(r -> record(r, lk.orgNames)).toList(),
                pFlags.stream().map(f -> flag(f, lk, true)).toList(),
                pAlerts.stream().map(a -> alertSummary(a, lk)).toList(),
                support.observations(patientId), lk.orgNames);
    }

    PatientSummaryDto summary(Patient p, Map<String, String> names, Set<String> orgIds, List<Condition> conds,
                              List<Appointment> appts, Map<String, ClinicalRecord> apptRecords, int openFlags, int openAlerts,
                              int recordCount) {
        LocalDateTime now = LocalDateTime.now();
        AppointmentDto next = appts.stream().filter(a -> a.scheduledStart() != null && a.scheduledStart().isAfter(now))
                .min(Comparator.comparing(Appointment::scheduledStart)).map(a -> appointment(a, names, apptRecords)).orElse(null);
        List<String> key = conds.stream().filter(Condition::isActive).map(Condition::display).filter(Objects::nonNull)
                .filter(d -> !d.toLowerCase(Locale.ROOT).startsWith("encounter for supervision")).distinct().limit(3).toList();
        Integer age = p.dateOfBirth() == null ? null : Period.between(p.dateOfBirth(), LocalDate.now()).getYears();
        return new PatientSummaryDto(p.patientId(), p.displayId(), p.fullName(), p.dateOfBirth(), age, p.sex(), p.obstetric(),
                p.estimatedDueDate(), p.gestationalNote(), names.getOrDefault(p.primaryOrgId(), p.primaryOrgId()),
                p.demoScenario(), p.summary(), orgIds.stream().map(o -> names.getOrDefault(o, o)).sorted().toList(), key, next,
                openFlags, openAlerts, recordCount);
    }

    public AppointmentDto appointment(Appointment a, Map<String, String> names, Map<String, ClinicalRecord> recById) {
        ClinicalRecord r = recById.get(a.recordId());
        boolean delivery = r != null && r.hasTag("DELIVERY");
        long days = a.scheduledStart() == null ? 0 : ChronoUnit.DAYS.between(LocalDate.now(), a.scheduledStart().toLocalDate());
        return new AppointmentDto(a.appointmentId(), a.recordId(), a.orgId(), names.getOrDefault(a.orgId(), a.orgId()),
                a.appointmentType(), a.department(), a.scheduledStart(), days, a.status(), a.providerName(), a.description(),
                a.obstetric(), delivery);
    }

    // ------------------------------------------------------------------ flags

    public List<FlagDto> flagList(String status, String ruleId, String patientId) {
        Lookup lk = new Lookup();
        return lk.allFlags.stream()
                .filter(f -> blank(status) == null || f.status().equals(status.trim()))
                .filter(f -> blank(ruleId) == null || f.ruleId().equals(ruleId.trim()))
                .filter(f -> blank(patientId) == null || f.patientId().equals(patientId.trim()))
                .map(f -> flag(f, lk, true)).toList();
    }

    public FlagDto flagDetail(String flagId) {
        ClinicalFlag f = flags.findById(flagId).orElseThrow(() -> ApiException.notFound("Flag", flagId));
        return flag(f, new Lookup(), true);
    }

    FlagDto flag(ClinicalFlag f, Lookup lk, boolean withEvidence) {
        Patient p = lk.patientsById.get(f.patientId());
        List<EvidenceDto> evidence = withEvidence ? evidence(lk.flagEvidence.getOrDefault(f.flagId(), List.of()), lk) : List.of();
        CoordinationAlert alert = lk.alertByFlag.get(f.flagId());
        AiAnswer explanation = null;
        if (f.aiExplanationJson() != null) {
            try {
                explanation = Json.MAPPER.readValue(f.aiExplanationJson(), AiAnswer.class);
            } catch (JacksonException ignored) {
                // stale or incompatible cache; the UI offers regeneration
            }
        }
        return new FlagDto(f.flagId(), f.patientId(), p == null ? null : p.fullName(), p == null ? null : p.displayId(),
                f.ruleId(), f.ruleVersion(), f.title(), f.detectionReason(), f.reviewPriority(), f.status(), f.statusReason(),
                f.createdAt(), f.updatedAt(), f.updatedBy(), f.lastEvaluatedAt(), f.evidenceUpdatedAt(), evidence,
                alert == null ? null : alert.alertId(), explanation);
    }

    List<EvidenceDto> evidence(List<EvidenceLink> links, Lookup lk) {
        Map<String, Integer> order = Map.of("TRIGGER", 0, "UPCOMING_ENCOUNTER", 1, "SUPPORTING", 2);
        return links.stream()
                .sorted(Comparator.comparing((EvidenceLink l) -> order.getOrDefault(l.role(), 3))
                        .thenComparing(l -> lk.records.containsKey(l.recordId()) && lk.records.get(l.recordId()).recordDate() != null
                                ? lk.records.get(l.recordId()).recordDate() : LocalDateTime.MIN, Comparator.reverseOrder()))
                .map(l -> new EvidenceDto(l.recordId(), l.role(), l.description(),
                        lk.records.containsKey(l.recordId()) ? record(lk.records.get(l.recordId()), lk.orgNames) : null))
                .toList();
    }

    // ------------------------------------------------------------------ alerts

    public List<AlertSummaryDto> alertList(String status, String team) {
        Lookup lk = new Lookup();
        return lk.allAlerts.stream()
                .filter(a -> blank(status) == null || a.status().equals(status.trim()))
                .filter(a -> blank(team) == null || a.assignedTeam().equals(team.trim()))
                .map(a -> alertSummary(a, lk)).toList();
    }

    public AlertDetailDto alertDetail(String alertId) {
        Lookup lk = new Lookup();
        CoordinationAlert a = lk.allAlerts.stream().filter(x -> x.alertId().equals(alertId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Alert", alertId));
        Patient p = lk.patientsById.get(a.patientId());
        List<ClinicalRecord> pRecords = clinical.recordsForPatient(p.patientId());
        Map<String, ClinicalRecord> recById = pRecords.stream().collect(Collectors.toMap(ClinicalRecord::recordId, Function.identity()));
        List<Appointment> appts = clinical.appointmentsForPatient(p.patientId());
        PatientSummaryDto ps = summary(p, lk.orgNames, pRecords.stream().map(ClinicalRecord::orgId).collect(Collectors.toCollection(TreeSet::new)),
                clinical.conditionsForPatient(p.patientId()), appts, recById,
                (int) lk.allFlags.stream().filter(f -> f.patientId().equals(p.patientId()) && OPEN_FLAG.contains(f.status())).count(),
                (int) lk.allAlerts.stream().filter(x -> x.patientId().equals(p.patientId()) && !CLOSED_ALERT.contains(x.status())).count(),
                pRecords.size());
        Appointment ap = a.appointmentId() == null ? null : lk.appointments.get(a.appointmentId());
        AppointmentDto encounter = ap == null ? null : appointment(ap, lk.orgNames, recById);
        FlagDto f = lk.allFlags.stream().filter(x -> x.flagId().equals(a.flagId())).findFirst().map(x -> flag(x, lk, false)).orElse(null);
        List<Activity> activity = new ArrayList<>(support.activityForEntity(alertId));
        activity.addAll(support.activityForEntity(a.flagId()));
        activity.sort(Comparator.comparing(Activity::createdAt).reversed());
        return new AlertDetailDto(alertSummary(a, lk), ps, f, encounter, evidence(lk.alertEvidence.getOrDefault(alertId, List.of()), lk),
                alerts.notes(alertId), activity, a.dismissReason(), a.acknowledgedAt(), new ArrayList<>(transitions(a.status())));
    }

    static Set<String> transitions(String status) {
        return switch (status) {
            case "NEW" -> new LinkedHashSet<>(List.of("UNDER_REVIEW", "COORDINATION_NEEDED", "DISMISSED"));
            case "UNDER_REVIEW" -> new LinkedHashSet<>(List.of("COORDINATION_NEEDED", "PLAN_DOCUMENTED", "DISMISSED"));
            case "COORDINATION_NEEDED" -> new LinkedHashSet<>(List.of("UNDER_REVIEW", "PLAN_DOCUMENTED", "DISMISSED"));
            case "PLAN_DOCUMENTED" -> new LinkedHashSet<>(List.of("COORDINATION_NEEDED", "RESOLVED"));
            default -> new LinkedHashSet<>(List.of("UNDER_REVIEW"));
        };
    }

    AlertSummaryDto alertSummary(CoordinationAlert a, Lookup lk) {
        Patient p = lk.patientsById.get(a.patientId());
        List<EvidenceLink> ev = lk.alertEvidence.getOrDefault(a.alertId(), List.of());
        List<String> orgs = ev.stream().map(e -> lk.records.get(e.recordId())).filter(Objects::nonNull)
                .map(r -> lk.orgNames.getOrDefault(r.orgId(), r.orgId())).distinct().sorted().toList();
        Appointment appt = a.appointmentId() == null ? null : lk.appointments.get(a.appointmentId());
        long days = a.encounterDate() == null ? 0 : ChronoUnit.DAYS.between(LocalDate.now(), a.encounterDate().toLocalDate());
        return new AlertSummaryDto(a.alertId(), a.flagId(), a.patientId(), p == null ? null : p.fullName(),
                p == null ? null : p.displayId(), a.alertType(), a.title(), a.assignedTeam(), a.status(), a.priority(),
                a.encounterDate(), days, appt == null ? null : appt.appointmentType(),
                appt == null ? null : lk.orgNames.getOrDefault(appt.orgId(), appt.orgId()) + " — " + appt.department(),
                a.reviewDueDate(), a.concernSummary(), a.involvedSpecialties(), orgs, ev.size(), a.acknowledgedBy(),
                a.contactStatus(), a.planningDiscussionOccurred(), a.infoNeeded(), a.updatedAt(), a.updatedBy(),
                a.evidenceUpdatedAt(), lk.noteCounts.getOrDefault(a.alertId(), 0));
    }

    // ------------------------------------------------------------------ dashboard

    public DashboardDto dashboard(String databaseMode, boolean geminiConfigured, String geminiModel) {
        Lookup lk = new Lookup();
        LocalDateTime now = LocalDateTime.now();
        List<AlertSummaryDto> upcoming = lk.allAlerts.stream()
                .filter(a -> !CLOSED_ALERT.contains(a.status()) && a.encounterDate() != null && a.encounterDate().isAfter(now))
                .sorted(Comparator.comparing(CoordinationAlert::encounterDate))
                .map(a -> alertSummary(a, lk)).toList();

        Map<String, List<ClinicalFlag>> openByPatient = lk.allFlags.stream().filter(f -> OPEN_FLAG.contains(f.status()))
                .collect(Collectors.groupingBy(ClinicalFlag::patientId));
        Map<String, Long> openAlertsByPatient = lk.allAlerts.stream().filter(a -> !CLOSED_ALERT.contains(a.status()))
                .collect(Collectors.groupingBy(CoordinationAlert::patientId, Collectors.counting()));
        Set<String> review = new TreeSet<>(openByPatient.keySet());
        review.addAll(openAlertsByPatient.keySet());
        List<PatientReviewDto> requiring = review.stream().map(pid -> {
            Patient p = lk.patientsById.get(pid);
            List<ClinicalFlag> fl = openByPatient.getOrDefault(pid, List.of());
            String prio = fl.stream().anyMatch(f -> "EXPEDITED".equals(f.reviewPriority())) ? "EXPEDITED" : "STANDARD";
            return new PatientReviewDto(pid, p == null ? pid : p.fullName(), p == null ? null : p.displayId(),
                    fl.stream().map(ClinicalFlag::title).toList(), prio, fl.size(), openAlertsByPatient.getOrDefault(pid, 0L).intValue());
        }).sorted(Comparator.comparing(PatientReviewDto::highestPriority).thenComparing(PatientReviewDto::name)).toList();

        long openFlags = lk.allFlags.stream().filter(f -> OPEN_FLAG.contains(f.status())).count();
        long newAlerts = lk.allAlerts.stream().filter(a -> "NEW".equals(a.status())).count();
        long inProgress = lk.allAlerts.stream().filter(a -> !CLOSED_ALERT.contains(a.status()) && !"NEW".equals(a.status())).count();
        return new DashboardDto(lk.patientsById.size(), openFlags, newAlerts, inProgress, upcoming.size(), upcoming, requiring,
                support.recentActivity(15), support.lastRun().orElse(null), support.getMeta("ANCHOR_DATE").orElse(null),
                databaseMode, geminiConfigured, geminiModel);
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
