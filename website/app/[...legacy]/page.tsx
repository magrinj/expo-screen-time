import Link from 'next/link';
import { basePath } from '@/lib/shared';

// Old Docusaurus URLs (/api/blocking, ...) redirect to /docs.
const LEGACY = [
  'installation', 'configuration', 'platform-support', 'components', 'types', 'troubleshooting',
  'api/importing', 'api/permissions', 'api/selections', 'api/blocking', 'api/restrictions',
  'api/monitoring', 'api/shields', 'api/templates', 'api/capabilities',
];

export const dynamicParams = false;

export function generateStaticParams() {
  return LEGACY.map((path) => ({ legacy: path.split('/') }));
}

export default async function Legacy(props: PageProps<'/[...legacy]'>) {
  const { legacy } = await props.params;
  const target = `${basePath}/docs/${legacy.join('/')}/`;
  return (
    <main className="flex flex-1 items-center justify-center p-8 text-center">
      <meta httpEquiv="refresh" content={`0; url=${target}`} />
      <script dangerouslySetInnerHTML={{ __html: `location.replace(${JSON.stringify(target)} + location.hash)` }} />
      <p>
        This page moved to <Link href={`/docs/${legacy.join('/')}`}>/docs/{legacy.join('/')}</Link>.
      </p>
    </main>
  );
}
