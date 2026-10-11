import { useQueryClient } from '@tanstack/react-query'
import { CircleCheck, CircleAlert, X } from 'lucide-react'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useLocation, useNavigate } from 'react-router-dom'
import { rememberUserId } from '../api/client'
import { keys, selectSession, useSession } from '../api/hooks'
import type { NavItem, Permission, Session, SessionUser } from '../api/types'
import { CamelliaMark } from '../components/Logo'
import { ErrorBox } from '../components/ui'
import { cx } from '../lib/format'

interface Toast { id: number; tone: 'success' | 'error' | 'info'; title: string; body?: string }

interface AppState {
  /** The active SIMULATED demo identity, as issued by the backend session (never asserted by the client). */
  session: Session
  user: SessionUser
  can: (p: Permission) => boolean
  nav: NavItem[]
  switchUser: (id: string) => Promise<void>
  switching: boolean
  openRecord: (recordId: string) => void
  recordId: string | null
  closeRecord: () => void
  toast: (t: Omit<Toast, 'id'>) => void
}

const Ctx = createContext<AppState | null>(null)

/** Routes that need a permission beyond the baseline (the backend enforces the same rule). */
function routePermission(pathname: string): Permission | null {
  if (pathname.startsWith('/alerts') || pathname.startsWith('/coordination')) return 'ANESTHESIA_OB_ALERTS'
  return null
}

export function AppProvider({ children }: { children: ReactNode }) {
  const sessionQuery = useSession()
  const qc = useQueryClient()
  const navigate = useNavigate()
  const location = useLocation()
  const [recordId, setRecordId] = useState<string | null>(null)
  const [toasts, setToasts] = useState<Toast[]>([])
  const [switching, setSwitching] = useState(false)
  const session = sessionQuery.data

  const toast = useCallback((t: Omit<Toast, 'id'>) => {
    const id = Date.now() + Math.random()
    setToasts((prev) => [...prev, { ...t, id }])
    setTimeout(() => setToasts((prev) => prev.filter((x) => x.id !== id)), t.tone === 'error' ? 8000 : 5000)
  }, [])

  const switchUser = useCallback(async (id: string) => {
    setSwitching(true)
    try {
      const next = await selectSession(id)
      rememberUserId(id)
      setRecordId(null)
      // Nothing cached for the previous identity may be shown to the next one.
      qc.clear()
      qc.setQueryData(keys.session, next)
      const needed = routePermission(location.pathname)
      if (needed && !next.permissions.includes(needed)) navigate('/')
      toast({ tone: 'info', title: `Now viewing as ${next.user.name}`, body: `${next.user.roleLabel} · ${next.user.patientScope}` })
    } catch (e) {
      toast({ tone: 'error', title: 'Could not switch demo user', body: (e as Error).message })
    } finally {
      setSwitching(false)
    }
  }, [qc, location.pathname, navigate, toast])

  const value = useMemo<AppState | null>(() => session ? ({
    session,
    user: session.user,
    can: (p: Permission) => session.permissions.includes(p),
    nav: session.navigation,
    switchUser,
    switching,
    toast,
    recordId,
    openRecord: setRecordId,
    closeRecord: () => setRecordId(null),
  }) : null, [session, switchUser, switching, toast, recordId])

  if (!value) {
    return (
      <div className="flex h-full items-center justify-center bg-canvas p-6">
        {sessionQuery.error ? (
          <div className="w-full max-w-md space-y-3">
            <ErrorBox error={sessionQuery.error} title="Could not start a demo session" />
            <button onClick={() => sessionQuery.refetch()} className="text-[13px] font-medium text-coral-700 hover:text-coral-800">Try again</button>
          </div>
        ) : (
          <div className="flex items-center gap-3 text-sm text-navy-500"><CamelliaMark className="h-6 w-6 animate-petal" />Starting demo session…</div>
        )}
      </div>
    )
  }

  return (
    <Ctx.Provider value={value}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[var(--layer-toast)] flex w-[380px] max-w-[calc(100vw-2rem)] flex-col gap-2">
        {toasts.map((t) => (
          <div key={t.id} className={cx('pointer-events-auto animate-fade-up flex items-start gap-3 rounded-xl border bg-white px-4 py-3 shadow-lg',
            t.tone === 'error' ? 'border-critical-100' : 'border-line')}>
            {t.tone === 'error' ? <CircleAlert className="mt-0.5 h-4 w-4 shrink-0 text-critical-600" /> : <CircleCheck className="mt-0.5 h-4 w-4 shrink-0 text-sage-600" />}
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium text-navy-900">{t.title}</div>
              {t.body && <div className="mt-0.5 text-[13px] text-navy-500">{t.body}</div>}
            </div>
            <button onClick={() => setToasts((p) => p.filter((x) => x.id !== t.id))} className="text-navy-300 hover:text-navy-600" aria-label="Dismiss">
              <X className="h-4 w-4" />
            </button>
          </div>
        ))}
      </div>
    </Ctx.Provider>
  )
}

export function useApp() {
  const c = useContext(Ctx)
  if (!c) throw new Error('useApp outside AppProvider')
  return c
}

/** Escape closes overlays. */
export function useEscape(handler: () => void, active = true) {
  useEffect(() => {
    if (!active) return
    const fn = (e: KeyboardEvent) => { if (e.key === 'Escape') handler() }
    window.addEventListener('keydown', fn)
    return () => window.removeEventListener('keydown', fn)
  }, [handler, active])
}
