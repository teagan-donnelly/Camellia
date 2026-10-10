import { Building2, CalendarDays, ExternalLink, FileCode2, Flag, Link2, UserRound, X } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useRecord } from '../api/hooks'
import { FORMAT_LABEL, RECORD_TYPE_LABEL, cx, fmtDateTime } from '../lib/format'
import { useApp, useEscape } from '../state/AppContext'
import { RecordTypeIcon } from './records'
import { Badge, ErrorBox, KeyValue, Loading } from './ui'

/** Global "original source record" viewer. Any citation or evidence item opens this drawer. */
export function RecordDrawer() {
  const { recordId, closeRecord } = useApp()
  const { data, isLoading, error } = useRecord(recordId)
  const [showRaw, setShowRaw] = useState(false)
  useEscape(closeRecord, !!recordId)
  if (!recordId) return null
  const r = data?.record
  return (
    <div className="fixed inset-0 z-50 flex justify-end">
      <div className="absolute inset-0 bg-navy-950/30 backdrop-blur-[1px]" onClick={closeRecord} />
      <aside className="relative flex h-full w-full max-w-[640px] flex-col bg-white shadow-2xl animate-fade-up">
        <div className="flex items-start justify-between gap-3 border-b border-line px-6 py-4">
          <div className="min-w-0">
            <div className="text-[11.5px] font-semibold uppercase tracking-[0.08em] text-coral-600">Original source record</div>
            <h2 className="mt-1 text-lg font-semibold leading-snug text-navy-950">{r?.title ?? recordId}</h2>
            <div className="mt-1 font-mono text-[12px] text-navy-500">{recordId}</div>
          </div>
          <button onClick={closeRecord} className="rounded-lg p-1.5 text-navy-400 hover:bg-navy-50 hover:text-navy-700" aria-label="Close">
            <X className="h-5 w-5" />
          </button>
        </div>
        <div className="scroll-thin flex-1 overflow-y-auto px-6 py-5">
          {isLoading && <Loading label="Retrieving record from the database…" />}
          {error && <ErrorBox error={error} title="Could not load record" />}
          {data && r && (
            <div className="space-y-5">
              <div className="flex flex-wrap items-center gap-2">
                <Badge tone="navy"><RecordTypeIcon type={r.recordType} className="h-3 w-3" />{RECORD_TYPE_LABEL[r.recordType]}</Badge>
                <Badge tone="outline"><FileCode2 className="h-3 w-3" />{FORMAT_LABEL[r.sourceFormat] ?? r.sourceFormat}</Badge>
                <Badge tone="sage">Documented source</Badge>
              </div>
              <div className="grid grid-cols-2 gap-4 rounded-xl border border-line bg-canvas p-4">
                <KeyValue label="Patient">
                  <Link to={`/patients/${r.patientId}`} onClick={closeRecord} className="font-medium text-navy-900 hover:text-coral-700">
                    {data.patientName}
                  </Link>
                  <span className="ml-1 text-navy-400">{data.patientDisplayId}</span>
                </KeyValue>
                <KeyValue label="Record date"><span className="inline-flex items-center gap-1"><CalendarDays className="h-3.5 w-3.5 text-navy-400" />{fmtDateTime(r.recordDate)}</span></KeyValue>
                <KeyValue label="Source organization"><span className="inline-flex items-center gap-1"><Building2 className="h-3.5 w-3.5 text-navy-400" />{r.orgName}</span></KeyValue>
                <KeyValue label="Author / specialty"><span className="inline-flex items-center gap-1"><UserRound className="h-3.5 w-3.5 text-navy-400" />{r.author ?? '—'}{r.specialty ? ` · ${r.specialty}` : ''}</span></KeyValue>
                <KeyValue label="Source system">{data.sourceSystem ?? '—'}</KeyValue>
                <KeyValue label="Source identifier"><span className="font-mono text-[12.5px]">{r.sourceRecordId ?? '—'}</span></KeyValue>
              </div>

              <div>
                <h3 className="mb-2 text-[13px] font-semibold text-navy-800">Normalized content</h3>
                <p className="whitespace-pre-wrap rounded-xl border border-line bg-white p-4 text-[14px] leading-relaxed text-navy-900">{r.contentText}</p>
              </div>

              {r.tags.length > 0 && (
                <div>
                  <h3 className="mb-2 text-[13px] font-semibold text-navy-800">Deterministic concept tags</h3>
                  <div className="flex flex-wrap gap-1.5">{r.tags.map((t) => <Badge key={t}>{t}</Badge>)}</div>
                </div>
              )}

              {data.referencedBy.length > 0 && (
                <div>
                  <h3 className="mb-2 text-[13px] font-semibold text-navy-800">Cited as evidence by</h3>
                  <ul className="space-y-1.5">
                    {data.referencedBy.map((ref) => (
                      <li key={ref.kind + ref.id}>
                        <Link to={ref.kind === 'ALERT' ? `/alerts/${ref.id}` : `/flags/${ref.id}`} onClick={closeRecord}
                          className="flex items-center gap-2 rounded-lg border border-line px-3 py-2 text-[13px] hover:border-coral-200 hover:bg-coral-50">
                          <Flag className="h-3.5 w-3.5 text-coral-500" />
                          <span className="flex-1 truncate text-navy-800">{ref.title}</span>
                          <Badge>{ref.kind === 'ALERT' ? 'Alert' : 'Flag'} · {ref.role.replace('_', ' ').toLowerCase()}</Badge>
                          <ExternalLink className="h-3.5 w-3.5 text-navy-300" />
                        </Link>
                      </li>
                    ))}
                  </ul>
                </div>
              )}

              <div>
                <button onClick={() => setShowRaw((s) => !s)} className="inline-flex items-center gap-1.5 text-[13px] font-medium text-navy-600 hover:text-coral-700">
                  <Link2 className="h-3.5 w-3.5" />{showRaw ? 'Hide' : 'Show'} raw source payload as received
                </button>
                {showRaw && (
                  <pre className={cx('scroll-thin mt-2 max-h-[340px] overflow-auto rounded-xl bg-navy-950 p-4 font-mono text-[11.5px] leading-relaxed text-navy-100')}>
                    {prettyRaw(data.rawContent, r.sourceFormat)}
                  </pre>
                )}
              </div>
              <p className="text-[11.5px] text-navy-400">Ingested {fmtDateTime(data.ingestedAt)} through the Camellia normalization layer. Synthetic data.</p>
            </div>
          )}
        </div>
      </aside>
    </div>
  )
}

function prettyRaw(raw: string | undefined, format: string) {
  if (!raw) return '(no raw payload stored)'
  if (format === 'FHIR_R4_JSON' || format === 'LEGACY_JSON') {
    try { return JSON.stringify(JSON.parse(raw), null, 2) } catch { return raw }
  }
  return raw
}
