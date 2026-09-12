'use client';
import { useRef, useState } from 'react';
import type { RouteResult } from '@routiqo/shared';
import { RouteMap } from './route-map';

export function RouteResults({ result }: { result: RouteResult }) {
  const [choice, setChoice] = useState(0);
  const [stepIndex, setStepIndex] = useState(0);
  const currentStep = useRef<HTMLDivElement>(null);
  const route = result.routes[choice];
  if (!route) return null;
  const steps = route.steps ?? [];
  const step = steps[stepIndex];
  return (
    <section className="route-estimates" aria-label="Calculated route">
      <h3>Route estimates</h3>
      <div className="route-options" aria-label="Route alternatives">
        {result.routes.map((option, index) => (
          <button
            type="button"
            className="button secondary"
            aria-pressed={choice === index}
            key={index}
            onClick={() => {
              if (choice === index) return;
              setChoice(index);
              setStepIndex(0);
            }}
          >
            <span>Route {index + 1}</span>
            <strong>
              {(option.distanceMetres / 1000).toLocaleString('en-IN', { maximumFractionDigits: 1 })}{' '}
              km
            </strong>
            <span>About {Math.max(1, Math.round(option.durationSeconds / 60))} min</span>
          </button>
        ))}
      </div>
      <p className="fine-print">
        Last calculated {new Date(result.calculatedAt).toLocaleString('en-IN')}. Mapbox estimates;
        check road signs and current conditions. No live traffic updates.
      </p>
      <RouteMap geometry={route.geometry} />
      <div className="route-directions">
        <h3>Directions</h3>
        {step ? (
          <>
            <p className="fine-print">
              Review steps before travelling. This view does not announce turns or follow your
              position.
            </p>
            <div
              ref={currentStep}
              tabIndex={-1}
              role="status"
              aria-live="polite"
              className="route-current-step"
            >
              <span>
                Step {stepIndex + 1} of {steps.length}
              </span>
              <p>{step.instruction}</p>
              <span>{Math.round(step.distanceMetres).toLocaleString('en-IN')} m</span>
            </div>
            <div className="route-step-actions">
              <button
                type="button"
                className="button secondary"
                disabled={stepIndex === 0}
                onClick={() => setStepIndex((value) => value - 1)}
              >
                Previous step
              </button>
              <button
                type="button"
                className="button secondary"
                disabled={stepIndex >= steps.length - 1}
                onClick={() => setStepIndex((value) => value + 1)}
              >
                Next step
              </button>
            </div>
            <details>
              <summary>All directions</summary>
              <ol>
                {steps.map((item, index) => (
                  <li key={index}>
                    <button
                      type="button"
                      className="button secondary route-step-choice"
                      aria-current={stepIndex === index ? 'step' : undefined}
                      onClick={() => {
                        setStepIndex(index);
                        currentStep.current?.focus();
                      }}
                    >
                      Step {index + 1}: {item.instruction} ·{' '}
                      {Math.round(item.distanceMetres).toLocaleString('en-IN')} m
                    </button>
                  </li>
                ))}
              </ol>
            </details>
          </>
        ) : (
          <p>Turn instructions are unavailable for this result. Calculate again when connected.</p>
        )}
      </div>
      <p className="fine-print">
        Keep this view open to review loaded directions during connection loss. Closing or reloading
        loses the route; offline maps and rerouting are not downloaded.
      </p>
    </section>
  );
}
