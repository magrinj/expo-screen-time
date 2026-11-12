'use client';
import { useState } from 'react';
import { CodeCard, CodeLines } from './code';

const SNIPPETS = [
  {
    id: 'shield', title: 'Shield', file: 'shield.ts', platforms: 'iOS · Android',
    desc: 'Your words on the block screen, and what each button does.',
    code: [
      'ExpoScreenTime.setShieldConfig({',
      "  title: { text: 'Time to focus' },",
      "  subtitle: { text: 'This app is blocked during focus time.' },",
      "  primaryButton: { text: 'Close' },",
      "  secondaryButton: { text: 'Snooze 5 min' },",
      '});',
      '',
      'ExpoScreenTime.setShieldActions({',
      "  primaryAction: 'close',",
      "  secondaryAction: { type: 'snooze', duration: 5 * 60 },",
      '});',
    ],
  },
  {
    id: 'earned', title: 'Earned time', file: 'earned-time.ts', platforms: 'iOS · Android',
    desc: 'Give time back. The shield returns by itself.',
    code: [
      '// The user finished a workout: 15 minutes back.',
      'const grant = await ExpoScreenTime.temporaryUnlock(15 * 60);',
      '',
      '// Shielded apps open until the budget runs out,',
      '// then relock, even with your app closed.',
      'ExpoScreenTime.getRemainingUnlockSeconds(); // 900',
      'ExpoScreenTime.relock(); // or end it now',
    ],
  },
  {
    id: 'report', title: 'Usage report', file: 'Report.tsx', platforms: 'iOS · Android',
    desc: 'A report written in JSX, rendered natively. Usage data never reaches JS.',
    code: [
      '<DeviceActivityReport filterSegment="daily" style={{ height: 320 }}>',
      '  <Report.VStack spacing={14} padding={16}>',
      '    <Report.Text bind="totalDuration" size={30} weight="bold" />',
      '    <Report.Chart type="area" bind="hourly" height={90} />',
      '    <Report.TopApps limit={3}>',
      '      <Report.HStack spacing={10}>',
      '        <Report.AppIcon size={26} />',
      '        <Report.AppTitle size={14} />',
      '      </Report.HStack>',
      '    </Report.TopApps>',
      '  </Report.VStack>',
      '</DeviceActivityReport>',
    ],
  },
  {
    id: 'web', title: 'Web domains', file: 'web.ts', platforms: 'iOS',
    desc: 'Block websites by domain, no picker needed.',
    code: [
      "ExpoScreenTime.blockWebDomains(['tiktok.com', 'reddit.com']);",
      '',
      'ExpoScreenTime.getBlockedWebDomains();',
      "// ['tiktok.com', 'reddit.com']",
      '',
      'ExpoScreenTime.unblockAllWebDomains();',
    ],
  },
  {
    id: 'caps', title: 'Capabilities', file: 'capabilities.ts', platforms: 'iOS · Android',
    desc: 'Branch on what this device enforces, not on Platform.OS.',
    code: [
      'const { blocking, usageReports } = ExpoScreenTime.getCapabilities();',
      '',
      "// 'enforced' on iOS, 'friction' on Android",
      "if (blocking === 'friction') {",
      '  const gaps = await ExpoScreenTime.getEnforcementGaps();',
      '}',
    ],
  },
] as const;

export function Snippets() {
  const [active, setActive] = useState(0);
  const snip = SNIPPETS[active];
  return (
    <div className="grid gap-6 lg:grid-cols-12">
      <CodeCard file={snip.file} aside={<span>{snip.platforms}</span>} className="h-[380px] sm:h-[420px] lg:col-span-8 lg:h-[520px]">
        <div role="tabpanel" className="min-h-0 flex-1 overflow-auto">
          <p className="m-0 px-[18px] pt-4 text-[14px] leading-normal text-mist lg:hidden">{snip.desc}</p>
          <CodeLines lines={[...snip.code]} className="py-5 text-[12px] sm:text-[14px]" />
        </div>
      </CodeCard>
      <div role="tablist" aria-label="Examples" aria-orientation="vertical" className="order-first -mx-4 flex gap-1 overflow-x-auto px-4 [scrollbar-width:none] md:-mx-8 md:px-8 lg:order-none lg:col-span-4 lg:mx-0 lg:grid lg:h-[520px] lg:grid-rows-5 lg:px-0">
        {SNIPPETS.map((s, i) => (
          <button
            key={s.id}
            type="button"
            role="tab"
            aria-selected={i === active}
            onClick={() => setActive(i)}
            className="flex shrink-0 flex-col justify-center gap-1.5 rounded-[14px] px-4 py-2.5 text-left transition-colors lg:px-5 lg:py-3"
            style={{ background: i === active ? '#0E0E1C' : 'transparent', boxShadow: i === active ? 'inset 0 0 0 1px #34338A' : 'none' }}
          >
            <span className="font-mono text-[14px]" style={{ color: i === active ? '#A5A3FF' : '#F4F4FA' }}>{s.title}</span>
            <span className="hidden text-[14px] leading-normal text-mist lg:block">{s.desc}</span>
          </button>
        ))}
      </div>
    </div>
  );
}
