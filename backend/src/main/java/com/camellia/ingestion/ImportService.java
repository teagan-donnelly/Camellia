package com.camellia.ingestion;

import com.camellia.analysis.AnalysisService;
import com.camellia.model.*;
import com.camellia.repository.ClinicalDataRepository;
import com.camellia.repository.ImportRepository;
import com.camellia.repository.ImportRepository.ImportBatch;
import com.camellia.repository.OrganizationRepository;
import com.camellia.repository.PatientRepository;
import com.camellia.repository.PracticeRepository;
import com.camellia.repository.SupportRepository;
import com.camellia.security.AuthorizationService;
import com.camellia.security.AuthorizationService.AccessScope;
import com.camellia.security.DemoUser;
import com.camellia.security.Permission;
import com.camellia.web.ApiException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import static com.camellia.ingestion.Json.text;

/**
 * Imports SYNTHETIC patient record files (preview → confirm). Supported inputs:
 * <ul>
 *   <li>{@code FHIR_JSON}: a FHIR R4-style Bundle with one Patient resource and Encounter, Observation, Condition,
 *       MedicationStatement, DocumentReference, DiagnosticReport and Appointment resources.</li>
 *   <li>{@code CAMELLIA_JSON}: the documented Camellia import format ({@code "format": "camellia-import/v1"}).</li>
 *   <li>{@code CSV}: the documented one-row-per-item CSV format.</li>
 * </ul>
 * All three are converted to the same FHIR-style structure and normalized by {@link FhirLikeNormalizer}, so imported
 * records get the same deterministic IDs (duplicate detection), concept tags and evidence handling as seeded data.
 *
 * <p>Patients are never merged automatically: the preview lists candidate matches (exact source identifier, or
 * demographic similarity) and the user must explicitly choose to attach to one of them or create a new patient.</p>
 *
 * <p>Not an EHR integration: this demonstrates the ingestion and normalization path that future authorized
 * integrations could feed.</p>
 */
@Service
public class ImportService {

    public static final int MAX_BYTES = 1_000_000;
    public static final int MAX_ITEMS = 500;
    static final Set<String> FHIR_TYPES = Set.of("Encounter", "Observation", "Condition", "MedicationStatement",
            "DocumentReference", "DiagnosticReport", "Appointment");
    static final List<String> CSV_COLUMNS = List.of("record_type", "source_id", "patient_mrn", "patient_first_name",
            "patient_last_name", "patient_birth_date", "patient_sex", "org_id", "org_name", "date", "end_date", "code", "name",
            "value", "unit", "ref_low", "ref_high", "flag", "text", "provider", "department", "status", "encounter_source_id",
            "encounter_class");

    public record Sample(String file, String title, String description, String format, String scenario) {}

    static final List<Sample> SAMPLES = List.of(
            new Sample("01-new-patient-camellia.json", "New patient (Camellia JSON)",
                    "A completely new fictional patient from Larkspur Regional's primary care clinic.", "CAMELLIA_JSON",
                    "Preview shows no existing match → create a new patient."),
            new Sample("02-existing-patient-labs.csv", "More records for an existing patient (CSV)",
                    "Two new Cedar Hollow laboratory results and a clinic note for Caleb Wren, identified by his Cedar Hollow MRN.",
                    "CSV", "Preview finds an exact source-identifier match → attach to Caleb Wren."),
            new Sample("03-second-hospital-fhir.json", "Records from a second hospital (FHIR)",
                    "An emergency visit at Magnolia Bay Medical Center, a hospital Camellia has not seen before.", "FHIR_JSON",
                    "No identifier match; Elias Navarro is a possible match by name and birth date → confirm before attaching."),
            new Sample("04-new-ob-patient-low-platelets-fhir.json", "New obstetric patient with a reviewable finding (FHIR)",
                    "A new patient with a scheduled cesarean delivery and platelet results below the laboratory reference range.",
                    "FHIR_JSON", "Create a new patient, then run screening → an Anesthesia OB review flag and alert are created."),
            new Sample("05-invalid-file.json", "Invalid file (validation errors)",
                    "Missing patient identifier, an unparseable date and an unsupported resource type.", "FHIR_JSON",
                    "Preview is rejected with specific validation errors; nothing is imported."));

    private final PatientRepository patients;
    private final ClinicalDataRepository clinical;
    private final OrganizationRepository orgs;
    private final PracticeRepository practices;
    private final ImportRepository imports;
    private final SupportRepository support;
    private final DemoDataService demoData;
    private final AnalysisService analysis;
    private final AuthorizationService auth;

    public ImportService(PatientRepository patients, ClinicalDataRepository clinical, OrganizationRepository orgs,
                         PracticeRepository practices, ImportRepository imports, SupportRepository support,
                         DemoDataService demoData, AnalysisService analysis, AuthorizationService auth) {
        this.patients = patients;
        this.clinical = clinical;
        this.orgs = orgs;
        this.practices = practices;
        this.imports = imports;
        this.support = support;
        this.demoData = demoData;
        this.analysis = analysis;
        this.auth = auth;
    }

    // ================================================================================================================
    // API types
    // ================================================================================================================

    public record ImportRequest(String fileName, String content) {}

    public record ConfirmRequest(String fileName, String content, String fileSha256, String decision, String patientId,
                                 String practiceId, Boolean runScreening) {}

    public record OrganizationRef(String orgId, String name, boolean known) {}

    public record PatientRef(String mrn, String firstName, String lastName, LocalDate dateOfBirth, String sex) {}

    public record PreviewItem(String sourceId, String recordType, String title, LocalDateTime date, boolean duplicate) {}

    public record Match(String patientId, String displayId, String name, LocalDate dateOfBirth, String matchType, String detail) {}

    public record Preview(boolean valid, List<String> errors, List<String> warnings, String fileName, String fileSha256,
                          String format, OrganizationRef organization, PatientRef patient, Map<String, Integer> counts,
                          int totalRecords, int duplicateRecords, List<PreviewItem> items, List<Match> matches,
                          String suggestedDecision, String suggestedPatientId, boolean previouslyImported, String notice) {}

    public record ConfirmResult(String importId, String patientId, String patientName, String displayId, String decision,
                                int recordsInserted, int recordsDuplicate, List<String> recordIds, boolean screeningRun,
                                List<String> screeningOutcomes, String notice) {}

    static final String NOTICE = "Synthetic test files only. This demonstrates Camellia's ingestion and normalization path; "
            + "it is not an Epic or other EHR integration.";

    // ================================================================================================================
    // Preview
    // ================================================================================================================

    public Preview preview(DemoUser user, ImportRequest req) {
        auth.requirePermission(user, Permission.IMPORT_RECORDS);
        Parsed parsed = parse(req == null ? null : req.fileName(), req == null ? null : req.content());
        if (!parsed.errors.isEmpty()) {
            return new Preview(false, parsed.errors, parsed.warnings, parsed.fileName, parsed.sha, parsed.format, parsed.org,
                    parsed.patient, Map.of(), 0, 0, List.of(), List.of(), null, null, false, NOTICE);
        }
        NormalizedBatch batch;
        try {
            batch = normalize(parsed, "PREVIEW-PATIENT");
        } catch (RuntimeException e) {
            return new Preview(false, List.of("The records could not be normalized: " + rootMessage(e)), parsed.warnings,
                    parsed.fileName, parsed.sha, parsed.format, parsed.org, parsed.patient, Map.of(), 0, 0, List.of(), List.of(),
                    null, null, false, NOTICE);
        }
        List<String> errors = new ArrayList<>(parsed.errors);
        List<String> warnings = new ArrayList<>(parsed.warnings);
        warnings.addAll(batch.warnings);
        if (batch.records.isEmpty()) errors.add("The file contains no importable clinical records.");
        Set<String> existing = clinical.existingRecordIds();
        List<PreviewItem> items = batch.records.stream().sorted(Comparator.comparing(ClinicalRecord::recordDate,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .map(r -> new PreviewItem(r.sourceRecordId(), r.recordType().name(), r.title(), r.recordDate(), existing.contains(r.recordId())))
                .toList();
        Map<String, Integer> counts = new TreeMap<>();
        batch.records.forEach(r -> counts.merge(r.recordType().name(), 1, Integer::sum));
        int dup = (int) items.stream().filter(PreviewItem::duplicate).count();
        List<Match> matches = matches(parsed, auth.scope(user));
        String decision = matches.stream().anyMatch(m -> m.matchType().equals("EXACT_SOURCE_IDENTIFIER")) ? "ATTACH_EXISTING" : "CREATE_NEW";
        String suggested = matches.stream().filter(m -> m.matchType().equals("EXACT_SOURCE_IDENTIFIER")).map(Match::patientId)
                .findFirst().orElse(null);
        if (dup == items.size() && !items.isEmpty()) warnings.add("Every record in this file is already in Camellia; confirming imports nothing new.");
        boolean seen = imports.history(200).stream().anyMatch(b -> parsed.sha.equals(b.fileSha256()) && "COMPLETED".equals(b.status()));
        if (seen) warnings.add("This exact file was imported before.");
        return new Preview(errors.isEmpty(), errors, warnings, parsed.fileName, parsed.sha, parsed.format, parsed.org, parsed.patient,
                counts, items.size(), dup, items.stream().limit(60).toList(), matches, decision, suggested, seen, NOTICE);
    }

    /** Candidate existing patients. Names alone never decide: everything except an exact source identifier needs a person. */
    List<Match> matches(Parsed p, AccessScope scope) {
        List<Match> out = new ArrayList<>();
        Map<String, Patient> byId = patients.findAll().stream().filter(x -> scope.allowsPatient(x.patientId()))
                .collect(Collectors.toMap(Patient::patientId, x -> x));
        Set<String> seen = new HashSet<>();
        for (PatientIdentifier id : patients.findIdentifiers()) {
            if (id.orgId().equals(p.org.orgId()) && id.sourceMrn().equalsIgnoreCase(p.patient.mrn()) && byId.containsKey(id.patientId())) {
                Patient x = byId.get(id.patientId());
                out.add(new Match(x.patientId(), x.displayId(), x.fullName(), x.dateOfBirth(), "EXACT_SOURCE_IDENTIFIER",
                        "Camellia already links " + p.org.orgId() + " MRN " + p.patient.mrn() + " to this patient."));
                seen.add(x.patientId());
            }
        }
        for (Patient x : byId.values()) {
            if (seen.contains(x.patientId())) continue;
            boolean sameLast = x.lastName().equalsIgnoreCase(p.patient.lastName());
            boolean sameFirst = x.firstName().equalsIgnoreCase(p.patient.firstName());
            boolean sameDob = Objects.equals(x.dateOfBirth(), p.patient.dateOfBirth());
            String type = sameFirst && sameLast && sameDob ? "NAME_AND_BIRTH_DATE"
                    : sameLast && sameDob ? "LAST_NAME_AND_BIRTH_DATE"
                    : sameFirst && sameLast ? "NAME_ONLY" : null;
            if (type != null) {
                out.add(new Match(x.patientId(), x.displayId(), x.fullName(), x.dateOfBirth(), type,
                        "Possible match on demographics only — confirm with additional identifiers before attaching."));
            }
        }
        return out;
    }

    // ================================================================================================================
    // Confirm
    // ================================================================================================================

    public synchronized ConfirmResult confirm(DemoUser user, ConfirmRequest req) {
        auth.requirePermission(user, Permission.IMPORT_RECORDS);
        if (req == null) throw ApiException.badRequest("Request body is required");
        Parsed parsed = parse(req.fileName(), req.content());
        if (!parsed.errors.isEmpty()) throw ApiException.badRequest("The file is invalid: " + String.join("; ", parsed.errors));
        if (req.fileSha256() == null || !req.fileSha256().equals(parsed.sha)) {
            throw new ApiException(HttpStatus.CONFLICT, "IMPORT_CHANGED", "The file differs from the previewed file. Preview it again.");
        }
        String decision = req.decision() == null ? "" : req.decision().trim().toUpperCase(Locale.ROOT);
        PatientIdentifier existingLink = patients.findIdentifiers().stream()
                .filter(i -> i.orgId().equals(parsed.org.orgId()) && i.sourceMrn().equalsIgnoreCase(parsed.patient.mrn()))
                .findFirst().orElse(null);

        Patient patient;
        switch (decision) {
            case "CREATE_NEW" -> {
                if (existingLink != null) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDENTIFIER_IN_USE", parsed.org.orgId() + " MRN " + parsed.patient.mrn()
                            + " is already linked to an existing patient. Attach the records to that patient instead.");
                }
                patient = createPatient(parsed, user);
                if (req.practiceId() != null && !req.practiceId().isBlank()) {
                    if (practices.findAll().stream().noneMatch(pr -> pr.practiceId().equals(req.practiceId()))) {
                        throw ApiException.badRequest("Unknown practice " + req.practiceId());
                    }
                    practices.insertAssociations(List.of(new PracticeRepository.Association(patient.patientId(), req.practiceId(),
                            "Registered via import")));
                }
            }
            case "ATTACH_EXISTING" -> {
                if (req.patientId() == null || req.patientId().isBlank()) throw ApiException.badRequest("patientId is required to attach records");
                auth.requirePatient(user, req.patientId());
                patient = patients.findById(req.patientId()).orElseThrow(() -> AuthorizationService.denied(null));
                if (existingLink != null && !existingLink.patientId().equals(patient.patientId())) {
                    throw new ApiException(HttpStatus.CONFLICT, "IDENTIFIER_IN_USE", parsed.org.orgId() + " MRN " + parsed.patient.mrn()
                            + " belongs to a different patient in Camellia. Choose that patient instead.");
                }
                ensureOrganization(parsed.org);
                if (existingLink == null) {
                    patients.insertIdentifiers(List.of(new PatientIdentifier(patient.patientId(), parsed.org.orgId(), parsed.patient.mrn())));
                }
            }
            default -> throw ApiException.badRequest("decision must be CREATE_NEW or ATTACH_EXISTING");
        }

        NormalizedBatch batch = normalize(parsed, patient.patientId());
        int[] counts = demoData.persistMissing(batch);
        String importId = SupportRepository.newId("IMP");
        imports.insert(new ImportBatch(importId, parsed.fileName, parsed.sha, parsed.format, parsed.org.orgId(), patient.patientId(),
                decision, counts[0], counts[1], "COMPLETED", batch.warnings.isEmpty() ? null : String.join("; ", batch.warnings),
                user.name(), LocalDateTime.now()));
        support.logActivity("IMPORT", importId, patient.patientId(), "RECORDS_IMPORTED",
                "Imported %d record(s) (%d already present) from %s (%s)".formatted(counts[0], counts[1], parsed.org.name(), parsed.format),
                user.name());

        boolean screen = req.runScreening() == null || req.runScreening();
        List<String> outcomes = new ArrayList<>();
        if (screen) {
            AnalysisService.RunResult run = analysis.run(user.name() + " (after import)");
            run.outcomes().stream().filter(o -> o.patientId().equals(patient.patientId()))
                    .forEach(o -> outcomes.add(o.ruleId() + ": " + o.result()));
        }
        return new ConfirmResult(importId, patient.patientId(), patient.fullName(), patient.displayId(), decision, counts[0], counts[1],
                batch.records.stream().map(ClinicalRecord::recordId).toList(), screen, outcomes, NOTICE);
    }

    public List<ImportBatch> history(DemoUser user) {
        auth.requirePermission(user, Permission.IMPORT_RECORDS);
        return imports.history(100);
    }

    public List<Sample> samples(DemoUser user) {
        auth.requirePermission(user, Permission.IMPORT_RECORDS);
        return SAMPLES;
    }

    public String sample(DemoUser user, String file) {
        auth.requirePermission(user, Permission.IMPORT_RECORDS);
        if (SAMPLES.stream().noneMatch(s -> s.file().equals(file))) throw ApiException.notFound("Sample", file);
        try {
            return new ClassPathResource("import-samples/" + file).getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Patient createPatient(Parsed p, DemoUser user) {
        ensureOrganization(p.org);
        Set<String> ids = patients.existingIds();
        int n = 2001;
        while (ids.contains("CAM-P-" + n)) n++;
        String patientId = "CAM-P-" + n;
        Set<String> displayIds = patients.findAll().stream().map(Patient::displayId).collect(Collectors.toSet());
        Random r = new Random(Objects.hash(p.org.orgId(), p.patient.mrn()));
        String displayId;
        do {
            displayId = "SYN-" + (100000 + r.nextInt(900000));
        } while (displayIds.contains(displayId));
        NormalizedBatch probe = normalize(p, patientId);
        boolean obstetric = probe.records.stream().anyMatch(rec -> rec.hasTag("OBSTETRIC") || rec.hasTag("DELIVERY"));
        Patient patient = new Patient(patientId, displayId, p.patient.firstName(), p.patient.lastName(), p.patient.dateOfBirth(),
                p.patient.sex(), obstetric, null, null, p.org.orgId(),
                "Imported from %s on %s by %s (synthetic test record).".formatted(p.org.name(), LocalDate.now(), user.name()),
                "Imported patient", LocalDateTime.now());
        patients.insertAll(List.of(patient));
        patients.insertIdentifiers(List.of(new PatientIdentifier(patientId, p.org.orgId(), p.patient.mrn())));
        return patients.findById(patientId).orElse(patient);
    }

    private void ensureOrganization(OrganizationRef ref) {
        if (!orgs.existingIds().contains(ref.orgId())) {
            orgs.insertAll(List.of(new Organization(ref.orgId(), ref.name(), "Imported source organization (synthetic)",
                    "Patient record import (synthetic file)", "IMPORT", null, null)));
        }
    }

    // ================================================================================================================
    // Parsing and validation
    // ================================================================================================================

    static final class Parsed {
        String fileName;
        String sha;
        String format;
        OrganizationRef org;
        PatientRef patient;
        String patientResourceId;
        ObjectNode bundle;                                 // FHIR-style bundle handed to the normalizer
        final Map<String, String> rawBySourceId = new HashMap<>();   // original CSV row / JSON item, by source id
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
    }

    Parsed parse(String fileName, String content) {
        Parsed p = new Parsed();
        p.fileName = fileName == null || fileName.isBlank() ? "upload" : fileName.trim();
        if (content == null || content.isBlank()) {
            p.errors.add("The file is empty.");
            return p;
        }
        if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            p.errors.add("The file is larger than " + (MAX_BYTES / 1000) + " KB.");
            return p;
        }
        p.sha = sha256(content);
        LocalDate anchor = demoData.currentAnchor();
        // Synthetic demo files may use Camellia's relative date tokens (@D+21T08:00) so scenarios stay current.
        String text = RelativeDates.render(content, anchor, false);
        String trimmed = text.stripLeading();
        try {
            if (trimmed.startsWith("{")) {
                JsonNode root = Json.parse(trimmed);
                if ("Bundle".equals(text(root.path("resourceType")))) {
                    p.format = "FHIR_JSON";
                    parseFhir(root, p);
                } else if (text(root.path("format")) != null && text(root.path("format")).startsWith("camellia-import/")) {
                    p.format = "CAMELLIA_JSON";
                    parseCamellia(root, p);
                } else {
                    p.errors.add("Unrecognized JSON: expected a FHIR Bundle (resourceType \"Bundle\") or a Camellia import "
                            + "document (\"format\": \"camellia-import/v1\").");
                }
            } else if (p.fileName.toLowerCase(Locale.ROOT).endsWith(".csv") || trimmed.toLowerCase(Locale.ROOT).startsWith("record_type")) {
                p.format = "CSV";
                parseCsv(trimmed, p);
            } else {
                p.errors.add("Unsupported file. Use FHIR JSON, Camellia JSON (camellia-import/v1) or the documented CSV format.");
            }
        } catch (RuntimeException e) {
            p.errors.add("The file could not be parsed: " + rootMessage(e));
        }
        if (p.bundle != null) validateDates(p, anchor);
        return p;
    }

    private void parseFhir(JsonNode root, Parsed p) {
        JsonNode entries = root.path("entry");
        if (!entries.isArray() || entries.isEmpty()) {
            p.errors.add("The bundle has no entries.");
            return;
        }
        if (entries.size() > MAX_ITEMS) p.errors.add("Too many entries (maximum " + MAX_ITEMS + ").");
        ObjectNode bundle = Json.MAPPER.createObjectNode();
        bundle.put("resourceType", "Bundle");
        ArrayNode out = bundle.putArray("entry");
        JsonNode patientRes = null;
        JsonNode orgRes = null;
        int i = 0;
        for (JsonNode e : entries) {
            i++;
            JsonNode r = e.path("resource");
            String type = text(r.path("resourceType"));
            if ("Patient".equals(type)) {
                if (patientRes != null) p.errors.add("The bundle contains more than one Patient; import one patient per file.");
                patientRes = r;
            } else if ("Organization".equals(type)) {
                orgRes = r;
            } else if (type != null && FHIR_TYPES.contains(type)) {
                if (text(r.path("id")) == null) p.errors.add("Entry " + i + " (" + type + ") has no id.");
                out.addObject().set("resource", r);
            } else {
                p.errors.add("Entry " + i + ": unsupported resource type \"" + type + "\". Supported: Patient, Organization, "
                        + String.join(", ", new TreeSet<>(FHIR_TYPES)) + ".");
            }
        }
        p.bundle = bundle;
        if (patientRes == null) {
            p.errors.add("The bundle has no Patient resource.");
            return;
        }
        String mrn = textAt(patientRes, "identifier.0.value");
        String orgId = orgRes != null ? textAt(orgRes, "identifier.0.value") : text(root.path("meta").path("source"));
        String orgName = orgRes != null ? text(orgRes.path("name")) : null;
        p.patientResourceId = text(patientRes.path("id"));
        p.patient = new PatientRef(mrn, textAt(patientRes, "name.0.given.0"), textAt(patientRes, "name.0.family"),
                parseDate(text(patientRes.path("birthDate")), "Patient.birthDate", p), sex(text(patientRes.path("gender"))));
        p.org = org(orgId, orgName, p);
        requirePatientFields(p);
        for (JsonNode e : out) p.rawBySourceId.put(text(e.path("resource").path("id")), Json.write(e.path("resource")));
        p.bundle = bundle;
    }

    private void parseCamellia(JsonNode root, Parsed p) {
        JsonNode pt = root.path("patient");
        JsonNode o = root.path("organization");
        p.patient = new PatientRef(text(pt.path("mrn")), text(pt.path("firstName")), text(pt.path("lastName")),
                parseDate(text(pt.path("dateOfBirth")), "patient.dateOfBirth", p), sex(text(pt.path("sex"))));
        p.org = org(text(o.path("orgId")), text(o.path("name")), p);
        p.patientResourceId = p.patient.mrn();
        requirePatientFields(p);
        List<Item> items = new ArrayList<>();
        Map<String, String> kinds = Map.of("encounters", "ENCOUNTER", "labs", "LAB", "conditions", "CONDITION",
                "medications", "MEDICATION", "notes", "NOTE", "appointments", "APPOINTMENT");
        kinds.forEach((field, kind) -> {
            for (JsonNode n : root.path(field)) {
                Item it = new Item(kind);
                it.sourceId = text(n.path("sourceId"));
                it.date = firstText(n, "date", "start", "collected", "onset");
                it.endDate = text(n.path("end"));
                it.code = text(n.path("code"));
                it.name = firstText(n, "name", "display", "type", "title");
                it.value = text(n.path("value"));
                it.unit = text(n.path("unit"));
                it.refLow = text(n.path("refLow"));
                it.refHigh = text(n.path("refHigh"));
                it.flag = text(n.path("flag"));
                it.text = firstText(n, "text", "description", "dosage", "reason");
                it.provider = firstText(n, "provider", "author", "performer", "recorder", "prescriber");
                it.department = firstText(n, "department", "specialty", "category");
                it.status = text(n.path("status"));
                it.encounterSourceId = text(n.path("encounterSourceId"));
                it.encounterClass = text(n.path("class"));
                it.raw = Json.write(n);
                items.add(it);
            }
        });
        buildBundle(items, p);
    }

    private void parseCsv(String text, Parsed p) {
        List<List<String>> rows = Csv.parse(text);
        if (rows.isEmpty()) {
            p.errors.add("The CSV has no header row.");
            return;
        }
        List<String> header = rows.getFirst().stream().map(h -> h.trim().toLowerCase(Locale.ROOT)).toList();
        List<String> missing = CSV_COLUMNS.stream().filter(c -> !header.contains(c)).toList();
        if (!missing.isEmpty()) {
            p.errors.add("Missing CSV columns: " + String.join(", ", missing) + ". See the documented CSV format.");
            return;
        }
        if (rows.size() - 1 > MAX_ITEMS) p.errors.add("Too many rows (maximum " + MAX_ITEMS + ").");
        List<Item> items = new ArrayList<>();
        Set<String> patientKeys = new HashSet<>();
        String firstOrg = null, firstOrgName = null;
        for (int i = 1; i < rows.size(); i++) {
            List<String> row = rows.get(i);
            if (row.stream().allMatch(String::isBlank)) continue;
            Map<String, String> v = new HashMap<>();
            for (int c = 0; c < header.size(); c++) v.put(header.get(c), c < row.size() ? blankToNull(row.get(c)) : null);
            patientKeys.add(v.get("patient_mrn") + "|" + v.get("patient_first_name") + "|" + v.get("patient_last_name") + "|" + v.get("patient_birth_date"));
            if (firstOrg == null) {
                firstOrg = v.get("org_id");
                firstOrgName = v.get("org_name");
            } else if (!Objects.equals(firstOrg, v.get("org_id"))) {
                p.errors.add("Row " + (i + 1) + ": every row must have the same org_id (one source organization per file).");
            }
            if (p.patient == null) {
                p.patient = new PatientRef(v.get("patient_mrn"), v.get("patient_first_name"), v.get("patient_last_name"),
                        parseDate(v.get("patient_birth_date"), "row " + (i + 1) + " patient_birth_date", p), sex(v.get("patient_sex")));
            }
            Item it = new Item(v.get("record_type") == null ? "" : v.get("record_type").trim().toUpperCase(Locale.ROOT));
            it.sourceId = v.get("source_id");
            it.date = v.get("date");
            it.endDate = v.get("end_date");
            it.code = v.get("code");
            it.name = v.get("name");
            it.value = v.get("value");
            it.unit = v.get("unit");
            it.refLow = v.get("ref_low");
            it.refHigh = v.get("ref_high");
            it.flag = v.get("flag");
            it.text = v.get("text");
            it.provider = v.get("provider");
            it.department = v.get("department");
            it.status = v.get("status");
            it.encounterSourceId = v.get("encounter_source_id");
            it.encounterClass = v.get("encounter_class");
            it.row = i + 1;
            it.raw = String.join(",", row);
            items.add(it);
        }
        if (patientKeys.size() > 1) p.errors.add("The CSV describes more than one patient; import one patient per file.");
        if (p.patient == null) {
            p.errors.add("The CSV has no data rows.");
            return;
        }
        p.org = org(firstOrg, firstOrgName, p);
        p.patientResourceId = p.patient.mrn();
        requirePatientFields(p);
        buildBundle(items, p);
    }

    /** A flat import item (CSV row or Camellia JSON element) before conversion to a FHIR-style resource. */
    static final class Item {
        final String kind;
        String sourceId, date, endDate, code, name, value, unit, refLow, refHigh, flag, text, provider, department, status,
                encounterSourceId, encounterClass, raw;
        int row;

        Item(String kind) {
            this.kind = kind;
        }

        String where() {
            return row > 0 ? "Row " + row : kind.toLowerCase(Locale.ROOT) + " " + (sourceId == null ? "(no sourceId)" : sourceId);
        }
    }

    private void buildBundle(List<Item> items, Parsed p) {
        if (items.isEmpty()) p.errors.add("The file contains no records.");
        if (items.size() > MAX_ITEMS) p.errors.add("Too many records (maximum " + MAX_ITEMS + ").");
        ObjectNode bundle = Json.MAPPER.createObjectNode();
        bundle.put("resourceType", "Bundle");
        ArrayNode entries = bundle.putArray("entry");
        Set<String> ids = new HashSet<>();
        String subject = "Patient/" + p.patientResourceId;
        for (Item it : items) {
            if (it.sourceId == null) {
                p.errors.add(it.where() + ": source_id is required (it keeps re-imports from creating duplicates).");
                continue;
            }
            if (!ids.add(it.kind + "|" + it.sourceId)) p.errors.add(it.where() + ": duplicate source_id " + it.sourceId + " in this file.");
            if (it.date == null) p.errors.add(it.where() + ": date is required.");
            ObjectNode r = Json.MAPPER.createObjectNode();
            r.put("id", it.sourceId);
            r.putObject("subject").put("reference", subject);
            switch (it.kind) {
                case "ENCOUNTER" -> {
                    r.put("resourceType", "Encounter");
                    r.putObject("class").put("code", it.encounterClass == null ? "AMB" : it.encounterClass.toUpperCase(Locale.ROOT));
                    r.putArray("type").addObject().put("text", req(it.name, it, "name", p));
                    r.putObject("serviceType").put("text", it.department);
                    ObjectNode period = r.putObject("period");
                    period.put("start", it.date);
                    if (it.endDate != null) period.put("end", it.endDate);
                    r.putArray("participant").addObject().putObject("individual").put("display", it.provider);
                    r.putArray("reasonCode").addObject().put("text", it.text == null ? it.name : it.text);
                    r.put("department", it.department);
                }
                case "LAB" -> {
                    r.put("resourceType", "Observation");
                    ObjectNode coding = r.putObject("code").putArray("coding").addObject();
                    coding.put("code", it.code);
                    coding.put("display", req(it.name, it, "name", p));
                    if (it.value != null) {
                        ObjectNode q = r.putObject("valueQuantity");
                        q.put("value", number(it.value, it, "value", p));
                        q.put("unit", it.unit);
                    }
                    if (it.refLow != null || it.refHigh != null) {
                        ObjectNode range = r.putArray("referenceRange").addObject();
                        if (it.refLow != null) range.putObject("low").put("value", number(it.refLow, it, "ref_low", p));
                        if (it.refHigh != null) range.putObject("high").put("value", number(it.refHigh, it, "ref_high", p));
                    }
                    if (it.flag != null) r.putArray("interpretation").addObject().putArray("coding").addObject().put("code", it.flag);
                    r.put("effectiveDateTime", it.date);
                    r.putArray("performer").addObject().put("display", it.provider);
                    if (it.encounterSourceId != null) r.putObject("encounter").put("reference", "Encounter/" + it.encounterSourceId);
                }
                case "CONDITION" -> {
                    r.put("resourceType", "Condition");
                    ObjectNode coding = r.putObject("code").putArray("coding").addObject();
                    coding.put("code", it.code);
                    coding.put("display", req(it.name, it, "name", p));
                    r.putObject("clinicalStatus").putArray("coding").addObject().put("code", it.status == null ? "active" : it.status);
                    r.put("onsetDateTime", it.date);
                    r.putObject("recorder").put("display", it.provider);
                    r.put("specialty", it.department);
                }
                case "MEDICATION" -> {
                    r.put("resourceType", "MedicationStatement");
                    r.putObject("medicationCodeableConcept").put("text", req(it.name, it, "name", p));
                    r.putArray("dosage").addObject().put("text", it.text);
                    r.put("status", it.status == null ? "active" : it.status);
                    r.putObject("effectivePeriod").put("start", it.date);
                    r.putObject("informationSource").put("display", it.provider);
                }
                case "NOTE" -> {
                    r.put("resourceType", "DocumentReference");
                    r.putObject("type").put("text", req(it.name, it, "name", p));
                    r.put("date", it.date);
                    r.putArray("author").addObject().put("display", it.provider);
                    ObjectNode ctx = r.putObject("context");
                    if (it.encounterSourceId != null) ctx.putArray("encounter").addObject().put("reference", "Encounter/" + it.encounterSourceId);
                    ctx.putObject("practiceSetting").put("text", it.department);
                    r.putArray("content").addObject().putObject("attachment").put("contentType", "text/plain")
                            .put("text", req(it.text, it, "text", p));
                }
                case "APPOINTMENT" -> {
                    r.put("resourceType", "Appointment");
                    r.put("status", it.status == null ? "booked" : it.status);
                    r.putObject("appointmentType").put("text", req(it.name, it, "name", p));
                    r.putObject("serviceCategory").put("text", it.department);
                    r.put("department", it.department);
                    r.put("start", it.date);
                    r.putArray("participant").addObject().putObject("actor").put("display", it.provider);
                    r.put("description", it.text);
                }
                default -> {
                    p.errors.add(it.where() + ": unknown record_type \"" + it.kind + "\" (use ENCOUNTER, LAB, CONDITION, MEDICATION, NOTE or APPOINTMENT).");
                    continue;
                }
            }
            entries.addObject().set("resource", r);
            p.rawBySourceId.put(it.sourceId, it.raw);
        }
        p.bundle = bundle;
    }

    /** Runs the normalizer once with a placeholder identity so date and value problems surface in the preview. */
    private void validateDates(Parsed p, LocalDate anchor) {
        for (JsonNode entry : p.bundle.path("entry")) {
            JsonNode r = entry.path("resource");
            for (String path : List.of("period.start", "period.end", "effectiveDateTime", "onsetDateTime", "date", "start",
                    "effectivePeriod.start")) {
                String v = textAt(r, path);
                if (v == null) continue;
                try {
                    RelativeDates.dateTime(v, anchor);
                } catch (RuntimeException e) {
                    p.errors.add(text(r.path("resourceType")) + " " + text(r.path("id")) + ": \"" + v + "\" is not a valid date "
                            + "(use ISO-8601 such as 2026-10-01 or 2026-10-01T08:30).");
                }
            }
        }
    }

    NormalizedBatch normalize(Parsed p, String patientId) {
        Map<String, String> index = new HashMap<>();
        index.put(SourceContext.indexKey(p.org.orgId(), p.patientResourceId), patientId);
        if (p.patient.mrn() != null) index.put(SourceContext.indexKey(p.org.orgId(), p.patient.mrn()), patientId);
        SourceContext ctx = new SourceContext(demoData.currentAnchor(), index, LocalDateTime.now());
        NormalizedBatch b = new FhirLikeNormalizer().normalize(Json.write(p.bundle), p.org.orgId(), ctx);
        if (p.format.equals("FHIR_JSON")) return b;
        // CSV / Camellia JSON: keep the original row or element as the raw source payload and label the format.
        NormalizedBatch relabeled = new NormalizedBatch();
        relabeled.addAll(b);
        relabeled.records.clear();
        for (ClinicalRecord r : b.records) {
            relabeled.records.add(new ClinicalRecord(r.recordId(), r.patientId(), r.orgId(), r.encounterId(), r.recordType(), r.title(),
                    r.recordDate(), r.author(), r.specialty(), r.contentText(), p.format, r.sourceRecordId(),
                    p.rawBySourceId.getOrDefault(r.sourceRecordId(), r.rawContent()), r.categoryTags(), r.ingestedAt()));
        }
        return relabeled;
    }

    // ================================================================================================================
    // helpers
    // ================================================================================================================

    private OrganizationRef org(String orgId, String name, Parsed p) {
        if (orgId == null || !orgId.matches("[A-Z0-9]{2,12}")) {
            p.errors.add("A source organization id is required (2–12 uppercase letters or digits, e.g. LRMC or MBMC).");
            return new OrganizationRef(orgId, name, false);
        }
        Optional<Organization> known = orgs.findAll().stream().filter(o -> o.orgId().equals(orgId)).findFirst();
        if (known.isEmpty() && (name == null || name.isBlank())) {
            p.errors.add("Organization " + orgId + " is new to Camellia, so its name is required.");
        }
        return new OrganizationRef(orgId, known.map(Organization::name).orElse(name), known.isPresent());
    }

    private void requirePatientFields(Parsed p) {
        PatientRef pt = p.patient;
        if (pt.mrn() == null || pt.mrn().isBlank()) p.errors.add("The patient's source identifier (MRN) is required.");
        if (pt.firstName() == null || pt.lastName() == null) p.errors.add("The patient's first and last name are required.");
        if (pt.dateOfBirth() == null) p.errors.add("The patient's date of birth is required.");
    }

    private static LocalDate parseDate(String v, String field, Parsed p) {
        if (v == null || v.isBlank()) return null;
        try {
            return LocalDate.parse(v.trim().length() > 10 ? v.trim().substring(0, 10) : v.trim());
        } catch (RuntimeException e) {
            p.errors.add(field + ": \"" + v + "\" is not a valid date (use YYYY-MM-DD).");
            return null;
        }
    }

    private static String req(String v, Item it, String field, Parsed p) {
        if (v == null || v.isBlank()) p.errors.add(it.where() + ": " + field + " is required for " + it.kind + ".");
        return v;
    }

    private static double number(String v, Item it, String field, Parsed p) {
        try {
            return Double.parseDouble(v.trim());
        } catch (RuntimeException e) {
            p.errors.add(it.where() + ": " + field + " \"" + v + "\" is not a number.");
            return 0;
        }
    }

    private static String sex(String v) {
        if (v == null) return null;
        return switch (v.trim().toLowerCase(Locale.ROOT)) {
            case "f", "female" -> "F";
            case "m", "male" -> "M";
            default -> v.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static String firstText(JsonNode n, String... fields) {
        for (String f : fields) {
            String v = text(n.path(f));
            if (v != null) return v;
        }
        return null;
    }

    static String textAt(JsonNode node, String dotted) {
        JsonNode n = node;
        for (String part : dotted.split("\\.")) n = part.matches("\\d+") ? n.path(Integer.parseInt(part)) : n.path(part);
        return text(n);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String sha256(String content) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) t = t.getCause();
        String m = t.getMessage();
        return m == null ? t.getClass().getSimpleName() : m.length() > 200 ? m.substring(0, 200) : m;
    }
}
