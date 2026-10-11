import { Download, Eye, FileText, FileType2, Info, Lock, Scale, Upload, X } from 'lucide-react'
import { useId, useRef, useState } from 'react'
import { documentFileUrl, usePatientDocuments, usePatientTraits, useUploadDocument } from '../api/hooks'
import type { EncounterView, PatientDocument } from '../api/types'
import { useApp } from '../state/AppContext'
import { DOCUMENT_TEXT_LABEL, cx, fmtBytes, fmtDate, fmtHospital } from '../lib/format'
import { Badge, Button, EmptyState, ErrorBox, Loading } from './ui'

const ACCEPT = '.pdf,.docx,.doc,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/msword'
const ALLOWED = ['pdf', 'docx', 'doc']
const MAX_MB = 10

/**
 * Diagnosis-derived traits (obesity, morbid obesity) as neutral reference chips. Shown only to clinical roles;
 * each chip opens the coded diagnosis it came from.
 */
export function TraitChips({ patientId }: { patientId: string }) {
  const { can, openRecord } = useApp()
  const clinical = can('CLINICAL_SEARCH')
  const traits = usePatientTraits(patientId, clinical)
  const active = (traits.data ?? []).filter((t) => t.status === 'ACTIVE')
  if (!clinical || active.length === 0) return null
  return (
    <div className="mt-3 flex flex-wrap items-center gap-1.5">
      <span className="text-[12px] font-medium text-navy-500">Documented traits</span>
      {active.map((t) => (
        <button key={t.traitId} type="button" onClick={() => t.sourceRecordId && openRecord(t.sourceRecordId)}
          title={`${t.codeDisplay ?? t.label} (${t.codeSystem} ${t.code}) — from a coded diagnosis${t.recordedBy ? `, ${t.recordedBy}` : ''}. Open the source record.`}
          className="inline-flex items-center gap-1.5 rounded-full border border-line bg-canvas px-2.5 py-1 text-[12px] text-navy-700 hover:border-navy-200 hover:bg-navy-50">
          <Scale className="h-3 w-3 shrink-0 text-navy-400" />
          <span className="whitespace-nowrap font-medium">{t.label}</span>
          <span className="whitespace-nowrap font-mono text-[11px] text-navy-400">{t.code}</span>
          {t.encounterId && <span className="whitespace-nowrap text-[11px] text-navy-400">· during a hospital stay</span>}
        </button>
      ))}
    </div>
  )
}

/** Uploaded PDF / Word documents for one patient: upload form (when permitted) and the document list. */
export function PatientDocuments({ patientId, encounters, initialEncounterId }: {
  patientId: string; encounters: EncounterView[]; initialEncounterId?: string
}) {
  const { can, toast } = useApp()
  const docs = usePatientDocuments(patientId)
  const encounterLabel = (id?: string) => {
    const e = encounters.find((x) => x.encounterId === id)
    return e ? `${e.encounterType} · ${fmtDate(e.startTime)}` : id
  }
  return (
    <div className="space-y-4">
      {can('UPLOAD_DOCUMENTS') && (
        <UploadForm patientId={patientId} encounters={encounters} initialEncounterId={initialEncounterId}
          onUploaded={(d) => toast({
            tone: 'success', title: 'Document uploaded',
            body: `${d.fileName} · ${DOCUMENT_TEXT_LABEL[d.textStatus]}${d.duplicateOf ? ` · same content as ${d.duplicateOf} (kept as a separate upload)` : ''}`,
          })} />
      )}
      <div className="flex items-start gap-2 rounded-lg bg-canvas px-3 py-2 text-[12px] text-navy-500">
        <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0 text-navy-400" />
        Files are stored outside any public folder and opened only through an access-checked link. Extracted text is
        searchable in Clinical Search by clinicians who can see this patient; it is not sent to Gemini.
      </div>
      {docs.isLoading && <Loading label="Loading documents…" />}
      {docs.error && <ErrorBox error={docs.error} title="Could not load documents" />}
      {docs.data && docs.data.length === 0 && (
        <EmptyState title="No uploaded documents" icon={<FileText className="h-5 w-5" />}>
          {can('UPLOAD_DOCUMENTS') ? 'Upload a PDF or Word document above.' : 'Documents uploaded for this patient appear here.'}
        </EmptyState>
      )}
      {docs.data && docs.data.length > 0 && (
        <ul className="divide-y divide-line rounded-xl border border-line">
          {docs.data.map((d) => <DocumentRow key={d.documentId} doc={d} encounterLabel={encounterLabel(d.encounterId)} />)}
        </ul>
      )}
    </div>
  )
}

function DocumentRow({ doc, encounterLabel }: { doc: PatientDocument; encounterLabel?: string }) {
  return (
    <li className="flex flex-col gap-3 px-4 py-3 sm:flex-row sm:items-center">
      <div className="flex min-w-0 flex-1 items-start gap-3">
        <span className={cx('mt-0.5 flex h-9 w-9 shrink-0 items-center justify-center rounded-lg text-[10px] font-bold',
          doc.fileType === 'PDF' ? 'bg-coral-50 text-coral-700' : 'bg-navy-50 text-navy-700')}>{doc.fileType}</span>
        <div className="min-w-0">
          <div className="break-words text-[13.5px] font-medium text-navy-900">{doc.title}</div>
          <div className="mt-0.5 break-all text-[12px] text-navy-500">{doc.fileName} · {fmtBytes(doc.sizeBytes)}{doc.pageCount ? ` · ${doc.pageCount} page${doc.pageCount === 1 ? '' : 's'}` : ''}</div>
          <div className="mt-1 flex flex-wrap items-center gap-x-2 gap-y-1 text-[11.5px] text-navy-500">
            <span>Uploaded {fmtHospital(doc.uploadedAt)} by {doc.uploadedBy}</span>
            {encounterLabel && <Badge tone="outline">{encounterLabel}</Badge>}
            <Badge tone={doc.textStatus === 'EXTRACTED' ? 'sage' : 'neutral'} title={doc.textStatus === 'NO_TEXT' ? 'Scanned PDFs are not OCR-processed, so their text is not searchable.' : undefined}>
              {DOCUMENT_TEXT_LABEL[doc.textStatus]}
            </Badge>
          </div>
        </div>
      </div>
      <div className="flex shrink-0 gap-2 sm:justify-end">
        {doc.fileType === 'PDF' && (
          <a href={documentFileUrl(doc.documentId)} target="_blank" rel="noopener noreferrer"
            className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-line bg-white px-3 text-[12.5px] font-medium text-navy-700 hover:bg-navy-50">
            <Eye className="h-3.5 w-3.5" />View<span className="sr-only"> {doc.title}</span>
          </a>
        )}
        <a href={documentFileUrl(doc.documentId, true)}
          className="inline-flex h-8 items-center gap-1.5 rounded-lg border border-line bg-white px-3 text-[12.5px] font-medium text-navy-700 hover:bg-navy-50">
          <Download className="h-3.5 w-3.5" />Download<span className="sr-only"> {doc.title}</span>
        </a>
      </div>
    </li>
  )
}

function UploadForm({ patientId, encounters, initialEncounterId, onUploaded }: {
  patientId: string; encounters: EncounterView[]; initialEncounterId?: string; onUploaded: (d: PatientDocument) => void
}) {
  const upload = useUploadDocument(patientId)
  const [file, setFile] = useState<File | null>(null)
  const [title, setTitle] = useState('')
  const [encounterId, setEncounterId] = useState(initialEncounterId ?? '')
  const [problem, setProblem] = useState<string | null>(null)
  const inputRef = useRef<HTMLInputElement>(null)
  const ids = { file: useId(), title: useId(), encounter: useId() }

  function choose(f: File | null) {
    setProblem(null)
    upload.reset()
    if (!f) { setFile(null); return }
    const ext = f.name.split('.').pop()?.toLowerCase() ?? ''
    if (!ALLOWED.includes(ext)) { setProblem('Only PDF (.pdf) and Word (.docx, .doc) documents can be uploaded.'); setFile(null); return }
    if (f.size > MAX_MB * 1024 * 1024) { setProblem(`The file is larger than the ${MAX_MB} MB limit.`); setFile(null); return }
    setFile(f)
  }

  function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!file) { setProblem('Choose a PDF or Word document to upload.'); return }
    upload.mutate({ file, title: title.trim() || undefined, encounterId: encounterId || undefined }, {
      onSuccess: (d) => {
        onUploaded(d)
        setFile(null); setTitle('')
        if (inputRef.current) inputRef.current.value = ''
      },
    })
  }

  return (
    <form onSubmit={submit} className="rounded-xl border border-line bg-white p-4">
      <div className="mb-3 flex items-center gap-2 text-[13.5px] font-semibold text-navy-900"><Upload className="h-4 w-4 text-navy-400" />Upload a document</div>
      <div className="grid grid-cols-1 gap-3 md:grid-cols-2">
        <div className="md:col-span-2">
          <label htmlFor={ids.file} className="text-[12px] font-medium text-navy-600">File <span className="font-normal text-navy-400">(PDF, DOCX or DOC · up to {MAX_MB} MB)</span></label>
          <div className="mt-1 flex flex-wrap items-center gap-2">
            <input id={ids.file} ref={inputRef} type="file" accept={ACCEPT} onChange={(e) => choose(e.target.files?.[0] ?? null)}
              className="block w-full max-w-full text-[13px] text-navy-700 file:mr-3 file:rounded-lg file:border file:border-line file:bg-canvas file:px-3 file:py-1.5 file:text-[12.5px] file:font-medium file:text-navy-700 hover:file:bg-navy-50" />
          </div>
          {file && (
            <div className="mt-1.5 inline-flex max-w-full items-center gap-1.5 rounded-lg bg-canvas px-2 py-1 text-[12px] text-navy-600">
              <FileType2 className="h-3.5 w-3.5 shrink-0 text-navy-400" /><span className="truncate">{file.name}</span><span className="shrink-0 text-navy-400">· {fmtBytes(file.size)}</span>
              <button type="button" aria-label="Remove selected file" onClick={() => { choose(null); if (inputRef.current) inputRef.current.value = '' }} className="text-navy-400 hover:text-coral-700"><X className="h-3.5 w-3.5" /></button>
            </div>
          )}
        </div>
        <div>
          <label htmlFor={ids.title} className="text-[12px] font-medium text-navy-600">Title <span className="font-normal text-navy-400">(optional)</span></label>
          <input id={ids.title} value={title} maxLength={200} onChange={(e) => setTitle(e.target.value)} placeholder="Defaults to the file name"
            className="mt-1 h-9 w-full rounded-lg border border-line px-3 text-[13px] focus:border-coral-300 focus:outline-none focus:ring-2 focus:ring-coral-100" />
        </div>
        <div>
          <label htmlFor={ids.encounter} className="text-[12px] font-medium text-navy-600">Encounter <span className="font-normal text-navy-400">(optional)</span></label>
          <select id={ids.encounter} value={encounterId} onChange={(e) => setEncounterId(e.target.value)}
            className="mt-1 h-9 w-full rounded-lg border border-line bg-white px-2 text-[13px] focus:border-coral-300 focus:outline-none focus:ring-2 focus:ring-coral-100">
            <option value="">Patient record (no specific encounter)</option>
            {encounters.map((e) => <option key={e.encounterId} value={e.encounterId}>{e.encounterType} · {fmtDate(e.startTime)}{e.ongoing ? ' (current stay)' : ''}</option>)}
          </select>
        </div>
      </div>
      {(problem || upload.error) && (
        <div role="alert" className="mt-3 flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[12.5px] text-amber-800">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />{problem ?? (upload.error as Error).message}
        </div>
      )}
      <div className="mt-3 flex justify-end">
        <Button type="submit" variant="primary" size="sm" loading={upload.isPending} disabled={!file}>
          <Upload className="h-3.5 w-3.5" />{upload.isPending ? 'Uploading…' : 'Upload document'}
        </Button>
      </div>
    </form>
  )
}
