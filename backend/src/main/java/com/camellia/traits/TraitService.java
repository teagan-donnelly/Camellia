package com.camellia.traits;

import com.camellia.model.ClinicalRecord;
import com.camellia.model.Condition;
import com.camellia.model.PatientTrait;
import com.camellia.repository.ClinicalDataRepository;
import com.camellia.repository.TraitRepository;
import com.camellia.security.AuthorizationService;
import com.camellia.security.DemoUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Structured, searchable patient traits (obesity, morbid obesity). A trait is derived ONLY from a coded ICD-10-CM
 * diagnosis on the patient's record: never from free text, wearable or monitoring data. Traits are reference
 * information for clinicians; they never create alerts or nursing work items.
 */
@Service
public class TraitService {

    private static final Logger log = LoggerFactory.getLogger(TraitService.class);

    public static final String OBESITY = "OBESITY";
    public static final String MORBID_OBESITY = "MORBID_OBESITY";

    /** Trait codes and their neutral display labels, in display order. */
    public static final Map<String, String> LABELS = new LinkedHashMap<>();

    static {
        LABELS.put(OBESITY, "Obesity");
        LABELS.put(MORBID_OBESITY, "Morbid (severe) obesity");
    }

    /**
     * ICD-10-CM codes that establish each trait. E66.3 (overweight) and BMI Z-codes deliberately establish nothing.
     * Package-visible for tests.
     */
    static final Map<String, String> CODE_TO_TRAIT = Map.ofEntries(
            Map.entry("E66.01", MORBID_OBESITY),   // Morbid (severe) obesity due to excess calories
            Map.entry("E66.2", MORBID_OBESITY),    // Morbid (severe) obesity with alveolar hypoventilation
            Map.entry("E66.813", MORBID_OBESITY),  // Obesity, class 3
            Map.entry("E66.09", OBESITY),          // Other obesity due to excess calories
            Map.entry("E66.1", OBESITY),           // Drug-induced obesity
            Map.entry("E66.8", OBESITY),           // Other obesity
            Map.entry("E66.811", OBESITY),         // Obesity, class 1
            Map.entry("E66.812", OBESITY),         // Obesity, class 2
            Map.entry("E66.89", OBESITY),          // Other obesity not elsewhere classified
            Map.entry("E66.9", OBESITY));          // Obesity, unspecified

    private final TraitRepository traits;
    private final ClinicalDataRepository clinical;
    private final AuthorizationService auth;

    public TraitService(TraitRepository traits, ClinicalDataRepository clinical, AuthorizationService auth) {
        this.traits = traits;
        this.clinical = clinical;
        this.auth = auth;
    }

    /** Returns the trait a coded diagnosis establishes, or null. Only ICD-10-CM codes are considered. */
    public static String traitFor(String codeSystem, String code) {
        if (code == null || codeSystem == null) return null;
        String sys = codeSystem.toLowerCase(Locale.ROOT);
        if (!(sys.contains("icd-10-cm") || sys.contains("icd10cm") || sys.endsWith("icd-10"))) return null;
        return CODE_TO_TRAIT.get(code.trim().toUpperCase(Locale.ROOT));
    }

    /** Creates traits for coded diagnoses that do not have one yet. Idempotent: trait ids derive from condition ids. */
    public synchronized int deriveFromConditions() {
        Set<String> existing = traits.existingIds();
        List<PatientTrait> created = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        for (Condition c : clinical.allConditions()) {
            String code = traitFor(c.codeSystem(), c.code());
            String id = "TRT-" + c.conditionId();
            if (code == null || existing.contains(id)) continue;
            ClinicalRecord r = clinical.findRecord(c.recordId()).orElse(null);
            LocalDateTime at = r != null && r.recordDate() != null ? r.recordDate()
                    : c.onsetDate() == null ? null : c.onsetDate().atStartOfDay();
            created.add(new PatientTrait(id, c.patientId(), r == null ? null : r.encounterId(), code, LABELS.get(code),
                    c.codeSystem(), c.code(), c.display(), c.orgId(), c.recordId(), c.conditionId(),
                    c.isActive() ? "ACTIVE" : "INACTIVE", at, c.recordedBy(), now));
        }
        traits.insertAll(created);
        if (!created.isEmpty()) log.info("Derived {} patient trait(s) from coded diagnoses", created.size());
        return created.size();
    }

    public record TraitView(String traitId, String traitCode, String label, String status, String codeSystem, String code,
                            String codeDisplay, String encounterId, String orgId, String sourceRecordId,
                            LocalDateTime recordedAt, String recordedBy) {}

    /**
     * Traits for one patient. Requires access to the patient and a clinical role: the administrative data coordinator
     * imports records but has no clinical need to see diagnosis-derived traits.
     */
    public List<TraitView> forPatient(DemoUser user, String patientId) {
        auth.requirePatient(user, patientId);
        if (!user.role().clinical()) throw AuthorizationService.denied(null);
        return traits.forPatient(patientId).stream()
                .map(t -> new TraitView(t.traitId(), t.traitCode(), t.traitLabel(), t.status(), t.codeSystem(), t.code(),
                        t.codeDisplay(), t.encounterId(), t.orgId(), t.sourceRecordId(), t.recordedAt(), t.recordedBy()))
                .toList();
    }
}
