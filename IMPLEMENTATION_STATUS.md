# Implementation Status

_Last updated: 2026-10-10 (first iteration)._

**Legend**
- ✅ **Implemented and tested.** Covered by automated tests and/or exercised end to end through the running app.
- 🟡 **Implemented but unverified.** The code is complete and compiles, but it has not been exercised against the real
  external service.
- 🟠 **Partially implemented.**
- ⛔ **Not implemented.**

## Blockers

| Blocker | Impact | What is needed |
|---|---|---|
| No `backend/.env` with Snowflake key-pair credentials was available during the build | The Snowflake JDBC path (connect, DDL, seeding, queries, writes) has **not yet run against a real Snowflake account**. Everything was verified on the labeled local fallback, which uses the same SQL, repositories and services. | Add the `SNOWFLAKE_*` values to `backend/.env` (see README → Snowflake setup) and start the backend. Then run **System & integrations → Run read / write check**. |
| No `GEMINI_API_KEY` was available during the build | **No live Gemini call has been made yet.** The Gemini pipeline was verified with a scripted gateway in tests, and the no-key behavior was verified live. | Add `GEMINI_API_KEY` to `backend/.env`, restart, and click **Send live test request**. If the model name is rejected, set `GEMINI_MODEL`. |

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
| Persistence after refresh and backend restart | ✅ on local fallback · 🟡 on Snowflake | UI reload test; DB-backed by design |
| Duplicate prevention; re-evaluation updates evidence and preserves human status | ✅ | `repeatedAnalysisDoesNotDuplicateFlagsOrAlerts`, `newEvidenceUpdatesExistingAlertWithoutDuplicating` |
| Care Coordination shared summary (facts / AI / human notes / planning status) | ✅ | UI walkthrough |
| Simulated team-based access control | ✅ | workflow test (403 for other teams) |

## Priority 2: Gemini (Google Gen AI Java SDK `com.google.genai:google-genai` 1.76.0, default model `gemini-3.8-flash`)

| Capability | Status | Evidence |
|---|---|---|
| Gateway: SDK client, JSON schema output, timeouts, error mapping, usage stats | 🟡 | Compiles against SDK 1.76.0. The not-configured path is tested. No live call yet. |
| 1 · Clinical document interpretation with verbatim-quote validation and storage | 🟡 pipeline · ✅ validation | `AiResponseParsingTest.extractedObservationsMustQuoteTheirSourceVerbatim` |
| 2 · Evidence-grounded flag explanation (cached on the flag) | 🟡 live · ✅ with scripted gateway | `GeminiGroundingIntegrationTest.flagExplanationIsValidatedAndCached` |
| 3 · Interactive assistant (multi-turn, context-bound, suggestions, citations) | 🟡 live · ✅ with scripted gateway | `chatSendsOnlyThePatientsRecordsAndStripsFabricatedCitations` (context isolation, history, citation stripping) |
| 4 · Natural-language search interpretation with allow-listed criteria | 🟡 live · ✅ sanitization and rule-based fallback | `SearchCriteriaTest` |
| 5 · Explainability: facts / interpretation / missing, citation validation, source links | ✅ | `CitationValidatorTest`, UI |
| Safe handling of missing Gemini configuration (labeled, nothing simulated) | ✅ | `ConfigurationHandlingTest`, `aiEndpointsDegradeSafelyWithoutGemini`, UI |
| Live connectivity check endpoint and UI | 🟡 | Returns `503 GEMINI_NOT_CONFIGURED` without a key (tested) |

## Priority 3: Snowflake (JDBC driver `net.snowflake:snowflake-jdbc` 4.4.0, key-pair auth)

| Item | Status | Evidence |
|---|---|---|
| Key-pair connection settings, account normalization, Windows path handling | ✅ unit · 🟡 live | `ConfigurationHandlingTest.snowflakeSettingsNormalizeAccountAndUseKeyPairAuth` |
| Diagnostic failure on missing or partial config; no silent fallback | ✅ | `ConfigurationHandlingTest` |
| Canonical Snowflake DDL (19 tables) applied at startup | 🟡 on Snowflake · ✅ translated on fallback | `db/schema-snowflake.sql` |
| Idempotent seed with typed batch binds | 🟡 on Snowflake · ✅ on fallback | `repeatedAnalysisDoesNotDuplicateFlagsOrAlerts` (re-seed inserts 0) |
| Read/write integration check (JOIN read + insert + read-back) | 🟡 on Snowflake · ✅ on fallback | `integrationStatusIsHonestAboutFallbackAndDbCheckReadsAndWrites` |
| Account setup script (warehouse, db, schema, role, key registration) | 🟡 | `database/snowflake/00_account_setup.sql` (not executed) |

## Priority 4: clinical search

| Item | Status | Evidence |
|---|---|---|
| Keyword search with patient, organization, record type and date filters | ✅ | `searchFiltersByKeywordConceptOrganizationTypeAndDate` |
| Concept search (deterministic tags), population filters, repeated-findings threshold | ✅ | same |
| Parameterized SQL only; hostile input neutralized | ✅ | `SearchCriteriaTest.queryBuilderUsesOnlyBoundParameters`, workflow test |
| Gemini summary of results with citations; "discuss results" assistant | 🟡 live · ✅ labeled fallback | UI |
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
| Live Snowflake connection | 🟡 Not run (no credentials yet) |
| Live Gemini API call | 🟡 Not run (no API key yet) |
