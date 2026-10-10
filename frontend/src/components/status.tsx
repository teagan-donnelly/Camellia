import { CircleCheck, Clock, Hourglass, Siren, CircleDashed, CircleSlash, FileCheck2, Users } from 'lucide-react'
import type { AlertStatus } from '../api/types'
import { ALERT_STATUS_LABEL, FLAG_STATUS_LABEL } from '../lib/format'
import { Badge } from './ui'

export function AlertStatusBadge({ status }: { status: AlertStatus }) {
  const map: Record<AlertStatus, { tone: 'coral' | 'amber' | 'sage' | 'neutral' | 'outline' | 'navy'; icon: React.ReactNode }> = {
    NEW: { tone: 'coral', icon: <Siren className="h-3 w-3" /> },
    UNDER_REVIEW: { tone: 'amber', icon: <Hourglass className="h-3 w-3" /> },
    COORDINATION_NEEDED: { tone: 'amber', icon: <Users className="h-3 w-3" /> },
    PLAN_DOCUMENTED: { tone: 'sage', icon: <FileCheck2 className="h-3 w-3" /> },
    RESOLVED: { tone: 'sage', icon: <CircleCheck className="h-3 w-3" /> },
    DISMISSED: { tone: 'outline', icon: <CircleSlash className="h-3 w-3" /> },
  }
  const m = map[status]
  return <Badge tone={m.tone}>{m.icon}{ALERT_STATUS_LABEL[status]}</Badge>
}

export function FlagStatusBadge({ status }: { status: string }) {
  const tone = status === 'UNREVIEWED' ? 'coral' : status === 'IN_REVIEW' ? 'amber' : status === 'REVIEWED' ? 'sage' : 'outline'
  const icon = status === 'UNREVIEWED' ? <CircleDashed className="h-3 w-3" /> : status === 'IN_REVIEW' ? <Hourglass className="h-3 w-3" />
    : status === 'REVIEWED' ? <CircleCheck className="h-3 w-3" /> : <CircleSlash className="h-3 w-3" />
  return <Badge tone={tone}>{icon}{FLAG_STATUS_LABEL[status] ?? status}</Badge>
}

/** Workflow ordering by time-to-encounter. Explicitly NOT a clinical risk score. */
export function PriorityBadge({ priority }: { priority: string }) {
  const expedited = priority === 'EXPEDITED'
  return (
    <Badge tone={expedited ? 'amber' : 'neutral'} title="Prototype ordering by time to the encounter — not a validated clinical risk score.">
      <Clock className="h-3 w-3" />
      {expedited ? 'Expedited review' : 'Standard review'}
    </Badge>
  )
}

export function RoleBadge({ role }: { role: string }) {
  if (role === 'TRIGGER') return <Badge tone="coral">Triggering finding</Badge>
  if (role === 'UPCOMING_ENCOUNTER') return <Badge tone="navy">Upcoming encounter</Badge>
  return <Badge tone="neutral">Supporting documentation</Badge>
}
