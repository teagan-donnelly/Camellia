import { useMutation, useQueryClient } from '@tanstack/react-query'
import {
  AudioLines, CalendarDays, Check, FileText, Gauge, Loader2, Pause, Play, RefreshCw, RotateCcw, RotateCw, ShieldAlert, Sparkles, Users,
} from 'lucide-react'
import { useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { post } from '../api/client'
import { useBriefing, useShifts } from '../api/hooks'
import type { BriefingSegment, BriefingView, ShiftView } from '../api/types'
import { Badge, Button, Card, CardHeader, EmptyState, ErrorBox, KeyValue, Loading, PageHeader } from '../components/ui'
import { cx, fmtDateTime } from '../lib/format'
import { useApp } from '../state/AppContext'

type Mode = 'FULL' | 'PRIORITY'

/**
 * Camellia Briefing: Snowflake shift assignments → authorized records → Gemini script → ElevenLabs audio.
 * The audio is fetched through the authorized API with the session cookie and played from a private in-memory blob;
 * there is no public audio URL.
 */
export default function Briefing() {
  const shifts = useShifts()
  const [shiftId, setShiftId] = useState<string | null>(null)
  const [mode, setMode] = useState<Mode>('FULL')
  const [briefingId, setBriefingId] = useState<string | undefined>()
  const qc = useQueryClient()
  const { toast } = useApp()

  useEffect(() => {
    if (!shiftId && shifts.data?.length) setShiftId(shifts.data[0].shiftId)
  }, [shifts.data, shiftId])
  const shift = shifts.data?.find((s) => s.shiftId === shiftId)
  useEffect(() => { setBriefingId(shift?.latest[mode]?.briefingId) }, [shift, mode])

  const briefing = useBriefing(briefingId)
  const generate = useMutation({
    mutationFn: (regenerate: boolean) => post<BriefingView>('/briefings/generate', { shiftId, mode, regenerate }),
    onSuccess: (b) => {
      setBriefingId(b.briefingId)
      qc.setQueryData(['briefing', b.briefingId], b)
      if (b.reused) toast({ tone: 'info', title: 'Reused the existing briefing', body: 'Nothing in the source records changed since it was generated. Use Regenerate to make a new one.' })
    },
    onError: (e) => toast({ tone: 'error', title: 'Could not start the briefing', body: (e as Error).message }),
  })
  const b = briefing.data
  const working = !!b && ['QUEUED', 'SCRIPTING', 'SYNTHESIZING'].includes(b.status)
  useEffect(() => { if (b && !working) qc.invalidateQueries({ queryKey: ['briefing-shifts'] }) }, [b?.status]) // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div>
      <PageHeader eyebrow="Before your shift" title="Camellia Briefing"
        subtitle="A short spoken handoff about your assigned patients: upcoming encounters, documented findings, open alerts and unresolved coordination tasks." />
      <div className="mb-5 flex items-start gap-2 rounded-xl border border-amber-200 bg-amber-50 px-4 py-3 text-[12.5px] text-amber-800">
        <ShieldAlert className="mt-0.5 h-4 w-4 shrink-0" />
        <span>Prototype using synthetic patients. Not for clinical use. A real deployment would need privacy safeguards before anyone listened to identifiable patient
          information in public or while driving, plus vendor agreements and organizational approval. Shifts and assignments are simulated.</span>
      </div>
      {shifts.isLoading && <Loading />}
      {shifts.error && <ErrorBox error={shifts.error} />}
      {shifts.data?.length === 0 && <Card><EmptyState title="No simulated shifts for this user">Switch to a demo clinician with scheduled shifts (for example Jordan Lee, RN or Maya Ellison, CRNA).</EmptyState></Card>}
      {shifts.data && shifts.data.length > 0 && (
        <div className="grid grid-cols-1 gap-6 xl:grid-cols-[340px_minmax(0,1fr)]">
          <div className="space-y-3">
            <div className="text-[12px] font-semibold uppercase tracking-wide text-navy-400">Your shifts</div>
            {shifts.data.map((s) => <ShiftCard key={s.shiftId} s={s} selected={s.shiftId === shiftId} onSelect={() => setShiftId(s.shiftId)} />)}
          </div>
          <div className="min-w-0 space-y-5">
            {shift && (
              <Card>
                <div className="flex flex-wrap items-center justify-between gap-3">
                  <div>
                    <div className="text-[16px] font-semibold text-navy-950">{shift.label} · {fmtDateTime(shift.startAt)}</div>
                    <div className="text-[12.5px] text-navy-500">{shift.location}</div>
                  </div>
                  <div className="flex rounded-lg bg-navy-50 p-0.5 text-[12.5px]" role="radiogroup" aria-label="Briefing type">
                    {(['FULL', 'PRIORITY'] as Mode[]).map((m) => (
                      <button key={m} role="radio" aria-checked={mode === m} onClick={() => setMode(m)}
                        className={cx('rounded-md px-3 py-1.5 font-medium', mode === m ? 'bg-white text-navy-900 shadow-sm' : 'text-navy-500 hover:text-navy-800')}>
                        {m === 'FULL' ? 'Full Shift Briefing' : 'Priority Alerts Only'}
                      </button>
                    ))}
                  </div>
                </div>
                <div className="mt-4 flex flex-wrap gap-2">
                  <Button variant="primary" loading={generate.isPending && !generate.variables} disabled={working || generate.isPending}
                    onClick={() => generate.mutate(false)}><Sparkles className="h-4 w-4" />Generate {mode === 'FULL' ? 'full briefing' : 'priority briefing'}</Button>
                  {b && !working && <Button disabled={generate.isPending} loading={generate.isPending && generate.variables === true}
                    onClick={() => generate.mutate(true)}><RefreshCw className="h-4 w-4" />Regenerate</Button>}
                </div>
              </Card>
            )}
            {briefing.error && <ErrorBox error={briefing.error} />}
            {b && <BriefingPanel b={b} />}
          </div>
        </div>
      )}
    </div>
  )
}

function ShiftCard({ s, selected, onSelect }: { s: ShiftView; selected: boolean; onSelect: () => void }) {
  return (
    <button onClick={onSelect} className={cx('w-full rounded-xl border bg-white p-3 text-left transition-shadow hover:shadow-sm',
      selected ? 'border-coral-300 ring-2 ring-coral-100' : 'border-line')}>
      <div className="flex items-center gap-1.5 text-[13.5px] font-semibold text-navy-900"><CalendarDays className="h-3.5 w-3.5 text-navy-400" />{s.label} · {fmtDateTime(s.startAt)}</div>
      <div className="mt-0.5 text-[12px] text-navy-500">{s.department}</div>
      <div className="mt-2 flex flex-wrap gap-1">
        <Badge tone="neutral"><Users className="h-3 w-3" />{s.authorizedPatients} patient{s.authorizedPatients === 1 ? '' : 's'}</Badge>
        {s.openItems > 0 && <Badge tone="amber">{s.openItems} open item{s.openItems === 1 ? '' : 's'}</Badge>}
        {s.excludedPatients > 0 && <Badge tone="outline" title="Assignments outside your access scope are excluded from your briefing">{s.excludedPatients} excluded</Badge>}
      </div>
      <div className="mt-1.5 truncate text-[11.5px] text-navy-400">{s.patientNames.join(', ')}</div>
    </button>
  )
}

const STAGES = [
  { key: 'SCRIPTING', label: 'Gemini prepares the script from your authorized records' },
  { key: 'SYNTHESIZING', label: 'ElevenLabs generates the audio' },
  { key: 'READY', label: 'Ready to play' },
]

function BriefingPanel({ b }: { b: BriefingView }) {
  const [seekTo, setSeekTo] = useState<number | null>(null)
  const stageIndex = b.status === 'QUEUED' ? -1 : b.status === 'SCRIPTING' ? 0 : b.status === 'SYNTHESIZING' ? 1 : 2
  const working = ['QUEUED', 'SCRIPTING', 'SYNTHESIZING'].includes(b.status)
  return (
    <>
      <Card>
        <CardHeader icon={<AudioLines className="h-4 w-4 text-coral-500" />} title={b.title ?? (b.mode === 'FULL' ? 'Full Shift Briefing' : 'Priority Alerts Only')}
          subtitle={working || b.status === 'READY' ? b.statusDetail : undefined} actions={<StatusBadge status={b.status} />} />
        {(working || b.status === 'READY') && (
          <ol className="mb-4 space-y-1.5">
            {STAGES.map((s, i) => {
              const done = i < stageIndex || b.status === 'READY'
              const active = i === stageIndex && working
              return (
                <li key={s.key} className={cx('flex items-center gap-2 text-[13px]', done ? 'text-sage-700' : active ? 'font-medium text-navy-900' : 'text-navy-400')}>
                  {done ? <Check className="h-4 w-4" /> : active ? <Loader2 className="h-4 w-4 animate-spin" /> : <span className="h-4 w-4 rounded-full border border-line" />}
                  {s.label}
                </li>
              )
            })}
          </ol>
        )}
        {b.status === 'FAILED' && <ErrorBox error={b.statusDetail} title="The briefing could not be completed" />}
        {b.status === 'SCRIPT_ONLY' && (
          <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-3 py-2 text-[12.5px] text-amber-800">
            {b.statusDetail} The transcript below was written by Gemini; no audio was synthesized and no substitute voice is used.
          </div>
        )}
        <div className="grid grid-cols-2 gap-3 md:grid-cols-4">
          <KeyValue label="Briefing date">{fmtDateTime(b.shiftStart)}</KeyValue>
          <KeyValue label="Provider">{b.providerName}</KeyValue>
          <KeyValue label="Shift">{b.shiftLabel} · {b.location}</KeyValue>
          <KeyValue label="Patients">{b.patientCount}{b.excludedPatients ? ` (${b.excludedPatients} outside your scope excluded)` : ''}</KeyValue>
          <KeyValue label="Open items">{b.openAlertCount}</KeyValue>
          <KeyValue label="Audio duration">{b.durationSeconds != null ? clock(b.durationSeconds) : b.estimatedSeconds != null ? `~${clock(b.estimatedSeconds)} (estimate)` : '—'}</KeyValue>
          <KeyValue label="Generated">{fmtDateTime(b.completedAt ?? b.createdAt)}</KeyValue>
          <KeyValue label="Models">{[b.geminiModel, b.ttsModel && `ElevenLabs ${b.ttsModel}`].filter(Boolean).join(' · ') || '—'}</KeyValue>
        </div>
        {b.audioAvailable && <div className="mt-4"><AudioPlayer briefingId={b.briefingId} seekTo={seekTo} onSought={() => setSeekTo(null)} /></div>}
      </Card>
      {b.segments.length > 0 && <Transcript b={b} onSeek={b.audioAvailable ? setSeekTo : undefined} />}
      <p className="text-[11.5px] text-navy-400">{b.notice}</p>
    </>
  )
}

function StatusBadge({ status }: { status: BriefingView['status'] }) {
  const map: Record<string, [string, 'sage' | 'amber' | 'critical' | 'neutral' | 'coral']> = {
    QUEUED: ['Queued', 'neutral'], SCRIPTING: ['Writing script', 'coral'], SYNTHESIZING: ['Generating audio', 'coral'],
    READY: ['Ready', 'sage'], SCRIPT_ONLY: ['Transcript only', 'amber'], FAILED: ['Failed', 'critical'],
  }
  const [label, tone] = map[status] ?? [status, 'neutral']
  return <Badge tone={tone}>{label}</Badge>
}

const clock = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, '0')}`

function AudioPlayer({ briefingId, seekTo, onSought }: { briefingId: string; seekTo: number | null; onSought: () => void }) {
  const audio = useRef<HTMLAudioElement>(null)
  const [url, setUrl] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [playing, setPlaying] = useState(false)
  const [time, setTime] = useState(0)
  const [duration, setDuration] = useState(0)
  const [rate, setRate] = useState(1)

  useEffect(() => {
    let revoked: string | null = null
    setUrl(null); setError(null)
    fetch(`/api/briefings/${briefingId}/audio`, { credentials: 'same-origin' })
      .then(async (r) => { if (!r.ok) throw new Error(`Audio unavailable (HTTP ${r.status})`); return r.blob() })
      .then((blob) => { revoked = URL.createObjectURL(blob); setUrl(revoked) })
      .catch((e) => setError((e as Error).message))
    return () => { if (revoked) URL.revokeObjectURL(revoked) }
  }, [briefingId])

  useEffect(() => {
    if (seekTo != null && audio.current) { audio.current.currentTime = seekTo; void audio.current.play(); onSought() }
  }, [seekTo, onSought])
  useEffect(() => { if (audio.current) audio.current.playbackRate = rate }, [rate])

  if (error) return <ErrorBox error={error} />
  if (!url) return <Loading label="Loading audio…" />
  const skip = (d: number) => { if (audio.current) audio.current.currentTime = Math.max(0, Math.min(duration, audio.current.currentTime + d)) }
  return (
    <div className="rounded-xl border border-line bg-canvas p-3">
      <audio ref={audio} src={url} preload="metadata"
        onPlay={() => setPlaying(true)} onPause={() => setPlaying(false)} onEnded={() => setPlaying(false)}
        onTimeUpdate={(e) => setTime(e.currentTarget.currentTime)} onLoadedMetadata={(e) => setDuration(e.currentTarget.duration)} />
      <div className="flex flex-wrap items-center gap-2">
        <button aria-label="Rewind 15 seconds" onClick={() => skip(-15)} className="rounded-full p-2 text-navy-600 hover:bg-white"><RotateCcw className="h-4 w-4" /></button>
        <button aria-label={playing ? 'Pause' : 'Play'} onClick={() => (playing ? audio.current?.pause() : audio.current?.play())}
          className="flex h-10 w-10 items-center justify-center rounded-full bg-coral-600 text-white hover:bg-coral-700">
          {playing ? <Pause className="h-5 w-5" /> : <Play className="ml-0.5 h-5 w-5" />}
        </button>
        <button aria-label="Forward 15 seconds" onClick={() => skip(15)} className="rounded-full p-2 text-navy-600 hover:bg-white"><RotateCw className="h-4 w-4" /></button>
        <span className="w-[92px] text-center text-[12.5px] tabular-nums text-navy-700">{clock(time)} / {clock(duration || 0)}</span>
        <input type="range" aria-label="Seek" min={0} max={duration || 0} step={0.1} value={time}
          onChange={(e) => { if (audio.current) audio.current.currentTime = Number(e.target.value) }}
          className="h-1.5 min-w-[160px] flex-1 cursor-pointer accent-[var(--color-coral-600)]" />
        <label className="flex items-center gap-1 text-[12px] text-navy-600"><Gauge className="h-3.5 w-3.5" />
          <select aria-label="Playback speed" value={rate} onChange={(e) => setRate(Number(e.target.value))} className="h-7 rounded-md border border-line bg-white px-1 text-[12px]">
            {[0.75, 1, 1.25, 1.5, 2].map((r) => <option key={r} value={r}>{r}×</option>)}
          </select>
        </label>
      </div>
    </div>
  )
}

function Transcript({ b, onSeek }: { b: BriefingView; onSeek?: (t: number) => void }) {
  const { openRecord } = useApp()
  const segments = useMemo(() => b.segments, [b.segments])
  return (
    <Card>
      <CardHeader icon={<FileText className="h-4 w-4" />} title="Transcript"
        subtitle={b.audioAvailable ? 'Exactly the narration ElevenLabs spoke, with the source records each segment relies on' : 'The Gemini-written narration, with the source records each segment relies on'} />
      {b.opening && <p className="mb-3 text-[14px] leading-relaxed text-navy-800">{b.opening}</p>}
      <ol className="space-y-4">
        {segments.map((s: BriefingSegment) => (
          <li key={s.index} className="rounded-lg border border-line p-3">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <div className="text-[13.5px] font-semibold text-navy-900">{s.heading}</div>
              {onSeek && s.startSeconds != null && (
                <button onClick={() => onSeek(s.startSeconds!)} className="inline-flex items-center gap-1 text-[12px] font-medium text-coral-700">
                  <Play className="h-3 w-3" />Play from {clock(s.startSeconds)}
                </button>
              )}
            </div>
            <p className="mt-1 text-[14px] leading-relaxed text-navy-800">{s.narration}</p>
            {s.sources.length > 0 && (
              <div className="mt-2 flex flex-wrap gap-1.5">
                {s.sources.map((src) => {
                  const label = `${src.type === 'RECORD' || src.type === 'APPOINTMENT' ? '' : src.type.replace('_', ' ').toLowerCase() + ': '}${src.label ?? src.id}`
                  const cls = 'inline-flex max-w-[280px] items-center gap-1 rounded-md bg-sage-50 px-1.5 py-0.5 text-[11.5px] text-sage-700 ring-1 ring-inset ring-sage-200 hover:bg-sage-100'
                  if (src.link?.startsWith('record:')) return <button key={src.type + src.id} className={cls} title={src.id} onClick={() => openRecord(src.id)}><FileText className="h-3 w-3" /><span className="truncate">{label}</span></button>
                  if (src.link) return <Link key={src.type + src.id} to={src.link} className={cls} title={src.id}><span className="truncate">{label}</span></Link>
                  return <span key={src.type + src.id} className={cls} title={src.id}><span className="truncate">{label}</span></span>
                })}
              </div>
            )}
          </li>
        ))}
      </ol>
      {b.closing && <p className="mt-3 text-[14px] leading-relaxed text-navy-800">{b.closing}</p>}
    </Card>
  )
}
