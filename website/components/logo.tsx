export function LogoMark({ size = 28, tile = '#1E1D4A' }: { size?: number; tile?: string }) {
  return (
    <svg width={size} height={size} viewBox="0 0 64 64" aria-hidden="true">
      <rect width="64" height="64" rx="15" fill={tile} />
      <path d="M17 15 L32 30 L47 15" fill="none" stroke="#F4F4FA" strokeWidth="5" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M17 49 L32 34 L47 49 Z" fill="#A5A3FF" stroke="#A5A3FF" strokeWidth="5" strokeLinejoin="round" />
    </svg>
  );
}

export function Wordmark() {
  return (
    <span className="flex items-center gap-2.5">
      <LogoMark />
      <span className="font-mono text-[15px] font-medium tracking-[-0.02em]">expo-screen-time</span>
    </span>
  );
}
