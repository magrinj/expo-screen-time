export default function Layout({ children }: LayoutProps<'/'>) {
  return <main className="dark flex flex-1 flex-col bg-night text-ink">{children}</main>;
}
