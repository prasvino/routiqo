import '@routiqo/design-tokens/css';
import './style.css';
export const metadata = { title: 'Routiqo · Moderation', robots: { index: false, follow: false } };
export default function Layout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
