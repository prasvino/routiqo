'use client';
import Image from 'next/image';
import { ArrowRight, Bookmark, MapPin } from 'lucide-react';
import type { Destination } from '@routiqo/shared';
import { Modal } from './modal';
import { usePlanning } from './planning-provider';
export function DestinationDialog({
  place,
  onClose,
  onPlan,
}: {
  place: Destination;
  onClose: () => void;
  onPlan: () => void;
}) {
  const { state, saveDestination, ready } = usePlanning();
  const saved = state.saved.includes(place.id);
  return (
    <Modal title={place.name} onClose={onClose} wide>
      <div className="detail-photo">
        <Image src={place.image} alt={place.imageAlt} fill sizes="(max-width:650px) 95vw, 640px" />
      </div>
      <div className="detail-body">
        <span className="eyebrow">
          <MapPin size={14} />
          {place.region} · {place.category}
        </span>
        <h3 className="serif">{place.description}</h3>
        <p>{place.detail}</p>
        <div className="highlight-list">
          {place.highlights.map((highlight) => (
            <span key={highlight}>{highlight}</span>
          ))}
        </div>
        <p className="fine-print">
          Inspiration for your plan. Check access, opening hours, road and weather conditions before
          travel. Photography is illustrative.
        </p>
        <div className="detail-actions">
          <button className="button primary" onClick={onPlan}>
            Plan a journey
            <ArrowRight size={17} />
          </button>
          <button
            className="button secondary"
            disabled={!ready}
            onClick={() => {
              try {
                saveDestination(place.id);
              } catch {
                /* error shown in provider */
              }
            }}
          >
            <Bookmark size={17} fill={saved ? 'currentColor' : 'none'} />
            {saved ? 'Saved' : 'Save place'}
          </button>
        </div>
      </div>
    </Modal>
  );
}
