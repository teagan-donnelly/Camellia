import { CalendarClock, ClipboardPlus, FileText, FlaskConical, HeartPulse, Pill, ScanLine, Stethoscope } from 'lucide-react'
import type { EvidenceDto, RecordType } from '../api/types'
import { RECORD_TYPE_LABEL, cx, fmtDate } from '../lib/format'
import { useApp } from '../state/AppContext'
import { RoleBadge } from './status'

export function RecordTypeIcon({ type, className = 'h-4 w-4' }: { type: RecordType | string; className?: string }) {
  switch (type) {
    case 'LAB_RESULT': return <FlaskConical className={className} />
    case 'MEDICATION': return <Pill className={className} />
    case 'CONDITION': return <HeartPulse className={className} />
    case 'APPOINTMENT': return <CalendarClock className={className} />
    case 'ENCOUNTER': return <Stethoscope className={className} />
    case 'IMAGING': return <ScanLine className={className} />
    case 'NOTE': return <FileText className={className} />
    default: return <ClipboardPlus className={className} />
  }
}

/** Clickable citation chip that opens the original record. */
export function CitationChip({ recordId, label, className }: { recordId: string; label?: string; className?: string }) {
  const { openRecord } = useApp()
  return (
    <button
      type="button"
      onClick={() => openRecord(recordId)}
      title={`Open source record ${recordId}`}
      className={cx('inline-flex max-w-full items-center gap-1 rounded-md bg-sage-50 px-1.5 py-0.5 align-baseline font-mono text-[11px] text-sage-700 ring-1 ring-inset ring-sage-200 hover:bg-sage-100', className)}
    >
      <FileText className="h-3 w-3 shrink-0" />
      <span className="truncate">{label ?? recordId}</span>
    </button>
  )
}

/** Evidence item card: role, description, source org/date, and a link to the original record. */
export function EvidenceItem({ e }: { e: EvidenceDto }) {
  const { openRecord } = useApp()
  const r = e.record
  return (
    <button
      onClick={() => openRecord(e.recordId)}
      className={cx('group w-full rounded-xl border bg-white p-3.5 text-left transition-colors hover:border-coral-200 hover:bg-coral-50/40',
        e.role === 'TRIGGER' ? 'border-coral-200' : 'border-line')}
    >
      <div className="flex items-start gap-3">
        <div className={cx('mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg',
          e.role === 'TRIGGER' ? 'bg-coral-50 text-coral-600' : e.role === 'UPCOMING_ENCOUNTER' ? 'bg-navy-50 text-navy-700' : 'bg-canvas text-navy-400')}>
          <RecordTypeIcon type={r?.recordType ?? 'NOTE'} />
        </div>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-1.5">
            <RoleBadge role={e.role} />
            {r && <span className="text-[11.5px] text-navy-400">{RECORD_TYPE_LABEL[r.recordType]}</span>}
          </div>
          <div className="mt-1.5 text-[13.5px] leading-snug text-navy-900">{e.description}</div>
          {r && (
            <div className="mt-1.5 flex flex-wrap items-center gap-x-3 gap-y-1 text-[12px] text-navy-500">
              <span className="font-medium text-navy-600">{r.orgName}</span>
              <span>{fmtDate(r.recordDate)}</span>
              {r.author && <span className="truncate">{r.author}</span>}
              <span className="font-mono text-[11px] text-navy-400 group-hover:text-coral-700">{e.recordId} ↗</span>
            </div>
          )}
        </div>
      </div>
    </button>
  )
}

export function EvidenceList({ evidence }: { evidence: EvidenceDto[] }) {
  const groups = [
    { role: 'TRIGGER', title: 'Triggering findings', hint: 'Records that met the screening criteria' },
    { role: 'UPCOMING_ENCOUNTER', title: 'Upcoming encounter', hint: 'Scheduled care this review relates to' },
    { role: 'SUPPORTING', title: 'Supporting documentation', hint: 'Related notes and prior results for context' },
  ]
  return (
    <div className="space-y-5">
      {groups.map((g) => {
        const items = evidence.filter((e) => e.role === g.role)
        if (!items.length) return null
        return (
          <div key={g.role}>
            <div className="mb-2 flex items-baseline gap-2">
              <h3 className="text-[13px] font-semibold text-navy-800">{g.title}</h3>
              <span className="text-[12px] text-navy-400">{g.hint}</span>
            </div>
            <div className="space-y-2">{items.map((e) => <EvidenceItem key={e.recordId} e={e} />)}</div>
          </div>
        )
      })}
    </div>
  )
}
