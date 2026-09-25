import { useRouter } from 'expo-router';
import { useNativeAccount } from '../../auth/native-account-provider';
import {
  currentJourney,
  elapsedLabel,
  journeyMapEnabled,
  journeyTitle,
} from './journey-mode-model';
import { useMinuteClock } from './journey-mode-screen';
import { ActiveJourneyCard, JourneyReturnBar } from './journey-mode-view';

const enabled = journeyMapEnabled();

function useJourneyEntry() {
  const session = useNativeAccount();
  const router = useRouter();
  const now = useMinuteClock();
  const journey = enabled && session.accountId ? currentJourney(session.partition) : null;
  if (!journey) return null;
  return {
    title: journeyTitle(journey),
    elapsed: elapsedLabel(journey.startedAt, now),
    onOpen: () => router.push('/journey'),
  };
}

/** Persistent bar on every tab while a journey is current (flag on only). */
export function JourneyReturnBarContainer() {
  const entry = useJourneyEntry();
  return entry ? <JourneyReturnBar {...entry} /> : null;
}

/** Home card for the current journey (flag on only). */
export function ActiveJourneyCardContainer() {
  const entry = useJourneyEntry();
  return entry ? <ActiveJourneyCard {...entry} /> : null;
}
