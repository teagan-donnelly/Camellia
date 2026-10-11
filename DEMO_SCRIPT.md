# Camellia — Live Demo Script (PalmettoHacks 2026)

**Length:** about 7–8 minutes, plus Q&A. **Presenter view:** a 1440px or wider browser window at http://localhost:5173.
Lines in *italics* are things to say; **bold** lines are actions to take.

---

## Before you present (10 minutes before)

1. Backend running, with the log line `Camellia ready on Snowflake`. Frontend running.
2. Header badges read **Snowflake** (green) and **Gemini live** (coral).
3. As **Riley Okafor**, **Demo & integrations → Reset demo data**, so encounters are relative to today and no notes are
   left over from rehearsal (imported records and uploaded documents are kept). Then switch back to Maya.
4. On the same page, click **Run read / write check** and **Send live test request** once. This warms up the Snowflake
   warehouse and confirms the Gemini key works.
5. Demo user: **Maya Ellison, CRNA** (top right). If you rehearsed the role-switching segment, switch back to her.
6. Open these tabs in order: Dashboard · Anesthesia OB Alerts · Clinical Search.

> If either badge is amber, say so honestly and stick to what works. The app labels its fallbacks, and we never fake a
> sponsor integration.

---

## 1. The problem (≈ 60 s) — Dashboard tab

*"We built Camellia after talking with a Chief CRNA, an anesthesia professional responsible for coordinating care for
obstetric patients."*

*"They told us about a pregnant patient with a serious medical condition. The information that mattered was already in
her records. But the anesthesia team didn't learn about it until anesthesia was actually needed. Then they had to
scramble: review her history, call other specialists, build a plan, and get that plan to whoever would be staffing her
delivery."*

*"Today, patients like her are often found through individual messages and hand-maintained spreadsheets or watchlists.
That depends on someone noticing the problem and remembering to tell anesthesia. When they asked for a smart 'Anesthesia
OB Alert' that would find these patients early, with the records attached, we built it."*

**Point to the banner:** *"Everything here is synthetic data. This is a prototype, not clinical software."*

## 2. Discovery: the system found her first (≈ 45 s)

**Point to "Upcoming complex-care encounters."**

*"Nobody messaged us about these patients. Camellia read records from five fictional organizations, in a FHIR-style
hospital export, a legacy OB practice system, an HL7 lab feed and urgent-care dictation, all normalized into
**Snowflake**. Then it screened them."*

*"Ava Marlowe has an induction of labor in 12 days. Beatriz has a cesarean in 18 days with a cardio-oncology history.
Imani has a repeat cesarean, and the history that matters for her lives at a different hospital."*

## 3. Flag generation and team notification (≈ 45 s) — Anesthesia OB Alerts tab

**Open Anesthesia OB Alerts.**

*"This is the anesthesia team's work queue, which replaces the manual watchlist. Each alert was routed to the Anesthesia
OB Coordination team automatically. It shows the upcoming encounter, the concern category, which organizations the
evidence came from, and the review status."*

*"The detection is deliberately explainable. A deterministic rule requires a booked delivery-related encounter, not just
a due date, plus a documented finding such as a platelet result below the lab's own reference range. The thresholds
are configurable and labeled as prototype heuristics. They're not a risk score."*

**Click Review on Ava Marlowe.**

## 4. Record review (≈ 45 s)

*"Here's the review workspace: the encounter in 12 days, the exact rule output, and every source record."*

**Scroll to Source evidence → click the platelet 86 card.**

*"Every claim traces back to an original record. This is the actual HL7 message from Northgate Labs (**click 'Show raw
source payload'**), with the value, the reference range of 150 to 400, the lab flag, and which flag and alert cite it."*

**Close the drawer (Esc).**

## 5. Gemini conversation (≈ 75 s)

**Click the Ask Gemini tab → "Why was this patient flagged?"**

*"This is a live Gemini call. The backend pulls only Ava's records from Snowflake, sends just those to Gemini with
strict grounding rules, and then checks every citation it returns against the records it actually sent."*

**When the answer arrives, point to the sections:** *"Documented facts in green, each with a source. Gemini's
interpretation in coral, which stays review-oriented. And what's missing in amber: missing information is not the same
as a normal result."*

**Click "Where did you find that information?"** (or type it).

*(Expect citations to the Northgate lab results, the Bellwether OB note and the Larkspur scheduling record. Read out the
organizations that actually appear.)* *"It points to records from three different organizations."* **Click a citation
chip to show it opens the record.**

**Optional, if time:** ask *"What should I do next?"* — *"Notice it doesn't prescribe. It suggests verifying results and
coordinating with the referring clinician. Clinical decisions stay with clinicians, and Gemini can't change any data or
status."*

## 6. Provider follow-up and coordination status (≈ 60 s)

**Switch to the Coordinate tab.**

1. **Click Acknowledge alert.** *"Maya acknowledges it, and the status moves to Under Review."*
2. **Add a note:** type *Provider contact* → *"Called Dr. Voss's office; hematology consult confirmed before induction."*
   → **Add note**.
3. **Set Contact with referring provider → Planned.**
4. **Click Move to → Coordination Needed.**

*"Every change is written to Snowflake."* **Refresh the browser.** *"After a refresh, the acknowledgement, the note and
the status are all still here."*

**Optional, the "new lab arrives" moment:** **Demo & integrations → Simulate incoming lab result → back to the
alert.** *"A new HL7 result arrived. Camellia re-screened and updated this same alert with the new evidence, with a
system note and a 'new evidence' badge. It didn't create a duplicate, and it didn't touch Maya's workflow status."*

## 7. Shared planning status (≈ 30 s) — Coordination board

**Open Anesthesia OB Alerts → Coordination board tab → select Ava.**

*"The person who staffs the delivery may not be the person who did the planning. That was part of the original
incident. This summary keeps four things visibly separate: documented facts, AI observations, notes written by people,
and the planning status. Camellia never writes the treatment plan. It makes sure the right people know there is one to
write."*

## 7b. Who sees what: role-based access (≈ 75 s) — user switcher

*"In a real hospital, people shouldn't all see the same things. So Camellia is role-aware. These are simulated demo
identities, not a real login, but every rule is enforced by the backend, including what Gemini is allowed to read."*

**Open the user menu (top right).** Point to the label *"Simulated demo identity — not a secure login."*

1. **Switch to Dr. Alex Kim** (Physician, Practice A). *"Dr. Kim is a primary-care physician. He sees only the five
   patients registered to his practice, and there's no Anesthesia OB Alerts item in his sidebar."* **Open Patients**
   (5 patients). **Open Clinical Alerts:** Caleb's fractures, Dolores's calcium, Elias's headaches.
   *If you have Ava's URL handy, paste it:* *"Ava isn't his patient, so he gets 'Not available to your role'. The same
   response comes back for a patient who doesn't exist, so it never reveals who's in the system."*
2. **Switch to Dr. Sarah Mitchell** (OB/GYN, Practice B). *"Ava is her patient, so she sees Ava's records and the
   falling platelets, but not the anesthesia team's alert. That workflow belongs to anesthesia."* **Clinical Alerts →
   0 unresolved.**
3. **Switch to Jordan Lee, RN.** *"A registered nurse sees all patients and the general clinical alerts, but can't
   change review status and doesn't see the anesthesia queue."*
4. **Switch to Taylor Morgan, L&D nurse.** **Open Anesthesia OB Alerts → Ava.** *"Labor and delivery can see the
   anesthesia alerts and add notes, but the workflow buttons are disabled. Only anesthesia can change the status."*
5. **Switch to Dr. Daniel Brooks, anesthesiologist.** *"And the anesthesiologist has the full workflow."* **Open the
   dashboard:** *"Different users, different dashboards, from the same endpoints."*

**Switch back to Maya Ellison.**

*"The same scope applies to Gemini: if you can't see a patient, their records are never retrieved or sent to the
model. Our tests check that no Gemini request is made at all."*

## 8. Broader platform: cross-organization search (≈ 60 s) — Clinical Search

**Click the example "Find every patient with repeated fractures."**

*"The same engine works beyond obstetrics. Gemini turns the question into structured, allow-listed search criteria;
you can see exactly what it chose. The backend runs parameterized queries in Snowflake. The model never writes SQL."*

**Point to the results:** *"Caleb Wren has fractures documented at an urgent care, a community hospital ED and an
orthopedics clinic, three organizations that never connected the dots."* **Point to the Gemini summary** — *"cited, with
a section listing what isn't in the records."* (The ED note says no bone density study was found; Gemini may
call that out.)

**If time:** *"Show all patients with multiple abnormal calcium tests"* → Dolores Fenwick.

**Or search the alerts themselves:** click the bell chip *"Find all patients flagged for low platelets."* *"Questions
about alerts become a clinical-alert search, with the triggering records attached."*

## 9. Sponsor technology and close (≈ 30 s)

**Open Demo & integrations.**

- **Snowflake:** *"The primary database: patients, records from five source formats, flags, alerts, notes and the
  activity log, connected through the official JDBC driver with key-pair auth. Here's a live read/write check."*
  **(Click Run read / write check: `verified: true`.)**
- **Gemini:** *"Through Google's official Gen AI Java SDK, with structured JSON output, for document interpretation,
  flag explanations, the assistant and natural-language search. Every answer is checked against the records sent to
  the model."*

*"Our promise is simple: help the right healthcare provider discover the right clinical information early enough to act
on it."*

---

## What's real today vs. what's next (be ready to say this)

| Real in this prototype | Future, not built |
|---|---|
| Ingestion of 4 synthetic source formats from 5 fictional organizations | Live EHR connections (SMART on FHIR / HL7 interfaces); we do **not** connect to Epic |
| Deterministic, configurable screening rules with cited evidence | Clinically validated criteria authored with anesthesia, OB and MFM leadership |
| In-app alert queue with team routing, workflow and notes | Secure messaging or EHR in-basket notifications, escalation and SLAs |
| Live Gemini explanations, chat, extraction and search, with citation validation | BAA-covered deployment and formal AI evaluation |
| Snowflake persistence with live read/write checks | Snowflake Cortex semantic search; streaming re-screening with streams and tasks |
| Role-based access for 6 simulated users (practice scope, anesthesia workflow, Gemini retrieval), enforced server-side | Real authentication (SSO), care-relationship-based access, break-the-glass and audit |

## Backup answers

- **"Is this diagnosing patients?"** No. Rules suggest review; Gemini explains documented facts with citations. Neither
  diagnoses or recommends treatment.
- **"What if Gemini makes something up?"** Every cited record ID is checked against what was sent. Unverifiable
  citations are removed and the count is shown. Extraction requires verbatim quotes.
- **"Why not just let the model query the database?"** It would be unsafe. Gemini only fills an enum-constrained JSON
  schema; the backend validates it and runs fixed, parameterized SQL.
- **"Can anyone just switch to the anesthesiologist?"** In the demo, yes, on purpose. The switcher is demo
  impersonation, not a login. What's real is that the backend, not the browser, decides access from a signed session,
  and every endpoint (search, dashboard, Gemini) applies it. Production would put real SSO in front of the same checks.
- **"Duplicates?"** Each flag has a stable dedup key and an evidence fingerprint. Re-runs update, never duplicate.
- **If Gemini is rate-limited live:** the UI shows a clear error. Retry, or show the saved flag explanation, which is
  cached with its timestamp.

---

# Camellia V3 — combined demo (≈ 9–10 min)

**Prepare (10 min before):** backend on Snowflake with `GEMINI_API_KEY` and `ELEVENLABS_API_KEY` set; System page shows
*Snowflake connected*, *Gemini live*, *ElevenLabs API key configured*. As Riley Okafor, open **Demo & integrations → Reset demo
data** so dates are relative to today (this resets monitoring and workflow state; imports and uploads are kept), then
switch to Maya. Pre-generate one Full Shift Briefing for
Maya so playback is instant (generation takes about a minute with ElevenLabs).

**On the hosted link** (https://camellia-snowy.vercel.app): open it a few minutes early, because the free Render backend
sleeps after 15 idle minutes and takes about a minute to wake. Generate the briefing within 15 minutes of presenting and
keep the site in use, because its audio file is lost if the backend sleeps. The free ElevenLabs plan has 10,000
characters a month and a full briefing uses about 2,000, so avoid test generations. If Gemini answers slowly, Google's
main model is busy: the app retries, then switches to the backup model for three minutes.

1. **The problem (45 s).** An obstetric patient with a serious condition reached the anesthesia team late; the team needed
   history, specialists and a plan, and relied on messages, spreadsheets and watchlists. Camellia finds that information
   first. *(Dashboard as Maya Ellison, CRNA.)*
2. **Existing intelligence (60 s).** Clinical Search → *"pregnant patients anesthesia should know about"* (Gemini turns it
   into structured criteria; Snowflake runs parameterized SQL). Open Ava's anesthesia alert, show cited evidence.
3. **Import a record (75 s).** Switch to **Riley Okafor** → Patients → **Import records** → sample *New obstetric patient
   with a reviewable finding*. Show the preview (format, organization, records, *no existing match*). Create the patient,
   keep screening on → result: **OB anesthesia review created**. *"New information enters Camellia through one
   normalization path — no Epic connection is claimed."*
4. **The finding appears (30 s).** Switch to **Maya** → Anesthesia OB Alerts: Noemi Castillo is in the queue with cited
   platelet results.
5. **Personal briefing (90 s).** Sidebar → **Camellia Briefing** → today's shift → **Full Shift Briefing** → Generate (or
   open the pre-generated one). Point at the stages: *Snowflake* supplied the shift assignments and records; *Gemini* wrote a
   cited script; *ElevenLabs* produced the narration. Play 30–40 s, open the transcript, click a source citation. Switch to
   **Priority Alerts Only** to show the shorter version. *"Duration is measured, never over eight minutes."*
6. **Monitored hospital stay (60 s).** Switch to **Jordan Lee, RN** → Patients → **Dolores Fenwick**: one Patient Monitoring
   card (current admission) — her January stay has no wristband, so no card and no readings there. Open **View Monitoring
   Details**.
7. **Simulated sensor event (45 s).** In **Simulated Device Controls** click **Walking (6 min)**: the walking chart, heart
   rate and timeline update with timestamped observations. *"Clearly labeled simulation — the Java backend generates and
   stores the data; the proposed wristband is in HARDWARE_ARCHITECTURE.md."*
8. **Nursing review (60 s).** Click **Trigger inactivity review** → a *Mobility review suggested* notification appears
   (only because Dolores' care plan sets a 4-hour mobility interval). Optional: **Disconnect device** + advance time → only a
   *device connectivity* review — missing data is never called inactivity.
9. **Document an intervention (45 s).** Open **Henry Castellan** (Nursing Monitoring queue → his stay). Show that wrist
   movement does not clear the repositioning review; type a note in **Document repositioning** → **Record repositioning** →
   the review resolves, and the timeline shows *Nursing documentation — Jordan Lee, RN*.
10. **Back to the patient (10 s).** **← Back to Patient Profile** returns to the same patient and tab.
11. **Close (30 s).** Gemini: grounded search, scripts and Q&A with validated citations. Snowflake: the system of record for
    records, shifts, briefings, imports and every monitoring observation. ElevenLabs: the spoken briefing. Everything is
    synthetic; the wristband is a simulation of a proposed design; nothing is clinically validated.

**If ElevenLabs is not configured:** the briefing shows *Transcript only*; say so plainly and show the transcript and
citations. Never play browser speech as if it were ElevenLabs.
