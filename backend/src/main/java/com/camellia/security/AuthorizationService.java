package com.camellia.security;

import com.camellia.model.ClinicalFlag;
import com.camellia.model.CoordinationAlert;
import com.camellia.repository.PatientRepository;
import com.camellia.repository.PracticeRepository;
import com.camellia.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;

/**
 * Centralized authorization for the SIMULATED demo identities. Every patient-specific backend path (directory,
 * profile, records, search, flags, alerts, coordination, Gemini retrieval, dashboard) asks this service.
 *
 * <p>Denials never reveal whether a restricted patient exists: missing and restricted resources produce the same
 * {@code 403 ACCESS_DENIED} response.</p>
 */
@Service
public class AuthorizationService {

    /** Flags produced by this rule belong to the specialized Anesthesia OB workflow. */
    public static final String ANESTHESIA_RULE = "OB_ANESTHESIA_REVIEW";

    private final PracticeRepository practices;
    private final PatientRepository patients;

    public AuthorizationService(PracticeRepository practices, PatientRepository patients) {
        this.practices = practices;
        this.patients = patients;
    }

    /**
     * What a user may see, computed once per request.
     *
     * @param patientIds  accessible patient ids, or {@code null} when the role has network-wide scope
     * @param anesthesia  whether Anesthesia OB flags/alerts are visible
     */
    public record AccessScope(String userId, Set<String> patientIds, boolean anesthesia) {

        public boolean networkWide() {
            return patientIds == null;
        }

        public boolean allowsPatient(String patientId) {
            return patientId != null && (patientIds == null || patientIds.contains(patientId));
        }

        public boolean allowsRule(String ruleId) {
            return anesthesia || !ANESTHESIA_RULE.equals(ruleId);
        }

        public boolean allowsFlag(ClinicalFlag f) {
            return f != null && allowsPatient(f.patientId()) && allowsRule(f.ruleId());
        }

        public boolean allowsAlert(CoordinationAlert a) {
            return a != null && anesthesia && allowsPatient(a.patientId());
        }
    }

    public AccessScope scope(DemoUser user) {
        Set<String> ids = user.role().networkScope() ? null : Set.copyOf(practices.patientIdsForPractices(user.practiceIds()));
        return new AccessScope(user.id(), ids, user.has(Permission.ANESTHESIA_OB_ALERTS));
    }

    // ------------------------------------------------------------------ permission checks

    public boolean canViewPatient(DemoUser user, String patientId) {
        return user.has(Permission.VIEW_PATIENTS) && scope(user).allowsPatient(patientId) && patients.exists(patientId);
    }

    public boolean canAccessAnesthesiaAlerts(DemoUser user) {
        return user.has(Permission.ANESTHESIA_OB_ALERTS);
    }

    public boolean canViewClinicalFlag(DemoUser user, ClinicalFlag flag) {
        return user.has(Permission.CLINICAL_ALERTS) && scope(user).allowsFlag(flag);
    }

    public boolean canViewAlert(DemoUser user, CoordinationAlert alert) {
        return scope(user).allowsAlert(alert);
    }

    public boolean canUpdateCoordinationStatus(DemoUser user, CoordinationAlert alert) {
        return user.has(Permission.UPDATE_COORDINATION) && canViewAlert(user, alert);
    }

    public boolean canAddCoordinationNote(DemoUser user, CoordinationAlert alert) {
        return user.has(Permission.ADD_COORDINATION_NOTES) && canViewAlert(user, alert);
    }

    public boolean canUpdateFlagStatus(DemoUser user, ClinicalFlag flag) {
        return user.has(Permission.REVIEW_FLAGS) && canViewClinicalFlag(user, flag);
    }

    public boolean canUseClinicalSearch(DemoUser user) {
        return user.has(Permission.CLINICAL_SEARCH);
    }

    // ------------------------------------------------------------------ enforcing variants (throw 403)

    public void requirePermission(DemoUser user, Permission p) {
        if (!user.has(p)) throw denied("Your role (" + user.role().label() + ") does not have access to this function.");
    }

    public void requirePatient(DemoUser user, String patientId) {
        if (!canViewPatient(user, patientId)) throw denied(null);
    }

    public void requireAnesthesiaAlerts(DemoUser user) {
        if (!canAccessAnesthesiaAlerts(user)) {
            throw denied("Anesthesia OB Alerts are available to Labor & Delivery nurses, nurse anesthetists and anesthesiologists.");
        }
    }

    public void requireFlag(DemoUser user, ClinicalFlag flag) {
        if (!canViewClinicalFlag(user, flag)) throw denied(null);
    }

    public void requireAlert(DemoUser user, CoordinationAlert alert) {
        requireAnesthesiaAlerts(user);
        if (!canViewAlert(user, alert)) throw denied(null);
    }

    public void requireAllPatients(DemoUser user, Collection<String> patientIds) {
        for (String id : patientIds) requirePatient(user, id);
    }

    public static ApiException denied(String message) {
        return new ApiException(HttpStatus.FORBIDDEN, "ACCESS_DENIED",
                message != null ? message : "This item is not available to your account (it may not exist, or it is outside your access scope).");
    }
}
