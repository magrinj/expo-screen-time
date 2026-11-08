import type * as Preset from '@docusaurus/preset-classic';
import type {Config} from '@docusaurus/types';
import {themes as prismThemes} from 'prism-react-renderer';

// This runs in Node.js - Don't use client-side code here (browser APIs, JSX...)

const config: Config = {
  title: 'expo-screen-time',
  tagline: "App blocking, schedules, shields and usage reports for Expo, on iOS (Screen Time) and Android",
  favicon: 'img/favicon.ico',

  future: {
    v4: true, // Improve compatibility with the upcoming Docusaurus v4
  },

  // GitHub Pages: https://magrinj.github.io/expo-screen-time/
  url: 'https://magrinj.github.io',
  baseUrl: '/expo-screen-time/',
  organizationName: 'magrinj',
  projectName: 'expo-screen-time',
  trailingSlash: false,

  onBrokenLinks: 'throw',

  // .md → CommonMark (safe for migrated README prose), .mdx → MDX/JSX.
  markdown: {
    format: 'detect',
    hooks: {
      onBrokenMarkdownLinks: 'throw',
    },
  },

  i18n: {
    defaultLocale: 'en',
    locales: ['en'],
  },

  presets: [
    [
      'classic',
      {
        docs: {
          routeBasePath: '/', // docs at the site root (no /docs prefix)
          sidebarPath: './sidebars.ts',
          editUrl: 'https://github.com/magrinj/expo-screen-time/tree/main/website/',
        },
        blog: false,
        theme: {
          customCss: './src/css/custom.css',
        },
      } satisfies Preset.Options,
    ],
  ],

  themeConfig: {
    colorMode: {
      respectPrefersColorScheme: true,
    },
    navbar: {
      title: 'expo-screen-time',
      items: [
        {
          href: 'https://github.com/magrinj/expo-screen-time',
          label: 'GitHub',
          position: 'right',
        },
        {
          href: 'https://www.npmjs.com/package/expo-screen-time',
          label: 'npm',
          position: 'right',
        },
      ],
    },
    footer: {
      style: 'dark',
      links: [
        {
          title: 'Docs',
          items: [
            {label: 'Introduction', to: '/'},
            {label: 'Installation', to: '/installation'},
            {label: 'API Reference', to: '/api/permissions'},
          ],
        },
        {
          title: 'More',
          items: [
            {label: 'GitHub', href: 'https://github.com/magrinj/expo-screen-time'},
            {label: 'npm', href: 'https://www.npmjs.com/package/expo-screen-time'},
          ],
        },
      ],
      copyright: 'Built with Docusaurus. MIT licensed.',
    },
    prism: {
      theme: prismThemes.github,
      darkTheme: prismThemes.dracula,
      additionalLanguages: ['bash', 'json', 'swift', 'ruby'],
    },
  } satisfies Preset.ThemeConfig,
};

export default config;
