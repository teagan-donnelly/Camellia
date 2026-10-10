import { useId } from 'react'
import { cx } from '../lib/format'

/**
 * The Camellia flower: eight translucent overlapping petals around a transparent center (recreated from the brand logo).
 * The center is cut out with a mask rather than painted white, so the mark sits cleanly on navy and white alike.
 */
export function CamelliaMark({ className = 'h-8 w-8', color = '#EE6B72' }: { className?: string; color?: string }) {
  const maskId = `camellia-center-${useId().replace(/:/g, '')}`
  return (
    <svg viewBox="0 0 100 100" className={className} aria-hidden="true">
      <mask id={maskId}>
        <rect width="100" height="100" fill="white" />
        <circle cx="50" cy="50" r="7.5" fill="black" />
      </mask>
      <g fill={color} fillOpacity={0.62} mask={`url(#${maskId})`}>
        {Array.from({ length: 8 }, (_, i) => (
          <ellipse key={i} cx="50" cy="28" rx="17" ry="21" transform={`rotate(${i * 45} 50 50)`} />
        ))}
      </g>
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
