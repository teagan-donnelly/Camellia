import { ArrowRight, Bell, Building2, CalendarClock, FileText, Info, KanbanSquare, ListTodo, Sparkle, Users } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { useAlerts } from '../api/hooks'
import type { AlertStatus, AlertSummary } from '../api/types'
import { CoordinationBoard } from '../components/CoordinationBoard'
import { AlertStatusBadge, PriorityBadge } from '../components/status'
import { Badge, Button, Card, EmptyState, ErrorBox, Loading, PageHeader, Tabs } from '../components/ui'
import { ALERT_STATUS_LABEL, CONTACT_LABEL, cx, daysLabel, fmtDate, relative } from '../lib/format'
import { useApp } from '../state/AppContext'

type View = 'active' | AlertStatus | 'all'
type Tab = 'queue' | 'board'
const TEAM = 'Anesthesia OB Coordination'

export default function AnesthesiaAlerts() {
  const { data, isLoading, error } = useAlerts()
  const [view, setView] = useState<View>('active')
  const [params, setParams] = useSearchParams()
  const tab: Tab = params.get('tab') === 'board' ? 'board' : 'queue'
  const { user, can } = useApp()
  const navigate = useNavigate()

  const teamAlerts = useMemo(() => (data ?? []).filter((a) => a.assignedTeam === TEAM), [data])
  const rows = useMemo(() => teamAlerts
    .filter((a) => view === 'all' || (view === 'active' ? !['RESOLVED', 'DISMISSED'].includes(a.status) : a.status === view))
    .sort((a, b) => (a.encounterDate ?? '').localeCompare(b.encounterDate ?? '')), [teamAlerts, view])
  const count = (s: AlertStatus) => teamAlerts.filter((a) => a.status === s).length
  const canUpdate = can('UPDATE_COORDINATION')
  const canNote = can('ADD_COORDINATION_NOTES')

  return (
    <div>
      <PageHeader
        eyebrow="Feature C · Primary workflow"
        title={<span className="inline-flex items-center gap-2.5"><Bell className="h-5 w-5 text-coral-500" />Anesthesia OB Alerts</span>}
        subtitle="Work queue and coordination board for advance anesthesia planning. Obstetric patients with an upcoming delivery-related encounter and documented findings are routed here automatically — before the day of delivery."
      />

      <div className="mb-5">
        <Tabs<Tab> value={tab} onChange={(t) => setParams(t === 'board' ? { tab: 'board' } : {}, { replace: true })} tabs={[
          { id: 'queue', label: <span className="inline-flex items-center gap-1.5"><ListTodo className="h-4 w-4" />Work queue</span>, count: data ? teamAlerts.filter((a) => !['RESOLVED', 'DISMISSED'].includes(a.status)).length : undefined },
          { id: 'board', label: <span className="inline-flex items-center gap-1.5"><KanbanSquare className="h-4 w-4" />Coordination board</span> },
        ]} />
      </div>

      <div className="mb-5 flex flex-wrap items-center gap-3 rounded-xl border border-line bg-white px-4 py-3">
        <div className="flex items-center gap-2 text-[13px]">
          <Users className="h-4 w-4 text-navy-400" />
          <span className="text-navy-500">Assigned team:</span><span className="font-semibold text-navy-900">{TEAM}</span>
        </div>
        <span className="h-4 w-px bg-line" />
        <span className={cx('text-[12.5px]', canUpdate ? 'text-sage-700' : 'text-amber-700')}>
          {canUpdate ? `${user.name} (${user.roleLabel}) can acknowledge alerts and update coordination status.`
            : `${user.name} (${user.roleLabel}) can view alerts${canNote ? ' and add coordination notes' : ''}, but not change their workflow status.`}
        </span>
        <span className="ml-auto inline-flex items-center gap-1 text-[12px] text-navy-400"><Info className="h-3.5 w-3.5" />In-app queue only. No real notifications are sent.</span>
      </div>

      {isLoading && <Loading />}
      {error && <ErrorBox error={error} />}
      {tab === 'board' && data && <CoordinationBoard data={teamAlerts} />}
      {tab === 'queue' && <>
      <div className="mb-5 grid grid-cols-2 gap-3 md:grid-cols-3 xl:grid-cols-6">
        {(['NEW', 'UNDER_REVIEW', 'COORDINATION_NEEDED', 'PLAN_DOCUMENTED', 'RESOLVED', 'DISMISSED'] as AlertStatus[]).map((s) => (
          <button key={s} onClick={() => setView(view === s ? 'active' : s)}
            className={cx('rounded-xl border bg-white px-4 py-3 text-left transition-colors', view === s ? 'border-coral-300 ring-2 ring-coral-100' : 'border-line hover:border-navy-200')}>
            <div className="text-[12px] font-medium text-navy-500">{ALERT_STATUS_LABEL[s]}</div>
            <div className={cx('mt-0.5 text-[24px] font-semibold leading-tight', s === 'NEW' && count(s) > 0 ? 'text-coral-600' : 'text-navy-900')}>{count(s)}</div>
          </button>
        ))}
      </div>

      <div className="mb-3 flex items-center gap-2 text-[13px]">
        {(['active', 'all'] as const).map((v) => (
          <button key={v} onClick={() => setView(v)} className={cx('rounded-full px-3 py-1 font-medium ring-1 ring-inset',
            view === v ? 'bg-navy-800 text-white ring-navy-800' : 'bg-white text-navy-600 ring-line hover:bg-navy-50')}>
            {v === 'active' ? 'Active queue' : 'All alerts'}
          </button>
        ))}
        {view !== 'active' && view !== 'all' && <Badge tone="coral">Filtered: {ALERT_STATUS_LABEL[view]}</Badge>}
      </div>

      {data && rows.length === 0 && <Card><EmptyState title="Queue is clear" icon={<Bell className="h-5 w-5" />}>No alerts in this view.</EmptyState></Card>}
      {rows.length > 0 && (
        <Card padded={false} className="overflow-hidden">
          <div className="overflow-x-auto">
            <table className="w-full min-w-[1100px] text-left text-[13.5px]">
              <thead className="bg-canvas text-[11.5px] font-semibold uppercase tracking-wide text-navy-500">
                <tr>
                  <th className="w-1 p-0" />
                  <th className="px-4 py-3">Patient</th>
                  <th className="px-4 py-3">Upcoming encounter</th>
                  <th className="px-4 py-3">Relevant clinical concerns</th>
                  <th className="px-4 py-3">Source findings</th>
                  <th className="px-4 py-3">Review status</th>
                  <th className="px-4 py-3">Last updated</th>
                  <th className="px-4 py-3" />
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {rows.map((a) => <AlertRow key={a.alertId} a={a} onOpen={() => navigate(`/alerts/${a.alertId}`)} />)}
              </tbody>
            </table>
          </div>
        </Card>
      )}
      </>}
    </div>
  )
}

function AlertRow({ a, onOpen }: { a: AlertSummary; onOpen: () => void }) {
  const [categories, findings] = (a.concernSummary ?? '').split(' — ')
  const soon = a.daysUntilEncounter <= 21
  return (
    <tr onClick={onOpen} className="cursor-pointer align-top hover:bg-canvas">
      <td className="p-0"><div className={cx('h-full min-h-[96px] w-1', a.status === 'NEW' ? 'bg-coral-500' : soon ? 'bg-amber-400' : 'bg-navy-200')} /></td>
      <td className="px-4 py-3.5">
        <div className="font-semibold text-navy-900">{a.patientName}</div>
        <div className="text-[12px] text-navy-500">{a.patientDisplayId}</div>
        <div className="mt-1.5"><Badge tone={a.alertType === 'MULTIDISCIPLINARY_OB' ? 'navy' : 'coral'}>{a.alertType === 'MULTIDISCIPLINARY_OB' ? 'Multidisciplinary' : 'Anesthesia planning'}</Badge></div>
      </td>
      <td className="px-4 py-3.5">
        <div className="flex items-center gap-1.5 font-medium text-navy-900"><CalendarClock className="h-3.5 w-3.5 text-navy-400" />{a.encounterType}</div>
        <div className="mt-0.5 text-[12.5px] text-navy-600">{fmtDate(a.encounterDate)} · <span className={cx('font-medium', soon ? 'text-amber-700' : 'text-navy-700')}>{daysLabel(a.daysUntilEncounter)}</span></div>
        <div className="mt-0.5 text-[12px] text-navy-400">{a.encounterLocation}</div>
        <div className="mt-1.5"><PriorityBadge priority={a.priority} /></div>
      </td>
      <td className="max-w-[320px] px-4 py-3.5">
        <div className="flex flex-wrap gap-1">{(categories ?? '').split(' · ').filter(Boolean).map((c) => <Badge key={c} tone="amber">{c}</Badge>)}</div>
        <div className="mt-1.5 line-clamp-3 text-[12.5px] leading-snug text-navy-600">{findings}</div>
      </td>
      <td className="px-4 py-3.5">
        <div className="flex items-center gap-1.5 text-navy-800"><FileText className="h-3.5 w-3.5 text-navy-400" />{a.evidenceCount} records</div>
        <div className="mt-1 space-y-0.5">{a.sourceOrganizations.map((o) => (
          <div key={o} className="flex items-center gap-1 text-[12px] text-navy-500"><Building2 className="h-3 w-3" />{o}</div>
        ))}</div>
      </td>
      <td className="px-4 py-3.5">
        <AlertStatusBadge status={a.status} />
        <div className="mt-1.5 space-y-0.5 text-[12px] text-navy-500">
          <div>Contact: {CONTACT_LABEL[a.contactStatus]}</div>
          <div>{a.acknowledgedBy ? `Ack: ${a.acknowledgedBy.split(',')[0]}` : 'Not acknowledged'}</div>
          {a.noteCount > 0 && <div>{a.noteCount} note{a.noteCount > 1 ? 's' : ''}</div>}
        </div>
        {a.evidenceUpdatedAt && <div className="mt-1"><Badge tone="amber"><Sparkle className="h-3 w-3" />New evidence</Badge></div>}
      </td>
      <td className="px-4 py-3.5 text-[12.5px] text-navy-500">
        <div>{relative(a.updatedAt)}</div>
        <div className="text-[11.5px] text-navy-400">{a.updatedBy}</div>
        <div className="mt-1 text-[11.5px] text-navy-400">Review due {fmtDate(a.reviewDueDate)}</div>
      </td>
      <td className="px-4 py-3.5 text-right">
        <Button size="sm" variant={a.status === 'NEW' ? 'primary' : 'secondary'} onClick={(e) => { e.stopPropagation(); onOpen() }}>Review <ArrowRight className="h-3.5 w-3.5" /></Button>
      </td>
    </tr>
  )
}
