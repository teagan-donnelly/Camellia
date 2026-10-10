import {
  ArrowLeft, BadgeCheck, Baby, Building2, CalendarClock, Check, ClipboardCheck, ListChecks, MessageSquarePlus, Phone, Sparkles, Stethoscope,
  TriangleAlert, UserRound,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useAddNote, useAlert, useUpdateAlert, type AlertUpdate } from '../api/hooks'
import type { AlertDetail, AlertStatus } from '../api/types'
import { AssistantPanel, FlagExplanationCard } from '../components/ai'
import { CitationChip, EvidenceList } from '../components/records'
import { AlertStatusBadge, PriorityBadge } from '../components/status'
import { Badge, Button, Card, CardHeader, ErrorBox, KeyValue, Loading, Tabs } from '../components/ui'
import { ALERT_STATUS_LABEL, CONTACT_LABEL, NOTE_TYPE_LABEL, cx, daysLabel, fmtDate, fmtDateTime, relative } from '../lib/format'
import { useApp } from '../state/AppContext'

const STEPS: AlertStatus[] = ['NEW', 'UNDER_REVIEW', 'COORDINATION_NEEDED', 'PLAN_DOCUMENTED', 'RESOLVED']

export default function AlertWorkspace() {
  const { id = '' } = useParams()
  const { data, isLoading, error } = useAlert(id)
  const [side, setSide] = useState<'coordinate' | 'assistant'>('coordinate')

  if (isLoading) return <Loading label="Opening review workspace…" />
  if (error) return <ErrorBox error={error} title="Could not load alert" />
  if (!data) return null
  const a = data.alert
  const p = data.patient

  return (
    <div>
      <Link to="/alerts" className="mb-4 inline-flex items-center gap-1 text-[13px] text-navy-500 hover:text-navy-800"><ArrowLeft className="h-3.5 w-3.5" /> Anesthesia OB Alerts</Link>

      <Card className="mb-5">
        <div className="flex flex-wrap items-start justify-between gap-4">
          <div className="min-w-0">
            <div className="flex flex-wrap items-center gap-2">
              <AlertStatusBadge status={a.status} />
              <PriorityBadge priority={a.priority} />
              <Badge tone={a.alertType === 'MULTIDISCIPLINARY_OB' ? 'navy' : 'coral'}>{a.alertType === 'MULTIDISCIPLINARY_OB' ? 'Multidisciplinary coordination' : 'Anesthesia OB alert'}</Badge>
              {a.evidenceUpdatedAt && <Badge tone="amber"><TriangleAlert className="h-3 w-3" />New evidence {relative(a.evidenceUpdatedAt)}</Badge>}
            </div>
            <h1 className="mt-2 text-[22px] font-semibold tracking-tight text-navy-950">{a.title}</h1>
            <div className="mt-1 text-[14px] text-navy-600">
              <Link to={`/patients/${p.patientId}`} className="font-semibold text-navy-900 hover:text-coral-700">{p.name}</Link>
              <span className="text-navy-400"> · {p.displayId} · {p.age}y</span>
              {p.obstetric && <span className="ml-2 inline-flex items-center gap-1 text-coral-700"><Baby className="h-3.5 w-3.5" />{p.gestationalNote}{p.estimatedDueDate ? `, EDD ${fmtDate(p.estimatedDueDate)} (est.)` : ''}</span>}
            </div>
          </div>
          <div className="flex items-center gap-3 rounded-xl border border-amber-200 bg-amber-50 px-4 py-2.5">
            <CalendarClock className="h-5 w-5 text-amber-600" />
            <div>
              <div className="text-[18px] font-semibold leading-tight text-navy-950">{daysLabel(a.daysUntilEncounter)}</div>
              <div className="text-[12px] text-navy-600">{a.encounterType}</div>
            </div>
          </div>
        </div>
        <Stepper status={a.status} />
        <div className="mt-4 grid grid-cols-2 gap-4 border-t border-line pt-4 md:grid-cols-5">
          <KeyValue label="Assigned team">{a.assignedTeam}</KeyValue>
          <KeyValue label="Review due">{fmtDate(a.reviewDueDate)}</KeyValue>
          <KeyValue label="Acknowledged">{a.acknowledgedBy ? <>{a.acknowledgedBy}<div className="text-[12px] text-navy-500">{fmtDateTime(data.acknowledgedAt)}</div></> : <span className="text-coral-700">Not yet</span>}</KeyValue>
          <KeyValue label="Referring-provider contact">{CONTACT_LABEL[a.contactStatus]}</KeyValue>
          <KeyValue label="Planning discussion">{a.planningDiscussionOccurred ? 'Occurred' : 'Not yet'}{a.infoNeeded && <div className="text-[12px] text-amber-700">Further information needed</div>}</KeyValue>
        </div>
      </Card>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_440px]">
        <div className="min-w-0 space-y-5">
          <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
            <Card>
              <CardHeader icon={<CalendarClock className="h-4 w-4" />} title="Upcoming encounter" />
              {data.encounter ? (
                <div className="space-y-2 text-[13.5px]">
                  <div className="text-[15px] font-semibold text-navy-900">{data.encounter.type}</div>
                  <div className="text-navy-700">{fmtDateTime(data.encounter.scheduledStart)} · {daysLabel(data.encounter.daysUntil)}</div>
                  <div className="flex items-center gap-1.5 text-navy-600"><Building2 className="h-3.5 w-3.5 text-navy-400" />{data.encounter.orgName} — {data.encounter.department}</div>
                  <div className="flex items-center gap-1.5 text-navy-600"><UserRound className="h-3.5 w-3.5 text-navy-400" />{data.encounter.provider}</div>
                  {data.encounter.description && <p className="text-[13px] text-navy-500">{data.encounter.description}</p>}
                  <CitationChip recordId={data.encounter.recordId} label="Open scheduling record" />
                </div>
              ) : <p className="text-sm text-navy-400">No linked encounter.</p>}
            </Card>
            <Card>
              <CardHeader icon={<Stethoscope className="h-4 w-4" />} title="Patient summary & specialists" />
              <p className="text-[13.5px] leading-relaxed text-navy-700">{p.summary}</p>
              <div className="mt-3 text-[12px] font-semibold uppercase tracking-wide text-navy-400">Specialties in the evidence</div>
              <div className="mt-1 flex flex-wrap gap-1">{(a.involvedSpecialties ?? '').split(', ').filter(Boolean).map((s) => <Badge key={s} tone="outline">{s}</Badge>)}</div>
              <div className="mt-3 text-[12px] font-semibold uppercase tracking-wide text-navy-400">Source organizations</div>
              <div className="mt-1 flex flex-wrap gap-1">{a.sourceOrganizations.map((o) => <Badge key={o}><Building2 className="h-3 w-3" />{o}</Badge>)}</div>
            </Card>
          </div>

          <Card>
            <CardHeader icon={<ListChecks className="h-4 w-4" />} title="Why this alert was generated" subtitle="Deterministic screening rule output — documented facts only" />
            <div className="mb-3 flex flex-wrap gap-1">{(a.concernSummary ?? '').split(' — ')[0].split(' · ').filter(Boolean).map((c) => <Badge key={c} tone="amber">{c}</Badge>)}</div>
            <p className="rounded-xl border border-navy-100 bg-navy-50/60 p-4 text-[14px] leading-relaxed text-navy-900">{data.flag?.detectionReason}</p>
          </Card>

          <FlagExplanationCard flagId={a.flagId} cached={data.flag?.aiExplanation} />

          <Card>
            <CardHeader title="Source evidence" subtitle={`${data.evidence.length} records across ${a.sourceOrganizations.length} organizations — click to open the original record`} />
            <EvidenceList evidence={data.evidence} />
          </Card>
        </div>

        <div className="space-y-4 xl:sticky xl:top-0 xl:self-start">
          <Tabs<'coordinate' | 'assistant'> value={side} onChange={setSide} tabs={[
            { id: 'coordinate', label: <span className="inline-flex items-center gap-1.5"><ClipboardCheck className="h-4 w-4" />Coordinate</span> },
            { id: 'assistant', label: <span className="inline-flex items-center gap-1.5"><Sparkles className="h-4 w-4 text-coral-500" />Ask Gemini</span> },
          ]} />
          {side === 'coordinate' ? <CoordinationPanel data={data} /> : (
            <AssistantPanel
              context={{ type: 'ALERT', id: a.alertId, label: `${a.title} — ${p.name}` }}
              suggestions={[
                'Why was this patient flagged?',
                'Where did you find that information?',
                'Show the original laboratory results.',
                'Which specialist documented this issue?',
                'Is there an upcoming encounter associated with this concern?',
                'What should I do next?',
              ]}
              className="xl:h-[calc(100vh-200px)]"
            />
          )}
        </div>
      </div>
    </div>
  )
}

function Stepper({ status }: { status: AlertStatus }) {
  const dismissed = status === 'DISMISSED'
  const idx = STEPS.indexOf(status)
  return (
    <div className="mt-5 flex flex-wrap items-center gap-1">
      {STEPS.map((s, i) => {
        const done = !dismissed && i < idx
        const current = !dismissed && i === idx
        return (
          <div key={s} className="flex items-center gap-1">
            <div className={cx('flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] font-medium',
              current ? 'bg-coral-600 text-white' : done ? 'bg-sage-100 text-sage-700' : 'bg-navy-50 text-navy-400')}>
              {done ? <Check className="h-3 w-3" /> : <span className="text-[11px]">{i + 1}</span>}{ALERT_STATUS_LABEL[s]}
            </div>
            {i < STEPS.length - 1 && <div className={cx('h-px w-5', done ? 'bg-sage-300' : 'bg-line-strong')} />}
          </div>
        )
      })}
      {dismissed && <Badge tone="outline" className="ml-2">Dismissed</Badge>}
    </div>
  )
}

function CoordinationPanel({ data }: { data: AlertDetail }) {
  const a = data.alert
  const { user, toast } = useApp()
  const update = useUpdateAlert(a.alertId)
  const addNote = useAddNote(a.alertId)
  const [noteType, setNoteType] = useState('GENERAL')
  const [body, setBody] = useState('')
  const [pendingStatus, setPendingStatus] = useState<AlertStatus | null>(null)
  const [statusText, setStatusText] = useState('')
  const canEdit = user.team === a.assignedTeam

  const save = (u: AlertUpdate, msg: string) => update.mutate(u, {
    onSuccess: () => { toast({ tone: 'success', title: msg, body: 'Saved to the database — persists across refresh and restart.' }); setPendingStatus(null); setStatusText('') },
    onError: (e) => toast({ tone: 'error', title: 'Update failed', body: (e as Error).message }),
  })

  const needsText = pendingStatus === 'DISMISSED' || pendingStatus === 'PLAN_DOCUMENTED'

  return (
    <div className="space-y-4">
      <Card>
        <CardHeader title="Coordination actions" subtitle="Administrative workflow — not a clinical clearance" />
        {!canEdit && (
          <div className="mb-3 rounded-lg bg-amber-50 px-3 py-2 text-[12.5px] text-amber-700">
            Simulated access: {user.name} ({user.team}) can add notes, but only {a.assignedTeam} members can change this alert's workflow.
          </div>
        )}
        {!a.acknowledgedBy && (
          <Button variant="primary" className="mb-3 w-full" disabled={!canEdit} loading={update.isPending} onClick={() => save({ acknowledge: true }, 'Alert acknowledged')}>
            <BadgeCheck className="h-4 w-4" /> Acknowledge alert
          </Button>
        )}
        <div className="text-[12px] font-semibold uppercase tracking-wide text-navy-400">Move to</div>
        <div className="mt-1.5 flex flex-wrap gap-1.5">
          {data.allowedTransitions.map((s) => (
            <Button key={s} size="sm" variant={s === 'DISMISSED' ? 'danger' : pendingStatus === s ? 'navy' : 'secondary'} disabled={!canEdit}
              onClick={() => (s === 'DISMISSED' || s === 'PLAN_DOCUMENTED') ? setPendingStatus(s) : save({ status: s }, `Status: ${ALERT_STATUS_LABEL[s]}`)}>
              {ALERT_STATUS_LABEL[s]}
            </Button>
          ))}
        </div>
        {needsText && (
          <div className="mt-3 space-y-2 rounded-lg border border-line bg-canvas p-3">
            <div className="text-[12.5px] font-medium text-navy-700">{pendingStatus === 'DISMISSED' ? 'Reason for dismissal (required)' : 'Summarize the plan documented by the care team (required)'}</div>
            <textarea value={statusText} onChange={(e) => setStatusText(e.target.value)} rows={3}
              placeholder={pendingStatus === 'DISMISSED' ? 'e.g. Duplicate of existing anesthesia consult; already reviewed in clinic.' : 'e.g. Anesthesia consult completed; plan documented in the L&D record by the obstetric anesthesia team.'}
              className="w-full rounded-lg border border-line bg-white px-3 py-2 text-[13px] focus:border-coral-300 focus:outline-none focus:ring-2 focus:ring-coral-100" />
            <div className="flex gap-2">
              <Button size="sm" variant={pendingStatus === 'DISMISSED' ? 'danger' : 'navy'} disabled={!statusText.trim()} loading={update.isPending}
                onClick={() => save(pendingStatus === 'DISMISSED' ? { status: 'DISMISSED', reason: statusText } : { status: 'PLAN_DOCUMENTED', note: statusText },
                  `Status: ${ALERT_STATUS_LABEL[pendingStatus!]}`)}>Confirm</Button>
              <Button size="sm" variant="ghost" onClick={() => { setPendingStatus(null); setStatusText('') }}>Cancel</Button>
            </div>
          </div>
        )}
        <div className="mt-4 space-y-3 border-t border-line pt-4">
          <div>
            <div className="mb-1.5 flex items-center gap-1.5 text-[12.5px] font-medium text-navy-700"><Phone className="h-3.5 w-3.5 text-navy-400" />Contact with referring provider</div>
            <div className="flex rounded-lg bg-navy-50 p-0.5 text-[12.5px]">
              {(['NOT_STARTED', 'PLANNED', 'COMPLETED'] as const).map((c) => (
                <button key={c} disabled={!canEdit || update.isPending} onClick={() => save({ contactStatus: c }, `Contact: ${CONTACT_LABEL[c]}`)}
                  className={cx('flex-1 rounded-md px-2 py-1.5 font-medium disabled:cursor-not-allowed', a.contactStatus === c ? 'bg-white text-navy-900 shadow-sm' : 'text-navy-500 hover:text-navy-800')}>
                  {CONTACT_LABEL[c]}
                </button>
              ))}
            </div>
          </div>
          <Toggle label="Planning discussion occurred" checked={a.planningDiscussionOccurred} disabled={!canEdit || update.isPending}
            onChange={(v) => save({ planningDiscussionOccurred: v }, v ? 'Planning discussion recorded' : 'Planning discussion cleared')} />
          <Toggle label="Further information needed" checked={a.infoNeeded} disabled={!canEdit || update.isPending}
            onChange={(v) => save({ infoNeeded: v }, v ? 'Marked: information needed' : 'Cleared: information needed')} />
        </div>
      </Card>

      <Card>
        <CardHeader icon={<MessageSquarePlus className="h-4 w-4" />} title="Coordination notes" subtitle="Human-entered and visible to the care team" />
        <form onSubmit={(e) => {
          e.preventDefault()
          addNote.mutate({ noteType, body }, {
            onSuccess: () => { setBody(''); toast({ tone: 'success', title: 'Note saved', body: 'Persisted to the COORDINATION_NOTES table.' }) },
            onError: (err) => toast({ tone: 'error', title: 'Could not save note', body: (err as Error).message }),
          })
        }} className="space-y-2">
          <select value={noteType} onChange={(e) => setNoteType(e.target.value)} className="h-8 w-full rounded-lg border border-line bg-white px-2 text-[13px]">
            {['GENERAL', 'INFO_REQUEST', 'CONTACT', 'PLANNING_DISCUSSION', 'PLAN_SUMMARY'].map((t) => <option key={t} value={t}>{NOTE_TYPE_LABEL[t]}</option>)}
          </select>
          <textarea value={body} onChange={(e) => setBody(e.target.value)} rows={3} placeholder="e.g. Contacted Dr. Voss's office; hematology consult confirmed before induction."
            className="w-full rounded-lg border border-line px-3 py-2 text-[13px] focus:border-coral-300 focus:outline-none focus:ring-2 focus:ring-coral-100" />
          <Button type="submit" variant="navy" size="sm" disabled={!body.trim()} loading={addNote.isPending}>Add note</Button>
        </form>
        <ol className="mt-4 space-y-2.5">
          {[...data.notes].reverse().map((n) => (
            <li key={n.noteId} className={cx('rounded-lg border px-3 py-2',
              n.noteType === 'SYSTEM' ? 'border-amber-200 bg-amber-50/60' : n.noteType === 'STATUS' ? 'border-line bg-canvas' : 'border-line bg-white')}>
              <div className="flex flex-wrap items-center gap-x-2 text-[11.5px] text-navy-500">
                <span className="font-semibold text-navy-800">{n.authorName}</span>
                <Badge tone={n.noteType === 'SYSTEM' ? 'amber' : n.noteType === 'STATUS' ? 'neutral' : 'sage'}>{NOTE_TYPE_LABEL[n.noteType] ?? n.noteType}</Badge>
                <span>{relative(n.createdAt)}</span>
              </div>
              <div className="mt-1 whitespace-pre-wrap text-[13px] text-navy-800">{n.body}</div>
            </li>
          ))}
          {data.notes.length === 0 && <li className="text-[13px] text-navy-400">No notes yet.</li>}
        </ol>
      </Card>
    </div>
  )
}

function Toggle({ label, checked, onChange, disabled }: { label: string; checked: boolean; onChange: (v: boolean) => void; disabled?: boolean }) {
  return (
    <label className={cx('flex items-center justify-between gap-3 text-[13px] text-navy-800', disabled && 'opacity-60')}>
      {label}
      <button type="button" role="switch" aria-checked={checked} disabled={disabled} onClick={() => onChange(!checked)}
        className={cx('relative h-5 w-9 rounded-full transition-colors disabled:cursor-not-allowed', checked ? 'bg-sage-500' : 'bg-navy-200')}>
        <span className={cx('absolute top-0.5 h-4 w-4 rounded-full bg-white shadow transition-all', checked ? 'left-[18px]' : 'left-0.5')} />
      </button>
    </label>
  )
}
