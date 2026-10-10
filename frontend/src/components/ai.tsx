import { CircleCheck, CircleHelp, Lightbulb, RefreshCw, SendHorizontal, ShieldCheck, Sparkles, TriangleAlert } from 'lucide-react'
import { Fragment, useEffect, useRef, useState, type ReactNode } from 'react'
import { ApiError, post } from '../api/client'
import { useExplainFlag } from '../api/hooks'
import type { AiAnswer, Statement } from '../api/types'
import { cx, fmtDate, fmtDateTime } from '../lib/format'
import { CitationChip } from './records'
import { Badge, Button, Card, CardHeader, ErrorBox, Spinner } from './ui'

const ID_RE = /(REC-[A-Z0-9]+(?:-[A-Z0-9]+)*)/g
const IS_ID = /^REC-[A-Z0-9]+(?:-[A-Z0-9]+)*$/

/** Renders text, turning verified record IDs into clickable citation chips. */
export function CitedText({ text }: { text: string }) {
  const parts = text.split(ID_RE)
  return (
    <>
      {parts.map((p, i) => (IS_ID.test(p)
        ? <CitationChip key={i} recordId={p} className="mx-0.5" />
        : <Fragment key={i}>{p.replace(/\[\s*\]/g, '').replace(/\[(?=\s*$)|^\s*\]/g, '')}</Fragment>))}
    </>
  )
}

export function AiBadge({ model }: { model?: string }) {
  return (
    <span className="inline-flex items-center gap-1 rounded-md bg-gradient-to-r from-coral-50 to-white px-1.5 py-0.5 text-[11px] font-semibold text-coral-700 ring-1 ring-inset ring-coral-200">
      <Sparkles className="h-3 w-3" /> AI-generated{model ? ` · ${model}` : ''}
    </span>
  )
}

function StatementList({ items, tone, icon, title, hint }: { items: Statement[]; tone: 'sage' | 'coral'; icon: ReactNode; title: string; hint: string }) {
  if (!items.length) return null
  return (
    <div className={cx('rounded-lg border p-3', tone === 'sage' ? 'border-sage-200 bg-sage-50/60' : 'border-coral-200 bg-coral-50/50')}>
      <div className={cx('mb-1.5 flex items-center gap-1.5 text-[12px] font-semibold uppercase tracking-wide', tone === 'sage' ? 'text-sage-700' : 'text-coral-700')}>
        {icon}{title}<span className="font-normal normal-case tracking-normal text-navy-400">— {hint}</span>
      </div>
      <ul className="space-y-1.5">
        {items.map((s, i) => (
          <li key={i} className="text-[13.5px] leading-relaxed text-navy-900">
            <CitedText text={s.statement} />
            {s.sourceRecordIds.filter((id) => !s.statement.includes(id)).map((id) => <CitationChip key={id} recordId={id} className="ml-1" />)}
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Structured, explainable rendering of a Gemini answer. */
export function AiAnswerView({ answer, compact }: { answer: AiAnswer; compact?: boolean }) {
  if (answer.mode === 'RETRIEVAL_ONLY') {
    return (
      <div className="space-y-3">
        <div className="flex items-start gap-2 rounded-lg border border-amber-200 bg-amber-50 p-3 text-[13px] text-amber-700">
          <TriangleAlert className="mt-0.5 h-4 w-4 shrink-0" />
          <div><span className="font-semibold">Gemini not configured — no AI answer generated.</span> {answer.answer}</div>
        </div>
        <RetrievedList answer={answer} />
      </div>
    )
  }
  return (
    <div className="space-y-3">
      <p className="text-[14px] leading-relaxed text-navy-900"><CitedText text={answer.answer} /></p>
      <StatementList items={answer.documentedFacts} tone="sage" icon={<CircleCheck className="h-3.5 w-3.5" />}
        title="Documented in records" hint="confirmed by cited sources" />
      <StatementList items={answer.interpretation} tone="coral" icon={<Lightbulb className="h-3.5 w-3.5" />}
        title="AI interpretation" hint="for clinician review, not a determination" />
      {answer.missingInformation.length > 0 && (
        <div className="rounded-lg border border-amber-200 bg-amber-50/60 p-3">
          <div className="mb-1.5 flex items-center gap-1.5 text-[12px] font-semibold uppercase tracking-wide text-amber-700">
            <CircleHelp className="h-3.5 w-3.5" />Not found in available records
            <span className="font-normal normal-case tracking-normal text-navy-400">— missing ≠ absent</span>
          </div>
          <ul className="list-disc space-y-1 pl-5 text-[13.5px] text-navy-800">
            {answer.missingInformation.map((m, i) => <li key={i}><CitedText text={m} /></li>)}
          </ul>
        </div>
      )}
      {!compact && answer.citations.length > 0 && (
        <div>
          <div className="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-navy-500">Sources cited ({answer.citations.length})</div>
          <div className="space-y-1">
            {answer.citations.map((c) => (
              <div key={c.recordId} className="flex min-w-0 items-center gap-2 text-[12.5px] text-navy-600">
                <CitationChip recordId={c.recordId} className="shrink-0" />
                <span className="min-w-0 truncate text-navy-800">{c.title}<span className="text-navy-400"> · {c.orgName} · {fmtDate(c.recordDate)}</span></span>
              </div>
            ))}
          </div>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1 border-t border-line pt-2 text-[11.5px] text-navy-400">
        <AiBadge model={answer.model} />
        <span className="inline-flex items-center gap-1"><ShieldCheck className="h-3 w-3 text-sage-600" />
          {answer.citations.length} verified citation{answer.citations.length === 1 ? '' : 's'} from {answer.validation.recordsProvided} retrieved records</span>
        {(answer.validation.citationsRemoved > 0 || answer.validation.uncitedFactsRemoved > 0) && (
          <span className="text-amber-700">
            {answer.validation.citationsRemoved} unverifiable reference(s) and {answer.validation.uncitedFactsRemoved} uncited fact(s) removed
          </span>
        )}
        {answer.latencyMs != null && <span>{(answer.latencyMs / 1000).toFixed(1)} s</span>}
        <span>Verify against sources · not for clinical use</span>
      </div>
    </div>
  )
}

function RetrievedList({ answer }: { answer: AiAnswer }) {
  if (!answer.retrievedRecords.length) return null
  return (
    <div>
      <div className="mb-1.5 text-[12px] font-semibold uppercase tracking-wide text-navy-500">Retrieved records ({answer.retrievedRecords.length})</div>
      <div className="space-y-1">
        {answer.retrievedRecords.slice(0, 12).map((c) => (
          <div key={c.recordId} className="flex min-w-0 items-center gap-2 text-[12.5px] text-navy-600">
            <CitationChip recordId={c.recordId} className="shrink-0" />
            <span className="min-w-0 truncate text-navy-800">{c.title}<span className="text-navy-400"> · {c.orgName} · {fmtDate(c.recordDate)}</span></span>
          </div>
        ))}
      </div>
    </div>
  )
}

/** Gemini explanation for a flag (cached on the flag; regenerate on demand). */
export function FlagExplanationCard({ flagId, cached }: { flagId?: string; cached?: AiAnswer }) {
  const explain = useExplainFlag(flagId)
  const answer = explain.data ?? cached
  return (
    <Card>
      <CardHeader
        icon={<Sparkles className="h-4 w-4 text-coral-500" />}
        title="Why was this flagged? — Gemini explanation"
        subtitle="Evidence-grounded explanation of the deterministic detection. Advisory only; it cannot change workflow status."
        actions={answer ? (
          <Button size="sm" variant="ghost" onClick={() => explain.mutate(true)} loading={explain.isPending} disabled={!flagId}>
            <RefreshCw className="h-3.5 w-3.5" /> Regenerate
          </Button>
        ) : null}
      />
      {explain.isPending && <GeminiThinking label="Gemini is reviewing this flag's evidence records…" />}
      {explain.error && <ErrorBox error={explain.error} title="Gemini explanation failed" />}
      {!explain.isPending && answer && (
        <>
          {!explain.data && cached && <div className="mb-2 text-[11.5px] text-navy-400">Saved explanation from {fmtDateTime(cached.generatedAt)}</div>}
          <AiAnswerView answer={answer} />
        </>
      )}
      {!explain.isPending && !answer && (
        <div className="flex flex-col items-start gap-3 rounded-lg border border-dashed border-coral-200 bg-coral-50/40 p-4">
          <p className="text-[13.5px] text-navy-700">Ask Gemini to explain this flag using only its cited evidence records. Every factual claim is checked against the records that were sent.</p>
          <Button variant="primary" onClick={() => explain.mutate(false)} disabled={!flagId}><Sparkles className="h-4 w-4" /> Explain with Gemini</Button>
        </div>
      )}
    </Card>
  )
}

export function GeminiThinking({ label }: { label: string }) {
  return (
    <div className="flex items-center gap-3 rounded-lg border border-coral-100 bg-gradient-to-r from-coral-50 to-white px-4 py-3 text-[13px] text-coral-800">
      <Spinner className="h-5 w-5" />
      <span>{label}</span>
    </div>
  )
}

// =====================================================================================================
// Interactive assistant
// =====================================================================================================

export type AssistantContext =
  | { type: 'PATIENT' | 'FLAG' | 'ALERT'; id: string; label: string }
  | { type: 'SEARCH'; recordIds: string[]; label: string }

interface Msg { role: 'user' | 'assistant'; text: string; answer?: AiAnswer; error?: string }

/** Multi-turn Gemini chat scoped to exactly one context; switching context resets the conversation. */
export function AssistantPanel({ context, suggestions, className }: { context: AssistantContext; suggestions: string[]; className?: string }) {
  const contextKey = context.type === 'SEARCH' ? 'SEARCH:' + context.recordIds.join(',') : context.type + ':' + context.id
  const [messages, setMessages] = useState<Msg[]>([])
  const [conversationId, setConversationId] = useState<string | undefined>()
  const [input, setInput] = useState('')
  const [pending, setPending] = useState(false)
  const scrollRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    // Prevent cross-patient leakage: a new context always starts a new conversation.
    setMessages([])
    setConversationId(undefined)
    setInput('')
  }, [contextKey])

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' })
  }, [messages, pending])

  async function ask(question: string) {
    const q = question.trim()
    if (!q || pending) return
    setMessages((m) => [...m, { role: 'user', text: q }])
    setInput('')
    setPending(true)
    try {
      const body = context.type === 'SEARCH'
        ? { conversationId, contextType: 'SEARCH', recordIds: context.recordIds, message: q }
        : { conversationId, contextType: context.type, contextId: context.id, message: q }
      const answer = await post<AiAnswer>('/ai/chat', body)
      setConversationId(answer.conversationId)
      setMessages((m) => [...m, { role: 'assistant', text: answer.answer, answer }])
    } catch (e) {
      const msg = e instanceof ApiError ? e.message : 'Request failed'
      if (e instanceof ApiError && e.code === 'CONTEXT_MISMATCH') setConversationId(undefined)
      setMessages((m) => [...m, { role: 'assistant', text: '', error: msg }])
    } finally {
      setPending(false)
    }
  }

  const followUps = [...messages].reverse().find((m) => m.answer)?.answer?.suggestedFollowUpQuestions ?? []

  return (
    <div className={cx('flex min-h-[520px] flex-col overflow-hidden rounded-xl border border-line bg-white', className)}>
      <div className="flex items-center gap-2.5 border-b border-line bg-gradient-to-r from-coral-50/70 to-white px-4 py-3">
        <div className="flex h-8 w-8 items-center justify-center rounded-lg bg-coral-500 text-white"><Sparkles className="h-4 w-4" /></div>
        <div className="min-w-0 flex-1">
          <div className="text-[14px] font-semibold text-navy-900">Gemini clinical assistant</div>
          <div className="truncate text-[12px] text-navy-500">Context: {context.label}</div>
        </div>
        {messages.length > 0 && (
          <button onClick={() => { setMessages([]); setConversationId(undefined) }} className="text-[12px] font-medium text-navy-500 hover:text-coral-700">New chat</button>
        )}
      </div>
      <div ref={scrollRef} className="scroll-thin flex-1 space-y-4 overflow-y-auto px-4 py-4">
        {messages.length === 0 && (
          <div className="space-y-3">
            <p className="text-[13px] leading-relaxed text-navy-600">
              Ask about this {context.type === 'SEARCH' ? 'set of search results' : context.type.toLowerCase()}. Camellia retrieves the relevant
              records from the database, sends only those to Gemini, and verifies every citation before showing the answer.
            </p>
            <div className="flex flex-col gap-1.5">
              {suggestions.map((s) => (
                <button key={s} onClick={() => ask(s)} className="rounded-lg border border-line bg-canvas px-3 py-2 text-left text-[13px] text-navy-800 hover:border-coral-200 hover:bg-coral-50">
                  {s}
                </button>
              ))}
            </div>
          </div>
        )}
        {messages.map((m, i) => m.role === 'user' ? (
          <div key={i} className="flex justify-end">
            <div className="max-w-[85%] rounded-2xl rounded-br-md bg-navy-800 px-3.5 py-2 text-[13.5px] text-white">{m.text}</div>
          </div>
        ) : (
          <div key={i} className="animate-fade-up">
            {m.error ? <ErrorBox error={m.error} title="Gemini request failed" /> : m.answer && <AiAnswerView answer={m.answer} compact />}
          </div>
        ))}
        {pending && <GeminiThinking label="Retrieving source records and asking Gemini…" />}
      </div>
      {followUps.length > 0 && !pending && (
        <div className="flex flex-wrap gap-1.5 border-t border-line px-4 pt-3">
          {followUps.map((f) => (
            <button key={f} onClick={() => ask(f)} className="rounded-full border border-line bg-white px-2.5 py-1 text-[12px] text-navy-700 hover:border-coral-200 hover:bg-coral-50">{f}</button>
          ))}
        </div>
      )}
      <form onSubmit={(e) => { e.preventDefault(); ask(input) }} className="flex items-end gap-2 p-3">
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); ask(input) } }}
          rows={2}
          placeholder="Ask a question about the records…"
          className="min-h-[44px] flex-1 resize-none rounded-lg border border-line bg-canvas px-3 py-2 text-[13.5px] placeholder:text-navy-400 focus:border-coral-300 focus:bg-white focus:outline-none focus:ring-2 focus:ring-coral-100"
        />
        <Button type="submit" variant="primary" disabled={!input.trim()} loading={pending} aria-label="Send"><SendHorizontal className="h-4 w-4" /></Button>
      </form>
      <div className="border-t border-line bg-canvas px-4 py-1.5 text-[11px] text-navy-400">
        <Badge tone="coral" className="mr-1.5">AI</Badge>Answers are AI-generated from synthetic records and never change clinical data or workflow status.
      </div>
    </div>
  )
}
