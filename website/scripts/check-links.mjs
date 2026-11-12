// Fails on links to missing docs pages or anchors, in the MDX and the landing page.
import { readFileSync, readdirSync, existsSync, statSync } from 'node:fs';
import { dirname, join, relative, resolve } from 'node:path';
import GithubSlugger from 'github-slugger';
import { fromMarkdown } from 'mdast-util-from-markdown';
import { gfmFromMarkdown } from 'mdast-util-gfm';
import { mdxFromMarkdown } from 'mdast-util-mdx';
import { toString } from 'mdast-util-to-string';
import { gfm } from 'micromark-extension-gfm';
import { mdxjs } from 'micromark-extension-mdxjs';

const root = resolve(import.meta.dirname, '..');
const docsDir = join(root, 'content/docs');
const HEADING_ID = /\s*\[#([^\]]+)\]\s*$/;

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name);
    return statSync(path).isDirectory() ? walk(path) : path.endsWith('.mdx') ? [path] : [];
  });
}

function visit(node, fn) {
  fn(node);
  for (const child of node.children ?? []) visit(child, fn);
}

const pages = new Map(); // file -> { anchors, links }
for (const file of walk(docsDir)) {
  const tree = fromMarkdown(readFileSync(file, 'utf8'), {
    extensions: [mdxjs(), gfm()],
    mdastExtensions: [mdxFromMarkdown(), gfmFromMarkdown()],
  });
  const slugger = new GithubSlugger();
  const anchors = new Set();
  const links = [];
  visit(tree, (node) => {
    if (node.type === 'heading') {
      const text = toString(node);
      const custom = HEADING_ID.exec(text);
      anchors.add(custom ? custom[1] : slugger.slug(text));
    }
    if (node.type === 'link') links.push({ url: node.url, line: node.position?.start.line });
  });
  pages.set(file, { anchors, links });
}

/** A docs URL path (`api/blocking`) or file path to its .mdx file. */
function resolvePage(target) {
  const clean = target.replace(/\.mdx?$/, '').replace(/\/$/, '');
  for (const candidate of [`${clean}.mdx`, join(clean, 'index.mdx')]) if (existsSync(candidate)) return candidate;
  return null;
}

const errors = [];
function check(fromLabel, fromFile, url) {
  if (/^[a-z]+:/i.test(url) || url.startsWith('//')) return; // external
  const [path, anchor] = url.split('#');
  let file = fromFile;
  if (path) {
    if (path.startsWith('/')) {
      if (!path.startsWith('/docs')) return; // site routes outside the docs (/, /llms.txt)
      file = resolvePage(join(docsDir, path.slice('/docs'.length)));
    } else {
      file = resolvePage(resolve(dirname(fromFile), path));
    }
    if (!file) return errors.push(`${fromLabel}: ${url} → no such page`);
  }
  if (anchor && !pages.get(file)?.anchors.has(anchor)) errors.push(`${fromLabel}: ${url} → no #${anchor} in ${relative(root, file)}`);
}

for (const [file, { links }] of pages) {
  for (const { url, line } of links) check(`${relative(root, file)}:${line}`, file, url);
}

// The landing page links each API function to its docs entry.
const landing = join(root, 'app/(home)/page.tsx');
for (const [, path] of readFileSync(landing, 'utf8').matchAll(/'((?:api\/|components)[\w/-]*#[\w-]+)'/g)) {
  check('app/(home)/page.tsx', landing, `/docs/${path}`);
}

if (errors.length) {
  console.error(`Broken docs links (${errors.length}):\n  ${errors.join('\n  ')}`);
  process.exit(1);
}
console.log(`Docs links OK (${pages.size} pages).`);
