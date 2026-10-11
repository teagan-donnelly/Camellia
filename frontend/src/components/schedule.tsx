import { ArrowRight, CalendarDays, CalendarPlus, Clock, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { useShiftSchedule } from '../api/hooks'
import type { ScheduleShift } from '../api/types'
import { cx, fmtTime, fmtWeekday } from '../lib/format'
import { Badge, Card, CardHeader } from './ui'

const TZ_SHORT: Record<string, string> = { 'America/New_York': 'ET', 'America/Chicago': 'CT', 'America/Denver': 'MT', 'America/Los_Angeles': 'PT' }

/**
 * The signed-in user's simulated shift schedule: current and next shift, later shifts this week, and open shifts in
 * their own units with staffing coverage. Renders nothing for users without scheduled shifts.
 */
export function ShiftSchedulePanel() {
  const { data, isLoading } = useShiftSchedule()
  if (isLoading || !data || (data.myShifts.length === 0 && data.openShifts.length === 0)) return null
  const tz = TZ_SHORT[data.timeZone] ?? data.timeZone
  const highlighted = new Set([data.current?.scheduleId, data.next?.scheduleId])
  const later = data.myShifts.filter((s) => !highlighted.has(s.scheduleId) && s.timing === 'UPCOMING')
  const unit = data.myShifts[0]?.unit

  return (
    <div className="grid grid-cols-1 gap-6 xl:grid-cols-[1.6fr_1fr]">
      <Card>
        <CardHeader icon={<CalendarDays className="h-4 w-4" />} title="My shifts"
          subtitle={<>{unit ? `${unit} · ` : ''}Times in hospital time ({tz}) · simulated schedule</>} />
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
          <HighlightTile kind="Current shift" shift={data.current} empty="No shift in progress right now" />
          <HighlightTile kind="Next shift" shift={data.next} empty="No upcoming shift scheduled" />
        </div>
        {later.length > 0 && (
          <div className="mt-5">
            <h3 className="mb-2 text-[12px] font-semibold uppercase tracking-wide text-navy-500">Later this week</h3>
            <ul className="divide-y divide-line rounded-xl border border-line">
              {later.map((s) => <ShiftRow key={s.scheduleId} shift={s} />)}
            </ul>
          </div>
        )}
      </Card>

      <Card>
        <CardHeader icon={<CalendarPlus className="h-4 w-4" />} title="Open shifts in your units" subtitle="Staffing gaps posted for the coming week" />
        {data.openShifts.length === 0 ? (
          <p className="rounded-lg bg-canvas px-3 py-3 text-[13px] text-navy-500">No open shifts posted for your units.</p>
        ) : (
          <ul className="space-y-2">
            {data.openShifts.map((s) => (
              <li key={s.scheduleId} className="rounded-lg border border-line px-3 py-2.5">
                <div className="flex flex-wrap items-center justify-between gap-2">
                  <span className="text-[13.5px] font-medium text-navy-900">{fmtWeekday(s.startAt)} · {s.label}</span>
                  <Badge tone="amber">Open</Badge>
                </div>
                <div className="mt-0.5 text-[12.5px] text-navy-600">{fmtTime(s.startAt)} – {fmtTime(s.endAt)} · {s.unit}</div>
                <div className="mt-1.5 flex flex-wrap items-center gap-2">
                  <CoverageBadge shift={s} />
                  {s.note && <span className="text-[12px] text-navy-500">{s.note}</span>}
                </div>
              </li>
            ))}
          </ul>
        )}
        <p className="mt-3 text-[11.5px] text-navy-400">Synthetic schedule for the demo. Requesting or trading shifts is not part of this prototype.</p>
      </Card>
    </div>
  )
}

function HighlightTile({ kind, shift, empty }: { kind: string; shift?: ScheduleShift; empty: string }) {
  if (!shift) {
    return (
      <div className="flex min-h-[132px] flex-col justify-center rounded-xl border border-dashed border-line bg-canvas/60 p-4">
        <div className="text-[11.5px] font-semibold uppercase tracking-wide text-navy-400">{kind}</div>
        <div className="mt-1 text-[13px] text-navy-500">{empty}</div>
      </div>
    )
  }
  const live = shift.timing === 'IN_PROGRESS'
  return (
    <div className={cx('rounded-xl border p-4', live ? 'border-coral-200 bg-coral-50/40' : 'border-line bg-white')}>
      <div className="flex items-center justify-between gap-2">
        <span className="text-[11.5px] font-semibold uppercase tracking-wide text-navy-500">{kind}</span>
        {live ? <Badge tone="coral"><span className="h-1.5 w-1.5 rounded-full bg-coral-500" />In progress</Badge> : <Badge tone="outline">Upcoming</Badge>}
      </div>
      <div className="mt-2 text-[15px] font-semibold text-navy-950">{fmtWeekday(shift.startAt)} · {shift.label}</div>
      <div className="mt-0.5 flex items-center gap-1.5 text-[13px] text-navy-600"><Clock className="h-3.5 w-3.5 text-navy-400" />{fmtTime(shift.startAt)} – {fmtTime(shift.endAt)}</div>
      <div className="mt-0.5 text-[12.5px] text-navy-500">{shift.orgName} · {shift.unit}</div>
      <div className="mt-2.5 flex flex-wrap items-center justify-between gap-2">
        <CoverageBadge shift={shift} />
        {shift.briefingAvailable && (
          <Link to="/briefing" className="inline-flex items-center gap-1 text-[12.5px] font-medium text-coral-700 hover:text-coral-800">
            Shift briefing <ArrowRight className="h-3.5 w-3.5" />
          </Link>
        )}
      </div>
      {shift.note && <div className="mt-2 text-[12px] text-navy-500">{shift.note}</div>}
    </div>
  )
}

function ShiftRow({ shift }: { shift: ScheduleShift }) {
  return (
    <li className="flex flex-col gap-1.5 px-3 py-2.5 sm:flex-row sm:items-center sm:gap-4">
      <div className="w-full shrink-0 text-[13px] font-medium text-navy-900 sm:w-32">{fmtWeekday(shift.startAt)}</div>
      <div className="min-w-0 flex-1 text-[13px] text-navy-600">
        {shift.label} · {fmtTime(shift.startAt)} – {fmtTime(shift.endAt)}
        {shift.note && <div className="text-[12px] text-navy-500">{shift.note}</div>}
      </div>
      <div className="shrink-0"><CoverageBadge shift={shift} /></div>
    </li>
  )
}

/** Staffing coverage with a text label (never color alone). */
function CoverageBadge({ shift }: { shift: ScheduleShift }) {
  if (!shift.coverage || shift.staffed == null || shift.target == null) return null
  const count = `${shift.staffed}/${shift.target} staffed`
  if (shift.coverage === 'FULL') return <Badge tone="sage"><Users className="h-3 w-3" />Fully staffed · {count}</Badge>
  if (shift.coverage === 'SHORT') return <Badge tone="amber"><Users className="h-3 w-3" />Short 1 · {count}</Badge>
  return <Badge tone="critical"><Users className="h-3 w-3" />Short {shift.target - shift.staffed} · {count}</Badge>
}
