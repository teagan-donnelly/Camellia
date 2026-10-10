# Implementation Status

_Last updated: 2026-10-10 (first iteration)._

**Legend**
- ✅ **Implemented and tested.** Covered by automated tests and/or exercised end to end through the running app.
- 🟡 **Implemented but unverified.** The code is complete and compiles, but it has not been exercised against the real
  external service.
- 🟠 **Partially implemented.**
- ⛔ **Not implemented.**

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
| Configurable thresholds, labeled as prototype heuristics and shown in the UI | ✅ | `application.yml`, Clinical Flags page |

## Priority 6: frontend

| Item | Status |
|---|---|
| Dashboard, Patients, Patient Profile (9 tabs), Clinical Search, Clinical Flags plus detail, Anesthesia OB Alerts queue plus workspace, Care Coordination, System & integrations | ✅ built (`npm run build`) and exercised with headless Chromium |
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

- ⛔ Real authentication and authorization (users are simulated).
- ⛔ Real EHR connections (Epic or otherwise) and real notifications.
- ⛔ Semantic or vector search.
- ⛔ Persisted assistant conversation history (it is kept in memory for 2 hours; flag explanations and AI observations
  *are* persisted).
- ⛔ A frontend unit-test suite (frontend behavior was verified with a scripted browser walkthrough).

## Commands run during the build

| Command | Result |
|---|---|
| `./mvnw test` (backend) | ✅ 38 tests, 0 failures. One real SQL bug was found by the tests and fixed. |
| `./mvnw -DskipTests package` | ✅ `target/camellia-backend-0.1.0.jar` |
| `npm run build` (frontend: `tsc -b && vite build`) | ✅ |
| Backend started with `CAMELLIA_DB_MODE=local` and the API exercised with curl | ✅ 156 records seeded; 6 flags and 3 alerts; dedup, workflow, re-evaluation and search verified |
| Scripted Chromium walkthrough of Demo 1 (queue → workspace → record drawer → acknowledge → note → status → reload) | ✅ All steps passed, and changes persisted after reload |
| Live Snowflake connection, DDL, seed, read/write check | ✅ Passed |
| Live Gemini API calls (all five capabilities) | ✅ Passed via the fallback model; the primary model is rate limited on the current key |
