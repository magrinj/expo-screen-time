'use client';
import { useEffect, useState } from 'react';
import { gitConfig } from '@/lib/shared';

function format(n: number): string {
  return n >= 1000 ? `${(n / 1000).toFixed(n >= 10000 ? 0 : 1)}k` : String(n);
}

// Fetched in the browser: the site is a static export, so a build-time count
// would stay frozen until the next deploy. Hidden until there is a star to show.
export function Stars() {
  const [stars, setStars] = useState<number | null>(null);

  useEffect(() => {
    fetch(`https://api.github.com/repos/${gitConfig.user}/${gitConfig.repo}`)
      .then((res) => (res.ok ? res.json() : null))
      .then((repo: { stargazers_count?: number } | null) => setStars(repo?.stargazers_count ?? null))
      .catch(() => {});
  }, []);

  if (!stars) return null;
  return (
    <span className="flex h-5 items-center gap-1 rounded-md bg-stage px-[7px] font-mono text-[12px] text-[#C9C8FF]">
      <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden="true"><path d="M6 0.8 L7.6 4.1 L11.2 4.6 L8.6 7.1 L9.2 10.7 L6 9 L2.8 10.7 L3.4 7.1 L0.8 4.6 L4.4 4.1 Z" fill="#FFD27A" /></svg>
      <span className="sr-only">Stars:</span>{format(stars)}
    </span>
  );
}
