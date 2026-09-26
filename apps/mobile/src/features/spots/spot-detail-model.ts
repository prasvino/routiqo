import {
  SPOT_SIGNAL_VALUES,
  agoLabel,
  defaultSpotPostType,
  spotCategoryLabel,
  spotSignalSummaryLabel,
  spotSignalValueLabel,
  stillTrueLabel,
  type QueuedSpotContribution,
  type Spot,
  type SpotActivityEntry,
  type SpotPostType,
  type SpotVote,
} from '@routiqo/shared';

/**
 * One Spot's content and controls (POSTS_AND_SIGNALS_SPEC, UI system "Journey and Spots
 * composition"): unattributed signal summaries, posts with alias and capture time, highlights,
 * one-tap signals and a composer entry. Pure, so every state is testable without React.
 */
export interface SignalSummaryView {
  ref: string;
  category: string;
  summary: string;
  stillTrue: string;
  viewerVote: SpotVote | null;
  /** Summaries may be voted on and reported, never blocked: they are unattributed. */
  canAct: boolean;
}

export interface PostView {
  ref: string;
  byline: string;
  text: string;
  stillTrue: string;
  viewerVote: SpotVote | null;
  mine: boolean;
  canAct: boolean;
  accessibilityLabel: string;
}

export interface SignalChoiceView {
  category: string;
  label: string;
  values: { value: string; label: string; accessibilityLabel: string }[];
}

export interface SpotDetailView {
  signals: SignalSummaryView[];
  posts: PostView[];
  postsNote: string | null;
  highlights: { text: string; when: string }[];
  /** Null when contributions are off in this build. */
  contribute: {
    notice: string | null;
    enabled: boolean;
    choices: SignalChoiceView[];
    defaultPostType: SpotPostType;
    waiting: string[];
  } | null;
}

export interface SpotDetailInput {
  spot: Pick<Spot, 'kind' | 'categories'>;
  entry: SpotActivityEntry | null;
  now: number;
  contributions: {
    ghost: boolean | null;
    queued: readonly QueuedSpotContribution[];
    /** A journey exists on this device to attach contributions to. */
    journey: boolean;
  } | null;
}

const clip = (text: string, max = 40) => {
  const points = [...text];
  return points.length > max ? `${points.slice(0, max - 1).join('')}…` : text;
};

export function spotDetailModel(input: SpotDetailInput): SpotDetailView {
  const acting = input.contributions !== null && input.contributions.ghost === false;
  const entry = input.entry;
  const signals = (entry?.signals ?? []).map((summary): SignalSummaryView => ({
    ref: summary.ref,
    category: spotCategoryLabel(summary.category),
    summary: spotSignalSummaryLabel(summary, input.now),
    stillTrue: stillTrueLabel(summary.stillTrue),
    viewerVote: summary.viewerVote,
    canAct: acting,
  }));
  // Posts past their expiry are never shown, even from an older response.
  const posts = (entry?.posts ?? [])
    .filter((post) => Date.parse(post.expiresAt) > input.now)
    .map((post): PostView => {
      const byline = `${post.mine ? 'You' : post.alias} · ${agoLabel(post.capturedAt, input.now)}`;
      return {
        ref: post.ref,
        byline,
        text: post.text,
        stillTrue: stillTrueLabel(post.stillTrue),
        viewerVote: post.viewerVote,
        mine: post.mine,
        canAct: acting,
        accessibilityLabel: `${byline}. ${post.text}. ${stillTrueLabel(post.stillTrue)}`,
      };
    });
  const highlights = (entry?.highlights ?? []).map((highlight) => ({
    text: highlight.text,
    when: `Traveller tip · ${agoLabel(highlight.createdAt, input.now)}`,
  }));
  const postsNote = entry?.postsTruncated
    ? 'Older posts are not shown.'
    : entry && posts.length === 0 && signals.length === 0
      ? 'No recent posts or reports here.'
      : null;

  let contribute: SpotDetailView['contribute'] = null;
  if (input.contributions) {
    const { ghost, journey, queued } = input.contributions;
    const notice =
      ghost === null
        ? 'Checking Ghost Mode…'
        : ghost
          ? 'Ghost Mode is on. Nothing is sent from this phone.'
          : !journey
            ? 'Posting needs an active journey.'
            : null;
    contribute = {
      notice,
      enabled: ghost === false && journey,
      choices: input.spot.categories.map((category) => ({
        category,
        label: spotCategoryLabel(category),
        values: SPOT_SIGNAL_VALUES[category].map((value) => ({
          value,
          label: spotSignalValueLabel(value),
          accessibilityLabel: `Report ${spotCategoryLabel(category)}: ${spotSignalValueLabel(value)}`,
        })),
      })),
      defaultPostType: defaultSpotPostType(input.spot.kind),
      waiting: queued.map((item) =>
        item.kind === 'signal'
          ? `Waiting to send: ${spotCategoryLabel(item.category)} ${spotSignalValueLabel(item.value)}`
          : `Waiting to send: “${clip(item.text)}”`,
      ),
    };
  }
  return { signals, posts, postsNote, highlights, contribute };
}
