import { Activity, ChevronRight } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useNursingQueue } from '../api/hooks'
import { DeviceStatusBadge, MonitoringAlertBadge, SimulatedTag } from '../components/monitoring'
import { Card, CardHeader, EmptyState, ErrorBox, Loading, PageHeader, SynMrn } from '../components/ui'
import { cx, fmtHospital } from '../lib/format'

/** Nursing work queue for simulated wristband monitoring. Scoped by the backend to the user's patients. */
export default function NursingMonitoring() {
  const { data, isLoading, error } = useNursingQueue()
  const open = data?.alerts.filter((a) => a.alert.status !== 'RESOLVED') ?? []
  const resolved = data?.alerts.filter((a) => a.alert.status === 'RESOLVED') ?? []
  const byType = (t: string) => open.filter((a) => a.alert.alertType === t)
  return (
    <div>
      <PageHeader eyebrow="Nursing workflow" title="Nursing Monitoring"
        subtitle="Monitored hospital stays and open review notifications from simulated wristbands. Each item opens the patient's specific hospital stay."
        actions={<SimulatedTag />} />
      {isLoading && <Loading />}
      {error && <ErrorBox error={error} />}
      {data && (
        <div className="space-y-5">
          <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
            <Tile label="Monitored patients" value={data.patients.length} />
            <Tile label="Mobility reviews open" value={byType('MOBILITY_REVIEW').length} attention />
            <Tile label="Repositioning reviews due" value={byType('REPOSITIONING_REVIEW').length} attention />
            <Tile label="Device status concerns" value={byType('DEVICE_CONNECTIVITY').length} />
          </div>

          <Card>
            <CardHeader title="Open reviews" subtitle="Review notifications, not diagnoses. Device problems are listed separately from patient reviews by type." />
            {open.length === 0 ? <EmptyState title="No open monitoring reviews" /> : (
              <ul className="divide-y divide-line">
                {open.map(({ alert: a, patientName, displayId, orgName, department }) => (
                  <li key={a.alertId}>
                    <Link to={`/patients/${a.patientId}/encounters/${a.encounterId}/monitoring?from=stays`} className="flex items-start gap-3 py-3 hover:bg-canvas">
                      <div className="min-w-0 flex-1">
                        <div className="flex flex-wrap items-center gap-2">
                          <span className="text-[14px] font-semibold text-navy-900">{patientName}</span>
                          <SynMrn id={displayId} />
                          <MonitoringAlertBadge alert={a} />
                        </div>
                        <div className="text-[12.5px] text-navy-600">{a.message}</div>
                        <div className="text-[12px] text-navy-400">{orgName} · {department} · triggered {fmtHospital(a.triggeredAt)} (simulated)</div>
                      </div>
                      <ChevronRight className="mt-1 h-4 w-4 text-navy-400" />
                    </Link>
                  </li>
                ))}
              </ul>
            )}
          </Card>

          <Card>
            <CardHeader icon={<Activity className="h-4 w-4" />} title="Monitored hospital stays" />
            {data.patients.length === 0 ? <EmptyState title="No active monitoring sessions" /> : (
              <div className="overflow-x-auto">
                <table className="w-full text-[13px]">
                  <thead className="text-left text-[12px] text-navy-500">
                    <tr><th className="py-2 pr-3 font-medium">Patient</th><th className="pr-3 font-medium">Stay</th><th className="pr-3 font-medium">Device</th>
                      <th className="pr-3 font-medium">Walking today</th><th className="pr-3 font-medium">Last sync</th><th className="pr-3 font-medium">Next repositioning due</th><th /></tr>
                  </thead>
                  <tbody className="divide-y divide-line">
                    {data.patients.map((p) => (
                      <tr key={p.encounterId} className="align-top">
                        <td className="py-2.5 pr-3"><div className="font-medium text-navy-900">{p.patientName}</div><SynMrn id={p.displayId} /></td>
                        <td className="pr-3 text-navy-600">{p.orgName}<div className="text-[12px] text-navy-400">{p.department}</div></td>
                        <td className="pr-3"><DeviceStatusBadge status={p.card.deviceStatus} /></td>
                        <td className="pr-3 text-navy-800">{p.card.walkingTodayMinutes} min</td>
                        <td className="pr-3 text-navy-600">{fmtHospital(p.card.lastSyncAt)}</td>
                        <td className={cx('pr-3', p.nextRepositioningDueAt && p.nextRepositioningDueAt <= p.card.simulatedNow ? 'font-medium text-amber-700' : 'text-navy-600')}>
                          {p.nextRepositioningDueAt ? fmtHospital(p.nextRepositioningDueAt) : '—'}</td>
                        <td><Link to={`/patients/${p.patientId}/encounters/${p.encounterId}/monitoring?from=stays`} className="text-[12.5px] font-medium text-coral-700">Open</Link></td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          {resolved.length > 0 && (
            <Card>
              <CardHeader title="Recently resolved" />
              <ul className="space-y-1.5 text-[12.5px] text-navy-600">
                {resolved.slice(0, 10).map(({ alert: a, patientName }) => (
                  <li key={a.alertId}>{patientName} · <MonitoringAlertBadge alert={a} /> · {a.resolution}</li>
                ))}
              </ul>
            </Card>
          )}
          <p className="text-[11.5px] text-navy-400">{data.notice}</p>
        </div>
      )}
    </div>
  )
}

function Tile({ label, value, attention }: { label: string; value: number; attention?: boolean }) {
  return (
    <div className={cx('rounded-xl border p-3', attention && value > 0 ? 'border-amber-200 bg-amber-50' : 'border-line bg-white')}>
      <div className="text-[12px] text-navy-500">{label}</div>
      <div className="text-[24px] font-semibold text-navy-950">{value}</div>
    </div>
  )
}
