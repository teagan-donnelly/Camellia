# Role-Based Access — Progress & Continuation Plan

_Working notes for the "ADDITIONAL REQUIREMENTS: ROLE-BASED USER EXPERIENCES" iteration._
_Last updated: 2026-10-10. Work paused at the user's request partway through the backend._

> **Teammates:** the code described here is on the branch **`feature/role-based-access`**, not on `main`.
> It is unfinished: tests are not updated yet, and the current frontend cannot sign in to it. Keep using `main` to run
> the app until that branch is completed and merged.

---

## 0. Where things stand (read this first)

| Item | State |
|---|---|
| Git branch | `feature/role-based-access`, created from `feature/first-iteration` @ `ca874b7` (that commit equals `origin/main`) |
| Commits on this branch | **None yet.** Everything below is uncommitted in the working tree. |
| Backend compile | `./mvnw -q compile` printed no errors after the controller rewrite, but a full `./mvnw compile` was interrupted before it confirmed `BUILD SUCCESS`. **Re-verify first.** |
| Backend tests | **Not updated yet. They will not compile** (they still reference the deleted `config/DemoUsers` and old method signatures). |
| Frontend | **Not started** for this iteration. It still sends the old `X-Camellia-User` header, which the backend no longer reads, so the UI will get `401 DEMO_SESSION_REQUIRED` until it is updated. |
| Logo transparency | Not started |
| Docs (README, USER_GUIDE, ARCHITECTURE_DECISIONS, IMPLEMENTATION_STATUS, DEMO_SCRIPT) | Not updated for this iteration |
| Running servers | A backend from **before** these changes may still be running on :8080 (nohup), with Vite on :5173. Restart the backend after finishing the code. |
| Snowflake | Live account `uyc13421.us-east-1`, user `CAMELLIA_SVC`, role `CAMELLIA_APP`. The new tables are created automatically on next startup (`CREATE TABLE IF NOT EXISTS`), and practice links are seeded into the existing DB idempotently. |

**Operational gotchas**
- Never use `pkill -f <pattern>`: it matches and kills the shell running it. Stop the backend by port:
  `kill $(ss -ltnp | awk '/:8080 /{print $NF}' | grep -o 'pid=[0-9]*' | cut -d= -f2)`
- Tests use H2 (`camellia.database.mode=local`) and override Gemini/Snowflake config. They never touch Snowflake.
- Pushing to GitHub happens only when the user asks.

---

## 1. Decisions already made

1. **Practices.** Two new tables, nothing existing altered:
   - `PRACTICES(PRACTICE_ID, NAME, ORG_ID, SPECIALTY, IS_SYNTHETIC)`
   - `PATIENT_PRACTICE_ASSOCIATIONS(PATIENT_ID, PRACTICE_ID, RELATIONSHIP, REGISTERED_AT)`
   - **PRAC-A**: Larkspur Regional Primary Care (Practice A), org LRMC. Patients C, D, E, F, H (`CAM-P-1003/1004/1005/1006/1008`).
   - **PRAC-B**: Bellwether Women's Health Associates (Practice B), org BWHA. Patients A, B, G, I (`CAM-P-1001/1002/1007/1009`).
2. **Demo users** (`security/DemoDirectory.java`; default user `maya.ellison`):

   | id | Name | Role | Scope |
   |---|---|---|---|
   | alex.kim | Dr. Alex Kim | PHYSICIAN | Practice A only |
   | sarah.mitchell | Dr. Sarah Mitchell | PHYSICIAN | Practice B only |
   | jordan.lee | Jordan Lee, RN | REGISTERED_NURSE | all patients |
   | taylor.morgan | Taylor Morgan, RN | LD_NURSE | all patients |
   | maya.ellison | Maya Ellison, CRNA | CRNA | all patients |
   | daniel.brooks | Dr. Daniel Brooks | ANESTHESIOLOGIST | all patients |

3. **Permissions** (`security/Role.java`):
   - Everyone: VIEW_PATIENTS, CLINICAL_SEARCH, CLINICAL_ALERTS, GEMINI_ASSISTANT.
   - REVIEW_FLAGS (change flag review status): physician, CRNA, anesthesiologist. *Not* RN or L&D nurse.
   - ANESTHESIA_OB_ALERTS: L&D nurse, CRNA, anesthesiologist.
   - UPDATE_COORDINATION (acknowledge, status, contact, toggles): CRNA and anesthesiologist only.
   - ADD_COORDINATION_NOTES: L&D nurse, CRNA, anesthesiologist.
4. **Anesthesia OB data is part of the restricted workflow.** Flags with rule `OB_ANESTHESIA_REVIEW` and all coordination alerts are hidden from users without ANESTHESIA_OB_ALERTS everywhere: lists, flag search, record search `openFlagsOnly`, dashboard, patient profile, record "cited as evidence by", rule descriptors, analysis-run outcomes, the activity feed and Gemini contexts.
5. **Identity.** `POST /api/session {userId}` issues an HMAC-signed, HttpOnly, SameSite=Strict cookie `CAMELLIA_DEMO_SESSION` (12-hour TTL). The secret comes from `CAMELLIA_DEMO_SESSION_SECRET`; if unset, a random secret is generated per startup and users re-select after a restart. The `X-Camellia-User` header is no longer trusted. This is **demo impersonation, not authentication**, and must be documented as such.
6. **Denials** return `403 ACCESS_DENIED` with a generic message, identical for missing and restricted ids, so patient existence is never revealed. A missing or invalid session returns `401 DEMO_SESSION_REQUIRED`.
7. **Navigation** (from `GET /api/session` → `navigation`): DASHBOARD, PATIENTS, CLINICAL_SEARCH, CLINICAL_ALERTS, plus ANESTHESIA_OB_ALERTS for L&D, CRNA and anesthesiologist. The standalone Care Coordination page goes away; its board becomes a tab inside Anesthesia OB Alerts. "Clinical Flags" is renamed "Clinical Alerts" in the UI (backend paths stay `/api/flags…`).
8. **The integration DB check** now reads reference data (organizations × practices), not a patient. `patientCount` was removed from `/api/integrations/status`.

---

## 2. Completed so far (uncommitted)

### New files
- `backend/src/main/java/com/camellia/security/`
  - `Permission.java`, `Role.java`, `DemoUser.java`, `DemoDirectory.java`
  - `DemoSessionService.java`: HMAC sign and verify
  - `CurrentUserArgumentResolver.java`: injects `DemoUser` into controller methods; 401 if no session
  - `AuthorizationService.java`: `scope(user)` returns `AccessScope(userId, patientIds|null, anesthesia)`, plus `canViewPatient`, `canAccessAnesthesiaAlerts`, `canViewClinicalFlag`, `canViewAlert`, `canUpdateCoordinationStatus`, `canAddCoordinationNote`, `canUpdateFlagStatus`, `canUseClinicalSearch` and the `require*` variants
- `backend/src/main/java/com/camellia/controller/SessionController.java`: `GET /api/session/users`, `GET /api/session`, `POST /api/session`, `DELETE /api/session`
- `backend/src/main/java/com/camellia/model/Practice.java`
- `backend/src/main/java/com/camellia/repository/PracticeRepository.java`
- `backend/src/main/java/com/camellia/search/FlagSearchCriteria.java`: allow-listed flag criteria (text, categories, statusGroup OPEN/CLOSED/ANY, statuses, priorities, specialties, concepts, patientId), plus `sanitize()` that reports when a restricted category was requested
- `backend/src/main/java/com/camellia/search/FlagSearchService.java`: parameterized SQL over flags ⨝ patients ⟕ alerts, with scope and anesthesia restrictions applied in SQL; `facets(scope)`
- `backend/src/main/resources/seed/practices.json`

### Modified files
- `db/schema-snowflake.sql`: added PRACTICES and PATIENT_PRACTICE_ASSOCIATIONS.
- `seed/patients.json`: each patient has `"practices": [{practiceId, relationship}]`.
- `ingestion/DemoDataService.java`: seeds practices and associations idempotently (also upgrades the existing Snowflake DB).
- `repository/SupportRepository.java`: demo reset also deletes the two new tables.
- `config/CamelliaProperties.java` and `application.yml`: `camellia.demo.session-secret` (`CAMELLIA_DEMO_SESSION_SECRET`).
- `config/WebConfig.java`: registers the argument resolver; CORS allows credentials and DELETE.
- **Deleted:** `config/DemoUsers.java`, `controller/CurrentUser.java`.
- `coordination/CoordinationService.java`: uses `AuthorizationService` (status needs UPDATE_COORDINATION; notes need ADD_COORDINATION_NOTES; flag status needs REVIEW_FLAGS). Note author role is `user.roleDescription()`.
- `service/ClinicalViewService.java`:
  - `Lookup(AccessScope)` pre-filters patients, flags, alerts, evidence, appointments and records.
  - Every public method now takes `AccessScope`: `patientDirectory`, `patientProfile`, `recordDetail(ClinicalRecord, scope)`, `flagList`, `flagDetail`, `alertList`, `alertDetail`, `dashboard`.
  - The dashboard adds `anesthesiaAccess` and `upcomingEncounters`, hides network-wide `lastAnalysisRun` unless the user is network-wide with anesthesia access, and filters activity through `visibleActivity()`.
- `dto/Dtos.java`: `DashboardDto` has 2 new fields; new `UpcomingEncounterDto`.
- `search/SearchService.java`: `search(criteria, scope)`; patient IN-list in SQL; `openFlagsOnly` excludes the anesthesia rule when needed. The `buildQuery(c, now)` overload is kept for tests.
- `search/RuleBasedQueryInterpreter.java`: new `interpretFlags(query, patientId)`.
- `ai/ClinicalAiService.java`:
  - `chat(req, DemoUser)` and `resolveContext(..., user)` authorize before retrieval. SEARCH context drops inaccessible records and returns 403 if none remain. The conversation key is `userId@context`.
  - `explainFlag(id, refresh, user)`, `summarizeSearch(query, ids, user)` (filters records) and `extract(patientId, user)` all take the user.
  - `interpretSearch(query, patientId, user)` returns `Interpretation(target RECORDS|FLAGS, criteria, flagCriteria, source, model, explanation, latencyMs, notice)`. Only accessible patients and allowed flag categories are sent to Gemini.
- `ai/AiSchemas.java`: `searchCriteria(..., flagCategories)` adds `searchTarget`, `flagCategories`, `flagStatusGroup`, `flagPriorities`, `flagSpecialties`.
- `ai/Prompts.java`: INTERPRET_SEARCH guidance for flag search.
- **Controllers rewritten** with a `DemoUser user` parameter: `PatientController`, `SearchController`, `AiController`, `FlagController`, `AlertController`, `DashboardController`, `SystemController`.
  - `FlagController` adds `GET /api/flags/search`, `POST /api/flags/search`, `GET /api/flags/facets` and filters the rules list.
  - `FlagController.scoped(...)` filters analysis-run outcomes; it is also used by the admin endpoints.
- `integration/IntegrationService.java`: removed `patientCount`; the read check is now non-patient.

---

## 3. Remaining plan (in order)

### Step 1: Verify backend compiles
- `cd backend && ./mvnw compile`. Fix any errors. Likely suspects are unused imports or leftovers in `SystemController` (`OrganizationRepository` and `Organization` are still used for meta) and `IntegrationService` (`patients` field now unused, which is fine).
- `grep -rn "DemoUsers\|X-Camellia-User" backend/src` should return nothing in main code.

### Step 2: Update existing tests
- `workflow/ClinicalWorkflowIntegrationTest.java`:
  - Replace `DemoUsers` with `com.camellia.security.DemoDirectory.require("maya.ellison")`. The old JORDAN (L&D team) becomes `taylor.morgan`, an L&D nurse who can add notes but gets 403 on status changes. Update the expected message (no longer "Simulated access control"; assert `ApiException` with status FORBIDDEN).
  - `search.search(criteria)` becomes `search.search(criteria, auth.scope(user))`.
  - Every MockMvc request needs a session cookie. Add a helper: `POST /api/session {"userId":"maya.ellison"}`, read the `Set-Cookie` header, then `.cookie(new Cookie("CAMELLIA_DEMO_SESSION", value))`.
  - `/api/integrations/database/check`: the `readCheck` shape changed; `writeCheck.verified` is still true.
  - The `/api/patients/CAM-P-9999` expectation changes from 404 to **403**.
- `ai/GeminiGroundingIntegrationTest.java`: `ai.chat(req, DemoDirectory.require("maya.ellison"))` and `ai.explainFlag(id, false, user)`.
- `search/SearchCriteriaTest.java` should still compile (it uses the `buildQuery(c, now)` overload).

### Step 3: New `RoleBasedAccessIntegrationTest` (MockMvc, H2, scripted Gemini gateway like `GeminiGroundingIntegrationTest`)
- **A.** `alex.kim`: `GET /api/patients` returns exactly the PRAC-A ids (1003, 1004, 1005, 1006, 1008).
- **B.** `alex.kim`: these all return 403 `ACCESS_DENIED`:
  - `GET /api/patients/CAM-P-1001`, `/api/patients/CAM-P-1001/records`, `/api/records/REC-NDL-ACC-55012-1`
  - `POST /api/patients/CAM-P-1001/ai-extraction`
  - `GET /api/patients/CAM-P-9999`, with the same body as a restricted patient
- **C.** `jordan.lee`: 9 patients. `GET /api/coordination-alerts` returns 403. Flags exclude `OB_ANESTHESIA_REVIEW`. `PATCH /api/flags/{id}/status` returns 403 (no REVIEW_FLAGS).
- **D.** `daniel.brooks`: `GET /api/session` navigation includes ANESTHESIA_OB_ALERTS; alerts list has 3; acknowledge, status change and note all succeed.
- **E.** `alex.kim` and `sarah.mitchell`: navigation lacks ANESTHESIA_OB_ALERTS. `/api/coordination-alerts`, `/{id}`, `PATCH` and `/notes` all return 403. `GET /api/flags/{obFlagId}` returns 403. Sarah's `/api/flags` contains no OB flag even though Ava is her patient.
  - `taylor.morgan`: can view alerts and add a note; `PATCH` status returns 403.
- **F.** `jordan.lee`: `GET /api/flags/search?category=RECURRENT_FRACTURE` returns Caleb. `?concept=CALCIUM` returns Dolores. Maya with `?concept=LOW_PLATELETS&statusGroup=OPEN` returns Ava with `link=/alerts/...`.
- **G.** `alex.kim`: `?q=marlowe` returns empty; `?category=OB_ANESTHESIA_REVIEW` returns empty with a notice; `?patientId=CAM-P-1001` returns empty. Sarah: `?category=OB_ANESTHESIA_REVIEW` returns empty with the notice. NL interpret for Sarah, "show unresolved obstetric anesthesia alerts", then execute, returns no OB flags.
- **H.** With the scripted gateway as `alex.kim`:
  - chat with PATIENT `CAM-P-1001`, FLAG and ALERT contexts each return 403, and **no gateway request is recorded**;
  - SEARCH context with only Ava's record ids returns 403 with no request;
  - the interpret prompt (captured) does not contain "Marlowe" or `CAM-P-1001`, and its flag-category enum lacks `OB_ANESTHESIA_REVIEW`;
  - a chat about Caleb (`CAM-P-1003`) succeeds, and its prompt contains no Practice B patient names.
- **I.** The same endpoints under different users give different results:
  - dashboard `totalPatients`: alex 5, sarah 4, jordan 9;
  - `unreviewedAnesthesiaAlerts` and `upcomingComplexEncounters` are 0 for non-anesthesia users;
  - record search for "platelet" returns Ava's records for jordan, none for alex;
  - navigation differs per user.
- Also: requests without a cookie return 401; a tampered cookie returns 401.

### Step 4: Frontend
1. `src/api/client.ts`:
   - Remove the `X-Camellia-User` header (fetch sends same-origin cookies by default).
   - On `401 DEMO_SESSION_REQUIRED`, call `POST /api/session {userId: localStorage 'camellia.user' || 'maya.ellison'}` and retry once.
2. `src/api/types.ts`: add
   - `Session {user:{id,name,role,roleLabel,department,practices[],patientScope}, permissions[], navigation[], simulated, notice}`;
   - `FlagHit`, `FlagSearchResult`, `Facets`;
   - `Interpretation.target` and `flagCriteria`;
   - `DashboardDto.anesthesiaAccess` and `upcomingEncounters`.
3. `src/state/AppContext.tsx`:
   - Replace the hard-coded `DEMO_USERS` with `GET /api/session/users`.
   - Load the session on start.
   - `switchUser(id)`: `POST /api/session`, then `queryClient.clear()`, persist the id, and navigate to `/` if the current route is not permitted.
   - Expose `session`, `can(permission)` and `nav`.
4. `src/components/Layout.tsx`:
   - Sidebar built from `session.navigation`. Rename "Clinical Flags" to **Clinical Alerts**. Remove the Care Coordination item. Fetch the Anesthesia OB badge count only if permitted.
   - User switcher: name, role label, department/practice, a clear **"Simulated demo identity — not a secure login"** label, and fast one-click switching.
5. `src/App.tsx`:
   - Route guard component for `/alerts` and `/alerts/:id`; render a restricted message if the session lacks ANESTHESIA_OB_ALERTS.
   - `/coordination` redirects to `/alerts?tab=board`.
6. `pages/AnesthesiaAlerts.tsx`: tabs **Work queue | Coordination board**. Move `CareCoordination.tsx`'s board and summary into the board tab (reuse the component; delete the sidebar page).
7. `pages/ClinicalFlags.tsx` (now "Clinical Alerts"):
   - Filter bar: search text, category (from `/api/flags/facets`), status group/status, priority, specialty. Calls `GET /api/flags/search`.
   - Result cards show patient, title, category, status (alert status if any), priority, evidence count, specialties, source-record chips, and a link (`hit.link`).
8. `pages/ClinicalSearch.tsx`: if `interp.target === 'FLAGS'`, `POST /api/flags/search` with `interp.flagCriteria` and render a FlagHit list. Optionally summarize the triggering source records with `summarizeSearch`. Show `result.notice` (e.g. restricted category). Add flag example chips: "Which patients have open clinical alerts?", "Find all patients flagged for low platelets", "Show unresolved obstetric anesthesia alerts".
9. `pages/Dashboard.tsx`:
   - If `!anesthesiaAccess`: hide the OB metrics and queue. Show cards for accessible patients, open clinical alerts, patients requiring review and upcoming encounters (`upcomingEncounters`).
   - Show "Viewing as … — scope: …".
10. `pages/PatientProfile.tsx`, `FlagDetail.tsx`, `AlertWorkspace.tsx`:
    - On 403, show an "Not available to your role" panel.
    - Review actions only with REVIEW_FLAGS. Coordinate controls only with UPDATE_COORDINATION; notes with ADD_COORDINATION_NOTES.
    - Add the user id to the `AssistantPanel` context key so switching users resets the chat.
11. `pages/SystemStatus.tsx`: the `readCheck` shape changed. Keep it reachable from the sidebar bottom ("Demo & integrations").
12. `npm run build` must pass.

### Step 5: Logo transparency (requirement 8)
- Back up the originals: `assets/camellia-logo-original.png` and `frontend/public/camellia-logo-original.png`.
- PNG (927×625 RGB, white background, blue screenshot selection lines at the top and bottom edges): with PIL, apply color-to-alpha against white (`alpha = max(255 - c)/255`, un-premultiply the color to avoid halos), remove the blue border lines by cropping or clearing those rows, then trim to the content bounding box with padding. Save RGBA to both paths. Keep the pink petals and wordmark intact.
- SVG mark (`frontend/src/components/Logo.tsx` and `frontend/public/favicon.svg`): replace the `<circle fill="#fff">` center with a `<mask>` (white rect, black center circle), so the center is transparent on navy and white alike.
- **Test J:** a script that checks center and corner alpha == 0 in the PNG and that no `fill="#fff"` remains in the SVGs. Render composites on navy `#142136` and white, then view the screenshots.

### Step 6: Docs
- **README.md:** roles and access table, how to switch demo users, new flag search, updated feature table and nav, limitations (simulated identity).
- **USER_GUIDE.md:** the user switcher, per-role navigation, Clinical Alerts filters, flag search in Clinical Search, the board tab within Anesthesia OB Alerts, and restricted messages.
- **ARCHITECTURE_DECISIONS.md:** new sections for the authorization layer (scope computed once and applied in SQL; generic 403), demo sessions (HMAC cookie; why not trust headers), practice tables and the minimal schema change, the anesthesia-rule restriction, Gemini retrieval filtering, flag search, the nav/alert consolidation, and the logo.
- **IMPLEMENTATION_STATUS.md:** new rows with real test results.
- **DEMO_SCRIPT.md:** add the role-switching segment (Maya → Dr. Kim → Dr. Mitchell → Jordan → Taylor → Dr. Brooks).

### Step 7: Verify end to end
1. `./mvnw test`: all old and new tests must pass. Report actual counts.
2. `npm run build`.
3. Restart the backend on Snowflake. Confirm the log shows practices seeded and `Camellia ready on Snowflake`.
4. Run a Playwright script from the scratchpad (`pw/` already has `playwright-core`; Chromium at `/usr/bin/chromium`) that switches users and checks:
   - the sidebar items per role;
   - patient counts;
   - `/alerts` restricted for physicians;
   - flag search results;
   - dashboard differences;
   - the logo on navy.
5. Commit on `feature/role-based-access`. Push or merge only when the user asks.

### Step 8: Final report to the user (12 deliverables)
Cover:
- a summary of RBAC;
- who sees which patients;
- the nav per role;
- how simulated switching works;
- a flag search demo;
- the modified alert workflow;
- the logo fix;
- backend enforcement points;
- real test results;
- the docs updated;
- remaining security limitations: simulated identity, anyone can switch users, network-wide nurse access is a prototype simplification, in-memory conversations, no audit-grade logging, not HIPAA;
- a judge walkthrough.

---

## 4. Endpoint enforcement map (as implemented)

| Endpoint | Check |
|---|---|
| `GET /api/session/users`, `POST/DELETE /api/session` | public (demo user picker) |
| `GET /api/session` | valid session |
| `GET /api/dashboard` | scope-filtered via `Lookup(scope)` |
| `GET /api/patients` | VIEW_PATIENTS, scope filter |
| `GET /api/patients/{id}`, `/records`, `/appointments` | `requirePatient` |
| `POST /api/patients/{id}/ai-extraction` | GEMINI_ASSISTANT + `requirePatient` |
| `GET /api/records/{id}` | record → `requirePatient`; back-references filtered |
| `GET /api/search`, `POST /api/search/execute` | CLINICAL_SEARCH; patient IN-list in SQL |
| `POST /api/search/interpret` | CLINICAL_SEARCH; only accessible patients and allowed categories sent to Gemini |
| `GET/POST /api/flags/search`, `GET /api/flags/facets`, `GET /api/flags` | CLINICAL_ALERTS; scope and anesthesia rule in SQL / Lookup |
| `GET /api/flags/{id}` | `requireFlag` |
| `PATCH /api/flags/{id}/status` | `requireFlag` + REVIEW_FLAGS |
| `GET /api/analysis/rules`, `POST /api/analysis/run` | CLINICAL_ALERTS; outcomes and rule list filtered |
| `/api/coordination-alerts/**` | `requireAnesthesiaAlerts` / `requireAlert`; PATCH needs UPDATE_COORDINATION; notes need ADD_COORDINATION_NOTES |
| `POST /api/ai/chat` | GEMINI_ASSISTANT; context authorized before retrieval; conversation bound to user |
| `POST /api/ai/explain-flag` | `requireFlag` |
| `POST /api/ai/search-summary` | CLINICAL_SEARCH; inaccessible records dropped before Gemini |
| `/api/integrations/*`, `/api/meta` | no patient data returned |
| `/api/admin/*` | valid session; responses contain only counts and scoped outcomes |
