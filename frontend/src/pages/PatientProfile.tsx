import {
  ArrowLeft, Baby, Bell, Building2, CalendarClock, ChevronRight, FileSearch, FlaskConical, HeartPulse, Pill, Quote, Sparkles, Upload,
} from 'lucide-react'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useEncounters, useExtraction, usePatient } from '../api/hooks'
import { PatientMonitoringCard } from '../components/monitoring'
import { PatientDocuments, TraitChips } from '../components/documents'
import type { EncounterView, LabResult, PatientProfile as Profile, RecordDto } from '../api/types'
import { AiBadge, AssistantPanel, GeminiThinking } from '../components/ai'
import { CitationChip, RecordTypeIcon } from '../components/records'
import { AlertStatusBadge, FlagStatusBadge, PriorityBadge } from '../components/status'
import { Badge, Button, Card, CardHeader, EmptyState, ErrorBox, KeyValue, Loading, Tabs, SynMrn } from '../components/ui'
import { FORMAT_LABEL, RECORD_TYPE_LABEL, RULE_LABEL, cx, daysLabel, fmtDate, fmtDateTime, initials, titleCase } from '../lib/format'
import { useApp } from '../state/AppContext'

type Tab = 'overview' | 'stays' | 'timeline' | 'conditions' | 'medications' | 'labs' | 'notes' | 'documents' | 'appointments' | 'flags' | 'ai'
const TABS: Tab[] = ['overview', 'stays', 'timeline', 'conditions', 'medications', 'labs', 'notes', 'documents', 'appointments', 'flags', 'ai']

export default function PatientProfile() {
  const { id = '' } = useParams()
  const { data, isLoading, error } = usePatient(id)
  const encounters = useEncounters(id)
  // The selected tab lives in the URL (?tab=…), so "Back to Patient Profile" from a monitoring page and a page
  // refresh both return to the same tab.
  const [params, setParams] = useSearchParams()
  const requested = params.get('tab') as Tab | null
  const tab: Tab = requested && TABS.includes(requested) ? requested : 'overview'
  const setTab = (t: Tab) => setParams((prev) => { prev.set('tab', t); prev.delete('encounter'); return prev }, { replace: true })
  /** Opens the Documents tab with an encounter preselected for upload (from the Hospital stays tab). */
  const uploadFor = (encounterId: string) => setParams((prev) => { prev.set('tab', 'documents'); prev.set('encounter', encounterId); return prev }, { replace: true })
  const monitored = (encounters.data ?? []).filter((e) => e.monitoring)

  if (isLoading) return <Loading label="Retrieving patient records…" />
  if (error) return <ErrorBox error={error} title="Could not load patient" />
  if (!data) return null
  const p = data.patient
  const notes = data.timeline.filter((r) => r.recordType === 'NOTE' || r.recordType === 'IMAGING')

  return (
    <div>
      <Link to="/patients" className="mb-4 inline-flex items-center gap-1 text-[13px] text-navy-500 hover:text-navy-800"><ArrowLeft className="h-3.5 w-3.5" /> Patients</Link>
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_400px]">
        <div className="min-w-0 space-y-5">
          <Card>
            <div className="flex flex-wrap items-start gap-4">
              <div className="flex h-14 w-14 shrink-0 items-center justify-center rounded-2xl bg-navy-800 text-lg font-semibold text-white">{initials(p.name)}</div>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <h1 className="text-[22px] font-semibold tracking-tight text-navy-950">{p.name}</h1>
                  {p.obstetric && <Badge tone="coral"><Baby className="h-3 w-3" />Obstetric · {p.gestationalNote}</Badge>}
                  <Badge tone="outline">Synthetic patient</Badge>
                </div>
                <div className="mt-1 text-[13px] text-navy-500"><SynMrn id={p.displayId} /> · <span title="Camellia's internal patient key">ID {p.patientId}</span> · {p.age} years · {p.sex === 'F' ? 'Female' : p.sex === 'M' ? 'Male' : p.sex} · DOB {fmtDate(p.dateOfBirth)}</div>
                {p.summary && <p className="mt-2 max-w-3xl text-[13.5px] leading-relaxed text-navy-700">{p.summary}</p>}
                <TraitChips patientId={p.patientId} />
              </div>
              <div className="flex gap-2">
                <Link to={`/search?patientId=${p.patientId}`}><Button size="sm"><FileSearch className="h-3.5 w-3.5" />Search records</Button></Link>
              </div>
            </div>
            <div className="mt-5 grid grid-cols-2 gap-4 border-t border-line pt-4 md:grid-cols-4">
              <KeyValue label="Estimated due date">{p.estimatedDueDate ? <>{fmtDate(p.estimatedDueDate)} <span className="text-[11.5px] text-navy-400">(estimate, not an admission)</span></> : '—'}</KeyValue>
              <KeyValue label="Next encounter">{p.nextAppointment ? <>{p.nextAppointment.type}<div className="text-[12px] text-navy-500">{fmtDate(p.nextAppointment.scheduledStart)} · {daysLabel(p.nextAppointment.daysUntil)}</div></> : 'None scheduled'}</KeyValue>
              <KeyValue label="Open review items">{p.openAlertCount} alert{p.openAlertCount === 1 ? '' : 's'} · {p.openFlagCount} flag{p.openFlagCount === 1 ? '' : 's'}</KeyValue>
              <KeyValue label="Records">{p.recordCount} from {data.identifiers.length} organizations</KeyValue>
            </div>
            <div className="mt-4 flex flex-wrap gap-2">
              {data.identifiers.map((i) => (
                <span key={i.orgId} className="inline-flex items-center gap-1.5 rounded-lg border border-line bg-canvas px-2.5 py-1 text-[12px] text-navy-600">
                  <Building2 className="h-3.5 w-3.5 text-navy-400" />{i.orgName}<span className="font-mono text-[11px] text-navy-400">MRN {i.sourceMrn}</span>
                </span>
              ))}
            </div>
          </Card>

          {data.alerts.filter((a) => a.status !== 'RESOLVED' && a.status !== 'DISMISSED').map((a) => (
            <Link key={a.alertId} to={`/alerts/${a.alertId}`} className="flex items-center gap-3 rounded-xl border border-coral-200 bg-coral-50 px-4 py-3 hover:bg-coral-100/60">
              <Bell className="h-5 w-5 text-coral-600" />
              <div className="min-w-0 flex-1">
                <div className="text-[14px] font-semibold text-navy-900">{a.title}</div>
                <div className="text-[12.5px] text-navy-600">Routed to {a.assignedTeam} · {a.encounterType} {daysLabel(a.daysUntilEncounter)}</div>
              </div>
              <AlertStatusBadge status={a.status} />
              <ChevronRight className="h-4 w-4 text-navy-400" />
            </Link>
          ))}

          {/* One card per hospital stay that has a wristband session; nothing for stays without monitoring. */}
          {monitored.length > 0 && (
            <div className={cx('grid grid-cols-1 gap-4', monitored.length > 1 && 'lg:grid-cols-2')}>
              {monitored.map((e) => <PatientMonitoringCard key={e.encounterId} patientId={p.patientId} encounter={e} fromTab={tab} />)}
            </div>
          )}

          <Card padded={false}>
            <div className="px-4 pt-2">
              <Tabs<Tab> value={tab} onChange={setTab} tabs={[
                { id: 'overview', label: 'Overview' },
                { id: 'stays', label: 'Hospital stays', count: encounters.data?.length },
                { id: 'timeline', label: 'Timeline', count: data.timeline.length },
                { id: 'conditions', label: 'Conditions', count: data.conditions.length },
                { id: 'medications', label: 'Medications', count: data.medications.length },
                { id: 'labs', label: 'Labs', count: data.labs.length },
                { id: 'notes', label: 'Notes', count: notes.length },
                { id: 'documents', label: 'Documents' },
                { id: 'appointments', label: 'Appointments', count: data.appointments.length },
                { id: 'flags', label: 'Flags', count: data.flags.length },
                { id: 'ai', label: <span className="inline-flex items-center gap-1"><Sparkles className="h-3.5 w-3.5 text-coral-500" />AI observations</span>, count: data.aiObservations.length },
              ]} />
            </div>
            <div className="p-4">
              {tab === 'overview' && <Overview data={data} onTab={setTab} />}
              {tab === 'stays' && <Stays patientId={p.patientId} encounters={encounters.data ?? []} loading={encounters.isLoading} onUpload={uploadFor} />}
              {tab === 'timeline' && <Timeline records={data.timeline} />}
              {tab === 'conditions' && <Conditions data={data} />}
              {tab === 'medications' && <Medications data={data} />}
              {tab === 'labs' && <Labs labs={data.labs} names={data.organizationNames} />}
              {tab === 'notes' && <Timeline records={notes} expanded />}
              {tab === 'documents' && <PatientDocuments key={params.get('encounter') ?? ''} patientId={p.patientId} encounters={encounters.data ?? []}
                initialEncounterId={params.get('encounter') ?? undefined} />}
              {tab === 'appointments' && <Appointments data={data} />}
              {tab === 'flags' && <Flags data={data} />}
              {tab === 'ai' && <AiObservations data={data} />}
            </div>
          </Card>
        </div>

        <div className="xl:sticky xl:top-0 xl:self-start">
          <AssistantPanel
            context={{ type: 'PATIENT', id: p.patientId, label: `${p.name} (${p.displayId})` }}
            suggestions={[
              'Summarize the history relevant to upcoming care.',
              'Has this patient had previous anesthesia complications?',
              'What medications is this patient currently taking?',
              'Show all fractures documented across this patient’s history.',
              'Is there an upcoming encounter that may need advance preparation?',
            ]}
            className="xl:h-[calc(100vh-150px)]"
          />
        </div>
      </div>
    </div>
  )
}

function Overview({ data, onTab }: { data: Profile; onTab: (t: Tab) => void }) {
  const upcoming = data.appointments.filter((a) => a.daysUntil >= 0)
  const active = data.conditions.filter((c) => c.clinicalStatus !== 'resolved')
  return (
    <div className="grid grid-cols-1 gap-5 lg:grid-cols-2">
      <div>
        <SectionTitle icon={<CalendarClock className="h-4 w-4" />} title="Upcoming encounters" />
        {upcoming.length === 0 ? <p className="text-[13px] text-navy-400">None scheduled.</p> : (
          <ul className="space-y-2">{upcoming.map((a) => (
            <li key={a.appointmentId} className={cx('rounded-lg border px-3 py-2', a.deliveryRelated ? 'border-coral-200 bg-coral-50/40' : 'border-line')}>
              <div className="flex items-center justify-between gap-2">
                <span className="text-[13.5px] font-medium text-navy-900">{a.type}</span>
                <CitationChip recordId={a.recordId} label="source" />
              </div>
              <div className="text-[12.5px] text-navy-500">{fmtDateTime(a.scheduledStart)} · {daysLabel(a.daysUntil)} · {a.orgName}{a.department ? ` — ${a.department}` : ''}</div>
            </li>
          ))}</ul>
        )}
      </div>
      <div>
        <SectionTitle icon={<HeartPulse className="h-4 w-4" />} title="Active documented conditions" action={<button className="text-[12px] text-coral-700" onClick={() => onTab('conditions')}>All</button>} />
        {active.length === 0 ? <p className="text-[13px] text-navy-400">None documented.</p> : (
          <ul className="space-y-1.5">{active.map((c) => (
            <li key={c.conditionId} className="flex items-start justify-between gap-2 text-[13.5px]">
              <span className="text-navy-800">{c.display}<span className="ml-1 text-[12px] text-navy-400">{c.code}</span></span>
              <CitationChip recordId={c.recordId} label={data.organizationNames[c.orgId]?.split(' ')[0] ?? c.orgId} />
            </li>
          ))}</ul>
        )}
      </div>
      <div className="lg:col-span-2">
        <SectionTitle icon={<FlaskConical className="h-4 w-4" />} title="Laboratory results outside the reporting lab's reference range" />
        <OutOfRange labs={data.labs} />
      </div>
    </div>
  )
}

function OutOfRange({ labs }: { labs: LabResult[] }) {
  const out = labs.filter(isOut)
  if (!out.length) return <p className="text-[13px] text-navy-400">None in the available records.</p>
  return (
    <div className="flex flex-wrap gap-2">
      {out.map((l) => (
        <div key={l.labId} className="flex items-center gap-2 rounded-lg border border-amber-200 bg-amber-50/60 px-3 py-1.5 text-[13px]">
          <span className="font-medium text-navy-900">{l.testName}</span>
          <span className="text-amber-700">{l.valueNum ?? l.valueText} {l.unit} ({l.abnormalFlag})</span>
          <span className="text-[12px] text-navy-400">{fmtDate(l.collectedAt)}</span>
          <CitationChip recordId={l.recordId} label="source" />
        </div>
      ))}
    </div>
  )
}

const isOut = (l: LabResult) => (l.valueNum != null && ((l.refLow != null && l.valueNum < l.refLow) || (l.refHigh != null && l.valueNum > l.refHigh)))
  || (!!l.abnormalFlag && l.abnormalFlag !== 'N' && l.valueNum == null)

function SectionTitle({ icon, title, action }: { icon: React.ReactNode; title: string; action?: React.ReactNode }) {
  return (
    <div className="mb-2 flex items-center justify-between">
      <h3 className="flex items-center gap-1.5 text-[13px] font-semibold text-navy-800"><span className="text-navy-400">{icon}</span>{title}</h3>
      {action}
    </div>
  )
}

function Timeline({ records, expanded }: { records: RecordDto[]; expanded?: boolean }) {
  const { openRecord } = useApp()
  const [type, setType] = useState<string>('ALL')
  const types = useMemo(() => Array.from(new Set(records.map((r) => r.recordType))), [records])
  const shown = records.filter((r) => type === 'ALL' || r.recordType === type)
  if (!records.length) return <EmptyState title="No records" />
  return (
    <div>
      {!expanded && (
        <div className="mb-3 flex flex-wrap gap-1.5">
          {['ALL', ...types].map((t) => (
            <button key={t} onClick={() => setType(t)} className={cx('rounded-full px-2.5 py-1 text-[12px] font-medium ring-1 ring-inset',
              type === t ? 'bg-navy-800 text-white ring-navy-800' : 'bg-white text-navy-600 ring-line hover:bg-navy-50')}>
              {t === 'ALL' ? 'All types' : RECORD_TYPE_LABEL[t as keyof typeof RECORD_TYPE_LABEL]}
            </button>
          ))}
        </div>
      )}
      <ol className="relative space-y-2 border-l border-line pl-5">
        {shown.map((r) => (
          <li key={r.recordId} className="relative">
            <span className="absolute -left-[29px] top-3 flex h-4 w-4 items-center justify-center rounded-full bg-white ring-1 ring-line">
              <span className={cx('h-1.5 w-1.5 rounded-full', r.tags.includes('ABNORMAL_LAB') || r.tags.includes('FRACTURE') ? 'bg-amber-500' : 'bg-navy-300')} />
            </span>
            <button onClick={() => openRecord(r.recordId)} className="w-full rounded-lg border border-line bg-white px-3 py-2.5 text-left hover:border-coral-200 hover:bg-coral-50/30">
              <div className="flex flex-wrap items-center gap-2">
                <RecordTypeIcon type={r.recordType} className="h-3.5 w-3.5 text-navy-400" />
                <span className="text-[13.5px] font-medium text-navy-900">{r.title}</span>
                <span className="text-[12px] text-navy-400">{fmtDate(r.recordDate)}</span>
              </div>
              <div className="mt-0.5 flex flex-wrap gap-x-2 text-[12px] text-navy-500">
                <span>{r.orgName}</span>{r.author && <span>· {r.author}</span>}{r.specialty && <span>· {r.specialty}</span>}
                <span className="text-navy-400">· {FORMAT_LABEL[r.sourceFormat] ?? r.sourceFormat}</span>
              </div>
              {expanded && r.contentText && <p className="mt-2 text-[13px] leading-relaxed text-navy-700">{r.contentText}</p>}
            </button>
          </li>
        ))}
      </ol>
    </div>
  )
}

function Conditions({ data }: { data: Profile }) {
  if (!data.conditions.length) return <EmptyState title="No documented conditions" />
  return (
    <div className="-mx-4 overflow-x-auto px-4">
    <table className="w-full min-w-[620px] text-left text-[13.5px]">
      <thead className="text-[11.5px] uppercase tracking-wide text-navy-500"><tr><th className="py-2">Condition</th><th>Status</th><th>Onset</th><th>Recorded by</th><th>Source</th></tr></thead>
      <tbody className="divide-y divide-line">
        {data.conditions.map((c) => (
          <tr key={c.conditionId}>
            <td className="py-2 pr-3 text-navy-900">{c.display}<div className="text-[11.5px] text-navy-400">{c.codeSystem} {c.code}</div></td>
            <td><Badge tone={c.clinicalStatus === 'resolved' ? 'neutral' : 'sage'}>{titleCase(c.clinicalStatus ?? 'unknown')}</Badge></td>
            <td className="text-navy-600">{fmtDate(c.onsetDate)}</td>
            <td className="text-navy-600">{c.recordedBy}</td>
            <td><CitationChip recordId={c.recordId} label={data.organizationNames[c.orgId] ?? c.orgId} /></td>
          </tr>
        ))}
      </tbody>
    </table>
    </div>
  )
}

function Medications({ data }: { data: Profile }) {
  if (!data.medications.length) return <EmptyState title="No medications documented" />
  return (
    <ul className="divide-y divide-line">
      {data.medications.map((m) => (
        <li key={m.medicationId} className="flex items-start gap-3 py-2.5">
          <Pill className="mt-0.5 h-4 w-4 text-navy-400" />
          <div className="min-w-0 flex-1">
            <div className="text-[13.5px] font-medium text-navy-900">{m.name}</div>
            <div className="text-[12.5px] text-navy-500">{m.dose} · {m.route} · started {fmtDate(m.startDate)}{m.endDate ? ` · ended ${fmtDate(m.endDate)}` : ''} · {m.prescriber}</div>
          </div>
          <Badge tone={m.status === 'active' ? 'sage' : 'neutral'}>{titleCase(m.status ?? 'unknown')}</Badge>
          <CitationChip recordId={m.recordId} label={data.organizationNames[m.orgId]?.split(' ')[0] ?? m.orgId} />
        </li>
      ))}
    </ul>
  )
}

function Labs({ labs, names }: { labs: LabResult[]; names: Record<string, string> }) {
  const groups = useMemo(() => {
    const m = new Map<string, LabResult[]>()
    for (const l of labs) m.set(l.testCode ?? l.testName ?? '?', [...(m.get(l.testCode ?? l.testName ?? '?') ?? []), l])
    return Array.from(m.values()).map((g) => g.sort((a, b) => (a.collectedAt ?? '').localeCompare(b.collectedAt ?? '')))
  }, [labs])
  if (!labs.length) return <EmptyState title="No laboratory results" />
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      {groups.map((g) => (
        <div key={g[0].labId} className="rounded-xl border border-line p-3">
          <div className="mb-2 flex items-baseline justify-between gap-2">
            <div>
              <div className="text-[13.5px] font-semibold text-navy-900">{g[0].testName}</div>
              <div className="text-[11.5px] text-navy-400">LOINC {g[0].testCode} · reference {g[0].refText ?? '—'} {g[0].unit}</div>
            </div>
            {g.length > 1 && <Sparkline labs={g} />}
          </div>
          <table className="w-full text-[13px]">
            <tbody className="divide-y divide-line">
              {[...g].reverse().map((l) => (
                <tr key={l.labId}>
                  <td className="py-1.5 text-navy-600">{fmtDate(l.collectedAt)}</td>
                  <td className={cx('py-1.5 font-medium', isOut(l) ? 'text-amber-700' : 'text-navy-900')}>{l.valueNum ?? l.valueText} {l.unit} {isOut(l) && <span className="text-[11px]">({l.abnormalFlag})</span>}</td>
                  <td className="py-1.5 text-right"><CitationChip recordId={l.recordId} label={names[l.orgId]?.split(' ')[0] ?? l.orgId} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ))}
    </div>
  )
}

/** Tiny trend line with the reference range shaded. */
function Sparkline({ labs }: { labs: LabResult[] }) {
  const vals = labs.map((l) => l.valueNum ?? 0)
  const lo = labs[0].refLow, hi = labs[0].refHigh
  const min = Math.min(...vals, lo ?? Infinity) * 0.9, max = Math.max(...vals, hi ?? -Infinity) * 1.05
  const W = 120, H = 36
  const y = (v: number) => H - ((v - min) / (max - min || 1)) * H
  const x = (i: number) => (i / (vals.length - 1)) * (W - 6) + 3
  return (
    <svg width={W} height={H} className="shrink-0" aria-label="Trend">
      {lo != null && hi != null && <rect x={0} y={y(hi)} width={W} height={Math.max(0, y(lo) - y(hi))} className="fill-sage-50" />}
      <polyline points={vals.map((v, i) => `${x(i)},${y(v)}`).join(' ')} fill="none" className="stroke-navy-400" strokeWidth={1.5} />
      {vals.map((v, i) => <circle key={i} cx={x(i)} cy={y(v)} r={2.5} className={isOut(labs[i]) ? 'fill-amber-500' : 'fill-navy-600'} />)}
    </svg>
  )
}

function Appointments({ data }: { data: Profile }) {
  if (!data.appointments.length) return <EmptyState title="No appointments" />
  return (
    <ul className="divide-y divide-line">
      {data.appointments.map((a) => (
        <li key={a.appointmentId} className="flex items-start gap-3 py-2.5">
          <CalendarClock className="mt-0.5 h-4 w-4 text-navy-400" />
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-[13.5px] font-medium text-navy-900">{a.type}</span>
              {a.deliveryRelated && <Badge tone="coral">Delivery-related</Badge>}
            </div>
            <div className="text-[12.5px] text-navy-500">{fmtDateTime(a.scheduledStart)} · {daysLabel(a.daysUntil)} · {a.orgName}{a.department ? ` — ${a.department}` : ''} · {a.provider}</div>
            {a.description && <div className="mt-0.5 text-[12.5px] text-navy-600">{a.description}</div>}
          </div>
          <CitationChip recordId={a.recordId} label="source" />
        </li>
      ))}
    </ul>
  )
}

function Flags({ data }: { data: Profile }) {
  const navigate = useNavigate()
  if (!data.flags.length) return <EmptyState title="No flags for this patient">No prototype screening rule currently matches the available records.</EmptyState>
  return (
    <ul className="space-y-2">
      {data.flags.map((f) => (
        <li key={f.flagId}>
          <button onClick={() => navigate(`/flags/${f.flagId}`)} className="w-full rounded-xl border border-line p-3 text-left hover:border-coral-200 hover:bg-coral-50/30">
            <div className="flex flex-wrap items-center gap-2">
              <span className="text-[14px] font-medium text-navy-900">{f.title}</span>
              <FlagStatusBadge status={f.status} /><PriorityBadge priority={f.reviewPriority} />
              <Badge>{RULE_LABEL[f.ruleId] ?? f.ruleId}</Badge>
            </div>
            <p className="mt-1 line-clamp-2 text-[13px] text-navy-600">{f.detectionReason}</p>
          </button>
        </li>
      ))}
    </ul>
  )
}

function AiObservations({ data }: { data: Profile }) {
  const extract = useExtraction(data.patient.patientId)
  const { toast } = useApp()
  const obs = data.aiObservations
  const grouped = useMemo(() => {
    const m = new Map<string, typeof obs>()
    obs.forEach((o) => m.set(o.category, [...(m.get(o.category) ?? []), o]))
    return Array.from(m.entries())
  }, [obs])
  return (
    <div>
      <Card className="mb-4 border-coral-200 bg-gradient-to-r from-coral-50/70 to-white">
        <CardHeader
          icon={<Sparkles className="h-4 w-4 text-coral-500" />}
          title="Clinical document interpretation (Gemini)"
          subtitle="Gemini reads this patient's notes and imaging reports and extracts structured observations. Each observation must quote its source verbatim; unverifiable ones are rejected before storage."
          actions={<Button variant="primary" loading={extract.isPending} onClick={() => extract.mutate(undefined, {
            onSuccess: (r) => toast({ tone: 'success', title: `${r.observationsAccepted} observations stored`, body: `${r.recordsAnalyzed} documents analyzed by ${r.model}; ${r.observationsRejected} rejected by validation.` }),
            onError: (e) => toast({ tone: 'error', title: 'Extraction failed', body: (e as Error).message }),
          })}><Sparkles className="h-4 w-4" />{obs.length ? 'Re-run extraction' : 'Extract with Gemini'}</Button>}
        />
        {extract.isPending && <GeminiThinking label="Gemini is reading clinical notes from multiple organizations…" />}
        {extract.data && extract.data.rejectionReasons.length > 0 && (
          <details className="mt-2 text-[12.5px] text-navy-500">
            <summary className="cursor-pointer">{extract.data.observationsRejected} observation(s) rejected by source verification</summary>
            <ul className="mt-1 list-disc pl-5">{extract.data.rejectionReasons.map((r, i) => <li key={i}>{r}</li>)}</ul>
          </details>
        )}
      </Card>
      {obs.length === 0 ? <EmptyState title="No AI observations yet" icon={<Sparkles className="h-5 w-5" />}>Run the extraction to populate this view.</EmptyState> : (
        <div className="space-y-4">
          <div className="flex items-center gap-2 text-[12px] text-navy-500"><AiBadge model={obs[0].model} /> generated {fmtDateTime(obs[0].createdAt)} · stored in the AI_OBSERVATIONS table with source record IDs</div>
          {grouped.map(([cat, items]) => (
            <div key={cat}>
              <h3 className="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-navy-500">{titleCase(cat)}</h3>
              <ul className="space-y-1.5">
                {items.map((o) => (
                  <li key={o.observationId} className="rounded-lg border border-line bg-white px-3 py-2">
                    <div className="flex items-start justify-between gap-2">
                      <span className="text-[13.5px] text-navy-900">{o.statement}</span>
                      <CitationChip recordId={o.recordId} />
                    </div>
                    <div className="mt-1 flex items-start gap-1.5 text-[12.5px] italic text-navy-500"><Quote className="mt-0.5 h-3 w-3 shrink-0" />{o.evidenceQuote}</div>
                  </li>
                ))}
              </ul>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}

/** All encounters; only stays with a monitoring session link to monitoring details. */
function Stays({ patientId, encounters, loading, onUpload }: {
  patientId: string; encounters: EncounterView[]; loading: boolean; onUpload: (encounterId: string) => void
}) {
  const { can } = useApp()
  if (loading) return <Loading />
  if (!encounters.length) return <p className="text-[13px] text-navy-400">No encounters recorded.</p>
  return (
    <ul className="divide-y divide-line">
      {encounters.map((e) => (
        <li key={e.encounterId} className="flex flex-wrap items-start gap-3 py-3">
          <div className="w-full shrink-0 text-[12.5px] text-navy-500 sm:w-[170px]">{fmtDate(e.startTime)}{e.inpatient ? ` – ${e.endTime ? fmtDate(e.endTime) : 'present'}` : ''}</div>
          <div className="min-w-0 flex-1">
            <div className="flex flex-wrap items-center gap-2 text-[13.5px] font-medium text-navy-900">
              {e.encounterType}{e.ongoing && <Badge tone="coral">Current stay</Badge>}
              {e.monitoring ? <Badge tone="sage">Wristband monitoring (simulated)</Badge> : e.inpatient ? <Badge tone="outline">No wearable monitoring</Badge> : null}
            </div>
            <div className="text-[12.5px] text-navy-600">{e.orgName} · {e.department} · {e.reason}</div>
          </div>
          <div className="flex shrink-0 flex-wrap items-center gap-x-4 gap-y-1">
            {can('UPLOAD_DOCUMENTS') && (
              <button type="button" onClick={() => onUpload(e.encounterId)} className="inline-flex items-center gap-1 text-[12.5px] font-medium text-navy-600 hover:text-coral-700">
                <Upload className="h-3.5 w-3.5" />Upload document
              </button>
            )}
            {e.monitoring && (
              <Link to={`/patients/${patientId}/encounters/${e.encounterId}/monitoring?from=stays`} className="text-[12.5px] font-medium text-coral-700 hover:text-coral-800">View Monitoring Details →</Link>
            )}
          </div>
        </li>
      ))}
    </ul>
  )
}
