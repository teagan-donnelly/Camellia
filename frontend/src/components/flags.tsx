import { ArrowRight, Bell, Building2, Clock, FileText, Stethoscope } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { FlagHit } from '../api/types'
import { fmtDate, fmtDateTime, relative } from '../lib/format'
import { CitationChip } from './records'
import { AlertStatusBadge, FlagStatusBadge, PriorityBadge } from './status'
import { Badge, SynMrn } from './ui'

/**
 * One clinical alert (flag) search result. Grouped top to bottom: title and status, patient and time, the reason it
 * was raised with its supporting details, then the triggering records and the action. Source-record chips open the
 * original record.
 */
export function FlagHitCard({ hit }: { hit: FlagHit }) {
  const orgs = Array.from(new Set(hit.sourceRecords.map((r) => r.orgName)))
  return (
    <article className="rounded-xl border border-line bg-white transition-shadow hover:border-coral-200 hover:shadow-md">
      {/* Title, category and status */}
      <header className="flex flex-col gap-3 px-5 pt-4 sm:flex-row sm:items-start sm:justify-between">
        <div className="min-w-0">
          <Link to={hit.link} className="text-[15px] font-semibold leading-snug text-navy-900 hover:text-coral-700">{hit.title}</Link>
          <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
            <Badge>{hit.category}</Badge>
            {hit.alertId && <Badge tone="coral"><Bell className="h-3 w-3" />Anesthesia OB alert</Badge>}
          </div>
        </div>
        <div className="flex shrink-0 flex-wrap items-center gap-1.5 sm:flex-col sm:items-end">
          {hit.alertStatus ? <AlertStatusBadge status={hit.alertStatus} /> : <FlagStatusBadge status={hit.status} />}
          <PriorityBadge priority={hit.reviewPriority} />
        </div>
      </header>

      {/* Patient and timestamp */}
      <div className="mt-2.5 flex flex-wrap items-center gap-x-3 gap-y-1 px-5 text-[13px] text-navy-500">
        <span>
          <Link to={`/patients/${hit.patientId}`} className="font-medium text-navy-800 hover:text-coral-700">{hit.patientName}</Link>
          <span className="mx-1.5 text-navy-300">·</span><SynMrn id={hit.displayId} />
        </span>
        <span className="inline-flex items-center gap-1 text-[12px] text-navy-400" title={fmtDateTime(hit.updatedAt)}>
          <Clock className="h-3 w-3" />Updated {relative(hit.updatedAt)}
        </span>
      </div>

      {/* Why it was raised, with supporting details */}
      <div className="mx-5 mt-3 border-t border-line pt-3">
        <div className="text-[11px] font-semibold uppercase tracking-wide text-navy-400">Reason for review</div>
        <p className="mt-1 line-clamp-3 text-[13.5px] leading-relaxed text-navy-700">{hit.detectionReason}</p>
        <div className="mt-2.5 flex flex-wrap items-center gap-x-4 gap-y-1.5 text-[12px] text-navy-500">
          <span className="inline-flex items-center gap-1"><FileText className="h-3.5 w-3.5 text-navy-400" />{hit.evidenceCount} evidence records</span>
          {hit.specialties.length > 0 && <span className="inline-flex items-center gap-1"><Stethoscope className="h-3.5 w-3.5 text-navy-400" />{hit.specialties.join(', ')}</span>}
          {orgs.length > 0 && <span className="inline-flex items-center gap-1"><Building2 className="h-3.5 w-3.5 text-navy-400" />{orgs.join(', ')}</span>}
        </div>
      </div>

      {/* Triggering records and action */}
      <footer className="mt-3 flex flex-col gap-3 rounded-b-xl border-t border-line bg-canvas/50 px-5 py-3 sm:flex-row sm:items-center sm:justify-between">
        {hit.sourceRecords.length > 0 ? (
          <div className="flex min-w-0 flex-wrap items-center gap-1.5">
            <span className="text-[11.5px] font-medium text-navy-400">Triggered by:</span>
            {hit.sourceRecords.map((r) => (
              <CitationChip key={r.recordId} recordId={r.recordId} label={`${r.title}${r.recordDate ? ' · ' + fmtDate(r.recordDate) : ''}`} />
            ))}
          </div>
        ) : <span />}
        <Link to={hit.link} className="inline-flex shrink-0 items-center gap-1 text-[12.5px] font-medium text-coral-700 hover:text-coral-800">
          {hit.alertId ? 'Open alert workspace' : 'Open alert'} <ArrowRight className="h-3.5 w-3.5" />
        </Link>
      </footer>
    </article>
  )
}
