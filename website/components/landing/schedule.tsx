'use client';
import { useEffect, useState, useSyncExternalStore } from 'react';
import { AppIcon, type AppId } from './app-icon';
import { CodeCard, CodeLines } from './code';

const PRESETS = {
  work: { label: '9–17', start: 9, end: 17, name: 'workHours' },
  evening: { label: '19–23', start: 19, end: 23, name: 'evening' },
  bedtime: { label: '22–7', start: 22, end: 7, name: 'bedtime' },
} as const;
type Preset = keyof typeof PRESETS;
const DIAL_APPS: AppId[] = ['instagram', 'tiktok', 'youtube', 'x', 'netflix'];

const C = 200;
const R = 160;
function point(hour: number, r: number) {
  const a = (hour / 24) * 2 * Math.PI - Math.PI / 2;
  return [C + r * Math.cos(a), C + r * Math.sin(a)] as const;
}
const TICKS = Array.from({ length: 24 }, (_, h) => {
  const [x1, y1] = point(h, 132);
  const [x2, y2] = point(h, h % 6 === 0 ? 120 : 126);
  return `M ${x1.toFixed(1)} ${y1.toFixed(1)} L ${x2.toFixed(1)} ${y2.toFixed(1)}`;
}).join(' ');

const REDUCED = '(prefers-reduced-motion: reduce)';
function useReducedMotion() {
  return useSyncExternalStore(
    (onChange) => {
      const mq = window.matchMedia(REDUCED);
      mq.addEventListener('change', onChange);
      return () => mq.removeEventListener('change', onChange);
    },
    () => window.matchMedia(REDUCED).matches,
    () => false,
  );
}

export function Schedule() {
  const [hour, setHour] = useState(7.5);
  const reduced = useReducedMotion();
  const [choice, setChoice] = useState<boolean | null>(null);
  const playing = choice ?? !reduced;
  const [preset, setPreset] = useState<Preset>('work');

  useEffect(() => {
    if (!playing) return;
    const id = setInterval(() => setHour((h) => (h + 0.125) % 24), 140);
    return () => clearInterval(id);
  }, [playing]);

  const { start, end, name } = PRESETS[preset];
  const span = (end - start + 24) % 24;
  const [ax, ay] = point(start, R);
  const [bx, by] = point(end, R);
  const arc = `M ${ax.toFixed(1)} ${ay.toFixed(1)} A ${R} ${R} 0 ${span > 12 ? 1 : 0} 1 ${bx.toFixed(1)} ${by.toFixed(1)}`;
  const [hx, hy] = point(hour, R);
  const [ix, iy] = point(hour, 112);
  const inside = start < end ? hour >= start && hour < end : hour >= start || hour < end;
  const hh = Math.floor(hour);
  const mm = Math.round((hour - hh) * 60);
  const clock = `${String(hh).padStart(2, '0')}:${String(mm).padStart(2, '0')}`;

  const code = [
    'await ExpoScreenTime.startMonitoring({',
    `  activityName: '${name}', // one name per schedule`,
    "  selectionId: 'distractions',",
    '  schedule: {',
    '    // local time, can cross midnight',
    `    intervalStart: { hour: ${start}, minute: 0 },`,
    `    intervalEnd: { hour: ${end}, minute: 0 },`,
    '    repeats: true,',
    '  },',
    '  weekdays: [2, 3, 4, 5, 6], // Monday to Friday',
    '});',
    '',
    'useMonitorEvents((event) => console.log(event.eventType));',
  ];

  return (
    <div className="grid gap-6 lg:grid-cols-12">
      <div className="flex flex-col items-center gap-[18px] rounded-[20px] bg-panel px-4 pb-[22px] pt-7 shadow-[inset_0_0_0_1px_var(--color-line)] sm:px-6 lg:col-span-5">
        <div className="@container relative aspect-square w-full max-w-[400px]">
          <svg viewBox="0 0 400 400" className="h-full w-full" role="img" aria-label={`24-hour dial: ${clock}, ${inside ? 'inside' : 'outside'} the ${name} schedule`}>
            <circle cx="200" cy="200" r="160" fill="none" stroke="#1C1B36" strokeWidth="30" />
            <path d={TICKS} stroke="#34338A" strokeWidth="2" strokeLinecap="round" />
            <path d={arc} fill="none" stroke="#5E5CE6" strokeWidth="30" strokeLinecap="round" />
            <line x1={ix} y1={iy} x2={hx} y2={hy} stroke="#FFD27A" strokeWidth="3" strokeLinecap="round" />
            <circle cx={hx} cy={hy} r="9" fill="#FFD27A" stroke="#0E0E1C" strokeWidth="3" />
            {[['00', 200, 22], ['06', 384, 204], ['12', 200, 392], ['18', 16, 204]].map(([t, x, y]) => (
              <text key={t} x={x} y={y} textAnchor="middle" fill="#6F6E9E" fontFamily="var(--font-geist-mono), monospace" fontSize="12">{t}</text>
            ))}
          </svg>
          <div className="pointer-events-none absolute left-1/2 top-1/2 flex -translate-x-1/2 -translate-y-1/2 flex-col items-center gap-2.5">
            <span className="font-mono text-[11cqw] font-medium leading-none tracking-[-0.04em] tabular-nums">{clock}</span>
            <div className="flex gap-1.5">
              {DIAL_APPS.map((id, i) => <span key={id} className={i > 2 ? '@max-[330px]:hidden' : undefined}><AppIcon id={id} size={26} dim={inside} /></span>)}
            </div>
            <span className="text-[13px]" style={{ color: inside ? '#A5A3FF' : '#6F6E9E' }}>{inside ? 'shielded' : 'free'}</span>
          </div>
        </div>
        <div className="flex w-full items-center gap-2.5">
          <button type="button" onClick={() => setChoice(!playing)} aria-label={playing ? 'Pause' : 'Play'} className="press flex h-[38px] w-[38px] shrink-0 items-center justify-center rounded-full bg-ink">
            {playing ? (
              <svg width="12" height="14" viewBox="0 0 14 16" aria-hidden="true"><rect x="1" y="1" width="4" height="14" rx="1" fill="#0E0E1C" /><rect x="9" y="1" width="4" height="14" rx="1" fill="#0E0E1C" /></svg>
            ) : (
              <svg width="12" height="14" viewBox="0 0 14 16" aria-hidden="true"><path d="M2 1 L13 8 L2 15 Z" fill="#0E0E1C" /></svg>
            )}
          </button>
          <div role="radiogroup" aria-label="Schedule" className="grid flex-1 grid-cols-3 gap-1 rounded-[10px] bg-[#1C1B36] p-1">
            {(Object.keys(PRESETS) as Preset[]).map((k) => (
              <button key={k} type="button" role="radio" aria-checked={preset === k} onClick={() => setPreset(k)} className="h-[30px] rounded-[7px] font-mono text-[12px] transition-colors" style={{ background: preset === k ? '#34338A' : 'transparent', color: preset === k ? '#F4F4FA' : '#9D9CB8' }}>
                {PRESETS[k].label}
              </button>
            ))}
          </div>
        </div>
      </div>
      <CodeCard file="schedule.ts" className="lg:col-span-7">
        <CodeLines lines={code} highlight={[6, 7]} className="py-5 text-[13px] sm:text-[14px]" />
      </CodeCard>
    </div>
  );
}
