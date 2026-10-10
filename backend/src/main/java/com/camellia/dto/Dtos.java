package com.camellia.dto;

import com.camellia.ai.AiDtos.AiAnswer;
import com.camellia.model.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/** Response DTOs (request bodies live with their controllers). */
public final class Dtos {

    private Dtos() {}

    public record RecordDto(String recordId, String patientId, String orgId, String orgName, String encounterId,
                            String recordType, String title, LocalDateTime recordDate, String author, String specialty,
                            String contentText, String sourceFormat, String sourceRecordId, List<String> tags) {}

    public record RecordDetailDto(RecordDto record, String rawContent, String sourceSystem, LocalDateTime ingestedAt,
                                  String patientName, String patientDisplayId,
                                  List<ReferencedBy> referencedBy) {}

    public record ReferencedBy(String kind, String id, String title, String role) {}

    public record AppointmentDto(String appointmentId, String recordId, String orgId, String orgName, String type,
                                 String department, LocalDateTime scheduledStart, long daysUntil, String status,
                                 String provider, String description, boolean obstetric, boolean deliveryRelated) {}

    public record PatientSummaryDto(String patientId, String displayId, String name, LocalDate dateOfBirth, Integer age,
                                    String sex, boolean obstetric, LocalDate estimatedDueDate, String gestationalNote,
                                    String primaryOrgName, String demoScenario, String summary, List<String> organizations,
                                    List<String> keyConditions, AppointmentDto nextAppointment, int openFlagCount,
                                    int openAlertCount, int recordCount) {}

    public record IdentifierDto(String orgId, String orgName, String sourceMrn) {}

    public record PatientProfileDto(PatientSummaryDto patient, List<IdentifierDto> identifiers, List<Condition> conditions,
                                    List<Medication> medications, List<LabResult> labs, List<Encounter> encounters,
                                    List<AppointmentDto> appointments, List<RecordDto> timeline,
                                    List<FlagDto> flags, List<AlertSummaryDto> alerts, List<AiObservation> aiObservations,
                                    Map<String, String> organizationNames) {}

    public record EvidenceDto(String recordId, String role, String description, RecordDto record) {}

    public record FlagDto(String flagId, String patientId, String patientName, String patientDisplayId, String ruleId,
                          String ruleVersion, String title, String detectionReason, String reviewPriority, String status,
                          String statusReason, LocalDateTime createdAt, LocalDateTime updatedAt, String updatedBy,
                          LocalDateTime lastEvaluatedAt, LocalDateTime evidenceUpdatedAt, List<EvidenceDto> evidence,
                          String alertId, AiAnswer aiExplanation) {}

    public record AlertSummaryDto(String alertId, String flagId, String patientId, String patientName, String patientDisplayId,
                                  String alertType, String title, String assignedTeam, String status, String priority,
                                  LocalDateTime encounterDate, long daysUntilEncounter, String encounterType,
                                  String encounterLocation, LocalDate reviewDueDate, String concernSummary,
                                  String involvedSpecialties, List<String> sourceOrganizations, int evidenceCount,
                                  String acknowledgedBy, String contactStatus, boolean planningDiscussionOccurred,
                                  boolean infoNeeded, LocalDateTime updatedAt, String updatedBy,
                                  LocalDateTime evidenceUpdatedAt, int noteCount) {}

    public record AlertDetailDto(AlertSummaryDto alert, PatientSummaryDto patient, FlagDto flag, AppointmentDto encounter,
                                 List<EvidenceDto> evidence, List<CoordinationNote> notes, List<Activity> activity,
                                 String dismissReason, LocalDateTime acknowledgedAt, List<String> allowedTransitions) {}

    public record DashboardDto(long totalPatients, long openFlags, long unreviewedAnesthesiaAlerts, long alertsInProgress,
                               long upcomingComplexEncounterCount, List<AlertSummaryDto> upcomingComplexEncounters,
                               List<PatientReviewDto> patientsRequiringReview, List<Activity> recentActivity,
                               Map<String, Object> lastAnalysisRun, String anchorDate, String databaseMode,
                               boolean geminiConfigured, String geminiModel) {}

    public record PatientReviewDto(String patientId, String name, String displayId, List<String> flagTitles,
                                   String highestPriority, int openFlags, int openAlerts) {}
}
