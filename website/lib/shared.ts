import { createGetUrl } from 'fumadocs-core/source';

export const appName = 'expo-screen-time';
export const tagline = 'The Screen Time API for React Native.';
export const description =
  'Block apps, run schedules and draw the shield on iOS and Android from one TypeScript API. Built for Expo.';
export const basePath = process.env.PAGES_BASE_PATH ?? '';
export const siteUrl = 'https://magrinj.github.io';
export const docsRoute = '/docs';
export const docsImageRoute = '/og/docs';
export const docsContentRoute = '/llms.mdx/docs';

export const gitConfig = {
  user: 'magrinj',
  repo: 'expo-screen-time',
  branch: 'main',
  docsDir: 'website/content/docs',
};
export const githubUrl = `https://github.com/${gitConfig.user}/${gitConfig.repo}`;

const getContentUrl = createGetUrl(docsContentRoute);

export function getPageMarkdownUrl(page: { slugs: string[]; locale?: string }) {
  const segments = [...page.slugs, 'content.md'];
  return { segments, url: getContentUrl(segments, page.locale) };
}

const getImageUrl = createGetUrl(docsImageRoute);

export function getPageImageUrl(page: { slugs: string[]; locale?: string }) {
  const segments = [...page.slugs, 'image.png'];
  return { segments, url: getImageUrl(segments, page.locale) };
}
