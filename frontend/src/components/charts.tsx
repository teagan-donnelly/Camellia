import { Table2 } from 'lucide-react'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { cx } from '../lib/format'

/*
 * Small, dependency-free SVG charts for SIMULATED wearable data.
 * One series per chart (so no legend box: the card title names the series), one validated series hue
 * (coral-600 #d9505a: passes the lightness, chroma and 3:1 contrast checks on the white card surface),
 * 2px lines with a ~10% area wash, hairline recessive grid, crosshair + tooltip on hover/focus, and a
 * table view so no value is hover-only.
 */
const SERIES = 'var(--color-coral-600)'
const GRID = 'var(--color-line)'
const INK_MUTED = 'var(--color-navy-400)'
const H = 168
const PAD = { top: 12, right: 14, bottom: 26, left: 40 }

function useWidth<T extends HTMLElement>() {
  const ref = useRef<T>(null)
  const [w, setW] = useState(600)
  useEffect(() => {
    if (!ref.current) return
    const ro = new ResizeObserver(([e]) => setW(Math.max(260, Math.floor(e.contentRect.width))))
    ro.observe(ref.current)
    return () => ro.disconnect()
  }, [])
  return [ref, w] as const
}

function niceTicks(min: number, max: number, count = 4): number[] {
  if (min === max) { min -= 1; max += 1 }
  const step0 = (max - min) / count
  const mag = Math.pow(10, Math.floor(Math.log10(step0)))
  const step = [1, 2, 2.5, 5, 10].map((m) => m * mag).find((s) => s >= step0) ?? step0
  const start = Math.floor(min / step) * step
  const out: number[] = []
  for (let v = start; v <= max + step * 0.5; v += step) out.push(Math.round(v * 100) / 100)
  return out
}

export function ChartFrame({ title, subtitle, table, children, stale }: { title: string; subtitle?: ReactNode; table: ReactNode; children: ReactNode; stale?: boolean }) {
  const [showTable, setShowTable] = useState(false)
  return (
    <figure className="rounded-xl border border-line bg-white p-4">
      <figcaption className="mb-2 flex items-start justify-between gap-2">
        <div>
          <div className="text-[13.5px] font-semibold text-navy-900">{title}</div>
          {subtitle && <div className="text-[11.5px] text-navy-500">{subtitle}</div>}
        </div>
        <button type="button" onClick={() => setShowTable((v) => !v)} aria-pressed={showTable}
          className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-[11.5px] font-medium text-navy-500 hover:bg-navy-50 hover:text-navy-800">
          <Table2 className="h-3.5 w-3.5" />{showTable ? 'Chart' : 'Table'}
        </button>
      </figcaption>
      <div className={cx('transition-opacity', stale && 'opacity-50')}>{showTable ? table : children}</div>
    </figure>
  )
}

export interface TimePoint { t: string; v: number; quality?: string }

/** Line chart over time with a crosshair that snaps to the nearest reading. */
export function TimeSeriesChart({ points, unit, from, to, format, emptyText = 'No readings in this range.' }: {
  points: TimePoint[]; unit: string; from: string; to: string; format: (iso: string) => string; emptyText?: string
}) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  const t0 = Date.parse(from + 'Z'), t1 = Date.parse(to + 'Z')
  const data = useMemo(() => points.map((p) => ({ ...p, ms: Date.parse(p.t + 'Z') })), [points])
  if (!data.length) return <div ref={ref} className="flex h-[168px] items-center justify-center rounded-lg bg-canvas text-[12.5px] text-navy-400">{emptyText}</div>
  const vals = data.map((d) => d.v)
  const ticks = niceTicks(Math.min(...vals), Math.max(...vals))
  const yMin = ticks[0], yMax = ticks[ticks.length - 1]
  const iw = width - PAD.left - PAD.right, ih = H - PAD.top - PAD.bottom
  const x = (ms: number) => PAD.left + ((ms - t0) / Math.max(1, t1 - t0)) * iw
  const y = (v: number) => PAD.top + (1 - (v - yMin) / Math.max(1e-9, yMax - yMin)) * ih
  const line = data.map((d, i) => `${i ? 'L' : 'M'}${x(d.ms).toFixed(1)},${y(d.v).toFixed(1)}`).join('')
  const area = `${line}L${x(data[data.length - 1].ms).toFixed(1)},${PAD.top + ih}L${x(data[0].ms).toFixed(1)},${PAD.top + ih}Z`
  const xTicks = [0, 0.25, 0.5, 0.75, 1].map((f) => t0 + f * (t1 - t0))
  const h = hover == null ? null : data[hover]
  const nearest = (px: number) => {
    let best = 0, bd = Infinity
    data.forEach((d, i) => { const dd = Math.abs(x(d.ms) - px); if (dd < bd) { bd = dd; best = i } })
    return best
  }
  return (
    <div ref={ref} className="relative">
      <svg width={width} height={H} role="img" aria-label={`${data.length} readings from ${format(from)} to ${format(to)}, ranging ${Math.min(...vals)} to ${Math.max(...vals)} ${unit}`}
        tabIndex={0}
        onPointerMove={(e) => setHover(nearest(e.clientX - e.currentTarget.getBoundingClientRect().left))}
        onPointerLeave={() => setHover(null)}
        onKeyDown={(e) => {
          if (e.key === 'ArrowRight') setHover((hv) => Math.min(data.length - 1, (hv ?? -1) + 1))
          if (e.key === 'ArrowLeft') setHover((hv) => Math.max(0, (hv ?? data.length) - 1))
        }}
        onBlur={() => setHover(null)} className="block outline-none focus-visible:ring-2 focus-visible:ring-coral-200 rounded">
        {ticks.map((tk) => (
          <g key={tk}>
            <line x1={PAD.left} x2={width - PAD.right} y1={y(tk)} y2={y(tk)} stroke={GRID} strokeWidth={1} />
            <text x={PAD.left - 6} y={y(tk)} dy="0.32em" textAnchor="end" fontSize={10.5} fill={INK_MUTED}>{tk}</text>
          </g>
        ))}
        {xTicks.map((ms, i) => (
          <text key={i} x={x(ms)} y={H - 8} textAnchor={i === 0 ? 'start' : i === 4 ? 'end' : 'middle'} fontSize={10.5} fill={INK_MUTED}>{format(new Date(ms).toISOString().slice(0, 19))}</text>
        ))}
        <path d={area} fill={SERIES} opacity={0.1} />
        <path d={line} fill="none" stroke={SERIES} strokeWidth={2} strokeLinejoin="round" strokeLinecap="round" />
        {h && (
          <g pointerEvents="none">
            <line x1={x(h.ms)} x2={x(h.ms)} y1={PAD.top} y2={PAD.top + ih} stroke={INK_MUTED} strokeWidth={1} />
            <circle cx={x(h.ms)} cy={y(h.v)} r={5} fill={SERIES} stroke="white" strokeWidth={2} />
          </g>
        )}
      </svg>
      {h && (
        <div className="pointer-events-none absolute top-1 rounded-lg border border-line bg-white px-2.5 py-1.5 text-[12px] shadow-md"
          style={{ left: Math.min(Math.max(x(h.ms) - 70, 0), width - 150) }}>
          <div className="font-semibold text-navy-900">{h.v} {unit}</div>
          <div className="text-navy-500">{format(h.t)}{h.quality && h.quality !== 'GOOD' ? ` · signal ${h.quality.toLowerCase()}` : ''}</div>
        </div>
      )}
    </div>
  )
}

export interface BarDatum { label: string; value: number }

/** Column chart (walking minutes per hour or per day). Each column is its own hover/focus target. */
export function ColumnChart({ data, unit, emptyText = 'No data in this range.' }: { data: BarDatum[]; unit: string; emptyText?: string }) {
  const [ref, width] = useWidth<HTMLDivElement>()
  const [hover, setHover] = useState<number | null>(null)
  if (!data.length) return <div ref={ref} className="flex h-[168px] items-center justify-center rounded-lg bg-canvas text-[12.5px] text-navy-400">{emptyText}</div>
  const max = Math.max(5, ...data.map((d) => d.value))
  const ticks = niceTicks(0, max)
  const yMax = ticks[ticks.length - 1]
  const iw = width - PAD.left - PAD.right, ih = H - PAD.top - PAD.bottom
  const slot = iw / data.length
  const bw = Math.max(2, Math.min(24, slot - 2))
  const y = (v: number) => PAD.top + (1 - v / yMax) * ih
  const labelEvery = Math.ceil(data.length / 6)
  const h = hover == null ? null : data[hover]
  return (
    <div ref={ref} className="relative">
      <svg width={width} height={H} role="img" aria-label={`${data.reduce((s, d) => s + d.value, 0)} ${unit} in total`} className="block">
        {ticks.map((tk) => (
          <g key={tk}>
            <line x1={PAD.left} x2={width - PAD.right} y1={y(tk)} y2={y(tk)} stroke={GRID} strokeWidth={1} />
            <text x={PAD.left - 6} y={y(tk)} dy="0.32em" textAnchor="end" fontSize={10.5} fill={INK_MUTED}>{tk}</text>
          </g>
        ))}
        {data.map((d, i) => {
          const cx0 = PAD.left + slot * i + slot / 2
          const top = y(d.value), base = PAD.top + ih
          const r = Math.min(4, bw / 2, base - top)
          const path = d.value <= 0 ? '' : `M${cx0 - bw / 2},${base}V${top + r}Q${cx0 - bw / 2},${top} ${cx0 - bw / 2 + r},${top}H${cx0 + bw / 2 - r}Q${cx0 + bw / 2},${top} ${cx0 + bw / 2},${top + r}V${base}Z`
          return (
            <g key={i} tabIndex={0} role="img" aria-label={`${d.label}: ${d.value} ${unit}`}
              onPointerEnter={() => setHover(i)} onPointerLeave={() => setHover(null)} onFocus={() => setHover(i)} onBlur={() => setHover(null)}
              className="outline-none">
              <rect x={PAD.left + slot * i} y={PAD.top} width={slot} height={ih} fill="transparent" />
              {path && <path d={path} fill={SERIES} opacity={hover === i ? 0.8 : 1} />}
              {i % labelEvery === 0 && <text x={cx0} y={H - 8} textAnchor="middle" fontSize={10.5} fill={INK_MUTED}>{d.label.split(' ').slice(-1)[0]}</text>}
            </g>
          )
        })}
      </svg>
      {h && (
        <div className="pointer-events-none absolute top-1 rounded-lg border border-line bg-white px-2.5 py-1.5 text-[12px] shadow-md"
          style={{ left: Math.min(Math.max(PAD.left + slot * (hover ?? 0) - 50, 0), width - 140) }}>
          <div className="font-semibold text-navy-900">{h.value} {unit}</div>
          <div className="text-navy-500">{h.label}</div>
        </div>
      )}
    </div>
  )
}

export function DataTable({ head, rows }: { head: string[]; rows: (string | number)[][] }) {
  return (
    <div className="scroll-thin max-h-[168px] overflow-y-auto rounded-lg border border-line">
      <table className="w-full text-[12px]">
        <thead className="sticky top-0 bg-canvas text-left text-navy-500"><tr>{head.map((h) => <th key={h} className="px-2.5 py-1.5 font-medium">{h}</th>)}</tr></thead>
        <tbody className="divide-y divide-line">{rows.map((r, i) => <tr key={i}>{r.map((c, j) => <td key={j} className="px-2.5 py-1 text-navy-800">{c}</td>)}</tr>)}</tbody>
      </table>
    </div>
  )
}
