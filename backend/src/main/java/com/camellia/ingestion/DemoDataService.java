package com.camellia.ingestion;

import com.camellia.config.CamelliaProperties;
import com.camellia.model.*;
import com.camellia.repository.ClinicalDataRepository;
import com.camellia.repository.OrganizationRepository;
import com.camellia.repository.PatientRepository;
import com.camellia.repository.PracticeRepository;
import com.camellia.repository.SupportRepository;
import com.camellia.repository.WorkforceRepository;
import com.camellia.monitoring.MonitoringService;
import com.camellia.traits.TraitService;
import com.camellia.security.DemoDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.function.Predicate;

import static com.camellia.ingestion.Json.text;

/**
 * Loads the synthetic multi-organization dataset through the normalization layer and persists it.
 * Seeding is idempotent: entities are keyed by deterministic IDs and only missing rows are inserted.
 */
@Service
public class DemoDataService {

    private static final Logger log = LoggerFactory.getLogger(DemoDataService.class);
    public static final String META_ANCHOR = "ANCHOR_DATE";
    public static final String META_SEEDED_AT = "SEEDED_AT";

    /** Source files, their (fictional) originating organization and format. */
    record Source(String resource, String orgId, String format) {}

    static final List<Source> SOURCES = List.of(
            new Source("seed/sources/larkspur-regional-fhir.json", "LRMC", FhirLikeNormalizer.FORMAT),
            new Source("seed/sources/bellwether-practicesuite-export.json", "BWHA", LegacyPracticeJsonNormalizer.FORMAT),
            new Source("seed/sources/northgate-lab-feed.hl7", "NDL", Hl7v2LabNormalizer.FORMAT),
            new Source("seed/sources/cedar-hollow-fhir.json", "CHCH", FhirLikeNormalizer.FORMAT),
            new Source("seed/sources/riverbend-urgent-care-notes.txt", "RUC", PlainTextNoteNormalizer.FORMAT),
            // V3: hospital stays (current admissions with simulated wristbands, plus earlier stays without monitoring).
            new Source("seed/sources/larkspur-inpatient-stays-fhir.json", "LRMC", FhirLikeNormalizer.FORMAT),
            new Source("seed/sources/cedar-hollow-inpatient-stays-fhir.json", "CHCH", FhirLikeNormalizer.FORMAT));

    static final Source INCOMING_LAB = new Source("seed/incoming/northgate-update-marlowe.hl7", "NDL", Hl7v2LabNormalizer.FORMAT);

    private final OrganizationRepository orgs;
    private final PatientRepository patients;
    private final ClinicalDataRepository clinical;
    private final SupportRepository support;
    private final PracticeRepository practices;
    private final CamelliaProperties props;
    private final WorkforceRepository workforce;
    private final MonitoringService monitoring;
    private final TraitService traits;

    public DemoDataService(OrganizationRepository orgs, PatientRepository patients, ClinicalDataRepository clinical,
                           SupportRepository support, PracticeRepository practices, CamelliaProperties props,
                           WorkforceRepository workforce, MonitoringService monitoring, TraitService traits) {
        this.traits = traits;
        this.workforce = workforce;
        this.monitoring = monitoring;
        this.orgs = orgs;
        this.practices = practices;
        this.patients = patients;
        this.clinical = clinical;
        this.support = support;
        this.props = props;
    }

    public record SeedResult(LocalDate anchorDate, int organizationsInserted, int patientsInserted, int recordsInserted,
                             int recordsAlreadyPresent, List<String> warnings) {}

    /** Inserts any missing demo data. Safe to run repeatedly. */
    public synchronized SeedResult seed() {
        LocalDate anchor = resolveAnchor();
        List<Organization> orgList = loadOrganizations();
        Set<String> existingOrgs = orgs.existingIds();
        List<Organization> newOrgs = orgList.stream().filter(o -> !existingOrgs.contains(o.orgId())).toList();
        orgs.insertAll(newOrgs);

        PatientSeed ps = loadPatients(anchor);
        Set<String> existingPatients = patients.existingIds();
        List<Patient> newPatients = ps.patients.stream().filter(p -> !existingPatients.contains(p.patientId())).toList();
        patients.insertAll(newPatients);
        // Source identifiers are inserted when missing (also for existing patients, e.g. a new organization's MRN).
        Set<String> existingIdentifiers = new HashSet<>();
        patients.findIdentifiers().forEach(i -> existingIdentifiers.add(i.patientId() + "|" + i.orgId()));
        patients.insertIdentifiers(ps.identifiers.stream()
                .filter(i -> existingPatients.contains(i.patientId()) || newPatients.stream().anyMatch(p -> p.patientId().equals(i.patientId())))
                .filter(i -> !existingIdentifiers.contains(i.patientId() + "|" + i.orgId())).toList());

        // Practices and patient registrations (role-based access). Inserted when missing, so existing databases
        // seeded before practices existed are upgraded in place.
        List<Practice> practiceList = loadPractices();
        Set<String> existingPractices = practices.existingIds();
        practices.insertAll(practiceList.stream().filter(p -> !existingPractices.contains(p.practiceId())).toList());
        Set<String> existingAssoc = new HashSet<>();
        practices.allAssociations().forEach(a -> existingAssoc.add(a.patientId() + "|" + a.practiceId()));
        practices.insertAssociations(ps.associations.stream()
                .filter(a -> !existingAssoc.contains(a.patientId() + "|" + a.practiceId())).toList());

        seedWorkforce(anchor);

        NormalizedBatch all = new NormalizedBatch();
        SourceContext ctx = new SourceContext(anchor, ps.index(), LocalDateTime.now());
        for (Source s : SOURCES) all.addAll(normalize(s, ctx));
        int[] inserted = persistMissing(all);
        traits.deriveFromConditions();   // also covers databases whose diagnoses predate the traits table
        monitoring.seedBaseline(anchor);

        support.putMeta(META_ANCHOR, anchor.toString());
        support.putMeta(META_SEEDED_AT, LocalDateTime.now().toString());
        all.warnings.forEach(w -> log.warn("Normalization warning: {}", w));
        log.info("Demo seed complete (anchor {}): {} orgs, {} patients, {} records inserted ({} already present)",
                anchor, newOrgs.size(), newPatients.size(), inserted[0], inserted[1]);
        return new SeedResult(anchor, newOrgs.size(), newPatients.size(), inserted[0], inserted[1], all.warnings);
    }

    /**
     * Restores the seeded demo dataset anchored to today and clears workflow state (statuses, notes, alerts, monitoring,
     * briefings). Imported records, uploaded documents and import history are kept: only rows whose ids come from the
     * seed files are removed from the patient and record tables.
     */
    public synchronized SeedResult resetAndReseed() {
        LocalDate anchor = resolveAnchor();   // record ids do not depend on the anchor
        PatientSeed ps = loadPatients(anchor);
        SourceContext ctx = new SourceContext(anchor, ps.index(), LocalDateTime.now());
        Set<String> seedRecordIds = new HashSet<>();
        for (Source s : SOURCES) normalize(s, ctx).records.forEach(r -> seedRecordIds.add(r.recordId()));
        normalize(INCOMING_LAB, ctx).records.forEach(r -> seedRecordIds.add(r.recordId()));
        support.resetDemoData(ps.patients.stream().map(Patient::patientId).toList(), seedRecordIds);
        return seed();
    }

    public record IngestResult(int recordsInserted, int recordsAlreadyPresent, List<String> recordIds) {}

    /** Simulates a new HL7 result arriving from the reference lab (dated relative to today). */
    public synchronized IngestResult ingestIncomingLabResult() {
        SourceContext ctx = new SourceContext(LocalDate.now(), loadPatients(LocalDate.now()).index(), LocalDateTime.now());
        NormalizedBatch batch = normalize(INCOMING_LAB, ctx);
        int[] inserted = persistMissing(batch);
        return new IngestResult(inserted[0], inserted[1], batch.records.stream().map(ClinicalRecord::recordId).toList());
    }

    public Optional<String> anchorDate() {
        return support.getMeta(META_ANCHOR);
    }

    // ------------------------------------------------------------------ internals

    /** The demo anchor date currently in effect (dates in synthetic files are relative to it). */
    public LocalDate currentAnchor() {
        return resolveAnchor();
    }

    NormalizedBatch normalize(Source s, SourceContext ctx) {
        boolean hl7 = Hl7v2LabNormalizer.FORMAT.equals(s.format());
        String content = RelativeDates.render(read(s.resource()), ctx.anchor(), hl7);
        return switch (s.format()) {
            case FhirLikeNormalizer.FORMAT -> new FhirLikeNormalizer().normalize(content, s.orgId(), ctx);
            case LegacyPracticeJsonNormalizer.FORMAT -> new LegacyPracticeJsonNormalizer().normalize(content, s.orgId(), ctx);
            case Hl7v2LabNormalizer.FORMAT -> new Hl7v2LabNormalizer().normalize(content, s.orgId(), ctx);
            case PlainTextNoteNormalizer.FORMAT -> new PlainTextNoteNormalizer().normalize(content, s.orgId(), ctx);
            default -> throw new IllegalArgumentException("Unknown source format " + s.format());
        };
    }

    /** Persists the records of a batch that are not stored yet. Returns {inserted, alreadyPresent} (CLINICAL_RECORDS). */
    public int[] persistMissing(NormalizedBatch b) {
        Set<String> existing = clinical.existingRecordIds();
        Predicate<String> isNew = id -> !existing.contains(id);
        List<ClinicalRecord> records = b.records.stream().filter(r -> isNew.test(r.recordId())).toList();
        clinical.insertRecords(records);
        clinical.insertEncounters(b.encounters.stream().filter(e -> isNew.test(e.recordId())).toList());
        clinical.insertLabs(b.labs.stream().filter(l -> isNew.test(l.recordId())).toList());
        clinical.insertConditions(b.conditions.stream().filter(c -> isNew.test(c.recordId())).toList());
        clinical.insertMedications(b.medications.stream().filter(m -> isNew.test(m.recordId())).toList());
        clinical.insertAppointments(b.appointments.stream().filter(a -> isNew.test(a.recordId())).toList());
        // Coded obesity diagnoses become structured, searchable traits (idempotent).
        if (b.conditions.stream().anyMatch(c -> isNew.test(c.recordId()))) traits.deriveFromConditions();
        return new int[]{records.size(), b.records.size() - records.size()};
    }

    /** 100 synthetic clinicians (+ one administrative account), practice memberships and simulated shifts. */
    private void seedWorkforce(LocalDate anchor) {
        Set<String> existing = workforce.existingClinicianIds();
        workforce.insertClinicians(DemoDirectory.CLINICIANS.stream().filter(c -> !existing.contains(c.user().id())).toList());
        Set<String> links = workforce.existingClinicianPracticeKeys();
        List<String[]> newLinks = new ArrayList<>();
        for (DemoDirectory.Clinician c : DemoDirectory.CLINICIANS) {
            for (String pr : c.user().practiceIds()) {
                if (!links.contains(c.user().id() + "|" + pr)) newLinks.add(new String[]{c.user().id(), pr});
            }
        }
        workforce.insertClinicianPractices(newLinks);

        Set<String> shifts = workforce.existingShiftIds();
        List<WorkforceRepository.Shift> newShifts = new ArrayList<>();
        List<WorkforceRepository.Assignment> newAssignments = new ArrayList<>();
        for (JsonNode n : Json.parse(read("seed/shifts.json"))) {
            String id = text(n.path("shiftId"));
            if (shifts.contains(id)) continue;
            LocalDateTime start = RelativeDates.dateTime(text(n.path("start")), anchor);
            newShifts.add(new WorkforceRepository.Shift(id, text(n.path("userId")), start.toLocalDate(), start,
                    RelativeDates.dateTime(text(n.path("end")), anchor), text(n.path("orgId")), text(n.path("department")),
                    text(n.path("location")), text(n.path("label"))));
            for (JsonNode a : n.path("patients")) {
                newAssignments.add(new WorkforceRepository.Assignment(id, text(a.path("patientId")), text(a.path("role")),
                        text(a.path("note"))));
            }
        }
        workforce.insertShifts(newShifts);
        workforce.insertAssignments(newAssignments);

        // Unit roster for the dashboard: later shifts in the week, open shifts and staffing coverage.
        Set<String> roster = workforce.existingRosterIds();
        List<WorkforceRepository.RosterShift> newRoster = new ArrayList<>();
        for (JsonNode n : Json.parse(read("seed/unit-schedule.json"))) {
            String id = text(n.path("scheduleId"));
            if (roster.contains(id)) continue;
            newRoster.add(new WorkforceRepository.RosterShift(id, text(n.path("userId")), text(n.path("linkedShiftId")),
                    text(n.path("orgId")), text(n.path("department")), text(n.path("unit")), text(n.path("label")),
                    RelativeDates.dateTime(text(n.path("start")), anchor), RelativeDates.dateTime(text(n.path("end")), anchor),
                    text(n.path("status")), n.path("staffed").isNumber() ? n.path("staffed").asInt() : null,
                    n.path("target").isNumber() ? n.path("target").asInt() : null, text(n.path("note"))));
        }
        workforce.insertRoster(newRoster);
    }

    private LocalDate resolveAnchor() {
        String configured = props.getDemo().getAnchorDate();
        if (configured != null && !configured.isBlank()) return LocalDate.parse(configured.trim());
        return support.getMeta(META_ANCHOR).map(LocalDate::parse).orElse(LocalDate.now());
    }

    private List<Practice> loadPractices() {
        List<Practice> out = new ArrayList<>();
        for (JsonNode p : Json.parse(read("seed/practices.json"))) {
            out.add(new Practice(text(p.path("practiceId")), text(p.path("name")), text(p.path("orgId")), text(p.path("specialty"))));
        }
        return out;
    }

    private List<Organization> loadOrganizations() {
        List<Organization> out = new ArrayList<>();
        for (JsonNode o : Json.parse(read("seed/organizations.json"))) {
            out.add(new Organization(text(o.path("orgId")), text(o.path("name")), text(o.path("orgType")),
                    text(o.path("sourceSystem")), text(o.path("sourceFormat")), text(o.path("city")), text(o.path("state"))));
        }
        return out;
    }

    record PatientSeed(List<Patient> patients, List<PatientIdentifier> identifiers,
                       List<PracticeRepository.Association> associations) {
        Map<String, String> index() {
            Map<String, String> idx = new HashMap<>();
            identifiers.forEach(i -> idx.put(SourceContext.indexKey(i.orgId(), i.sourceMrn()), i.patientId()));
            return idx;
        }
    }

    private PatientSeed loadPatients(LocalDate anchor) {
        List<Patient> list = new ArrayList<>();
        List<PatientIdentifier> ids = new ArrayList<>();
        List<PracticeRepository.Association> assoc = new ArrayList<>();
        for (JsonNode p : Json.parse(read("seed/patients.json"))) {
            String pid = text(p.path("patientId"));
            list.add(new Patient(pid, text(p.path("displayId")), text(p.path("firstName")), text(p.path("lastName")),
                    LocalDate.parse(text(p.path("dateOfBirth"))), text(p.path("sex")), p.path("obstetric").asBoolean(),
                    RelativeDates.date(text(p.path("estimatedDueDate")), anchor), text(p.path("gestationalNote")),
                    text(p.path("primaryOrgId")), text(p.path("summary")), text(p.path("demoScenario")), null));
            for (JsonNode i : p.path("identifiers")) {
                ids.add(new PatientIdentifier(pid, text(i.path("orgId")), text(i.path("mrn"))));
            }
            for (JsonNode pr : p.path("practices")) {
                assoc.add(new PracticeRepository.Association(pid, text(pr.path("practiceId")), text(pr.path("relationship"))));
            }
        }
        return new PatientSeed(list, ids, assoc);
    }

    static String read(String resource) {
        try {
            return new ClassPathResource(resource).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Missing seed resource " + resource, e);
        }
    }
}
