'use client';
import { AppIcon, APP_NAMES, type AppId } from './app-icon';

export type Screen = 'home' | 'perm' | 'picker' | 'shield' | 'app';

export type PhoneProps = {
  screen: Screen;
  openId: AppId | null;
  shielded: (id: AppId) => boolean;
  ringId: AppId | null;
  hint: string;
  openReason: string;
  onOpen: (id: AppId) => void;
  onHome: () => void;
  onSnooze: () => void;
  permGranted?: boolean;
};

const SHIELD = {
  title: 'Time to focus',
  subtitle: 'This app is blocked during focus time.',
  primary: 'Close',
  secondary: 'Snooze 5 min',
};

export const SELECTION: AppId[] = ['instagram', 'tiktok', 'youtube'];
const GRID: AppId[] = ['instagram', 'tiktok', 'x', 'facebook', 'youtube', 'netflix', 'spotify', 'twitch', 'whatsapp', 'reddit', 'pinterest', 'screentime'];

function TapRing({ top }: { top: number }) {
  return (
    <span
      className="tap-ring pointer-events-none absolute left-1/2 -ml-[22px] h-11 w-11 rounded-full border-2 border-white bg-white/55"
      style={{ top: top - 22 }}
    />
  );
}

function HourglassBadge() {
  return (
    <svg width="7" height="10" viewBox="0 0 7 10" aria-hidden="true">
      <path d="M0.5 0.5 H6.5 V1.6 C6.5 3 4.6 4.1 4.1 5 C4.6 5.9 6.5 7 6.5 8.4 V9.5 H0.5 V8.4 C0.5 7 2.4 5.9 2.9 5 C2.4 4.1 0.5 3 0.5 1.6 Z" fill="#fff" />
    </svg>
  );
}

const PICKER_CATEGORIES: [string, string, boolean][] = [
  ['All Apps & Categories', '#8E8E93', false],
  ['Social', '#0A84FF', true],
  ['Games', '#FF375F', false],
  ['Entertainment', '#FF9F0A', true],
  ['Creativity', '#BF5AF2', false],
  ['Education', '#30D158', false],
  ['Health & Fitness', '#FF453A', false],
  ['Productivity & Finance', '#64D2FF', false],
  ['Utilities', '#8E8E93', false],
];

export function IPhone(p: PhoneProps) {
  const open = p.openId ?? 'instagram';
  return (
    <div className="relative h-[690px] w-[330px] text-white" style={{ fontFamily: "-apple-system, 'SF Pro Text', var(--font-geist), system-ui, sans-serif" }}>
      <div className="absolute -left-[3px] top-[118px] h-[30px] w-1 rounded-sm bg-[#3A3A3E]" />
      <div className="absolute -left-[3px] top-[168px] h-14 w-1 rounded-sm bg-[#3A3A3E]" />
      <div className="absolute -left-[3px] top-[236px] h-14 w-1 rounded-sm bg-[#3A3A3E]" />
      <div className="absolute -right-[3px] top-[190px] h-[86px] w-1 rounded-sm bg-[#3A3A3E]" />
      <div className="absolute inset-0 rounded-[58px] bg-[#1C1C1F] shadow-[inset_0_0_0_2px_#3A3A3E,0_40px_80px_rgba(0,0,0,0.55)]">
        <div className="absolute inset-[11px] overflow-hidden rounded-[47px]" style={{ background: 'linear-gradient(165deg, #3B2F78 0%, #1C2B5E 45%, #0B1530 100%)' }}>
          <div className="absolute inset-x-0 top-0 z-[5] flex h-[50px] items-center justify-between pl-[34px] pr-[30px] pt-1.5 text-[15px] font-semibold">
            <span>9:41</span>
            <span className="flex items-center gap-[5px]">
              <svg width="17" height="11" viewBox="0 0 17 11" aria-hidden="true"><rect y="7" width="3" height="4" rx="1" fill="#fff" /><rect x="4.5" y="5" width="3" height="6" rx="1" fill="#fff" /><rect x="9" y="2.5" width="3" height="8.5" rx="1" fill="#fff" /><rect x="13.5" width="3" height="11" rx="1" fill="#fff" /></svg>
              <svg width="15" height="11" viewBox="0 0 15 11" aria-hidden="true"><path d="M7.5 10.5 L5.3 8.2 A3.2 3.2 0 0 1 9.7 8.2 Z" fill="#fff" /><path d="M3.2 6.1 A6.2 6.2 0 0 1 11.8 6.1" fill="none" stroke="#fff" strokeWidth="1.6" strokeLinecap="round" /><path d="M1 3.6 A9.4 9.4 0 0 1 14 3.6" fill="none" stroke="#fff" strokeWidth="1.6" strokeLinecap="round" /></svg>
              <svg width="26" height="12" viewBox="0 0 26 12" aria-hidden="true"><rect x="0.5" y="0.5" width="22" height="11" rx="3.5" fill="none" stroke="rgba(255,255,255,0.45)" /><rect x="2" y="2" width="17" height="8" rx="2" fill="#fff" /><path d="M24 4 V8" stroke="rgba(255,255,255,0.45)" strokeWidth="1.5" strokeLinecap="round" /></svg>
            </span>
          </div>
          <div className="absolute left-1/2 top-[11px] z-[6] -ml-12 h-7 w-24 rounded-[14px] bg-black" />

          {(p.screen === 'home' || p.screen === 'perm') && (
            <>
              <div className="absolute inset-x-0 top-[62px] grid grid-cols-4 gap-y-5 px-4">
                {GRID.map((id) => {
                  const dim = p.shielded(id);
                  return (
                    <button key={id} type="button" onClick={() => p.onOpen(id)} aria-label={`Open ${APP_NAMES[id]}`} className="relative flex flex-col items-center gap-[5px]">
                      <AppIcon id={id} dim={dim} />
                      <span className="flex items-center gap-0.5 text-[12px] tracking-[-0.01em] [text-shadow:0_1px_2px_rgba(0,0,0,0.35)]">
                        {dim ? <HourglassBadge /> : null}
                        {APP_NAMES[id]}
                      </span>
                      {p.ringId === id ? <TapRing top={29} /> : null}
                    </button>
                  );
                })}
              </div>
              {p.hint ? (
                <div className="absolute bottom-[112px] left-1/2 -translate-x-1/2 whitespace-nowrap rounded-full bg-black/40 px-3.5 py-[7px] text-[12.5px] font-semibold">{p.hint}</div>
              ) : null}
              <div className="absolute inset-x-3 bottom-3.5 grid h-[84px] grid-cols-4 items-center rounded-[32px] bg-white/[0.18] px-2">
                {(['phone', 'safari', 'messages', 'mail'] as AppId[]).map((id) => (
                  <button key={id} type="button" onClick={() => p.onOpen(id)} aria-label={`Open ${APP_NAMES[id]}`} className="flex justify-center">
                    <AppIcon id={id} />
                  </button>
                ))}
              </div>
            </>
          )}

          {p.screen === 'perm' && (
            <div className="absolute inset-0 z-[4] flex items-center justify-center bg-black/40">
              <div className="enter-alert w-[252px] overflow-hidden rounded-[14px] bg-[rgba(44,44,46,0.94)] text-center">
                <div className="flex flex-col gap-1 px-4 pb-4 pt-[18px]">
                  <div className="text-[15.5px] font-semibold leading-tight">“Screen Time” Would Like to Access Screen Time</div>
                  <div className="text-[12px] leading-[1.35] text-white/85">Providing “Screen Time” access to Screen Time may allow it to see your activity data, restrict content, and limit the usage of apps and websites.</div>
                </div>
                <div className="grid grid-cols-2 border-t-[0.5px] border-[rgba(84,84,88,0.65)] text-[15.5px] text-[#0A84FF]">
                  <span className="flex h-[42px] items-center justify-center border-r-[0.5px] border-[rgba(84,84,88,0.65)]">Continue</span>
                  <span className="flex h-[42px] items-center justify-center font-semibold">Don’t Allow</span>
                </div>
              </div>
            </div>
          )}

          {p.screen === 'picker' && (
            <div className="absolute inset-0 z-[4] bg-black/50">
              <div className="enter-sheet absolute inset-x-0 bottom-0 top-[52px] flex flex-col rounded-t-xl bg-[#1C1C1E]">
                <div className="flex items-center justify-between px-4 pb-2 pt-3.5 text-[15.5px]">
                  <span className="text-[#0A84FF]">Cancel</span>
                  <span className="font-semibold">Choose Activities</span>
                  <span className="font-semibold text-[#0A84FF]">Done</span>
                </div>
                <div className="mx-3.5 mb-3 mt-1.5 flex h-[34px] items-center gap-1.5 rounded-[10px] bg-[rgba(118,118,128,0.24)] px-2.5 text-[15px] text-[rgba(235,235,245,0.6)]">Search</div>
                <div className="mx-3.5 overflow-hidden rounded-[10px] bg-[#2C2C2E]">
                  {PICKER_CATEGORIES.map(([name, color, on]) => (
                    <div key={name} className="flex h-[42px] items-center gap-[11px] border-b-[0.5px] border-[rgba(84,84,88,0.6)] px-3 text-[14.5px]">
                      <span className="flex h-[21px] w-[21px] items-center justify-center rounded-full" style={{ background: on ? '#0A84FF' : 'transparent', border: on ? 0 : '1.5px solid rgba(235,235,245,0.3)' }}>
                        {on ? <svg width="11" height="9" viewBox="0 0 11 9"><path d="M1 4.5 L4 7.5 L10 1.5" fill="none" stroke="#fff" strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round" /></svg> : null}
                      </span>
                      <span className="h-[22px] w-[22px] rounded-md" style={{ background: color }} />
                      <span className="flex-1">{name}</span>
                    </div>
                  ))}
                </div>
              </div>
            </div>
          )}

          {p.screen === 'shield' && (
            <div className="enter-shield absolute inset-0 z-[4] flex flex-col items-center px-[22px] pb-[34px] text-center" style={{ background: 'linear-gradient(180deg, #3A3A3C 0%, #2C2C2E 45%, #1C1C1E 100%)' }}>
              <div className="flex flex-1 flex-col items-center justify-center gap-1.5 pt-[30px]">
                <svg width="30" height="42" viewBox="0 0 30 42" className="mb-1.5" aria-hidden="true"><path d="M2 1 H28 V5 C28 13 18 17.5 17 21 C18 24.5 28 29 28 37 V41 H2 V37 C2 29 12 24.5 13 21 C12 17.5 2 13 2 5 Z" fill="#636366" /><path d="M15 29 L25.5 39 H4.5 Z" fill="#fff" /></svg>
                <div className="text-[27px] font-bold leading-[1.15] tracking-[-0.01em]">{SHIELD.title}</div>
                <div className="text-[14.5px] leading-[1.35]">{SHIELD.subtitle}</div>
              </div>
              <button type="button" onClick={p.onHome} className="press h-[42px] min-w-[136px] rounded-[21px] bg-[#0A84FF] px-7 text-[16px]">{SHIELD.primary}</button>
              <button type="button" onClick={p.onSnooze} className="mt-2 h-[30px] text-[14.5px]">{SHIELD.secondary}</button>
            </div>
          )}

          {p.screen === 'app' && <OpenedApp id={open} reason={p.openReason} onHome={p.onHome} />}

          <div className="absolute bottom-[7px] left-1/2 z-[6] -ml-[62px] h-[5px] w-[124px] rounded-[3px] bg-white/85" />
        </div>
      </div>
    </div>
  );
}

function OpenedApp({ id, reason, onHome, round = false }: { id: AppId; reason: string; onHome: () => void; round?: boolean }) {
  return (
    <div className="absolute inset-0 z-[4] flex flex-col items-center justify-center gap-3 bg-[#0E0E10]">
      <AppIcon id={id} size={76} round={round} />
      <div className="text-[22px] font-semibold">{APP_NAMES[id]}</div>
      <div className="text-[13.5px] text-white/70">{reason}</div>
      <button type="button" onClick={onHome} className="press mt-3.5 rounded-full bg-white/[0.14] px-[22px] py-[11px] text-[14px] font-semibold">Back to Home</button>
    </div>
  );
}

export function AndroidPhone(p: PhoneProps) {
  const open = p.openId ?? 'instagram';
  const granted = !!p.permGranted;
  return (
    <div className="relative h-[700px] w-[330px] text-[#E6E1E5]" style={{ fontFamily: "Roboto, 'Google Sans', var(--font-geist), system-ui, sans-serif" }}>
      <div className="absolute -right-[3px] top-[150px] h-11 w-1 rounded-sm bg-[#3A3A3E]" />
      <div className="absolute -right-[3px] top-[214px] h-[84px] w-1 rounded-sm bg-[#3A3A3E]" />
      <div className="absolute inset-0 rounded-[44px] bg-[#202124] shadow-[inset_0_0_0_2px_#3C4043,0_40px_80px_rgba(0,0,0,0.55)]">
        <div className="absolute inset-[9px] overflow-hidden rounded-[36px]" style={{ background: 'linear-gradient(170deg, #2D3E50 0%, #1E2A36 55%, #121A22 100%)' }}>
          <div className="absolute inset-x-0 top-0 z-[5] flex h-9 items-center justify-between px-[22px] pt-1 text-[13px] font-medium text-white">
            <span>9:41</span>
            <span className="flex items-center gap-[5px]">
              <svg width="13" height="11" viewBox="0 0 13 11" aria-hidden="true"><path d="M6.5 10.5 L0.5 3 A9.5 9.5 0 0 1 12.5 3 Z" fill="#fff" /></svg>
              <svg width="11" height="11" viewBox="0 0 11 11" aria-hidden="true"><path d="M10.5 0.5 V10.5 H0.5 Z" fill="#fff" /></svg>
              <svg width="8" height="13" viewBox="0 0 8 13" aria-hidden="true"><rect x="0.5" y="1.5" width="7" height="11" rx="1.5" fill="#fff" /><rect x="2.5" width="3" height="2" rx="0.5" fill="#fff" /></svg>
            </span>
          </div>
          <div className="absolute left-1/2 top-2.5 z-[6] -ml-1.5 h-3 w-3 rounded-full bg-black" />

          {p.screen === 'home' && (
            <>
              <div className="absolute left-[22px] top-14 flex flex-col gap-0.5 text-white">
                <span className="text-[17px]">Mon, Nov 9</span>
                <span className="text-[13px] opacity-85">12°C · Mostly cloudy</span>
              </div>
              <div className="absolute inset-x-0 bottom-[156px] grid grid-cols-4 gap-y-[18px] px-3">
                {GRID.map((id) => (
                  <button key={id} type="button" onClick={() => p.onOpen(id)} aria-label={`Open ${APP_NAMES[id]}`} className="relative flex flex-col items-center gap-1.5 text-white">
                    <AppIcon id={id} size={52} round />
                    <span className="text-[11.5px]">{APP_NAMES[id]}</span>
                    {p.ringId === id ? <TapRing top={26} /> : null}
                  </button>
                ))}
              </div>
              <div className="absolute inset-x-0 bottom-[78px] grid grid-cols-4 px-3">
                {(['dialer', 'gmessages', 'chrome', 'gmail'] as AppId[]).map((id) => (
                  <button key={id} type="button" onClick={() => p.onOpen(id)} aria-label={`Open ${APP_NAMES[id]}`} className="flex justify-center">
                    <AppIcon id={id} size={52} round />
                  </button>
                ))}
              </div>
              <div className="absolute inset-x-[18px] bottom-6 flex h-11 items-center gap-2.5 rounded-[22px] bg-[rgba(48,49,52,0.92)] px-4 text-[14px] text-[#BDC1C6]">
                <svg width="16" height="16" viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="5.2" fill="none" stroke="#BDC1C6" strokeWidth="1.8" /><path d="M11 11 L15 15" stroke="#BDC1C6" strokeWidth="1.8" strokeLinecap="round" /></svg>
                Search
              </div>
              {p.hint ? (
                <div className="absolute left-1/2 top-[118px] -translate-x-1/2 whitespace-nowrap rounded-full bg-black/40 px-3 py-1.5 text-[12px] font-medium text-white">{p.hint}</div>
              ) : null}
            </>
          )}

          {p.screen === 'perm' && (
            <div className="enter-sheet absolute inset-0 z-[4] flex flex-col bg-[#1C1B1F] pt-11">
              <div className="px-[18px] py-2.5">
                <svg width="18" height="18" viewBox="0 0 18 18" aria-hidden="true"><path d="M16 9 H3 M8 3 L2 9 L8 15" fill="none" stroke="#E6E1E5" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /></svg>
              </div>
              <div className="px-[22px] pb-[22px] pt-[18px] text-[30px]">Usage access</div>
              <div className="flex items-center gap-4 px-[22px] py-3.5">
                <AppIcon id="screentime" size={40} round />
                <span className="text-[17px]">Screen Time</span>
              </div>
              <div className="mx-4 mt-3 flex items-center justify-between rounded-[28px] bg-[#2B2930] px-5 py-[18px]">
                <span className="text-[17px]">Permit usage access</span>
                <span className="flex h-8 w-[52px] items-center rounded-2xl px-1 transition-colors" style={{ background: granted ? '#8AB4F8' : '#36343B', justifyContent: granted ? 'flex-end' : 'flex-start' }}>
                  <span className="h-6 w-6 rounded-xl" style={{ background: granted ? '#062E6F' : '#938F99' }} />
                </span>
              </div>
              <div className="px-[26px] py-[18px] text-[13.5px] leading-[1.45] text-[#CAC4D0]">Usage access allows an app to track what other apps you’re using and how often, as well as your carrier, language settings, and other details.</div>
            </div>
          )}

          {p.screen === 'picker' && (
            <div className="enter-sheet absolute inset-0 z-[4] flex flex-col bg-[#1C1B1F] pt-11">
              <div className="flex items-center justify-between px-5 pb-3.5 pt-2.5 text-[20px]">
                <span>Choose apps</span>
                <span className="text-[15px] font-medium text-[#8AB4F8]">Done</span>
              </div>
              <div className="mx-4 mb-2 flex h-11 items-center rounded-xl bg-[#2B2930] px-3.5 text-[15px] text-[#CAC4D0]">Search apps</div>
              {(['instagram', 'tiktok', 'x', 'youtube', 'netflix', 'reddit', 'spotify', 'whatsapp'] as AppId[]).map((id) => {
                const on = SELECTION.includes(id);
                return (
                  <div key={id} className="flex min-h-14 items-center py-2 pl-4 pr-2">
                    <AppIcon id={id} size={40} round />
                    <span className="ml-4 flex-1 text-[15.5px]">{APP_NAMES[id]}</span>
                    <span className="mx-3.5 flex h-[18px] w-[18px] items-center justify-center rounded-sm" style={{ background: on ? '#8AB4F8' : 'transparent', border: on ? 0 : '2px solid #CAC4D0' }}>
                      {on ? <svg width="12" height="10" viewBox="0 0 12 10"><path d="M1 5 L4.5 8.5 L11 1.5" fill="none" stroke="#1C1B1F" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" /></svg> : null}
                    </span>
                  </div>
                );
              })}
            </div>
          )}

          {p.screen === 'shield' && (
            <div className="enter-shield absolute inset-0 z-[4] flex flex-col items-center justify-center bg-[#1C1B1F] px-7 py-12 text-center">
              <AppIcon id={open} size={84} round />
              <div className="mt-[22px] text-[23px] font-bold leading-[1.2]">{SHIELD.title}</div>
              <div className="mt-2 text-[14.5px] leading-[1.4] text-[#CAC4D0]">{SHIELD.subtitle}</div>
              <button type="button" onClick={p.onHome} className="press mt-9 min-h-11 w-full rounded-[22px] bg-[#8AB4F8] text-[15px] font-bold text-black">{SHIELD.primary}</button>
              <button type="button" onClick={p.onSnooze} className="mt-2 min-h-11 w-full text-[15px] text-[#8AB4F8]">{SHIELD.secondary}</button>
            </div>
          )}

          {p.screen === 'app' && <OpenedApp id={open} reason={p.openReason} onHome={p.onHome} round />}

          <div className="absolute bottom-2 left-1/2 z-[6] -ml-[54px] h-1 w-[108px] rounded-sm bg-white/80" />
        </div>
      </div>
    </div>
  );
}
