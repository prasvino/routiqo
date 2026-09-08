import Link from 'next/link';
export default function NotFound() {
  return (
    <div className="page empty-state">
      <h1>A little off route.</h1>
      <p>This page isn’t here. Let’s find your way back.</p>
      <Link className="button primary" href="/">
        Back to Home
      </Link>
    </div>
  );
}
