# Implementation Status

_Last updated: 2026-10-10 (role-based access iteration)._

**Legend**
- ✅ **Implemented and tested.** Covered by automated tests and/or exercised end to end through the running app.
- 🟡 **Implemented but unverified.** The code is complete and compiles, but it has not been exercised against the real
  external service.
- 🟠 **Partially implemented.**
- ⛔ **Not implemented.**

## Camellia V3 (branch `feature/camellia-v3`, 2026-10-11)

Rollback point: tag `pre-camellia-v3`.

| Area | Status | How it was verified |
|---|---|---|
| Profile dropdown above page content | Done | Browser test (Chromium): 0 of 35 sample points covered on Clinical Search, the coordination board and the alert workspace, at 1440 px and 390 px; Escape closes it; selecting a user switches identity |
| Coordination actions | Done (feedback fix) | Every action persisted in the browser on H2 and on **live Snowflake** (acknowledge, contact, both toggles, note, Coordination Needed, Plan Documented, Resolved; dismiss with reason on H2). Each Snowflake write took 3.5–6 s — the root cause of the "nothing happens" reports |
| Database status (4 states) | Done | Integration test + live Snowflake: `SNOWFLAKE_CONNECTED`; real read/write check verified |
| Synthetic identifiers documented | Done | README "Patient identifiers"; UI labels `MRN SYN-…` |
| 100 synthetic clinicians | Done | Test: 40/30/15/10/5 + 1 coordinator; re-seeding does not duplicate; 7 demo accounts in the switcher |
| Patient import (FHIR JSON, Camellia JSON, CSV) | Done | Tests + API run on H2 **and Snowflake**: create, attach, duplicate skip, MRN conflict, invalid file, unauthorized, screening creates an OB flag/alert, imported data in search |
| Camellia Briefing — shifts, retrieval, scoping, validation, duration loop, caching, privacy | Done | Tests with scripted Gemini + fake ElevenLabs |
| Camellia Briefing — **live Gemini** scripts | Verified | Live runs (H2 and Snowflake) for Maya (Full), Jordan (Priority), Dr. Kim (Full, 1 excluded patient never mentioned); answers came from the fallback model `gemini-3.5-flash-lite` |
| Camellia Briefing — **live ElevenLabs** audio | Verified (2026-10-11) | Full Shift briefing for Maya on Snowflake: *Audio ready*, 140 s MP3 (128 kbps, 44.1 kHz) in the *River* voice with 3 timed segments; another user's request for the audio returned 403. A forced Gemini timeout then fell back to `gemini-3.5-flash-lite` and still produced a 53 s audio briefing |
| Encounter-scoped monitoring, charts, timeline, cards | Done | Tests + API checks on H2 and Snowflake; browser test of card → detail → refresh → back to the same tab |
| Nursing review rules (mobility, repositioning, connectivity), de-duplication | Done | Tests + API checks on H2 and Snowflake |
| Simulator (backend, persisted, seeded, own clock) | Done | Tests; persistence confirmed across a backend restart on Snowflake |
| Gemini monitoring conversations | Done | **Live Gemini**: three questions on Dolores' stay; every citation was a `REC-MON-…` record or a note from the same stay |
| HARDWARE_ARCHITECTURE.md | Done | Documentation (facts vs. assumptions labeled) |

Test totals: **73 backend tests pass** (`./mvnw test`), frontend `npm run build` passes.

Found and fixed during live testing: an admission with no recorded discharge time (3½ years old) was briefed as a current
stay; open-ended stays now count as current only within 30 days.

Not done / limitations: ElevenLabs live audio (no key); runtime-role SQL (`10_…sql`) not yet run in Snowflake; briefing
audio is stored on local disk (needs object storage for serverless); the monitoring REST surface has no device-ingest
endpoint (the simulator writes rows directly); nurse scope is the prototype's network-wide scope.

## Live verification (2026-10-10)

Both sponsor integrations have now been verified live against the team's accounts.

| Check | Result |
|---|---|
| Snowflake connection (key-pair, service user `CAMELLIA_SVC`, role `CAMELLIA_APP`, AWS us-east-1) | ✅ `Camellia ready on Snowflake` |
| Schema creation (19 tables) and synthetic seed (156 records) on Snowflake | ✅ |
| Screening on Snowflake: 6 flags and 3 OB alerts; restart re-seeds 0 rows and leaves 6 flags unchanged | ✅ idempotent and persistent |
| `POST /api/integrations/database/check`: JOIN read plus insert and read-back | ✅ `verified: true` |
| Live Gemini ping, chat (2 turns on Ava's alert), flag explanation (Beatriz), NL search interpretation and summary, extraction (Imani) | ✅ all answered with validated citations to Snowflake records |
| Browser walkthrough with live Gemini (alert chat, AI search with summary) | ✅ |

**Known external constraint.** The team's key is consistently rate limited (HTTP 429) on the primary model
`gemini-3.8-flash`, which was also briefly overloaded (HTTP 503). The gateway retries, then falls back to
`gemini-3.5-flash-lite` and skips the primary model for 3 minutes. Live answers so far came from the fallback model.
The UI always shows the model that actually answered. Raising the key's quota (billing enabled) should let the primary
model answer.

## Role-based access iteration (2026-10-10)

| Item | Status | Evidence |
|---|---|---|
| Six simulated demo users, five roles, permission model (`security/Role`, `Permission`, `DemoDirectory`) | ✅ | `RoleBasedAccessIntegrationTest` |
| Signed demo-session cookie (`POST/GET/DELETE /api/session`); `X-Camellia-User` header no longer trusted; missing or tampered cookie → 401 | ✅ | `requestsWithoutOrWithTamperedSessionAreRejected` |
| Practice tables `PRACTICES`, `PATIENT_PRACTICE_ASSOCIATIONS`; Practice A (5 patients) and Practice B (4) seeded idempotently | ✅ on H2 · 🟡 on Snowflake | Created by `CREATE TABLE IF NOT EXISTS` on next startup; not yet run against the team's Snowflake account |
| Practice-scoped patient directory, profile, records, appointments, AI extraction; generic 403 for restricted and missing ids | ✅ | `physicianSeesOnlyTheirPracticesPatients`, `physicianIsDeniedOtherPracticesPatientsWithAGenericError` |
| Anesthesia OB workflow hidden from physicians and RNs everywhere (lists, flag detail, profile, rules, facets, dashboard) | ✅ | `registeredNurseSeesAllPatientsButNoAnesthesiaWorkflow`, `physiciansCannotReachAnesthesiaAlerts` |
| Coordination permissions: CRNA/anesthesiologist full; L&D nurse notes only; RN cannot change flag review status | ✅ | `anesthesiologistHasFullCoordinationWorkflow`, `laborAndDeliveryNurseCanViewAndAnnotateButNotChangeStatus` |
| Flag search (filter bar + NL), scope and restricted categories enforced in SQL | ✅ | `flagSearchFindsFlaggedPatientsWithinScope`, `flagSearchNeverWidensScope` |
| Gemini authorized before retrieval; zero Gemini requests on denial; prompts contain no out-of-scope patients | ✅ | `geminiNeverReceivesDataOutsideTheUsersScope` (scripted gateway) |
| Per-user dashboard, record search and screening-run outcomes | ✅ | `sameEndpointsReturnDifferentResultsPerUser`, `analysisRunOutcomesAreScoped` |
| Frontend: session-driven sidebar, user switcher with simulated-identity label, route guard, *Not available to your role* panel, Clinical Alerts filter bar, flag results in Clinical Search, role-aware dashboard, Coordination board tab | ✅ | `npm run build`; headless Chrome walkthrough as all six users (below) |
| Transparent logo PNG (white background, center and blue selection lines removed) and masked SVG center | ✅ | Alpha checks: center and corners alpha = 0, petals opaque, 0 visible blue pixels; composites viewed on navy and white |

**Headless Chrome walkthrough** (backend on local H2 with live Gemini; Vite proxy), switching users from the menu:

| User | Sidebar | `/alerts` | Unresolved clinical alerts |
|---|---|---|---|
| Maya Ellison, CRNA | 5 items incl. Anesthesia OB Alerts | Opens | 6 alerts · 6 patients |
| Dr. Alex Kim | 4 items | *Not available to your role* | 3 alerts · 3 patients |
| Dr. Sarah Mitchell | 4 items | *Not available to your role* | 0 (her patients only have OB anesthesia alerts) |
| Jordan Lee, RN | 4 items | *Not available to your role* | 3 alerts · 3 patients |
| Taylor Morgan, RN (L&D) | 5 items | Opens | 6 alerts · 6 patients |
| Dr. Daniel Brooks | 5 items | Opens | 6 alerts · 6 patients |

Also verified: Dr. Kim opening `/patients/CAM-P-1001` shows the restricted panel; Jordan's Clinical Alerts filtered to
*Recurrent fractures* shows Caleb Wren; Maya's NL query *"Show unresolved obstetric anesthesia alerts"* was interpreted
by Gemini as a flag search and returned 3 alerts across 3 patients; `/coordination` redirects to the board tab; no page
errors in the browser console.

## Priority 1: obstetric anesthesia workflow

| Feature | Status | Evidence |
|---|---|---|
| Identify obstetric patients with a booked delivery-related encounter (a due date alone is not treated as an admission) | ✅ | `ScreeningRulesTest.obRuleRequiresScheduledDeliveryNotJustDueDate`, workflow test |
| Triggers: platelets below the lab's reference range, configured conditions, anticoagulants | ✅ | `ScreeningRulesTest` |
| Anesthesia vs. multidisciplinary alert typing | ✅ | `ScreeningRulesTest`, `ClinicalWorkflowIntegrationTest` (A, B and I alerts) |
| Routing to the simulated *Anesthesia OB Coordination* queue | ✅ | workflow test, UI walkthrough |
| Review workspace (summary, encounter, evidence, specialists, detection reason) | ✅ | Scripted Chromium walkthrough |
| Acknowledge, notes, contact status, planning discussion, information needed | ✅ | workflow test, UI walkthrough |
| Workflow New → Under Review → Coordination Needed → Plan Documented → Resolved, plus Dismissed with a reason; transition validation | ✅ | `coordinationStatusNotesAndAccessControlPersist` |
| Persistence after refresh and backend restart | ✅ on local fallback and Snowflake | UI reload test; DB-backed by design |
| Duplicate prevention; re-evaluation updates evidence and preserves human status | ✅ | `repeatedAnalysisDoesNotDuplicateFlagsOrAlerts`, `newEvidenceUpdatesExistingAlertWithoutDuplicating` |
| Care Coordination shared summary (facts / AI / human notes / planning status) | ✅ | UI walkthrough |
| Simulated team-based access control | ✅ | workflow test (403 for other teams) |

## Priority 2: Gemini (Google Gen AI Java SDK `com.google.genai:google-genai` 1.76.0, default model `gemini-3.8-flash`)

| Capability | Status | Evidence |
|---|---|---|
| Gateway: SDK client, JSON schema output, timeouts, error mapping, retry, fallback model, thinking level, usage stats | ✅ | Live calls verified. The not-configured path is tested. |
| 1 · Clinical document interpretation with verbatim-quote validation and storage | ✅ live | `AiResponseParsingTest.extractedObservationsMustQuoteTheirSourceVerbatim` |
| 2 · Evidence-grounded flag explanation (cached on the flag) | ✅ live and scripted | `GeminiGroundingIntegrationTest.flagExplanationIsValidatedAndCached` |
| 3 · Interactive assistant (multi-turn, context-bound, suggestions, citations) | ✅ live and scripted | `chatSendsOnlyThePatientsRecordsAndStripsFabricatedCitations` (context isolation, history, citation stripping) |
| 4 · Natural-language search interpretation with allow-listed criteria | ✅ live and sanitization tests | `SearchCriteriaTest` |
| 5 · Explainability: facts / interpretation / missing, citation validation, source links | ✅ | `CitationValidatorTest`, UI |
| Safe handling of missing Gemini configuration (labeled, nothing simulated) | ✅ | `ConfigurationHandlingTest`, `aiEndpointsDegradeSafelyWithoutGemini`, UI |
| Live connectivity check endpoint and UI | ✅ | Returns `503 GEMINI_NOT_CONFIGURED` without a key (tested) |

## Priority 3: Snowflake (JDBC driver `net.snowflake:snowflake-jdbc` 4.4.0, key-pair auth)

| Item | Status | Evidence |
|---|---|---|
| Key-pair connection settings, account normalization, Windows path handling | ✅ unit and live | `ConfigurationHandlingTest.snowflakeSettingsNormalizeAccountAndUseKeyPairAuth` |
| Diagnostic failure on missing or partial config; no silent fallback | ✅ | `ConfigurationHandlingTest` |
| Canonical Snowflake DDL (19 tables) applied at startup | ✅ on Snowflake and fallback | `db/schema-snowflake.sql` |
| Idempotent seed with typed batch binds | ✅ on Snowflake and fallback | `repeatedAnalysisDoesNotDuplicateFlagsOrAlerts` (re-seed inserts 0) |
| Read/write integration check (JOIN read + insert + read-back) | ✅ on Snowflake and fallback | `integrationStatusIsHonestAboutFallbackAndDbCheckReadsAndWrites` |
| Account setup script (warehouse, db, schema, role, key registration) | ✅ executed by the team | `database/snowflake/00_account_setup.sql` (not executed) |

## Priority 4: clinical search

| Item | Status | Evidence |
|---|---|---|
| Keyword search with patient, organization, record type and date filters | ✅ | `searchFiltersByKeywordConceptOrganizationTypeAndDate` |
| Concept search (deterministic tags), population filters, repeated-findings threshold | ✅ | same |
| Parameterized SQL only; hostile input neutralized | ✅ | `SearchCriteriaTest.queryBuilderUsesOnlyBoundParameters`, workflow test |
| Gemini summary of results with citations; "discuss results" assistant | ✅ live | UI |
| Ctrl/Cmd+K command search | ✅ | UI |

## Priority 5: pattern detection

| Rule | Status | Evidence |
|---|---|---|
| Recurrent fractures (≥3 in 5 years) | ✅ | `ScreeningRulesTest`, Patient C |
| Repeated out-of-range labs (≥3 per LOINC code in 2 years, cross-organization) | ✅ | `ScreeningRulesTest`, Patient D |
| Repeated symptom encounters (≥3 per category in 1 year) | ✅ | `ScreeningRulesTest`, Patient E |
| No unnecessary flags for patients F, G and H | ✅ | `screeningGeneratesExpectedFlagsAndRoutesObAlerts` |
| Configurable thresholds, labeled as prototype heuristics and shown in the UI | ✅ | `application.yml`, Clinical Alerts page |

## Priority 6: frontend

| Item | Status |
|---|---|
| Dashboard, Patients, Patient Profile (9 tabs), Clinical Search, Clinical Alerts plus detail, Anesthesia OB Alerts (work queue + coordination board) plus workspace, Demo & integrations | ✅ built (`npm run build`) and exercised with headless Chromium |
| Camellia brand theme (coral petals, deep navy, sage, amber), logo recreated as SVG | ✅ |
| Responsive layout | 🟠 Desktop first. Works at tablet widths. The sidebar is hidden on small screens and has no mobile menu yet. |
| Dark mode | ⛔ Not implemented |

## Data and ingestion

| Item | Status |
|---|---|
| 9 synthetic patients (A–I), 5 fictional organizations, 4 source formats, relative dates | ✅ |
| Normalizers: FHIR-like, legacy JSON, HL7 v2 ORU, plain-text dictation | ✅ `NormalizationTest` |
| Master patient index; unmatched identities skipped with a warning | ✅ |
| Simulated incoming HL7 result, then re-evaluation | ✅ |
| Demo reset (re-anchor to today) | ✅ |

## Not implemented (by design for this iteration)

- ⛔ Real authentication (users are simulated demo identities; anyone can switch). Role-based authorization *is*
  enforced server-side for the demo identities.
- ⛔ Care-relationship-based access, break-the-glass access, and an audit-grade access log.
- ⛔ Real EHR connections (Epic or otherwise) and real notifications.
- ⛔ Semantic or vector search.
- ⛔ Persisted assistant conversation history (it is kept in memory for 2 hours; flag explanations and AI observations
  *are* persisted).
- ⛔ A frontend unit-test suite (frontend behavior was verified with a scripted browser walkthrough).

## Commands run during the build

| Command | Result |
|---|---|
| `./mvnw test` (backend, first iteration) | ✅ 38 tests, 0 failures. One real SQL bug was found by the tests and fixed. |
| `./mvnw test` (backend, role-based access iteration) | ✅ **51 tests, 0 failures** (39 existing, updated for sessions and scopes, plus 12 in `RoleBasedAccessIntegrationTest`) |
| `./mvnw -DskipTests package` | ✅ `target/camellia-backend-0.1.0.jar` |
| `npm run build` (frontend: `tsc -b && vite build`) | ✅ |
| Backend started with `CAMELLIA_DB_MODE=local` and the API exercised with curl | ✅ 156 records seeded; 6 flags and 3 alerts; dedup, workflow, re-evaluation and search verified |
| Scripted Chromium walkthrough of Demo 1 (queue → workspace → record drawer → acknowledge → note → status → reload) | ✅ All steps passed, and changes persisted after reload |
| Live Snowflake connection, DDL, seed, read/write check | ✅ Passed |
| Live Gemini API calls (all five capabilities) | ✅ Passed via the fallback model; the primary model is rate limited on the current key |
| Role-based access iteration: `npm run build`, headless Chrome walkthrough as all six users, logo alpha checks | ✅ Passed (details above). Not yet run against Snowflake. |

## V3.1: traits, documents, schedule, readability (2026-10-11)

| Area | Status | How it was verified |
|---|---|---|
| Obesity / morbid obesity traits in Snowflake | Done | `CamelliaV31IntegrationTest`; **live Snowflake**: 4 traits derived on startup (Dolores and Henry: morbid obesity linked to their current stays; Frances: obesity linked to her stay; Caleb: obesity, patient-level), restart did not duplicate |
| Trait search (filter, keyword, natural language) | Done | Tests; live: trait filters return exactly {Dolores, Henry} and {Caleb, Frances}; **live Gemini** mapped "documented morbid obesity" to `MORBID_OBESITY` and "obese patients" to both traits |
| Trait access rules | Done | Tests: coordinator 403, out-of-practice physician 403 and empty search, nothing in the nursing queue |
| PDF / DOCX upload, metadata, view/download, search | Done | Tests; live on Snowflake: PDF (1 page) and DOCX extracted, invalid PDF rejected, anesthesiologist upload 403, out-of-scope file 403, no session 401, downloaded bytes identical, `bariatric` / `walker` found in document results |
| Legacy `.doc` | Done | Unit-level run of the backend's detection and extraction on a real Word 97 file (not uploaded): detected as DOC, text extracted; the same file named `.docx` is rejected. No `.doc` was uploaded through the API |
| Dashboard shift schedule | Done | Tests; live for Jordan (5 shifts, 2 open 4 West shifts) and Taylor (4 shifts, 2 open L&D shifts) |
| Clinical Alerts readability | Done | Same fields and actions, regrouped (title/status, patient/time, reason + details, triggering records + action) |
| Formatting pass (touched screens) | Done | Browser checks at 1440 px and 390 px: no horizontal scroll on dashboard, profile, search, alerts; labeled form fields; conditions table scrolls inside its card; single monitoring card uses full width |
| Browser walkthrough | Done | 41/41 Chromium checks (desktop + mobile), including a UI upload, invalid-file message and stay → upload encounter preselection |

Test totals: **79 backend tests pass** (`./mvnw clean test`), frontend `npm run build` passes.

Not done / limitations: no OCR for scanned PDFs; no document delete, versioning or virus scanning; document files live on
local disk; the runtime-role grants for the two new tables are in `10_…sql` but that script has still not been run.

## Debugging and regression pass (2026-10-11)

| Issue | Root cause | Fix | Verified |
|---|---|---|---|
| Any demo user (and anyone on a public link) could wipe the database with *Reset demo data* | `/api/admin/reset-demo` checked only the deployment flag, not the user's role | New `MANAGE_DEMO_DATA` permission (Clinical Data Coordinator only); the button is shown only to that role | `DemoResetIntegrationTest`; live Snowflake: Maya gets 403; browser: nurse sees no button, Riley's reset works |
| Reset deleted user-added data: imported records/patients, uploaded documents (rows and files), import history | `deleteAllDemoData` emptied every table | Reset deletes workflow state plus only the rows whose ids come from the seed files, then re-seeds; imports, uploads, import history, organizations and identifiers are kept | `DemoResetIntegrationTest` (two resets: no duplicates, imports and both uploads intact, files still downloadable) |
| Clinical Search page and header search shown to the data coordinator, who has no search permission | `/search`, `/flags`, `/flags/:id` had no route guard; the header search bar rendered for every role | Route guards for those pages; header search only with `CLINICAL_SEARCH` (the API already returned 403) | Browser crawl: 7 roles × 16 routes, 0 issues |
| "Back" from a stay opened in the Nursing Monitoring queue went to the patient profile | The back link always pointed to the profile | Queue links carry `from=queue`; Back returns to the queue, with a separate *Patient profile* link | Browser action test |
| An acknowledged nursing review did not say who acknowledged it | Only resolutions were shown | "Acknowledged by … · time" line | Browser action test (persists after reload) |

Checked with no defect found:
- **Authorization:** role × endpoint matrix of 28 endpoints × 7 users. Nursing queue, alert status, repositioning and
  simulator calls are nurse-only; practice scope applies to profiles, records, traits, documents, monitoring and Gemini
  chat; no session → 401.
- **Data integrity (live Snowflake, read-only):** 27 integrity checks with 0 violations — no duplicate records, patients,
  traits, shifts, roster rows, documents or clinicians; every sensor reading, event, intervention, alert, trait and
  document matches its patient, stay and session; interventions inside their stay and by a known user; no duplicate
  open alerts or flags.
- **Nursing scenarios (27 API checks):** upcoming repositioning (due in 25 min) → overdue at the right simulated time
  (125 min, interval 120) → documenting moves the next due time to +120 min; future times rejected; sensor position
  changes never count; mobility review after 245 min, walking raises the count and resolves it; disconnection produces
  only a device review, never inactivity; acknowledgements persist; per-stay clocks; stay reset touches only its stay.

Not present in Camellia (not implemented, not tested): a "Patient Care Needs" page (the nursing workflow is **Nursing
Monitoring**), possible-fall alerts (fall detection is deliberately out of scope), and walking goals such as "1 of 3
walks". Remaining lint: 8 React style warnings (intentional state resets in effects, `Date` in render), no errors.

Tests: **80 backend tests pass** (`./mvnw clean test`); frontend type-check and `npm run build` pass; `oxlint`: 0 errors.

## Hosting and fixes (2026-10-11, afternoon)

The backend moved from a laptop behind a Cloudflare quick tunnel to Render, so https://camellia-snowy.vercel.app works
with no computer on. See the README's *Hosted demo* section and [deploy/render/README.md](deploy/render/README.md).

| Issue | Root cause | Fix | Verified |
|---|---|---|---|
| The public link broke whenever the laptop slept or the tunnel closed | The backend ran on the laptop | Render web service from `backend/Dockerfile` (free plan, JVM sized for 512 MB); Vercel's `/api` proxy points at Render | Live: 7 demo users sign in, every role check matches, pages and assets load, Snowflake connected |
| Render deploys could not find the Snowflake key | The secret file was not attached yet, and a non-root container user may not read `/etc/secrets` | Entrypoint uses the first `.p8`/`.pem` secret file (or `SNOWFLAKE_PRIVATE_KEY`), logs the secret file names when none is found, and runs as root | Deploy logs; health reports `SNOWFLAKE` |
| Briefing audio answered "not available to your account" on Render | Briefings were reused although their audio file was on another machine or erased by a restart; the path check answered 403 | 410 with a regenerate hint; generating makes a new briefing when the audio is not stored on this server | `CamelliaV3IntegrationTest` (deleted audio → 410, then a fresh briefing); live 410 |
| Gemini failed with HTTP 401 | The committed key was disabled after the repository became public: it worked until 06:04 UTC and was rejected from 10:58 UTC, with Google returning "API key not valid" on both Gemini endpoints | New key kept out of the repository (Render environment variable, `backend/.env.local` locally); dead key removed from `backend/.env`; the error names the likely cause | Live: Gemini check and AI search through the UI. Isolated copy with the new key: search, alert chat with a follow-up, patient chat, flag explanation, extraction, search summary and briefing script all pass with cited records |
| The header said "Gemini live" while every call failed | The badge reflected configuration only | Shows "Gemini error" with the reason until a call succeeds | Browser check |
| Anesthesia alert cards and the coordination board looked unclickable | Tailwind v4 gives buttons the arrow cursor; a board card click only updated a summary below the fold | Pointer cursor for enabled buttons app-wide; a board card click scrolls its summary into view; hover highlight | Live browser runs as the CRNA, L&D nurse and anesthesiologist: queue rows, *Review*, board cards, *Open review workspace*, dashboard cards |

Demo risks:
- **Gemini latency:** Google's main model (`gemini-3.8-flash`) answered in 6–60 s under high demand; the backup
  (`gemini-3.5-flash-lite`) answered in 1–3 s. Setting `GEMINI_MODEL=gemini-3.5-flash-lite` on Render trades some
  capability for speed.
- **ElevenLabs quota:** 8,863 of 10,000 free characters used; it resets on 2026-11-10. When it runs out, briefings
  still show their transcript, with a note that the audio failed.
- **ElevenLabs key** is still in the committed `backend/.env`, so it is public and could be disabled like the Gemini key.
- **Render free plan:** sleeps when idle and keeps uploads and briefing audio only until it restarts.
