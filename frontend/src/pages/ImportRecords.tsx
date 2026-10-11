import { useMutation, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Building2, Download, FileUp, ShieldAlert, TriangleAlert, UserPlus, UserRoundCheck } from 'lucide-react'
import { useState } from 'react'
import { Link } from 'react-router-dom'
import { post } from '../api/client'
import { useImportHistory, useImportSamples, useInvalidateWorkflow } from '../api/hooks'
import type { ImportPreview, ImportResult } from '../api/types'
import { Badge, Button, Card, CardHeader, ErrorBox, KeyValue, PageHeader, SynMrn } from '../components/ui'
import { cx, fmtDate, fmtDateTime } from '../lib/format'
import { useApp } from '../state/AppContext'

const MATCH_LABEL: Record<string, string> = {
  EXACT_SOURCE_IDENTIFIER: 'Same organization + MRN',
  NAME_AND_BIRTH_DATE: 'Same name and birth date',
  LAST_NAME_AND_BIRTH_DATE: 'Same last name and birth date',
  NAME_ONLY: 'Same name only',
}

/** Import synthetic patient records (FHIR JSON, Camellia JSON, CSV). Patients are never merged without confirmation. */
export default function ImportRecords() {
  const { can, toast } = useApp()
  const samples = useImportSamples(can('IMPORT_RECORDS'))
  const history = useImportHistory(can('IMPORT_RECORDS'))
  const invalidate = useInvalidateWorkflow()
  const qc = useQueryClient()
  const [file, setFile] = useState<{ name: string; content: string } | null>(null)
  const [decision, setDecision] = useState<'CREATE_NEW' | 'ATTACH_EXISTING' | null>(null)
  const [patientId, setPatientId] = useState<string | null>(null)
  const [practiceId, setPracticeId] = useState('')
  const [runScreening, setRunScreening] = useState(true)
  const [result, setResult] = useState<ImportResult | null>(null)

  const preview = useMutation({
    mutationFn: (f: { name: string; content: string }) => post<ImportPreview>('/import/preview', { fileName: f.name, content: f.content }),
    onSuccess: (p) => {
      setResult(null)
      setDecision(p.valid ? (p.suggestedDecision ?? null) : null)
      setPatientId(p.suggestedPatientId ?? null)
    },
  })
  const confirm = useMutation({
    mutationFn: () => post<ImportResult>('/import/confirm', {
      fileName: file!.name, content: file!.content, fileSha256: preview.data!.fileSha256, decision,
      patientId: decision === 'ATTACH_EXISTING' ? patientId : null, practiceId: decision === 'CREATE_NEW' && practiceId ? practiceId : null, runScreening,
    }),
    onSuccess: (r) => {
      setResult(r); invalidate(); qc.invalidateQueries({ queryKey: ['import-history'] })
      toast({ tone: 'success', title: `Imported ${r.recordsInserted} record(s)`, body: `${r.patientName} — persisted to the database.` })
    },
  })

  const load = (name: string, content: string) => { setFile({ name, content }); preview.mutate({ name, content }) }
  // Raw text (not parsed JSON) so the previewed bytes are exactly the sample file's bytes.
  const loadSample = async (f: string) => {
    const res = await fetch(`/api/import/samples/${encodeURIComponent(f)}`, { credentials: 'same-origin' })
    if (!res.ok) { toast({ tone: 'error', title: 'Could not load sample', body: `HTTP ${res.status}` }); return }
    load(f, await res.text())
  }
  const p = preview.data
  const canConfirm = p?.valid && decision && (decision === 'CREATE_NEW' || patientId)

  if (!can('IMPORT_RECORDS')) {
    return <Card><p className="text-[13px] text-navy-600">Importing records is limited to the Clinical Data Coordinator demo role (Riley Okafor). Switch users from the menu in the top right.</p></Card>
  }
  return (
    <div>
      <Link to="/patients" className="mb-4 inline-flex items-center gap-1 text-[13px] text-navy-500 hover:text-navy-800"><ArrowLeft className="h-3.5 w-3.5" /> Patients</Link>
      <PageHeader eyebrow="Data management" title="Import Patient Records"
        subtitle="Bring synthetic records from simulated healthcare organizations into Camellia: preview, confirm the patient, then import and screen." />
      <div className="mb-5 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-[12.5px] text-amber-800">
        <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
        <span>Synthetic test files only. Supported: FHIR R4 JSON bundles, the Camellia JSON format and the documented CSV format (see USER_GUIDE.md). This demonstrates the
          ingestion path future authorized EHR integrations could use — it is not an Epic or EHR connection.</span>
      </div>

      <div className="grid grid-cols-1 gap-6 xl:grid-cols-[380px_minmax(0,1fr)]">
        <div className="space-y-4">
          <Card>
            <CardHeader icon={<FileUp className="h-4 w-4" />} title="1. Choose a file" />
            <label className="flex cursor-pointer flex-col items-center gap-1 rounded-lg border-2 border-dashed border-line px-3 py-5 text-center text-[13px] text-navy-600 hover:border-coral-300">
              <FileUp className="h-5 w-5 text-navy-400" />Upload .json or .csv (max 1 MB)
              <input type="file" accept=".json,.csv,application/json,text/csv" className="sr-only"
                onChange={async (e) => { const f = e.target.files?.[0]; if (f) load(f.name, await f.text()); e.target.value = '' }} />
            </label>
            <div className="mt-4 text-[12px] font-semibold uppercase tracking-wide text-navy-400">Or use a sample</div>
            <ul className="mt-2 space-y-2">
              {samples.data?.map((s) => (
                <li key={s.file} className="rounded-lg border border-line p-2.5">
                  <div className="flex items-start justify-between gap-2">
                    <button className="text-left text-[13px] font-medium text-navy-900 hover:text-coral-700" onClick={() => void loadSample(s.file)}>{s.title}</button>
                    <a href={`/api/import/samples/${s.file}`} download title={`Download ${s.file}`} className="text-navy-400 hover:text-navy-700"><Download className="h-3.5 w-3.5" /></a>
                  </div>
                  <div className="text-[12px] text-navy-500">{s.description}</div>
                  <div className="mt-0.5 text-[11.5px] text-navy-400">Expected: {s.scenario}</div>
                </li>
              ))}
            </ul>
          </Card>
          <Card>
            <CardHeader title="Import history" />
            {history.data?.length ? (
              <ul className="space-y-1.5 text-[12.5px]">
                {history.data.slice(0, 8).map((h) => (
                  <li key={h.importId} className="text-navy-600"><Link to={`/patients/${h.patientId}`} className="font-medium text-navy-800 hover:text-coral-700">{h.fileName}</Link>
                    {' '}· {h.recordsInserted} new, {h.recordsDuplicate} duplicate · {h.importedBy} · {fmtDateTime(h.importedAt)}</li>
                ))}
              </ul>
            ) : <p className="text-[12.5px] text-navy-400">No imports yet.</p>}
          </Card>
        </div>

        <div className="min-w-0 space-y-4">
          {preview.isPending && <Card><p className="text-[13px] text-navy-500">Validating and normalizing {file?.name}…</p></Card>}
          {preview.error && <ErrorBox error={preview.error} />}
          {!p && !preview.isPending && <Card><p className="text-[13px] text-navy-400">Choose a file to see a preview. Nothing is saved until you confirm.</p></Card>}
          {p && (
            <>
              <Card>
                <CardHeader title={`2. Preview — ${p.fileName}`} subtitle={p.format ? `Detected format: ${p.format}` : undefined}
                  actions={p.valid ? <Badge tone="sage">Valid</Badge> : <Badge tone="critical">Invalid</Badge>} />
                {!p.valid && (
                  <div className="rounded-lg border border-critical-100 bg-critical-50 p-3 text-[13px] text-critical-700">
                    <div className="mb-1 font-semibold">Fix these problems — nothing was imported:</div>
                    <ul className="list-disc space-y-0.5 pl-5">{p.errors.map((e) => <li key={e}>{e}</li>)}</ul>
                  </div>
                )}
                {p.warnings.length > 0 && (
                  <ul className="mt-3 space-y-1 text-[12.5px] text-amber-800">{p.warnings.map((w) => <li key={w} className="flex gap-1.5"><TriangleAlert className="mt-0.5 h-3.5 w-3.5 shrink-0" />{w}</li>)}</ul>
                )}
                {p.patient && (
                  <div className="mt-3 grid grid-cols-2 gap-3 md:grid-cols-4">
                    <KeyValue label="Patient in file">{p.patient.firstName} {p.patient.lastName}</KeyValue>
                    <KeyValue label="Birth date · sex">{p.patient.dateOfBirth ? fmtDate(p.patient.dateOfBirth) : '—'} · {p.patient.sex ?? '—'}</KeyValue>
                    <KeyValue label="Source MRN"><span className="font-mono text-[12.5px]">{p.patient.mrn ?? '—'}</span></KeyValue>
                    <KeyValue label="Source organization">{p.organization?.name ?? p.organization?.orgId ?? '—'}
                      {p.organization && !p.organization.known && <div className="text-[11.5px] text-amber-700">New to Camellia — will be added</div>}</KeyValue>
                  </div>
                )}
                {p.valid && (
                  <>
                    <div className="mt-3 flex flex-wrap gap-1.5">
                      {Object.entries(p.counts).map(([k, v]) => <Badge key={k} tone="neutral">{v} {k.toLowerCase().replace('_', ' ')}</Badge>)}
                      {p.duplicateRecords > 0 && <Badge tone="amber">{p.duplicateRecords} already in Camellia</Badge>}
                    </div>
                    <ul className="scroll-thin mt-3 max-h-56 space-y-1 overflow-y-auto text-[12.5px]">
                      {p.items.map((i) => (
                        <li key={i.recordType + i.sourceId} className={cx('flex gap-2', i.duplicate && 'text-navy-400')}>
                          <span className="w-[86px] shrink-0 text-navy-500">{fmtDate(i.date)}</span>
                          <span className="w-[96px] shrink-0 text-navy-500">{i.recordType.toLowerCase().replace('_', ' ')}</span>
                          <span className="min-w-0 flex-1 truncate text-navy-800">{i.title}</span>
                          {i.duplicate && <span className="text-[11.5px]">duplicate — skipped</span>}
                        </li>
                      ))}
                    </ul>
                  </>
                )}
              </Card>

              {p.valid && !result && (
                <Card>
                  <CardHeader title="3. Confirm the patient" subtitle="Camellia never merges patients on names alone. Choose explicitly." />
                  <div className="space-y-2">
                    {p.matches.map((m) => (
                      <label key={m.patientId} className={cx('flex cursor-pointer items-start gap-3 rounded-lg border p-3', decision === 'ATTACH_EXISTING' && patientId === m.patientId ? 'border-coral-300 bg-coral-50/50' : 'border-line')}>
                        <input type="radio" name="decision" className="mt-1" checked={decision === 'ATTACH_EXISTING' && patientId === m.patientId}
                          onChange={() => { setDecision('ATTACH_EXISTING'); setPatientId(m.patientId) }} />
                        <div className="min-w-0 flex-1">
                          <div className="flex flex-wrap items-center gap-2 text-[13.5px] font-medium text-navy-900"><UserRoundCheck className="h-4 w-4 text-navy-400" />
                            Attach to {m.name} <SynMrn id={m.displayId} /> · DOB {fmtDate(m.dateOfBirth)}
                            <Badge tone={m.matchType === 'EXACT_SOURCE_IDENTIFIER' ? 'sage' : 'amber'}>{MATCH_LABEL[m.matchType] ?? m.matchType}</Badge></div>
                          <div className="text-[12px] text-navy-500">{m.detail}</div>
                        </div>
                      </label>
                    ))}
                    {p.matches.length === 0 && <p className="text-[12.5px] text-navy-500">No existing patient matches this file's identifier or demographics.</p>}
                    <label className={cx('flex cursor-pointer items-start gap-3 rounded-lg border p-3', decision === 'CREATE_NEW' ? 'border-coral-300 bg-coral-50/50' : 'border-line')}>
                      <input type="radio" name="decision" className="mt-1" checked={decision === 'CREATE_NEW'} onChange={() => { setDecision('CREATE_NEW'); setPatientId(null) }} />
                      <div className="flex-1">
                        <div className="flex items-center gap-2 text-[13.5px] font-medium text-navy-900"><UserPlus className="h-4 w-4 text-navy-400" />Create a new patient</div>
                        <div className="text-[12px] text-navy-500">Gets a new Camellia ID and synthetic MRN; the source MRN is kept as an identifier.</div>
                        {decision === 'CREATE_NEW' && (
                          <select value={practiceId} onChange={(e) => setPracticeId(e.target.value)} className="mt-2 h-8 rounded-lg border border-line bg-white px-2 text-[12.5px]">
                            <option value="">No practice registration (network roles only)</option>
                            <option value="PRAC-A">Register with Practice A (Larkspur Primary Care)</option>
                            <option value="PRAC-B">Register with Practice B (Bellwether Women's Health)</option>
                          </select>
                        )}
                      </div>
                    </label>
                  </div>
                  <label className="mt-3 flex items-center gap-2 text-[13px] text-navy-700">
                    <input type="checkbox" checked={runScreening} onChange={(e) => setRunScreening(e.target.checked)} />Run clinical screening after import
                  </label>
                  {confirm.error && <div className="mt-3"><ErrorBox error={confirm.error} title="Import not completed" /></div>}
                  <Button variant="primary" className="mt-3" disabled={!canConfirm} loading={confirm.isPending} onClick={() => confirm.mutate()}>
                    <Building2 className="h-4 w-4" />Import {p.totalRecords - p.duplicateRecords} new record(s)
                  </Button>
                </Card>
              )}

              {result && (
                <Card className="border-sage-200">
                  <CardHeader title="Import complete" actions={<Badge tone="sage">Saved</Badge>} />
                  <p className="text-[13.5px] text-navy-800">{result.recordsInserted} record(s) added to <Link to={`/patients/${result.patientId}`} className="font-semibold text-coral-700">{result.patientName}</Link>
                    {' '}(<SynMrn id={result.displayId} />){result.recordsDuplicate ? `; ${result.recordsDuplicate} already present and skipped` : ''}.</p>
                  {result.screeningRun && (
                    <div className="mt-2 text-[13px] text-navy-700">Screening: {result.screeningOutcomes.length ? result.screeningOutcomes.join(' · ') : 'no screening rule matched this patient.'}</div>
                  )}
                  <div className="mt-3 flex gap-2">
                    <Link to={`/patients/${result.patientId}?tab=timeline`}><Button variant="navy" size="sm">Open patient profile</Button></Link>
                    <Link to={`/search?patientId=${result.patientId}`}><Button size="sm">Search their records</Button></Link>
                  </div>
                </Card>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}
