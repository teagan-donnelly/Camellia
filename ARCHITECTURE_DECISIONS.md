# Architecture Decisions — Camellia

This document explains the major technical decisions behind Camellia and the tradeoffs made for a hackathon. Sections
1–14 cover the first iteration; sections 15–22 cover the role-based access iteration.
Paths are relative to the repository root.

---

## 1. Spring Boot backend + React frontend

**Decision.** Java 21 / Spring Boot 4.1 REST API with a separate React 19 + TypeScript + Vite single-page app.

**Why.**
- The team specified Java/Spring Boot and React/TypeScript. Spring's mature web, validation and JDBC support let us
  spend time on the clinical workflow instead of plumbing.
- A strict client/server split keeps **all credentials (Snowflake key, Gemini key) on the server**. The browser only
  talks to `/api`.
- The official SDKs we depend on, the Snowflake JDBC driver and the Google Gen AI Java SDK, are first-class on the JVM.

**Shape.** Packages follow responsibilities: `controller` (HTTP), `dto` (wire types), `model` (records), `repository`
(SQL), `service` (read models), `ingestion` (normalization), `analysis` (rules), `coordination` (workflow), `search`,
`ai` (Gemini features), `integration/{snowflake,gemini}` (external systems), `security` (demo sessions, roles,
authorization) and `web` (errors).

## 2. Snowflake as the primary persistence layer

**Decision.** All patients, organizations, records, labs, conditions, medications, appointments, flags, alerts,
evidence, notes, activity, AI observations and analysis runs live in Snowflake. Access is through the **official
Snowflake JDBC driver (4.4)** with **key-pair authentication** (`authenticator=SNOWFLAKE_JWT`) and plain Spring
`JdbcTemplate`.

**Why Snowflake.** Clinical data from many organizations is an analytics-shaped problem: wide, append-heavy and queried
across sources. Snowflake fits that, and its roadmap fits the product (Cortex Search, streams and tasks for continuous
screening, data sharing between organizations).

**Why plain JDBC rather than JPA/Hibernate.** Hibernate's dialect assumptions (sequences, constraint enforcement,
locking) do not map cleanly onto Snowflake. Explicit SQL keeps every query visible and parameterized, and easy to reason
about for performance on a remote warehouse.

**Snowflake-specific choices** (`DataSourceConfig`, `SnowflakeConnectionSettings`):
- `JDBC_QUERY_RESULT_FORMAT=JSON` avoids Arrow's reflective memory access on modern JDKs. `--add-opens` is still set for
  safety.
- The session uses `TIMEZONE=UTC`, `CLIENT_TIMESTAMP_TYPE_MAPPING=TIMESTAMP_NTZ` and a UTC JVM, so `TIMESTAMP_NTZ`
  columns hold unambiguous UTC wall-clock times.
- **Batch inserts with explicit SQL types** (`Jdbc.batch(..., types)`). Snowflake array-binds JDBC batches, which needs
  consistent types per column. A `NULL` bound as an untyped value would break that, so every batch declares its column
  types.
- Hikari pool with `CLIENT_SESSION_KEEP_ALIVE` and a 30-minute max lifetime.
- A bootstrap connection runs `CREATE DATABASE/SCHEMA IF NOT EXISTS` (opt-out with `SNOWFLAKE_CREATE_DATABASE=false`).
- Read models **bulk-load** (one query per table) instead of issuing per-row queries (`ClinicalViewService.Lookup`).
  On a remote warehouse, round-trip count dominates latency.

**Local fallback, labeled.** If no `SNOWFLAKE_ACCOUNT` is set (`CAMELLIA_DB_MODE=auto`), the app uses an H2 file
database so the team can develop without credentials. This mode is reported as `LOCAL_FALLBACK — NOT Snowflake` in the
log, in `/api/integrations/status`, and in a header badge. If Snowflake *is* partially configured, or
`CAMELLIA_DB_MODE=snowflake` is set, startup **fails with a diagnostic** rather than silently falling back
(`ConfigurationHandlingTest`). The DDL is maintained once for Snowflake (`db/schema-snowflake.sql`), and the fallback
translates the Snowflake types explicitly (`SchemaInitializer.toH2`) rather than pretending the two databases are
identical.

## 3. Schema design

Canonical DDL: [`backend/src/main/resources/db/schema-snowflake.sql`](backend/src/main/resources/db/schema-snowflake.sql).

- **One citation namespace.** Every source item (note, lab, condition, medication, encounter, appointment, imaging)
  gets a row in `CLINICAL_RECORDS` with a stable `RECORD_ID`, plus a typed row in its own table (`LAB_RESULTS`,
  `MEDICAL_CONDITIONS`, …) that points back to it. Flags, alerts and Gemini answers all cite `RECORD_ID`s, so one
  validation rule ("does this ID exist among the records I retrieved?") covers every kind of evidence.
- **Provenance is preserved.** `ORG_ID`, `SOURCE_FORMAT`, `SOURCE_RECORD_ID`, `RAW_CONTENT` (the payload exactly as
  received) and `INGESTED_AT` are stored with every record.
- **Master patient index.** `PATIENT_SOURCE_IDENTIFIERS` maps each fictional organization's MRN to one internal
  patient.
- **Workflow tables.** `CLINICAL_FLAGS` + `FLAG_EVIDENCE`, `COORDINATION_ALERTS` + `ALERT_EVIDENCE`,
  `COORDINATION_NOTES` and `ACTIVITY_LOG` (audit-style history). `AI_OBSERVATIONS` holds validated Gemini extractions.
  `ANALYSIS_RUNS`, `INTEGRATION_CHECKS` and `DEMO_METADATA` support operations.
- **Deterministic IDs** (e.g. `REC-NDL-ACC-55012-1` = Northgate, accession 55012, OBX 1) make seeding idempotent.
  Snowflake does not enforce primary keys, so the app checks for existing IDs before inserting and keys flags by a
  `DEDUP_KEY`.

## 4. Normalizing heterogeneous records

Five fictional organizations each export in a different shape (`backend/src/main/resources/seed/sources/`):

| Organization | Format | Normalizer |
|---|---|---|
| Larkspur Regional Medical Center | FHIR R4-like bundle (Encounter, Observation, Condition, MedicationStatement, DocumentReference, DiagnosticReport, Appointment) | `FhirLikeNormalizer` |
| Cedar Hollow Community Hospital | FHIR R4-like bundle | `FhirLikeNormalizer` |
| Bellwether Women's Health Associates | Legacy practice-system JSON (visits with embedded notes/diagnoses, med list, schedule) | `LegacyPracticeJsonNormalizer` |
| Northgate Diagnostic Laboratories | HL7 v2.5.1 ORU^R01 (MSH/PID/OBR/OBX/NTE) | `Hl7v2LabNormalizer` |
| Riverbend Urgent Care | Plain-text dictation with `KEY: value` headers | `PlainTextNoteNormalizer` |

Each normalizer:
1. resolves the source MRN to an internal patient through the MPI (unmatched identities are skipped with a warning,
   never guessed);
2. maps the source structure to `ClinicalRecord` plus typed rows;
3. writes a readable `CONTENT_TEXT`, used for search and AI context;
4. applies **deterministic concept tags** (`ClinicalTagger`, e.g. `|FRACTURE|EMERGENCY|`) and lab-derived tags
   (`ABNORMAL_LAB`, `LOW_PLATELETS`) from the *reporting lab's own reference range*.

**Relative demo dates.** Source files use tokens like `@D-43T0815`. `RelativeDates.render` resolves them against a demo
anchor date *before parsing*, so stored raw payloads contain real dates and "upcoming encounter" scenarios work whenever
the demo runs. *Reset demo data* re-anchors to today.

## 5. How search works

```
"pregnant patients anesthesia should know about"
        │  Gemini (structured output, enum-constrained JSON schema)
        ▼
SearchCriteria { concepts[], keywords[], recordTypes[], organizationIds[], patientId, dates,
                 obstetricOnly, upcomingDeliveryOnly, multipleOrganizationsOnly, minMatchesPerPatient, matchMode }
        │  SearchCriteria.sanitize(): allow-lists + keyword character stripping
        ▼
SearchService.buildQuery(): fixed SQL fragments + bound parameters ONLY  →  Snowflake
        ▼
Results grouped by patient → Gemini summary over the retrieved record IDs (citations validated)
```

- **The model never writes SQL.** Gemini can only choose values from enums in the JSON schema (concepts, record types,
  organization IDs, patient IDs). The backend re-validates them, strips keywords to `[a-z0-9 -]`, and binds every value.
  `SearchCriteriaTest` and the workflow test send injection strings through the pipeline.
- "Repeated X" requests use `minMatchesPerPatient`, which counts *distinct record dates*, so several documents from one
  visit don't count as repetition.
- **Fallback.** Without Gemini, `RuleBasedQueryInterpreter` maps common phrases to the same criteria. The UI labels it
  *"Rule-based interpretation (not Gemini)"*.
- Keyword mode (`GET /api/search`) ANDs the terms and applies filters directly.

## 6. How Gemini is integrated

All calls go through `GeminiGateway` → `GoogleGenAiGateway`, which uses the **official Google Gen AI Java SDK**
(`com.google.genai`). The default model is `gemini-3.8-flash` (configurable with `GEMINI_MODEL`). Every request uses
`responseMimeType=application/json` with a `responseJsonSchema` (`AiSchemas`), a server-side API key, an HTTP timeout
and mapped errors (429 → rate limited, 401/403 → bad key, 404 → model unavailable, timeouts → 504).

| Capability | Endpoint | Triggered by |
|---|---|---|
| 1 · Document interpretation (extraction) | `POST /api/patients/{id}/ai-extraction` | Patient profile → *AI observations* → *Extract with Gemini* |
| 2 · Evidence-grounded flag explanation | `POST /api/ai/explain-flag` | *Explain with Gemini* on a flag or alert (result cached on the flag) |
| 3 · Interactive assistant | `POST /api/ai/chat` | Assistant panel on patient, flag, alert and search pages |
| 4 · Natural-language search | `POST /api/search/interpret`, `POST /api/ai/search-summary` | Clinical Search in *Ask Gemini* mode; Ctrl/Cmd+K bar |
| 5 · Explainability & verification | built into all of the above | Facts / interpretation / missing sections, citation chips |
| Connectivity check | `POST /api/integrations/gemini/check` | System & integrations page |

All safety rules live in one reviewable file (`ai/Prompts.java`): use only the provided records, cite record IDs,
separate facts from interpretation, say when information is missing, never diagnose or recommend treatment, and never
declare a patient cleared.

## 7. Retrieval before AI analysis

Gemini never receives the whole database.
- **Patient / flag / alert context.** Only that patient's records are candidates. `EvidencePacker` ranks them (flag or
  alert evidence first, then question-term and concept overlap, then recency), truncates long documents, and packs up
  to `GEMINI_MAX_CONTEXT_CHARS` (28k by default, at most 30 records).
- **Search context.** Only the record IDs returned by the search (at most 40).
- **Alert context** also includes the human coordination notes, labeled as workflow notes, not source records.
- **Conversation history** is kept server-side, bound to exactly one context (`ConversationStore`). Reusing a
  conversation ID for another patient returns `409 CONTEXT_MISMATCH`, and the UI starts a new conversation whenever the
  context changes. Records are re-retrieved on every turn; history carries only prior questions and validated answers.

## 8. Avoiding fabricated citations

`CitationValidator` post-processes every answer against the set of record IDs that were actually sent to the model:
- unknown IDs in the answer text are replaced with "[unverified reference removed]";
- unknown IDs in statement citation lists are dropped;
- a **documented fact with no remaining valid citation is removed** (it cannot be verified);
- counts of removed items are returned and shown in the UI.

Extraction is stricter. An observation is stored only if its `sourceRecordId` was provided **and** its `evidenceQuote`
appears verbatim (case and whitespace normalized) in that record. The UI's citation chips open the stored record, so a
reviewer can always check the source. `GeminiGroundingIntegrationTest` drives the pipeline with a scripted model that
deliberately invents citations and asserts that they are removed and that no other patient's data appears in the
prompt.

## 9. How flags are generated

Four `ScreeningRule` beans (`analysis/`) run over per-patient snapshots built from bulk queries:

| Rule | Candidate when | Routing |
|---|---|---|
| `OB_ANESTHESIA_REVIEW` | Booked **delivery-related** appointment within 120 days (an estimated due date alone is *not* treated as an admission) **and** ≥1 trigger: platelet below the lab's reference range (300-day window), an active condition matching configured anesthesia-relevant terms, or an active anticoagulant | Coordination alert to *Anesthesia OB Coordination*; `MULTIDISCIPLINARY_OB` when ≥2 consulting specialties appear in the evidence |
| `RECURRENT_FRACTURE` | ≥3 fracture-related encounters in 5 years | Flag only |
| `REPEATED_ABNORMAL_LAB` | ≥3 out-of-range results for the same LOINC code in 2 years | Flag only |
| `REPEATED_SYMPTOM` | ≥3 encounters in one symptom category in 1 year | Flag only |

- All terms, windows and thresholds are in `application.yml` under a banner reading **PROTOTYPE SCREENING HEURISTICS —
  NOT VALIDATED CLINICAL THRESHOLDS**. They are shown in the UI (*How flags are generated*).
- Abnormality uses each **reporting laboratory's own reference range** (a documented fact), not an external threshold.
- *Review priority* (`EXPEDITED` within 21 days of the encounter, otherwise `STANDARD`) orders the work queue only. It
  is explicitly *not* a risk score.
- Evidence items are labeled `TRIGGER`, `SUPPORTING` or `UPCOMING_ENCOUNTER`, so a flag always shows exactly what fired.

**De-duplication and re-evaluation** (`AnalysisService`). Each candidate has a stable `DEDUP_KEY` (rule + patient,
plus test code or symptom category where relevant). If a flag with that key exists, a SHA-256 **evidence fingerprint**
of `(recordId, role)` pairs is compared:
- same fingerprint → the flag is only marked re-evaluated ("unchanged, duplicate prevented");
- different fingerprint → the flag and alert evidence are replaced, the explanation cache is cleared, a **SYSTEM note**
  lists the new records, and the human workflow status is **preserved**;
- no flag → the flag and alert are created and the routing is logged.

## 10. How coordination alerts are routed and updated

- Routing is to a **simulated team queue** (`ASSIGNED_TEAM = "Anesthesia OB Coordination"`). Nothing is sent outside
  the app.
- Workflow: `NEW → UNDER_REVIEW → COORDINATION_NEEDED → PLAN_DOCUMENTED → RESOLVED`, plus `DISMISSED` (reason required).
  Transitions are validated server-side (`CoordinationService.ALERT_TRANSITIONS`). `PLAN_DOCUMENTED` requires a
  human-written summary of the plan the care team documented. Camellia never writes the plan.
- Separate fields track acknowledgement (by and when), referring-provider contact (`NOT_STARTED/PLANNED/COMPLETED`),
  whether a planning discussion occurred, whether further information is needed, the review due date and last
  updated by/at.
- **Role-based permissions** (see [§15](#15-role-based-authorization-layer)). Only CRNAs and anesthesiologists
  (`UPDATE_COORDINATION`) can acknowledge alerts or change their workflow. L&D nurses can add notes
  (`ADD_COORDINATION_NOTES`). Other roles cannot see coordination alerts at all.
- Every change writes a `STATUS` note and an `ACTIVITY_LOG` entry. **AI endpoints cannot call the coordination
  service.**

## 11. Frontend ↔ backend communication

- REST/JSON over `/api`. In development the Vite server proxies `/api` to `localhost:8080`, so there is no CORS
  configuration to manage (CORS is also configured for direct access).
- `api/client.ts` sends requests with the browser's demo-session cookie (it never sends a user id). It turns the
  backend's standard error envelope `{status, code, message}` into readable UI errors, and on `401
  DEMO_SESSION_REQUIRED` re-selects the remembered demo user once and retries (see [§16](#16-demo-sessions-signed-cookie-not-a-trusted-header)).
- **TanStack Query** caches reads and invalidates related queries after each write. Every button calls a real endpoint.
- The UI makes AI provenance obvious: an **AI-generated · model** badge, sage "documented" sections, coral
  "interpretation" sections, amber "missing information" sections, and clickable citation chips that open the original
  record (`RecordDrawer`).

## 12. Persistent state

| State | Where |
|---|---|
| Clinical data, flags, alerts, evidence, notes, statuses, activity, AI observations, flag explanations | Snowflake (or the labeled local H2 file `backend/data/`) |
| Assistant conversation turns | Backend memory (2-hour TTL) |
| Active demo identity | HMAC-signed HttpOnly cookie `CAMELLIA_DEMO_SESSION` (12-hour TTL) |
| Last selected demo user | Browser `localStorage` (convenience only, used to re-create the session) |
| Practices and patient–practice associations | Snowflake `PRACTICES`, `PATIENT_PRACTICE_ASSOCIATIONS` |

Statuses and notes survive browser refreshes and backend restarts. Seeding never duplicates rows.

## 13. Hackathon tradeoffs

- Keyword and concept search instead of embeddings: explainable and deterministic, but lower recall on paraphrases.
- Rules are deliberately narrow and term-based. The focus is on demonstrating *explainable* detection, not coverage.
- App-level uniqueness in a single backend instance, with no distributed locking.
- No real authentication. The user switcher is demo impersonation; access rules are enforced server-side, but anyone
  can choose any demo user (§16).
- Nurse and anesthesia roles have network-wide access to the 9 synthetic patients (§15).
- Assistant memory is not persisted.
- A synchronous analysis run triggered at startup or on demand, rather than streaming ingestion.
- The large Snowflake JDBC fat jar is accepted for simplicity.

## 14. What real healthcare deployment would require

- **Compliance and security:** HIPAA risk assessment, BAAs with every vendor (including the AI provider, e.g. Gemini
  via Vertex AI under a BAA), encryption and key management, network isolation, least-privilege roles, Snowflake
  masking and row access policies, and audit logging that meets retention requirements.
- **Identity:** SSO/OIDC with EHR-launched context (SMART on FHIR), real role and team management, and break-glass
  procedures.
- **Interoperability:** certified FHIR/HL7 interfaces, a robust MPI with probabilistic matching and review queues, and
  terminology services (LOINC/SNOMED/RxNorm).
- **Clinical governance:** screening criteria authored and validated by anesthesia, OB and MFM leadership; sensitivity
  and specificity measurement; alert-fatigue monitoring; and change control for rule updates.
- **AI governance:** evaluation sets, hallucination and citation audits, PHI-safe logging, model version pinning, human
  review policies, and documentation of intended use. Possible regulatory assessment for clinical decision support.
- **Operations:** monitoring, alert delivery integrated with existing secure messaging or EHR in-basket workflows, SLAs
  and escalation paths, and high availability.

---

# Role-based access iteration

## 15. Role-based authorization layer

**Decision.** One `AuthorizationService` (`backend/.../security/`) is the only place that decides access. Roles
(`Role`) map to permissions (`Permission`). Demo users (`DemoDirectory`) have a role and, for physicians, practice
memberships.

| Permission | Physician | RN | L&D nurse | CRNA | Anesthesiologist |
|---|---|---|---|---|---|
| VIEW_PATIENTS, CLINICAL_SEARCH, CLINICAL_ALERTS, GEMINI_ASSISTANT | ✓ | ✓ | ✓ | ✓ | ✓ |
| REVIEW_FLAGS (change flag review status) | ✓ | | | ✓ | ✓ |
| ANESTHESIA_OB_ALERTS (see the OB anesthesia workflow) | | | ✓ | ✓ | ✓ |
| UPDATE_COORDINATION (acknowledge, status, contact, toggles) | | | | ✓ | ✓ |
| ADD_COORDINATION_NOTES | | | ✓ | ✓ | ✓ |

**Scope computed once, applied in SQL.** `scope(user)` returns an `AccessScope(userId, patientIds | null, anesthesia)`.
`null` means network-wide. Every patient-specific path uses it:

- **Record search:** the patient `IN (...)` list is added to the parameterized SQL, so restricted rows are never read.
  `openFlagsOnly` excludes the anesthesia rule when needed.
- **Flag search:** the same patient list and `RULE_ID <> 'OB_ANESTHESIA_REVIEW'` are added to the SQL.
- **Read models** (`ClinicalViewService.Lookup(scope)`): patients, flags, alerts, evidence, appointments and records are
  pre-filtered before any DTO is built. That covers the directory, profile, "cited as evidence by" back-references,
  dashboard and activity feed.
- **Screening run results** returned to a user only include outcomes they may see. The run itself still evaluates the
  whole network.

**Generic denials.** `requirePatient/requireFlag/requireAlert` throw `403 ACCESS_DENIED` with the same message for
missing and restricted ids, so the API cannot be used to probe whether a patient exists. A missing or invalid session is
`401 DEMO_SESSION_REQUIRED`.

**Why centralized.** Scattered `if (role == ...)` checks are how access bugs happen. With one service, every endpoint
asks the same question, and `RoleBasedAccessIntegrationTest` exercises each path per role.

**Frontend.** The UI hides what a role can't use (sidebar from `GET /api/session` → `navigation`, buttons from
`permissions`, a route guard for `/alerts`) and shows a generic *Not available to your role* panel on 403. These are
conveniences only. The backend is the enforcement point.

**Prototype simplification.** Nurses and anesthesia roles see all patients. Real systems would scope by care
relationship, unit and organization policy, with break-the-glass access and audit.

## 16. Demo sessions: signed cookie, not a trusted header

**Decision.** `POST /api/session {userId}` issues an HMAC-SHA256-signed, HttpOnly, `SameSite=Strict` cookie
(`DemoSessionService`, 12-hour TTL). `CurrentUserArgumentResolver` injects the `DemoUser` into controllers only from a
valid cookie. The old `X-Camellia-User` header is no longer read.

**Why.** With a per-request header, any script or `curl` call could claim to be anyone on any request, and the backend
had nothing to check. A server-signed token means the identity is chosen in one explicit place and cannot be edited
in the browser (tampered cookies get 401). HttpOnly keeps it away from page scripts; SameSite=Strict blocks cross-site
use.

**What it is not.** It is still **demo impersonation, not authentication**: `GET /api/session/users` and `POST
/api/session` are public so judges can switch users. The secret comes from `CAMELLIA_DEMO_SESSION_SECRET`; if unset, a
random per-startup secret is generated, sessions reset on restart, and the frontend silently re-selects the remembered
user.

## 17. Practice tables (minimal schema change)

**Decision.** Two new tables, with nothing existing altered:

- `PRACTICES(PRACTICE_ID, NAME, ORG_ID, SPECIALTY, IS_SYNTHETIC)`
- `PATIENT_PRACTICE_ASSOCIATIONS(PATIENT_ID, PRACTICE_ID, RELATIONSHIP, REGISTERED_AT)`

Practice A (Larkspur Regional Primary Care, org LRMC) holds patients C, D, E, F and H. Practice B (Bellwether Women's
Health Associates, org BWHA) holds A, B, G and I. They are seeded from `seed/practices.json` and each patient's
`practices` entry, idempotently, so an existing Snowflake database is upgraded on the next startup.

**Why separate tables.** A patient can belong to several practices, and a practice is not an organization (one
organization has many practices). An association table models that without touching `PATIENTS`.

## 18. Anesthesia OB data is a restricted workflow

**Decision.** Flags from rule `OB_ANESTHESIA_REVIEW` and all coordination alerts are visible only with
`ANESTHESIA_OB_ALERTS`, everywhere: lists, flag search, record search `openFlagsOnly`, dashboard metrics, patient
profile, evidence back-references, rule descriptors, screening outcomes, the activity feed and Gemini context.

**Why.** Hiding the page while leaking the same alert through search or the dashboard would not be access control. Dr.
Mitchell can see her patient Ava's records, but not the anesthesia team's alert about Ava.

## 19. Gemini retrieval is authorized first

**Decision.** `ClinicalAiService` authorizes **before** retrieval:

- PATIENT, FLAG and ALERT chat contexts call `requirePatient/requireFlag/requireAlert` first. A denial means no records
  are loaded and no request is sent to Gemini.
- SEARCH context and search summaries drop records outside the scope, and return 403 if nothing remains.
- NL search interpretation sends Gemini only the accessible patients, and only the flag categories the role may use
  (in the prompt *and* in the response schema's enum).
- Conversations are keyed by `userId@context`, so history never crosses users.

The integration test proves this with a scripted gateway: denied contexts record **zero** Gemini requests, and allowed
prompts contain no out-of-scope patient names.

## 20. Flag search

**Decision.** `FlagSearchCriteria` is an allow-listed criteria object (text, categories, status group OPEN/CLOSED/ANY,
statuses, priorities, specialties, concepts, patient). Only `FlagSearchService` turns it into SQL, using fixed fragments
and bound parameters. It is used by:

- the Clinical Alerts filter bar (`GET /api/flags/search`, `GET /api/flags/facets`);
- natural-language search: Gemini (or the rule-based fallback) sets `searchTarget: FLAGS` and fills the flag fields, and
  the frontend runs `POST /api/flags/search`.

"Unresolved" uses the alert's workflow status when an alert exists, otherwise the flag's review status. Asking for a
category the role can't use is removed from the criteria (never widened) and reported in `notice`. Asking for an
out-of-scope patient forces an empty result.

## 21. Navigation and alert consolidation

- "Clinical Flags" is renamed **Clinical Alerts** in the UI (API paths stay `/api/flags…`).
- The standalone Care Coordination page became the **Coordination board** tab inside Anesthesia OB Alerts, because it
  shows the same restricted data. `/coordination` redirects there.
- Sidebar items come from the backend session's `navigation`, so the UI and the server agree on what a role can open.
- The router is keyed by user id: switching users remounts every page, and the TanStack Query cache is cleared, so no
  previous user's data, drafts or chat history remains on screen.

## 22. Logo transparency

The brand PNG was a screenshot: white background, white flower center and blue selection lines at the edges.
`assets/make_logo_transparent.py` crops to the artwork (ignoring the blue lines) and converts white to alpha. Pixels at
or above the lightest petal tone stay fully opaque, so the petals keep their exact color on dark backgrounds, while
anti-aliased edges are un-premultiplied to avoid white halos. The original is kept as `camellia-logo-original.png`. In
the SVG mark (`Logo.tsx`, `favicon.svg`) the painted white center circle was replaced with a mask, so the center is
truly transparent on navy and white.
