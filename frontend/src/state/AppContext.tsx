import { CircleCheck, CircleAlert, X } from 'lucide-react'
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { setApiUser } from '../api/client'
import type { DemoUser } from '../api/types'
import { cx } from '../lib/format'

/** SIMULATED users (no authentication in this prototype). Kept in sync with backend DemoUsers. */
export const DEMO_USERS: DemoUser[] = [
  { id: 'maya.ellison', name: 'Maya Ellison, CRNA', role: 'Chief CRNA — Anesthesia OB coordinator', team: 'Anesthesia OB Coordination' },
  { id: 'samuel.okafor', name: 'Dr. Samuel Okafor, MD', role: 'Obstetric anesthesiologist', team: 'Anesthesia OB Coordination' },
  { id: 'priya.raman', name: 'Dr. Priya Raman, MD', role: 'Maternal-fetal medicine physician', team: 'Maternal-Fetal Medicine' },
  { id: 'jordan.lee', name: 'Jordan Lee, RN', role: 'Labor & delivery charge nurse', team: 'Labor & Delivery' },
  { id: 'alex.kim', name: 'Dr. Alex Kim, MD', role: 'Primary care clinical reviewer', team: 'Clinical Review' },
]

interface Toast { id: number; tone: 'success' | 'error' | 'info'; title: string; body?: string }

interface AppState {
  user: DemoUser
  setUserId: (id: string) => void
  openRecord: (recordId: string) => void
  recordId: string | null
  closeRecord: () => void
  toast: (t: Omit<Toast, 'id'>) => void
}

const Ctx = createContext<AppState | null>(null)

function readStoredUser(): string {
  try {
    return localStorage.getItem('camellia.user') ?? DEMO_USERS[0].id
  } catch {
    return DEMO_USERS[0].id
  }
}

export function AppProvider({ children }: { children: ReactNode }) {
  const [userId, setUserIdState] = useState(readStoredUser)
  const [recordId, setRecordId] = useState<string | null>(null)
  const [toasts, setToasts] = useState<Toast[]>([])
  const user = DEMO_USERS.find((u) => u.id === userId) ?? DEMO_USERS[0]
  setApiUser(user.id)

  const setUserId = useCallback((id: string) => {
    setUserIdState(id)
    try { localStorage.setItem('camellia.user', id) } catch { /* storage unavailable */ }
  }, [])

  const toast = useCallback((t: Omit<Toast, 'id'>) => {
    const id = Date.now() + Math.random()
    setToasts((prev) => [...prev, { ...t, id }])
    setTimeout(() => setToasts((prev) => prev.filter((x) => x.id !== id)), t.tone === 'error' ? 8000 : 5000)
  }, [])

  const value = useMemo<AppState>(() => ({
    user, setUserId, toast, recordId, openRecord: setRecordId, closeRecord: () => setRecordId(null),
  }), [user, setUserId, toast, recordId])

  return (
    <Ctx.Provider value={value}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-[60] flex w-[380px] max-w-[calc(100vw-2rem)] flex-col gap-2">
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
