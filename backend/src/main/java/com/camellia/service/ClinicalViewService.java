package com.camellia.service;

import com.camellia.ai.AiDtos.AiAnswer;
import com.camellia.dto.Dtos.*;
import com.camellia.ingestion.Json;
import com.camellia.model.*;
import com.camellia.repository.*;
import com.camellia.security.AuthorizationService.AccessScope;
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

    /**
     * Per-request bulk-loaded reference data, already restricted to what the active user may see. Everything built from
     * a Lookup (directory, profile, flags, alerts, dashboard) therefore inherits the authorization scope.
     */
    final class Lookup {
        final AccessScope scope;
        final Map<String, String> orgNames;
        final Map<String, Patient> patientsById;
        final List<ClinicalFlag> allFlags;
        final List<CoordinationAlert> allAlerts;
        final Map<String, CoordinationAlert> alertByFlag;
        final Map<String, List<EvidenceLink>> flagEvidence;
        final Map<String, List<EvidenceLink>> alertEvidence;
        final Map<String, Integer> noteCounts;
        final Map<String, Appointment> appointments;
        final Map<String, ClinicalRecord> records;

        Lookup(AccessScope scope) {
            this.scope = scope;
            orgNames = orgNames();
            patientsById = patients.findAll().stream().filter(p -> scope.allowsPatient(p.patientId()))
                    .collect(Collectors.toMap(Patient::patientId, Function.identity()));
            allFlags = flags.find(null, null, null).stream().filter(scope::allowsFlag).toList();
            allAlerts = alerts.find(null, null, null).stream().filter(scope::allowsAlert).toList();
            alertByFlag = allAlerts.stream().collect(Collectors.toMap(CoordinationAlert::flagId, Function.identity(), (a, b) -> a));
            Set<String> flagIds = allFlags.stream().map(ClinicalFlag::flagId).collect(Collectors.toSet());
            Set<String> alertIds = allAlerts.stream().map(CoordinationAlert::alertId).collect(Collectors.toSet());
            flagEvidence = flags.allEvidence().stream().filter(e -> flagIds.contains(e.ownerId()))
                    .collect(Collectors.groupingBy(EvidenceLink::ownerId));
            alertEvidence = alertIds.isEmpty() ? Map.of() : alerts.allEvidence().stream().filter(e -> alertIds.contains(e.ownerId()))
                    .collect(Collectors.groupingBy(EvidenceLink::ownerId));
            noteCounts = alertIds.isEmpty() ? Map.of() : alerts.noteCounts();
            appointments = clinical.allAppointments().stream().filter(a -> scope.allowsPatient(a.patientId()))
                    .collect(Collectors.toMap(Appointment::appointmentId, Function.identity()));
            Set<String> ids = new HashSet<>();
            flagEvidence.values().forEach(l -> l.forEach(e -> ids.add(e.recordId())));
            alertEvidence.values().forEach(l -> l.forEach(e -> ids.add(e.recordId())));
            appointments.values().forEach(a -> ids.add(a.recordId()));
            records = new HashMap<>();
            clinical.findRecords(ids).stream().filter(r -> scope.allowsPatient(r.patientId())).forEach(r -> records.put(r.recordId(), r));
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

    /** Caller must have authorized the record's patient. Back-references are filtered to visible flags/alerts. */
    public RecordDetailDto recordDetail(ClinicalRecord r, AccessScope scope) {
        Map<String, String> names = orgNames();
        String sourceSystem = organizations.findAll().stream().filter(o -> o.orgId().equals(r.orgId()))
                .map(Organization::sourceSystem).findFirst().orElse(null);
        Patient p = patients.findById(r.patientId()).orElse(null);
        List<ReferencedBy> refs = new ArrayList<>();
        Map<String, ClinicalFlag> pFlags = flags.find(null, null, r.patientId()).stream().filter(scope::allowsFlag)
                .collect(Collectors.toMap(ClinicalFlag::flagId, Function.identity()));
        flags.allEvidence().stream().filter(e -> e.recordId().equals(r.recordId()) && pFlags.containsKey(e.ownerId()))
                .forEach(e -> refs.add(new ReferencedBy("FLAG", e.ownerId(), pFlags.get(e.ownerId()).title(), e.role())));
        if (scope.anesthesia()) {
            Map<String, CoordinationAlert> pAlerts = alerts.find(null, null, r.patientId()).stream().filter(scope::allowsAlert)
                    .collect(Collectors.toMap(CoordinationAlert::alertId, Function.identity()));
            alerts.allEvidence().stream().filter(e -> e.recordId().equals(r.recordId()) && pAlerts.containsKey(e.ownerId()))
                    .forEach(e -> refs.add(new ReferencedBy("ALERT", e.ownerId(), pAlerts.get(e.ownerId()).title(), e.role())));
        }
        return new RecordDetailDto(record(r, names), r.rawContent(), sourceSystem, r.ingestedAt(),
                p == null ? null : p.fullName(), p == null ? null : p.displayId(), refs);
    }

    // ------------------------------------------------------------------ patients

    public List<PatientSummaryDto> patientDirectory(AccessScope scope) {
        Map<String, String> names = orgNames();
        Map<String, Set<String>> orgsByPatient = clinical.organizationsByPatient();
        Map<String, List<Condition>> conds = clinical.allConditions().stream().collect(Collectors.groupingBy(Condition::patientId));
        Map<String, List<Appointment>> appts = clinical.allAppointments().stream().collect(Collectors.groupingBy(Appointment::patientId));
        Map<String, ClinicalRecord> apptRecords = clinical.recordsOfType(RecordType.APPOINTMENT).stream()
                .collect(Collectors.toMap(ClinicalRecord::recordId, Function.identity()));
        Map<String, Integer> recordCounts = clinical.recordCounts();
        Map<String, Long> openFlags = flags.find(null, null, null).stream().filter(scope::allowsFlag)
                .filter(f -> OPEN_FLAG.contains(f.status()))
                .collect(Collectors.groupingBy(ClinicalFlag::patientId, Collectors.counting()));
        Map<String, Long> openAlerts = alerts.find(null, null, null).stream().filter(scope::allowsAlert)
                .filter(a -> !CLOSED_ALERT.contains(a.status()))
                .collect(Collectors.groupingBy(CoordinationAlert::patientId, Collectors.counting()));
        return patients.findAll().stream().filter(p -> scope.allowsPatient(p.patientId())).map(p -> summary(p, names, orgsByPatient.getOrDefault(p.patientId(), Set.of()),
                conds.getOrDefault(p.patientId(), List.of()), appts.getOrDefault(p.patientId(), List.of()), apptRecords,
                openFlags.getOrDefault(p.patientId(), 0L).intValue(), openAlerts.getOrDefault(p.patientId(), 0L).intValue(),
                recordCounts.getOrDefault(p.patientId(), 0))).toList();
    }

    /** Caller must have authorized the patient. */
    public PatientProfileDto patientProfile(String patientId, AccessScope scope) {
        Patient p = patients.findById(patientId).orElseThrow(() -> ApiException.notFound("Patient", patientId));
        Lookup lk = new Lookup(scope);
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

    public List<FlagDto> flagList(String status, String ruleId, String patientId, AccessScope scope) {
        Lookup lk = new Lookup(scope);
        return lk.allFlags.stream()
                .filter(f -> blank(status) == null || f.status().equals(status.trim()))
                .filter(f -> blank(ruleId) == null || f.ruleId().equals(ruleId.trim()))
                .filter(f -> blank(patientId) == null || f.patientId().equals(patientId.trim()))
                .map(f -> flag(f, lk, true)).toList();
    }

    /** Caller must have authorized the flag. */
    public FlagDto flagDetail(String flagId, AccessScope scope) {
        ClinicalFlag f = flags.findById(flagId).orElseThrow(() -> ApiException.notFound("Flag", flagId));
        return flag(f, new Lookup(scope), true);
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

    public List<AlertSummaryDto> alertList(String status, String team, AccessScope scope) {
        Lookup lk = new Lookup(scope);
        return lk.allAlerts.stream()
                .filter(a -> blank(status) == null || a.status().equals(status.trim()))
                .filter(a -> blank(team) == null || a.assignedTeam().equals(team.trim()))
                .map(a -> alertSummary(a, lk)).toList();
    }

    /** Caller must have authorized the alert. */
    public AlertDetailDto alertDetail(String alertId, AccessScope scope) {
        Lookup lk = new Lookup(scope);
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
                a.evidenceUpdatedAt(), lk.noteCounts.getOrDefault(a.alertId(), 0), a.acknowledgedAt());
    }

    // ------------------------------------------------------------------ dashboard

    public DashboardDto dashboard(AccessScope scope, String databaseMode, boolean geminiConfigured, String geminiModel) {
        Lookup lk = new Lookup(scope);
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

        // General upcoming encounters for the user's accessible patients (all roles).
        Map<String, ClinicalRecord> apptRecords = lk.records;
        List<UpcomingEncounterDto> encounters = lk.appointments.values().stream()
                .filter(a -> a.scheduledStart() != null && a.scheduledStart().isAfter(now) && a.scheduledStart().isBefore(now.plusDays(60)))
                .sorted(Comparator.comparing(Appointment::scheduledStart)).limit(8)
                .map(a -> {
                    Patient p = lk.patientsById.get(a.patientId());
                    return new UpcomingEncounterDto(a.patientId(), p == null ? null : p.fullName(), p == null ? null : p.displayId(),
                            appointment(a, lk.orgNames, apptRecords));
                }).toList();

        // Network-wide run statistics and patient-less activity would reveal data outside a restricted scope.
        boolean fullView = scope.networkWide() && scope.anesthesia();
        return new DashboardDto(lk.patientsById.size(), openFlags, newAlerts, inProgress, upcoming.size(), upcoming, requiring,
                visibleActivity(lk, 15), fullView ? support.lastRun().orElse(null) : null,
                support.getMeta("ANCHOR_DATE").orElse(null), databaseMode, geminiConfigured, geminiModel, scope.anesthesia(),
                encounters);
    }

    /** Activity entries whose patient, flag and alert are all visible to the user. */
    List<Activity> visibleActivity(Lookup lk, int limit) {
        Set<String> allFlagIds = flags.find(null, null, null).stream().map(ClinicalFlag::flagId).collect(Collectors.toSet());
        Set<String> allAlertIds = alerts.find(null, null, null).stream().map(CoordinationAlert::alertId).collect(Collectors.toSet());
        Set<String> visibleFlags = lk.allFlags.stream().map(ClinicalFlag::flagId).collect(Collectors.toSet());
        Set<String> visibleAlerts = lk.allAlerts.stream().map(CoordinationAlert::alertId).collect(Collectors.toSet());
        boolean fullView = lk.scope.networkWide() && lk.scope.anesthesia();
        return support.recentActivity(200).stream().filter(a -> {
            if (a.patientId() == null) return fullView;
            if (!lk.scope.allowsPatient(a.patientId())) return false;
            if (a.entityId() != null && allFlagIds.contains(a.entityId()) && !visibleFlags.contains(a.entityId())) return false;
            if (a.entityId() != null && allAlertIds.contains(a.entityId()) && !visibleAlerts.contains(a.entityId())) return false;
            return !"ALERT".equals(a.entityType()) || lk.scope.anesthesia();
        }).limit(limit).toList();
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
