/** `me` is read by every guard and button; one request per five minutes at most. */
export const ME_STALE_TIME = 5 * 60_000;

/** Typing pauses this long before a search reaches the server. */
export const SEARCH_DEBOUNCE_MS = 300;

/**
 * Root of each feature's cache keys. Shared so a feature can invalidate
 * another's answers (a payment changes its subscriber) without importing it.
 */
export const QUERY_ROOTS = {
  me: 'me',
  plans: 'plans',
  subscribers: 'subscribers',
  payments: 'payments',
  users: 'users',
} as const;
