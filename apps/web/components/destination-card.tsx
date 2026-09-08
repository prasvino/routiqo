'use client';
import Image from 'next/image';
import { Bookmark, ArrowUpRight } from 'lucide-react';
import type { Destination } from '@routiqo/shared';
import { usePlanning } from './planning-provider';
export function DestinationCard({ place, onOpen }: { place: Destination; onOpen: () => void }) {
  const { state, saveDestination, ready } = usePlanning();
  const saved = state.saved.includes(place.id);
  return (
    <article className="destination-card">
      <div className="destination-image">
        <button className="image-open" onClick={onOpen} aria-label={'Explore ' + place.name}>
          <Image
            src={place.image}
            alt={place.imageAlt}
            fill
            sizes="(max-width:600px) 90vw, (max-width:1100px) 45vw, 28vw"
          />
        </button>
        <span className="category-tag">{place.category}</span>
        <button
          className={'save-button ' + (saved ? 'saved' : '')}
          aria-label={(saved ? 'Unsave ' : 'Save ') + place.name}
          aria-pressed={saved}
          disabled={!ready}
          onClick={() => {
            try {
              saveDestination(place.id);
            } catch {
              /* provider exposes recoverable error */
            }
          }}
        >
          <Bookmark size={18} fill={saved ? 'currentColor' : 'none'} />
        </button>
      </div>
      <button className="destination-title" onClick={onOpen}>
        <h3>{place.name}</h3>
        <ArrowUpRight size={19} />
      </button>
      <p>{place.description}</p>
      <span className="destination-meta">
        {place.region} <span>·</span> {place.travelLabel}
      </span>
    </article>
  );
}
