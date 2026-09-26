import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { randomUUID } from 'expo-crypto';
import { tokens } from '@routiqo/design-tokens';
import {
  SpotOutboxFull,
  type Spot,
  type SpotActivityEntry,
  type SpotCategory,
  type SpotPostType,
  type SpotReportReason,
  type SpotVote,
} from '@routiqo/shared';
import { SpotOutboxUnavailable } from '../../storage/spot-outbox';
import { NativeSpotsError } from './native-spots';
import { GhostModeOn, useSpotContributions } from './spot-contributions-provider';
import { spotDetailModel } from './spot-detail-model';
import {
  GhostModeSwitch,
  SpotDetailContent,
  SpotPostComposer,
  SpotReportSheet,
} from './spot-detail-view';

type Action = 'vote' | 'report' | 'block' | 'delete' | 'queue';

/** No Spot or journey to attach a contribution to. */
export class NoJourneyForSpot extends Error {
  constructor() {
    super('Posting needs an active journey.');
    this.name = 'NoJourneyForSpot';
  }
}

/** What the traveller is told after an action fails; never a raw status or server text. */
export function contributionMessage(error: unknown, action: Action): string {
  if (error instanceof GhostModeOn) return 'Ghost Mode is on. Nothing was sent.';
  if (error instanceof NoJourneyForSpot) return error.message;
  if (error instanceof SpotOutboxFull) return error.message;
  if (error instanceof SpotOutboxUnavailable) return 'This could not be saved on your phone.';
  if (error instanceof NativeSpotsError) {
    switch (error.code) {
      case 'rate-limited':
        return action === 'report'
          ? 'You have reached the report limit for now.'
          : action === 'block'
            ? 'Too many blocks at once. Try again in a minute.'
            : 'Too many at once. Try again in a minute.';
      case 'forbidden':
        return action === 'vote'
          ? "You can't vote on your own post."
          : "You can't do that right now.";
      case 'not-found':
        return 'That is no longer here.';
      // 409 means different things per action; for votes it is "no active journey".
      case 'conflict':
        return action === 'report'
          ? 'You already reported this.'
          : action === 'block'
            ? 'Your block list is full.'
            : action === 'vote'
              ? 'Voting needs an active journey.'
              : "That couldn't be done now.";
      case 'session':
        return 'Sign in from Profile to continue.';
      default:
        return "Couldn't reach Routiqo. Try again when you're online.";
    }
  }
  return "Couldn't reach Routiqo. Try again when you're online.";
}

export interface SpotContributionControlsInput {
  spot: Spot | null;
  entry: SpotActivityEntry | null;
  journeyId: string | null;
  online: boolean;
  now: number;
  /** Re-read activity soon after the traveller's own action. */
  refreshSoon(): void;
}

/**
 * Contribution UI for the Spots panel: the Ghost Mode switch and queue notices above the list,
 * and the open Spot's content and actions. Returns nothing when contributions are off.
 */
export function useSpotContributionControls(input: SpotContributionControlsInput): {
  header?: ReactNode;
  content?: ReactNode;
} {
  const contributions = useSpotContributions();
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [reporting, setReporting] = useState<string | null>(null);
  const [writing, setWriting] = useState(false);
  const [ghostNote, setGhostNote] = useState<string | null>(null);
  const spotId = input.spot?.id ?? null;
  const refreshSoon = useRef(input.refreshSoon);
  refreshSoon.current = input.refreshSoon;
  const queuedCount = contributions?.outbox.entries.length ?? 0;
  const previousQueued = useRef(queuedCount);
  useEffect(() => {
    // Something left the queue (sent or dropped): show the server's view soon.
    // Something left the queue: stop saying "Sending…" (a refusal arrives as its own notice).
    if (queuedCount < previousQueued.current) {
      refreshSoon.current();
      setMessage((current) => (current?.startsWith('Sending') ? null : current));
    }
    previousQueued.current = queuedCount;
  }, [queuedCount]);
  useEffect(() => setMessage(null), [spotId]);
  if (!contributions) return {};

  const run = async (action: Action, work: () => Promise<unknown>, done: string | null) => {
    if (action !== 'queue' && !input.online) {
      setMessage("You're offline. Try again when you're back online.");
      return;
    }
    setBusy(true);
    try {
      await work();
      setMessage(done);
      if (action !== 'queue') refreshSoon.current();
    } catch (error) {
      setMessage(contributionMessage(error, action));
    } finally {
      setBusy(false);
    }
  };
  const queue = (
    contribution: { category: SpotCategory; value: string } | { type: SpotPostType; text: string },
  ) => {
    if (!input.spot || !input.journeyId) return Promise.reject(new NoJourneyForSpot());
    const common = {
      clientKey: randomUUID(),
      spotId: input.spot.id,
      journeyId: input.journeyId,
      capturedAt: new Date().toISOString(),
    };
    return contributions.queue(
      'category' in contribution
        ? { ...common, kind: 'signal', ...contribution }
        : { ...common, kind: 'post', ...contribution },
    );
  };

  const header = (
    <View>
      <GhostModeSwitch
        ghost={contributions.ghost}
        onChange={(on) =>
          void contributions
            .setGhost(on)
            .then((note) => note && setGhostNote(note))
            .catch(() => setGhostNote('Ghost Mode could not be changed.'))
        }
      />
      {ghostNote ? (
        <View style={styles.notice}>
          <Text style={styles.noticeText} accessibilityRole="alert">
            {ghostNote}
          </Text>
          <Pressable
            style={styles.dismiss}
            accessibilityRole="button"
            accessibilityLabel="Dismiss this message"
            onPress={() => setGhostNote(null)}
          >
            <Text style={styles.dismissText}>OK</Text>
          </Pressable>
        </View>
      ) : null}
      {contributions.outbox.notices.map((notice) => (
        <View key={notice.id} style={styles.notice}>
          <Text style={styles.noticeText} accessibilityRole="alert">
            {notice.message}
          </Text>
          <Pressable
            style={styles.dismiss}
            accessibilityRole="button"
            accessibilityLabel="Dismiss this message"
            onPress={() => contributions.dismiss(notice.id)}
          >
            <Text style={styles.dismissText}>OK</Text>
          </Pressable>
        </View>
      ))}
    </View>
  );
  if (!input.spot) return { header };

  const model = spotDetailModel({
    spot: input.spot,
    entry: input.entry,
    now: input.now,
    contributions: {
      ghost: contributions.ghost,
      journey: input.journeyId !== null,
      queued: contributions.outbox.entries.filter((item) => item.spotId === input.spot!.id),
    },
  });
  const content = (
    <>
      <SpotDetailContent
        {...model}
        busy={busy}
        message={message}
        onVote={(ref: string, vote: SpotVote) =>
          void run('vote', () => contributions.vote(ref, vote), 'Thanks for confirming.')
        }
        onReport={(ref) => setReporting(ref)}
        onBlock={(ref) =>
          Alert.alert(
            'Block this person?',
            "You won't see their posts at this Spot today. They won't be told.",
            [
              { text: 'Cancel', style: 'cancel' },
              {
                text: 'Block',
                style: 'destructive',
                onPress: () =>
                  void run(
                    'block',
                    () => contributions.block(ref),
                    "Blocked. You won't see their posts here today.",
                  ),
              },
            ],
          )
        }
        onDelete={(ref) =>
          Alert.alert('Delete your post?', 'It disappears for everyone right away.', [
            { text: 'Cancel', style: 'cancel' },
            {
              text: 'Delete',
              style: 'destructive',
              onPress: () =>
                void run('delete', () => contributions.deletePost(ref), 'Your post was deleted.'),
            },
          ])
        }
        onSignal={(category, value) =>
          void run(
            'queue',
            () => queue({ category: category as SpotCategory, value }),
            input.online ? 'Sending your update…' : 'Saved. It will send when you are back online.',
          )
        }
        onWrite={() => setWriting(true)}
      />
      <SpotReportSheet
        visible={reporting !== null}
        onClose={() => setReporting(null)}
        onPick={(reason: SpotReportReason) => {
          const ref = reporting;
          setReporting(null);
          if (ref)
            void run(
              'report',
              () => contributions.report(ref, reason),
              'Thanks. Moderators will review it.',
            );
        }}
      />
      <SpotPostComposer
        visible={writing}
        spotName={input.spot.name}
        defaultType={model.contribute?.defaultPostType ?? 'place'}
        onClose={() => setWriting(false)}
        onSubmit={async (text, type) => {
          try {
            await queue({ type, text });
            setMessage(
              input.online ? 'Sending your post…' : 'Saved. It will send when you are back online.',
            );
            return null;
          } catch (error) {
            return contributionMessage(error, 'queue');
          }
        }}
      />
    </>
  );
  return { header, content };
}

const styles = StyleSheet.create({
  notice: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 4 },
  noticeText: { flex: 1, color: tokens.colors.ink, fontSize: 15, lineHeight: 22 },
  dismiss: {
    minHeight: tokens.touchTarget,
    minWidth: tokens.touchTarget,
    alignItems: 'center',
    justifyContent: 'center',
  },
  dismissText: { color: tokens.colors.ink, fontSize: 15, fontWeight: '600' },
});
