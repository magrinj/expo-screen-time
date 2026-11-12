import type { Metadata, Viewport } from 'next';
import { Geist, Geist_Mono } from 'next/font/google';
import { Provider } from '@/components/provider';
import { appName, basePath, description, siteUrl, tagline } from '@/lib/shared';
import './global.css';

const geist = Geist({ subsets: ['latin'], variable: '--font-geist' });
const geistMono = Geist_Mono({ subsets: ['latin'], variable: '--font-geist-mono' });

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: { default: `${appName}: ${tagline}`, template: `%s | ${appName}` },
  description,
  icons: { icon: `${basePath}/icon.svg` },
};

export const viewport: Viewport = { themeColor: '#000000' };

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <html lang="en" className={`${geist.variable} ${geistMono.variable}`} suppressHydrationWarning>
      <body className="flex min-h-screen flex-col font-sans">
        <Provider>{children}</Provider>
      </body>
    </html>
  );
}
