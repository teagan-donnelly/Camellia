import { Activity as ActivityIcon, ArrowRight, AudioLines, Bell, CalendarClock, ClipboardList, Flag, PlayCircle, ShieldCheck, Sparkles, Users } from 'lucide-react'
import type { ReactNode } from 'react'
import type { Dashboard as DashboardDto } from '../api/types'
import { Link, useNavigate } from 'react-router-dom'
import { useDashboard, useNursingQueue, useRunAnalysis, useShifts } from '../api/hooks'
import { AlertStatusBadge, PriorityBadge } from '../components/status'
import { Badge, Button, Card, CardHeader, EmptyState, ErrorBox, Loading, PageHeader, SynMrn } from '../components/ui'
import { cx, fmtDate, relative } from '../lib/format'
import { useApp } from '../state/AppContext'

export default function Dashboard() {
  const { data, isLoading, error } = useDashboard()
  const run = useRunAnalysis()
  const { user, toast, can } = useApp()
  const navigate = useNavigate()

  const hour = new Date().getHours()
  const greeting = hour < 12 ? 'Good morning' : hour < 18 ? 'Good afternoon' : 'Good evening'
  const firstName = user.name.replace(/^Dr\.\s*/, '').split(' ')[0]

  return (
    <div>
      <PageHeader
        eyebrow={`${user.roleLabel} · ${user.department}`}
        title={`${greeting}, ${firstName}`}
        subtitle={<>
          Patients whose documented history may need advance preparation before their upcoming care — surfaced early, with the source records attached.
          <span className="mt-2 block text-[12.5px] text-navy-500">
            <ShieldCheck className="-mt-0.5 mr-1.5 inline h-3.5 w-3.5 text-sage-600" />
            Viewing as <span className="font-medium text-navy-700">{user.name}</span> — scope: {user.patientScope} <span className="text-navy-400">(simulated demo identity)</span>
          </span>
        </>}
        actions={can('CLINICAL_ALERTS') &&
          <Button
            variant="navy"
            loading={run.isPending}
            onClick={() => run.mutate(undefined, {
              onSuccess: (r) => toast({
                tone: 'success',
                title: 'Screening run complete',
                body: `${r.patientsEvaluated} patients evaluated · ${r.flagsCreated} new · ${r.flagsUpdated} updated · ${r.flagsUnchanged} unchanged (duplicates prevented)`,
              }),
              onError: (e) => toast({ tone: 'error', title: 'Screening run failed', body: (e as Error).message }),
            })}
          >
            <PlayCircle className="h-4 w-4" /> Run screening
          </Button>
        }
      />
      {isLoading && <Loading />}
      {error && <ErrorBox error={error} title="Could not load the dashboard" />}
      {data && (
        <div className="space-y-6">
          {(can('CLINICAL_BRIEFING') || can('NURSING_MONITORING') || can('IMPORT_RECORDS')) && <TodayStrip />}
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 xl:grid-cols-4">
            <Metric icon={<Users className="h-4 w-4" />} label={data.anesthesiaAccess ? 'Total patients' : 'Accessible patients'} value={data.totalPatients}
              hint={user.practices.length ? 'Registered to your practice' : 'Synthetic, across 5 organizations'} onClick={() => navigate('/patients')} />
            <Metric icon={<Flag className="h-4 w-4" />} label="Open clinical alerts" value={data.openFlags} hint="Unreviewed or in review" onClick={() => navigate('/flags')} />
            {data.anesthesiaAccess ? <>
              <Metric icon={<Bell className="h-4 w-4" />} label="Unreviewed anesthesia alerts" value={data.unreviewedAnesthesiaAlerts} accent
                hint={`${data.alertsInProgress} more in progress`} onClick={() => navigate('/alerts')} />
              <Metric icon={<CalendarClock className="h-4 w-4" />} label="Upcoming complex-care encounters" value={data.upcomingComplexEncounterCount}
                hint="With an open coordination alert" onClick={() => navigate('/alerts?tab=board')} />
            </> : <>
              <Metric icon={<ClipboardList className="h-4 w-4" />} label="Patients requiring review" value={data.patientsRequiringReview.length}
                hint="With an open clinical alert" onClick={() => navigate('/flags')} />
              <Metric icon={<CalendarClock className="h-4 w-4" />} label="Upcoming encounters" value={data.upcomingEncounters.length}
                hint="Scheduled in the next 60 days" onClick={() => navigate('/patients')} />
            </>}
          </div>

          <div className="grid grid-cols-1 gap-6 xl:grid-cols-[1.6fr_1fr]">
            {!data.anesthesiaAccess ? <UpcomingEncounters data={data} /> : <Card padded={false}>
              <div className="px-5 pt-5">
                <CardHeader
                  icon={<CalendarClock className="h-4 w-4" />}
                  title="Upcoming complex-care encounters"
                  subtitle="Scheduled deliveries with documented findings that may warrant advance planning"
                  actions={<Link to="/alerts" className="inline-flex items-center gap-1 text-[13px] font-medium text-coral-700 hover:text-coral-800">Open queue <ArrowRight className="h-3.5 w-3.5" /></Link>}
                />
              </div>
              {data.upcomingComplexEncounters.length === 0 ? (
                <EmptyState title="No upcoming encounters with open alerts" />
              ) : (
                <ul className="divide-y divide-line border-t border-line">
                  {data.upcomingComplexEncounters.map((a) => (
                    <li key={a.alertId}>
                      <Link to={`/alerts/${a.alertId}`} className="flex items-center gap-4 px-5 py-3.5 hover:bg-canvas">
                        <div className={cx('flex h-12 w-12 shrink-0 flex-col items-center justify-center rounded-xl border',
                          a.daysUntilEncounter <= 21 ? 'border-amber-200 bg-amber-50 text-amber-700' : 'border-line bg-canvas text-navy-600')}>
                          <span className="text-[17px] font-semibold leading-none">{a.daysUntilEncounter}</span>
                          <span className="mt-0.5 text-[10px] font-medium uppercase">days</span>
                        </div>
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-2">
                            <span className="font-medium text-navy-900">{a.patientName}</span>
                            <span className="text-[12px] text-navy-400">{a.patientDisplayId}</span>
                            <AlertStatusBadge status={a.status} />
                          </div>
                          <div className="mt-0.5 truncate text-[13px] text-navy-600">{a.encounterType} · {fmtDate(a.encounterDate)}</div>
                          <div className="mt-1 truncate text-[12.5px] text-navy-500">{a.concernSummary?.split(' — ')[0]}</div>
                        </div>
                        <div className="hidden shrink-0 text-right sm:block">
                          <PriorityBadge priority={a.priority} />
                          <div className="mt-1 text-[11.5px] text-navy-400">Review by {fmtDate(a.reviewDueDate)}</div>
                        </div>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Card>}

            <Card>
              <CardHeader icon={<Flag className="h-4 w-4" />} title="Patients requiring review" subtitle={data.anesthesiaAccess ? 'Open clinical or coordination alerts' : 'Open clinical alerts'} />
              {data.patientsRequiringReview.length === 0 ? <EmptyState title="Nothing awaiting review" /> : (
                <ul className="space-y-2">
                  {data.patientsRequiringReview.map((p) => (
                    <li key={p.patientId}>
                      <Link to={`/patients/${p.patientId}`} className="block rounded-lg border border-line px-3 py-2.5 hover:border-coral-200 hover:bg-coral-50/40">
                        <div className="flex items-center justify-between gap-2">
                          <span className="text-[13.5px] font-medium text-navy-900">{p.name}</span>
                          <div className="flex gap-1">
                            {p.openAlerts > 0 && <Badge tone="coral">{p.openAlerts} alert{p.openAlerts > 1 ? 's' : ''}</Badge>}
                            {p.openFlags > 0 && <Badge>{p.openFlags} clinical alert{p.openFlags > 1 ? 's' : ''}</Badge>}
                          </div>
                        </div>
                        <div className="mt-0.5 truncate text-[12.5px] text-navy-500">{p.flagTitles.join(' · ') || 'Coordination in progress'}</div>
                      </Link>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>

          <div className="grid grid-cols-1 gap-6 xl:grid-cols-[1.6fr_1fr]">
            <Card>
              <CardHeader icon={<ActivityIcon className="h-4 w-4" />} title="Recent activity" subtitle="Screening runs, routed alerts, reviews and notes — persisted in the database" />
              {data.recentActivity.length === 0 ? <EmptyState title="No activity yet" /> : (
                <ol className="relative space-y-3 border-l border-line pl-5">
                  {data.recentActivity.map((a) => (
                    <li key={a.activityId} className="relative">
                      <span className={cx('absolute -left-[25px] top-1 h-2.5 w-2.5 rounded-full ring-4 ring-white',
                        a.action.startsWith('AI') ? 'bg-coral-400' : a.action.includes('ALERT') ? 'bg-amber-500' : a.action === 'ANALYSIS_RUN' ? 'bg-navy-400' : 'bg-sage-500')} />
                      <div className="flex flex-wrap items-baseline gap-x-2 text-[13px]">
                        <span className="font-medium text-navy-800">{actionLabel(a.action)}</span>
                        <span className="text-navy-400">{relative(a.createdAt)} · {a.actor}</span>
                      </div>
                      <div className="text-[13px] text-navy-600">{a.detail}</div>
                    </li>
                  ))}
                </ol>
              )}
            </Card>
            <Card>
              <CardHeader icon={<Sparkles className="h-4 w-4" />} title="How Camellia works" />
              <ol className="space-y-3 text-[13px] text-navy-600">
                {[
                  ['Ingest', 'Records from 5 fictional organizations (FHIR-like, legacy export, HL7 v2 labs, dictation text) are normalized into Snowflake.'],
                  ['Screen', 'Explainable, configurable rules look for upcoming deliveries plus documented findings relevant to anesthesia planning.'],
                  ['Route', 'Matches become flags and in-app alerts for the Anesthesia OB Coordination team — no external notifications.'],
                  ['Review', 'Clinicians inspect the source records and ask Gemini questions; every AI claim cites a real record.'],
                  ['Coordinate', 'Acknowledgement, notes, contact and planning status are tracked and persisted.'],
                ].map(([t, d], i) => (
                  <li key={t} className="flex gap-3">
                    <span className="flex h-6 w-6 shrink-0 items-center justify-center rounded-full bg-coral-50 text-[12px] font-semibold text-coral-700">{i + 1}</span>
                    <span><span className="font-medium text-navy-900">{t}.</span> {d}</span>
                  </li>
                ))}
              </ol>
              {data.lastAnalysisRun && (
                <div className="mt-4 rounded-lg bg-canvas px-3 py-2 text-[12px] text-navy-500">
                  Last screening run {relative(String(data.lastAnalysisRun.startedAt))}: {String(data.lastAnalysisRun.patientsEvaluated)} patients,
                  {' '}{String(data.lastAnalysisRun.flagsCreated)} new, {String(data.lastAnalysisRun.flagsUnchanged)} unchanged.
                  {data.anchorDate && <> Demo dates anchored to {fmtDate(data.anchorDate)}.</>}
                </div>
              )}
            </Card>
          </div>
        </div>
      )}
    </div>
  )
}

/** General upcoming encounters for the user's accessible patients (roles without the Anesthesia OB workflow). */
function UpcomingEncounters({ data }: { data: DashboardDto }) {
  return (
    <Card padded={false}>
      <div className="px-5 pt-5">
        <CardHeader icon={<CalendarClock className="h-4 w-4" />} title="Upcoming encounters" subtitle="Scheduled care for patients in your access scope (next 60 days)" />
      </div>
      {data.upcomingEncounters.length === 0 ? <EmptyState title="No upcoming encounters" /> : (
        <ul className="divide-y divide-line border-t border-line">
          {data.upcomingEncounters.map((e) => (
            <li key={e.appointment.appointmentId}>
              <Link to={`/patients/${e.patientId}`} className="flex items-center gap-4 px-5 py-3.5 hover:bg-canvas">
                <div className="flex h-12 w-12 shrink-0 flex-col items-center justify-center rounded-xl border border-line bg-canvas text-navy-600">
                  <span className="text-[17px] font-semibold leading-none">{e.appointment.daysUntil}</span>
                  <span className="mt-0.5 text-[10px] font-medium uppercase">days</span>
                </div>
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-medium text-navy-900">{e.patientName}</span>
                    <SynMrn id={e.displayId} className="text-navy-400" />
                  </div>
                  <div className="mt-0.5 truncate text-[13px] text-navy-600">{e.appointment.type} · {fmtDate(e.appointment.scheduledStart)}</div>
                  <div className="mt-0.5 truncate text-[12.5px] text-navy-500">{e.appointment.orgName}{e.appointment.department ? ` — ${e.appointment.department}` : ''}</div>
                </div>
              </Link>
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function Metric({ icon, label, value, hint, accent, onClick }: { icon: ReactNode; label: string; value: number; hint: string; accent?: boolean; onClick: () => void }) {
  return (
    <button onClick={onClick} className={cx('group rounded-xl border bg-white p-5 text-left transition-shadow hover:shadow-md',
      accent ? 'border-coral-200' : 'border-line')}>
      <div className="flex items-center justify-between">
        <span className="text-[13px] font-medium text-navy-500">{label}</span>
        <span className={cx('flex h-8 w-8 items-center justify-center rounded-lg', accent ? 'bg-coral-50 text-coral-600' : 'bg-navy-50 text-navy-500')}>{icon}</span>
      </div>
      <div className={cx('mt-2 text-[32px] font-semibold leading-none tracking-tight', accent ? 'text-coral-600' : 'text-navy-950')}>{value}</div>
      <div className="mt-2 text-[12px] text-navy-400">{hint}</div>
    </button>
  )
}

function actionLabel(action: string) {
  const map: Record<string, string> = {
    ANALYSIS_RUN: 'Screening run',
    FLAG_CREATED: 'Flag created',
    ALERT_ROUTED: 'Alert routed',
    ALERT_UPDATED: 'Alert updated',
    NOTE_ADDED: 'Note added',
    FLAG_STATUS: 'Flag status changed',
    EVIDENCE_UPDATED: 'Evidence updated',
    AI_EXPLANATION: 'Gemini explanation',
    AI_CHAT: 'Gemini conversation',
    AI_SEARCH_SUMMARY: 'Gemini search summary',
    AI_EXTRACTION: 'Gemini extraction',
    DB_CHECK: 'Database check',
  }
  return map[action] ?? action
}

/** Entry points for the V3 workflows: the next shift's briefing, nursing monitoring reviews, record import. */
function TodayStrip() {
  const { can } = useApp()
  const shifts = useShifts(can('CLINICAL_BRIEFING'))
  const queue = useNursingQueue(can('NURSING_MONITORING'))
  const next = shifts.data?.[0]
  const openReviews = queue.data?.alerts.filter((a) => a.alert.status !== 'RESOLVED').length ?? 0
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      {can('CLINICAL_BRIEFING') && (
        <Link to="/briefing" className="flex items-center gap-4 rounded-xl border border-coral-200 bg-gradient-to-r from-coral-50 to-white p-4 hover:shadow-sm">
          <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-coral-600 text-white"><AudioLines className="h-5 w-5" /></span>
          <div className="min-w-0 flex-1">
            <div className="text-[14.5px] font-semibold text-navy-950">Camellia Briefing</div>
            <div className="text-[12.5px] text-navy-600">{next ? `${next.label} · ${fmtDate(next.startAt)} · ${next.authorizedPatients} patients · ${next.openItems} open items`
              : shifts.isLoading ? 'Loading your shifts…' : 'No simulated shifts for this user.'}</div>
          </div>
          <ArrowRight className="h-4 w-4 text-coral-600" />
        </Link>
      )}
      {can('NURSING_MONITORING') && (
        <Link to="/monitoring" className="flex items-center gap-4 rounded-xl border border-line bg-white p-4 hover:shadow-sm">
          <span className={cx('flex h-11 w-11 items-center justify-center rounded-xl', openReviews ? 'bg-amber-100 text-amber-700' : 'bg-sage-50 text-sage-700')}><ActivityIcon className="h-5 w-5" /></span>
          <div className="min-w-0 flex-1">
            <div className="text-[14.5px] font-semibold text-navy-950">Nursing Monitoring</div>
            <div className="text-[12.5px] text-navy-600">{queue.data ? `${queue.data.patients.length} monitored stays · ${openReviews} open review${openReviews === 1 ? '' : 's'} (simulated wristbands)` : 'Loading…'}</div>
          </div>
          <ArrowRight className="h-4 w-4 text-navy-400" />
        </Link>
      )}
      {can('IMPORT_RECORDS') && (
        <Link to="/patients/import" className="flex items-center gap-4 rounded-xl border border-line bg-white p-4 hover:shadow-sm">
          <span className="flex h-11 w-11 items-center justify-center rounded-xl bg-navy-800 text-white"><Users className="h-5 w-5" /></span>
          <div className="min-w-0 flex-1">
            <div className="text-[14.5px] font-semibold text-navy-950">Import Patient Records</div>
            <div className="text-[12.5px] text-navy-600">Preview and import synthetic FHIR, Camellia JSON or CSV files.</div>
          </div>
          <ArrowRight className="h-4 w-4 text-navy-400" />
        </Link>
      )}
    </div>
  )
}
