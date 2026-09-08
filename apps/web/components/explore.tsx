'use client';
import { useState } from 'react';
import { Search, SlidersHorizontal, ArrowRight } from 'lucide-react';
import { categories, searchDestinations, type Category, type Destination } from '@routiqo/shared';
import { DestinationCard } from './destination-card';
import { DestinationDialog } from './destination-dialog';
import { PlanDialog } from './plan-dialog';
export function Explore({ initialQuery }: { initialQuery: string }) {
  const [query, setQuery] = useState(initialQuery);
  const [category, setCategory] = useState<Category>('All');
  const [selected, setSelected] = useState<Destination | null>(null);
  const [planning, setPlanning] = useState<string | null>(null);
  const matches = searchDestinations(query, category);
  return (
    <div className="page">
      <section className="page-heading">
        <div>
          <span className="eyebrow">FOLLOW YOUR CURIOSITY</span>
          <h1>A little beyond the usual.</h1>
          <p>Coastal mornings, hill-town pauses, and stories in stone.</p>
        </div>
      </section>
      <div className="search-bar">
        <Search size={21} />
        <input
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Search places, experiences, or regions"
          aria-label="Search places"
        />
        {query && (
          <button onClick={() => setQuery('')} className="clear-search">
            Clear
          </button>
        )}
      </div>
      <div className="filter-row">
        <div className="filters" aria-label="Destination categories">
          {categories.map((item) => (
            <button key={item} aria-pressed={category === item} onClick={() => setCategory(item)}>
              {item}
            </button>
          ))}
        </div>
        <span className="result-count" role="status">
          <SlidersHorizontal size={15} />
          {matches.length} places
        </span>
      </div>
      {matches.length ? (
        <div className="destination-grid explore-grid">
          {matches.map((place) => (
            <DestinationCard place={place} key={place.id} onOpen={() => setSelected(place)} />
          ))}
        </div>
      ) : (
        <div className="empty-state">
          <Search size={30} />
          <h2>No places found.</h2>
          <p>Try a different place or explore all our destinations.</p>
          <button
            className="button secondary"
            onClick={() => {
              setQuery('');
              setCategory('All');
            }}
          >
            Explore all
            <ArrowRight size={17} />
          </button>
        </div>
      )}
      <p className="collection-note">
        A small, curated collection to begin with. More roads ahead.
      </p>
      {selected && (
        <DestinationDialog
          place={selected}
          onClose={() => setSelected(null)}
          onPlan={() => {
            setPlanning(selected.name);
            setSelected(null);
          }}
        />
      )}
      {planning !== null && <PlanDialog destination={planning} onClose={() => setPlanning(null)} />}
    </div>
  );
}
