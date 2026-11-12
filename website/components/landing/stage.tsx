'use client';
import { useCallback, useEffect, useRef, useState } from 'react';
import type { AppId } from './app-icon';
import { CodeLines } from './code';
import { AndroidPhone, IPhone, SELECTION, type Screen } from './phones';

const LINES = [
  'import ExpoScreenTime, {',
  '  ActivityPicker,',
  "} from 'expo-screen-time';",
  '',
  'await ExpoScreenTime',
  '  .requestScreenTimePermissionsAsync();',
  '',
  '<ActivityPicker selectionId="focus" />',
  '',
  "ExpoScreenTime.blockSelection('focus');",
  '',
  '// someone opens Instagram',
];
const LAST = LINES.length - 1;
type Step = 'perm' | 'picker' | 'blocked' | 'open';
const STEPS: Record<number, Step> = { 5: 'perm', 7: 'picker', 9: 'blocked', 11: 'open' };

const FOOTNOTES: Record<Step, string> = {
  perm: 'iOS asks for Family Controls. Android opens Usage access.',
  picker: 'Each OS shows its own picker. Your JS only gets an ID.',
  blocked: 'iOS dims the icons. Android shields on open.',
  open: 'Same code, running on both phones.',
};

type State = {
  line: number;
  col: number;
  typing: boolean;
  live: boolean;
  ios: Screen;
  android: Screen;
  permGranted: boolean;
  blocked: boolean;
  ring: AppId | null;
  openId: AppId | null;
  snoozed: AppId[];
};

const INITIAL: State = {
  line: 0, col: 0, typing: true, live: false, ios: 'home', android: 'home',
  permGranted: false, blocked: false, ring: null, openId: null, snoozed: [],
};

export function Stage() {
  const [s, setS] = useState<State>(INITIAL);
  const [mobilePhone, setMobilePhone] = useState<'ios' | 'android'>('ios');
  const timers = useRef<ReturnType<typeof setTimeout>[]>([]);
  const pauseUntil = useRef(0);
  const reduceMotion = useRef(false);

  const clearTimers = () => {
    timers.current.forEach(clearTimeout);
    timers.current = [];
  };
  const at = (ms: number, patch: Partial<State>) => {
    timers.current.push(setTimeout(() => setS((prev) => ({ ...prev, ...patch })), ms));
  };

  useEffect(() => {
    if (!s.typing) return;
    const id = setInterval(() => {
      if (Date.now() < pauseUntil.current) return;
      setS((prev) => {
        if (!prev.typing) return prev;
        const text = LINES[prev.line];
        if (prev.col < text.length) return { ...prev, col: prev.col + 1 };
        const step = STEPS[prev.line];
        const next = { ...prev, line: prev.line + 1, col: 0 };
        if (step === 'perm') {
          pauseUntil.current = Date.now() + 2600;
          at(1200, { permGranted: true });
          return { ...next, ios: 'perm', android: 'perm' };
        }
        if (step === 'picker') {
          pauseUntil.current = Date.now() + 2300;
          return { ...next, ios: 'picker', android: 'picker' };
        }
        if (step === 'blocked') {
          pauseUntil.current = Date.now() + 1300;
          return { ...next, ios: 'home', android: 'home', blocked: true };
        }
        if (step === 'open') {
          at(800, { ring: null, ios: 'shield', android: 'shield', openId: 'instagram' });
          at(4200, { ios: 'home', android: 'home', live: true });
          return { ...prev, typing: false, ring: 'instagram' };
        }
        pauseUntil.current = Date.now() + (text === '' ? 60 : 220);
        return next;
      });
    }, 32);
    return () => clearInterval(id);
  }, [s.typing]);

  useEffect(() => {
    reduceMotion.current = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
    if (reduceMotion.current) setS({ ...INITIAL, line: LAST, col: LINES[LAST].length, typing: false, live: true, blocked: true, permGranted: true });
    return clearTimers;
  }, []);

  const takeOver = useCallback((patch: Partial<State>) => {
    clearTimers();
    setS((prev) => ({
      ...prev, line: LAST, col: LINES[LAST].length, typing: false, live: true, ring: null,
      blocked: true, permGranted: true, ...patch,
    }));
  }, []);

  const replay = () => {
    clearTimers();
    pauseUntil.current = 0;
    setS(INITIAL);
  };

  const isShielded = (id: AppId) => s.blocked && SELECTION.includes(id) && !s.snoozed.includes(id);
  const phone = (key: 'ios' | 'android') => ({
    screen: s[key],
    openId: s.openId,
    ringId: s.ring,
    hint: s.live ? 'Your turn: tap any app' : '',
    openReason: s.openId && s.snoozed.includes(s.openId) ? 'Snoozed for 5 minutes' : 'Not in the selection',
    shielded: key === 'ios' ? isShielded : () => false,
    permGranted: s.permGranted,
    onOpen: (id: AppId) => takeOver({ openId: id, [key]: SELECTION.includes(id) && !s.snoozed.includes(id) ? 'shield' : 'app' }),
    onHome: () => takeOver({ [key]: 'home' }),
    onSnooze: () => takeOver({ [key]: 'app', snoozed: s.openId ? [...s.snoozed, s.openId] : s.snoozed }),
  });

  const lastStep = STEPS[s.line - 1];
  const footnote = s.live ? 'Your turn: tap an app on either phone.' : lastStep && s.typing ? FOOTNOTES[lastStep] : FOOTNOTES.open;
  const shown = LINES.map((text, i) => (i < s.line ? text : i === s.line ? text.slice(0, s.col) : ''));
  const highlight = s.typing && lastStep ? [s.line] : !s.live && !s.typing ? [LAST + 1] : [];

  const editor = (
    <div className="flex min-w-0 flex-1 flex-col self-stretch overflow-hidden rounded-[14px] bg-panel shadow-[0_24px_60px_rgba(0,0,0,0.35)]">
      <div className="flex h-10 items-center justify-between px-4 font-mono text-[12.5px] text-mist shadow-[inset_0_-1px_0_var(--color-line)]">
        <span>FocusScreen.tsx</span>
        <span style={{ color: s.live ? '#9D9CB8' : '#7CE3FF' }}>{s.live ? 'your turn' : '● running'}</span>
      </div>
      <CodeLines lines={shown} highlight={highlight} caretLine={s.typing ? s.line : undefined} className="flex-1 py-[18px] text-[12px] sm:text-[13px] md:text-[13.5px]" />
      <div className="flex items-center justify-between gap-3 px-4 py-3 shadow-[inset_0_1px_0_var(--color-line)]">
        <span className="text-[13.5px] leading-[1.4] text-[#C9C8FF]" aria-live="polite">{footnote}</span>
        <button type="button" onClick={replay} className="press h-[34px] shrink-0 rounded-lg bg-raised px-3 font-mono text-[12.5px]">replay</button>
      </div>
    </div>
  );

  return (
    <section className="rounded-[28px] bg-stage px-3 py-5 shadow-[inset_0_0_0_1px_var(--color-stage-line)] sm:px-4 sm:py-6 lg:px-8 lg:pb-8 lg:pt-10">
      <div className="hidden items-start justify-between gap-7 xl:flex">
        <figure className="m-0 flex flex-col items-center gap-3.5">
          <div className="phone-fit"><IPhone {...phone('ios')} /></div>
          <figcaption className="font-mono text-[12.5px] text-[#C9C8FF]">ios · enforced by the OS</figcaption>
        </figure>
        {editor}
        <figure className="m-0 flex flex-col items-center gap-3.5">
          <div className="phone-fit"><AndroidPhone {...phone('android')} /></div>
          <figcaption className="font-mono text-[12.5px] text-[#C9C8FF]">android · the module’s own engine</figcaption>
        </figure>
      </div>
      <div className="flex flex-col items-center gap-5 xl:hidden">
        <div role="tablist" aria-label="Platform" className="grid grid-cols-2 gap-1 rounded-xl bg-panel p-1">
          {(['ios', 'android'] as const).map((k) => (
            <button key={k} type="button" role="tab" aria-selected={mobilePhone === k} onClick={() => setMobilePhone(k)} className="press h-9 rounded-lg px-4 font-mono text-[13px]" style={{ background: mobilePhone === k ? '#34338A' : 'transparent', color: mobilePhone === k ? '#F4F4FA' : '#9D9CB8' }}>
              {k}
            </button>
          ))}
        </div>
        <div className="phone-fit">{mobilePhone === 'ios' ? <IPhone {...phone('ios')} /> : <AndroidPhone {...phone('android')} />}</div>
        <div className="flex w-full max-w-[640px]">{editor}</div>
      </div>
    </section>
  );
}
