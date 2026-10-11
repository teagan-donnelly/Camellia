import { useMutation } from '@tanstack/react-query'
import {
  Activity, ArrowLeft, BatteryMedium, Clock, Cpu, FastForward, HeartPulse, Move, NotebookPen, Play, Plug, RotateCcw, Square,
  Thermometer, Unplug, UserRoundCheck, Wind,
} from 'lucide-react'
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { patch, post } from '../api/client'
import { useInvalidateMonitoring, useMonitoring, useObservations, useTimeline } from '../api/hooks'
import type { MonitoringAlert, MonitoringDetail as Detail, ObservationSeries, TimelineItem } from '../api/types'
import { AssistantPanel } from '../components/ai'
import { ChartFrame, ColumnChart, DataTable, TimeSeriesChart } from '../components/charts'
import { DeviceStatusBadge, MonitoringAlertBadge, SimulatedTag } from '../components/monitoring'
import { Badge, Button, Card, CardHeader, ErrorBox, KeyValue, Loading, SynMrn } from '../components/ui'
import { MONITORING_LABEL, cx, fmtDate, fmtHospital, sinceSim } from '../lib/format'
import { useApp } from '../state/AppContext'

type Range = '24h' | '7d' | 'stay'

/** /patients/:patientId/encounters/:encounterId/monitoring — everything here belongs to this one hospital stay. */
export default function MonitoringDetail() {
  const { patientId = '', encounterId = '' } = useParams()
  const [params] = useSearchParams()
  const fromTab = params.get('from') ?? 'stays'
  const { data, isLoading, error } = useMonitoring(patientId, encounterId)
  const { can } = useApp()

  // Back returns to where the user came from: the Nursing Monitoring queue, or the same patient's profile tab.
  const fromQueue = fromTab === 'queue'
  const back = (
    <div className="mb-4 flex flex-wrap items-center gap-x-4 gap-y-1 text-[13px]">
      <Link to={fromQueue ? '/monitoring' : `/patients/${patientId}?tab=${encodeURIComponent(fromTab)}`}
        className="inline-flex items-center gap-1 text-navy-500 hover:text-navy-800"><ArrowLeft className="h-3.5 w-3.5" /> {fromQueue ? 'Back to Nursing Monitoring' : 'Back to Patient Profile'}</Link>
      {fromQueue && <Link to={`/patients/${patientId}?tab=stays`} className="text-navy-500 hover:text-navy-800">Patient profile</Link>}
    </div>
  )

  if (isLoading) return <>{back}<Loading label="Loading monitoring for this hospital stay…" /></>
  if (error) return <>{back}<ErrorBox error={error} title="Monitoring is not available" /></>
  if (!data) return null
  const e = data.encounter
  const tz = data.timeZone

  return (
    <div>
      {back}
      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_380px]">
        <div className="min-w-0 space-y-5">
          <Card>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="text-[12px] font-semibold uppercase tracking-wide text-coral-600">Wearable monitoring · this hospital stay only</div>
                <h1 className="text-[22px] font-semibold tracking-tight text-navy-950">{data.patientName}</h1>
                <div className="mt-0.5 text-[13px] text-navy-500"><SynMrn id={data.displayId} /> · {e.orgName} · {e.department}</div>
                <div className="text-[13px] text-navy-500">{e.encounterType}: {fmtDate(e.startTime)} – {e.endTime ? fmtDate(e.endTime) : 'present'} · {e.reason}</div>
              </div>
              <div className="flex flex-col items-end gap-1.5">
                {data.card ? <DeviceStatusBadge status={data.card.deviceStatus} /> : <Badge tone="neutral">No monitoring session</Badge>}
                <SimulatedTag />
              </div>
            </div>
            <p className="mt-3 rounded-lg bg-canvas px-3 py-2 text-[12px] text-navy-600">{data.notice} Times shown in hospital time ({tz}); the simulation has its own clock.</p>
          </Card>

          {!data.card ? (
            <Card><p className="text-[13px] text-navy-600">No wristband was used during this stay, so there is no monitoring data. Detailed readings always stay with the stay they were recorded in.</p></Card>
          ) : (
            <>
              <Summary data={data} />
              <Charts patientId={patientId} encounterId={encounterId} tz={tz} />
              <AlertsCard data={data} />
              <TimelineCard patientId={patientId} encounterId={encounterId} tz={tz} simNow={data.card.simulatedNow} />
            </>
          )}
          <DeviceCard data={data} />
        </div>

        <div className="space-y-5 xl:sticky xl:top-0 xl:self-start">
          {data.card && data.canDocument && <RepositioningCard data={data} />}
          {data.canSimulate && <SimulatorCard data={data} />}
          {data.card && can('GEMINI_ASSISTANT') && (
            <AssistantPanel
              context={{ type: 'MONITORING', id: encounterId, label: `${data.patientName} — ${e.orgName} stay` }}
              suggestions={[
                'How much has this patient walked today?',
                'When was their last activity?',
                'Show their heart rate trend.',
                'Why was the mobility alert generated?',
                'Has repositioning been documented?',
                'What monitoring data is available during this hospital stay?',
              ]}
              className="h-[560px]"
            />
          )}
        </div>
      </div>
    </div>
  )
}

function Summary({ data }: { data: Detail }) {
  const c = data.card!
  const tz = data.timeZone
  const tiles = [
    { icon: <Activity className="h-4 w-4" />, label: 'Walking today', value: `${c.walkingTodayMinutes} min`, hint: 'Device-estimated' },
    { icon: <Clock className="h-4 w-4" />, label: 'Last detected activity', value: c.lastActivityAt ? fmtHospital(c.lastActivityAt, tz, false) : 'None', hint: c.lastActivityAt ? `${MONITORING_LABEL[c.lastActivityType ?? ''] ?? ''} · ${sinceSim(c.lastActivityAt, c.simulatedNow)}` : '' },
    { icon: <HeartPulse className="h-4 w-4" />, label: 'Latest heart rate', value: c.latestHeartRate ? `${c.latestHeartRate.value} bpm` : '—', hint: c.latestHeartRate ? fmtHospital(c.latestHeartRate.at, tz, false) : '' },
    { icon: <Wind className="h-4 w-4" />, label: 'Latest SpO₂', value: c.latestSpo2 ? `${c.latestSpo2.value}%` : '—', hint: c.latestSpo2 ? fmtHospital(c.latestSpo2.at, tz, false) : '' },
    { icon: <Thermometer className="h-4 w-4" />, label: 'Skin temperature', value: c.latestSkinTemp ? `${c.latestSkinTemp.value} °C` : '—', hint: 'Peripheral, not core' },
    { icon: <BatteryMedium className="h-4 w-4" />, label: 'Battery · last sync', value: c.batteryPct != null ? `${Math.round(c.batteryPct)}%` : '—', hint: fmtHospital(c.lastSyncAt, tz, false) },
  ]
  return (
    <div>
      {c.dataStale && c.deviceStatus !== 'STOPPED' && (
        <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[12.5px] text-amber-800">
          Readings are not current (device {c.deviceStatus === 'DISCONNECTED' ? 'disconnected' : 'not syncing'} since {fmtHospital(c.lastSyncAt, tz)}).
          Missing readings are not evidence of patient inactivity.
        </div>
      )}
      <div className="grid grid-cols-2 gap-3 md:grid-cols-3">
        {tiles.map((t) => (
          <div key={t.label} className="rounded-xl border border-line bg-white p-3">
            <div className="flex items-center gap-1.5 text-[12px] text-navy-500">{t.icon}{t.label}</div>
            <div className={cx('mt-1 text-[20px] font-semibold text-navy-950', c.dataStale && t.label.startsWith('Latest') && 'text-navy-400')}>{t.value}</div>
            <div className="text-[11.5px] text-navy-400">{t.hint}</div>
          </div>
        ))}
      </div>
      <div className="mt-2 text-[11.5px] text-navy-400">Simulated time now: {fmtHospital(c.simulatedNow, tz)}</div>
    </div>
  )
}

function Charts({ patientId, encounterId, tz }: { patientId: string; encounterId: string; tz: string }) {
  const [range, setRange] = useState<Range>('24h')
  const { data, isFetching, error } = useObservations(patientId, encounterId, range)
  const fmt = (iso: string) => fmtHospital(iso, tz, range === '24h' ? false : true).replace(/ [A-Z]{2,4}$/, '')
  return (
    <section>
      {/* One filter row scopes every chart below. */}
      <div className="mb-3 flex flex-wrap items-center gap-2">
        <span className="text-[12.5px] font-medium text-navy-600">Range</span>
        {(['24h', '7d', 'stay'] as Range[]).map((r) => (
          <button key={r} onClick={() => setRange(r)} aria-pressed={range === r}
            className={cx('rounded-full px-3 py-1 text-[12.5px] font-medium ring-1 ring-inset', range === r ? 'bg-navy-800 text-white ring-navy-800' : 'bg-white text-navy-600 ring-line hover:bg-navy-50')}>
            {r === '24h' ? 'Last 24 hours' : r === '7d' ? 'Last 7 days' : 'Entire hospital stay'}
          </button>
        ))}
        {data && <span className="text-[11.5px] text-navy-400">{fmtHospital(data.from, tz)} – {fmtHospital(data.to, tz)} · clipped to this stay</span>}
      </div>
      {error && <ErrorBox error={error} />}
      {data && <ChartGrid data={data} fmt={fmt} stale={isFetching} tz={tz} />}
    </section>
  )
}

function ChartGrid({ data, fmt, stale, tz }: { data: ObservationSeries; fmt: (iso: string) => string; stale: boolean; tz: string }) {
  const series = [
    { key: 'HEART_RATE' as const, title: 'Heart rate', unit: 'bpm' },
    { key: 'SPO2' as const, title: 'SpO₂ (oxygen saturation)', unit: '%' },
    { key: 'SKIN_TEMP' as const, title: 'Skin temperature (peripheral)', unit: '°C' },
  ]
  const walkTotal = data.walking.reduce((s, w) => s + w.minutes, 0)
  return (
    <div className="grid grid-cols-1 gap-4 lg:grid-cols-2">
      <ChartFrame title={`Walking (${data.bucket === 'HOUR' ? 'minutes per hour' : 'minutes per day'})`} subtitle={`${walkTotal} min in this range · device-estimated`} stale={stale}
        table={<DataTable head={['Period', 'Walking (min)']} rows={data.walking.map((w) => [w.label, w.minutes])} />}>
        <ColumnChart data={data.walking.map((w) => ({ label: w.label, value: w.minutes }))} unit="min walking" />
      </ChartFrame>
      {series.map((s) => (
        <ChartFrame key={s.key} title={s.title} subtitle={`${data.series[s.key].length} simulated readings · ${s.unit}`} stale={stale}
          table={<DataTable head={['Time', s.unit, 'Signal']} rows={[...data.series[s.key]].reverse().map((p) => [fmtHospital(p.t, tz), p.v, p.quality.toLowerCase()])} />}>
          <TimeSeriesChart points={data.series[s.key]} unit={s.unit} from={data.from} to={data.to} format={fmt} />
        </ChartFrame>
      ))}
    </div>
  )
}

function AlertsCard({ data }: { data: Detail }) {
  const { can, toast } = useApp()
  const invalidate = useInvalidateMonitoring()
  const update = useMutation({
    mutationFn: ({ id, status }: { id: string; status: string }) => patch<MonitoringAlert>(`/monitoring/alerts/${id}/status`, { status }),
    onSuccess: (_, v) => { invalidate(); toast({ tone: 'success', title: `Review ${v.status.toLowerCase()}`, body: 'Saved to the database.' }) },
    onError: (e) => toast({ tone: 'error', title: 'Update failed', body: (e as Error).message }),
  })
  const tz = data.timeZone
  return (
    <Card>
      <CardHeader title="Nursing reviews for this stay" subtitle="Review notifications — not diagnoses. Device alerts are kept separate from clinical flags." />
      {data.alerts.length === 0 ? <p className="text-[13px] text-navy-400">No monitoring reviews for this stay.</p> : (
        <ul className="space-y-2.5">
          {data.alerts.map((a) => (
            <li key={a.alertId} className={cx('rounded-lg border px-3 py-2.5', a.status === 'RESOLVED' ? 'border-line bg-canvas' : 'border-amber-200 bg-amber-50/60')}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <MonitoringAlertBadge alert={a} />
                <span className="text-[11.5px] text-navy-500">Triggered {fmtHospital(a.triggeredAt, tz)} (simulated)</span>
              </div>
              <p className="mt-1.5 text-[13px] text-navy-800">{a.message}</p>
              {a.basis && <p className="mt-0.5 text-[12px] text-navy-500">Basis: {a.basis}</p>}
              {a.resolution && <p className="mt-0.5 text-[12px] text-sage-700">{a.resolution} — {a.updatedBy}</p>}
              {a.status === 'ACKNOWLEDGED' && a.updatedBy && <p className="mt-0.5 text-[12px] text-navy-600">Acknowledged by {a.updatedBy}{a.updatedAt ? ` · ${fmtHospital(a.updatedAt, tz)}` : ''}</p>}
              {can('NURSING_MONITORING') && a.status !== 'RESOLVED' && (
                <div className="mt-2 flex gap-2">
                  {a.status === 'OPEN' && <Button size="sm" loading={update.isPending && update.variables?.id === a.alertId && update.variables.status === 'ACKNOWLEDGED'}
                    disabled={update.isPending} onClick={() => update.mutate({ id: a.alertId, status: 'ACKNOWLEDGED' })}>Acknowledge</Button>}
                  <Button size="sm" variant="navy" loading={update.isPending && update.variables?.id === a.alertId && update.variables.status === 'RESOLVED'}
                    disabled={update.isPending} onClick={() => update.mutate({ id: a.alertId, status: 'RESOLVED' })}>Mark reviewed</Button>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </Card>
  )
}

function TimelineCard({ patientId, encounterId, tz, simNow }: { patientId: string; encounterId: string; tz: string; simNow: string }) {
  const { data, isLoading } = useTimeline(patientId, encounterId)
  const [all, setAll] = useState(false)
  const items = (data ?? []).filter((i) => all || i.type !== 'MOVEMENT')
  const shown = items.slice(0, all ? 200 : 25)
  return (
    <Card>
      <CardHeader title="Activity timeline" subtitle="Sensor estimates, nursing documentation and system events — newest first"
        actions={<button onClick={() => setAll((v) => !v)} className="text-[12px] font-medium text-coral-700">{all ? 'Hide routine movement' : 'Show everything'}</button>} />
      {isLoading && <Loading />}
      <ol className="space-y-1.5">
        {shown.map((i) => <TimelineRow key={i.id} item={i} tz={tz} />)}
      </ol>
      {items.length > shown.length && <p className="mt-2 text-[12px] text-navy-400">{items.length - shown.length} older events not shown.</p>}
      <p className="mt-3 text-[11.5px] text-navy-400">Simulated time now: {fmtHospital(simNow, tz)}. Wrist movement is never treated as confirmed repositioning.</p>
    </Card>
  )
}

function TimelineRow({ item, tz }: { item: TimelineItem; tz: string }) {
  const source = item.source === 'NURSE' ? { tone: 'sage' as const, text: 'Nursing documentation' }
    : item.source === 'SENSOR' ? { tone: 'outline' as const, text: 'Sensor estimate' } : { tone: 'neutral' as const, text: 'System' }
  return (
    <li className="flex items-start gap-3 rounded-lg px-2 py-1.5 hover:bg-canvas">
      <span className="w-[118px] shrink-0 text-[12px] tabular-nums text-navy-500">{fmtHospital(item.at, tz)}</span>
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-1.5 text-[13px] font-medium text-navy-900">
          {MONITORING_LABEL[item.type] ?? item.label}
          <Badge tone={source.tone}>{source.text}</Badge>
          {item.durationSeconds ? <span className="text-[12px] font-normal text-navy-500">{Math.round(item.durationSeconds / 60)} min</span> : null}
        </div>
        {item.detail && <div className="text-[12px] text-navy-500">{item.detail}</div>}
      </div>
    </li>
  )
}

function DeviceCard({ data }: { data: Detail }) {
  return (
    <Card>
      <CardHeader icon={<Cpu className="h-4 w-4" />} title="Device and care plan" subtitle="Proposed hardware — see HARDWARE_ARCHITECTURE.md. Nothing here is a real device." />
      <div className="grid grid-cols-2 gap-3">
        <KeyValue label="Device">{data.device ? `${data.device.deviceId} (simulated)` : '—'}</KeyValue>
        <KeyValue label="Firmware">{data.device?.firmware ?? '—'}</KeyValue>
        <KeyValue label="Hardware concept">{data.device?.model ?? '—'}</KeyValue>
        <KeyValue label="Mobility review interval">{data.plan?.mobilityReviewMinutes ? `${data.plan.mobilityReviewMinutes / 60} h (care plan)` : 'Not configured — no mobility reviews'}</KeyValue>
        <KeyValue label="Repositioning interval">{data.plan?.repositioningIntervalMinutes ? `${data.plan.repositioningIntervalMinutes / 60} h (care plan)` : 'Not configured'}</KeyValue>
        <KeyValue label="Stale-data threshold">{data.plan?.staleDataMinutes ? `${data.plan.staleDataMinutes} min` : '—'}</KeyValue>
      </div>
      {data.plan?.note && <p className="mt-3 text-[12.5px] text-navy-600">Plan: {data.plan.note}</p>}
    </Card>
  )
}

function RepositioningCard({ data }: { data: Detail }) {
  const { user, toast } = useApp()
  const invalidate = useInvalidateMonitoring()
  const [note, setNote] = useState('')
  const doc = useMutation({
    mutationFn: () => post<Detail>('/monitoring/interventions/repositioning', { patientId: data.patientId, encounterId: data.encounter.encounterId, note: note.trim() || null }),
    onSuccess: () => { setNote(''); invalidate(); toast({ tone: 'success', title: 'Repositioning documented', body: 'Saved with your name at the current simulated time.' }) },
    onError: (e) => toast({ tone: 'error', title: 'Not saved', body: (e as Error).message }),
  })
  return (
    <Card>
      <CardHeader icon={<UserRoundCheck className="h-4 w-4" />} title="Document repositioning" subtitle="Confirmed nursing documentation — the only thing that resets the repositioning interval" />
      <p className="mb-2 text-[12.5px] text-navy-600">Documenting as <span className="font-medium">{user.name}</span> at the current simulated time ({fmtHospital(data.card?.simulatedNow, data.timeZone)}).</p>
      <textarea value={note} onChange={(e) => setNote(e.target.value)} rows={2} placeholder="Optional note, e.g. Log-rolled to left side; skin intact."
        className="w-full rounded-lg border border-line px-3 py-2 text-[13px] focus:border-coral-300 focus:outline-none focus:ring-2 focus:ring-coral-100" />
      <Button variant="navy" size="sm" className="mt-2" loading={doc.isPending} onClick={() => doc.mutate()}><NotebookPen className="h-3.5 w-3.5" />Record repositioning</Button>
    </Card>
  )
}

function SimulatorCard({ data }: { data: Detail }) {
  const { toast } = useApp()
  const invalidate = useInvalidateMonitoring()
  const [minutes, setMinutes] = useState(60)
  const run = useMutation({
    mutationFn: (v: { action: string; minutes?: number }) =>
      post<Detail>('/monitoring/simulator/events', { patientId: data.patientId, encounterId: data.encounter.encounterId, ...v }),
    onSuccess: (_, v) => { invalidate(); toast({ tone: 'info', title: `Simulator: ${v.action.replaceAll('_', ' ').toLowerCase()}`, body: 'Generated by the Java backend and stored in the database.' }) },
    onError: (e) => toast({ tone: 'error', title: 'Simulator action failed', body: (e as Error).message }),
  })
  const active = data.card?.sessionStatus === 'ACTIVE'
  const connected = data.card?.deviceStatus === 'CONNECTED' || data.card?.deviceStatus === 'STALE'
  const btn = (action: string, label: string, icon: React.ReactNode, opts: { disabled?: boolean; minutes?: number; variant?: 'secondary' | 'navy' | 'danger' } = {}) => (
    <Button size="sm" variant={opts.variant ?? 'secondary'} disabled={run.isPending || opts.disabled}
      loading={run.isPending && run.variables?.action === action} onClick={() => run.mutate({ action, minutes: opts.minutes })}>{icon}{label}</Button>
  )
  return (
    <Card className="border-amber-300 bg-amber-50/40">
      <CardHeader icon={<Cpu className="h-4 w-4" />} title="Simulated Device Controls" subtitle="Demo only — sends requests to the Java simulator; no hardware involved" />
      <div className="space-y-3">
        <div className="flex flex-wrap gap-1.5">
          {!data.card || !active ? btn('START_SESSION', 'Start session', <Play className="h-3.5 w-3.5" />, { disabled: !data.encounter.ongoing && !data.card })
            : btn('STOP_SESSION', 'Stop session', <Square className="h-3.5 w-3.5" />)}
          {btn('WALK', 'Walking (6 min)', <Activity className="h-3.5 w-3.5" />, { disabled: !active, minutes: 6 })}
          {btn('VITALS', 'Vital-sign reading', <HeartPulse className="h-3.5 w-3.5" />, { disabled: !active })}
          {btn('MOVEMENT', 'Movement', <Move className="h-3.5 w-3.5" />, { disabled: !active })}
          {btn('POSITION_CHANGE', 'Possible position change', <Move className="h-3.5 w-3.5" />, { disabled: !active })}
          {data.card?.deviceStatus === 'DISCONNECTED'
            ? btn('RECONNECT', 'Reconnect device', <Plug className="h-3.5 w-3.5" />, { disabled: !active })
            : btn('DISCONNECT', 'Disconnect device', <Unplug className="h-3.5 w-3.5" />, { disabled: !active || !connected })}
        </div>
        <div className="flex flex-wrap items-center gap-2 border-t border-amber-200 pt-3">
          <label className="text-[12.5px] text-navy-700">Advance simulated time</label>
          <select value={minutes} onChange={(e) => setMinutes(Number(e.target.value))} className="h-8 rounded-lg border border-line bg-white px-2 text-[12.5px]">
            {[15, 30, 60, 120, 240, 480].map((m) => <option key={m} value={m}>{m < 60 ? `${m} min` : `${m / 60} h`}</option>)}
          </select>
          {btn('ADVANCE_TIME', 'Advance', <FastForward className="h-3.5 w-3.5" />, { disabled: !active, minutes })}
        </div>
        <div className="flex flex-wrap gap-1.5 border-t border-amber-200 pt-3">
          {btn('SCENARIO_INACTIVITY', 'Trigger inactivity review', <Clock className="h-3.5 w-3.5" />, { disabled: !active || !data.plan?.mobilityReviewMinutes })}
          {btn('SCENARIO_REPOSITIONING_DUE', 'Trigger repositioning review', <Clock className="h-3.5 w-3.5" />, { disabled: !active || !data.plan?.repositioningIntervalMinutes })}
          {btn('RESET', 'Reset scenario', <RotateCcw className="h-3.5 w-3.5" />, { variant: 'danger' })}
        </div>
        <p className="text-[11.5px] text-navy-500">Seeded and reproducible. The simulation clock is separate from real time; reset restores the seeded scenario for this stay only.</p>
      </div>
    </Card>
  )
}
