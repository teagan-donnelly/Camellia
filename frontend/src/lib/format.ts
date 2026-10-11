import type { AlertStatus, RecordType } from '../api/types'

const dateFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC' })
const dateTimeFmt = new Intl.DateTimeFormat('en-US', {
  month: 'short', day: 'numeric', year: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'UTC',
})

/** Backend timestamps are UTC wall-clock values without an offset. */
function parse(value?: string | null): Date | null {
  if (!value) return null
  const v = value.length === 10 ? value + 'T00:00:00Z' : value.endsWith('Z') ? value : value + 'Z'
  const d = new Date(v)
  return Number.isNaN(d.getTime()) ? null : d
}

export const fmtDate = (v?: string | null) => (parse(v) ? dateFmt.format(parse(v)!) : '—')
export const fmtDateTime = (v?: string | null) => (parse(v) ? dateTimeFmt.format(parse(v)!) : '—')

export function relative(v?: string | null): string {
  const d = parse(v)
  if (!d) return '—'
  const diff = (Date.now() - d.getTime()) / 1000
  const abs = Math.abs(diff)
  const fmt = (n: number, unit: string) => `${Math.round(n)} ${unit}${Math.round(n) === 1 ? '' : 's'}`
  let s: string
  if (abs < 60) s = 'just now'
  else if (abs < 3600) s = fmt(abs / 60, 'min')
  else if (abs < 86400) s = fmt(abs / 3600, 'hour')
  else s = fmt(abs / 86400, 'day')
  if (s === 'just now') return s
  return diff >= 0 ? `${s} ago` : `in ${s}`
}

export function daysLabel(days: number): string {
  if (days === 0) return 'today'
  if (days === 1) return 'tomorrow'
  if (days < 0) return `${-days} days ago`
  return `in ${days} days`
}

export const ALERT_STATUS_LABEL: Record<AlertStatus, string> = {
  NEW: 'New',
  UNDER_REVIEW: 'Under Review',
  COORDINATION_NEEDED: 'Coordination Needed',
  PLAN_DOCUMENTED: 'Plan Documented',
  RESOLVED: 'Resolved',
  DISMISSED: 'Dismissed',
}

export const FLAG_STATUS_LABEL: Record<string, string> = {
  UNREVIEWED: 'Unreviewed',
  IN_REVIEW: 'In Review',
  REVIEWED: 'Reviewed',
  DISMISSED: 'Dismissed',
}

export const CONTACT_LABEL: Record<string, string> = {
  NOT_STARTED: 'Not started',
  PLANNED: 'Planned',
  COMPLETED: 'Completed',
}

export const NOTE_TYPE_LABEL: Record<string, string> = {
  GENERAL: 'General note',
  INFO_REQUEST: 'Further information needed',
  CONTACT: 'Provider contact',
  PLANNING_DISCUSSION: 'Planning discussion',
  PLAN_SUMMARY: 'Plan summary',
  STATUS: 'Workflow update',
  SYSTEM: 'System',
}

export const RECORD_TYPE_LABEL: Record<RecordType, string> = {
  NOTE: 'Clinical note',
  LAB_RESULT: 'Lab result',
  CONDITION: 'Condition',
  MEDICATION: 'Medication',
  ENCOUNTER: 'Encounter',
  APPOINTMENT: 'Appointment',
  IMAGING: 'Imaging',
}

export const RULE_LABEL: Record<string, string> = {
  OB_ANESTHESIA_REVIEW: 'Anesthesia OB review',
  RECURRENT_FRACTURE: 'Recurrent fractures',
  REPEATED_ABNORMAL_LAB: 'Repeated abnormal labs',
  REPEATED_SYMPTOM: 'Repeated symptom',
}

export const FORMAT_LABEL: Record<string, string> = {
  FHIR_R4_JSON: 'FHIR R4-like JSON',
  LEGACY_JSON: 'Legacy practice export',
  HL7V2_ORU: 'HL7 v2 ORU feed',
  PLAINTEXT_NOTE: 'Plain-text dictation',
}

export const titleCase = (s: string) =>
  s.toLowerCase().replace(/_/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase())

export const initials = (name: string) =>
  name.replace(/^Dr\.\s*/, '').split(/\s+/).filter(Boolean).slice(0, 2).map((p) => p[0]).join('').toUpperCase()

export const cx = (...c: (string | false | null | undefined)[]) => c.filter(Boolean).join(' ')

// ---------------------------------------------------------------- hospital-local time (simulated monitoring)

const hospitalFmts = new Map<string, Intl.DateTimeFormat>()
function hospitalFmt(tz: string, opts: Intl.DateTimeFormatOptions) {
  const key = tz + JSON.stringify(opts)
  if (!hospitalFmts.has(key)) hospitalFmts.set(key, new Intl.DateTimeFormat('en-US', { ...opts, timeZone: tz }))
  return hospitalFmts.get(key)!
}

/** Monitoring timestamps are stored in UTC and shown in the hospital's local time zone. */
export function fmtHospital(v: string | null | undefined, tz = 'America/New_York', withDate = true): string {
  const d = parse(v)
  if (!d) return '—'
  return hospitalFmt(tz, withDate
    ? { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short' }
    : { hour: 'numeric', minute: '2-digit', timeZoneName: 'short' }).format(d)
}

/** "2 h 10 min before simulated now" — monitoring uses the simulation clock, not real time. */
export function sinceSim(v: string | null | undefined, simNow: string | null | undefined): string {
  const a = parse(v), b = parse(simNow)
  if (!a || !b) return '—'
  const mins = Math.round((b.getTime() - a.getTime()) / 60000)
  if (mins < 1) return 'just now (simulated)'
  const h = Math.floor(mins / 60), m = mins % 60
  return `${h ? `${h} h ` : ''}${m} min ago (simulated)`
}

export const MONITORING_LABEL: Record<string, string> = {
  MOBILITY_REVIEW: 'Mobility review', REPOSITIONING_REVIEW: 'Repositioning review', DEVICE_CONNECTIVITY: 'Device connectivity',
  WALKING: 'Walking detected', MOVEMENT: 'Movement detected', POSSIBLE_POSITION_CHANGE: 'Possible position change',
  INACTIVITY_REVIEW: 'Inactivity review triggered', DEVICE_DISCONNECTED: 'Device disconnected', DEVICE_RECONNECTED: 'Device reconnected',
  REPOSITIONING_DOCUMENTED: 'Nursing repositioning documented', SESSION_STARTED: 'Monitoring session started', SESSION_STOPPED: 'Monitoring session stopped',
}
