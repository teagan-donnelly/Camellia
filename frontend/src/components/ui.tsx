import { AlertCircle, Inbox, Lock } from 'lucide-react'
import type { ButtonHTMLAttributes, ReactNode } from 'react'
import { ApiError } from '../api/client'
import { cx } from '../lib/format'
import { CamelliaMark } from './Logo'

export function Card({ children, className, padded = true }: { children: ReactNode; className?: string; padded?: boolean }) {
  return (
    <section className={cx('rounded-xl border border-line bg-white shadow-[0_1px_2px_rgba(20,33,54,0.04)]', padded && 'p-5', className)}>
      {children}
    </section>
  )
}

export function CardHeader({ title, subtitle, icon, actions }: { title: ReactNode; subtitle?: ReactNode; icon?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-4 flex items-start justify-between gap-3">
      <div className="flex min-w-0 items-start gap-2.5">
        {icon && <div className="mt-0.5 text-navy-400">{icon}</div>}
        <div className="min-w-0">
          <h2 className="text-[15px] font-semibold text-navy-900">{title}</h2>
          {subtitle && <p className="mt-0.5 text-[13px] text-navy-500">{subtitle}</p>}
        </div>
      </div>
      {actions && <div className="flex shrink-0 items-center gap-2">{actions}</div>}
    </div>
  )
}

type Tone = 'neutral' | 'navy' | 'coral' | 'sage' | 'amber' | 'critical' | 'outline'

const TONES: Record<Tone, string> = {
  neutral: 'bg-navy-50 text-navy-600 ring-navy-100',
  navy: 'bg-navy-800 text-white ring-navy-800',
  coral: 'bg-coral-50 text-coral-700 ring-coral-200',
  sage: 'bg-sage-50 text-sage-700 ring-sage-200',
  amber: 'bg-amber-50 text-amber-700 ring-amber-200',
  critical: 'bg-critical-50 text-critical-700 ring-critical-100',
  outline: 'bg-white text-navy-600 ring-line-strong',
}

export function Badge({ children, tone = 'neutral', className, title }: { children: ReactNode; tone?: Tone; className?: string; title?: string }) {
  return (
    <span title={title} className={cx('inline-flex items-center gap-1 whitespace-nowrap rounded-md px-1.5 py-0.5 text-[11.5px] font-medium ring-1 ring-inset', TONES[tone], className)}>
      {children}
    </span>
  )
}

type Variant = 'primary' | 'secondary' | 'ghost' | 'navy' | 'danger'

const VARIANTS: Record<Variant, string> = {
  primary: 'bg-coral-600 text-white hover:bg-coral-700 shadow-sm disabled:bg-coral-300',
  navy: 'bg-navy-800 text-white hover:bg-navy-900 shadow-sm disabled:bg-navy-300',
  secondary: 'bg-white text-navy-800 ring-1 ring-inset ring-line-strong hover:bg-navy-50 disabled:text-navy-300',
  ghost: 'text-navy-600 hover:bg-navy-50 hover:text-navy-900 disabled:text-navy-300',
  danger: 'bg-white text-critical-700 ring-1 ring-inset ring-critical-100 hover:bg-critical-50',
}

export function Button({ variant = 'secondary', size = 'md', className, children, loading, ...rest }:
  ButtonHTMLAttributes<HTMLButtonElement> & { variant?: Variant; size?: 'sm' | 'md'; loading?: boolean }) {
  return (
    <button
      {...rest}
      disabled={rest.disabled || loading}
      className={cx(
        'inline-flex items-center justify-center gap-1.5 rounded-lg font-medium transition-colors disabled:cursor-not-allowed',
        size === 'sm' ? 'h-8 px-2.5 text-[13px]' : 'h-9 px-3.5 text-sm',
        VARIANTS[variant],
        className,
      )}
    >
      {loading && <Spinner className="h-3.5 w-3.5" light={variant === 'primary' || variant === 'navy'} />}
      {children}
    </button>
  )
}

export function Spinner({ className = 'h-5 w-5', light }: { className?: string; light?: boolean }) {
  return <CamelliaMark className={cx('animate-petal', className)} color={light ? '#ffffff' : undefined} />
}

export function Loading({ label = 'Loading…' }: { label?: string }) {
  return (
    <div className="flex items-center justify-center gap-3 py-16 text-sm text-navy-500">
      <Spinner className="h-6 w-6" /> {label}
    </div>
  )
}

export function ErrorBox({ error, title = 'Something went wrong' }: { error: unknown; title?: string }) {
  if (error instanceof ApiError && error.code === 'ACCESS_DENIED') return <Restricted message={error.message} />
  const message = error instanceof ApiError || error instanceof Error ? error.message : String(error)
  return (
    <div className="flex items-start gap-3 rounded-lg border border-critical-100 bg-critical-50 px-4 py-3 text-sm text-critical-700">
      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
      <div>
        <div className="font-medium">{title}</div>
        <div className="mt-0.5 text-critical-700/90">{message}</div>
      </div>
    </div>
  )
}

/** Shown for 403 ACCESS_DENIED. Deliberately generic: it never confirms whether a restricted item exists. */
export function Restricted({ title = 'Not available to your role', message }: { title?: string; message?: string }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-line bg-white px-6 py-12 text-center">
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-full bg-navy-50 text-navy-500"><Lock className="h-5 w-5" /></div>
      <div className="text-[15px] font-semibold text-navy-900">{title}</div>
      <div className="mt-1 max-w-md text-[13px] text-navy-500">
        {message ?? 'This item is not available to your account (it may not exist, or it is outside your access scope).'}
      </div>
      <div className="mt-3 text-[11.5px] text-navy-400">Access is based on the simulated demo identity selected in the top-right corner.</div>
    </div>
  )
}

export function EmptyState({ title, children, icon }: { title: string; children?: ReactNode; icon?: ReactNode }) {
  return (
    <div className="flex flex-col items-center justify-center px-6 py-12 text-center">
      <div className="mb-3 flex h-10 w-10 items-center justify-center rounded-full bg-navy-50 text-navy-400">{icon ?? <Inbox className="h-5 w-5" />}</div>
      <div className="text-sm font-medium text-navy-800">{title}</div>
      {children && <div className="mt-1 max-w-sm text-[13px] text-navy-500">{children}</div>}
    </div>
  )
}

export function PageHeader({ title, subtitle, eyebrow, actions }: { title: ReactNode; subtitle?: ReactNode; eyebrow?: ReactNode; actions?: ReactNode }) {
  return (
    <div className="mb-6 flex flex-wrap items-end justify-between gap-4">
      <div className="min-w-0">
        {eyebrow && <div className="mb-1 text-[12px] font-semibold uppercase tracking-[0.08em] text-coral-600">{eyebrow}</div>}
        <h1 className="text-[22px] font-semibold tracking-tight text-navy-950">{title}</h1>
        {subtitle && <p className="mt-1 max-w-3xl text-sm text-navy-500">{subtitle}</p>}
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}

export function Tabs<T extends string>({ tabs, value, onChange }: { tabs: { id: T; label: ReactNode; count?: number }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div className="flex flex-wrap gap-1 border-b border-line">
      {tabs.map((t) => (
        <button
          key={t.id}
          onClick={() => onChange(t.id)}
          className={cx(
            '-mb-px inline-flex items-center gap-1.5 border-b-2 px-3 py-2 text-[13px] font-medium transition-colors',
            value === t.id ? 'border-coral-500 text-navy-950' : 'border-transparent text-navy-500 hover:text-navy-800',
          )}
        >
          {t.label}
          {t.count !== undefined && (
            <span className={cx('rounded px-1.5 text-[11px]', value === t.id ? 'bg-coral-100 text-coral-700' : 'bg-navy-50 text-navy-500')}>{t.count}</span>
          )}
        </button>
      ))}
    </div>
  )
}

export function KeyValue({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="text-[11.5px] font-medium uppercase tracking-wide text-navy-400">{label}</div>
      <div className="mt-0.5 text-sm text-navy-900">{children}</div>
    </div>
  )
}

/**
 * Synthetic enterprise medical record number (SYN-…). A human-facing identifier that tells apart patients with similar
 * names; the internal database key is CAM-P-…. Neither is a real MRN — all patients are synthetic.
 */
export function SynMrn({ id, className }: { id?: string | null; className?: string }) {
  if (!id) return null
  return (
    <span title="Synthetic medical record number (demo only, not a real MRN). Unique per patient — use it to tell apart patients with similar names."
      className={cx('whitespace-nowrap font-mono text-[11.5px] text-navy-500', className)}>MRN {id}</span>
  )
}
