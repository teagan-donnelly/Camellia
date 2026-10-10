import { Baby, Building2, Search } from 'lucide-react'
import { useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { usePatients } from '../api/hooks'
import { Badge, Card, EmptyState, ErrorBox, Loading, PageHeader } from '../components/ui'
import { cx, daysLabel, fmtDate } from '../lib/format'

export default function Patients() {
  const { data, isLoading, error } = usePatients()
  const [q, setQ] = useState('')
  const [filter, setFilter] = useState<'all' | 'ob' | 'review'>('all')
  const navigate = useNavigate()

  const rows = useMemo(() => {
    const needle = q.trim().toLowerCase()
    return (data ?? [])
      .filter((p) => filter === 'all' || (filter === 'ob' ? p.obstetric : p.openFlagCount + p.openAlertCount > 0))
      .filter((p) => !needle || [p.name, p.displayId, p.patientId, ...p.keyConditions, ...p.organizations, p.demoScenario ?? '']
        .join(' ').toLowerCase().includes(needle))
  }, [data, q, filter])

  return (
    <div>
      <PageHeader
        eyebrow="Directory"
        title="Patients"
        subtitle="Synthetic patients with records normalized from multiple fictional healthcare organizations."
      />
      <Card padded={false}>
        <div className="flex flex-wrap items-center gap-3 border-b border-line px-4 py-3">
          <div className="relative min-w-[240px] flex-1">
            <Search className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-navy-400" />
            <input value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter by name, ID, condition or organization…"
              className="h-9 w-full rounded-lg border border-line bg-canvas pl-9 pr-3 text-sm focus:border-coral-300 focus:bg-white focus:outline-none focus:ring-2 focus:ring-coral-100" />
          </div>
          <div className="flex rounded-lg bg-navy-50 p-0.5 text-[13px]">
            {([['all', 'All'], ['ob', 'Obstetric'], ['review', 'Needs review']] as const).map(([id, label]) => (
              <button key={id} onClick={() => setFilter(id)}
                className={cx('rounded-md px-3 py-1.5 font-medium', filter === id ? 'bg-white text-navy-900 shadow-sm' : 'text-navy-500 hover:text-navy-800')}>
                {label}
              </button>
            ))}
          </div>
        </div>
        {isLoading && <Loading />}
        {error && <div className="p-4"><ErrorBox error={error} /></div>}
        {data && rows.length === 0 && <EmptyState title="No patients match" />}
        {rows.length > 0 && (
          <div className="overflow-x-auto">
            <table className="w-full min-w-[960px] text-left text-[13.5px]">
              <thead className="bg-canvas text-[11.5px] font-semibold uppercase tracking-wide text-navy-500">
                <tr>
                  <th className="px-4 py-2.5">Patient</th>
                  <th className="px-4 py-2.5">Relevant conditions</th>
                  <th className="px-4 py-2.5">Source organizations</th>
                  <th className="px-4 py-2.5">Next encounter</th>
                  <th className="px-4 py-2.5 text-right">Open alerts / flags</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line">
                {rows.map((p) => (
                  <tr key={p.patientId} onClick={() => navigate(`/patients/${p.patientId}`)} className="cursor-pointer align-top hover:bg-canvas">
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-2">
                        <span className="font-medium text-navy-900">{p.name}</span>
                        {p.obstetric && <Badge tone="coral" title="Obstetric patient"><Baby className="h-3 w-3" />OB</Badge>}
                      </div>
                      <div className="mt-0.5 text-[12px] text-navy-500">{p.displayId} · {p.age}y {p.sex}{p.estimatedDueDate ? ` · EDD ${fmtDate(p.estimatedDueDate)} (est.)` : ''}</div>
                      {p.demoScenario && <div className="mt-1 text-[11.5px] text-navy-400">Demo scenario {p.demoScenario}</div>}
                    </td>
                    <td className="px-4 py-3">
                      {p.keyConditions.length ? (
                        <ul className="space-y-0.5 text-[12.5px] text-navy-700">{p.keyConditions.map((c) => <li key={c} className="max-w-[300px] truncate">{c}</li>)}</ul>
                      ) : <span className="text-navy-400">—</span>}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex max-w-[280px] flex-wrap gap-1">
                        {p.organizations.map((o) => <Badge key={o} tone="outline"><Building2 className="h-3 w-3" />{o.replace(/ (Medical Center|Associates|Laboratories|Community Hospital)$/, '')}</Badge>)}
                      </div>
                    </td>
                    <td className="px-4 py-3">
                      {p.nextAppointment ? (
                        <div>
                          <div className={cx('text-[13px]', p.nextAppointment.deliveryRelated ? 'font-medium text-navy-900' : 'text-navy-700')}>{p.nextAppointment.type}</div>
                          <div className="text-[12px] text-navy-500">{fmtDate(p.nextAppointment.scheduledStart)} · {daysLabel(p.nextAppointment.daysUntil)}</div>
                        </div>
                      ) : <span className="text-navy-400">None scheduled</span>}
                    </td>
                    <td className="px-4 py-3 text-right">
                      <div className="inline-flex gap-1">
                        <Badge tone={p.openAlertCount ? 'coral' : 'neutral'}>{p.openAlertCount}</Badge>
                        <Badge tone={p.openFlagCount ? 'amber' : 'neutral'}>{p.openFlagCount}</Badge>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  )
}
