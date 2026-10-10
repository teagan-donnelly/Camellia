import { ArrowRight, Bell, Building2, FileText, Stethoscope } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { FlagHit } from '../api/types'
import { fmtDate, relative } from '../lib/format'
import { CitationChip } from './records'
import { AlertStatusBadge, FlagStatusBadge, PriorityBadge } from './status'
import { Badge } from './ui'

/** One clinical alert (flag) search result. Source-record chips open the original record. */
export function FlagHitCard({ hit }: { hit: FlagHit }) {
  const orgs = Array.from(new Set(hit.sourceRecords.map((r) => r.orgName)))
  return (
    <div className="rounded-xl border border-line bg-white p-4 transition-shadow hover:border-coral-200 hover:shadow-md">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <Link to={hit.link} className="text-[15px] font-semibold text-navy-900 hover:text-coral-700">{hit.title}</Link>
            <Badge>{hit.category}</Badge>
            {hit.alertId && <Badge tone="coral"><Bell className="h-3 w-3" />Anesthesia OB alert</Badge>}
          </div>
          <div className="mt-1 text-[13px] text-navy-500">
            <Link to={`/patients/${hit.patientId}`} className="font-medium text-navy-800 hover:text-coral-700">{hit.patientName}</Link> · {hit.displayId}
          </div>
          <p className="mt-2 line-clamp-2 text-[13.5px] leading-relaxed text-navy-700">{hit.detectionReason}</p>
          <div className="mt-2 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-navy-500">
            <span className="inline-flex items-center gap-1"><FileText className="h-3 w-3" />{hit.evidenceCount} evidence records</span>
            {hit.specialties.length > 0 && <span className="inline-flex items-center gap-1"><Stethoscope className="h-3 w-3" />{hit.specialties.join(', ')}</span>}
            {orgs.length > 0 && <span className="inline-flex items-center gap-1"><Building2 className="h-3 w-3" />{orgs.join(', ')}</span>}
          </div>
          {hit.sourceRecords.length > 0 && (
            <div className="mt-2 flex flex-wrap items-center gap-1.5">
              <span className="text-[11.5px] font-medium text-navy-400">Triggered by:</span>
              {hit.sourceRecords.map((r) => (
                <CitationChip key={r.recordId} recordId={r.recordId} label={`${r.title}${r.recordDate ? ' · ' + fmtDate(r.recordDate) : ''}`} />
              ))}
            </div>
          )}
        </div>
        <div className="flex shrink-0 flex-col items-end gap-1.5">
          {hit.alertStatus ? <AlertStatusBadge status={hit.alertStatus} /> : <FlagStatusBadge status={hit.status} />}
          <PriorityBadge priority={hit.reviewPriority} />
          <span className="text-[11.5px] text-navy-400">updated {relative(hit.updatedAt)}</span>
          <Link to={hit.link} className="mt-1 inline-flex items-center gap-1 text-[12.5px] font-medium text-coral-700 hover:text-coral-800">
            {hit.alertId ? 'Open alert workspace' : 'Open alert'} <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        </div>
      </div>
    </div>
  )
}
