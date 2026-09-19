import type { LiveSignalAcceptance, LiveSignalChoice } from './browser-live';

type Category = LiveSignalChoice['categories'][number];
export const privateSignalOptions: Record<
  LiveSignalAcceptance['value'],
  readonly [Category, string]
> = {
  queue_under_5: ['queue', 'Queue · under 5 minutes'],
  queue_5_to_15: ['queue', 'Queue · 5–15 minutes'],
  queue_15_to_30: ['queue', 'Queue · 15–30 minutes'],
  queue_over_30: ['queue', 'Queue · over 30 minutes'],
  traffic_moving: ['traffic', 'Traffic · moving'],
  traffic_slow: ['traffic', 'Traffic · slow'],
  traffic_very_slow: ['traffic', 'Traffic · very slow'],
  traffic_stopped: ['traffic', 'Traffic · stopped'],
  parking_available: ['parking', 'Parking · available'],
  parking_filling: ['parking', 'Parking · filling'],
  parking_full: ['parking', 'Parking · full'],
  food_queue_none: ['food_queue', 'Food queue · none'],
  food_queue_short: ['food_queue', 'Food queue · short'],
  food_queue_long: ['food_queue', 'Food queue · long'],
  restroom_usable: ['restroom', 'Restroom · usable'],
  restroom_busy: ['restroom', 'Restroom · busy'],
  restroom_problem_reported: ['restroom', 'Restroom · problem observed'],
};
