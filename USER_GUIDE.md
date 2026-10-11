# Camellia User Guide

A walkthrough of every feature, written for a first-time user. Everything described here exists in the app today,
including the role-based experience: what you see depends on the simulated demo user you pick (see
[§16](#16-switching-the-simulated-user-and-what-each-role-sees)).

> **Demo application using synthetic patient information. Not for clinical use.** All patients, providers and
> organizations are fictional. Users and roles are simulated demo identities. There is no real login.

---

## Contents

1. [Opening the application](#1-opening-the-application)
2. [Finding your way around](#2-finding-your-way-around)
3. [Dashboard](#3-dashboard)
4. [Finding patients](#4-finding-patients)
5. [Reading a patient's history](#5-reading-a-patients-history)
6. [Inspecting an original source record](#6-inspecting-an-original-source-record)
7. [Searching records](#7-searching-records)
8. [Clinical Alerts](#8-clinical-alerts)
9. [Anesthesia OB Alerts: the work queue](#9-anesthesia-ob-alerts-the-work-queue)
10. [Reviewing an alert: the workspace](#10-reviewing-an-alert-the-workspace)
11. [Recording care coordination](#11-recording-care-coordination)
12. [Using the Gemini assistant](#12-using-the-gemini-assistant)
13. [Gemini document interpretation (AI observations)](#13-gemini-document-interpretation-ai-observations)
14. [Coordination board](#14-coordination-board)
15. [Demo & integrations](#15-demo--integrations)
16. [Switching the simulated user and what each role sees](#16-switching-the-simulated-user-and-what-each-role-sees)
17. [Exploring the broader search and pattern features](#17-exploring-the-broader-search-and-pattern-features)
18. [Troubleshooting](#18-troubleshooting)
19. [Camellia Briefing (audio shift handoff)](#19-camellia-briefing-audio-shift-handoff)
20. [Wearable monitoring in a patient's hospital stay](#20-wearable-monitoring-in-a-patients-hospital-stay)
21. [Nursing Monitoring queue and documenting repositioning](#21-nursing-monitoring-queue-and-documenting-repositioning)
22. [Simulated Device Controls](#22-simulated-device-controls)
23. [Importing patient records](#23-importing-patient-records)
24. [What the database badge means](#24-what-the-database-badge-means)

---

## 1. Opening the application

1. Start the backend (`backend/` → `.\mvnw.cmd spring-boot:run` on PowerShell or `./mvnw spring-boot:run` on Git Bash)
   and wait for the log line `Camellia ready on …`.
2. Start the frontend (`frontend/` → `npm run dev`).
3. Open **http://localhost:5173**.

On first start the backend creates the tables, loads the synthetic records from five fictional organizations and runs
the screening engine. Three Anesthesia OB alerts and three other clinical flags are then waiting for review.

A dark banner across the top of every page reads *"Demo application using synthetic patient information. Not for
clinical use."*

## 2. Finding your way around

**Left sidebar** (items depend on your role)

| Item | Who sees it | Purpose |
|---|---|---|
| Dashboard | Everyone | Overview: metrics, upcoming encounters, patients requiring review, recent activity |
| Patients | Everyone | Directory of the synthetic patients you may access |
| Clinical Search | Everyone | Natural-language and keyword search over records and clinical alerts you may access |
| Clinical Alerts | Everyone | Rule-generated review items, with filters (formerly *Clinical Flags*) |
| Anesthesia OB Alerts | L&D nurses, CRNAs, anesthesiologists | The anesthesia coordination **work queue** and **coordination board**. The coral badge counts **New** alerts. |
| Demo & integrations (bottom) | Everyone | Snowflake and Gemini status, live checks, demo data controls |

If you open a page or item your role can't use (for example from an old link), you'll see a **Not available to your
role** panel instead. It is deliberately generic: it doesn't say whether the item exists.

**Top bar**

- **Search bar.** Type a question and press Enter to run an AI search on the Clinical Search page. Press **Ctrl+K**
  (Windows/Linux) or **⌘K** (macOS) from any page to focus it. On the Clinical Search page the shortcut focuses that
  page's larger search box instead.
- **Integration badges.** *Snowflake* (green) or *Local fallback DB* (amber), and *Gemini live* (coral) or *Gemini not
  configured* (amber). Click either badge to open Demo & integrations.
- **User menu.** Shows the simulated user, a *Demo* tag, their role and their practice or department. See [§16](#16-switching-the-simulated-user-and-what-each-role-sees).

Notifications appear as small toasts in the bottom-right corner after every save.

## 3. Dashboard

**What it's for:** a morning overview. It shows who needs attention, before which encounter, and what changed
recently, limited to the patients and alerts your role may access. A line under the greeting reads *"Viewing as … —
scope: …"*.

**What you see (anesthesia roles: L&D nurse, CRNA, anesthesiologist)**

- **Metric cards** (all values come from the backend; click a card to jump to the related page):
  *Total patients*, *Open clinical flags* (unreviewed or in review), *Unreviewed anesthesia alerts* (status New, with
  the number in progress underneath) and *Upcoming complex-care encounters* (future encounters with an open alert).
- **Upcoming complex-care encounters.** Each row shows the days until the encounter (amber when 21 days or fewer), the
  patient, alert status, encounter type and date, the concern categories (e.g. *Hematologic* or *Cardiac · Oncology ·
  Anticoagulation*), the review ordering badge and the review due date. Click a row to open the
  [review workspace](#10-reviewing-an-alert-the-workspace).
- **Patients requiring review.** Patients with open flags or alerts, with counts. Click one to open the patient profile.
- **Recent activity.** A timeline of screening runs, routed alerts, status changes, notes, and Gemini requests, stored
  in the database.
- **How Camellia works.** A five-step explanation, plus the last screening run and the demo date anchor (shown only to
  network-wide anesthesia roles, because the run covers every patient).

**What you see (physicians and registered nurses)**

- **Metric cards:** *Accessible patients*, *Open clinical alerts*, *Patients requiring review* and *Upcoming
  encounters* (next 60 days). There are no Anesthesia OB metrics.
- **Upcoming encounters:** scheduled care for your accessible patients. Click one to open the patient.
- **Patients requiring review** and **Recent activity**, limited to your scope.

**Actions:** **Run screening** re-runs every rule over every patient. The toast reports how many alerts were created,
updated, or left unchanged ("duplicates prevented"), counting only those you may see. Running it again never creates duplicate flags or alerts.

## 4. Finding patients

**Where:** sidebar → **Patients**.

- Only patients in your access scope are listed: Dr. Kim sees Practice A's five patients, Dr. Mitchell sees Practice
  B's four, and other roles see all nine.
- **Filter box.** Matches name, synthetic ID, conditions, organizations and demo scenario.
- **All / Obstetric / Needs review.** Quick filters.
- **Table columns:** *Patient* (name, an **OB** badge for obstetric patients, synthetic ID, age, sex, estimated due date
  marked *(est.)*, demo scenario label), *Relevant conditions* (up to three active documented conditions), *Source
  organizations*, *Next encounter* and *Open alerts / flags* (coral = alerts, amber = flags).

Click a row to open the patient profile.

**Try it** (as Maya Ellison): select **Obstetric**. You'll see Ava Marlowe, Beatriz Okonkwo-Hale and Imani Brooks, who
have open alerts, and Grace Thornbury, who is routine and has none.

## 5. Reading a patient's history

**Where:** click a patient anywhere in the app, e.g. **Patients → Caleb Wren**.

**Header card**

- Name, obstetric badge (with gravida/para), the *Synthetic patient* label, IDs, age, sex, date of birth and a short
  synthetic summary.
- **Estimated due date** is labeled *"estimate, not an admission"*. Camellia never treats a due date as a confirmed
  hospital encounter.
- **Next encounter**, **open review items** and **record count** by organization.
- **Organization chips** show each fictional source system and the patient's MRN there. This is the master patient
  index that links records across organizations.
- **Search records** opens Clinical Search pre-filtered to this patient.

If the patient has an open coordination alert and your role has the Anesthesia OB workflow, a **coral banner** links
straight to its review workspace. Other roles see the patient's records but not the anesthesia alert.

**Tabs**

| Tab | Contents |
|---|---|
| Overview | Upcoming encounters (delivery-related ones highlighted), active documented conditions, and lab results outside the *reporting lab's* reference range. Each item has a **source** chip. |
| Timeline | Every record in date order. Filter by type with the chips; amber dots mark abnormal labs or fractures. Click any entry to open the original record. |
| Conditions | Condition, code, status, onset, author and a source chip |
| Medications | Name, dose and route, dates, prescriber, status and source |
| Labs | One card per test (by LOINC code) with reference range, a trend sparkline (reference range shaded sage, out-of-range points amber) and every value with its source |
| Notes | Clinical notes and imaging reports, with their full text |
| Appointments | Scheduled encounters, with *Delivery-related* badges where they apply |
| Flags | This patient's flags. Click one to open it. |
| AI observations | Gemini-extracted observations (see [§13](#13-gemini-document-interpretation-ai-observations)) |

**Right column:** the Gemini assistant, scoped to this patient (see [§12](#12-using-the-gemini-assistant)). On narrower
screens it appears below the tabs.

## 6. Inspecting an original source record

Any **citation chip** (a small sage-green chip with a document icon), evidence card, timeline entry or search result
opens the **Original source record** drawer on the right. It shows:

- the record type, **source format** (FHIR R4-like JSON, legacy practice export, HL7 v2 ORU feed or plain-text
  dictation) and a *Documented source* badge;
- the patient (as a link), record date, **source organization**, author and specialty, source system and source
  identifier;
- **Normalized content**: the readable text Camellia stores and searches;
- **Deterministic concept tags** (e.g. `LOW_PLATELETS`, `FRACTURE`);
- **Cited as evidence by**: the flags and alerts that use this record (only those your role may see), as links;
- **Show raw source payload as received**: the original HL7 message, FHIR resource or text block.

Close the drawer with the ✕, the Escape key, or by clicking outside it.

**Why it matters:** this is how a reviewer confirms that a flag or an AI statement is backed by a real record.

## 7. Searching records

**Where:** sidebar → **Clinical Search**, or the top search bar or Ctrl/Cmd+K.

Search only ever covers patients in your access scope. Gemini is only told about patients you may access.

### Ask Gemini mode (default)

1. Type a question in plain language, or click one of the example chips.
2. Press **Search**. Three stages run, each with a visible status:
   1. **Gemini interprets** the request. The *Interpreted query* card shows the structured criteria it chose
      (concepts, keywords, record types, organizations, patient, population filters such as *Obstetric patients* or
      *Upcoming delivery-related encounter*, and "≥ N separate dates per patient" for repeated findings), plus a badge
      showing the model and response time. If Gemini isn't configured, the badge reads *Rule-based interpretation
      (not Gemini)*.
   2. **The backend runs approved, parameterized queries** against the database. Gemini never writes SQL.
   3. **Summary of findings.** Gemini summarizes only the retrieved records, in sections: *Documented in records*
      (sage), *AI interpretation* (coral) and *Not found in available records* (amber). Every citation is clickable.
3. **Results** are grouped by patient, showing each patient's source organizations and the number of separate dates.
   Each row shows the record title, date, a snippet, organization, type and record ID. Click a row to open the original
   record, or **Show all N records** to expand.
4. **Discuss results with Gemini** opens an assistant scoped to these search results.

### Searching clinical alerts in plain language

Questions about **alerts or flags** run a clinical-alert search instead of a record search. Try the example chips with
a bell icon:

- *Which patients have open clinical alerts?*
- *Find all patients flagged for low platelets*
- *Show unresolved obstetric anesthesia alerts*

The *Interpreted query* card shows *Searching: Clinical alerts* and the chosen category, status and concepts. Results
are alert cards (see [§8](#8-clinical-alerts)), and Gemini summarizes the source records that triggered them. If you
ask for a category your role can't access (for example obstetric anesthesia alerts as a physician), an amber notice
says those alerts were not searched.

### Keyword mode

Click **Keyword**, type words (all of them must appear) and/or use the filters.

### Filters (both modes)

**Patient**, **Organization**, **Record type**, **From** and **To** dates, and **Clear filters**. In Ask Gemini mode the
filters override whatever Gemini chose.

> No results do not mean a condition is absent. They only mean the available records did not match.

## 8. Clinical Alerts

**Where:** sidebar → **Clinical Alerts** (formerly *Clinical Flags*).

**What an alert is:** a candidate review item produced by a deterministic, explainable rule. It is never a diagnosis.

- **How alerts are generated.** Expand this panel to see the rules available to your role, their configured
  parameters, where each one routes, and the label *Prototype heuristics · not validated clinical thresholds*.
- **Filter bar.** Search text (titles, detection reasons and patient names), **Category**, **Review status**,
  **Priority** and **Specialty** (the specialties in the evidence). Filtering runs on the server, and the filters are
  kept in the page URL so you can share a filtered view. **Clear filters** resets them.
- **Tabs:** *Unresolved* (default), *Resolved / dismissed* and *All*, with the number of alerts and patients found.
  For an alert routed to Anesthesia OB, the coordination workflow status decides whether it is unresolved.
- **Each result card shows:** title, category, an *Anesthesia OB alert* badge where one exists, patient, detection
  reason, evidence count, specialties, source organizations, **Triggered by** chips that open the source records,
  status (the alert's workflow status if it has one), review ordering, last update and an **Open alert** link.
- **Run screening** works the same way as on the dashboard.

Only alerts for your accessible patients appear. Obstetric anesthesia planning alerts appear only for L&D nurses, CRNAs
and anesthesiologists.

**Review ordering** (*Expedited* or *Standard review*) orders the list by time to the encounter. It is not a clinical
risk score; hover the badge for this explanation.

### Flag detail

Click an alert's title or **Open alert** to open it.

- **Detection reason (deterministic rule, not AI):** the rule's exact explanation, including dates, values and
  reference ranges.
- **Timestamps:** created, last evaluated, last updated by, and evidence updated.
- **Review actions** (physicians, CRNAs and anesthesiologists): **Start review** (Unreviewed → In Review), **Mark
  reviewed**, **Dismiss…** (a reason is required), and **Reopen** for reviewed or dismissed items. Every change is saved
  to the database. These are administrative states and do not mean the patient is clinically cleared. Registered and
  L&D nurses see a note that their role can view but not change review status.
- **Open Anesthesia OB alert** (OB items only, anesthesia roles) links to the coordination workspace.
- **Why was this flagged? (Gemini explanation):** see [§12](#12-using-the-gemini-assistant).
- **Supporting evidence**, grouped as *Triggering findings*, *Upcoming encounter* and *Supporting documentation*.
  Click any card to open the source record.
- **Right column:** the assistant, scoped to this flag.

## 9. Anesthesia OB Alerts: the work queue

**Where:** sidebar → **Anesthesia OB Alerts** → **Work queue** tab. Available to L&D nurses, CRNAs and
anesthesiologists.

**How it relates to the real workflow:** this queue replaces the manual watchlist the clinician described.
Instead of relying on someone to message the anesthesia team, Camellia finds obstetric patients with a **booked
delivery-related encounter** and documented findings relevant to anesthesia planning, and puts them here
automatically, before delivery day.

- **Tabs:** **Work queue** (this section) and **Coordination board** (see [§14](#14-coordination-board)).
- **Team bar:** the assigned team (*Anesthesia OB Coordination*) and what your role can do here: CRNAs and
  anesthesiologists can acknowledge and update alerts; L&D nurses can view alerts and add notes. The queue is in-app
  only and sends no real notifications.
- **Status cards:** New, Under Review, Coordination Needed, Plan Documented, Resolved and Dismissed. Click one to
  filter; click it again to return to the active queue. **Active queue** and **All alerts** chips switch between the
  two main views.
- **Queue columns:**
  - a colored edge: coral for **New**, amber for encounters within 21 days;
  - **Patient**, with an *Anesthesia planning* or *Multidisciplinary* badge;
  - **Upcoming encounter**: type, date, "in N days", location and review ordering;
  - **Relevant clinical concerns**: category badges (e.g. *Hematologic*, *Spine*, *Prior anesthesia history*) and the
    documented findings;
  - **Source findings**: number of evidence records and the organizations they came from;
  - **Review status**: workflow status, contact status, acknowledgement and note count, plus a *New evidence* badge
    when re-evaluation added records;
  - **Last updated**: when and by whom, and the review due date;
  - **Review**: opens the workspace. Clicking anywhere on the row does the same.

## 10. Reviewing an alert: the workspace

**Where:** click **Review** on a queue row, an encounter on the dashboard, or the coral banner on a patient profile.
**Demo patient:** Ava Marlowe.

**Header**

- Status, review ordering, alert type and, if present, a *New evidence* badge.
- The patient as a link, with obstetric details and the estimated due date marked as an estimate.
- A **countdown box**: days until the encounter and the encounter type.
- A **workflow stepper**: New → Under Review → Coordination Needed → Plan Documented → Resolved. The current step is
  coral, completed steps are sage, and a *Dismissed* tag appears when dismissed.
- Assigned team, review due date, acknowledgement (who and when), referring-provider contact, and planning discussion
  or information-needed status.

**Main column**

- **Upcoming encounter:** type, date and time, organization and department, provider, description, and an *Open
  scheduling record* chip.
- **Patient summary & specialists:** the summary, the specialties represented in the evidence (e.g. *Cardiology,
  Maternal-Fetal Medicine, Oncology*) and the source organizations.
- **Why this alert was generated:** concern category badges and the **deterministic detection reason**.
- **Why was this flagged? (Gemini explanation):** see [§12](#12-using-the-gemini-assistant).
- **Source evidence:** every record grouped by role. Click a card to open the original record.

**Right column:** two tabs, **Coordinate** (next section) and **Ask Gemini** (the assistant scoped to this alert).

## 11. Recording care coordination

In the workspace, go to the **Coordinate** tab. Every action is saved to the database, shows a toast, and survives a
page refresh or backend restart.

Who can do what: **CRNAs and anesthesiologists** can use every action. **L&D nurses** can add coordination notes only.

| Action | What it does |
|---|---|
| **Acknowledge alert** | Records who acknowledged it and when. A New alert moves to Under Review. |
| **Move to …** | Shows only the transitions allowed from the current status. **Plan Documented** requires a short summary of the plan the care team documented, saved as a *Plan summary* note. **Dismissed** requires a reason. Resolved or dismissed alerts can be reopened (to Under Review). |
| **Contact with referring provider** | Not started, Planned or Completed. It records the status only; Camellia does not send messages. |
| **Planning discussion occurred** | Toggle |
| **Further information needed** | Toggle |
| **Coordination notes** | Choose a type (*General note*, *Further information needed*, *Provider contact*, *Planning discussion*, *Plan summary*), write the note and click **Add note**. |

**Notes timeline:** human notes appear with a green type badge. *Workflow update* notes (gray) are written
automatically for each change. *System* notes (amber) appear when re-evaluation adds new evidence; they list the new
records and confirm that the workflow status was not changed.

If your role can't change the workflow (L&D nurse), an amber message explains this and the workflow controls are
disabled. The backend enforces the same rule and rejects the change with a 403 even if it were sent.

> These statuses describe administrative coordination progress, not clinical clearance. Camellia never writes or
> finalizes a treatment plan; it only records that clinicians documented one.

## 12. Using the Gemini assistant

**Where:** patient profile (right column), flag detail (right column), alert workspace (**Ask Gemini** tab), and Clinical
Search (**Discuss results with Gemini**).

**How to use it**

1. Click a suggested question or type your own. Press Enter to send, or Shift+Enter for a new line.
2. While it works you'll see *"Retrieving source records and asking Gemini…"*.
3. Each answer has:
   - a direct answer, with **citation chips** inline;
   - **Documented in records** (sage): facts with their sources;
   - **AI interpretation** (coral): cautious, review-oriented observations;
   - **Not found in available records** (amber): what the records don't contain. Missing does not mean absent.
   - a footer showing **AI-generated · model**, the number of *verified citations*, how many records were retrieved,
     any unverifiable references that were removed, and the response time.
4. **Follow-up chips** suggested by Gemini appear above the input.
5. **New chat** clears the conversation.

**Suggested questions on an alert (Demo 1):** *Why was this patient flagged?* · *Where did you find that
information?* · *Show the original laboratory results.* · *Which specialist documented this issue?* · *Is there an
upcoming encounter associated with this concern?* · *What should I do next?*

**Safety behavior**

- Camellia checks your access **before** retrieving anything. If you can't access the patient, flag, alert or search
  results, nothing is sent to Gemini and you see *Not available to your role*.
- Camellia retrieves only the relevant patient's records (or the search results you may access) and sends only those
  to Gemini.
- Every cited record ID is checked against what was sent. Fabricated citations are removed, and "facts" without a valid
  source are dropped.
- The assistant does not diagnose, recommend treatments, or declare a patient cleared. "What should I do next?" gets
  administrative next steps, such as verifying results, contacting the referring clinician, or holding a planning
  discussion.
- Each conversation belongs to one user and one patient, flag, alert or result set. Switching context or demo user
  starts a new conversation, so details can't carry over between patients or users.
- The assistant cannot change data or workflow status.

**Flag explanation card** ("Why was this flagged? — Gemini explanation")

- **Explain with Gemini** sends only the flag's evidence records and the rule's detection reason.
- The explanation is saved on the flag (and shown wherever that flag appears) with its timestamp. **Regenerate**
  creates a fresh one.

**If Gemini is not configured:** answers are labeled *"Gemini not configured — no AI answer generated"* and list the
records that were retrieved. No AI text is simulated.

## 13. Gemini document interpretation (AI observations)

**Where:** patient profile → **AI observations** tab.

1. Click **Extract with Gemini** (or **Re-run extraction**).
2. Gemini reads the patient's clinical notes and imaging reports and returns structured observations. Categories are
   condition, symptom, lab finding, medication, procedure history, anesthesia history, upcoming care need, clinical
   concern and care relationship.
3. **Each observation must quote its source record verbatim.** The backend rejects any observation whose quote is not
   in that record, or whose record was not provided. Expand *"N observation(s) rejected by source verification"* to
   see why.
4. Accepted observations are stored with their record IDs and displayed by category, each with its quote and a source
   chip.

**Try it with:** Imani Brooks (prior anesthesia history at Cedar Hollow) or Beatriz Okonkwo-Hale (cardio-oncology
relationships).

## 14. Coordination board

**Where:** sidebar → **Anesthesia OB Alerts** → **Coordination board** tab. (The old *Care Coordination* page now lives
here; old `/coordination` links redirect to it.)

**What it's for:** a shared, at-a-glance planning status for the anesthesia care team, including the clinician who will
staff the encounter but didn't build the plan.

- **Board:** alerts in columns by status (New → Resolved, plus Dismissed if any). Each card shows the patient, the
  encounter and when it is, contact status, and *Discussed* / *Info needed* badges.
- **Coordination summary** for the selected card, in four deliberately separate sections:
  1. **Documented clinical facts:** rule output from the source records, with citation chips.
  2. **AI-generated observations:** the saved Gemini explanation, if one has been generated.
  3. **Human-entered follow-up notes:** notes written by care-team members.
  4. **Planning status:** workflow status, acknowledgement, contact, planning discussion, information needed, review
     due date, last update and dismissal reason.
- **Open review workspace** jumps to the full workspace.

## 15. Demo & integrations

**Where:** sidebar bottom → **Demo & integrations**, or click a header badge.

- **Database (Snowflake):** mode (Snowflake, or *Local fallback — NOT Snowflake*), connection, live session details
  (Snowflake version, account, region, warehouse, database, schema, role and user).
  **Run read / write check** reads non-patient reference data (a JOIN of organizations and practices), inserts a row
  into `INTEGRATION_CHECKS`, reads it back, and shows the JSON result with `"verified": true`. It never returns patient
  data, so it is safe for every role.
- **AI (Google Gemini):** whether an API key is configured, model, SDK, call and failure counts since startup, last
  success and last error. **Send live test request** makes a real Gemini call and shows the model, latency, token counts
  and the structured response.
- **Demo data:**
  - **Simulate incoming lab result (Ava Marlowe)** ingests a new HL7 platelet result dated yesterday from Northgate and
    re-runs screening. The existing alert is updated (*New evidence*, plus a system note); no duplicate alert is
    created. Running it again inserts nothing.
  - **Reset demo data** (asks for confirmation) deletes all demo data, notes and statuses, re-seeds the dataset
    anchored to today, and re-runs screening. Use it before a presentation. The screening summary shown afterwards
    counts only what your role may see.
- **Safety & access:** a summary of what the prototype is and isn't.

## 16. Switching the simulated user and what each role sees

Click your name in the top-right corner. The menu is labeled **"Simulated demo identity — not a secure login"** and lists
six fictional users with their role, department and practice. Click one to switch. The app reloads its data for the new
user, closes any open record, starts fresh Gemini conversations, and returns you to the dashboard if your current page
isn't available to the new role. A toast confirms *"Now viewing as …"*.

| User | Role | Patients | Sidebar | Clinical Alerts review status | Anesthesia OB workflow |
|---|---|---|---|---|---|
| Dr. Alex Kim | Physician, Internal Medicine (Practice A) | Practice A: Caleb, Dolores, Elias, Frances, Henry | 4 items | Can change | Not available |
| Dr. Sarah Mitchell | Physician, OB/GYN (Practice B) | Practice B: Ava, Beatriz, Grace, Imani | 4 items | Can change | Not available |
| Jordan Lee, RN | Registered Nurse | All 9 | 4 items | View only | Not available |
| Taylor Morgan, RN | Labor & Delivery Nurse | All 9 | 5 items | View only | View + add notes |
| Maya Ellison, CRNA (default) | Nurse Anesthetist | All 9 | 5 items | Can change | Full |
| Dr. Daniel Brooks | Anesthesiologist | All 9 | 5 items | Can change | Full |

Things to notice when switching:

- **Dr. Kim** opening Ava Marlowe (for example from a saved link) sees *Not available to your role*. The same panel
  appears for a patient ID that doesn't exist, so it never confirms whether a patient exists.
- **Dr. Mitchell** sees Ava's records and labs (Ava is her patient), but Ava's obstetric anesthesia alert doesn't appear
  anywhere: not in Clinical Alerts, the profile, search or the dashboard.
- **Jordan Lee** sees every patient and the general clinical alerts, but not the Anesthesia OB workflow, and can't
  change review status.
- **Taylor Morgan** sees the Anesthesia OB queue and board and can add notes, but the workflow controls are disabled.

**How it works:** choosing a user asks the backend for a signed demo session (an HttpOnly cookie). Every request after
that is authorized on the server from that session. Your choice is remembered in this browser, and the session is
re-created automatically if the backend restarts. Notes and status changes record the selected user's name.

> This is **demo impersonation, not authentication**. Anyone using the demo can pick any user. It exists to show how
> access would differ by role and practice.

## 17. Exploring the broader search and pattern features

Anesthesia coordination is the primary use case, but the same platform finds other patterns across organizations.
These examples assume a network-wide user such as Maya Ellison or Jordan Lee.

| Try this | Where | What you should see |
|---|---|---|
| *Find every patient with repeated fractures* | Clinical Search | Caleb Wren: fracture records from Riverbend Urgent Care, Cedar Hollow and Larkspur Regional, on separate dates |
| Open **Caleb Wren → Ask:** *Show all fractures documented across this patient's history* | Patient profile | A cited list of wrist, rib, metatarsal and humerus fractures. Gemini may note the documented osteopenia, low vitamin D and steroid courses, and that no DXA result is in the records. |
| *Show all patients with multiple abnormal calcium tests* | Clinical Search | Dolores Fenwick: calcium above range at Northgate and Cedar Hollow |
| **Dolores Fenwick → Labs** | Patient profile | Calcium trend sparkline above the shaded reference range. Open the most recent calcium result to see the lab comment that intact PTH was not part of the order. |
| *Find repeated visits involving the same symptom* | Clinical Search | Elias Navarro: headache encounters at urgent care, the ED and primary care |
| *Which patients have records from more than one hospital?* | Clinical Search | Patients with records from more than one organization |
| *Has this patient had previous anesthesia complications?* on **Frances Albright** | Patient profile assistant | Uneventful general anesthesia and monitored anesthesia care are documented. No flag exists for her. |
| **Grace Thornbury** | Patients → Obstetric | Routine pregnancy, normal platelets, tentative induction slot, and no alert |
| *Find all patients flagged for low platelets* | Clinical Search | Ava Marlowe's alert card (anesthesia roles only) with the triggering platelet results |
| **Clinical Alerts → Category: Recurrent fractures** | Clinical Alerts | Caleb Wren's alert with the triggering fracture records |
| **Clinical Alerts → How alerts are generated** | Clinical Alerts | All rule parameters available to your role, labeled as prototype heuristics |

## 18. Troubleshooting

| Symptom | Fix |
|---|---|
| Red toast saying the backend is unreachable, or a *Backend offline* badge | Start the backend in `backend/` and wait for `Camellia ready`. |
| *Local fallback DB* badge | Snowflake isn't configured. Fill in the `SNOWFLAKE_*` values in `backend/.env` and restart. |
| Backend stops with "Snowflake configuration is incomplete" | The message lists exactly what's missing. Fix `backend/.env`, or set `CAMELLIA_DB_MODE=local`. |
| *Gemini not configured* | Set `GEMINI_API_KEY` in `backend/.env` and restart. |
| "Gemini rate limit or quota reached" | Wait a moment and retry. Free-tier keys have per-minute limits. |
| "Model … is not available" | Set `GEMINI_MODEL` to a model your key can use and restart. |
| Upcoming encounters show as in the past | The demo was seeded long ago. Use **Demo & integrations → Reset demo data**. |
| *Not available to your role* | The current demo user can't access that page or item. Switch users in the top-right corner. |
| *Could not start a demo session* | The backend isn't reachable or rejected the request. Check that it is running, then click **Try again**. |

---

## 19. Camellia Briefing (audio shift handoff)

**Who:** any clinical demo user with simulated shifts — Maya Ellison (CRNA), Dr. Daniel Brooks, Taylor Morgan (L&D),
Jordan Lee (RN), Dr. Alex Kim, Dr. Sarah Mitchell. **Where:** sidebar → **Camellia Briefing**, or the Briefing card at the
top of the Dashboard.

1. Pick a shift in **Your shifts** (date, department, number of patients, open items). If some assigned patients are
   outside your access scope you will see "*N excluded*" — those patients never appear in your briefing or reach Gemini.
2. Choose **Full Shift Briefing** (opening, patient count, upcoming encounters, documented findings, open alerts,
   outstanding coordination tasks, closing) or **Priority Alerts Only** (high-priority items only).
3. Click **Generate**. Watch the stages: *Gemini prepares the script* → *ElevenLabs generates the audio* → *Ready to play*.
4. Play with the player: play/pause, rewind or skip 15 s, drag the progress bar, change speed (0.75×–2×). Each transcript
   segment has **Play from m:ss** to jump to it.
5. The **Transcript** lists every segment with its sources. Record sources open the original record; flags and alerts open
   their pages; monitoring reviews open the patient's monitored stay.
6. **Regenerate** creates a new briefing. Clicking Generate again when nothing in the source records changed reuses the
   existing one (no new API calls).

The card shows the briefing date, provider, shift, patient count, open items, audio duration (measured, never more than
8 minutes) and generation time. If ElevenLabs is not configured the status is **Transcript only** — the Gemini script is
shown and no substitute voice is used. A briefing is visible only to the user who generated it.

> Prototype with synthetic patients. A real deployment would need privacy safeguards before anyone listened to
> identifiable patient information in public or while driving.

## 20. Wearable monitoring in a patient's hospital stay

**Who:** clinical roles within their patient scope (Dr. Kim sees Practice A patients; nurses and anesthesia roles see all).
**Try it with:** Jordan Lee, RN → **Patients** → **Dolores Fenwick**.

- Above the tabs, a **Patient Monitoring** card appears *only* for hospital stays with a wristband session: hospital, stay
  dates, walking today, last detected activity, latest heart rate and SpO₂, device status. If readings are not current the
  card says so and greys the values.
- The **Hospital stays** tab lists every encounter. Dolores has a January surgical stay and a March clinic visit without
  monitoring, and the current pneumonia admission with monitoring — readings appear only under the current stay.
- **View Monitoring Details →** opens `/patients/{id}/encounters/{encounterId}/monitoring`:
  summary tiles, a **Range** filter (last 24 hours, last 7 days, entire stay — always clipped to this stay), charts for
  walking minutes, heart rate, SpO₂ and skin temperature (hover for exact values, **Table** for a table view), nursing
  reviews, the activity timeline (sensor estimates vs. nursing documentation vs. system events), device and care-plan
  details, and the Gemini assistant limited to this stay.
- **← Back to Patient Profile** returns to the same patient and the tab you came from. Refreshing the page works.

Times are shown in hospital time (Eastern). The simulation has its own clock ("Simulated time now"). All values are
simulated device estimates, not clinical measurements.

Example questions for the assistant: *How much has this patient walked today? When was their last activity? Show their
heart rate trend. Why was the mobility alert generated? Has repositioning been documented?*

## 21. Nursing Monitoring queue and documenting repositioning

**Who:** Jordan Lee, RN and Taylor Morgan, RN (L&D). **Where:** sidebar → **Nursing Monitoring** (badge = open reviews).

The queue shows counts (monitored patients, open mobility reviews, repositioning reviews due, device concerns), the open
reviews (each opens the patient's specific stay), and a table of monitored stays with device status, walking today, last
sync and the next repositioning due time.

Review types:
- **Mobility review** — only when the care plan sets a mobility interval and no walking was detected for that long (with a
  connected device). Walking resolves it.
- **Repositioning review** — no *documented* repositioning within the care-plan interval. Wrist movement never counts.
- **Device connectivity** — the band is disconnected or not syncing. This is about the device, not the patient.

On a stay's monitoring page: **Acknowledge** or **Mark reviewed** a review; in **Document repositioning** add an optional
note and click **Record repositioning** (saved with your name at the current simulated time; it resolves an open
repositioning review).

## 22. Simulated Device Controls

**Who:** RN and L&D nurse demo users. **Where:** the amber card on a stay's monitoring page. Every button calls the Java
backend, which generates and stores synthetic data.

- **Start / Stop session**, **Walking (6 min)**, **Vital-sign reading**, **Movement**, **Possible position change**,
  **Disconnect / Reconnect device**.
- **Advance simulated time** (15 min – 8 h): generates readings for the skipped period (if connected) and re-evaluates the
  review rules.
- **Trigger inactivity review** / **Trigger repositioning review**: advance just past the care-plan interval.
- **Reset scenario**: restores the seeded scenario for this stay only.

Seeded scenarios: Dolores Fenwick (Larkspur, mobility plan every 4 h), Henry Castellan (Cedar Hollow, bed rest,
repositioning every 2 h), Frances Albright (Larkspur, wristband disconnected).

## 23. Importing patient records

**Who:** Riley Okafor (Clinical Data Coordinator). **Where:** **Patients → Import records**, or the Dashboard card.

1. **Choose a file:** upload `.json`/`.csv` (max 1 MB) or click a sample (download icon to save it). Samples:
   1. *New patient (Camellia JSON)* — no match → create.
   2. *More records for an existing patient (CSV)* — exact Cedar Hollow MRN match → attach to Caleb Wren.
   3. *Records from a second hospital (FHIR)* — Magnolia Bay Medical Center (new organization); Elias Navarro is only a
      *possible* match (same name and birth date) → you must decide.
   4. *New obstetric patient with a reviewable finding (FHIR)* — create, run screening → an Anesthesia OB review flag and alert.
   5. *Invalid file* — rejected with specific errors; nothing is imported.
2. **Preview:** detected format, patient and source MRN, source organization (and whether it is new), record counts, the
   record list (duplicates marked), warnings and errors.
3. **Confirm the patient:** attach to a listed match or create a new patient (optionally registering it with Practice A or B,
   which controls which physicians can see it). Leave **Run clinical screening** on.
4. **Import** → a summary with links to the patient profile and their records in search.

Re-importing the same file adds nothing. Supported formats:

- **FHIR R4 JSON** — a Bundle with one `Patient` (identifier = source MRN, name, birthDate, gender), optional
  `Organization` (identifier = org id like `MBMC`, name), and `Encounter`, `Observation`, `Condition`,
  `MedicationStatement`, `DocumentReference`, `DiagnosticReport`, `Appointment` resources with ids.
- **Camellia JSON** — `{"format": "camellia-import/v1", "organization": {...}, "patient": {...}, "encounters": [],
  "labs": [], "conditions": [], "medications": [], "notes": [], "appointments": []}` (see sample 1).
- **CSV** — one row per item with columns `record_type, source_id, patient_mrn, patient_first_name, patient_last_name,
  patient_birth_date, patient_sex, org_id, org_name, date, end_date, code, name, value, unit, ref_low, ref_high, flag,
  text, provider, department, status, encounter_source_id, encounter_class`; `record_type` is ENCOUNTER, LAB, CONDITION,
  MEDICATION, NOTE or APPOINTMENT; one patient and one organization per file (see sample 2).

Dates may be ISO-8601 (`2026-10-01`, `2026-10-01T08:30`, with or without offset) or Camellia demo tokens (`@D-12T13:40`
= 12 days before the demo anchor date). This is a synthetic-data demonstration of the ingestion path, not an EHR link.

## 24. What the database badge means

| Badge | Meaning |
|---|---|
| **Snowflake** | Connected to Snowflake; all reads and writes go there. |
| **Snowflake unavailable** | Configured and connected at startup, but the latest check failed. |
| **Local DB (selected)** | `CAMELLIA_DB_MODE=local` chose the local H2 database on purpose, even if Snowflake settings exist. |
| **Local DB — Snowflake not configured** | No Snowflake account is configured; the System page lists the missing settings by name. |

The local H2 database is a separate copy of the synthetic data — changes there do not appear in Snowflake. Teammates
who do not have the Snowflake private key can run with `CAMELLIA_DB_MODE=local`.

