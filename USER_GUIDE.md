# Camellia User Guide

A walkthrough of every feature in the first iteration, written for a first-time user. Everything described here exists
in the app today.

> **Demo application using synthetic patient information. Not for clinical use.** All patients, providers and
> organizations are fictional. Users and teams are simulated. There is no login.

---

## Contents

1. [Opening the application](#1-opening-the-application)
2. [Finding your way around](#2-finding-your-way-around)
3. [Dashboard](#3-dashboard)
4. [Finding patients](#4-finding-patients)
5. [Reading a patient's history](#5-reading-a-patients-history)
6. [Inspecting an original source record](#6-inspecting-an-original-source-record)
7. [Searching records](#7-searching-records)
8. [Clinical flags](#8-clinical-flags)
9. [Anesthesia OB Alerts: the work queue](#9-anesthesia-ob-alerts-the-work-queue)
10. [Reviewing an alert: the workspace](#10-reviewing-an-alert-the-workspace)
11. [Recording care coordination](#11-recording-care-coordination)
12. [Using the Gemini assistant](#12-using-the-gemini-assistant)
13. [Gemini document interpretation (AI observations)](#13-gemini-document-interpretation-ai-observations)
14. [Care Coordination summary](#14-care-coordination-summary)
15. [System & integrations](#15-system--integrations)
16. [Switching the simulated user](#16-switching-the-simulated-user)
17. [Exploring the broader search and pattern features](#17-exploring-the-broader-search-and-pattern-features)
18. [Troubleshooting](#18-troubleshooting)

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

**Left sidebar**

| Item | Purpose |
|---|---|
| Dashboard | Overview: metrics, upcoming complex-care encounters, patients requiring review, recent activity |
| Patients | Directory of all synthetic patients |
| Clinical Search | Natural-language and keyword search across all organizations |
| Clinical Flags | All rule-generated review items |
| Anesthesia OB Alerts | The anesthesia coordination work queue. The coral badge counts **New** alerts. |
| Care Coordination | Shared planning summary board |
| System & integrations (bottom) | Snowflake and Gemini status, live checks, demo data controls |

**Top bar**

- **Search bar.** Type a question and press Enter to run an AI search on the Clinical Search page. Press **Ctrl+K**
  (Windows/Linux) or **⌘K** (macOS) from any page to focus it. On the Clinical Search page the shortcut focuses that
  page's larger search box instead.
- **Integration badges.** *Snowflake* (green) or *Local fallback DB* (amber), and *Gemini live* (coral) or *Gemini not
  configured* (amber). Click either badge to open System & integrations.
- **User menu.** Shows the simulated user and team. See [§16](#16-switching-the-simulated-user).

Notifications appear as small toasts in the bottom-right corner after every save.

## 3. Dashboard

**What it's for:** a morning overview for the anesthesia coordinator. It shows who needs attention, before which
encounter, and what changed recently.

**What you see**

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
- **How Camellia works.** A five-step explanation, plus the last screening run and the demo date anchor.

**Actions:** **Run screening** re-runs every rule over every patient. The toast reports how many flags were created,
updated, or left unchanged ("duplicates prevented"). Running it again never creates duplicate flags or alerts.

## 4. Finding patients

**Where:** sidebar → **Patients**.

- **Filter box.** Matches name, synthetic ID, conditions, organizations and demo scenario.
- **All / Obstetric / Needs review.** Quick filters.
- **Table columns:** *Patient* (name, an **OB** badge for obstetric patients, synthetic ID, age, sex, estimated due date
  marked *(est.)*, demo scenario label), *Relevant conditions* (up to three active documented conditions), *Source
  organizations*, *Next encounter* and *Open alerts / flags* (coral = alerts, amber = flags).

Click a row to open the patient profile.

**Try it:** select **Obstetric**. You'll see Ava Marlowe, Beatriz Okonkwo-Hale and Imani Brooks, who have open alerts,
and Grace Thornbury, who is routine and has none.

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

If the patient has an open coordination alert, a **coral banner** links straight to its review workspace.

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
- **Cited as evidence by**: the flags and alerts that use this record, as links;
- **Show raw source payload as received**: the original HL7 message, FHIR resource or text block.

Close the drawer with the ✕, the Escape key, or by clicking outside it.

**Why it matters:** this is how a reviewer confirms that a flag or an AI statement is backed by a real record.

## 7. Searching records

**Where:** sidebar → **Clinical Search**, or the top search bar or Ctrl/Cmd+K.

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

### Keyword mode

Click **Keyword**, type words (all of them must appear) and/or use the filters.

### Filters (both modes)

**Patient**, **Organization**, **Record type**, **From** and **To** dates, and **Clear filters**. In Ask Gemini mode the
filters override whatever Gemini chose.

> No results do not mean a condition is absent. They only mean the available records did not match.

## 8. Clinical flags

**Where:** sidebar → **Clinical Flags**.

**What a flag is:** a candidate review item produced by a deterministic, explainable rule. It is never a diagnosis.

- **How flags are generated.** Expand this panel to see all four rules, their configured parameters, where each one
  routes, and the label *Prototype heuristics · not validated clinical thresholds*.
- **Tabs:** *Open*, *Reviewed*, *Dismissed* and *All* (with counts), plus a **rule filter**.
- **Each flag card shows:** title, rule, *Routed to Anesthesia OB* (when an alert exists), *New evidence* (when
  re-evaluation found new records), patient, detection reason, the number of triggering findings, evidence records and
  organizations, status, review ordering and last update.
- **Run screening** works the same way as on the dashboard.

**Review ordering** (*Expedited* or *Standard review*) orders the queue by time to the encounter. It is not a clinical
risk score; hover the badge for this explanation.

### Flag detail

Click a flag to open it.

- **Detection reason (deterministic rule, not AI):** the rule's exact explanation, including dates, values and
  reference ranges.
- **Timestamps:** created, last evaluated, last updated by, and evidence updated.
- **Review actions:** **Start review** (Unreviewed → In Review), **Mark reviewed**, **Dismiss…** (a reason is
  required), and **Reopen** for reviewed or dismissed flags. Every change is saved to the database. These are
  administrative states and do not mean the patient is clinically cleared.
- **Open Anesthesia OB alert** (OB flags only) links to the coordination workspace.
- **Why was this flagged? (Gemini explanation):** see [§12](#12-using-the-gemini-assistant).
- **Supporting evidence**, grouped as *Triggering findings*, *Upcoming encounter* and *Supporting documentation*.
  Click any card to open the source record.
- **Right column:** the assistant, scoped to this flag.

## 9. Anesthesia OB Alerts: the work queue

**Where:** sidebar → **Anesthesia OB Alerts**.

**How it relates to the real workflow:** this queue replaces the manual watchlist the clinician described.
Instead of relying on someone to message the anesthesia team, Camellia finds obstetric patients with a **booked
delivery-related encounter** and documented findings relevant to anesthesia planning, and puts them here
automatically, before delivery day.

- **Team bar:** the assigned team (*Anesthesia OB Coordination*) and whether the current simulated user is a member.
  The queue is in-app only and sends no real notifications.
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

If you are signed in as someone outside the assigned team, an amber message explains that you can add notes but not
change the workflow. This is simulated access control.

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

- Camellia retrieves only the relevant patient's records (or the search results) and sends only those to Gemini.
- Every cited record ID is checked against what was sent. Fabricated citations are removed, and "facts" without a valid
  source are dropped.
- The assistant does not diagnose, recommend treatments, or declare a patient cleared. "What should I do next?" gets
  administrative next steps, such as verifying results, contacting the referring clinician, or holding a planning
  discussion.
- Each conversation belongs to one patient, flag, alert or result set. Switching context starts a new conversation, so
  details from one patient can't carry over to another.
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

## 14. Care Coordination summary

**Where:** sidebar → **Care Coordination**.

**What it's for:** a shared, at-a-glance planning status that any simulated care-team member can read, including the
clinician who will staff the encounter but didn't build the plan.

- **Board:** alerts in columns by status (New → Resolved, plus Dismissed if any). Each card shows the patient, the
  encounter and when it is, contact status, and *Discussed* / *Info needed* badges.
- **Coordination summary** for the selected card, in four deliberately separate sections:
  1. **Documented clinical facts:** rule output from the source records, with citation chips.
  2. **AI-generated observations:** the saved Gemini explanation, if one has been generated.
  3. **Human-entered follow-up notes:** notes written by care-team members.
  4. **Planning status:** workflow status, acknowledgement, contact, planning discussion, information needed, review
     due date, last update and dismissal reason.
- **Open review workspace** jumps to the full workspace.

## 15. System & integrations

**Where:** sidebar bottom → **System & integrations**, or click a header badge.

- **Database (Snowflake):** mode (Snowflake, or *Local fallback — NOT Snowflake*), connection, live session details
  (Snowflake version, account, region, warehouse, database, schema, role and user) and the stored patient count.
  **Run read / write check** reads a demo patient with a JOIN, inserts a row into `INTEGRATION_CHECKS`, reads it back,
  and shows the JSON result with `"verified": true`.
- **AI (Google Gemini):** whether an API key is configured, model, SDK, call and failure counts since startup, last
  success and last error. **Send live test request** makes a real Gemini call and shows the model, latency, token counts
  and the structured response.
- **Demo data:**
  - **Simulate incoming lab result (Ava Marlowe)** ingests a new HL7 platelet result dated yesterday from Northgate and
    re-runs screening. The existing alert is updated (*New evidence*, plus a system note); no duplicate alert is
    created. Running it again inserts nothing.
  - **Reset demo data** (asks for confirmation) deletes all demo data, notes and statuses, re-seeds the dataset
    anchored to today, and re-runs screening. Use it before a presentation.
- **Safety & access:** a summary of what the prototype is and isn't.

## 16. Switching the simulated user

Click your name in the top-right corner. There are five fictional users:

| User | Team | Can change Anesthesia OB alert workflow? |
|---|---|---|
| Maya Ellison, CRNA (default) | Anesthesia OB Coordination | Yes |
| Dr. Samuel Okafor, MD | Anesthesia OB Coordination | Yes |
| Dr. Priya Raman, MD | Maternal-Fetal Medicine | No; can add notes |
| Jordan Lee, RN | Labor & Delivery | No; can add notes |
| Dr. Alex Kim, MD | Clinical Review | No; can add notes |

Your choice is remembered in this browser. Notes and status changes record the selected user's name. There is no
authentication.

## 17. Exploring the broader search and pattern features

Anesthesia coordination is the primary use case, but the same platform finds other patterns across organizations.

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
| **Clinical Flags → How flags are generated** | Clinical Flags | All rule parameters, labeled as prototype heuristics |

## 18. Troubleshooting

| Symptom | Fix |
|---|---|
| Red toast saying the backend is unreachable, or a *Backend offline* badge | Start the backend in `backend/` and wait for `Camellia ready`. |
| *Local fallback DB* badge | Snowflake isn't configured. Fill in the `SNOWFLAKE_*` values in `backend/.env` and restart. |
| Backend stops with "Snowflake configuration is incomplete" | The message lists exactly what's missing. Fix `backend/.env`, or set `CAMELLIA_DB_MODE=local`. |
| *Gemini not configured* | Set `GEMINI_API_KEY` in `backend/.env` and restart. |
| "Gemini rate limit or quota reached" | Wait a moment and retry. Free-tier keys have per-minute limits. |
| "Model … is not available" | Set `GEMINI_MODEL` to a model your key can use and restart. |
| Upcoming encounters show as in the past | The demo was seeded long ago. Use **System & integrations → Reset demo data**. |
