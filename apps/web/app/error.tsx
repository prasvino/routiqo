'use client';
export default function ErrorPage({ reset }: { reset: () => void }) {
  return (
    <div className="page empty-state">
      <h1>A small detour.</h1>
      <p>This screen could not load. Your saved plans remain on this device.</p>
      <button className="button primary" onClick={reset}>
        Try again
      </button>
    </div>
  );
}
