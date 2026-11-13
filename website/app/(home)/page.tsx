import Link from 'next/link';
import { LogoMark } from '@/components/logo';
import { Install } from '@/components/landing/install';
import { Stage } from '@/components/landing/stage';
import { Schedule } from '@/components/landing/schedule';
import { Snippets } from '@/components/landing/snippets';
import { basePath, gitConfig, githubUrl } from '@/lib/shared';

async function getStars(): Promise<string | null> {
  try {
    const res = await fetch(`https://api.github.com/repos/${gitConfig.user}/${gitConfig.repo}`, {
      headers: process.env.GITHUB_TOKEN ? { Authorization: `Bearer ${process.env.GITHUB_TOKEN}` } : {},
    });
    if (!res.ok) return null;
    const { stargazers_count: n } = (await res.json()) as { stargazers_count: number };
    return n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k` : String(n);
  } catch {
    return null;
  }
}

const API: [string, [string, string][]][] = [
  ['Permissions', [['requestScreenTimePermissionsAsync()', 'api/permissions#requestscreentimepermissionsasync'], ['getScreenTimePermissionsAsync()', 'api/permissions#getscreentimepermissionsasync'], ['getCapabilities()', 'api/capabilities#getcapabilities']]],
  ['Blocking', [['blockSelection(id)', 'api/blocking#blockselection'], ['unblockAll()', 'api/blocking#unblockall'], ['blockWebDomains(domains)', 'api/blocking#blockwebdomains']]],
  ['Schedules', [['startMonitoring(config)', 'api/monitoring#startmonitoring'], ['stopMonitoring(name)', 'api/monitoring#stopmonitoring'], ['useMonitorEvents(cb)', 'api/monitoring#usemonitorevents']]],
  ['Shields', [['setShieldConfig(config)', 'api/shields#setshieldconfig'], ['setShieldActions(config)', 'api/shields#setshieldactions'], ['drainShieldInteractions()', 'api/blocking#drainshieldinteractions']]],
  ['Earned time', [['temporaryUnlock(seconds)', 'api/blocking#temporaryunlock'], ['getRemainingUnlockSeconds()', 'api/blocking#getremainingunlockseconds'], ['relock()', 'api/blocking#relock']]],
  ['Reports and gaps', [['<DeviceActivityReport />', 'components#device-activity-report'], ['setReportTemplate(<Report />)', 'api/templates#imperative-api'], ['getEnforcementGaps()', 'api/capabilities#getenforcementgaps']]],
];

const PILLARS = [
  ['No Xcode surgery', 'The config plugin generates the four Screen Time extensions at prebuild. Re-running prebuild changes nothing.'],
  ['Private by construction', 'Selections stay native. Your JavaScript holds an ID and opaque handles, never the apps someone picked.'],
  ['Honest on Android', 'Capabilities tell you what each device enforces, and every gap in enforcement is reported.'],
];

const h2 = 'm-0 font-mono text-[30px] font-medium leading-[1.08] tracking-[-0.05em] sm:text-[40px]';
const lead = 'm-0 text-[16.5px] leading-[1.6] text-mist';

export default async function HomePage() {
  const stars = await getStars();
  return (
    <>
      <header className="wrap flex items-center justify-between gap-4 py-[22px]">
        <Link href="/" className="flex items-center gap-2.5">
          <LogoMark />
          <span className="font-mono text-[14px] font-medium tracking-[-0.02em] sm:text-[16px]">expo-screen-time</span>
        </Link>
        <nav className="flex items-center gap-4 text-[14.5px] sm:gap-7">
          <Link href="/docs" className="text-mist hover:text-ink">Docs</Link>
          <Link href="/docs/api/importing" className="hidden text-mist hover:text-ink sm:inline">API</Link>
          <Link href="/docs/platform-support" className="hidden text-mist hover:text-ink md:inline">Platforms</Link>
          <a href={`${githubUrl}/releases`} className="hidden text-mist hover:text-ink md:inline">Changelog</a>
          <a href={githubUrl} aria-label="GitHub" className="flex h-[34px] items-center gap-2 rounded-[9px] px-2.5 shadow-[inset_0_0_0_1px_var(--color-raised)] sm:px-3">
            <svg width="15" height="15" viewBox="0 0 16 16" aria-hidden="true"><path d="M8 0.5a7.5 7.5 0 0 0-2.4 14.6c.4.1.5-.2.5-.4v-1.4c-2.1.5-2.5-.9-2.5-.9-.3-.9-.8-1.1-.8-1.1-.7-.5 0-.5 0-.5.8.1 1.2.8 1.2.8.7 1.2 1.8.8 2.2.6.1-.5.3-.8.5-1-1.7-.2-3.4-.8-3.4-3.7 0-.8.3-1.5.8-2-.1-.2-.4-1 .1-2 0 0 .6-.2 2.1.8a7 7 0 0 1 3.8 0c1.4-1 2.1-.8 2.1-.8.4 1 .2 1.8.1 2 .5.5.8 1.2.8 2 0 2.9-1.8 3.5-3.4 3.7.3.2.5.7.5 1.4v2.1c0 .2.1.5.5.4A7.5 7.5 0 0 0 8 .5z" fill="currentColor" /></svg>
            <span className="hidden sm:inline">GitHub</span>
            {stars ? (
              <span className="flex h-5 items-center gap-1 rounded-md bg-stage px-[7px] font-mono text-[12px] text-[#C9C8FF]">
                <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M6 0.8 L7.6 4.1 L11.2 4.6 L8.6 7.1 L9.2 10.7 L6 9 L2.8 10.7 L3.4 7.1 L0.8 4.6 L4.4 4.1 Z" fill="#FFD27A" /></svg>
                <span className="sr-only">Stars:</span>{stars}
              </span>
            ) : null}
          </a>
        </nav>
      </header>

      <section className="wrap grid gap-8 pb-14 pt-10 lg:grid-cols-12 lg:gap-6 lg:pt-20">
        <h1 className="m-0 font-mono text-[36px] font-medium leading-[1.04] tracking-[-0.055em] min-[380px]:text-[40px] sm:text-[56px] lg:col-span-7 lg:text-[68px] lg:leading-[1.02]">
          The Screen Time API for React Native.
        </h1>
        <div className="flex min-w-0 flex-col gap-[18px] lg:col-span-4 lg:col-start-9 lg:pt-2">
          <p className="m-0 text-[17px] leading-[1.55] text-mist">
            Block apps, run schedules and draw the shield on iOS and Android from one TypeScript API. Built for Expo. If you ship a focus, wellbeing or parental‑control app, this is the native layer.
          </p>
          <Install />
          <span className="text-[13px] text-dim">Requires Expo SDK 54+, iOS 16+ and Android 7+. MIT licensed.</span>
        </div>
      </section>

      <div className="wrap"><Stage /></div>

      <section className="wrap grid gap-8 pt-24 lg:grid-cols-12 lg:gap-x-6 lg:gap-y-10 lg:pt-[136px]">
        <h2 className={`${h2} lg:col-span-6`}>A schedule is one call. It keeps running when your app is closed.</h2>
        <p className={`${lead} self-end lg:col-span-5 lg:col-start-8`}>iOS enforces it from a DeviceActivity extension the config plugin generates. Android runs it in the module’s own service. Your code is the same.</p>
        <div className="lg:col-span-12"><Schedule /></div>
      </section>

      <section className="wrap grid gap-8 pt-24 lg:grid-cols-12 lg:gap-6 lg:pt-[136px]">
        <h2 className={`${h2} lg:col-span-6`}>The rest is a few lines each.</h2>
        <div className="lg:col-span-12"><Snippets /></div>
      </section>

      <section className="wrap flex flex-col gap-9 pt-24 lg:pt-[136px]">
        <div className="grid gap-6 lg:grid-cols-12">
          <h2 className={`${h2} lg:col-span-6`}>The same day, on two systems.</h2>
          <p className={`${lead} self-end lg:col-span-5 lg:col-start-8`}>On iOS the OS holds the shield. On Android the module’s engine does, and a user can stop it from Settings. When that happens, you are told exactly when.</p>
        </div>
        <figure className="m-0 flex flex-col gap-4 rounded-[20px] bg-panel p-5 shadow-[inset_0_0_0_1px_var(--color-line)] sm:p-8">
          {(['ios', 'android'] as const).map((os) => (
            <div key={os} className="grid grid-cols-[72px_minmax(0,1fr)] items-center gap-4 sm:grid-cols-[96px_minmax(0,1fr)] sm:gap-5">
              <span className="font-mono text-[13px] text-[#C9C8FF]">{os}</span>
              <div className="relative h-[34px] overflow-hidden rounded-lg bg-[#1C1B36]">
                <div className="absolute inset-y-0 left-[37.5%] w-[33.33%] bg-indigo" />
                {os === 'android' ? <div className="absolute inset-y-0 left-[58.68%] w-[2.43%]" style={{ background: 'repeating-linear-gradient(135deg, #FFD27A 0 4px, #0E0E1C 4px 8px)' }} /> : null}
              </div>
            </div>
          ))}
          <div className="grid grid-cols-[72px_minmax(0,1fr)] gap-4 sm:grid-cols-[96px_minmax(0,1fr)] sm:gap-5">
            <span />
            <div className="relative h-[18px]"><span className="absolute left-[59.9%] -translate-x-1/2 whitespace-nowrap font-mono text-[11.5px] text-sun">↑ 14:05–14:40<span className="hidden sm:inline"> user stopped the engine</span></span></div>
          </div>
          <div className="grid grid-cols-[72px_minmax(0,1fr)] gap-4 sm:grid-cols-[96px_minmax(0,1fr)] sm:gap-5">
            <span />
            <div className="flex justify-between font-mono text-[11.5px] text-faint"><span>00</span><span>06</span><span>12</span><span>18</span><span>24</span></div>
          </div>
          <figcaption className="flex flex-col gap-3 pt-2 text-[13.5px] text-mist sm:ml-[116px] sm:flex-row sm:gap-6">
            <span className="flex items-center gap-2"><span className="h-3.5 w-3.5 shrink-0 rounded-[3px] bg-indigo" />Shield in force (work hours schedule)</span>
            <span className="flex items-center gap-2"><span className="h-3.5 w-3.5 shrink-0 rounded-[3px]" style={{ background: 'repeating-linear-gradient(135deg, #FFD27A 0 3px, #0E0E1C 3px 6px)' }} />Not enforced: the user stopped the engine from Android Settings. iOS has no such switch.</span>
          </figcaption>
          <code className="overflow-x-auto whitespace-nowrap font-mono text-[13px] sm:ml-[116px] sm:text-[14px]">
            <span className="text-lilac">await</span> <span className="text-white">ExpoScreenTime</span>.<span className="text-[#7CE3FF]">getEnforcementGaps</span>(){' '}
            <span className="text-dim">{"// [{ from: …14:05, to: …14:40, reason: 'stoppedByUser' }]"}</span>
          </code>
        </figure>
      </section>

      <section className="wrap grid gap-10 pb-28 pt-24 lg:grid-cols-12 lg:gap-x-6 lg:gap-y-12 lg:pb-[120px] lg:pt-[136px]">
        {PILLARS.map(([title, body]) => (
          <div key={title} className="flex flex-col gap-2.5 lg:col-span-4">
            <h3 className="m-0 font-mono text-[17px] font-medium tracking-[-0.02em]">{title}</h3>
            <p className="m-0 text-[15.5px] leading-[1.6] text-mist">{body}</p>
          </div>
        ))}
        <div className="grid overflow-hidden rounded-[20px] shadow-[inset_0_0_0_1px_var(--color-line)] sm:grid-cols-2 lg:col-span-12 lg:grid-cols-3">
          {API.map(([group, fns]) => (
            <div key={group} className="flex min-w-0 flex-col gap-3 px-5 py-6 shadow-[inset_-1px_-1px_0_var(--color-line)] sm:px-[26px]">
              <span className="text-[14px] font-semibold">{group}</span>
              <div className="flex flex-col gap-1.5 font-mono text-[13px]">
                {fns.map(([label, path]) => (
                  <Link key={label} href={`/docs/${path}`} className="w-fit max-w-full break-all text-[#C9C8FF] underline decoration-stage-line underline-offset-4 hover:text-white hover:decoration-lilac">
                    {label}
                  </Link>
                ))}
              </div>
            </div>
          ))}
        </div>
      </section>

      <section className="bg-stage">
        <div className="wrap grid items-end gap-8 py-24 lg:grid-cols-12 lg:gap-6 lg:py-28">
          <h2 className="m-0 font-mono text-[38px] font-medium leading-[1.02] tracking-[-0.055em] sm:text-[56px] lg:col-span-7">Write it once. Ship it on both.</h2>
          <div className="flex flex-col gap-2.5 lg:col-span-4 lg:col-start-9">
            <Link href="/docs/installation" className="press flex h-[50px] items-center justify-center rounded-xl bg-ink text-[15px] font-semibold text-stage">Read the quick start</Link>
            <a href={`${basePath}/llms.txt`} className="press flex h-[50px] items-center justify-center rounded-xl text-[15px] font-medium shadow-[inset_0_0_0_1px_#5654B8]">Give your agent the docs (llms.txt)</a>
          </div>
        </div>
      </section>

      <footer className="wrap flex flex-col gap-3 py-7 text-[13px] text-dim sm:flex-row sm:justify-between">
        <span>MIT License. Not affiliated with Apple or Google.</span>
        <div className="flex gap-[22px] font-mono">
          <a href={githubUrl} className="text-mist hover:text-ink">github</a>
          <a href="https://www.npmjs.com/package/expo-screen-time" className="text-mist hover:text-ink">npm</a>
          <a href={`${basePath}/llms.txt`} className="text-mist hover:text-ink">llms.txt</a>
        </div>
      </footer>
    </>
  );
}
