import { CircleCheck, Database, FlaskConical, RotateCcw, ShieldAlert, Sparkles, TriangleAlert } from 'lucide-react'
import { useState } from 'react'
import { post } from '../api/client'
import { useIntegrationStatus, useInvalidateWorkflow, keys } from '../api/hooks'
import { Badge, Button, Card, CardHeader, ErrorBox, KeyValue, Loading, PageHeader } from '../components/ui'
import { fmtDate, fmtDateTime } from '../lib/format'
import { useApp } from '../state/AppContext'
import { useQueryClient } from '@tanstack/react-query'

export default function SystemStatus() {
  const { data, isLoading, error, refetch } = useIntegrationStatus()
  const [dbCheck, setDbCheck] = useState<Record<string, unknown> | null>(null)
  const [gemCheck, setGemCheck] = useState<Record<string, unknown> | null>(null)
  const [busy, setBusy] = useState<string | null>(null)
  const [err, setErr] = useState<{ where: string; e: unknown } | null>(null)
  const invalidate = useInvalidateWorkflow()
  const qc = useQueryClient()
  const { toast } = useApp()

  async function act(where: string, fn: () => Promise<void>) {
    setBusy(where); setErr(null)
    try { await fn() } catch (e) { setErr({ where, e }) } finally { setBusy(null) }
  }

  return (
    <div>
      <PageHeader eyebrow="Sponsor integrations" title="System & integrations"
        subtitle="Live connectivity checks for Snowflake and Google Gemini, plus demo data controls. Every check below performs a real request." />
      {isLoading && <Loading />}
      {error && <ErrorBox error={error} title="Backend unreachable" />}
      {data && (
        <div className="grid grid-cols-1 gap-5 xl:grid-cols-2">
          <Card>
            <CardHeader icon={<Database className="h-4 w-4" />} title="Database — Snowflake"
              actions={data.database.isSnowflake ? <Badge tone="sage"><CircleCheck className="h-3 w-3" />Snowflake connected</Badge>
                : <Badge tone="amber"><TriangleAlert className="h-3 w-3" />Local fallback — NOT Snowflake</Badge>} />
            {!data.database.isSnowflake && (
              <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-[13px] text-amber-800">
                Snowflake is not configured, so the backend is using the clearly labeled local development database (H2). Set the SNOWFLAKE_* values in
                <code className="mx-1 rounded bg-white px-1">backend/.env</code> (see <code className="rounded bg-white px-1">.env.example</code>) and restart to use Snowflake.
              </div>
            )}
            <div className="grid grid-cols-2 gap-3">
              <KeyValue label="Mode">{data.database.label}</KeyValue>
              <KeyValue label="Connected">{data.database.connected ? 'Yes' : 'No'}</KeyValue>
              {data.database.session && Object.entries(data.database.session).map(([k, v]) => <KeyValue key={k} label={k.replace(/_/g, ' ')}>{String(v ?? '—')}</KeyValue>)}
              <KeyValue label="Patients stored">{data.database.patientCount ?? '—'}</KeyValue>
            </div>
            {data.database.error && <div className="mt-3"><ErrorBox error={data.database.error} title="Connection error" /></div>}
            <div className="mt-4 border-t border-line pt-4">
              <Button variant="navy" loading={busy === 'db'} onClick={() => act('db', async () => { setDbCheck(await post('/integrations/database/check')); refetch() })}>
                Run read / write check
              </Button>
              <p className="mt-2 text-[12px] text-navy-500">Reads a demo patient with a JOIN, inserts a row into INTEGRATION_CHECKS, and reads it back.</p>
              {err?.where === 'db' && <div className="mt-3"><ErrorBox error={err.e} /></div>}
              {dbCheck && <pre className="scroll-thin mt-3 max-h-72 overflow-auto rounded-lg bg-navy-950 p-3 font-mono text-[11.5px] text-navy-100">{JSON.stringify(dbCheck, null, 2)}</pre>}
            </div>
          </Card>

          <Card>
            <CardHeader icon={<Sparkles className="h-4 w-4 text-coral-500" />} title="AI — Google Gemini"
              actions={data.gemini.configured ? <Badge tone="coral"><CircleCheck className="h-3 w-3" />API key configured</Badge>
                : <Badge tone="amber"><TriangleAlert className="h-3 w-3" />Not configured</Badge>} />
            {!data.gemini.configured && (
              <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-[13px] text-amber-800">
                GEMINI_API_KEY is not set. AI features show a clearly labeled “retrieval only” mode and search uses a rule-based interpreter. No simulated AI text is ever shown.
              </div>
            )}
            <div className="grid grid-cols-2 gap-3">
              <KeyValue label="Model">{data.gemini.model}</KeyValue>
              <KeyValue label="SDK">{data.gemini.sdk}</KeyValue>
              <KeyValue label="Calls since startup">{data.gemini.callsSinceStartup}</KeyValue>
              <KeyValue label="Failures">{data.gemini.failuresSinceStartup}</KeyValue>
              <KeyValue label="Last success">{fmtDateTime(data.gemini.lastSuccessAt)}</KeyValue>
              <KeyValue label="Last operation">{data.gemini.lastOperation ?? '—'}</KeyValue>
            </div>
            {data.gemini.lastError && <div className="mt-3 text-[12.5px] text-critical-700">Last error: {data.gemini.lastError}</div>}
            <div className="mt-4 border-t border-line pt-4">
              <Button variant="primary" disabled={!data.gemini.configured} loading={busy === 'gem'}
                onClick={() => act('gem', async () => { setGemCheck(await post('/integrations/gemini/check')); refetch() })}>
                <Sparkles className="h-4 w-4" /> Send live test request
              </Button>
              <p className="mt-2 text-[12px] text-navy-500">Makes a real Gemini API call with structured JSON output. The key never leaves the backend.</p>
              {err?.where === 'gem' && <div className="mt-3"><ErrorBox error={err.e} /></div>}
              {gemCheck && <pre className="scroll-thin mt-3 max-h-72 overflow-auto rounded-lg bg-navy-950 p-3 font-mono text-[11.5px] text-navy-100">{JSON.stringify(gemCheck, null, 2)}</pre>}
            </div>
          </Card>

          <Card>
            <CardHeader icon={<FlaskConical className="h-4 w-4" />} title="Demo data" subtitle={`Synthetic dates are anchored to ${fmtDate(data.anchorDate)}.`} />
            <div className="space-y-4">
              <div>
                <Button loading={busy === 'lab'} onClick={() => act('lab', async () => {
                  const r = await post<{ ingest: { recordsInserted: number }; analysis: { flagsUpdated: number; flagsCreated: number } }>('/admin/simulate-lab-result')
                  invalidate()
                  toast({ tone: 'success', title: 'New HL7 result ingested', body: `${r.ingest.recordsInserted} records added; re-evaluation updated ${r.analysis.flagsUpdated} and created ${r.analysis.flagsCreated} flag(s).` })
                })}>
                  <FlaskConical className="h-4 w-4" /> Simulate incoming lab result (Ava Marlowe)
                </Button>
                <p className="mt-1.5 text-[12px] text-navy-500">Ingests a new Northgate HL7 platelet result dated yesterday and re-runs screening. The existing alert is updated with the new evidence — no duplicate alert is created. Running it again inserts nothing.</p>
              </div>
              <div className="border-t border-line pt-4">
                <Button variant="danger" loading={busy === 'reset'} onClick={() => {
                  if (!window.confirm('Delete all demo data, workflow statuses and notes, then re-seed anchored to today?')) return
                  act('reset', async () => {
                    await post('/admin/reset-demo')
                    invalidate(); qc.invalidateQueries({ queryKey: keys.status })
                    toast({ tone: 'success', title: 'Demo data reset', body: 'Re-seeded and re-screened with today as the anchor date.' })
                  })
                }}>
                  <RotateCcw className="h-4 w-4" /> Reset demo data
                </Button>
                <p className="mt-1.5 text-[12px] text-navy-500">Use before a presentation so upcoming encounters are relative to today. This removes all notes and status changes.</p>
              </div>
              {(err?.where === 'lab' || err?.where === 'reset') && <ErrorBox error={err.e} />}
            </div>
          </Card>

          <Card>
            <CardHeader icon={<ShieldAlert className="h-4 w-4" />} title="Safety & access" />
            <ul className="list-disc space-y-1.5 pl-5 text-[13px] text-navy-700">
              <li>{data.syntheticDataNotice}</li>
              <li>Users and teams are simulated; there is no authentication. Never use this prototype with real patient data.</li>
              <li>Not HIPAA compliant, not clinically validated, and not connected to Epic or any production EHR.</li>
              <li>Screening thresholds are configurable prototype heuristics in <code className="rounded bg-canvas px-1">application.yml</code>.</li>
              <li>Gemini answers are advisory, cite verified source records, and can never change clinical data or workflow status.</li>
              <li>Alerts are routed to an in-app queue only — no messages are sent to patients or clinicians.</li>
            </ul>
          </Card>
        </div>
      )}
    </div>
  )
}
