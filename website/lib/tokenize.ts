// A tiny TS/TSX highlighter for the landing page's live code. It runs on
// partially typed lines (the stage types its code), which is why it is not shiki.

export type Token = { t: string; c: string };

export const SYNTAX = {
  base: '#F4F4FA',
  kw: '#A5A3FF',
  str: '#FFD27A',
  num: '#FFB86B',
  com: '#6F6E9E',
  type: '#FFFFFF',
  fn: '#7CE3FF',
  tag: '#FF8FB1',
  prop: '#C9C8FF',
} as const;

const RE =
  /(\/\/.*$)|('[^']*'?|"[^"]*"?)|(\b\d+\b)|(\b(?:import|from|await|const|true|false|if)\b)|(<\/?[A-Z][\w.]*)|(\b[A-Z]\w*\b)|(\b[A-Za-z_]\w*(?=\())|(\b[a-z]\w*(?=[=:]))/g;

export function tokenize(line: string): Token[] {
  const toks: Token[] = [];
  let last = 0;
  RE.lastIndex = 0;
  for (let m = RE.exec(line); m; m = RE.exec(line)) {
    if (m.index > last) toks.push({ t: line.slice(last, m.index), c: SYNTAX.base });
    const c = m[1] ? SYNTAX.com : m[2] ? SYNTAX.str : m[3] ? SYNTAX.num : m[4] ? SYNTAX.kw : m[5] ? SYNTAX.tag : m[6] ? SYNTAX.type : m[7] ? SYNTAX.fn : SYNTAX.prop;
    toks.push({ t: m[0], c });
    last = RE.lastIndex;
  }
  if (last < line.length) toks.push({ t: line.slice(last), c: SYNTAX.base });
  return toks;
}
