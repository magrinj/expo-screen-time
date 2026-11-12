'use client';
import { useState } from 'react';

const COMMANDS = {
  expo: ['npx', 'expo install', 'expo-screen-time'],
  npm: ['npm', 'install', 'expo-screen-time'],
  bun: ['bun', 'add', 'expo-screen-time'],
  yarn: ['yarn', 'add', 'expo-screen-time'],
} as const;
type Manager = keyof typeof COMMANDS;

export function Install() {
  const [pm, setPm] = useState<Manager>('expo');
  const [copied, setCopied] = useState(false);
  const [bin, verb, pkg] = COMMANDS[pm];

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(`${bin} ${verb} ${pkg}`);
      setCopied(true);
      setTimeout(() => setCopied(false), 1600);
    } catch {}
  };

  return (
    <div className="overflow-hidden rounded-xl bg-panel shadow-[inset_0_0_0_1px_var(--color-line)]">
      <div role="tablist" aria-label="Package manager" className="flex gap-0.5 px-2 pt-1.5 shadow-[inset_0_-1px_0_var(--color-line)]">
        {(Object.keys(COMMANDS) as Manager[]).map((k) => (
          <button
            key={k}
            type="button"
            role="tab"
            aria-selected={pm === k}
            onClick={() => { setPm(k); setCopied(false); }}
            className="h-8 border-b-2 px-2.5 font-mono text-[12.5px] transition-colors"
            style={{ borderColor: pm === k ? '#A5A3FF' : 'transparent', color: pm === k ? '#F4F4FA' : '#6F6E9E' }}
          >
            {k}
          </button>
        ))}
      </div>
      <div className="flex h-[54px] items-center justify-between gap-2.5 pl-3.5 pr-1.5">
        <code className="overflow-x-auto whitespace-nowrap font-mono text-[14px] sm:text-[14.5px]">
          <span className="text-dim">$ </span>
          <span className="text-lilac">{bin}</span> {verb} <span className="text-sun">{pkg}</span>
        </code>
        <button type="button" onClick={copy} className="press h-10 shrink-0 rounded-lg bg-line px-3 text-[13px] font-medium">
          {copied ? 'Copied' : 'Copy'}
        </button>
      </div>
    </div>
  );
}
