import {
  Bell, ChevronDown, ClipboardList, Command, Database, Flag, LayoutDashboard, Search, ServerCog, ShieldAlert, Sparkles, Users, Workflow,
} from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useAlerts, useIntegrationStatus } from '../api/hooks'
import { cx, initials } from '../lib/format'
import { DEMO_USERS, useApp } from '../state/AppContext'
import { Wordmark } from './Logo'
import { RecordDrawer } from './RecordDrawer'

const NAV = [
  { to: '/', label: 'Dashboard', icon: LayoutDashboard, end: true },
  { to: '/patients', label: 'Patients', icon: Users },
  { to: '/search', label: 'Clinical Search', icon: Search },
  { to: '/flags', label: 'Clinical Flags', icon: Flag },
  { to: '/alerts', label: 'Anesthesia OB Alerts', icon: Bell, badge: true },
  { to: '/coordination', label: 'Care Coordination', icon: Workflow },
]

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
  const alerts = useAlerts()
  const newCount = alerts.data?.filter((a) => a.status === 'NEW').length ?? 0
  return (
    <aside className="hidden w-[248px] shrink-0 flex-col bg-navy-900 text-navy-100 md:flex">
      <div className="px-5 pb-5 pt-5">
        <Wordmark dark />
        <div className="mt-1.5 whitespace-nowrap pl-[42px] text-[10.5px] font-medium uppercase tracking-[0.1em] text-navy-400">Clinical coordination</div>
      </div>
      <nav className="flex-1 space-y-0.5 px-3">
        {NAV.map(({ to, label, icon: Icon, end, badge }) => (
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
                {badge && newCount > 0 && (
                  <span className="rounded-full bg-coral-500 px-1.5 text-[11px] font-semibold text-white">{newCount}</span>
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
          <ServerCog className="h-4 w-4 text-navy-400" /> System & integrations
        </NavLink>
      </div>
      <div className="mx-3 mb-4 rounded-lg border border-white/10 bg-white/5 p-3 text-[11.5px] leading-relaxed text-navy-300">
        Flags and alerts are prototype screening aids. All clinical decisions remain with qualified clinicians.
      </div>
    </aside>
  )
}

function TopBar() {
  return (
    <header className="flex h-[60px] shrink-0 items-center gap-3 border-b border-line bg-white/80 px-4 backdrop-blur sm:px-6">
      <CommandSearch />
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
  return (
    <button onClick={() => navigate('/system')} className="hidden items-center gap-1.5 lg:flex" title="Integration status">
      <span className={cx('inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] font-medium ring-1 ring-inset',
        isError || (db && !db.connected) ? 'bg-critical-50 text-critical-700 ring-critical-100'
          : db?.isSnowflake ? 'bg-sage-50 text-sage-700 ring-sage-200' : 'bg-amber-50 text-amber-700 ring-amber-200')}>
        <Database className="h-3.5 w-3.5" />
        {isError ? 'Backend offline' : !db ? 'Database…' : db.isSnowflake ? 'Snowflake' : 'Local fallback DB'}
      </span>
      <span className={cx('inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] font-medium ring-1 ring-inset',
        gem?.configured ? 'bg-coral-50 text-coral-700 ring-coral-200' : 'bg-amber-50 text-amber-700 ring-amber-200')}>
        <Sparkles className="h-3.5 w-3.5" />
        {!gem ? 'Gemini…' : gem.configured ? 'Gemini live' : 'Gemini not configured'}
      </span>
    </button>
  )
}

function UserSwitcher() {
  const { user, setUserId } = useApp()
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const close = (e: MouseEvent) => { if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false) }
    window.addEventListener('mousedown', close)
    return () => window.removeEventListener('mousedown', close)
  }, [])
  return (
    <div ref={ref} className="relative">
      <button onClick={() => setOpen((o) => !o)} className="flex items-center gap-2 rounded-lg px-2 py-1 hover:bg-navy-50">
        <span className="flex h-8 w-8 items-center justify-center rounded-full bg-navy-800 text-[12px] font-semibold text-white">{initials(user.name)}</span>
        <span className="hidden text-left sm:block">
          <span className="block text-[13px] font-medium leading-tight text-navy-900">{user.name}</span>
          <span className="block text-[11.5px] leading-tight text-navy-500">{user.team}</span>
        </span>
        <ChevronDown className="h-4 w-4 text-navy-400" />
      </button>
      {open && (
        <div className="absolute right-0 top-11 z-40 w-80 rounded-xl border border-line bg-white p-1.5 shadow-xl animate-fade-up">
          <div className="px-2.5 py-2 text-[11.5px] text-navy-500">
            <span className="font-semibold text-amber-700">Simulated access.</span> No authentication — switch the demo user to see team-based routing.
          </div>
          {DEMO_USERS.map((u) => (
            <button
              key={u.id}
              onClick={() => { setUserId(u.id); setOpen(false) }}
              className={cx('flex w-full items-center gap-2.5 rounded-lg px-2.5 py-2 text-left hover:bg-navy-50', u.id === user.id && 'bg-coral-50')}
            >
              <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-full bg-navy-100 text-[11px] font-semibold text-navy-700">{initials(u.name)}</span>
              <span className="min-w-0">
                <span className="block truncate text-[13px] font-medium text-navy-900">{u.name}</span>
                <span className="block truncate text-[11.5px] text-navy-500">{u.role} · {u.team}</span>
              </span>
            </button>
          ))}
          <div className="mt-1 border-t border-line px-2.5 pb-1 pt-2 text-[11px] text-navy-400">
            <ClipboardList className="mr-1 inline h-3 w-3" />Only members of an alert's assigned team can change its workflow.
          </div>
        </div>
      )}
    </div>
  )
}
