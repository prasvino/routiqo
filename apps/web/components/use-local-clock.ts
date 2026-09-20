'use client';
import { useEffect, useState } from 'react';

export function useLocalClock(): Date | null {
  const [now, setNow] = useState<Date | null>(null);

  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;

    const clear = () => {
      if (timer !== null) {
        clearTimeout(timer);
        timer = null;
      }
    };

    const schedule = (current: Date) => {
      clear();
      if (document.visibilityState === 'hidden') return;
      const msIntoMinute = current.getSeconds() * 1000 + current.getMilliseconds();
      const delay = Math.max(1, 60_000 - msIntoMinute);
      timer = setTimeout(() => {
        const next = new Date();
        setNow(next);
        schedule(next);
      }, delay);
    };

    const refresh = () => {
      const current = new Date();
      setNow(current);
      schedule(current);
    };

    refresh();

    const onVisibilityChange = () => {
      clear();
      if (document.visibilityState === 'visible') {
        refresh();
      }
    };

    document.addEventListener('visibilitychange', onVisibilityChange);

    return () => {
      clear();
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
  }, []);

  return now;
}
