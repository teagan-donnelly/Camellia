import { cx } from '../lib/format'

/** The Camellia flower: eight translucent overlapping petals around a white center (recreated from the brand logo). */
export function CamelliaMark({ className = 'h-8 w-8', color = '#EE6B72' }: { className?: string; color?: string }) {
  return (
    <svg viewBox="0 0 100 100" className={className} aria-hidden="true">
      <g fill={color} fillOpacity={0.62}>
        {Array.from({ length: 8 }, (_, i) => (
          <ellipse key={i} cx="50" cy="28" rx="17" ry="21" transform={`rotate(${i * 45} 50 50)`} />
        ))}
      </g>
      <circle cx="50" cy="50" r="7.5" fill="#fff" />
    </svg>
  )
}

export function Wordmark({ className, dark }: { className?: string; dark?: boolean }) {
  return (
    <div className={cx('flex items-center gap-2.5', className)}>
      <CamelliaMark className="h-8 w-8 shrink-0" />
      <span className={cx('font-brand text-[22px] font-semibold leading-none tracking-tight', dark ? 'text-coral-300' : 'text-coral-500')}>
        Camellia
      </span>
    </div>
  )
}
