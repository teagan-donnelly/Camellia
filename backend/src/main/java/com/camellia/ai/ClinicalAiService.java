package com.camellia.ai;

import com.camellia.ai.AiDtos.*;
import com.camellia.config.CamelliaProperties;
import com.camellia.ingestion.Json;
import com.camellia.integration.gemini.GeminiException;
import com.camellia.integration.gemini.GeminiGateway;
import com.camellia.integration.gemini.GeminiGateway.Turn;
import com.camellia.model.*;
import com.camellia.repository.*;
import com.camellia.search.FlagSearchCriteria;
import com.camellia.search.RuleBasedQueryInterpreter;
import com.camellia.search.SearchCriteria;
import com.camellia.security.AuthorizationService;
import com.camellia.security.AuthorizationService.AccessScope;
import com.camellia.security.DemoUser;
import com.camellia.security.Permission;
import com.camellia.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * All Gemini-backed features. Pattern for every call:
 * retrieve relevant records from the database → pack a bounded context → call Gemini with a JSON schema →
 * parse → validate every citation against the records that were actually sent → return with AI labeling.
 *
 * <p>Authorization happens BEFORE retrieval: the active demo user's scope decides which patients, flags and alerts may
 * be read, so restricted records are never loaded into a prompt.</p>
 */
@Service
public class ClinicalAiService {

    private static final Logger log = LoggerFactory.getLogger(ClinicalAiService.class);
    public static final String DISCLAIMER = "AI-generated from synthetic records. Verify against the cited sources. Not for clinical use.";

    private final GeminiGateway gemini;
    private final ConversationStore conversations;
    private final PatientRepository patients;
    private final ClinicalDataRepository clinical;
    private final FlagRepository flags;
    private final AlertRepository alerts;
    private final OrganizationRepository organizations;
    private final SupportRepository support;
    private final CamelliaProperties.Gemini cfg;
    private final AuthorizationService auth;
    private final com.camellia.monitoring.MonitoringService monitoring;

    public ClinicalAiService(GeminiGateway gemini, ConversationStore conversations, PatientRepository patients,
                             ClinicalDataRepository clinical, FlagRepository flags, AlertRepository alerts,
                             OrganizationRepository organizations, SupportRepository support, CamelliaProperties props,
                             AuthorizationService auth, com.camellia.monitoring.MonitoringService monitoring) {
        this.auth = auth;
        this.monitoring = monitoring;
        this.gemini = gemini;
        this.conversations = conversations;
        this.patients = patients;
        this.clinical = clinical;
        this.flags = flags;
        this.alerts = alerts;
        this.organizations = organizations;
        this.support = support;
        this.cfg = props.getGemini();
    }

    public boolean geminiConfigured() {
        return gemini.isConfigured();
    }

    // =====================================================================================================
    // Capability 3: interactive assistant (patient / flag / alert / search contexts)
    // =====================================================================================================

    public AiAnswer chat(ChatRequest req, DemoUser user) {
        auth.requirePermission(user, Permission.GEMINI_ASSISTANT);
        if (req.message() == null || req.message().isBlank()) throw ApiException.badRequest("message is required");
        if (req.message().length() > 2000) throw ApiException.badRequest("message must be 2000 characters or fewer");
        Context ctx = resolveContext(req.contextType(), req.contextId(), req.recordIds(), user);
        // Conversations are bound to user + context, so history never crosses users or patients.
        ConversationStore.Conversation conv = conversations.getOrCreate(req.conversationId(), user.id() + "@" + ctx.key());

        EvidencePacker.Packed packed = EvidencePacker.pack(ctx.candidates(), req.message(), ctx.priorityRoles(), orgNames(),
                cfg.getMaxContextChars(), LocalDateTime.now());
        Map<String, ClinicalRecord> sent = byId(packed.included());

        if (!gemini.isConfigured()) {
            return retrievalOnly(packed.included(), conv.id(),
                    "Gemini is not configured, so no AI answer was generated. These are the records Camellia retrieved for your question.");
        }
        String prompt = """
                %s

                SOURCE RECORDS (%d retrieved for this question; %d other records for this context were not included):
                %s
                CLINICIAN QUESTION: %s
                """.formatted(ctx.header(), packed.included().size(), packed.omitted(), packed.text(), req.message().trim());
        List<Turn> turns = new ArrayList<>(conv.recent(cfg.getMaxHistoryTurns()));
        turns.add(Turn.user(prompt));
        GeminiGateway.Reply reply = gemini.generateJson(new GeminiGateway.Request("chat", Prompts.CHAT, turns, AiSchemas.ASSISTANT_ANSWER));
        AiAnswer answer = toAnswer(reply, sent, conv.id());
        // History keeps the question and the validated answer text only (records are re-retrieved each turn).
        conv.append(Turn.user("(Earlier question) " + req.message().trim()), Turn.model(answer.answer()));
        support.logActivity("AI", ctx.contextId(), ctx.patientId(), "AI_CHAT",
                "Gemini answered a question in %s context (%d records provided, %d citations)".formatted(
                        ctx.type(), packed.included().size(), answer.citations().size()), user.name());
        return answer;
    }

    // =====================================================================================================
    // Capability 2: evidence-grounded flag explanation (cached on the flag)
    // =====================================================================================================

    public AiAnswer explainFlag(String flagId, boolean refresh, DemoUser user) {
        ClinicalFlag flag = flags.findById(flagId).orElse(null);
        auth.requireFlag(user, flag);
        String actor = user.name();
        if (!refresh && flag.aiExplanationJson() != null) {
            try {
                return Json.MAPPER.readValue(flag.aiExplanationJson(), AiAnswer.class);
            } catch (JacksonException e) {
                log.warn("Cached explanation for {} unreadable; regenerating", flagId);
            }
        }
        List<EvidenceLink> evidence = flags.evidence(flagId);
        Map<String, String> roles = evidence.stream().collect(Collectors.toMap(EvidenceLink::recordId, EvidenceLink::role, (a, b) -> a, LinkedHashMap::new));
        List<ClinicalRecord> records = clinical.findRecords(roles.keySet());
        if (!gemini.isConfigured()) {
            return retrievalOnly(records, null, "Gemini is not configured. The deterministic detection reason and the evidence records are shown; no AI explanation was generated.");
        }
        EvidencePacker.Packed packed = EvidencePacker.pack(records, flag.title(), roles, orgNames(), cfg.getMaxContextChars(), LocalDateTime.now());
        Patient p = patients.findById(flag.patientId()).orElseThrow();
        String prompt = """
                %s

                FLAG UNDER REVIEW
                  title: %s
                  rule: %s (version %s) — a prototype screening heuristic, not a validated clinical standard
                  deterministic detection reason: %s
                  evidence roles: TRIGGER = met the rule criteria; SUPPORTING = related documentation; UPCOMING_ENCOUNTER = scheduled care

                SOURCE RECORDS (the flag's complete evidence set):
                %s
                Explain why this flag was generated and why the documented findings may matter for the upcoming care or review.
                """.formatted(patientHeader(p), flag.title(), flag.ruleId(), flag.ruleVersion(), flag.detectionReason(), packed.text());
        GeminiGateway.Reply reply = gemini.generateJson(new GeminiGateway.Request("explain-flag", Prompts.EXPLAIN_FLAG,
                List.of(Turn.user(prompt)), AiSchemas.ASSISTANT_ANSWER));
        AiAnswer answer = toAnswer(reply, byId(packed.included()), null);
        flags.saveExplanation(flagId, Json.write(answer), reply.model(), LocalDateTime.now());
        support.logActivity("FLAG", flagId, flag.patientId(), "AI_EXPLANATION",
                "Gemini (%s) generated an evidence-grounded explanation with %d citations".formatted(reply.model(), answer.citations().size()), actor);
        return answer;
    }

    // =====================================================================================================
    // Capability 4: natural-language search interpretation (Gemini → validated criteria; backend runs approved SQL)
    // =====================================================================================================

    /**
     * @param target       RECORDS (clinical record search) or FLAGS (clinical flag / alert search)
     * @param flagCriteria populated when target is FLAGS
     */
    public record Interpretation(String target, SearchCriteria criteria, FlagSearchCriteria flagCriteria, String source,
                                 String model, String explanation, Long latencyMs, String notice) {}

    public Interpretation interpretSearch(String query, String requestedPatientId, DemoUser user) {
        auth.requirePermission(user, Permission.CLINICAL_SEARCH);
        if (query == null || query.isBlank()) throw ApiException.badRequest("query is required");
        if (query.length() > 500) throw ApiException.badRequest("query must be 500 characters or fewer");
        AccessScope scope = auth.scope(user);
        // Only patients the user may see are ever named to the model (or to the rule-based interpreter).
        List<Patient> all = patients.findAll().stream().filter(p -> scope.allowsPatient(p.patientId())).toList();
        String scopedPatientId = requestedPatientId != null && scope.allowsPatient(requestedPatientId) ? requestedPatientId : null;
        List<String> flagCategories = FlagSearchCriteria.categoriesFor(scope.anesthesia());
        if (!gemini.isConfigured()) {
            return ruleBased(query, all, scopedPatientId, scope, "Keyword/concept rules (Gemini not configured).",
                    "Rule-based interpretation — Gemini is not configured. Set GEMINI_API_KEY for AI query interpretation.");
        }
        List<Organization> orgs = organizations.findAll();
        String prompt = """
                Today's date: %s
                Organizations (id: name): %s
                Patients this clinician may access (id: name): %s
                Clinical flag categories available to this clinician (id: name): %s
                %s
                Clinician search request: %s
                """.formatted(LocalDate.now(),
                orgs.stream().map(o -> o.orgId() + ": " + o.name()).collect(Collectors.joining("; ")),
                all.isEmpty() ? "(none)" : all.stream().map(p -> p.patientId() + ": " + p.fullName()).collect(Collectors.joining("; ")),
                flagCategories.stream().map(c -> c + ": " + FlagSearchCriteria.CATEGORY_LABELS.get(c)).collect(Collectors.joining("; ")),
                scopedPatientId == null ? "" : "The request is scoped to the currently open patient: " + scopedPatientId,
                query.trim());
        try {
            GeminiGateway.Reply reply = gemini.generateJson(new GeminiGateway.Request("interpret-search", Prompts.INTERPRET_SEARCH,
                    List.of(Turn.user(prompt)), AiSchemas.searchCriteria(SearchCriteria.CONCEPTS, SearchCriteria.RECORD_TYPES,
                    orgs.stream().map(Organization::orgId).toList(), all.stream().map(Patient::patientId).toList(), flagCategories)));
            Map<String, Object> raw = parseObject(reply.text());
            SearchCriteria c = new SearchCriteria(query, strings(raw.get("keywords")), strings(raw.get("concepts")),
                    strings(raw.get("recordTypes")), strings(raw.get("organizationIds")),
                    blankToNull(raw.get("patientId"), scopedPatientId), blankToNull(raw.get("dateFrom"), null),
                    blankToNull(raw.get("dateTo"), null), bool(raw.get("obstetricOnly")), bool(raw.get("upcomingDeliveryOnly")),
                    bool(raw.get("multipleOrganizationsOnly")), bool(raw.get("openFlagsOnly")),
                    raw.get("minMatchesPerPatient") instanceof Number n ? n.intValue() : 1,
                    raw.get("matchMode") == null ? "ANY" : raw.get("matchMode").toString());
            String target = "FLAGS".equals(String.valueOf(raw.get("searchTarget"))) ? "FLAGS" : "RECORDS";
            FlagSearchCriteria fc = null;
            if (target.equals("FLAGS")) {
                fc = new FlagSearchCriteria(String.join(" ", strings(raw.get("keywords"))), strings(raw.get("flagCategories")),
                        raw.get("flagStatusGroup") == null ? "ANY" : raw.get("flagStatusGroup").toString(), List.of(),
                        strings(raw.get("flagPriorities")), strings(raw.get("flagSpecialties")), strings(raw.get("concepts")),
                        blankToNull(raw.get("patientId"), scopedPatientId));
            }
            return new Interpretation(target, c, fc, "GEMINI", reply.model(), String.valueOf(raw.getOrDefault("explanation", "")),
                    reply.latencyMs(), null);
        } catch (GeminiException e) {
            log.warn("Gemini search interpretation failed ({}); using labeled rule-based fallback", e.getMessage());
            return ruleBased(query, all, scopedPatientId, scope, "Keyword/concept rules.",
                    "Gemini request failed (" + e.getMessage() + "). Showing a rule-based interpretation instead.");
        }
    }

    private Interpretation ruleBased(String query, List<Patient> visible, String scopedPatientId, AccessScope scope,
                                     String explanation, String notice) {
        FlagSearchCriteria fc = RuleBasedQueryInterpreter.interpretFlags(query, scopedPatientId);
        SearchCriteria rc = RuleBasedQueryInterpreter.interpret(query, visible, scopedPatientId);
        return new Interpretation(fc != null ? "FLAGS" : "RECORDS", rc, fc, "RULE_BASED", null, explanation, null, notice);
    }

    public AiAnswer summarizeSearch(String query, List<String> recordIds, DemoUser user) {
        auth.requirePermission(user, Permission.CLINICAL_SEARCH);
        if (recordIds == null || recordIds.isEmpty()) throw ApiException.badRequest("recordIds are required");
        String actor = user.name();
        AccessScope scope = auth.scope(user);
        List<String> ids = recordIds.stream().distinct().limit(40).toList();
        // Records of patients outside the user's scope are dropped before anything is sent to Gemini.
        List<ClinicalRecord> records = clinical.findRecords(ids).stream().filter(r -> scope.allowsPatient(r.patientId())).toList();
        if (records.isEmpty()) throw AuthorizationService.denied(null);
        if (!gemini.isConfigured()) {
            return retrievalOnly(records, null, "Gemini is not configured, so the results were not summarized.");
        }
        EvidencePacker.Packed packed = EvidencePacker.pack(records, query, Map.of(), orgNames(), cfg.getMaxContextChars(), LocalDateTime.now());
        Map<String, String> names = patients.findAll().stream().collect(Collectors.toMap(Patient::patientId, Patient::fullName));
        String patientsInvolved = records.stream().map(ClinicalRecord::patientId).distinct()
                .map(pid -> pid + " = " + names.getOrDefault(pid, pid)).collect(Collectors.joining("; "));
        String prompt = """
                Clinician search query: %s
                Patients represented in these results: %s

                SOURCE RECORDS (search results):
                %s
                Summarize what these results show in relation to the query.
                """.formatted(query, patientsInvolved, packed.text());
        GeminiGateway.Reply reply = gemini.generateJson(new GeminiGateway.Request("search-summary", Prompts.SEARCH_SUMMARY,
                List.of(Turn.user(prompt)), AiSchemas.ASSISTANT_ANSWER));
        AiAnswer answer = toAnswer(reply, byId(packed.included()), null);
        support.logActivity("AI", null, null, "AI_SEARCH_SUMMARY",
                "Gemini summarized %d search results for \"%s\"".formatted(packed.included().size(), abbreviate(query, 80)), actor);
        return answer;
    }

    // =====================================================================================================
    // Capability 1: clinical document interpretation (structured extraction, quote-verified, stored)
    // =====================================================================================================

    public ExtractionResult extract(String patientId, DemoUser user) {
        auth.requirePermission(user, Permission.GEMINI_ASSISTANT);
        auth.requirePatient(user, patientId);
        String actor = user.name();
        Patient p = patients.findById(patientId).orElseThrow(() -> ApiException.notFound("Patient", patientId));
        if (!gemini.isConfigured()) throw GeminiException.notConfigured();
        List<ClinicalRecord> docs = clinical.recordsForPatient(patientId).stream()
                .filter(r -> r.recordType() == RecordType.NOTE || r.recordType() == RecordType.IMAGING)
                .toList();
        if (docs.isEmpty()) {
            return new ExtractionResult(patientId, gemini.model(), LocalDateTime.now(), 0, 0, 0, List.of("No clinical notes or imaging reports for this patient."), List.of());
        }
        EvidencePacker.Packed packed = EvidencePacker.pack(docs, "", Map.of(), orgNames(), cfg.getMaxContextChars(), LocalDateTime.now());
        String prompt = patientHeader(p) + "\n\nSOURCE RECORDS (clinical notes and imaging reports):\n" + packed.text()
                + "\nExtract structured observations.";
        GeminiGateway.Reply reply = gemini.generateJson(new GeminiGateway.Request("extract", Prompts.EXTRACTION,
                List.of(Turn.user(prompt)), AiSchemas.EXTRACTION));
        RawObservations raw = parse(reply.text(), RawObservations.class);
        Map<String, ClinicalRecord> sent = byId(packed.included());
        LocalDateTime now = LocalDateTime.now();
        List<AiObservation> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (RawObservation o : raw.observations() == null ? List.<RawObservation>of() : raw.observations()) {
            String reason = validateObservation(o, sent);
            if (reason != null) {
                rejected.add(reason);
                continue;
            }
            accepted.add(new AiObservation(SupportRepository.newId("OBS"), patientId, o.sourceRecordId().trim(),
                    o.category().toUpperCase(Locale.ROOT), o.statement().trim(), o.evidenceQuote().trim(), reply.model(), now));
        }
        support.replaceObservations(patientId, accepted);
        support.logActivity("PATIENT", patientId, patientId, "AI_EXTRACTION",
                "Gemini (%s) extracted %d verified observations from %d documents (%d rejected by validation)".formatted(
                        reply.model(), accepted.size(), packed.included().size(), rejected.size()), actor);
        return new ExtractionResult(patientId, reply.model(), now, packed.included().size(), accepted.size(), rejected.size(),
                rejected, accepted);
    }

    /** Returns null when valid, otherwise the reason for rejection. */
    static String validateObservation(RawObservation o, Map<String, ClinicalRecord> sent) {
        if (o == null || o.statement() == null || o.statement().isBlank()) return "Empty observation";
        if (o.category() == null || !AiSchemas.OBSERVATION_CATEGORIES.contains(o.category().toUpperCase(Locale.ROOT))) {
            return "Unknown category for: " + o.statement();
        }
        ClinicalRecord r = o.sourceRecordId() == null ? null : sent.get(o.sourceRecordId().trim());
        if (r == null) return "Cited record " + o.sourceRecordId() + " was not among the provided records: " + o.statement();
        if (o.evidenceQuote() == null || o.evidenceQuote().isBlank()) return "Missing evidence quote: " + o.statement();
        String hay = normalize(r.contentText() + " " + r.title());
        if (!hay.contains(normalize(o.evidenceQuote()))) {
            return "Quote not found verbatim in " + r.recordId() + ": \"" + abbreviate(o.evidenceQuote(), 80) + "\"";
        }
        return null;
    }

    static String normalize(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u00a0]+", " ").replaceAll("[“”]", "\"").replaceAll("[‘’]", "'").trim();
    }

    // =====================================================================================================
    // Integration check
    // =====================================================================================================

    public Map<String, Object> ping() {
        GeminiGateway.Reply reply = gemini.generateJson(new GeminiGateway.Request("ping",
                "You are a connectivity check for a hackathon prototype. Respond only with JSON.",
                List.of(Turn.user("Confirm you are reachable for the Camellia clinical coordination prototype.")), AiSchemas.PING));
        Map<String, Object> parsed = parseObject(reply.text());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("live", true);
        out.put("model", reply.model());
        out.put("latencyMs", reply.latencyMs());
        out.put("promptTokens", reply.promptTokens());
        out.put("outputTokens", reply.outputTokens());
        out.put("response", parsed);
        out.put("checkedAt", LocalDateTime.now());
        return out;
    }

    // =====================================================================================================
    // Context resolution & helpers
    // =====================================================================================================

    record Context(String type, String contextId, String patientId, String header, List<ClinicalRecord> candidates,
                   Map<String, String> priorityRoles) {
        String key() {
            return type + ":" + contextId;
        }
    }

    /** Authorizes the context for the user, then retrieves only that context's records. */
    Context resolveContext(String type, String id, List<String> recordIds, DemoUser user) {
        String t = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        switch (t) {
            case "PATIENT" -> {
                auth.requirePatient(user, id);
                Patient p = patients.findById(id).orElseThrow(() -> ApiException.notFound("Patient", id));
                return new Context(t, id, id, patientHeader(p), clinical.recordsForPatient(id), Map.of());
            }
            case "FLAG" -> {
                ClinicalFlag f = flags.findById(id).orElse(null);
                auth.requireFlag(user, f);
                Patient p = patients.findById(f.patientId()).orElseThrow();
                Map<String, String> roles = flags.evidence(id).stream()
                        .collect(Collectors.toMap(EvidenceLink::recordId, EvidenceLink::role, (a, b) -> a));
                String header = patientHeader(p) + "\n\nFLAG IN CONTEXT: " + f.title() + " (rule " + f.ruleId()
                        + ", prototype heuristic). Detection reason: " + f.detectionReason() + "\nStatus: " + f.status()
                        + ". Records marked TRIGGER/SUPPORTING/UPCOMING_ENCOUNTER are this flag's evidence.";
                return new Context(t, id, p.patientId(), header, clinical.recordsForPatient(p.patientId()), roles);
            }
            case "ALERT" -> {
                CoordinationAlert a = alerts.findById(id).orElse(null);
                auth.requireAlert(user, a);
                Patient p = patients.findById(a.patientId()).orElseThrow();
                Map<String, String> roles = alerts.evidence(id).stream()
                        .collect(Collectors.toMap(EvidenceLink::recordId, EvidenceLink::role, (x, y) -> x));
                List<CoordinationNote> notes = alerts.notes(id);
                String noteText = notes.isEmpty() ? "none" : notes.stream()
                        .map(n -> "- " + n.createdAt().toLocalDate() + " " + n.authorName() + " (" + n.noteType() + "): " + abbreviate(n.body(), 300))
                        .collect(Collectors.joining("\n"));
                String header = patientHeader(p) + "\n\nCARE COORDINATION ALERT IN CONTEXT: " + a.title() + " — routed to "
                        + a.assignedTeam() + ". Workflow status: " + a.status() + " (administrative status, not a clinical determination)."
                        + "\nContact with referring provider: " + a.contactStatus() + ". Planning discussion occurred: "
                        + a.planningDiscussionOccurred() + ". Further information needed: " + a.infoNeeded()
                        + "\nHUMAN-ENTERED COORDINATION NOTES (workflow notes, not source records):\n" + noteText;
                return new Context(t, id, p.patientId(), header, clinical.recordsForPatient(p.patientId()), roles);
            }
            case "MONITORING" -> {
                // id = encounter id. Only this hospital stay: its simulated monitoring evidence and the clinical records
                // documented in the same encounter. Nothing from other encounters is sent to Gemini.
                var enc = clinical.findEncounter(id).orElse(null);
                if (enc == null) throw AuthorizationService.denied(null);
                var scoped = monitoring.resolve(user, enc.patientId(), id);
                List<ClinicalRecord> candidates = new ArrayList<>(monitoring.evidence(scoped));
                if (candidates.isEmpty()) throw ApiException.badRequest("This hospital stay has no wearable monitoring data.");
                clinical.recordsForPatient(enc.patientId()).stream().filter(r -> id.equals(r.encounterId())).forEach(candidates::add);
                Map<String, String> roles = new HashMap<>();
                candidates.stream().filter(r -> r.recordId().startsWith("REC-MON-SUM-") || r.recordId().startsWith("REC-MON-MAL-"))
                        .forEach(r -> roles.put(r.recordId(), "TRIGGER"));
                String header = patientHeader(scoped.patient()) + "\n\nHOSPITAL STAY IN CONTEXT: " + enc.encounterType() + " at "
                        + enc.orgId() + ", " + enc.department() + ", admitted " + enc.startTime()
                        + (enc.endTime() == null ? " (ongoing)" : ", discharged " + enc.endTime()) + ". Reason: " + enc.reason() + "."
                        + "\nWEARABLE MONITORING DATA IS SIMULATED. Records titled as readings, walking, movement or position change are"
                        + " device ESTIMATES from a simulated wristband, not validated clinical measurements. Repositioning counts only when"
                        + " documented by nursing; wrist movement is never proof of repositioning. Missing readings during a disconnection are"
                        + " not evidence of inactivity. Do not infer fall risk or emergencies. Times are simulated and shown in hospital time.";
                return new Context(t, id, enc.patientId(), header, candidates, roles);
            }
            case "SEARCH" -> {
                if (recordIds == null || recordIds.isEmpty()) throw ApiException.badRequest("recordIds are required for SEARCH context");
                AccessScope scope = auth.scope(user);
                List<String> ids = recordIds.stream().distinct().sorted().limit(40).toList();
                List<ClinicalRecord> recs = clinical.findRecords(ids).stream().filter(r -> scope.allowsPatient(r.patientId())).toList();
                if (recs.isEmpty()) throw AuthorizationService.denied(null);
                ids = recs.stream().map(ClinicalRecord::recordId).sorted().toList();
                String key = Integer.toHexString(String.join(",", ids).hashCode());
                Map<String, String> names = patients.findAll().stream().collect(Collectors.toMap(Patient::patientId, Patient::fullName));
                String header = "CONTEXT: a set of search results spanning these synthetic patients: " + recs.stream()
                        .map(ClinicalRecord::patientId).distinct().map(pid -> pid + " = " + names.getOrDefault(pid, pid))
                        .collect(Collectors.joining("; "));
                return new Context(t, key, null, header, recs, Map.of());
            }
            default -> throw ApiException.badRequest("contextType must be PATIENT, FLAG, ALERT, SEARCH or MONITORING");
        }
    }

    String patientHeader(Patient p) {
        StringBuilder sb = new StringBuilder("PATIENT (synthetic): ").append(p.fullName()).append(" — ").append(p.patientId())
                .append(" / ").append(p.displayId()).append(". Sex: ").append(p.sex());
        if (p.dateOfBirth() != null) sb.append(". Age: ").append(Period.between(p.dateOfBirth(), LocalDate.now()).getYears());
        if (p.obstetric()) {
            sb.append(". Obstetric patient (").append(p.gestationalNote()).append(")");
            if (p.estimatedDueDate() != null) sb.append(", estimated due date ").append(p.estimatedDueDate())
                    .append(" (an estimate, not a confirmed admission)");
        }
        sb.append(". Today's date: ").append(LocalDate.now()).append('.');
        return sb.toString();
    }

    private AiAnswer toAnswer(GeminiGateway.Reply reply, Map<String, ClinicalRecord> sent, String conversationId) {
        RawAnswer raw = parse(reply.text(), RawAnswer.class);
        CitationValidator.Result v = CitationValidator.validate(raw, sent.keySet());
        if (v.citationsRemoved() > 0 || v.uncitedFactsRemoved() > 0) {
            log.info("Citation validation removed {} unverifiable citations and {} uncited facts", v.citationsRemoved(), v.uncitedFactsRemoved());
        }
        Map<String, String> orgs = orgNames();
        List<Citation> citations = v.citedIds().stream().map(sent::get).filter(Objects::nonNull).map(r -> citation(r, orgs)).toList();
        List<Citation> retrieved = sent.values().stream().map(r -> citation(r, orgs)).toList();
        String notice = v.citationsRemoved() > 0
                ? v.citationsRemoved() + " reference(s) that did not match a retrieved record were removed." : null;
        return new AiAnswer("GEMINI", true, reply.model(), LocalDateTime.now(), v.answer(), v.documentedFacts(), v.interpretation(),
                v.missingInformation(), v.followUps(), citations, retrieved,
                new Validation(v.citationsRemoved(), v.uncitedFactsRemoved(), sent.size()), reply.latencyMs(), conversationId, notice);
    }

    private AiAnswer retrievalOnly(List<ClinicalRecord> records, String conversationId, String message) {
        Map<String, String> orgs = orgNames();
        List<Citation> retrieved = records.stream().map(r -> citation(r, orgs)).toList();
        return new AiAnswer("RETRIEVAL_ONLY", false, null, LocalDateTime.now(), message, List.of(), List.of(),
                List.of("Gemini is not configured: set GEMINI_API_KEY in backend/.env and restart the backend."), List.of(),
                List.of(), retrieved, new Validation(0, 0, records.size()), null, conversationId, message);
    }

    static Citation citation(ClinicalRecord r, Map<String, String> orgs) {
        return new Citation(r.recordId(), r.title(), r.recordType().name(), r.recordDate(), r.orgId(),
                orgs.getOrDefault(r.orgId(), r.orgId()), r.author(), r.sourceFormat());
    }

    private Map<String, String> orgNames() {
        return organizations.findAll().stream().collect(Collectors.toMap(Organization::orgId, Organization::name));
    }

    private static Map<String, ClinicalRecord> byId(List<ClinicalRecord> records) {
        return records.stream().collect(Collectors.toMap(ClinicalRecord::recordId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    /** Parses model JSON, tolerating accidental Markdown code fences. */
    static <T> T parse(String text, Class<T> type) {
        try {
            return Json.MAPPER.readValue(stripFences(text), type);
        } catch (JacksonException e) {
            throw new GeminiException(HttpStatus.BAD_GATEWAY, "GEMINI_UNPARSEABLE",
                    "Gemini returned a response that did not match the expected structure. Please retry.", e);
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        return parse(text, Map.class);
    }

    static String stripFences(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            t = t.replaceFirst("^```[a-zA-Z]*\\s*", "");
            int end = t.lastIndexOf("```");
            if (end >= 0) t = t.substring(0, end);
        }
        return t.trim();
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) return List.of();
        return l.stream().filter(Objects::nonNull).map(Object::toString).toList();
    }

    private static String blankToNull(Object o, String fallback) {
        if (o == null || o.toString().isBlank()) return fallback;
        return o.toString();
    }

    private static Boolean bool(Object o) {
        return o instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(o));
    }

    static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
