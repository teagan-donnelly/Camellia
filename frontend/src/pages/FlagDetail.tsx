import { ArrowLeft, Bell, ChevronRight, ListChecks } from 'lucide-react'
import { useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { useFlag, useUpdateFlagStatus } from '../api/hooks'
import { AssistantPanel, FlagExplanationCard } from '../components/ai'
import { EvidenceList } from '../components/records'
import { FlagStatusBadge, PriorityBadge } from '../components/status'
import { Badge, Button, Card, CardHeader, ErrorBox, KeyValue, Loading } from '../components/ui'
import { RULE_LABEL, fmtDateTime } from '../lib/format'
import { useApp } from '../state/AppContext'

export default function FlagDetail() {
  const { id = '' } = useParams()
  const { data: f, isLoading, error } = useFlag(id)
  const update = useUpdateFlagStatus(id)
  const { toast } = useApp()
  const [dismissing, setDismissing] = useState(false)
  const [reason, setReason] = useState('')

  if (isLoading) return <Loading label="Loading flag and evidence…" />
  if (error) return <ErrorBox error={error} />
  if (!f) return null

  const setStatus = (status: string, r?: string) => update.mutate({ status, reason: r }, {
    onSuccess: () => { toast({ tone: 'success', title: 'Flag status saved', body: 'Persisted to the database.' }); setDismissing(false); setReason('') },
    onError: (e) => toast({ tone: 'error', title: 'Could not update flag', body: (e as Error).message }),
  })

  return (
    <div>
      <Link to="/flags" className="mb-4 inline-flex items-center gap-1 text-[13px] text-navy-500 hover:text-navy-800"><ArrowLeft className="h-3.5 w-3.5" /> Clinical flags</Link>
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_400px]">
        <div className="min-w-0 space-y-5">
          <Card>
            <div className="flex flex-wrap items-center gap-2">
              <Badge>{RULE_LABEL[f.ruleId] ?? f.ruleId} · v{f.ruleVersion}</Badge>
              <FlagStatusBadge status={f.status} />
              <PriorityBadge priority={f.reviewPriority} />
            </div>
            <h1 className="mt-2 text-[22px] font-semibold tracking-tight text-navy-950">{f.title}</h1>
            <div className="mt-1 text-[13.5px] text-navy-600">
              Patient: <Link to={`/patients/${f.patientId}`} className="font-medium text-navy-900 hover:text-coral-700">{f.patientName}</Link> · {f.patientDisplayId}
            </div>
            <div className="mt-4 rounded-xl border border-navy-100 bg-navy-50/60 p-4">
              <div className="mb-1 flex items-center gap-1.5 text-[12px] font-semibold uppercase tracking-wide text-navy-600"><ListChecks className="h-3.5 w-3.5" />Detection reason — deterministic rule, not AI</div>
              <p className="text-[14px] leading-relaxed text-navy-900">{f.detectionReason}</p>
            </div>
            <div className="mt-4 grid grid-cols-2 gap-4 md:grid-cols-4">
              <KeyValue label="Created">{fmtDateTime(f.createdAt)}</KeyValue>
              <KeyValue label="Last evaluated">{fmtDateTime(f.lastEvaluatedAt)}</KeyValue>
              <KeyValue label="Last updated by">{f.updatedBy ?? '—'}</KeyValue>
              <KeyValue label="Evidence updated">{f.evidenceUpdatedAt ? fmtDateTime(f.evidenceUpdatedAt) : 'No changes'}</KeyValue>
            </div>
            {f.statusReason && <div className="mt-3 text-[13px] text-navy-600">Status note: {f.statusReason}</div>}

            <div className="mt-5 flex flex-wrap items-center gap-2 border-t border-line pt-4">
              <span className="mr-1 text-[12.5px] font-medium text-navy-500">Review actions:</span>
              {f.status === 'UNREVIEWED' && <Button size="sm" variant="navy" onClick={() => setStatus('IN_REVIEW')} loading={update.isPending}>Start review</Button>}
              {(f.status === 'UNREVIEWED' || f.status === 'IN_REVIEW') && <Button size="sm" onClick={() => setStatus('REVIEWED')} loading={update.isPending}>Mark reviewed</Button>}
              {(f.status === 'UNREVIEWED' || f.status === 'IN_REVIEW') && <Button size="sm" variant="danger" onClick={() => setDismissing(true)}>Dismiss…</Button>}
              {(f.status === 'REVIEWED' || f.status === 'DISMISSED') && <Button size="sm" onClick={() => setStatus('IN_REVIEW')} loading={update.isPending}>Reopen</Button>}
              {f.alertId && (
                <Link to={`/alerts/${f.alertId}`} className="ml-auto inline-flex items-center gap-1.5 rounded-lg bg-coral-50 px-3 py-1.5 text-[13px] font-medium text-coral-700 ring-1 ring-inset ring-coral-200 hover:bg-coral-100">
                  <Bell className="h-3.5 w-3.5" /> Open Anesthesia OB alert <ChevronRight className="h-3.5 w-3.5" />
                </Link>
              )}
            </div>
            {dismissing && (
              <div className="mt-3 flex flex-wrap gap-2">
                <input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="Reason for dismissal (required)"
                  className="h-9 min-w-[280px] flex-1 rounded-lg border border-line px-3 text-sm focus:border-coral-300 focus:outline-none focus:ring-2 focus:ring-coral-100" />
                <Button size="md" variant="danger" disabled={!reason.trim()} onClick={() => setStatus('DISMISSED', reason)}>Confirm dismissal</Button>
                <Button size="md" variant="ghost" onClick={() => setDismissing(false)}>Cancel</Button>
              </div>
            )}
            <p className="mt-3 text-[11.5px] text-navy-400">Review statuses are administrative workflow states. They do not indicate that a patient is clinically cleared.</p>
          </Card>

          <FlagExplanationCard flagId={f.flagId} cached={f.aiExplanation} />

          <Card>
            <CardHeader title="Supporting evidence" subtitle={`${f.evidence.length} source records — click any record to open the original document`} />
            <EvidenceList evidence={f.evidence} />
          </Card>
        </div>
        <div className="xl:sticky xl:top-0 xl:self-start">
          <AssistantPanel
            context={{ type: 'FLAG', id: f.flagId, label: `${f.title} — ${f.patientName}` }}
            suggestions={['Why was this flagged?', 'Where did you find that information?', 'Have there been similar results before?', 'What should the care team verify next?']}
            className="xl:h-[calc(100vh-150px)]"
          />
        </div>
      </div>
    </div>
  )
}
