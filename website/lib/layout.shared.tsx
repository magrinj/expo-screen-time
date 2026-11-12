import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { Wordmark } from '@/components/logo';
import { githubUrl } from './shared';

export function baseOptions(): BaseLayoutProps {
  return {
    nav: { title: <Wordmark /> },
    githubUrl,
  };
}
