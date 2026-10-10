package com.camellia.coordination;

import com.camellia.config.DemoUsers.DemoUser;
import com.camellia.model.ClinicalFlag;
import com.camellia.model.CoordinationAlert;
import com.camellia.model.CoordinationNote;
import com.camellia.repository.AlertRepository;
import com.camellia.repository.FlagRepository;
import com.camellia.repository.SupportRepository;
import com.camellia.web.ApiException;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Human-driven administrative workflow for care-coordination alerts and clinical flags.
 * These states describe coordination progress only — they never assert that a patient is clinically cleared.
 * AI components cannot call this service.
 */
@Service
public class CoordinationService {

    public static final List<String> ALERT_STATUSES = List.of("NEW", "UNDER_REVIEW", "COORDINATION_NEEDED", "PLAN_DOCUMENTED", "RESOLVED", "DISMISSED");
    public static final List<String> CONTACT_STATUSES = List.of("NOT_STARTED", "PLANNED", "COMPLETED");
    public static final List<String> NOTE_TYPES = List.of("GENERAL", "INFO_REQUEST", "CONTACT", "PLANNING_DISCUSSION", "PLAN_SUMMARY");
    public static final List<String> FLAG_STATUSES = List.of("UNREVIEWED", "IN_REVIEW", "REVIEWED", "DISMISSED");

    static final Map<String, Set<String>> ALERT_TRANSITIONS = Map.of(
            "NEW", Set.of("UNDER_REVIEW", "COORDINATION_NEEDED", "DISMISSED"),
            "UNDER_REVIEW", Set.of("COORDINATION_NEEDED", "PLAN_DOCUMENTED", "DISMISSED"),
            "COORDINATION_NEEDED", Set.of("UNDER_REVIEW", "PLAN_DOCUMENTED", "DISMISSED"),
            "PLAN_DOCUMENTED", Set.of("COORDINATION_NEEDED", "RESOLVED"),
            "RESOLVED", Set.of("UNDER_REVIEW"),
            "DISMISSED", Set.of("UNDER_REVIEW"));

    static final Map<String, Set<String>> FLAG_TRANSITIONS = Map.of(
            "UNREVIEWED", Set.of("IN_REVIEW", "REVIEWED", "DISMISSED"),
            "IN_REVIEW", Set.of("REVIEWED", "DISMISSED", "UNREVIEWED"),
            "REVIEWED", Set.of("IN_REVIEW"),
            "DISMISSED", Set.of("IN_REVIEW"));

    private final AlertRepository alerts;
    private final FlagRepository flags;
    private final SupportRepository support;

    public CoordinationService(AlertRepository alerts, FlagRepository flags, SupportRepository support) {
        this.alerts = alerts;
        this.flags = flags;
        this.support = support;
    }

    /** All fields optional; only provided fields change. */
    public record AlertUpdate(Boolean acknowledge, String status, String contactStatus, Boolean planningDiscussionOccurred,
                              Boolean infoNeeded, String reason, String note) {}

    public CoordinationAlert updateAlert(String alertId, AlertUpdate u, DemoUser user) {
        CoordinationAlert a = alerts.findById(alertId).orElseThrow(() -> ApiException.notFound("Alert", alertId));
        if (!user.team().equals(a.assignedTeam())) {
            throw ApiException.forbidden("Simulated access control: only members of '" + a.assignedTeam()
                    + "' can change this alert's workflow. You are signed in as " + user.name() + " (" + user.team()
                    + "). You can still add notes.");
        }
        LocalDateTime now = LocalDateTime.now();
        List<String> changes = new ArrayList<>();
        String status = a.status();
        String ackBy = a.acknowledgedBy();
        LocalDateTime ackAt = a.acknowledgedAt();
        String contact = a.contactStatus();
        boolean planning = a.planningDiscussionOccurred();
        boolean infoNeeded = a.infoNeeded();
        String dismissReason = a.dismissReason();

        if (Boolean.TRUE.equals(u.acknowledge()) && ackBy == null) {
            ackBy = user.name();
            ackAt = now;
            changes.add("Acknowledged by " + user.name());
            if ("NEW".equals(status) && u.status() == null) {
                status = "UNDER_REVIEW";
                changes.add("Status NEW → UNDER_REVIEW");
            }
        }
        if (u.status() != null && !u.status().equals(status)) {
            String target = u.status().trim().toUpperCase(Locale.ROOT);
            if (!ALERT_STATUSES.contains(target)) throw ApiException.badRequest("Unknown alert status: " + u.status());
            if (!ALERT_TRANSITIONS.get(status).contains(target)) {
                throw ApiException.badRequest("Transition " + status + " → " + target + " is not allowed. Allowed: "
                        + ALERT_TRANSITIONS.get(status));
            }
            if (target.equals("DISMISSED")) {
                if (isBlank(u.reason())) throw ApiException.badRequest("A reason is required to dismiss an alert.");
                dismissReason = u.reason().trim();
            }
            if (target.equals("PLAN_DOCUMENTED") && isBlank(u.note())) {
                throw ApiException.badRequest("Summarize the plan documented by the care team (note) before marking Plan Documented.");
            }
            if (target.equals("UNDER_REVIEW") && (status.equals("DISMISSED") || status.equals("RESOLVED"))) dismissReason = null;
            if (ackBy == null && !target.equals("DISMISSED")) {
                ackBy = user.name();
                ackAt = now;
            }
            changes.add("Status " + status + " → " + target + (target.equals("DISMISSED") ? " (reason: " + dismissReason + ")" : ""));
            status = target;
        }
        if (u.contactStatus() != null && !u.contactStatus().equals(contact)) {
            String target = u.contactStatus().trim().toUpperCase(Locale.ROOT);
            if (!CONTACT_STATUSES.contains(target)) throw ApiException.badRequest("Unknown contact status: " + u.contactStatus());
            changes.add("Referring-provider contact " + contact + " → " + target);
            contact = target;
        }
        if (u.planningDiscussionOccurred() != null && u.planningDiscussionOccurred() != planning) {
            planning = u.planningDiscussionOccurred();
            changes.add(planning ? "Planning discussion recorded as occurred" : "Planning discussion marked as not yet occurred");
        }
        if (u.infoNeeded() != null && u.infoNeeded() != infoNeeded) {
            infoNeeded = u.infoNeeded();
            changes.add(infoNeeded ? "Marked: further information needed" : "Cleared: further information needed");
        }
        if (changes.isEmpty() && isBlank(u.note())) return a;

        CoordinationAlert updated = new CoordinationAlert(a.alertId(), a.flagId(), a.patientId(), a.alertType(), a.title(),
                a.assignedTeam(), status, a.priority(), a.appointmentId(), a.encounterDate(), a.reviewDueDate(), ackBy, ackAt,
                contact, planning, infoNeeded, dismissReason, a.concernSummary(), a.involvedSpecialties(), a.createdAt(), now,
                user.name(), a.evidenceUpdatedAt());
        alerts.updateWorkflow(updated);
        if (!changes.isEmpty()) {
            String detail = String.join("; ", changes);
            alerts.insertNote(new CoordinationNote(SupportRepository.newId("NTE"), alertId, a.patientId(), user.name(), user.role(),
                    "STATUS", detail, now));
            support.logActivity("ALERT", alertId, a.patientId(), "ALERT_UPDATED", detail, user.name());
        }
        if (!isBlank(u.note())) {
            String type = "PLAN_DOCUMENTED".equals(status) && u.status() != null ? "PLAN_SUMMARY" : "GENERAL";
            addNote(alertId, type, u.note(), user);
        }
        return updated;
    }

    public CoordinationNote addNote(String alertId, String noteType, String body, DemoUser user) {
        CoordinationAlert a = alerts.findById(alertId).orElseThrow(() -> ApiException.notFound("Alert", alertId));
        if (isBlank(body)) throw ApiException.badRequest("Note text is required.");
        if (body.length() > 4000) throw ApiException.badRequest("Note text must be 4000 characters or fewer.");
        String type = noteType == null ? "GENERAL" : noteType.trim().toUpperCase(Locale.ROOT);
        if (!NOTE_TYPES.contains(type)) throw ApiException.badRequest("Unknown note type: " + noteType + ". Allowed: " + NOTE_TYPES);
        CoordinationNote n = new CoordinationNote(SupportRepository.newId("NTE"), alertId, a.patientId(), user.name(), user.role(),
                type, body.trim(), LocalDateTime.now());
        alerts.insertNote(n);
        support.logActivity("ALERT", alertId, a.patientId(), "NOTE_ADDED", type + ": " + abbreviate(body.trim(), 160), user.name());
        return n;
    }

    public ClinicalFlag updateFlagStatus(String flagId, String status, String reason, DemoUser user) {
        ClinicalFlag f = flags.findById(flagId).orElseThrow(() -> ApiException.notFound("Flag", flagId));
        String target = status == null ? null : status.trim().toUpperCase(Locale.ROOT);
        if (target == null || !FLAG_STATUSES.contains(target)) throw ApiException.badRequest("Status must be one of " + FLAG_STATUSES);
        if (target.equals(f.status())) return f;
        if (!FLAG_TRANSITIONS.get(f.status()).contains(target)) {
            throw ApiException.badRequest("Transition " + f.status() + " → " + target + " is not allowed. Allowed: " + FLAG_TRANSITIONS.get(f.status()));
        }
        if (target.equals("DISMISSED") && isBlank(reason)) throw ApiException.badRequest("A reason is required to dismiss a flag.");
        LocalDateTime now = LocalDateTime.now();
        flags.updateStatus(flagId, target, isBlank(reason) ? null : reason.trim(), user.name(), now);
        support.logActivity("FLAG", flagId, f.patientId(), "FLAG_STATUS",
                f.status() + " → " + target + (isBlank(reason) ? "" : " (" + reason.trim() + ")"), user.name());
        return flags.findById(flagId).orElseThrow();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
