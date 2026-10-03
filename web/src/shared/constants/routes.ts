/** Every path in the panel. Patterns for the router; builders for links. */
export const ROUTE_PATTERNS = {
  home: '/',
  login: '/login',
  plans: '/plans',
  planNew: '/plans/new',
  planDetail: '/plans/:id',
  planEdit: '/plans/:id/edit',
  subscribers: '/subscribers',
  subscriberNew: '/subscribers/new',
  subscriberDetail: '/subscribers/:id',
  subscriberEdit: '/subscribers/:id/edit',
  payments: '/payments',
  users: '/users',
  userNew: '/users/new',
  userEdit: '/users/:id/edit',
  system: '/system',
} as const;

const withId = (pattern: string) => (id: string) => pattern.replace(':id', encodeURIComponent(id));

export const ROUTES = {
  ...ROUTE_PATTERNS,
  planDetail: withId(ROUTE_PATTERNS.planDetail),
  planEdit: withId(ROUTE_PATTERNS.planEdit),
  subscriberDetail: withId(ROUTE_PATTERNS.subscriberDetail),
  subscriberEdit: withId(ROUTE_PATTERNS.subscriberEdit),
  userEdit: withId(ROUTE_PATTERNS.userEdit),
} as const;
