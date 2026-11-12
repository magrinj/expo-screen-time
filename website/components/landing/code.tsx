import type { ReactNode } from 'react';
import { tokenize } from '@/lib/tokenize';

export function CodeLines({
  lines,
  highlight = [],
  caretLine,
  className = '',
}: {
  lines: string[];
  highlight?: number[];
  caretLine?: number;
  className?: string;
}) {
  return (
    <div className={`flex font-mono leading-[1.85] ${className}`}>
      <div aria-hidden="true" className="w-11 shrink-0 select-none pr-4 text-right text-faint">
        {lines.map((_, i) => (
          <div key={i}>{i + 1}</div>
        ))}
      </div>
      <pre className="m-0 min-w-0 flex-1 overflow-x-auto pr-4 font-mono">
        {lines.map((line, i) => (
          <div
            key={i}
            className="-ml-2 min-h-[1.85em] rounded pl-2"
            style={{ background: highlight.includes(i + 1) ? 'rgba(94,92,230,0.16)' : 'transparent' }}
          >
            {tokenize(line).map((tok, j) => (
              <span key={j} style={{ color: tok.c }}>
                {tok.t}
              </span>
            ))}
            {caretLine === i ? <span className="caret ml-px inline-block h-[1.1em] w-0.5 bg-lilac align-[-0.15em]" /> : null}
          </div>
        ))}
      </pre>
    </div>
  );
}

export function CodeCard({ file, aside, children, className = '' }: { file: string; aside?: ReactNode; children: ReactNode; className?: string }) {
  return (
    <div className={`flex flex-col overflow-hidden rounded-[20px] bg-panel shadow-[inset_0_0_0_1px_var(--color-line)] ${className}`}>
      <div className="flex h-10 shrink-0 items-center justify-between px-[18px] font-mono text-[12.5px] text-mist shadow-[inset_0_-1px_0_var(--color-line)]">
        <span>{file}</span>
        {aside}
      </div>
      {children}
    </div>
  );
}
