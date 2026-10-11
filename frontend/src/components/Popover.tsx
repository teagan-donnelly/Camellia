import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from 'react'
import { createPortal } from 'react-dom'

/**
 * Floating panel anchored to a trigger, rendered in a portal on <body>.
 *
 * Why a portal: page content can create its own stacking context (backdrop-filter, transforms, animations, overflow
 * clipping). A dropdown rendered inside the header could then be painted *under* later page content no matter how large
 * its z-index — which is exactly what happened on Clinical Search and the coordination board. From <body>, the panel
 * uses the single app-wide layer scale defined in index.css (--layer-popover), so it is always above page content and
 * below the record drawer and toasts.
 */
export function AnchoredPopover({ anchorRef, open, onClose, width = 360, align = 'end', role = 'menu', label, children }: {
  anchorRef: RefObject<HTMLElement | null>
  open: boolean
  onClose: () => void
  width?: number
  align?: 'start' | 'end'
  role?: string
  label?: string
  children: ReactNode
}) {
  const panelRef = useRef<HTMLDivElement>(null)
  const [pos, setPos] = useState<{ top: number; left: number; width: number; maxHeight: number } | null>(null)

  useLayoutEffect(() => {
    if (!open) return
    const place = () => {
      const a = anchorRef.current?.getBoundingClientRect()
      if (!a) return
      const margin = 8
      const w = Math.min(width, window.innerWidth - 2 * margin)
      const left = Math.max(margin, Math.min(align === 'end' ? a.right - w : a.left, window.innerWidth - w - margin))
      const top = a.bottom + 6
      setPos({ top, left, width: w, maxHeight: Math.max(160, window.innerHeight - top - margin) })
    }
    place()
    window.addEventListener('resize', place)
    window.addEventListener('scroll', place, true)
    return () => {
      window.removeEventListener('resize', place)
      window.removeEventListener('scroll', place, true)
    }
  }, [open, anchorRef, width, align])

  useEffect(() => {
    if (!open) return
    const onDown = (e: MouseEvent) => {
      const t = e.target as Node
      if (panelRef.current?.contains(t) || anchorRef.current?.contains(t)) return
      onClose()
    }
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        onClose()
        anchorRef.current?.focus()
      }
    }
    window.addEventListener('mousedown', onDown)
    window.addEventListener('keydown', onKey)
    return () => {
      window.removeEventListener('mousedown', onDown)
      window.removeEventListener('keydown', onKey)
    }
  }, [open, onClose, anchorRef])

  if (!open || !pos) return null
  return createPortal(
    <div
      ref={panelRef}
      role={role}
      aria-label={label}
      style={{ position: 'fixed', top: pos.top, left: pos.left, width: pos.width, maxHeight: pos.maxHeight, zIndex: 'var(--layer-popover)' }}
      className="scroll-thin overflow-y-auto rounded-xl border border-line bg-white p-1.5 shadow-xl animate-fade-up"
    >
      {children}
    </div>,
    document.body,
  )
}
