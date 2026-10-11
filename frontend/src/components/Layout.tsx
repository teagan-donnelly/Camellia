import {
  Activity, AudioLines, Bell, Check, ChevronDown, Command, Database, Flag, LayoutDashboard, Lock, Search, ServerCog, ShieldAlert,
  Sparkles, Users,
} from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useAlerts, useIntegrationStatus, useNursingQueue, useSessionUsers } from '../api/hooks'
import type { NavItem } from '../api/types'
import { cx, initials } from '../lib/format'
import { useApp } from '../state/AppContext'
import { Wordmark } from './Logo'
import { AnchoredPopover } from './Popover'
import { RecordDrawer } from './RecordDrawer'

/** Every possible sidebar item. Which ones appear comes from the backend session's `navigation`. */
const NAV: Record<NavItem, { to: string; label: string; icon: typeof Bell; end?: boolean; badge?: 'ob' | 'monitoring' }> = {
  DASHBOARD: { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  BRIEFING: { to: '/briefing', label: 'Camellia Briefing', icon: AudioLines },
  PATIENTS: { to: '/patients', label: 'Patients', icon: Users },
  CLINICAL_SEARCH: { to: '/search', label: 'Clinical Search', icon: Search },
  CLINICAL_ALERTS: { to: '/flags', label: 'Clinical Alerts', icon: Flag },
  ANESTHESIA_OB_ALERTS: { to: '/alerts', label: 'Anesthesia OB Alerts', icon: Bell, badge: 'ob' },
  NURSING_MONITORING: { to: '/monitoring', label: 'Nursing Monitoring', icon: Activity, badge: 'monitoring' },
}

export function Layout({ children }: { children: ReactNode }) {
  return (
    <div className="flex h-full flex-col">
      <SyntheticBanner />
      <div className="flex min-h-0 flex-1">
        <Sidebar />
        <div className="flex min-w-0 flex-1 flex-col">
          <TopBar />
          <main className="scroll-thin min-h-0 flex-1 overflow-y-auto">
            <div className="mx-auto w-full max-w-[1480px] px-4 py-6 sm:px-6 lg:px-8">{children}</div>
          </main>
        </div>
      </div>
      <RecordDrawer />
    </div>
  )
}

function SyntheticBanner() {
  return (
    <div className="flex items-center justify-center gap-2 bg-navy-950 px-4 py-1.5 text-center text-[12px] font-medium text-navy-100">
      <ShieldAlert className="h-3.5 w-3.5 shrink-0 text-coral-300" />
      Demo application using synthetic patient information. Not for clinical use.
    </div>
  )
}

function Sidebar() {
  const { nav, can } = useApp()
  // Only roles with the Anesthesia OB workflow ever request alert data (the backend would refuse anyway).
  const alerts = useAlerts(can('ANESTHESIA_OB_ALERTS'))
  const queue = useNursingQueue(can('NURSING_MONITORING'))
  const counts = {
    ob: alerts.data?.filter((a) => a.status === 'NEW').length ?? 0,
    monitoring: queue.data?.alerts.filter((a) => a.alert.status === 'OPEN').length ?? 0,
  }
  return (
    <aside className="hidden w-[248px] shrink-0 flex-col bg-navy-900 text-navy-100 md:flex">
      <div className="px-5 pb-5 pt-5">
        <Wordmark dark />
        <div className="mt-1.5 whitespace-nowrap pl-[42px] text-[10.5px] font-medium uppercase tracking-[0.1em] text-navy-400">Clinical coordination</div>
      </div>
      <nav className="flex-1 space-y-0.5 px-3">
        {nav.filter((n) => n in NAV).map((n) => NAV[n]).map(({ to, label, icon: Icon, end, badge }) => (
          <NavLink
            key={to}
            to={to}
            end={end}
            className={({ isActive }) => cx(
              'group relative flex items-center gap-3 rounded-lg px-3 py-2 text-[13.5px] font-medium transition-colors',
              isActive ? 'bg-white/10 text-white' : 'text-navy-200 hover:bg-white/5 hover:text-white',
            )}
          >
            {({ isActive }) => (
              <>
                {isActive && <span className="absolute left-0 top-1/2 h-5 w-[3px] -translate-y-1/2 rounded-r bg-coral-400" />}
                <Icon className={cx('h-4 w-4', isActive ? 'text-coral-300' : 'text-navy-400 group-hover:text-navy-200')} />
                <span className="flex-1">{label}</span>
                {badge && counts[badge] > 0 && (
                  <span className="rounded-full bg-coral-500 px-1.5 text-[11px] font-semibold text-white">{counts[badge]}</span>
                )}
              </>
            )}
          </NavLink>
        ))}
      </nav>
      <div className="space-y-0.5 px-3 pb-3">
        <NavLink
          to="/system"
          className={({ isActive }) => cx('flex items-center gap-3 rounded-lg px-3 py-2 text-[13px] font-medium',
            isActive ? 'bg-white/10 text-white' : 'text-navy-300 hover:bg-white/5 hover:text-white')}
        >
          <ServerCog className="h-4 w-4 text-navy-400" /> Demo & integrations
        </NavLink>
      </div>
      <div className="mx-3 mb-4 rounded-lg border border-white/10 bg-white/5 p-3 text-[11.5px] leading-relaxed text-navy-300">
        Flags and alerts are prototype screening aids. All clinical decisions remain with qualified clinicians.
      </div>
    </aside>
  )
}

function TopBar() {
  const { can } = useApp()
  return (
    <header className="relative z-[var(--layer-header)] flex h-[60px] shrink-0 items-center gap-3 border-b border-line bg-white/80 px-4 backdrop-blur sm:px-6">
      {can('CLINICAL_SEARCH') ? <CommandSearch /> : <div className="flex-1" />}
      <div className="ml-auto flex items-center gap-2">
        <IntegrationPills />
        <UserSwitcher />
      </div>
    </header>
  )
}

function CommandSearch() {
  const ref = useRef<HTMLInputElement>(null)
  const navigate = useNavigate()
  const location = useLocation()
  const [q, setQ] = useState('')

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault()
        // On the search page, focus its own prominent bar instead.
        const pageBar = document.getElementById('clinical-search-input') as HTMLInputElement | null
        ;(pageBar ?? ref.current)?.focus()
        ;(pageBar ?? ref.current)?.select()
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [])

  const isMac = typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
  if (location.pathname === '/search') return <div className="flex-1" />
  return (
    <form
      className="relative max-w-xl flex-1"
      onSubmit={(e) => {
        e.preventDefault()
        if (!q.trim()) return
        navigate(`/search?q=${encodeURIComponent(q.trim())}&mode=ai`)
        setQ('')
        ref.current?.blur()
      }}
    >
      <Sparkles className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-coral-400" />
      <input
        ref={ref}
        value={q}
        onChange={(e) => setQ(e.target.value)}
        placeholder="Ask about patients or records — e.g. “pregnant patients anesthesia should know about”"
        className="h-9 w-full rounded-lg border border-line bg-canvas pl-9 pr-16 text-sm text-navy-900 placeholder:text-navy-400 focus:border-coral-300 focus:bg-white focus:outline-none focus:ring-2 focus:ring-coral-100"
      />
      <kbd className="pointer-events-none absolute right-2.5 top-1/2 flex -translate-y-1/2 items-center gap-0.5 rounded border border-line bg-white px-1.5 py-0.5 text-[11px] text-navy-400">
        {isMac ? <Command className="h-3 w-3" /> : 'Ctrl'} K
      </kbd>
    </form>
  )
}

function IntegrationPills() {
  const { data, isError } = useIntegrationStatus()
  const navigate = useNavigate()
  const db = data?.database
  const gem = data?.gemini
  // lastError is cleared by the next successful call, so it marks a key or service that is failing right now.
  const gemFailing = !!gem?.configured && !!gem.lastError
  return (
    <button onClick={() => navigate('/system')} className="hidden items-center gap-1.5 lg:flex" title={gemFailing ? `Integration status. Gemini: ${gem.lastError}` : 'Integration status'}>
      <span className={cx('inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] font-medium ring-1 ring-inset',
        isError || (db && !db.connected) ? 'bg-critical-50 text-critical-700 ring-critical-100'
          : db?.isSnowflake ? 'bg-sage-50 text-sage-700 ring-sage-200' : 'bg-amber-50 text-amber-700 ring-amber-200')}>
        <Database className="h-3.5 w-3.5" />
        {isError ? 'Backend offline' : !db ? 'Database…'
          : db.state === 'SNOWFLAKE_UNAVAILABLE' ? 'Snowflake unavailable'
            : db.isSnowflake ? 'Snowflake'
              : db.state === 'LOCAL_FALLBACK_SELECTED' ? 'Local DB (selected)' : 'Local DB — Snowflake not configured'}
      </span>
      <span className={cx('inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] font-medium ring-1 ring-inset',
        gemFailing ? 'bg-critical-50 text-critical-700 ring-critical-100'
          : gem?.configured ? 'bg-coral-50 text-coral-700 ring-coral-200' : 'bg-amber-50 text-amber-700 ring-amber-200')}>
        <Sparkles className="h-3.5 w-3.5" />
        {!gem ? 'Gemini…' : gemFailing ? 'Gemini error' : gem.configured ? 'Gemini live' : 'Gemini not configured'}
      </span>
    </button>
  )
}

function UserSwitcher() {
  const { user, switchUser, switching, session } = useApp()
  const users = useSessionUsers()
  const [open, setOpen] = useState(false)
  const triggerRef = useRef<HTMLButtonElement>(null)
  const close = useCallback(() => setOpen(false), [])
  const scopeLabel = user.practices.length ? user.practices.map((p) => p.name).join(', ') : user.department
  return (
    <div className="relative">
      <button ref={triggerRef} onClick={() => setOpen((o) => !o)} className="flex items-center gap-2 rounded-lg px-2 py-1 hover:bg-navy-50" aria-haspopup="menu" aria-expanded={open}>
        <span className="flex h-8 w-8 items-center justify-center rounded-full bg-navy-800 text-[12px] font-semibold text-white">{initials(user.name)}</span>
        <span className="hidden text-left sm:block">
          <span className="flex items-center gap-1.5 text-[13px] font-medium leading-tight text-navy-900">
            {user.name}
            <span className="rounded bg-amber-50 px-1 text-[10px] font-semibold uppercase tracking-wide text-amber-700 ring-1 ring-inset ring-amber-200">Demo</span>
          </span>
          <span className="block max-w-[260px] truncate text-[11.5px] leading-tight text-navy-500">{user.roleLabel} · {scopeLabel}</span>
        </span>
        <ChevronDown className="h-4 w-4 text-navy-400" />
      </button>
      <AnchoredPopover anchorRef={triggerRef} open={open} onClose={close} width={360} label="Switch demo user">
          <div className="flex items-start gap-2 rounded-lg bg-amber-50 px-2.5 py-2 text-[11.5px] leading-snug text-amber-800">
            <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0" />
            <span><span className="font-semibold">Simulated demo identity — not a secure login.</span> Switch users to see how access changes by role and practice.</span>
          </div>
          {users.isLoading && <div className="px-2.5 py-3 text-[12.5px] text-navy-400">Loading demo users…</div>}
          {users.data?.map((u) => {
            const active = u.id === user.id
            return (
              <button
                key={u.id}
                role="menuitemradio"
                aria-checked={active}
                disabled={switching}
                onClick={() => { setOpen(false); if (!active) void switchUser(u.id) }}
                className={cx('mt-0.5 flex w-full items-start gap-2.5 rounded-lg px-2.5 py-2 text-left hover:bg-navy-50 disabled:opacity-60', active && 'bg-coral-50 hover:bg-coral-50')}
              >
                <span className="mt-0.5 flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-navy-100 text-[11px] font-semibold text-navy-700">{initials(u.name)}</span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-[13px] font-medium text-navy-900">{u.name}</span>
                  <span className="block truncate text-[11.5px] text-navy-600">{u.roleLabel} · {u.department}</span>
                  <span className="block truncate text-[11px] text-navy-400">{u.practices.length ? u.practices.map((p) => p.name).join(', ') : 'All synthetic patients'}</span>
                </span>
                {active && <Check className="mt-1 h-4 w-4 shrink-0 text-coral-600" />}
              </button>
            )
          })}
          <div className="mt-1 border-t border-line px-2.5 pb-1 pt-2 text-[11px] leading-snug text-navy-400">{session.notice}</div>
      </AnchoredPopover>
    </div>
  )
}
