package com.camellia.briefing;

import com.camellia.config.CamelliaProperties;
import com.camellia.ingestion.Json;
import com.camellia.integration.elevenlabs.ElevenLabsClient;
import com.camellia.integration.gemini.GeminiGateway;
import com.camellia.model.*;
import com.camellia.monitoring.MonitoringService;
import com.camellia.repository.*;
import com.camellia.repository.BriefingRepository.Briefing;
import com.camellia.repository.BriefingRepository.Reference;
import com.camellia.repository.WorkforceRepository.Assignment;
import com.camellia.repository.WorkforceRepository.Shift;
import com.camellia.security.AuthorizationService;
import com.camellia.security.AuthorizationService.AccessScope;
import com.camellia.security.DemoUser;
import com.camellia.security.Permission;
import com.camellia.web.ApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Camellia Briefing: a short spoken shift handoff.
 * <pre>
 *   Snowflake (shift + assignments + authorized records) → Java retrieval → Gemini script (JSON, cited)
 *     → validation (assigned + authorized patients only, citations exist) → ElevenLabs audio → duration check (≤ 8 min)
 * </pre>
 * Assignments come only from PROVIDER_SHIFTS / SHIFT_PATIENT_ASSIGNMENTS; patients outside the user's access scope are
 * excluded before anything is sent to Gemini. Briefings are visible only to the user who generated them.
 */
@Service
public class BriefingService {

    private static final Logger log = LoggerFactory.getLogger(BriefingService.class);
    public static final String NOTICE = "Prototype briefing generated from synthetic records. Not for clinical use. Do not listen to "
            + "identifiable patient information in public or while driving; real deployments would need privacy safeguards, vendor "
            + "agreements and organizational approval.";
    static final Set<String> MODES = Set.of("FULL", "PRIORITY");
    static final Set<String> IN_PROGRESS = Set.of("QUEUED", "SCRIPTING", "SYNTHESIZING");
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEE MMM d, HH:mm", Locale.US);

    private final WorkforceRepository workforce;
    private final BriefingRepository briefings;
    private final PatientRepository patients;
    private final ClinicalDataRepository clinical;
    private final FlagRepository flags;
    private final AlertRepository alerts;
    private final OrganizationRepository orgs;
    private final MonitoringService monitoring;
    private final AuthorizationService auth;
    private final GeminiGateway gemini;
    private final ElevenLabsClient tts;
    private final CamelliaProperties props;
    private final ExecutorService executor = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "briefing-worker");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public BriefingService(WorkforceRepository workforce, BriefingRepository briefings, PatientRepository patients,
                           ClinicalDataRepository clinical, FlagRepository flags, AlertRepository alerts,
                           OrganizationRepository orgs, MonitoringService monitoring, AuthorizationService auth,
                           GeminiGateway gemini, ElevenLabsClient tts, CamelliaProperties props) {
        this.workforce = workforce;
        this.briefings = briefings;
        this.patients = patients;
        this.clinical = clinical;
        this.flags = flags;
        this.alerts = alerts;
        this.orgs = orgs;
        this.monitoring = monitoring;
        this.auth = auth;
        this.gemini = gemini;
        this.tts = tts;
        this.props = props;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    // =================================================================================================================
    // Shifts
    // =================================================================================================================

    public record ShiftView(String shiftId, LocalDate shiftDate, LocalDateTime startAt, LocalDateTime endAt, String label,
                            String department, String location, String orgName, int assignedPatients, int authorizedPatients,
                            int excludedPatients, int openItems, List<String> patientNames, Map<String, BriefingSummary> latest) {}

    public record BriefingSummary(String briefingId, String mode, String status, Double durationSeconds, LocalDateTime createdAt) {}

    public List<ShiftView> shifts(DemoUser user) {
        auth.requirePermission(user, Permission.CLINICAL_BRIEFING);
        Map<String, String> orgNames = orgNames();
        List<Briefing> mine = briefings.forUser(user.id(), 100);
        List<ShiftView> out = new ArrayList<>();
        for (Shift s : workforce.shiftsForUser(user.id())) {
            Scope sc = scopeFor(user, s);
            Map<String, BriefingSummary> latest = new LinkedHashMap<>();
            for (Briefing b : mine) {
                if (b.shiftId().equals(s.shiftId()) && !latest.containsKey(b.mode())) {
                    latest.put(b.mode(), new BriefingSummary(b.briefingId(), b.mode(), b.status(), b.durationSeconds(), b.createdAt()));
                }
            }
            out.add(new ShiftView(s.shiftId(), s.shiftDate(), s.startAt(), s.endAt(), s.label(), s.department(), s.location(),
                    orgNames.get(s.orgId()), sc.assigned().size(), sc.authorized().size(), sc.excluded(),
                    context(user, s, sc).openItems(), sc.authorized().stream().map(Patient::fullName).toList(), latest));
        }
        return out;
    }

    /** Assigned patients split into authorized (kept) and excluded (outside the user's scope — never sent anywhere). */
    record Scope(List<Assignment> assigned, List<Patient> authorized, int excluded, Map<String, Assignment> byPatient) {}

    Scope scopeFor(DemoUser user, Shift s) {
        AccessScope scope = auth.scope(user);
        List<Assignment> assigned = workforce.assignments(s.shiftId());
        Map<String, Patient> all = patients.findAll().stream().collect(Collectors.toMap(Patient::patientId, p -> p));
        List<Patient> authorized = new ArrayList<>();
        Map<String, Assignment> byPatient = new LinkedHashMap<>();
        int excluded = 0;
        for (Assignment a : assigned) {
            Patient p = all.get(a.patientId());
            if (p != null && scope.allowsPatient(a.patientId())) {
                authorized.add(p);
                byPatient.put(p.patientId(), a);
            } else {
                excluded++;
            }
        }
        return new Scope(assigned, authorized, excluded, byPatient);
    }

    // =================================================================================================================
    // Retrieval (authorized data only)
    // =================================================================================================================

    /** One citable fact handed to Gemini. */
    public record SourceItem(String type, String id, String patientId, String label, String text, boolean priority) {}

    record Context(List<SourceItem> items, String prompt, int openItems, String fingerprint) {}

    Context context(DemoUser user, Shift shift, Scope sc) {
        AccessScope scope = auth.scope(user);
        LocalDateTime from = shift.startAt();
        LocalDateTime horizon = shift.startAt().plusDays(21);
        Map<String, String> orgNames = orgNames();
        List<SourceItem> items = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int open = 0;
        Set<String> patientIds = sc.byPatient().keySet();
        List<com.camellia.repository.MonitoringRepository.Alert> monAlerts = user.has(Permission.VIEW_MONITORING)
                ? monitoring.openAlertsFor(patientIds) : List.of();

        int n = 0;
        for (Patient p : sc.authorized()) {
            n++;
            String pid = p.patientId();
            Assignment a = sc.byPatient().get(pid);
            text.append("\nPATIENT ").append(n).append(" — patientId ").append(pid).append(": ").append(p.fullName())
                    .append(" (").append(p.displayId()).append("), ").append(age(p)).append(", sex ").append(p.sex())
                    .append(p.obstetric() ? ", obstetric patient" + (p.estimatedDueDate() == null ? "" : " (estimated due date " + p.estimatedDueDate() + ")") : "")
                    .append(". Assignment on this shift: ").append(a.role()).append(".\n");
            List<SourceItem> mine = new ArrayList<>();

            // Current hospital stay and upcoming appointments.
            for (Encounter e : clinical.encountersForPatient(pid)) {
                if (e.inpatient() && e.ongoingAt(from)) {
                    mine.add(new SourceItem("RECORD", e.recordId(), pid, "Current admission",
                            "Currently admitted: %s at %s, %s, since %s. Reason: %s.".formatted(e.encounterType(),
                                    orgNames.get(e.orgId()), e.department(), e.startTime().format(WHEN), e.reason()), false));
                }
            }
            for (Appointment ap : clinical.appointmentsForPatient(pid)) {
                if (ap.scheduledStart() != null && !ap.scheduledStart().isBefore(from) && ap.scheduledStart().isBefore(horizon)
                        && (ap.status() == null || !ap.status().equalsIgnoreCase("cancelled"))) {
                    long days = Duration.between(from.toLocalDate().atStartOfDay(), ap.scheduledStart().toLocalDate().atStartOfDay()).toDays();
                    mine.add(new SourceItem("APPOINTMENT", ap.recordId(), pid, ap.appointmentType(),
                            "Upcoming: %s on %s (%s) at %s, %s.".formatted(ap.appointmentType(), ap.scheduledStart().format(WHEN),
                                    days == 0 ? "today" : days == 1 ? "tomorrow" : "in " + days + " days", orgNames.get(ap.orgId()),
                                    ap.department()), days <= 3));
                }
            }
            // Open screening flags (only the categories the user may see).
            for (ClinicalFlag f : flags.find(null, null, pid)) {
                if (!scope.allowsFlag(f) || !(f.status().equals("UNREVIEWED") || f.status().equals("IN_REVIEW"))) continue;
                open++;
                mine.add(new SourceItem("FLAG", f.flagId(), pid, f.title(),
                        "Open screening flag (%s review priority, status %s; prototype heuristic, not a diagnosis): %s. Detection basis: %s"
                                .formatted(f.reviewPriority(), f.status(), f.title(), abbreviate(f.detectionReason(), 600)),
                        "EXPEDITED".equals(f.reviewPriority())));
            }
            // Anesthesia OB coordination alerts (only for roles with that workflow).
            if (scope.anesthesia()) {
                for (CoordinationAlert al : alerts.find(null, null, pid)) {
                    if (!scope.allowsAlert(al) || al.status().equals("RESOLVED") || al.status().equals("DISMISSED")) continue;
                    open++;
                    mine.add(new SourceItem("ALERT", al.alertId(), pid, al.title(),
                            "Open care-coordination alert (%s): %s. Workflow status %s; acknowledged %s; contact with referring provider %s; review due %s."
                                    .formatted(al.alertType(), al.title(), al.status(), al.acknowledgedBy() == null ? "not yet" : "by " + al.acknowledgedBy(),
                                            al.contactStatus(), al.reviewDueDate()), true));
                }
            }
            // Open wearable-monitoring reviews (simulated device data).
            for (var ma : monAlerts) {
                if (!ma.patientId().equals(pid)) continue;
                open++;
                mine.add(new SourceItem("MONITORING_ALERT", ma.alertId(), pid, ma.alertType(),
                        "Open nursing monitoring review from the SIMULATED wristband (%s, status %s): %s Basis: %s."
                                .formatted(ma.alertType(), ma.status(), ma.message(), ma.basis()), true));
            }
            // Recent out-of-range results (last 90 days), most recent first, capped.
            clinical.labsForPatient(pid).stream()
                    .filter(l -> l.collectedAt() != null && l.collectedAt().isAfter(from.minusDays(90)) && l.isOutOfReference())
                    .limit(4)
                    .forEach(l -> mine.add(new SourceItem("RECORD", l.recordId(), pid, l.testName(),
                            "Result outside the laboratory reference range: %s %s %s (reference %s) on %s, %s."
                                    .formatted(l.testName(), l.valueNum() == null ? l.valueText() : fmt(l.valueNum()), l.unit() == null ? "" : l.unit(),
                                            l.refText(), l.collectedAt().toLocalDate(), orgNames.get(l.orgId())), false)));
            for (SourceItem it : mine) {
                text.append("  [").append(it.type()).append(" ").append(it.id()).append("] ").append(it.text()).append('\n');
            }
            if (mine.isEmpty()) text.append("  (No current admission, upcoming appointment, open item or recent out-of-range result.)\n");
            items.addAll(mine);
        }
        String fingerprint = sha256(shift.shiftId() + "\n" + text);
        return new Context(items, text.toString(), open, fingerprint);
    }

    // =================================================================================================================
    // Generation
    // =================================================================================================================

    public record GenerateRequest(String shiftId, String mode, Boolean regenerate) {}

    public BriefingView generate(DemoUser user, GenerateRequest req) {
        auth.requirePermission(user, Permission.CLINICAL_BRIEFING);
        if (req == null || req.shiftId() == null) throw ApiException.badRequest("shiftId is required");
        String mode = req.mode() == null ? "FULL" : req.mode().trim().toUpperCase(Locale.ROOT);
        if (!MODES.contains(mode)) throw ApiException.badRequest("mode must be FULL or PRIORITY");
        Shift shift = workforce.findShift(req.shiftId()).orElse(null);
        // Only one's own shifts. A missing or foreign shift gets the same generic 403.
        if (shift == null || !shift.userId().equals(user.id())) throw AuthorizationService.denied(null);
        Scope sc = scopeFor(user, shift);
        if (sc.authorized().isEmpty()) throw ApiException.badRequest("No patients on this shift are within your access scope.");
        Context ctx = context(user, shift, sc);
        String fingerprint = sha256(mode + "|" + ctx.fingerprint() + "|" + tts.isConfigured());
        if (!Boolean.TRUE.equals(req.regenerate())) {
            Optional<Briefing> reuse = briefings.reusable(user.id(), shift.shiftId(), mode, fingerprint);
            if (reuse.isPresent()) return view(user, reuse.get(), true);
        }
        Briefing b = new Briefing("BRF-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT),
                shift.shiftId(), user.id(), mode, "QUEUED", "Waiting to start", fingerprint, sc.authorized().size(), ctx.openItems(),
                sc.excluded(), null, null, null, null, null, null, null, null, null, null, null, null, 0, LocalDateTime.now(), null);
        briefings.insert(b);
        running.add(b.briefingId());
        executor.submit(() -> {
            try {
                run(b, user, shift, sc, ctx);
            } catch (RuntimeException e) {
                log.warn("Briefing {} failed: {}", b.briefingId(), e.getMessage());
                briefings.find(b.briefingId()).ifPresent(cur -> briefings.update(withEnd(cur.withStatus("FAILED", message(e)))));
            } finally {
                running.remove(b.briefingId());
            }
        });
        return view(user, b, false);
    }

    /** The pipeline: script (Gemini) → validation → audio (ElevenLabs) → duration check, shortening if needed. */
    void run(Briefing initial, DemoUser user, Shift shift, Scope sc, Context ctx) {
        if (!gemini.isConfigured()) {
            briefings.update(withEnd(initial.withStatus("FAILED", "Gemini is not configured (GEMINI_API_KEY), so no briefing script can be "
                    + "generated. Camellia never substitutes a canned script.")));
            return;
        }
        int maxSeconds = props.getBriefing().getMaxAudioSeconds();
        int wpm = props.getBriefing().getWordsPerMinute();
        boolean full = initial.mode().equals("FULL");
        // Word budget: priority briefings are short; full briefings scale with content but stay well under the cap.
        int budget = full ? Math.min((int) (maxSeconds * 0.85 * wpm / 60), 140 + 150 * sc.authorized().size())
                : Math.min(420, 90 + 70 * Math.max(1, ctx.openItems()));
        Briefing b = initial;
        String feedback = null;
        Script script = null;
        int attempts = props.getBriefing().getMaxScriptAttempts();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            b = save(b.withStatus("SCRIPTING", "Gemini is writing the script (attempt " + attempt + ", up to " + budget + " words)"), attempt, null);
            GeminiGateway.Reply reply = gemini.generateJson(new GeminiGateway.Request("briefing", SYSTEM_PROMPT,
                    List.of(GeminiGateway.Turn.user(userPrompt(user, shift, sc, ctx, full, budget, feedback))), SCHEMA));
            script = parseAndValidate(reply.text(), sc, ctx);
            b = new Briefing(b.briefingId(), b.shiftId(), b.userId(), b.mode(), b.status(), b.statusDetail(), b.sourceFingerprint(),
                    b.patientCount(), b.openAlertCount(), b.excludedCount(), b.scriptJson(), b.transcript(), reply.model(), b.ttsProvider(),
                    b.ttsModel(), b.voiceId(), b.audioSha256(), b.audioPath(), b.audioBytes(), b.audioContentType(), b.durationSeconds(),
                    b.ttsCharacters(), attempt, b.createdAt(), b.completedAt());
            if (!script.problems().isEmpty()) {
                feedback = "Your previous script was rejected: " + String.join("; ", script.problems()) + ". Fix these problems.";
                if (attempt == attempts) {
                    briefings.update(withEnd(b.withStatus("FAILED", "The script failed validation: " + String.join("; ", script.problems()))));
                    return;
                }
                continue;
            }
            double estimate = script.words() * 60.0 / wpm;
            if (estimate > maxSeconds * 0.95) {
                budget = (int) (budget * 0.75);
                feedback = "Your previous script had " + script.words() + " words, too long for the time limit. Keep it under " + budget + " words.";
                if (attempt < attempts) continue;
            }
            b = saveScript(b, script, estimate);
            if (!tts.isConfigured()) {
                briefings.update(withEnd(b.withStatus("SCRIPT_ONLY", "Script ready. ElevenLabs is not configured (ELEVENLABS_API_KEY), so no "
                        + "audio was generated — the transcript and citations are available.")));
                return;
            }
            b = save(b.withStatus("SYNTHESIZING", "ElevenLabs is generating the audio (" + script.transcript().length() + " characters)"), attempt, null);
            Cached cached;
            try {
                cached = synthesizeCached(script.transcript());
            } catch (ApiException e) {
                briefings.update(withEnd(b.withStatus("SCRIPT_ONLY", "Script ready, but ElevenLabs audio failed: " + e.getMessage())));
                return;
            }
            ElevenLabsClient.Speech speech = cached.speech();
            if (speech.durationSeconds() > maxSeconds) {
                budget = (int) (script.words() * (maxSeconds * 0.85 / speech.durationSeconds()));
                feedback = "The narration ran " + Math.round(speech.durationSeconds()) + " seconds, over the " + maxSeconds
                        + "-second limit. Rewrite it in at most " + budget + " words; drop the least important details first.";
                if (attempt < attempts) continue;
                briefings.update(withEnd(b.withStatus("FAILED", "The audio stayed over the " + maxSeconds + "-second limit after "
                        + attempts + " attempts; nothing over the limit is published.")));
                return;
            }
            Briefing withAudio = storeAudio(b, speech, script, cached.file());
            briefings.update(withEnd(withAudio.withStatus("READY", "Audio ready (" + Math.round(speech.durationSeconds()) + " s)")));
            return;
        }
        if (script != null) briefings.update(withEnd(b.withStatus("FAILED", "Could not produce a script within the limits.")));
    }

    private Briefing save(Briefing b, int attempt, String detail) {
        Briefing n = new Briefing(b.briefingId(), b.shiftId(), b.userId(), b.mode(), b.status(), detail != null ? detail : b.statusDetail(),
                b.sourceFingerprint(), b.patientCount(), b.openAlertCount(), b.excludedCount(), b.scriptJson(), b.transcript(),
                b.geminiModel(), b.ttsProvider(), b.ttsModel(), b.voiceId(), b.audioSha256(), b.audioPath(), b.audioBytes(),
                b.audioContentType(), b.durationSeconds(), b.ttsCharacters(), attempt, b.createdAt(), b.completedAt());
        briefings.update(n);
        return n;
    }

    private Briefing saveScript(Briefing b, Script s, double estimate) {
        ObjectNode json = s.json();
        json.put("estimatedSeconds", Math.round(estimate));
        Briefing n = new Briefing(b.briefingId(), b.shiftId(), b.userId(), b.mode(), b.status(), b.statusDetail(), b.sourceFingerprint(),
                b.patientCount(), b.openAlertCount(), b.excludedCount(), Json.write(json), s.transcript(), b.geminiModel(),
                b.ttsProvider(), b.ttsModel(), b.voiceId(), b.audioSha256(), b.audioPath(), b.audioBytes(), b.audioContentType(),
                null, null, b.scriptAttempts(), b.createdAt(), b.completedAt());
        briefings.update(n);
        List<Reference> refs = new ArrayList<>();
        int seg = 0;
        for (JsonNode segment : json.path("segments")) {
            for (JsonNode src : segment.path("sources")) {
                refs.add(new Reference(b.briefingId(), seg, Json.text(src.path("patientId")), Json.text(src.path("type")), Json.text(src.path("id"))));
            }
            seg++;
        }
        briefings.replaceReferences(b.briefingId(), refs);
        return n;
    }

    private Briefing storeAudio(Briefing b, ElevenLabsClient.Speech sp, Script s, Path file) {
        ObjectNode json = s.json();
        // Segment start times from ElevenLabs' character alignment (seek-to-segment in the player).
        ArrayNode segs = (ArrayNode) json.path("segments");
        for (JsonNode seg : segs) {
            int offset = seg.path("charOffset").asInt(0);
            double start = offset <= 0 || sp.characterEndSeconds().isEmpty() ? 0
                    : sp.characterEndSeconds().get(Math.min(offset - 1, sp.characterEndSeconds().size() - 1));
            ((ObjectNode) seg).put("startSeconds", Math.round(start * 10) / 10.0);
        }
        json.put("durationSeconds", Math.round(sp.durationSeconds() * 10) / 10.0);
        return new Briefing(b.briefingId(), b.shiftId(), b.userId(), b.mode(), b.status(), b.statusDetail(), b.sourceFingerprint(),
                b.patientCount(), b.openAlertCount(), b.excludedCount(), Json.write(json), b.transcript(), b.geminiModel(), "ELEVENLABS",
                sp.modelId(), sp.voiceId(), sha256(sp.audio()), file.toString(), (long) sp.audio().length, sp.contentType(),
                Math.round(sp.durationSeconds() * 100) / 100.0, sp.characters(), b.scriptAttempts(), b.createdAt(), b.completedAt());
    }

    /** Identical text + voice + model + settings is never synthesized twice (the API is only called on a cache miss). */
    record Cached(ElevenLabsClient.Speech speech, Path file) {}

    Cached synthesizeCached(String transcript) {
        String key = sha256(tts.modelId() + "|" + tts.voiceId() + "|" + props.getElevenlabs().getOutputFormat() + "|"
                + props.getElevenlabs().getStability() + "|" + props.getElevenlabs().getSimilarityBoost() + "|"
                + props.getElevenlabs().getStyle() + "|" + props.getElevenlabs().getSpeed() + "|" + transcript);
        Path dir = audioDir();
        Path audio = dir.resolve(key + ".audio");
        Path meta = dir.resolve(key + ".json");
        try {
            if (Files.exists(audio) && Files.exists(meta)) {
                JsonNode m = Json.parse(Files.readString(meta));
                List<Double> ends = new ArrayList<>();
                m.path("characterEndSeconds").forEach(n -> ends.add(n.asDouble()));
                log.info("Briefing audio cache hit {}", key.substring(0, 12));
                return new Cached(new ElevenLabsClient.Speech(Files.readAllBytes(audio), Json.text(m.path("contentType")),
                        m.path("durationSeconds").asDouble(), Json.text(m.path("voiceId")), Json.text(m.path("modelId")),
                        m.path("characters").asInt(), ends), audio);
            }
            ElevenLabsClient.Speech sp = tts.synthesize(transcript);
            Files.createDirectories(dir);
            Files.write(audio, sp.audio());
            ObjectNode m = Json.MAPPER.createObjectNode();
            m.put("contentType", sp.contentType());
            m.put("durationSeconds", sp.durationSeconds());
            m.put("voiceId", sp.voiceId());
            m.put("modelId", sp.modelId());
            m.put("characters", sp.characters());
            ArrayNode ends = m.putArray("characterEndSeconds");
            sp.characterEndSeconds().forEach(ends::add);
            Files.writeString(meta, Json.write(m));
            return new Cached(sp, audio);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store briefing audio", e);
        }
    }

    Path audioDir() {
        String dir = props.getBriefing().getAudioDir();
        return Path.of(dir == null || dir.isBlank() ? "./data/briefings" : dir.trim()).toAbsolutePath().normalize();
    }

    // =================================================================================================================
    // Gemini script: prompt, schema and validation
    // =================================================================================================================

    static final String SYSTEM_PROMPT = """
            You write Camellia Briefing: a short, professional SPOKEN shift handoff for a clinician, in the calm, clear style of a
            daily news briefing. Camellia is a hackathon PROTOTYPE using only SYNTHETIC patients.

            Rules (mandatory):
            1. Use ONLY the facts in PATIENT CONTEXT. Never invent diagnoses, appointments, results, plans or recommendations.
            2. Mention only the patients listed in PATIENT CONTEXT, using the name exactly as given. Patient assignments come from
               the context; never add or remove patients from the shift.
            3. Every patient segment lists the sources it relies on (type + id exactly as given in square brackets). Do not read ids,
               record numbers or bracketed text aloud in the narration.
            4. Keep documented findings separate from considerations. Considerations are cautious and administrative, e.g. "may warrant
               review before the delivery" or "consider confirming the plan with the team". No treatment, medication or dosing advice.
               Never say a patient is safe, cleared or low risk.
            5. Screening flags are prototype heuristics, not diagnoses. Wristband monitoring data is simulated and device-estimated;
               describe it as such and never infer falls or emergencies from it.
            6. Write for the ear: short sentences, natural transitions, dates and times spoken plainly ("Thursday at seven a.m.").
               Write each number either fully in digits or fully in words ("365 days" or "a year"), never mixed.
            7. The opening says this is a Camellia demo briefing using synthetic patient information, names the shift and the number of
               assigned patients. The closing summarizes in one or two sentences what to review first.
            8. Use only as many words as the content needs, within the word limit given.
            Respond only with JSON matching the schema.
            """;

    static final Map<String, Object> SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "title", Map.of("type", "string", "description", "Short title, e.g. 'Day shift briefing — Thursday'"),
                    "opening", Map.of("type", "string", "description", "Spoken opening, 1-3 sentences."),
                    "segments", Map.of("type", "array", "description", "One segment per patient (or one combined segment for patients with nothing notable).",
                            "items", Map.of("type", "object",
                                    "properties", Map.of(
                                            "heading", Map.of("type", "string", "description", "Patient name and a 2-6 word topic."),
                                            "patientIds", Map.of("type", "array", "items", Map.of("type", "string"),
                                                    "description", "patientId values (e.g. CAM-P-1001) of the patients discussed."),
                                            "narration", Map.of("type", "string", "description", "Spoken text for this segment. No ids or brackets."),
                                            "sources", Map.of("type", "array", "description", "Sources used, exactly as given in the context.",
                                                    "items", Map.of("type", "object", "properties", Map.of(
                                                            "type", Map.of("type", "string", "enum", List.of("RECORD", "FLAG", "ALERT", "APPOINTMENT", "MONITORING_ALERT")),
                                                            "id", Map.of("type", "string")), "required", List.of("type", "id")))),
                                    "required", List.of("heading", "patientIds", "narration", "sources"))),
                    "closing", Map.of("type", "string", "description", "Spoken closing, 1-2 sentences.")),
            "required", List.of("title", "opening", "segments", "closing"));

    private String userPrompt(DemoUser user, Shift shift, Scope sc, Context ctx, boolean full, int budget, String feedback) {
        return """
                BRIEFING TYPE: %s
                CLINICIAN: %s, %s
                SHIFT: %s, %s to %s, %s
                ASSIGNED PATIENTS YOU MAY MENTION: %d%s
                WORD LIMIT: at most %d words in total (opening + segments + closing). Use fewer when there is less to say.
                %s

                PATIENT CONTEXT (authorized records only; ids in square brackets are for the sources array, never for narration):
                %s
                %s""".formatted(
                full ? "FULL SHIFT BRIEFING — opening, number of assigned patients, relevant upcoming encounters, important documented findings, open alerts, outstanding coordination tasks, brief closing."
                        : "PRIORITY ALERTS ONLY — cover only high-priority open items (expedited flags, open coordination alerts, open monitoring reviews, encounters within 3 days); mention the remaining patients in one sentence at most.",
                user.name(), user.role().label(), shift.label(), shift.startAt().format(WHEN), shift.endAt().format(WHEN), shift.location(),
                sc.authorized().size(), sc.excluded() > 0 ? " (some scheduled assignments are outside this clinician's access scope; do not mention that any exist beyond this count)" : "",
                budget, full ? "" : "Focus strictly on priority items.", ctx.prompt(), feedback == null ? "" : "\nREVISION NOTE: " + feedback);
    }

    record Script(ObjectNode json, String transcript, int words, List<String> problems) {}

    /** Validates the model output: only assigned + authorized patients, citations that exist, no unassigned names. */
    Script parseAndValidate(String raw, Scope sc, Context ctx) {
        List<String> problems = new ArrayList<>();
        JsonNode in;
        try {
            in = Json.parse(raw);
        } catch (RuntimeException e) {
            return new Script(Json.MAPPER.createObjectNode(), "", 0, List.of("the response was not valid JSON"));
        }
        Set<String> allowed = sc.byPatient().keySet();
        Map<String, SourceItem> sources = new HashMap<>();
        ctx.items().forEach(it -> sources.put(it.type() + "|" + it.id(), it));
        // Names of every patient who must NOT appear (not assigned, or assigned but outside scope).
        List<String> forbidden = patients.findAll().stream().filter(p -> !allowed.contains(p.patientId()))
                .flatMap(p -> distinctiveNames(p).stream()).toList();
        ObjectNode out = Json.MAPPER.createObjectNode();
        out.put("title", Json.text(in.path("title")));
        String opening = Optional.ofNullable(Json.text(in.path("opening"))).orElse("");
        String closing = Optional.ofNullable(Json.text(in.path("closing"))).orElse("");
        out.put("opening", opening);
        out.put("closing", closing);
        ArrayNode segs = out.putArray("segments");
        StringBuilder transcript = new StringBuilder(opening.trim());
        for (JsonNode s : in.path("segments")) {
            String narration = Optional.ofNullable(Json.text(s.path("narration"))).orElse("").trim();
            List<String> pids = new ArrayList<>();
            s.path("patientIds").forEach(n -> pids.add(n.asString()));
            for (String pid : pids) if (!allowed.contains(pid)) problems.add("segment mentions patientId " + pid + " which is not an authorized assignment");
            if (BRACKETS.matcher(narration).find()) problems.add("narration contains bracketed ids; keep ids only in sources");
            ObjectNode seg = segs.addObject();
            seg.put("heading", Json.text(s.path("heading")));
            ArrayNode pa = seg.putArray("patientIds");
            pids.stream().filter(allowed::contains).forEach(pa::add);
            seg.put("narration", narration);
            ArrayNode srcOut = seg.putArray("sources");
            for (JsonNode src : s.path("sources")) {
                SourceItem item = sources.get(Json.text(src.path("type")) + "|" + Json.text(src.path("id")));
                if (item == null) {
                    problems.add("source " + Json.text(src.path("type")) + " " + Json.text(src.path("id")) + " was not in the context");
                    continue;
                }
                if (!pids.contains(item.patientId())) {
                    problems.add("source " + item.id() + " belongs to a patient not listed in that segment's patientIds");
                    continue;
                }
                ObjectNode o = srcOut.addObject();
                o.put("type", item.type());
                o.put("id", item.id());
                o.put("patientId", item.patientId());
                o.put("label", item.label());
            }
            if (!pids.isEmpty() && srcOut.isEmpty() && sources.values().stream().anyMatch(i -> pids.contains(i.patientId()))) {
                problems.add("segment '" + Json.text(s.path("heading")) + "' cites no sources");
            }
            transcript.append("\n\n");
            seg.put("charOffset", transcript.length());
            transcript.append(narration);
        }
        if (!closing.isBlank()) transcript.append("\n\n").append(closing.trim());
        String full = transcript.toString();
        String lower = full.toLowerCase(Locale.ROOT);
        for (String name : forbidden) {
            if (Pattern.compile("\\b" + Pattern.quote(name.toLowerCase(Locale.ROOT)) + "\\b").matcher(lower).find()) {
                problems.add("the narration names a patient who is not an authorized assignment (" + name + ")");
            }
        }
        if (segs.isEmpty()) problems.add("no segments");
        int words = full.isBlank() ? 0 : full.trim().split("\\s+").length;
        out.put("wordCount", words);
        return new Script(out, full, words, problems);
    }

    static final Pattern BRACKETS = Pattern.compile("\\[(?:RECORD|FLAG|ALERT|APPOINTMENT|MONITORING_ALERT|REC-|FLG-|ALR-|MAL-)");

    private static List<String> distinctiveNames(Patient p) {
        List<String> out = new ArrayList<>();
        out.add(p.fullName());
        if (p.lastName() != null && p.lastName().length() >= 5) out.add(p.lastName());
        return out;
    }

    // =================================================================================================================
    // Read models and audio access (owner only)
    // =================================================================================================================

    public record SourceView(String type, String id, String patientId, String patientName, String label, String link) {}

    public record SegmentView(int index, String heading, String narration, List<String> patientIds, Double startSeconds,
                              List<SourceView> sources) {}

    public record BriefingView(String briefingId, String shiftId, String shiftLabel, LocalDate shiftDate, LocalDateTime shiftStart,
                               LocalDateTime shiftEnd, String location, String providerName, String providerRole, String mode,
                               String status, String statusDetail, boolean reused, int patientCount, int openAlertCount,
                               int excludedPatients, Double durationSeconds, Integer estimatedSeconds, Integer wordCount,
                               String title, String geminiModel, String ttsProvider, String ttsModel, String voiceId,
                               boolean audioAvailable, LocalDateTime createdAt, LocalDateTime completedAt,
                               List<SegmentView> segments, String opening, String closing, String notice) {}

    public BriefingView get(DemoUser user, String id) {
        return view(user, owned(user, id), false);
    }

    public record Audio(byte[] bytes, String contentType) {}

    public Audio audio(DemoUser user, String id) {
        Briefing b = owned(user, id);
        if (!"READY".equals(b.status()) || b.audioPath() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "AUDIO_NOT_AVAILABLE", "This briefing has no audio.");
        }
        Path p = Path.of(b.audioPath()).toAbsolutePath().normalize();
        if (!p.startsWith(audioDir())) throw AuthorizationService.denied(null);
        try {
            return new Audio(Files.readAllBytes(p), b.audioContentType());
        } catch (IOException e) {
            throw new ApiException(HttpStatus.GONE, "AUDIO_MISSING", "The audio file is no longer stored on this server. Regenerate the briefing.");
        }
    }

    /** Only the clinician who generated a briefing can read it, its transcript or its audio. */
    Briefing owned(DemoUser user, String id) {
        auth.requirePermission(user, Permission.CLINICAL_BRIEFING);
        Briefing b = briefings.find(id).orElse(null);
        if (b == null || !b.userId().equals(user.id())) throw AuthorizationService.denied(null);
        if (IN_PROGRESS.contains(b.status()) && !running.contains(b.briefingId())
                && b.createdAt() != null && b.createdAt().isBefore(LocalDateTime.now().minusMinutes(10))) {
            b = withEnd(b.withStatus("FAILED", "Generation was interrupted (for example by a backend restart). Generate it again."));
            briefings.update(b);
        }
        return b;
    }

    BriefingView view(DemoUser user, Briefing b, boolean reused) {
        Shift shift = workforce.findShift(b.shiftId()).orElse(null);
        Map<String, String> names = patients.findAll().stream().collect(Collectors.toMap(Patient::patientId, Patient::fullName));
        AccessScope scope = auth.scope(user);
        List<SegmentView> segments = new ArrayList<>();
        String opening = null, closing = null, title = null;
        Integer estimated = null, words = null;
        if (b.scriptJson() != null) {
            JsonNode s = Json.parse(b.scriptJson());
            opening = Json.text(s.path("opening"));
            closing = Json.text(s.path("closing"));
            title = Json.text(s.path("title"));
            estimated = s.path("estimatedSeconds").isMissingNode() ? null : s.path("estimatedSeconds").asInt();
            words = s.path("wordCount").isMissingNode() ? null : s.path("wordCount").asInt();
            int i = 0;
            for (JsonNode seg : s.path("segments")) {
                List<String> pids = new ArrayList<>();
                seg.path("patientIds").forEach(n -> pids.add(n.asString()));
                List<SourceView> srcs = new ArrayList<>();
                for (JsonNode src : seg.path("sources")) {
                    String pid = Json.text(src.path("patientId"));
                    if (!scope.allowsPatient(pid)) continue;     // re-checked at read time
                    srcs.add(new SourceView(Json.text(src.path("type")), Json.text(src.path("id")), pid, names.get(pid),
                            Json.text(src.path("label")), link(Json.text(src.path("type")), Json.text(src.path("id")), pid, user)));
                }
                segments.add(new SegmentView(i++, Json.text(seg.path("heading")), Json.text(seg.path("narration")), pids,
                        seg.path("startSeconds").isMissingNode() ? null : seg.path("startSeconds").asDouble(), srcs));
            }
        }
        DemoUser owner = com.camellia.security.DemoDirectory.find(b.userId()).orElse(user);
        return new BriefingView(b.briefingId(), b.shiftId(), shift == null ? null : shift.label(), shift == null ? null : shift.shiftDate(),
                shift == null ? null : shift.startAt(), shift == null ? null : shift.endAt(), shift == null ? null : shift.location(),
                owner.name(), owner.role().label(), b.mode(), b.status(), b.statusDetail(), reused, nz(b.patientCount()),
                nz(b.openAlertCount()), nz(b.excludedCount()), b.durationSeconds(), estimated, words, title, b.geminiModel(),
                b.ttsProvider(), b.ttsModel(), b.voiceId(), "READY".equals(b.status()) && b.audioPath() != null, b.createdAt(),
                b.completedAt(), segments, opening, closing, NOTICE);
    }

    private String link(String type, String id, String patientId, DemoUser user) {
        if (type == null || id == null) return null;
        return switch (type) {
            case "FLAG" -> "/flags/" + id;
            case "ALERT" -> user.has(Permission.ANESTHESIA_OB_ALERTS) ? "/alerts/" + id : null;
            case "MONITORING_ALERT" -> monitoringLink(id, patientId);
            default -> "record:" + id;    // opens the source record drawer
        };
    }

    private String monitoringLink(String alertId, String patientId) {
        return monitoring.openAlertsFor(List.of(patientId)).stream().filter(a -> a.alertId().equals(alertId)).findFirst()
                .map(a -> "/patients/" + patientId + "/encounters/" + a.encounterId() + "/monitoring").orElse("/patients/" + patientId);
    }

    // =================================================================================================================
    // helpers
    // =================================================================================================================

    private Map<String, String> orgNames() {
        return orgs.findAll().stream().collect(Collectors.toMap(Organization::orgId, Organization::name));
    }

    private static Briefing withEnd(Briefing b) {
        return new Briefing(b.briefingId(), b.shiftId(), b.userId(), b.mode(), b.status(), b.statusDetail(), b.sourceFingerprint(),
                b.patientCount(), b.openAlertCount(), b.excludedCount(), b.scriptJson(), b.transcript(), b.geminiModel(),
                b.ttsProvider(), b.ttsModel(), b.voiceId(), b.audioSha256(), b.audioPath(), b.audioBytes(), b.audioContentType(),
                b.durationSeconds(), b.ttsCharacters(), b.scriptAttempts(), b.createdAt(), LocalDateTime.now());
    }

    private static String age(Patient p) {
        return p.dateOfBirth() == null ? "age unknown" : Period.between(p.dateOfBirth(), LocalDate.now()).getYears() + " years";
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static int nz(Integer v) {
        return v == null ? 0 : v;
    }

    private static String message(Throwable e) {
        String m = e.getMessage();
        return m == null ? e.getClass().getSimpleName() : m;
    }

    static String sha256(String s) {
        return sha256(s.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
