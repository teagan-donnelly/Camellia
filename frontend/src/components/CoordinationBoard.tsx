import { ArrowRight, CircleCheck, ClipboardList, FileText, NotebookPen, Sparkles, Workflow } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAlert } from '../api/hooks'
import type { AlertStatus, AlertSummary } from '../api/types'
import { AiAnswerView } from './ai'
import { CitationChip } from './records'
import { AlertStatusBadge } from './status'
import { Badge, Card, EmptyState, ErrorBox, Loading } from './ui'
import { ALERT_STATUS_LABEL, CONTACT_LABEL, NOTE_TYPE_LABEL, cx, daysLabel, fmtDate, fmtDateTime, relative } from '../lib/format'

const COLUMNS: AlertStatus[] = ['NEW', 'UNDER_REVIEW', 'COORDINATION_NEEDED', 'PLAN_DOCUMENTED', 'RESOLVED']

/**
 * Coordination board (formerly the standalone Care Coordination page), shown as a tab of Anesthesia OB Alerts.
 * Keeps documented facts, AI observations, human follow-up notes and planning status visibly separate.
 */
export function CoordinationBoard({ data }: { data: AlertSummary[] }) {
  const [selected, setSelected] = useState<string | null>(null)
  const summaryRef = useRef<HTMLDivElement>(null)
  const scrollPending = useRef(false)
  useEffect(() => {
    if (!selected && data.length) setSelected(data[0].alertId)
  }, [data, selected])
  // The summary sits below the board, often off-screen: bring it into view so the click visibly does something. Scroll
  // again once it has loaded, because the page is shorter while it loads.
  const showSummary = () => summaryRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' })
  const select = (alertId: string) => {
    setSelected(alertId)
    scrollPending.current = true
    requestAnimationFrame(showSummary)
  }
  const onSummaryLoaded = () => {
    if (!scrollPending.current) return
    scrollPending.current = false
    requestAnimationFrame(showSummary)
  }

  return (
    <div>
      {data.length === 0 && <Card><EmptyState title="No coordination alerts" /></Card>}
      {data.length > 0 && (
        <>
          <div className="scroll-thin mb-6 grid auto-cols-[minmax(220px,1fr)] grid-flow-col gap-3 overflow-x-auto pb-2">
            {COLUMNS.map((s) => {
              const items = data.filter((a) => a.status === s)
              return (
                <div key={s} className="rounded-xl bg-navy-50/70 p-2.5">
                  <div className="mb-2 flex items-center justify-between px-1">
                    <span className="text-[12.5px] font-semibold text-navy-700">{ALERT_STATUS_LABEL[s]}</span>
                    <span className="rounded bg-white px-1.5 text-[11.5px] text-navy-500">{items.length}</span>
                  </div>
                  <div className="space-y-2">
                    {items.map((a) => (
                      <button key={a.alertId} onClick={() => select(a.alertId)} aria-pressed={selected === a.alertId}
                        className={cx('w-full rounded-lg border bg-white p-3 text-left transition-shadow hover:shadow-md', selected === a.alertId ? 'border-coral-300 ring-2 ring-coral-100' : 'border-line hover:border-coral-200')}>
                        <div className="text-[13.5px] font-semibold text-navy-900">{a.patientName}</div>
                        <div className="mt-0.5 text-[12px] text-navy-500">{a.encounterType} · {daysLabel(a.daysUntilEncounter)}</div>
                        <div className="mt-2 flex flex-wrap gap-1">
                          <Badge tone={a.contactStatus === 'COMPLETED' ? 'sage' : 'neutral'}>Contact: {CONTACT_LABEL[a.contactStatus]}</Badge>
                          {a.planningDiscussionOccurred && <Badge tone="sage">Discussed</Badge>}
                          {a.infoNeeded && <Badge tone="amber">Info needed</Badge>}
                        </div>
                      </button>
                    ))}
                    {items.length === 0 && <div className="px-1 py-3 text-center text-[12px] text-navy-400">—</div>}
                  </div>
                </div>
              )
            })}
            {data.some((a) => a.status === 'DISMISSED') && (
              <div className="rounded-xl bg-navy-50/40 p-2.5">
                <div className="mb-2 px-1 text-[12.5px] font-semibold text-navy-500">Dismissed</div>
                {data.filter((a) => a.status === 'DISMISSED').map((a) => (
                  <button key={a.alertId} onClick={() => select(a.alertId)} aria-pressed={selected === a.alertId} className="mb-2 w-full rounded-lg border border-line bg-white p-3 text-left text-[13px] text-navy-600 hover:border-coral-200">{a.patientName}</button>
                ))}
              </div>
            )}
          </div>
          <div ref={summaryRef} className="scroll-mt-4">{selected && <Summary alertId={selected} onLoaded={onSummaryLoaded} />}</div>
        </>
      )}
    </div>
  )
}

function Summary({ alertId, onLoaded }: { alertId: string; onLoaded: () => void }) {
  const { data, isLoading, error } = useAlert(alertId)
  const loaded = !!data
  useEffect(() => {
    if (loaded) onLoaded()
  }, [loaded, alertId, onLoaded])
  if (isLoading) return <Loading />
  if (error) return <ErrorBox error={error} />
  if (!data) return null
  const a = data.alert
  const facts = data.evidence.filter((e) => e.role !== 'SUPPORTING')
  const humanNotes = data.notes.filter((n) => n.noteType !== 'STATUS' && n.noteType !== 'SYSTEM')
  const ai = data.flag?.aiExplanation

  return (
    <Card padded={false}>
      <div className="flex flex-wrap items-center justify-between gap-3 border-b border-line px-5 py-4">
        <div>
          <div className="text-[12px] font-semibold uppercase tracking-wide text-coral-600">Coordination summary</div>
          <h2 className="text-[18px] font-semibold text-navy-950">{data.patient.name} — {a.encounterType}</h2>
          <div className="text-[13px] text-navy-500">{fmtDateTime(a.encounterDate)} · {a.encounterLocation} · assigned to {a.assignedTeam}</div>
        </div>
        <Link to={`/alerts/${a.alertId}`} className="inline-flex items-center gap-1.5 rounded-lg bg-navy-800 px-3 py-2 text-[13px] font-medium text-white hover:bg-navy-900">
          Open review workspace <ArrowRight className="h-3.5 w-3.5" />
        </Link>
      </div>
      <div className="grid grid-cols-1 divide-y divide-line lg:grid-cols-2 lg:divide-x lg:divide-y-0">
        <Section icon={<FileText className="h-4 w-4" />} tone="sage" title="Documented clinical facts" hint="From source records — rule output">
          <ul className="space-y-2">
            {facts.map((e) => (
              <li key={e.recordId} className="text-[13px] text-navy-800">
                {e.description} <CitationChip recordId={e.recordId} label={e.record?.orgName?.split(' ')[0]} />
              </li>
            ))}
          </ul>
        </Section>
        <Section icon={<Sparkles className="h-4 w-4" />} tone="coral" title="AI-generated observations" hint="Gemini — advisory, cited">
          {ai ? <AiAnswerView answer={ai} compact /> : (
            <p className="text-[13px] text-navy-500">No Gemini explanation saved yet. Generate one from the <Link to={`/alerts/${a.alertId}`} className="font-medium text-coral-700">review workspace</Link>.</p>
          )}
        </Section>
      </div>
      <div className="grid grid-cols-1 divide-y divide-line border-t border-line lg:grid-cols-2 lg:divide-x lg:divide-y-0">
        <Section icon={<NotebookPen className="h-4 w-4" />} tone="navy" title="Human-entered follow-up notes" hint="Written by care-team members">
          {humanNotes.length === 0 ? <p className="text-[13px] text-navy-400">No follow-up notes yet.</p> : (
            <ol className="space-y-2">
              {humanNotes.map((n) => (
                <li key={n.noteId} className="rounded-lg border border-line px-3 py-2">
                  <div className="flex flex-wrap gap-x-2 text-[11.5px] text-navy-500"><span className="font-semibold text-navy-800">{n.authorName}</span><span>{NOTE_TYPE_LABEL[n.noteType]}</span><span>{relative(n.createdAt)}</span></div>
                  <div className="mt-0.5 text-[13px] text-navy-800">{n.body}</div>
                </li>
              ))}
            </ol>
          )}
        </Section>
        <Section icon={<Workflow className="h-4 w-4" />} tone="amber" title="Planning status" hint="Administrative — not clinical clearance">
          <dl className="grid grid-cols-2 gap-3 text-[13px]">
            <div><dt className="text-navy-400">Workflow status</dt><dd className="mt-0.5"><AlertStatusBadge status={a.status} /></dd></div>
            <div><dt className="text-navy-400">Acknowledged</dt><dd className="mt-0.5 text-navy-800">{a.acknowledgedBy ?? 'Not yet'}</dd></div>
            <div><dt className="text-navy-400">Referring-provider contact</dt><dd className="mt-0.5 text-navy-800">{CONTACT_LABEL[a.contactStatus]}</dd></div>
            <div><dt className="text-navy-400">Planning discussion</dt><dd className="mt-0.5 text-navy-800">{a.planningDiscussionOccurred ? <span className="inline-flex items-center gap-1 text-sage-700"><CircleCheck className="h-3.5 w-3.5" />Occurred</span> : 'Not yet'}</dd></div>
            <div><dt className="text-navy-400">Further information needed</dt><dd className="mt-0.5 text-navy-800">{a.infoNeeded ? 'Yes' : 'No'}</dd></div>
            <div><dt className="text-navy-400">Review due</dt><dd className="mt-0.5 text-navy-800">{fmtDate(a.reviewDueDate)}</dd></div>
            <div className="col-span-2"><dt className="text-navy-400">Last updated</dt><dd className="mt-0.5 text-navy-800">{fmtDateTime(a.updatedAt)} by {a.updatedBy}</dd></div>
            {data.dismissReason && <div className="col-span-2"><dt className="text-navy-400">Dismissal reason</dt><dd className="mt-0.5 text-navy-800">{data.dismissReason}</dd></div>}
          </dl>
        </Section>
      </div>
      <div className="flex items-center gap-2 border-t border-line bg-canvas px-5 py-2.5 text-[11.5px] text-navy-500">
        <ClipboardList className="h-3.5 w-3.5" />Camellia does not generate treatment plans. Clinical decisions and plans are made and documented by qualified clinicians.
      </div>
    </Card>
  )
}

function Section({ icon, title, hint, tone, children }: { icon: React.ReactNode; title: string; hint: string; tone: 'sage' | 'coral' | 'navy' | 'amber'; children: React.ReactNode }) {
  const color = { sage: 'text-sage-700 bg-sage-50', coral: 'text-coral-700 bg-coral-50', navy: 'text-navy-700 bg-navy-50', amber: 'text-amber-700 bg-amber-50' }[tone]
  return (
    <div className="p-5">
      <div className="mb-3 flex items-center gap-2">
        <span className={cx('flex h-7 w-7 items-center justify-center rounded-lg', color)}>{icon}</span>
        <div>
          <div className="text-[14px] font-semibold text-navy-900">{title}</div>
          <div className="text-[11.5px] text-navy-400">{hint}</div>
        </div>
      </div>
      {children}
    </div>
  )
}
