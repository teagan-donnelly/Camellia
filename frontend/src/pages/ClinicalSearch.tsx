import { ArrowRight, Building2, Database, Filter, MessageSquareText, Search, Sparkles, Type, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { get } from '../api/client'
import { executeSearch, interpretSearch, summarizeSearch, useMeta, usePatients } from '../api/hooks'
import type { AiAnswer, Interpretation, SearchCriteria, SearchResult } from '../api/types'
import { AiAnswerView, AssistantPanel, GeminiThinking } from '../components/ai'
import { RecordTypeIcon } from '../components/records'
import { Badge, Button, Card, CardHeader, EmptyState, ErrorBox, PageHeader } from '../components/ui'
import { RECORD_TYPE_LABEL, cx, fmtDate, titleCase } from '../lib/format'
import { useApp } from '../state/AppContext'

const EXAMPLES = [
  'Find the pregnant patients who may need anesthesia to know about them before delivery',
  'Show me every patient with a documented low platelet count',
  'Which obstetric patients have hematology-related concerns?',
  'Find every patient with repeated fractures',
  'Show all patients with multiple abnormal calcium tests',
  'Which patients have records from more than one hospital?',
  'Find repeated visits involving the same symptom',
]

type Mode = 'ai' | 'keyword'
interface Filters { patientId: string; orgId: string; recordType: string; from: string; to: string }
const EMPTY_FILTERS: Filters = { patientId: '', orgId: '', recordType: '', from: '', to: '' }

export default function ClinicalSearch() {
  const [params, setParams] = useSearchParams()
  const [mode, setMode] = useState<Mode>((params.get('mode') as Mode) || 'ai')
  const [query, setQuery] = useState(params.get('q') ?? '')
  const [filters, setFilters] = useState<Filters>({ ...EMPTY_FILTERS, patientId: params.get('patientId') ?? '' })
  const [stage, setStage] = useState<'idle' | 'interpreting' | 'searching' | 'done'>('idle')
  const [interp, setInterp] = useState<Interpretation | null>(null)
  const [result, setResult] = useState<SearchResult | null>(null)
  const [summary, setSummary] = useState<AiAnswer | null>(null)
  const [summaryState, setSummaryState] = useState<'idle' | 'loading' | 'error'>('idle')
  const [summaryError, setSummaryError] = useState<unknown>(null)
  const [error, setError] = useState<unknown>(null)
  const [discuss, setDiscuss] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)
  const ran = useRef(false)
  const meta = useMeta()
  const patients = usePatients()
  const { user } = useApp()

  useEffect(() => {
    inputRef.current?.focus()
    if (!ran.current && params.get('q')) {
      ran.current = true
      run(params.get('q')!, (params.get('mode') as Mode) || 'ai')
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function mergeFilters(c: SearchCriteria): SearchCriteria {
    return {
      ...c,
      patientId: filters.patientId || c.patientId || null,
      organizationIds: filters.orgId ? [filters.orgId] : c.organizationIds,
      recordTypes: filters.recordType ? [filters.recordType] : c.recordTypes,
      dateFrom: filters.from || c.dateFrom || null,
      dateTo: filters.to || c.dateTo || null,
    }
  }

  async function run(q: string, m: Mode = mode) {
    const text = q.trim()
    const hasFilter = Object.values(filters).some(Boolean)
    if (!text && !hasFilter) return
    setError(null); setInterp(null); setResult(null); setSummary(null); setSummaryState('idle'); setDiscuss(false)
    setParams((p) => { const n = new URLSearchParams(p); n.set('q', text); n.set('mode', m); return n }, { replace: true })
    try {
      let res: SearchResult
      let usedQuery = text
      if (m === 'ai' && text) {
        setStage('interpreting')
        const i = await interpretSearch(text, filters.patientId || undefined)
        setInterp(i)
        setStage('searching')
        res = await executeSearch(mergeFilters(i.criteria))
      } else {
        setStage('searching')
        const sp = new URLSearchParams()
        if (text) sp.set('q', text)
        Object.entries(filters).forEach(([k, v]) => { if (v) sp.set(k === 'orgId' ? 'orgId' : k, v) })
        res = await get<SearchResult>('/search?' + sp.toString())
        usedQuery = text || 'filtered records'
      }
      setResult(res)
      setStage('done')
      if (m === 'ai' && res.totalRecords > 0) {
        setSummaryState('loading')
        const ids = res.patients.flatMap((p) => p.records.slice(0, 12).map((r) => r.recordId)).slice(0, 40)
        summarizeSearch(usedQuery, ids)
          .then((s) => { setSummary(s); setSummaryState('idle') })
          .catch((e) => { setSummaryError(e); setSummaryState('error') })
      }
    } catch (e) {
      setError(e)
      setStage('idle')
    }
  }

  const recordIds = result ? result.patients.flatMap((p) => p.records.map((r) => r.recordId)).slice(0, 40) : []

  return (
    <div>
      <PageHeader eyebrow="Feature A" title="Clinical Search"
        subtitle="Search records across all simulated organizations in plain language. Gemini turns the request into approved search criteria; the backend runs parameterized queries against the database — the model never writes SQL." />

      <Card className="mb-5 border-coral-200 shadow-[0_8px_30px_-12px_rgba(238,107,114,0.35)]">
        <form onSubmit={(e) => { e.preventDefault(); run(query) }}>
          <div className="flex flex-wrap items-center gap-2">
            <div className="flex rounded-lg bg-navy-50 p-0.5 text-[13px]">
              <button type="button" onClick={() => setMode('ai')} className={cx('inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 font-medium', mode === 'ai' ? 'bg-white text-coral-700 shadow-sm' : 'text-navy-500')}>
                <Sparkles className="h-3.5 w-3.5" /> Ask Gemini
              </button>
              <button type="button" onClick={() => setMode('keyword')} className={cx('inline-flex items-center gap-1.5 rounded-md px-3 py-1.5 font-medium', mode === 'keyword' ? 'bg-white text-navy-900 shadow-sm' : 'text-navy-500')}>
                <Type className="h-3.5 w-3.5" /> Keyword
              </button>
            </div>
            <span className="text-[12px] text-navy-400">Press <kbd className="rounded border border-line bg-white px-1">Ctrl</kbd>+<kbd className="rounded border border-line bg-white px-1">K</kbd> anywhere to focus search</span>
          </div>
          <div className="relative mt-3">
            {mode === 'ai' ? <Sparkles className="pointer-events-none absolute left-4 top-1/2 h-5 w-5 -translate-y-1/2 text-coral-400" /> : <Search className="pointer-events-none absolute left-4 top-1/2 h-5 w-5 -translate-y-1/2 text-navy-400" />}
            <input
              id="clinical-search-input"
              ref={inputRef}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder={mode === 'ai' ? 'e.g. Which pregnant patients with upcoming deliveries have hematology concerns?' : 'e.g. platelet, fracture, epidural…'}
              className="h-14 w-full rounded-xl border border-line bg-canvas pl-12 pr-36 text-[15px] text-navy-900 placeholder:text-navy-400 focus:border-coral-300 focus:bg-white focus:outline-none focus:ring-4 focus:ring-coral-100"
            />
            <Button type="submit" variant="primary" className="absolute right-2 top-1/2 -translate-y-1/2" loading={stage === 'interpreting' || stage === 'searching'}>
              Search <ArrowRight className="h-4 w-4" />
            </Button>
          </div>
          <div className="mt-3 flex flex-wrap items-end gap-2">
            <Filter className="mb-2 h-4 w-4 text-navy-400" />
            <Select label="Patient" value={filters.patientId} onChange={(v) => setFilters((f) => ({ ...f, patientId: v }))}
              options={(patients.data ?? []).map((p) => [p.patientId, p.name])} />
            <Select label="Organization" value={filters.orgId} onChange={(v) => setFilters((f) => ({ ...f, orgId: v }))}
              options={(meta.data?.organizations ?? []).map((o) => [o.orgId, o.name])} />
            <Select label="Record type" value={filters.recordType} onChange={(v) => setFilters((f) => ({ ...f, recordType: v }))}
              options={Object.entries(RECORD_TYPE_LABEL)} />
            <DateInput label="From" value={filters.from} onChange={(v) => setFilters((f) => ({ ...f, from: v }))} />
            <DateInput label="To" value={filters.to} onChange={(v) => setFilters((f) => ({ ...f, to: v }))} />
            {Object.values(filters).some(Boolean) && (
              <button type="button" onClick={() => setFilters(EMPTY_FILTERS)} className="mb-1.5 inline-flex items-center gap-1 text-[12px] text-navy-500 hover:text-coral-700"><X className="h-3 w-3" />Clear filters</button>
            )}
          </div>
        </form>
        {stage === 'idle' && !result && (
          <div className="mt-4 flex flex-wrap gap-1.5 border-t border-line pt-4">
            {EXAMPLES.map((ex) => (
              <button key={ex} onClick={() => { setQuery(ex); setMode('ai'); run(ex, 'ai') }} className="rounded-full border border-line bg-white px-3 py-1 text-[12.5px] text-navy-700 hover:border-coral-200 hover:bg-coral-50">
                {ex}
              </button>
            ))}
          </div>
        )}
      </Card>

      {error != null && <div className="mb-4"><ErrorBox error={error} title="Search failed" /></div>}
      {stage === 'interpreting' && <GeminiThinking label="Gemini is interpreting your request into structured, allow-listed search criteria…" />}
      {stage === 'searching' && <div className="flex items-center gap-2 rounded-lg border border-line bg-white px-4 py-3 text-[13px] text-navy-600"><Database className="h-4 w-4 text-sage-600" /> Running approved, parameterized queries against the clinical record index…</div>}

      {interp && <InterpretationCard interp={interp} />}

      {result && (
        <div className="mt-5 grid grid-cols-1 gap-6 xl:grid-cols-[minmax(0,1fr)_400px]">
          <div className="min-w-0 space-y-4">
            {(summaryState !== 'idle' || summary) && (
              <Card className="border-coral-200">
                <CardHeader icon={<Sparkles className="h-4 w-4 text-coral-500" />} title="Summary of findings"
                  subtitle="Gemini summarizes only the retrieved records; every claim cites a record below." />
                {summaryState === 'loading' && <GeminiThinking label={`Gemini is analyzing ${Math.min(recordIds.length, 40)} retrieved records…`} />}
                {summaryState === 'error' && <ErrorBox error={summaryError} title="Gemini summary unavailable" />}
                {summary && <AiAnswerView answer={summary} />}
              </Card>
            )}
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="text-[13px] text-navy-600">
                <span className="font-semibold text-navy-900">{result.totalRecords}</span> matching records across <span className="font-semibold text-navy-900">{result.totalPatients}</span> patient{result.totalPatients === 1 ? '' : 's'}
                {result.truncated && <span className="text-amber-700"> (truncated)</span>}
              </div>
              {recordIds.length > 0 && <Button size="sm" variant={discuss ? 'secondary' : 'primary'} onClick={() => setDiscuss((d) => !d)}><MessageSquareText className="h-3.5 w-3.5" />{discuss ? 'Hide assistant' : 'Discuss results with Gemini'}</Button>}
            </div>
            {result.warnings.length > 0 && <div className="rounded-lg bg-amber-50 px-3 py-2 text-[12.5px] text-amber-700">{result.warnings.join(' · ')}</div>}
            {result.patients.length === 0 && <Card><EmptyState title="No matching records">Try different wording or remove filters. No results does not mean a condition is absent.</EmptyState></Card>}
            {result.patients.map((g) => <PatientResults key={g.patientId} group={g} />)}
          </div>
          {discuss && recordIds.length > 0 && (
            <div className="xl:sticky xl:top-0 xl:self-start">
              <AssistantPanel context={{ type: 'SEARCH', recordIds, label: `${recordIds.length} search results` }}
                suggestions={['Explain the pattern across these records.', 'Which organizations documented these findings?', 'What information is missing from these results?']}
                className="xl:h-[calc(100vh-150px)]" />
            </div>
          )}
        </div>
      )}
      <p className="mt-6 text-[11.5px] text-navy-400">Signed in as {user.name} (simulated). Search runs over synthetic records only.</p>
    </div>
  )
}

function InterpretationCard({ interp }: { interp: Interpretation }) {
  const c = interp.criteria
  const chips: [string, string][] = [
    ...c.concepts.map((x) => ['Concept', titleCase(x.replace('SYMPTOM_', 'Symptom: '))] as [string, string]),
    ...c.keywords.map((x) => ['Keyword', x] as [string, string]),
    ...c.recordTypes.map((x) => ['Record type', RECORD_TYPE_LABEL[x as keyof typeof RECORD_TYPE_LABEL] ?? x] as [string, string]),
    ...c.organizationIds.map((x) => ['Organization', x] as [string, string]),
  ]
  if (c.patientId) chips.push(['Patient', c.patientId])
  if (c.obstetricOnly) chips.push(['Population', 'Obstetric patients'])
  if (c.upcomingDeliveryOnly) chips.push(['Population', 'Upcoming delivery-related encounter'])
  if (c.multipleOrganizationsOnly) chips.push(['Population', 'Records from >1 organization'])
  if (c.openFlagsOnly) chips.push(['Population', 'Open flags'])
  if (c.dateFrom || c.dateTo) chips.push(['Dates', `${c.dateFrom ?? '…'} → ${c.dateTo ?? '…'}`])
  if ((c.minMatchesPerPatient ?? 1) > 1) chips.push(['Repeated', `≥ ${c.minMatchesPerPatient} separate dates per patient`])
  return (
    <Card className="mt-4">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-[12px] font-semibold uppercase tracking-wide text-navy-500">Interpreted query</div>
          {interp.explanation && <p className="mt-1 text-[13.5px] text-navy-800">{interp.explanation}</p>}
        </div>
        {interp.source === 'GEMINI'
          ? <Badge tone="coral"><Sparkles className="h-3 w-3" />Interpreted by {interp.model}{interp.latencyMs ? ` · ${(interp.latencyMs / 1000).toFixed(1)} s` : ''}</Badge>
          : <Badge tone="amber">Rule-based interpretation (not Gemini)</Badge>}
      </div>
      <div className="mt-3 flex flex-wrap gap-1.5">
        {chips.map(([k, v], i) => (
          <span key={i} className="inline-flex items-center gap-1 rounded-lg border border-line bg-canvas px-2 py-1 text-[12.5px]">
            <span className="text-navy-400">{k}:</span><span className="font-medium text-navy-800">{v}</span>
          </span>
        ))}
        <span className="inline-flex items-center rounded-lg border border-line bg-canvas px-2 py-1 text-[12.5px] text-navy-500">Match {c.matchMode === 'ALL' ? 'all terms' : 'any term'}</span>
      </div>
      {interp.notice && <div className="mt-2 text-[12px] text-amber-700">{interp.notice}</div>}
    </Card>
  )
}

function PatientResults({ group }: { group: SearchResult['patients'][number] }) {
  const { openRecord } = useApp()
  const [expanded, setExpanded] = useState(false)
  const shown = expanded ? group.records : group.records.slice(0, 6)
  return (
    <Card padded={false}>
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line px-4 py-3">
        <div>
          <Link to={`/patients/${group.patientId}`} className="text-[14.5px] font-semibold text-navy-900 hover:text-coral-700">{group.patientName}</Link>
          <span className="ml-2 text-[12px] text-navy-400">{group.displayId}</span>
          <div className="mt-0.5 text-[12px] text-navy-500">{group.matchCount} records on {group.distinctDates} separate dates</div>
        </div>
        <div className="flex flex-wrap gap-1">{group.organizations.map((o) => <Badge key={o} tone="outline"><Building2 className="h-3 w-3" />{o}</Badge>)}</div>
      </div>
      <ul className="divide-y divide-line">
        {shown.map((r) => (
          <li key={r.recordId}>
            <button onClick={() => openRecord(r.recordId)} className="flex w-full items-start gap-3 px-4 py-2.5 text-left hover:bg-canvas">
              <RecordTypeIcon type={r.recordType} className="mt-0.5 h-4 w-4 shrink-0 text-navy-400" />
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-[13.5px] font-medium text-navy-900">{r.title}</span>
                  <span className="text-[12px] text-navy-400">{fmtDate(r.recordDate)}</span>
                </div>
                <div className="mt-0.5 line-clamp-2 text-[12.5px] text-navy-600">{r.snippet}</div>
                <div className="mt-1 flex flex-wrap items-center gap-x-2 text-[11.5px] text-navy-400">
                  <span className="font-medium text-navy-500">{r.orgName}</span><span>· {RECORD_TYPE_LABEL[r.recordType]}</span>
                  <span className="font-mono">· {r.recordId}</span>
                </div>
              </div>
            </button>
          </li>
        ))}
      </ul>
      {group.records.length > 6 && (
        <button onClick={() => setExpanded((e) => !e)} className="w-full border-t border-line py-2 text-[12.5px] font-medium text-navy-500 hover:text-coral-700">
          {expanded ? 'Show fewer' : `Show all ${group.records.length} records`}
        </button>
      )}
    </Card>
  )
}

function Select({ label, value, onChange, options }: { label: string; value: string; onChange: (v: string) => void; options: [string, string][] }) {
  return (
    <label className="flex flex-col text-[11.5px] font-medium text-navy-500">
      {label}
      <select value={value} onChange={(e) => onChange(e.target.value)}
        className={cx('mt-0.5 h-8 max-w-[220px] rounded-lg border bg-white px-2 text-[13px] focus:outline-none focus:ring-2 focus:ring-coral-100', value ? 'border-coral-300 text-navy-900' : 'border-line text-navy-600')}>
        <option value="">Any</option>
        {options.map(([v, l]) => <option key={v} value={v}>{l}</option>)}
      </select>
    </label>
  )
}

function DateInput({ label, value, onChange }: { label: string; value: string; onChange: (v: string) => void }) {
  return (
    <label className="flex flex-col text-[11.5px] font-medium text-navy-500">
      {label}
      <input type="date" value={value} onChange={(e) => onChange(e.target.value)}
        className={cx('mt-0.5 h-8 rounded-lg border bg-white px-2 text-[13px] focus:outline-none focus:ring-2 focus:ring-coral-100', value ? 'border-coral-300' : 'border-line text-navy-600')} />
    </label>
  )
}
